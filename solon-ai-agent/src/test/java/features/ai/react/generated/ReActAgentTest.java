package features.ai.react.generated;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.annotation.Param;

/**
 * ReAct 智能体基础功能测试：简单计算器场景
 * <p>验证 Agent 是否能通过 ReAct (Thought-Action-Observation) 循环，
 * 正确拆解并调用多个工具完成复合数学运算。</p>
 */
public class ReActAgentTest {

    /**
     * 测试数学运算与逻辑推理的链式调用
     * <p>目标：验证 Agent 先执行加法，再基于加法结果执行乘法的能力。</p>
     */
    @Test
    public void testMathAndLogic() throws Throwable {
        // 1. 获取聊天模型
        ChatModel chatModel = LlmUtil.getChatModel();

        // 2. 构建 ReActAgent，并注册计算工具类
        ReActAgent agent = ReActAgent.of(chatModel)
                .defaultToolAdd(new MathTools())
                .modelOptions(o -> o.temperature(0.0)) // 设为 0 以保证逻辑计算的严谨性
                .build();

        // 3. 使用 AgentSession 替代 FlowContext
        // AgentSession 负责维护当前的会话 ID 和执行上下文
        AgentSession session = InMemoryAgentSession.of("math_job_001");

        // 4. 定义复合任务指令
        String question = "先计算 12 加 34 的和，再把结果乘以 2 等于多少？";

        // 5. 调用智能体
        // 采用 call(Prompt, AgentSession) 契约，这是 3.8.x 推荐的标准用法
        String result = agent.prompt(question)
                .session(session)
                .call()
                .getContent();

        // 6. 验证计算结果
        Assertions.assertNotNull(result, "智能体回复不应为空");

        // (12 + 34) * 2 = 92
        Assertions.assertTrue(result.contains("92"),
                "计算逻辑或结果错误。预期应包含 92，实际结果: " + result);

        System.out.println("--- 计算器场景测试通过 ---");
        System.out.println("最终回答: " + result);
    }

    /**
     * 计算领域工具类
     */
    public static class MathTools {
        /**
         * 加法工具
         */
        @ToolMapping(description = "计算两个数字的和（a + b）")
        public double adder(@Param(description = "加数 a") double a,
                            @Param(description = "加数 b") double b) {
            return a + b;
        }

        /**
         * 乘法工具
         */
        @ToolMapping(description = "计算两个数字的乘积（a * b）")
        public double multiplier(@Param(description = "乘数 a") double a,
                                 @Param(description = "乘数 b") double b) {
            return a * b;
        }
    }
}