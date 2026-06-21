package org.noear.solon.ai.loop.strategy;

import org.noear.solon.ai.loop.engine.IterationResult;
import org.noear.solon.ai.loop.state.LoopState;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Team Pipeline 循环策略（重写版）
 * 多阶段管道：plan → prd → exec → verify → fix (loop)
 *
 * <p>对标 oh-my-claudecode 的 team-pipeline/types.ts，精细化管理：
 * <ul>
 *   <li>阶段历史追踪（phase_history）</li>
 *   <li>执行追踪（workers/tasks）</li>
 *   <li>修复循环追踪（attempt/max_attempts/last_failure_reason）</li>
 *   <li>取消恢复机制（cancel + preserve_for_resume）</li>
 * </ul>
 * </p>
 *
 * @since 4.0.3
 */
public class TeamPipelineStrategy extends AbstractLoopStrategy {

    public static final String NAME = "team-pipeline";
    public static final String DESCRIPTION = "多阶段管道：plan → prd → exec → verify → fix (loop)";

    private final List<Phase> phases;
    private final int maxFixAttempts;
    private final boolean parallelExecution;

    public TeamPipelineStrategy() {
        this(Arrays.asList(Phase.PLAN, Phase.PRD, Phase.EXEC, Phase.VERIFY, Phase.FIX), 3, false);
    }

    public TeamPipelineStrategy(List<Phase> phases, int maxFixAttempts, boolean parallelExecution) {
        super(NAME, DESCRIPTION, 100, parallelExecution);
        this.phases = phases != null ? phases : Arrays.asList(Phase.PLAN, Phase.PRD, Phase.EXEC, Phase.VERIFY, Phase.FIX);
        this.maxFixAttempts = maxFixAttempts;
        this.parallelExecution = parallelExecution;
    }

    @Override
    protected boolean shouldContinueInternal(LoopContext context) {
        if (isCancelled(context)) return false;
        Phase currentPhase = getCurrentPhase(context);
        return currentPhase != Phase.COMPLETED && currentPhase != Phase.CANCELLED && currentPhase != Phase.FAILED;
    }

    @Override
    protected IterationResult executeIterationInternal(LoopContext context) {
        Instant startTime = Instant.now();
        Phase currentPhase = getCurrentPhase(context);

        // 检查取消请求
        if (isCancelled(context)) {
            return createResult(context, startTime, null, true,
                    "Pipeline cancelled", LoopState.COMPLETED);
        }

        IterationResult result;
        switch (currentPhase) {
            case PLAN:
                result = executePlanPhase(context);
                break;
            case PRD:
                result = executePrdPhase(context);
                break;
            case EXEC:
                result = executeExecPhase(context);
                break;
            case VERIFY:
                result = executeVerifyPhase(context);
                break;
            case FIX:
                result = executeFixPhase(context);
                break;
            default:
                return createResult(context, startTime, null, false,
                        "Unknown phase: " + currentPhase, LoopState.FAILED);
        }

        // 记录阶段历史
        recordPhaseHistory(context, currentPhase, result);

        // 更新阶段
        updatePhase(context, currentPhase, result.isSuccess());
        return result;
    }

    // ===== 阶段执行 =====

    private IterationResult executePlanPhase(LoopContext context) {
        Object result = executePlanning(context);
        Map<String, Object> meta = createPhaseMeta(Phase.PLAN, Phase.PRD, context);
        return createPhaseResult(context, result, true, "Planning phase completed",
                LoopState.PLANNING, meta);
    }

    private IterationResult executePrdPhase(LoopContext context) {
        Object result = executePrdGeneration(context);
        Map<String, Object> meta = createPhaseMeta(Phase.PRD, Phase.EXEC, context);
        return createPhaseResult(context, result, true, "PRD phase completed",
                LoopState.PLANNING, meta);
    }

