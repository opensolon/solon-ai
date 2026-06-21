package org.noear.solon.ai.loop.strategy;

import org.noear.solon.ai.loop.engine.IterationResult;
import org.noear.solon.ai.loop.prd.*;
import org.noear.solon.ai.loop.progress.ProgressEntry;
import org.noear.solon.ai.loop.progress.ProgressManager;
import org.noear.solon.ai.loop.state.LoopState;
import org.noear.solon.ai.loop.state.LoopStateData;
import org.noear.solon.ai.loop.state.disk.DiskStateManager;
import org.noear.solon.ai.loop.validator.Validator;
import org.noear.solon.ai.loop.validator.ValidationCriteria;
import org.noear.solon.ai.loop.validator.verify.ArchitectVerifier;
import org.noear.solon.ai.loop.validator.verify.CriticVerifier;
import org.noear.solon.ai.loop.validator.verify.VerificationState;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;

/**
 * Ralph循环策略 —— 对标 oh-my-claudecode 的 ralph/loop.ts。
 *
 * <p>PRD驱动的持久化循环，直到所有用户故事完成并验证。
 * 重写后集成了完整的 PRD 系统、验证状态机和进度记忆系统。</p>
 *
 * <p>循环流程：</p>
 * <ol>
 *   <li>从 PRD 中获取下一个待实现的故事（按优先级排序）</li>
 *   <li>实现故事（使用注入的 StoryImplementor 或默认占位实现）</li>
 *   <li>验证故事（Architect 验证 + 可选的 Critic 评审）</li>
 *   <li>记录进度（实现内容、文件变更、经验教训）</li>
 *   <li>重复直到所有故事完成并通过 Architect 验证</li>
 * </ol>
 *
 * @since 4.0.3
 */
public class RalphLoopStrategy extends AbstractLoopStrategy {

    public static final String NAME = "ralph";
    public static final String DESCRIPTION = "PRD驱动的持久化循环，直到所有用户故事完成并验证";
    public static final String DEFAULT_CRITIC_MODE = "architect";

    private final boolean verificationRequired;
    private final String criticMode;
    private final StoryImplementor storyImplementor;
    private final StoryValidator storyValidator;
    private final Validator validator;

    // 新增：PRD 系统组件
    private PRDFileManager prdFileManager;
    private PRDStatusCalculator statusCalculator;
    private ProgressManager progressManager;
    private ArchitectVerifier architectVerifier;
    private CriticVerifier criticVerifier;
    private DiskStateManager diskStateManager;
    private VerificationState verificationState;

    public RalphLoopStrategy() {
        this(true, DEFAULT_CRITIC_MODE, 50, null, null, null);
    }

    public RalphLoopStrategy(boolean verificationRequired, String criticMode, int maxIterations,
                             StoryImplementor storyImplementor, StoryValidator storyValidator,
                             Validator validator) {
        super(NAME, DESCRIPTION, maxIterations, false);
        this.verificationRequired = verificationRequired;
        this.criticMode = criticMode;
        this.storyImplementor = storyImplementor;
        this.storyValidator = storyValidator;
        this.validator = validator;
    }

    /**
     * 注入 PRD 系统组件（由引擎初始化时调用）。
     */
    public void injectPrdComponents(PRDFileManager prdFileManager,
                                     PRDStatusCalculator statusCalculator,
                                     ProgressManager progressManager,
                                     DiskStateManager diskStateManager) {
        this.prdFileManager = prdFileManager;
        this.statusCalculator = statusCalculator;
        this.progressManager = progressManager;
        this.diskStateManager = diskStateManager;
        this.architectVerifier = new ArchitectVerifier();
        this.criticVerifier = new CriticVerifier(this.criticMode);
        this.verificationState = new VerificationState();
    }

