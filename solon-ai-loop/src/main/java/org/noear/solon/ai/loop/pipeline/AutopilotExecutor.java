package org.noear.solon.ai.loop.pipeline;

import org.noear.solon.ai.loop.engine.LoopEngine;
import org.noear.solon.ai.loop.engine.LoopResult;
import org.noear.solon.ai.loop.engine.LoopSession;
import org.noear.solon.ai.loop.strategy.LoopStrategy;
import org.noear.solon.ai.loop.config.LoopConfig;
import org.noear.solon.ai.loop.strategy.RalphLoopStrategy;
import org.noear.solon.ai.loop.strategy.TeamPipelineStrategy;
import org.noear.solon.ai.loop.strategy.UltraQAStrategy;
import org.noear.solon.ai.loop.validator.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autopilot 编排执行器 —— 统一管理多阶段 Pipeline 执行。
 *
 * <p>对标 oh-my-claudecode 的 Autopilot/PipelineOrchestrator 设计。
 * 在每个阶段委托给 LoopEngine + 对应的 LoopStrategy 执行。</p>
 *
 * <p>执行流程：</p>
 * <ol>
 *   <li>EXPANSION → 需求扩展分析（可选）</li>
 *   <li>PLANNING → 规划制定（可选）</li>
 *   <li>EXECUTION → 执行实现（委托给 RalphLoopStrategy）</li>
 *   <li>QA → 质量检查（委托给 UltraQAStrategy）</li>
 *   <li>VALIDATION → 最终验证（可选）</li>
 *   <li>COMPLETED / FAILED</li>
 * </ol>
 *
 * @since 4.0.3
 */
public class AutopilotExecutor {

    private final LoopEngine loopEngine;
    private final PipelineConfig config;
    private final Map<String, PipelineTracking> trackingMap = new ConcurrentHashMap<>();
    private final Map<PipelineStage, StageAdapter> adapters = new HashMap<>();
    private final Map<String, Map<PipelineStage, LoopSession>> stageSessions = new ConcurrentHashMap<>();
    private Validator defaultValidator;

    public AutopilotExecutor(LoopEngine loopEngine, PipelineConfig config) {
        this.loopEngine = loopEngine;
        this.config = config;
        // 注册默认适配器
        registerAdapter(DefaultStageAdapters.executionAdapter());
        registerAdapter(DefaultStageAdapters.qaAdapter());
    }

    /**
     * 设置默认验证器。
     */
    public void setDefaultValidator(Validator validator) {
        this.defaultValidator = validator;
    }

    /**
     * 注册阶段适配器。
     */
    public void registerAdapter(StageAdapter adapter) {
        adapters.put(adapter.supportedStage(), adapter);
    }

    /**
     * 启动完整 Pipeline。
     */
    public CompletableFuture<PipelineResult> startPipeline(PipelineRequest request) {
        final PipelineTracking tracking = new PipelineTracking(request.sessionId);
        trackingMap.put(request.sessionId, tracking);
        stageSessions.put(request.sessionId, new ConcurrentHashMap<PipelineStage, LoopSession>());

        return CompletableFuture.supplyAsync(new java.util.function.Supplier<PipelineResult>() {
            @Override
            public PipelineResult get() {
                try {
                    PipelineResult result = executePipeline(request, tracking);
                    tracking.complete(!result.success);
                    return result;
                } catch (Exception e) {
                    tracking.complete(true);
                    return PipelineResult.failure(request.sessionId, "Pipeline failed: " + e.getMessage(), tracking);
                }
            }
        });
    }

    private PipelineResult executePipeline(PipelineRequest request, PipelineTracking tracking) {
        List<PipelineStage> enabledStages = config.getEnabledStages();

        for (PipelineStage stage : enabledStages) {
            tracking.enterStage(stage);

            // 检查是否有自定义适配器
            StageAdapter adapter = adapters.get(stage);
            if (adapter != null) {
                StageAdapter.StageResult stageResult = adapter.execute(stage, request.context, loopEngine);
                tracking.completeStage(stage, stageResult.isSuccess(), stageResult.getMessage());
                if (!stageResult.isSuccess()) {
                    return PipelineResult.failure(request.sessionId,
                            "Stage " + stage + " failed: " + stageResult.getMessage(), tracking);
                }
                continue;
            }

            // 使用 LoopEngine + Strategy 执行阶段
            boolean success = executeStageWithEngine(stage, request, tracking);
            if (!success && stage.isCancellable()) {
                return PipelineResult.failure(request.sessionId,
                        "Stage " + stage + " failed", tracking);
            }
        }

        return PipelineResult.success(request.sessionId, "Pipeline completed successfully", tracking);
    }