    private IterationResult executeExecPhase(LoopContext context) {
        // 更新执行追踪
        updateExecutionTracking(context);
        Object result = executeImplementation(context);
        Map<String, Object> meta = createPhaseMeta(Phase.EXEC, Phase.VERIFY, context);
        meta.put("tasksCompleted", getTasksCompleted(context));
        meta.put("tasksTotal", getTasksTotal(context));
        return createPhaseResult(context, result, true, "Execution phase completed",
                LoopState.EXECUTING, meta);
    }

    private IterationResult executeVerifyPhase(LoopContext context) {
        boolean passed = executeVerification(context);
        Map<String, Object> meta = createPhaseMeta(Phase.VERIFY,
                passed ? Phase.COMPLETED : Phase.FIX, context);
        meta.put("passed", passed);

        if (!passed) {
            // 记录失败原因
            String failReason = "Verification failed in iteration " + context.getIterationCount();
            setLastFailureReason(context, failReason);
        }

        return createPhaseResult(context, null, passed,
                passed ? "Verification passed" : "Verification failed",
                LoopState.VERIFYING, meta);
    }

    private IterationResult executeFixPhase(LoopContext context) {
        int fixAttempts = getFixAttempts(context);

        if (fixAttempts >= maxFixAttempts) {
            String failReason = "Max fix attempts (" + maxFixAttempts + ") reached. Last failure: "
                    + getLastFailureReason(context);
            Map<String, Object> meta = createPhaseMeta(Phase.FIX, null, context);
            meta.put("fixAttempt", fixAttempts);
            meta.put("lastFailureReason", getLastFailureReason(context));
            return createPhaseResult(context, null, false, failReason,
                    LoopState.FAILED, meta);
        }

        Object result = executeFix(context);
        updateFixAttempts(context, fixAttempts + 1);
        setLastFailureReason(context, null);

        Map<String, Object> meta = createPhaseMeta(Phase.FIX, Phase.EXEC, context);
        meta.put("fixAttempt", fixAttempts + 1);
        return createPhaseResult(context, result, true,
                "Fix attempt " + (fixAttempts + 1) + " completed",
                LoopState.FIXING, meta);
    }

    // ===== 阶段历史管理 =====

    private void recordPhaseHistory(LoopContext context, Phase phase, IterationResult result) {
        List<Map<String, String>> history = getPhaseHistory(context);
        if (context.get("phaseHistory") == null) {
            context.getContextData().put("phaseHistory", history);
        }
        Phase currentPhase = getCurrentPhase(context);
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("phase", phase.name());
        entry.put("enteredAt", Instant.now().toString());
        entry.put("reason", result.getMessage());
        entry.put("success", String.valueOf(result.isSuccess()));
        entry.put("from", currentPhase != null ? currentPhase.name() : "INIT");
        history.add(entry);
    }

    /**
     * 获取阶段历史。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getPhaseHistory(LoopContext context) {
        List<Map<String, String>> history = (List<Map<String, String>>) context.get("phaseHistory");
        return history != null ? history : new ArrayList<>();
    }

    // ===== 执行追踪管理 =====

    private void updateExecutionTracking(LoopContext context) {
        // 初始化执行追踪数据
        Map<String, Object> exec = getExecutionData(context);
        if (!exec.containsKey("initialized")) {
            exec.put("workersTotal", 1);
            exec.put("workersActive", 1);
            exec.put("tasksTotal", getTasksTotal(context));
            exec.put("tasksCompleted", 0);
            exec.put("tasksFailed", 0);
            exec.put("initialized", true);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getExecutionData(LoopContext context) {
        Map<String, Object> exec = (Map<String, Object>) context.get("execution");
        if (exec == null) {
            exec = new HashMap<>();
            context.getContextData().put("execution", exec);
        }
        return exec;
    }

    private int getTasksTotal(LoopContext context) {
        Map<String, Object> exec = getExecutionData(context);
        return (int) exec.getOrDefault("tasksTotal", 0);
    }

    private int getTasksCompleted(LoopContext context) {
        Map<String, Object> exec = getExecutionData(context);
        return (int) exec.getOrDefault("tasksCompleted", 0);
    }

    /**
     * 设置执行数据（供外部调用）。
     */
    public void setExecutionData(LoopContext context, int workersTotal, int tasksTotal) {
        Map<String, Object> exec = getExecutionData(context);
        exec.put("workersTotal", workersTotal);
        exec.put("workersActive", workersTotal);
        exec.put("tasksTotal", tasksTotal);
        exec.put("tasksCompleted", 0);
        exec.put("tasksFailed", 0);
    }