    /**
     * 获取 Team Pipeline 阶段指令。
     * 当 Ralph 与 Team Pipeline 协同运行时，根据 Team 的当前阶段决定 Ralph 的行为。
     *
     * @param sessionId 会话 ID
     * @return "continue" 表示继续循环，"complete" 表示应结束循环，null 表示无 Team 状态
     */
    public String getTeamPhaseDirective(String sessionId) {
        if (diskStateManager == null || sessionId == null) return null;

        // 读取 Team 状态
        LoopStateData teamState = diskStateManager.readState("team", sessionId);
        if (teamState == null) return null;  // 无 Team 状态，Ralph 独立运行

        // 检查 Team 是否活跃
        String teamPhase = teamState.getContextData() != null
                ? (String) teamState.getContextData().get("currentPhase")
                : null;

        if (teamPhase == null) return null;

        // 终态检查
        if ("COMPLETED".equals(teamPhase) || "FAILED".equals(teamPhase)) {
            return "complete";
        }

        // 活跃阶段检查
        if ("VERIFY".equals(teamPhase) || "FIX".equals(teamPhase)
                || "EXEC".equals(teamPhase) || "PLAN".equals(teamPhase)
                || "PRD".equals(teamPhase)) {
            return "continue";
        }

        return null;
    }

    @Override
    protected boolean shouldContinueInternal(LoopContext context) {
        // 从 PRD 中检查是否还有未完成的故事
        String sessionId = getSessionId(context);
        if (sessionId == null || prdFileManager == null) return false;

        // 检查 Team Pipeline 指令
        String teamDirective = getTeamPhaseDirective(sessionId);
        if ("complete".equals(teamDirective)) {
            return false;
        }

        PRDDocument prd = prdFileManager.readPrd(sessionId);
        if (prd == null) return false;

        // 统一使用 allStoriesFullyComplete（与 executeIteration 一致）
        if (verificationRequired) {
            return !prd.allStoriesFullyComplete();
        }
        return !prd.allStoriesCompleted();
    }

    @Override
    protected IterationResult executeIterationInternal(LoopContext context) {
        Instant startTime = Instant.now();
        String sessionId = getSessionId(context);

        // PRD 未初始化时的降级行为
        if (sessionId == null || prdFileManager == null) {
            return executeFallbackIteration(context, startTime);
        }

        PRDDocument prd = prdFileManager.readPrd(sessionId);
        if (prd == null) {
            return createResult(context, startTime, null, false,
                    "No PRD found. Initialize PRD first.");
        }

        // 1. 获取下一个未完成的故事
        UserStory nextStory = prd.getNextIncompleteStory();
        if (nextStory == null) {
            return createResult(context, startTime, null, true,
                    "All stories completed", LoopState.COMPLETED);
        }

        // 2. 实现故事
        Object result = implementStory(nextStory, context);

        // 3. 验证故事
        boolean validationPassed = true;
        String message = "Story implemented: " + nextStory.getId();

        if (verificationRequired) {
            validationPassed = validateStory(nextStory, result, context, sessionId);
            message = validationPassed
                    ? "Story implemented and verified: " + nextStory.getId()
                    : "Story implementation failed verification: " + nextStory.getId();
        }

        // 4. 更新 PRD 和进度
        if (validationPassed) {
            prdFileManager.markStoryComplete(sessionId, nextStory.getId(),
                    "Implemented in iteration " + context.getIterationCount());

            // Architect 自动验证（如果验证通过且需要验证）
            if (verificationRequired) {
                prdFileManager.markStoryArchitectVerified(sessionId, nextStory.getId());
            }

            // 记录进度
            recordProgress(sessionId, nextStory, result, context);

            message = "Story completed and verified: " + nextStory.getId();
        }

        // 5. 检查是否全部完成（与 shouldContinueInternal 保持一致）
        boolean allDone = verificationRequired ? prd.allStoriesFullyComplete() : prd.allStoriesCompleted();

        // 6. 使用 statusCalculator 更新 PRD 状态到上下文
        if (statusCalculator != null && prd != null) {
            PRDStatus status = statusCalculator.calculate(prd);
            Map<String, Object> ctxData = context.getContextData();
            if (ctxData != null) {
                ctxData.put("prdStatus", status.getSummary());
                ctxData.put("prdTotalStories", status.getTotal());
                ctxData.put("prdCompletedStories", status.getCompleted());
            }
        }

        return createResult(context, startTime, result, validationPassed,
                message, allDone ? LoopState.COMPLETED :
                        validationPassed ? LoopState.EXECUTING : LoopState.FIXING);
    }

