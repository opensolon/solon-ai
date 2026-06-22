package org.noear.solon.ai.loop.integration;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.loop.config.LoopConfig;
import org.noear.solon.ai.loop.engine.LoopEngine;
import org.noear.solon.ai.loop.engine.LoopSession;
import org.noear.solon.ai.loop.strategy.RalphLoopStrategy;
import org.noear.solon.ai.loop.strategy.UltraQAStrategy;
import org.noear.solon.ai.loop.validator.Validator;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
* Solon Agent 集成 —— 将 LoopEngine 与 {@link Agent} 接口集成。
 *
 * <p>集成能力：
 * <ul>
 *   <li>使用 {@link Agent} 接口驱动循环任务（支持 SimpleAgent、ReActAgent、TeamAgent 等实现）</li>
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
     * 使用 Agent 作为故事实现器创建 Ralph 循环。
     *
     * <p>将注入的 Agent 包装为 {@link RalphLoopStrategy.StoryImplementor}，
     * 使 Ralph 循环的每个故事通过 Agent 调用实现。支持任意 Agent 实现
     * （如 SimpleAgent、ReActAgent、TeamAgent）。</p>
     *
     * @param taskDescription 任务描述
     * @param agent           Agent 实例（SimpleAgent / ReActAgent / TeamAgent 等）
     * @return 循环会话
     */
    public LoopSession startAgentDrivenRalphLoop(String taskDescription, Agent<?, ?> agent) {
        agentBridge.setAgent(agent);

        RalphLoopStrategy.StoryImplementor implementor = (story, context) -> {
            String prompt = "Implement the following story: " + story;
            return agentBridge.executePrompt(prompt);
        };

        RalphLoopStrategy strategy = RalphLoopStrategy.builder()
                .criticMode("architect")
                .maxIterations(50)
                .verificationRequired(true)
                .storyImplementor(implementor)
                .build();

        Map<String, Object> params = new HashMap<>();
        params.put("sessionId", UUID.randomUUID().toString());

        LoopConfig config = LoopConfig.builder()
                .taskDescription(taskDescription)
                .strategy(strategy)
                .maxIterations(50)
                .verificationRequired(true)
                .statePersistenceEnabled(true)
                .parameters(params)
                .build();

        return startAgentExecution(config);
    }

    /**
     * 获取 Agent 桥接器（用于扩展 Agent 相关功能）。
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
     * Agent 桥接器 —— 连接 LoopEngine 和任意 {@link Agent} 实现的桥梁。
     *
     * <p>{@code solon-ai-agent} 为编译期直接依赖（已在 pom.xml 中显式引入），
     * 因此可以对接所有 Agent 实现（如 SimpleAgent、ReActAgent、TeamAgent）。</p>
     */
    public static class AgentBridge {

        private Agent<?, ?> agent;

        /**
         * 注入 Agent 实例。
         */
        public void setAgent(Agent<?, ?> agent) {
            this.agent = agent;
        }

        /**
         * 获取注入的 Agent 实例。
         */
        @SuppressWarnings("unchecked")
        public <T extends Agent<?, ?>> T getAgent() {
            return (T) agent;
        }

        /**
         * 执行 Agent 调用并返回响应文本。
         *
         * @param promptText 提示词文本
         * @return Agent 响应内容，出错时返回错误信息
         */
        public String executePrompt(String promptText) {
            if (agent == null) {
                throw new IllegalStateException("Agent not injected. Call setAgent() first.");
            }
            try {
                AgentResponse<?> response = agent.prompt(promptText).call();
                if (response != null) {
                    return response.getContent();
                }
                return "";
            } catch (Throwable e) {
                return "Agent error: " + e.getMessage();
            }
        }
    }
}
