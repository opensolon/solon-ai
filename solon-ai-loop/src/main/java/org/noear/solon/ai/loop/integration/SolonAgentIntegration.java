package org.noear.solon.ai.loop.integration;

import org.noear.solon.ai.loop.config.LoopConfig;
import org.noear.solon.ai.loop.engine.LoopEngine;
import org.noear.solon.ai.loop.engine.LoopResult;
import org.noear.solon.ai.loop.engine.LoopSession;
import org.noear.solon.ai.loop.state.LoopState;
import org.noear.solon.ai.loop.strategy.RalphLoopStrategy;
import org.noear.solon.ai.loop.strategy.UltraQAStrategy;
import org.noear.solon.ai.loop.validator.Validator;

import java.util.List;

/**
 * Solon Agent 集成 —— 将 LoopEngine 与 solon-ai-agent 的 SimpleAgent 集成。
 *
 * <p>集成能力：
 * <ul>
 *   <li>使用 SimpleAgent 执行 Agent 驱动的循环任务</li>
 *   <li>支持 Ralph 循环（故事驱动）和 UltraQA 循环（质量门禁）</li>
 *   <li>Agent 会话管理和生命周期控制</li>
 * </ul>
 *
 * @since 4.0.3
 */
public class SolonAgentIntegration {

    private final LoopEngine loopEngine;
    private final AgentBridge agentBridge;

    public SolonAgentIntegration(LoopEngine loopEngine) {
        this.loopEngine = loopEngine;
        this.agentBridge = new AgentBridge();
    }

    /**
     * 创建 Agent 驱动的循环配置。
     */
    public LoopConfig createAgentDrivenConfig(String taskDescription, RalphLoopStrategy strategy,
                                               Validator validator) {
        return LoopConfig.builder()
                .taskDescription(taskDescription)
                .strategy(strategy)
                .validator(validator)
                .maxIterations(strategy.getMaxIterations())
                .verificationRequired(strategy.isVerificationRequired())
                .statePersistenceEnabled(true)
                .build();
    }

    /**
     * 启动 Agent 循环执行。
     */
    public LoopSession startAgentExecution(LoopConfig config) {
        return loopEngine.start(config);
    }

    /**
     * 启动 Ralph 循环（快捷方法）。
     */
    public LoopSession startRalphLoop(String taskDescription, Validator validator) {
        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .criticMode("architect")
                .maxIterations(50)
                .verificationRequired(true)
                .validator(validator)
                .build();

        LoopConfig config = createAgentDrivenConfig(taskDescription, strategy, validator);
        return startAgentExecution(config);
    }

    /**
     * 启动 UltraQA 循环（快捷方法）。
     */
    public LoopSession startUltraQALoop(String taskDescription, Validator validator) {
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

        return startAgentExecution(config);
    }

    /**
     * 暂停 Agent 执行。
     */
    public void pauseAgentExecution(String sessionId) {
        loopEngine.pause(sessionId);
    }

    /**
     * 恢复 Agent 执行。
     */
    public void resumeAgentExecution(String sessionId) {
        loopEngine.resume(sessionId);
    }

    /**
     * 停止 Agent 执行。
     */
    public void stopAgentExecution(String sessionId) {
        loopEngine.stop(sessionId);
    }

    /**
     * 获取 Agent 执行状态。
     */
    public ExecutionStatus getAgentExecutionStatus(String sessionId) {
        LoopSession session = loopEngine.getSession(sessionId);
        if (session == null) {
            return new ExecutionStatus(sessionId, "NOT_FOUND", 0, 0.0, 0);
        }

        return new ExecutionStatus(
                sessionId,
                session.getState().name(),
                session.getIterationCount(),
                session.getSuccessRate(),
                0
        );
    }

    /**
     * 获取 Agent 桥接器（用于扩展 SimpleAgent 相关功能）。
     */
    public AgentBridge getAgentBridge() {
        return agentBridge;
    }

    /**
     * 执行状态数据类。
     */
    public static class ExecutionStatus {
        public final String sessionId;
        public final String state;
        public final int iterationCount;
        public final double successRate;
        public final long duration;

        public ExecutionStatus(String sessionId, String state, int iterationCount,
                                double successRate, long duration) {
            this.sessionId = sessionId;
            this.state = state;
            this.iterationCount = iterationCount;
            this.successRate = successRate;
            this.duration = duration;
        }
    }

    /**
     * Agent 桥接器 —— 连接 LoopEngine 和 SimpleAgent 的桥梁。
     * 如果 SimpleAgent 在类路径中，则提供额外功能。
     */
    public static class AgentBridge {

        private boolean agentAvailable;

        public AgentBridge() {
            try {
                Class.forName("org.noear.solon.ai.agent.simple.SimpleAgent");
                this.agentAvailable = true;
            } catch (ClassNotFoundException e) {
                this.agentAvailable = false;
            }
        }

        /**
         * 检查 SimpleAgent 是否可用。
         */
        public boolean isAgentAvailable() {
            return agentAvailable;
        }
    }
}