    /**
     * 降级执行（PRD 未初始化时使用原始逻辑）。
     */
    private IterationResult executeFallbackIteration(LoopContext context, Instant startTime) {
        String story = getFallbackNextStory(context);
        if (story == null) {
            return createResult(context, startTime, null, true,
                    "All stories completed (fallback)", LoopState.COMPLETED);
        }

        Object result = implementFallbackStory(story, context);
        boolean validationPassed = true;
        String message = "Story implemented: " + story;

        if (verificationRequired && validator != null) {
            ValidationCriteria criteria = ValidationCriteria.simple(
                    "story-validation-" + story, Collections.singletonList(story));
            validationPassed = validator.validate(result, criteria).isPassed();
            message = validationPassed
                    ? "Story implemented and verified: " + story
                    : "Story implementation failed verification: " + story;
        }

        if (validationPassed) {
            markFallbackStoryCompleted(story, context);
        }

        return createResult(context, startTime, result, validationPassed, message,
                validationPassed ? LoopState.EXECUTING : LoopState.FIXING);
    }

    // ===== PRD 驱动的故事方法 =====

    private Object implementStory(UserStory story, LoopContext context) {
        if (storyImplementor != null) {
            return storyImplementor.apply(story.getId() + ": " + story.getTitle(), context);
        }
        return "Implemented: " + story.getId() + " " + story.getTitle();
    }

    private boolean validateStory(UserStory story, Object result, LoopContext context, String sessionId) {
        if (storyValidator != null) {
            return storyValidator.apply(story.getId() + ": " + story.getTitle(), result, context);
        }

        // 启动验证状态
        if (verificationState != null) {
            verificationState.startVerification(
                "Verify story: " + story.getId(),
                story.getId(),
                criticMode);
            verificationState.setCompletionClaim("Story " + story.getId() + " implemented");
        }

        // Architect 验证
        ArchitectVerifier.VerificationResult archResult =
                architectVerifier.verify(story, result, context);

        if (verificationState != null) {
            boolean verified = verificationState.recordArchitectFeedback(
                archResult.isPassed(),
                archResult.getMessage());

            if (!archResult.isPassed() && !verified) {
                // 验证失败但未达最大尝试次数
                context.getContextData().put("verificationState", verificationState.getCurrentState());
                context.getContextData().put("verificationAttempts", verificationState.getVerificationAttempts());
                return false;
            }

            if (!archResult.isPassed() && verified) {
                // 达到最大尝试次数，强制失败
                context.getContextData().put("verificationState", verificationState.getCurrentState());
                return false;
            }
        } else if (!archResult.isPassed()) {
            return false;
        }

        // Critic 评审
        CriticVerifier.CriticResult criticResult =
                criticVerifier.review(story, result, context);

        if (verificationState != null && criticResult.isApproved()) {
            verificationState.setCurrentState(VerificationState.STATE_CRITIC_APPROVED);
            context.getContextData().put("verificationState", verificationState.getCurrentState());
        }

        return criticResult.isApproved();
    }

    private void recordProgress(String sessionId, UserStory story, Object result, LoopContext context) {
        if (progressManager == null) return;

        ProgressEntry entry = new ProgressEntry(story.getId());
        entry.addImplementation("Implemented: " + story.getTitle());

        // 从上下文获取文件变更信息
        @SuppressWarnings("unchecked")
        List<String> filesChanged = (List<String>) context.get("filesChanged");
        if (filesChanged != null) {
            filesChanged.forEach(entry::addFileChanged);
        }

        entry.addLearning("Completed story " + story.getId() +
                ". Acceptance criteria: " + story.getAcceptanceCriteria());

        progressManager.appendProgress(sessionId, entry);
    }