    private boolean executeStageWithEngine(PipelineStage stage, PipelineRequest request,
                                            PipelineTracking tracking) {
        LoopStrategy strategy = config.getStrategyForStage(stage);
        Validator validator = request.validator != null ? request.validator : defaultValidator;

        if (validator == null) {
            validator = createStageValidator(stage);
        }

        LoopConfig loopConfig = LoopConfig.builder()
                .taskDescription("Pipeline stage: " + stage + " - " + request.description)
                .strategy(strategy)
                .validator(validator)
                .maxIterations(request.maxIterations)
                .verificationRequired(request.verificationRequired)
                .statePersistenceEnabled(true)
                .parameters(request.context)
                .build();

        try {
            LoopSession session = loopEngine.start(loopConfig);
            Map<PipelineStage, LoopSession> sessions = stageSessions.get(request.sessionId);
            if (sessions != null) {
                sessions.put(stage, session);
            }

            session.waitForCompletion();
            LoopResult result = session.getResult();
            tracking.completeStage(stage, result.isSuccess(), result.getMessage());

            return result.isSuccess();
        } catch (Exception e) {
            tracking.completeStage(stage, false, "Error: " + e.getMessage());
            return false;
        }
    }

    /**
     * 初始化管道跟踪（独立方法）。
     * 对标 oh-my-claudecode 的 initPipeline。
     *
     * @param sessionId 会话 ID
     * @param request   Pipeline 请求
     */
    public PipelineTracking initPipeline(String sessionId, PipelineRequest request) {
        PipelineTracking tracking = new PipelineTracking(sessionId);
        trackingMap.put(sessionId, tracking);
        stageSessions.put(sessionId, new ConcurrentHashMap<PipelineStage, LoopSession>());
        return tracking;
    }

    /**
     * 推进到下一个阶段（独立方法）。
     * 对标 oh-my-claudecode 的 advanceStage。
     *
     * @param sessionId 会话 ID
     * @return 是否成功推进
     */
    public boolean advanceStage(String sessionId) {
        PipelineTracking tracking = trackingMap.get(sessionId);
        if (tracking == null) return false;

        PipelineStage current = tracking.getCurrentStage();
        List<PipelineStage> enabledStages = config.getEnabledStages();

        // 找到当前阶段在列表中的位置
        int index = enabledStages.indexOf(current);
        if (index < 0 || index >= enabledStages.size() - 1) {
            return false;  // 已是最后阶段
        }

        // 跳过终态阶段
        PipelineStage next = enabledStages.get(index + 1);
        tracking.enterStage(next);
        return true;
    }

    /**
     * 标记当前阶段失败（独立方法）。
     * 对标 oh-my-claudecode 的 failCurrentStage。
     *
     * @param sessionId 会话 ID
     * @param message   失败消息
     */
    public void failCurrentStage(String sessionId, String message) {
        PipelineTracking tracking = trackingMap.get(sessionId);
        if (tracking == null) return;

        PipelineStage current = tracking.getCurrentStage();
        tracking.completeStage(current, false, message);
        tracking.complete(true);  // 标记整体失败
    }

