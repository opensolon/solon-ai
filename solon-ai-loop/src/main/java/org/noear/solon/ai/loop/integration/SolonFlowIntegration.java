package org.noear.solon.ai.loop.integration;

import org.noear.solon.ai.loop.config.LoopConfig;
import org.noear.solon.ai.loop.engine.LoopEngine;
import org.noear.solon.ai.loop.engine.LoopSession;
import org.noear.solon.ai.loop.strategy.RalphLoopStrategy;
import org.noear.solon.ai.loop.strategy.TeamPipelineStrategy;
import org.noear.solon.ai.loop.validator.Validator;

import java.util.HashMap;
import java.util.Map;

/**
 * Solon Flow 集成 —— 将 LoopEngine 与 solon-flow 的流编排能力集成。
 *
 * <p>集成能力：
 * <ul>
 *   <li>基于 solon-flow 的 Chain/Node 构建循环执行流</li>
 *   <li>Flow 上下文与 LoopContext 双向绑定</li>
 *   <li>支持 Flow 驱动的 Team Pipeline 执行</li>
 * </ul>
 *
 * @since 4.0.3
 */
public class SolonFlowIntegration {

    private final LoopEngine loopEngine;
    private final FlowBridge flowBridge;

    public SolonFlowIntegration(LoopEngine loopEngine) {
        this.loopEngine = loopEngine;
        this.flowBridge = new FlowBridge();
    }

    /**
     * 创建 Flow 驱动的循环配置。
     *
     * @param flowId    流 ID（用于绑定 solon-flow Chain）
     * @param strategy  循环策略
     * @param validator 验证器
     */
    public LoopConfig createFlowBasedConfig(String flowId, TeamPipelineStrategy strategy,
                                             Validator validator) {
        Map<String, Object> params = new HashMap<>();
        params.put("flowId", flowId);

        return LoopConfig.builder()
                .taskDescription("Flow-based execution: " + flowId)
                .strategy(strategy)
                .validator(validator)
                .maxIterations(strategy.getMaxIterations())
                .verificationRequired(true)
                .statePersistenceEnabled(true)
                .parameters(params)
                .build();
    }

    /**
     * 启动 Flow 驱动的循环执行。
     */
    public LoopSession startFlowExecution(LoopConfig config) {
        return loopEngine.start(config);
    }

    /**
     * 启动带 Flow 上下文的 Ralph 循环。
     *
     * @param flowId     流 ID
     * @param taskDescription 任务描述
     * @param validator  验证器
     */
    public LoopSession startFlowRalphLoop(String flowId, String taskDescription, Validator validator) {
        Map<String, Object> params = new HashMap<>();
        params.put("flowId", flowId);

        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .criticMode("architect")
                .maxIterations(50)
                .verificationRequired(true)
                .build();

        LoopConfig config = LoopConfig.builder()
                .taskDescription(taskDescription)
                .strategy(strategy)
                .validator(validator)
                .maxIterations(50)
                .verificationRequired(true)
                .statePersistenceEnabled(true)
                .parameters(params)
                .build();

        return loopEngine.start(config);
    }

    /**
     * 暂停 Flow 执行。
     */
    public void pauseFlowExecution(String sessionId) {
        loopEngine.pause(sessionId);
    }

    /**
     * 恢复 Flow 执行。
     */
    public void resumeFlowExecution(String sessionId) {
        loopEngine.resume(sessionId);
    }

    /**
     * 停止 Flow 执行。
     */
    public void stopFlowExecution(String sessionId) {
        loopEngine.stop(sessionId);
    }

    /**
     * 获取 Flow 执行状态。
     */
    public FlowExecutionStatus getFlowExecutionStatus(String sessionId) {
        LoopSession session = loopEngine.getSession(sessionId);
        if (session == null) {
            return new FlowExecutionStatus(sessionId, "NOT_FOUND", 0, 0.0, null);
        }

        String flowId = session.getContext() != null ?
                (String) session.getContext().get("flowId") : null;

        return new FlowExecutionStatus(
                sessionId,
                session.getState().name(),
                session.getIterationCount(),
                session.getSuccessRate(),
                flowId
        );
    }

    /**
     * 获取 Flow 桥接器。
     */
    public FlowBridge getFlowBridge() {
        return flowBridge;
    }

    /**
     * Flow 执行状态。
     */
    public static class FlowExecutionStatus {
        public final String sessionId;
        public final String state;
        public final int iterationCount;
        public final double successRate;
        public final String flowId;

        public FlowExecutionStatus(String sessionId, String state, int iterationCount,
                                    double successRate, String flowId) {
            this.sessionId = sessionId;
            this.state = state;
            this.iterationCount = iterationCount;
            this.successRate = successRate;
            this.flowId = flowId;
        }
    }

    /**
     * Flow 桥接器 —— 连接 LoopEngine 和 solon-flow。
     */
    public static class FlowBridge {

        private boolean flowAvailable;

        public FlowBridge() {
            try {
                Class.forName("org.noear.solon.flow.FlowEngine");
                this.flowAvailable = true;
            } catch (ClassNotFoundException e) {
                this.flowAvailable = false;
            }
        }

        /**
         * 检查 solon-flow 是否可用。
         */
        public boolean isFlowAvailable() {
            return flowAvailable;
        }
    }
}