    // ===== fallback 兼容方法 =====

    @SuppressWarnings("unchecked")
    private String getFallbackNextStory(LoopContext context) {
        List<String> allStories = (List<String>) context.get("stories");
        List<String> completedStories = (List<String>) context.get("completedStories");
        if (allStories == null) return null;

        List<String> uncompleted = new ArrayList<>(allStories);
        if (completedStories != null) uncompleted.removeAll(completedStories);
        return uncompleted.isEmpty() ? null : uncompleted.get(0);
    }

    private Object implementFallbackStory(String story, LoopContext context) {
        if (storyImplementor != null) return storyImplementor.apply(story, context);
        return "Implemented: " + story;
    }

    @SuppressWarnings("unchecked")
    private void markFallbackStoryCompleted(String story, LoopContext context) {
        Map<String, Object> contextData = context.getContextData();
        if (contextData == null) {
            // LoopContext 没有 setContextData 方法，contextData 为 null 时无法写回
            // 正常情况下 contextData 不应为 null（在 LoopContext 构造时初始化）
            return;
        }
        List<String> completedStories = (List<String>) contextData.get("completedStories");
        if (completedStories == null) {
            completedStories = new ArrayList<>();
            contextData.put("completedStories", completedStories);
        }
        completedStories.add(story);
    }

    // ===== 辅助方法 =====

    private String getSessionId(LoopContext context) {
        return (String) context.get("sessionId");
    }

    private IterationResult createResult(LoopContext context, Instant startTime,
                                          Object result, boolean success, String message) {
        return createResult(context, startTime, result, success, message,
                success ? LoopState.EXECUTING : LoopState.FIXING);
    }

    private IterationResult createResult(LoopContext context, Instant startTime,
                                          Object result, boolean success, String message,
                                          LoopState state) {
        Instant endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("criticMode", criticMode);
        metadata.put("verificationRequired", verificationRequired);
        if (result != null) {
            metadata.put("hasResult", true);
        }

        return new IterationResult(
                context.getIterationCount() + 1,
                context.getMaxIterations(),
                state,
                result,
                success,
                message,
                duration,
                startTime,
                endTime,
                metadata
        );
    }

    // ===== Getters =====

    public boolean isVerificationRequired() { return verificationRequired; }
    public String getCriticMode() { return criticMode; }

    // ===== Builder =====

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private boolean verificationRequired = true;
        private String criticMode = DEFAULT_CRITIC_MODE;
        private int maxIterations = 50;
        private StoryImplementor storyImplementor;
        private StoryValidator storyValidator;
        private Validator validator;

        public Builder verificationRequired(boolean verificationRequired) {
            this.verificationRequired = verificationRequired; return this;
        }
        public Builder criticMode(String criticMode) {
            this.criticMode = criticMode; return this;
        }
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations; return this;
        }
        public Builder storyImplementor(StoryImplementor storyImplementor) {
            this.storyImplementor = storyImplementor; return this;
        }
        public Builder storyValidator(StoryValidator storyValidator) {
            this.storyValidator = storyValidator; return this;
        }
        public Builder validator(Validator validator) {
            this.validator = validator; return this;
        }
        public RalphLoopStrategy build() {
            return new RalphLoopStrategy(verificationRequired, criticMode, maxIterations,
                    storyImplementor, storyValidator, validator);
        }
    }

    @FunctionalInterface
    public interface StoryImplementor extends BiFunction<String, LoopContext, Object> {}

    @FunctionalInterface
    public interface StoryValidator extends TriFunction<String, Object, LoopContext, Boolean> {}

    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }
}