    /**
     * 获取格式化的 HUD 显示信息。
     * 对标 oh-my-claudecode 的 formatPipelineHUD。
     *
     * @param sessionId 会话 ID
     * @return HUD 文本
     */
    public String formatPipelineHUD(String sessionId) {
        PipelineTracking tracking = trackingMap.get(sessionId);
        if (tracking == null) return "No active pipeline.";

        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════╗\n");
        sb.append("║        AUTOPILOT PIPELINE            ║\n");
        sb.append("╚══════════════════════════════════════╝\n");
        sb.append("Session: ").append(tracking.getSessionId()).append("\n");

        String status;
        if (tracking.isCompleted()) {
            status = tracking.isFailed() ? "❌ FAILED" : "✅ COMPLETED";
        } else {
            status = "🔄 RUNNING";
        }
        sb.append("Status: ").append(status).append("\n");
        sb.append("Current Stage: ").append(tracking.getCurrentStage()).append("\n");

        long elapsed = java.time.Duration.between(tracking.getStartedAt(), Instant.now()).getSeconds();
        sb.append("Elapsed: ").append(elapsed).append("s\n");

        sb.append("\n── Stage History ──\n");
        for (PipelineTracking.StageRecord record : tracking.getStageHistory()) {
            String icon;
            if (record.isSkipped()) {
                icon = "⏭";
            } else if (record.getCompletedAt() != null) {
                icon = record.isSuccess() ? "✅" : "❌";
            } else {
                icon = "🔄";
            }
            sb.append("  ").append(icon).append(" ").append(record.getStage());
            if (record.getSummary() != null) {
                sb.append(": ").append(record.getSummary());
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 获取当前管道状态。
     * 对标 oh-my-claudecode 的 getPipelineStatus。
     *
     * @param sessionId 会话 ID
     * @return 状态描述
     */
    public String getPipelineStatus(String sessionId) {
        PipelineTracking tracking = trackingMap.get(sessionId);
        if (tracking == null) return "NOT_STARTED";
        if (tracking.isCompleted()) {
            return tracking.isFailed() ? "FAILED" : "COMPLETED";
        }
        return "RUNNING:" + tracking.getCurrentStage().name();
    }

    /**
     * 创建阶段默认验证器。
     */
    private Validator createStageValidator(final PipelineStage stage) {
        return new Validator() {
            @Override
            public ValidationResult validate(Object result, ValidationCriteria criteria) {
                if (result == null) {
                    return ValidationResult.failed("Result is null", "");
                }
                return ValidationResult.passed(
                        "Stage " + stage + " completed: " + result.toString());
            }

            @Override
            public ValidationResult validateQualityGate(QualityGate gate, Object result) {
                return validate(result, null);
            }

            @Override
            public ValidationResult validateIteration(Object iterationResult, ValidationContext context) {
                return validate(iterationResult, null);
            }
        };
    }

    /**
     * 获取当前阶段。
     */
    public PipelineStage getCurrentStage(String sessionId) {
        PipelineTracking tracking = trackingMap.get(sessionId);
        return tracking != null ? tracking.getCurrentStage() : null;
    }

    /**
     * 获取阶段追踪信息。
     */
    public PipelineTracking getTracking(String sessionId) {
        return trackingMap.get(sessionId);
    }

    /**
     * 跳过某个阶段。
     */
    public boolean skipStage(String sessionId, PipelineStage stage) {
        PipelineTracking tracking = trackingMap.get(sessionId);
        if (tracking == null) return false;
        tracking.skipStage(stage);
        return true;
    }

    /**
     * 取消整个 Pipeline。
     */
    public boolean cancelPipeline(String sessionId) {
        // 停止所有阶段会话
        Map<PipelineStage, LoopSession> sessions = stageSessions.remove(sessionId);
        if (sessions != null) {
            for (LoopSession s : sessions.values()) {
                try { s.stop(); } catch (Exception ignored) {}
            }
        }
        // 完成追踪并移除
        PipelineTracking tracking = trackingMap.remove(sessionId);
        if (tracking != null) {
            tracking.complete(true);
        }
        return true;
    }

    /**
     * 获取所有活跃的 Pipeline 会话。
     */
    public List<String> getActivePipelines() {
        List<String> active = new ArrayList<>();
        for (Map.Entry<String, PipelineTracking> entry : trackingMap.entrySet()) {
            if (!entry.getValue().isCompleted()) {
                active.add(entry.getKey());
            }
        }
        return active;
    }

    /**
     * 阶段执行步骤（用于事务性回滚）。
     */
    private static class TransitionStep {
        final String name;
        final Runnable execute;
        final Runnable rollback;

        TransitionStep(String name, Runnable execute, Runnable rollback) {
            this.name = name;
            this.execute = execute;
            this.rollback = rollback;
        }
    }

    /**
     * 顺序执行步骤，失败时逆序回滚。
     */
    private boolean executeWithRollback(List<TransitionStep> steps) {
        List<TransitionStep> completed = new ArrayList<>();
        for (TransitionStep step : steps) {
            try {
                step.execute.run();
                completed.add(step);
            } catch (Exception e) {
                // 逆序回滚
                for (int i = completed.size() - 1; i >= 0; i--) {
                    try {
                        completed.get(i).rollback.run();
                    } catch (Exception ignored) {}
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Pipeline 请求。
     */
    public static class PipelineRequest {
        public final String sessionId;
        public final String description;
        public final int maxIterations;
        public final boolean verificationRequired;
        public final Validator validator;
        public final Map<String, Object> context;

        public PipelineRequest(String sessionId, String description, int maxIterations,
                                boolean verificationRequired, Validator validator,
                                Map<String, Object> context) {
            this.sessionId = sessionId;
            this.description = description;
            this.maxIterations = maxIterations;
            this.verificationRequired = verificationRequired;
            this.validator = validator;
            this.context = context != null ? context : new HashMap<String, Object>();
        }

        public static PipelineRequest create(String sessionId, String description) {
            return new PipelineRequest(sessionId, description, 50, true, null, new HashMap<String, Object>());
        }
    }

    /**
     * Pipeline 结果。
     */
    public static class PipelineResult {
        public final String sessionId;
        public final boolean success;
        public final String message;
        public final PipelineTracking tracking;

        private PipelineResult(String sessionId, boolean success, String message, PipelineTracking tracking) {
            this.sessionId = sessionId;
            this.success = success;
            this.message = message;
            this.tracking = tracking;
        }

        public static PipelineResult success(String sessionId, String message, PipelineTracking tracking) {
            return new PipelineResult(sessionId, true, message, tracking);
        }

        public static PipelineResult failure(String sessionId, String message, PipelineTracking tracking) {
            return new PipelineResult(sessionId, false, message, tracking);
        }

        public String getSummary() {
            return tracking != null ? tracking.getSummary() : message;
        }
    }
}