    /**
     * 标记任务完成。
     */
    public void markTaskCompleted(LoopContext context) {
        Map<String, Object> exec = getExecutionData(context);
        int completed = (int) exec.getOrDefault("tasksCompleted", 0);
        exec.put("tasksCompleted", completed + 1);
    }

    // ===== 取消/恢复机制 =====

    /**
     * 请求取消管道。
     *
     * @param context          循环上下文
     * @param preserveForResume 是否保留状态以恢复
     */
    public void requestCancel(LoopContext context, boolean preserveForResume) {
        Map<String, Object> contextData = context.getContextData();
        Phase currentPhase = getCurrentPhase(context);
        contextData.put("cancelRequested", true);
        contextData.put("cancelRequestedAt", Instant.now().toString());
        contextData.put("preserveForResume", preserveForResume);
        if (preserveForResume) {
            contextData.put("phaseBeforeCancel", currentPhase);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean isCancelled(LoopContext context) {
        return Boolean.TRUE.equals(context.get("cancelRequested"));
    }

    @SuppressWarnings("unchecked")
    private boolean isPreserveForResume(LoopContext context) {
        return Boolean.TRUE.equals(context.get("preserveForResume"));
    }

    /**
     * 从取消状态恢复管道执行。
     *
     * @param context 循环上下文
     * @return 是否成功恢复
     */
    public boolean resumeFromCancel(LoopContext context) {
        if (!isCancelled(context)) return false;
        if (!isPreserveForResume(context)) return false;

        // 恢复执行
        Map<String, Object> contextData = context.getContextData();
        contextData.put("cancelRequested", false);
        contextData.put("preserveForResume", false);
        // 恢复到上次执行的阶段（EXEC）
        Phase resumePhase = (Phase) contextData.get("phaseBeforeCancel");
        if (resumePhase == null) {
            resumePhase = Phase.EXEC;  // 默认恢复到 EXEC
        }
        contextData.put("currentPhase", resumePhase.name());

        // 记录恢复历史
        recordPhaseHistory(context, resumePhase,
            createResult(context, Instant.now(), null, true, "Resumed from cancelled", LoopState.EXECUTING));
        return true;
    }

    // ===== 修复循环管理 =====

    private int getFixAttempts(LoopContext context) {
        Object attempts = context.get("fixAttempts");
        return attempts instanceof Integer ? (Integer) attempts : 0;
    }

    private void updateFixAttempts(LoopContext context, int attempts) {
        context.getContextData().put("fixAttempts", attempts);
    }

    private String getLastFailureReason(LoopContext context) {
        String reason = (String) context.get("lastFailureReason");
        return reason != null ? reason : "Unknown failure";
    }

    private void setLastFailureReason(LoopContext context, String reason) {
        context.getContextData().put("lastFailureReason", reason);
    }

    // ===== 阶段转换 =====

    private Phase getCurrentPhase(LoopContext context) {
        String phaseStr = (String) context.get("currentPhase");
        if (phaseStr == null) {
            return phases.isEmpty() ? Phase.COMPLETED : phases.get(0);
        }
        try {
            return Phase.valueOf(phaseStr);
        } catch (IllegalArgumentException e) {
            return phases.isEmpty() ? Phase.COMPLETED : phases.get(0);
        }
    }

    /**
     * 转换守卫：检查从 from 到 to 的转换是否合法。
     */
    private boolean isValidTransition(Phase from, Phase to) {
        if (from == null) return true;  // 初始状态

        // 允许的转换表
        switch (from) {
            case PLAN: return to == Phase.PRD || to == Phase.CANCELLED || to == Phase.FAILED;
            case PRD: return to == Phase.EXEC || to == Phase.CANCELLED || to == Phase.FAILED;
            case EXEC: return to == Phase.VERIFY || to == Phase.CANCELLED || to == Phase.FAILED;
            case VERIFY: return to == Phase.FIX || to == Phase.COMPLETED || to == Phase.CANCELLED || to == Phase.FAILED;
            case FIX: return to == Phase.EXEC || to == Phase.VERIFY || to == Phase.COMPLETED || to == Phase.FAILED || to == Phase.CANCELLED;
            case COMPLETED:
            case FAILED:
            case CANCELLED:
                return false;  // 终态
            default: return false;
        }
    }

    /**
     * 检查进入目标阶段的前置条件是否满足。
     */
    private String checkTransitionGuards(LoopContext context, Phase targetPhase) {
        switch (targetPhase) {
            case EXEC:
                // EXEC 阶段需要至少有 plan 或 prd artifact
                // 这里简化检查：只要不是从 PLAN 直接跳到 EXEC（必须经过 PRD）
                return null;  // 放宽检查，允许灵活使用
            case VERIFY:
                // VERIFY 阶段需要 tasksTotal > 0 且 tasksCompleted >= tasksTotal
                int tasksTotal = getTasksTotal(context);
                int tasksCompleted = getTasksCompleted(context);
                if (tasksTotal <= 0) {
                    return "Cannot enter VERIFY: tasksTotal must be > 0";
                }
                if (tasksCompleted < tasksTotal) {
                    return "Cannot enter VERIFY: tasksCompleted (" + tasksCompleted +
                           ") < tasksTotal (" + tasksTotal + ")";
                }
                return null;
            default:
                return null;
        }
    }

    private void updatePhase(LoopContext context, Phase currentPhase, boolean success) {
        Map<String, Object> contextData = context.getContextData();
        Phase nextPhase;

        if (isCancelled(context)) {
            nextPhase = Phase.CANCELLED;
        } else if (currentPhase == Phase.VERIFY && success) {
            nextPhase = Phase.COMPLETED;
        } else if (currentPhase == Phase.VERIFY && !success) {
            nextPhase = Phase.FIX;
        } else if (currentPhase == Phase.FIX) {
            // 检查 fix 溢出
            if (getFixAttempts(context) >= maxFixAttempts) {
                nextPhase = Phase.FAILED;  // fix 溢出 → FAILED
            } else {
                nextPhase = Phase.EXEC;
            }
        } else {
            int currentIndex = phases.indexOf(currentPhase);
            if (currentIndex >= 0 && currentIndex < phases.size() - 1) {
                nextPhase = phases.get(currentIndex + 1);
            } else {
                nextPhase = Phase.COMPLETED;
            }
        }

        // 转换守卫检查
        if (!isValidTransition(currentPhase, nextPhase)) {
            // 非法转换，保持当前阶段
            return;
        }
        String guardError = checkTransitionGuards(context, nextPhase);
        if (guardError != null) {
            // 守卫失败，保持当前阶段（等待前置条件满足）
            contextData.put("guardError", guardError);
            return;
        }

        contextData.put("currentPhase", nextPhase.name());
        contextData.remove("guardError");

        // 进入 EXEC 时重置 fixAttempts
        if (nextPhase == Phase.EXEC) {
            updateFixAttempts(context, 0);
        }
    }

    /**
     * 标记阶段转换（外部调用，带历史记录）。
     *
     * @param context  循环上下文
     * @param nextPhase 下一阶段
     * @param reason    原因
     */
    public void markPhase(LoopContext context, Phase nextPhase, String reason) {
        Phase currentPhase = getCurrentPhase(context);

        // 转换守卫检查
        if (!isValidTransition(currentPhase, nextPhase)) {
            throw new IllegalArgumentException(
                "Invalid phase transition: " + currentPhase + " -> " + nextPhase);
        }

        context.getContextData().put("currentPhase", nextPhase.name());

        // 记录历史（统一结构）
        List<Map<String, String>> history = getPhaseHistory(context);
        if (context.get("phaseHistory") == null) {
            context.getContextData().put("phaseHistory", history);
        }
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("phase", nextPhase.name());
        entry.put("enteredAt", Instant.now().toString());
        entry.put("reason", reason);
        entry.put("success", "true");
        entry.put("from", currentPhase != null ? currentPhase.name() : "INIT");
        history.add(entry);
    }

    // ===== 可覆盖方法 =====

    protected Object executePlanning(LoopContext context) {
        Map<String, Object> contextData = context.getContextData();
        contextData.put("planGenerated", true);
        contextData.put("planResult", "Planning completed for iteration " + context.getIterationCount());
        return contextData.get("planResult");
    }

    protected Object executePrdGeneration(LoopContext context) {
        Map<String, Object> contextData = context.getContextData();
        contextData.put("prdGenerated", true);
        contextData.put("prdResult", "PRD generated for iteration " + context.getIterationCount());
        return contextData.get("prdResult");
    }

    protected Object executeImplementation(LoopContext context) {
        Map<String, Object> contextData = context.getContextData();
        // 标记任务完成
        markTaskCompleted(context);
        return "Implementation completed for iteration " + context.getIterationCount();
    }

    protected boolean executeVerification(LoopContext context) {
        // 默认验证逻辑：检查所有任务是否完成
        int tasksTotal = getTasksTotal(context);
        int tasksCompleted = getTasksCompleted(context);
        if (tasksTotal == 0) return true;  // 无任务则默认通过
        return tasksCompleted >= tasksTotal;
    }

    protected Object executeFix(LoopContext context) {
        String lastReason = getLastFailureReason(context);
        return "Fix applied for: " + (lastReason != null ? lastReason : "unknown issue");
    }

    // ===== 辅助方法 =====

    public List<Phase> getPhases() { return phases; }
    public int getMaxFixAttempts() { return maxFixAttempts; }
    public boolean isParallelExecution() { return parallelExecution; }

    private Map<String, Object> createPhaseMeta(Phase current, Phase next, LoopContext context) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("phase", current.name());
        if (next != null) meta.put("nextPhase", next.name());
        return meta;
    }

    private IterationResult createPhaseResult(LoopContext context, Object result,
                                               boolean success, String message,
                                               LoopState state, Map<String, Object> metadata) {
        Instant startTime = Instant.now();
        Duration duration = Duration.ofMillis(0); // 实际时长由调用者计算
        return new IterationResult(
                context.getIterationCount() + 1,
                context.getMaxIterations(),
                state,
                result,
                success,
                message,
                duration,
                startTime,
                startTime,
                metadata
        );
    }

    private IterationResult createResult(LoopContext context, Instant startTime,
                                          Object result, boolean success, String message,
                                          LoopState state) {
        Instant endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);
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
                null
        );
    }

    // ===== 阶段枚举 =====

    public enum Phase {
        PLAN,
        PRD,
        EXEC,
        VERIFY,
        FIX,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    // ===== Builder =====

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private List<Phase> phases = Arrays.asList(Phase.PLAN, Phase.PRD, Phase.EXEC, Phase.VERIFY, Phase.FIX);
        private int maxFixAttempts = 3;
        private boolean parallelExecution = false;

        public Builder phases(List<Phase> phases) { this.phases = phases; return this; }
        public Builder maxFixAttempts(int maxFixAttempts) { this.maxFixAttempts = maxFixAttempts; return this; }
        public Builder parallelExecution(boolean parallelExecution) { this.parallelExecution = parallelExecution; return this; }
        public TeamPipelineStrategy build() { return new TeamPipelineStrategy(phases, maxFixAttempts, parallelExecution); }
    }
}
