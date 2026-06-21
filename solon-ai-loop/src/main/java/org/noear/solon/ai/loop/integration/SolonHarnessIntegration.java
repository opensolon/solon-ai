package org.noear.solon.ai.loop.integration;

import org.noear.solon.ai.loop.config.LoopConfig;
import org.noear.solon.ai.loop.engine.LoopEngine;
import org.noear.solon.ai.loop.engine.LoopSession;
import org.noear.solon.ai.loop.strategy.TeamPipelineStrategy;
import org.noear.solon.ai.loop.strategy.UltraQAStrategy;
import org.noear.solon.ai.loop.validator.Validator;

import java.util.HashMap;
import java.util.Map;

/**
 * Solon Harness 集成 —— 将 LoopEngine 与 solon-ai-harness 的工具管理能力集成。
 *
 * <p>集成能力：
 * <ul>
 *   <li>使用 HarnessEngine 的 AgentFactory 创建 Agent 并执行循环任务</li>
 *   <li>支持 ToolPermission 管理和命令注册</li>
 *   <li>支持 Team Pipeline 和 UltraQA 循环策略</li>
 * </ul>
 *
 * @since 4.0.3
 */
public class SolonHarnessIntegration {

    private final LoopEngine loopEngine;
    private final HarnessBridge harnessBridge;

    public SolonHarnessIntegration(LoopEngine loopEngine) {
        this.loopEngine = loopEngine;
        this.harnessBridge = new HarnessBridge();
    }

    /**
     * 创建工具驱动的循环配置。
     *
     * @param taskDescription 任务描述
     * @param strategy        循环策略
     * @param validator       验证器
     */
    public LoopConfig createToolDrivenConfig(String taskDescription,
                                              TeamPipelineStrategy strategy,
                                              Validator validator) {
        Map<String, Object> params = new HashMap<>();
        params.put("harnessMode", true);

        return LoopConfig.builder()
                .taskDescription(taskDescription)
                .strategy(strategy)
                .validator(validator)
                .maxIterations(100)
                .verificationRequired(true)
                .statePersistenceEnabled(true)
                .parameters(params)
                .build();
    }

    /**
     * 启动工具驱动的循环执行。
     */
    public LoopSession startToolExecution(LoopConfig config) {
        return loopEngine.start(config);
    }

    /**
     * 启动 Harness 驱动的 UltraQA 循环。
     *
     * @param taskDescription 任务描述
     * @param validator       验证器
     */
    public LoopSession startToolUltraQALoop(String taskDescription, Validator validator) {
        UltraQAStrategy strategy = UltraQAStrategy.builder()
                .maxTestAttempts(10)
                .goalType(UltraQAStrategy.UltraQAGoalType.TESTS)
                .build();

        LoopConfig config = LoopConfig.builder()
                .taskDescription(taskDescription)
                .strategy(strategy)
                .validator(validator)
                .maxIterations(100)
                .verificationRequired(true)
                .statePersistenceEnabled(true)
                .build();

        return loopEngine.start(config);
    }

    /**
     * 暂停工具执行。
     */
    public void pauseToolExecution(String sessionId) {
        loopEngine.pause(sessionId);
    }

    /**
     * 恢复工具执行。
     */
    public void resumeToolExecution(String sessionId) {
        loopEngine.resume(sessionId);
    }

    /**
     * 停止工具执行。
     */
    public void stopToolExecution(String sessionId) {
        loopEngine.stop(sessionId);
    }

    /**
     * 获取工具执行状态。
     */
    public ToolExecutionStatus getToolExecutionStatus(String sessionId) {
        LoopSession session = loopEngine.getSession(sessionId);
        if (session == null) {
            return new ToolExecutionStatus(sessionId, "NOT_FOUND", 0, 0.0, false);
        }

        return new ToolExecutionStatus(
                sessionId,
                session.getState().name(),
                session.getIterationCount(),
                session.getSuccessRate(),
                harnessBridge.isHarnessAvailable()
        );
    }

    /**
     * 获取 Harness 桥接器。
     */
    public HarnessBridge getHarnessBridge() {
        return harnessBridge;
    }

    /**
     * 工具执行状态。
     */
    public static class ToolExecutionStatus {
        public final String sessionId;
        public final String state;
        public final int iterationCount;
        public final double successRate;
        public final boolean harnessAvailable;

        public ToolExecutionStatus(String sessionId, String state, int iterationCount,
                                    double successRate, boolean harnessAvailable) {
            this.sessionId = sessionId;
            this.state = state;
            this.iterationCount = iterationCount;
            this.successRate = successRate;
            this.harnessAvailable = harnessAvailable;
        }
    }

    /**
     * Harness 桥接器 —— 连接 LoopEngine 和 solon-ai-harness。
     */
    public static class HarnessBridge {

        private boolean harnessAvailable;

        public HarnessBridge() {
            try {
                Class.forName("org.noear.solon.ai.harness.HarnessEngine");
                this.harnessAvailable = true;
            } catch (ClassNotFoundException e) {
                this.harnessAvailable = false;
            }
        }

        /**
         * 检查 HarnessEngine 是否可用。
         */
        public boolean isHarnessAvailable() {
            return harnessAvailable;
        }
    }
}
