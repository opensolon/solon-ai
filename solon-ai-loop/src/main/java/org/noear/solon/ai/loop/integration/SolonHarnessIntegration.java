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
     * 使用 HarnessEngine 执行 UltraQA 循环。
     * <p>将注入的 HarnessEngine 包装为循环驱动力，
     * 创建 UltraQA 策略以进行质量门禁循环。</p>
     *
     * @param taskDescription 任务描述
     * @param harnessEngine   HarnessEngine 实例
     * @return 循环会话
     */
    public LoopSession startHarnessDrivenUltraQA(String taskDescription, Object harnessEngine) {
        harnessBridge.setHarnessEngine(harnessEngine);

        UltraQAStrategy strategy = UltraQAStrategy.builder()
                .maxTestAttempts(5)
                .goalType(UltraQAStrategy.UltraQAGoalType.TESTS)
                .build();

        Map<String, Object> params = new HashMap<>();
        params.put("harnessMode", true);
        params.put("sessionId", java.util.UUID.randomUUID().toString());

        LoopConfig config = LoopConfig.builder()
                .taskDescription(taskDescription)
                .strategy(strategy)
                .maxIterations(20)
                .verificationRequired(false)
                .statePersistenceEnabled(true)
                .parameters(params)
                .build();

        return loopEngine.start(config);
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
        private Object harnessEngine;  // org.noear.solon.ai.harness.HarnessEngine (optional)

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

        /**
         * 注入 HarnessEngine 实例。
         */
        public void setHarnessEngine(Object harnessEngine) {
            this.harnessEngine = harnessEngine;
            this.harnessAvailable = true;
        }

        /**
         * 获取注入的 HarnessEngine。
         */
        public Object getHarnessEngine() {
            return harnessEngine;
        }

        /**
         * 检查指定工具是否可用。
         * <p>通过反射检查 HarnessEngine.getAgentManager() 是否返回非 null 的管理器。</p>
         *
         * @param toolName 工具名称
         * @return 工具是否可用
         */
        public boolean isToolAvailable(String toolName) {
            if (harnessEngine == null) return false;
            try {
                java.lang.reflect.Method getAgentManager = harnessEngine.getClass().getMethod("getAgentManager");
                Object agentManager = getAgentManager.invoke(harnessEngine);
                return agentManager != null;
            } catch (Exception ignored) {
            }
            return false;
        }

        /**
         * 执行 HarnessEngine 中的 Agent。
         * <p>使用反射调用 HarnessEngine.getMainAgent() 获取主 Agent，
         * 然后调用 agent.prompt(taskPrompt).call() 获取响应。</p>
         *
         * @param taskPrompt 任务提示词
         * @return Agent 响应内容，出错时返回错误信息
         */
        public String executeWithHarness(String taskPrompt) {
            if (harnessEngine == null) {
                throw new IllegalStateException("HarnessEngine not injected");
            }
            try {
                java.lang.reflect.Method getMainAgent = harnessEngine.getClass().getMethod("getMainAgent");
                Object agent = getMainAgent.invoke(harnessEngine);
                if (agent != null) {
                    java.lang.reflect.Method prompt = agent.getClass().getMethod("prompt", String.class);
                    Object request = prompt.invoke(agent, taskPrompt);
                    java.lang.reflect.Method call = request.getClass().getMethod("call");
                    Object response = call.invoke(request);
                    java.lang.reflect.Method getMessage = response.getClass().getMethod("getMessage");
                    Object message = getMessage.invoke(response);
                    if (message != null) {
                        java.lang.reflect.Method getContent = message.getClass().getMethod("getContent");
                        return (String) getContent.invoke(message);
                    }
                }
            } catch (Exception e) {
                return "Harness execution error: " + e.getMessage();
            }
            return "";
        }
    }
}
