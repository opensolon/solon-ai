package features.ai.react.hitl;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.util.FeedbackTool;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;

/**
 * 测试 ReActAgent 的步数限制与 HITL 动态扩容
 */
public class HITLStepLimitTest {

    @Test
    public void testStepExpansionFlow() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 构建 Agent，设置较小的步数上限
        ReActAgent agent = ReActAgent.of(chatModel)
                .role("一个计数员")
                .instruction("每次收到任务，必须调用 iterate_count 工具，且不要停止，直到我让你停止。")
                .defaultToolAdd(new LoopTools())
                .build();

        // 配置初始 maxSteps 为 10，开启反馈模式（FeedbackMode 是触发扩容的前提）
        AgentSession session = InMemoryAgentSession.of("user_step_test");

        // 2. 第一次调用：执行到临界点（ReasonTask 会在约 8-9 步时拦截）
        System.out.println(">>> 启动任务：开始循环计数...");
        ReActResponse resp1 = agent.prompt("请开始循环计数")
                .session(session)
                .options(o -> o.maxSteps(10).feedbackMode(true)) // 开启反馈模式
                .call();

        // 验证被拦截：因为 currentStep 会触碰 thresholdStep (max(9, 8))
        Assertions.assertTrue(resp1.getTrace().isPending(), "步数接近上限，应该是 Pending 状态");

        // 3. 检查 HITL 任务
        HITLTask pendingTask = HITL.getPendingTask(session);
        Assertions.assertNotNull(pendingTask);
        Assertions.assertEquals(FeedbackTool.TOOL_NAME, pendingTask.getToolName());
        System.out.println("收到步数预警: " + pendingTask.getComment());

        // 4. 人工介入：批准继续
        System.out.println(">>> 人工介入：批准继续执行（动态扩容）");
        HITL.submit(session, FeedbackTool.TOOL_NAME, HITLDecision.approve().comment("请继续执行，我需要更多结果"));

        // 5. 恢复执行：验证 maxSteps 是否增加
        System.out.println(">>> 第二次调用：恢复执行并观察步数变化");
        ReActResponse resp2 = agent.prompt().session(session).call();

        // 验证结果
        int newMaxSteps = resp2.getTrace().getOptions().getMaxSteps();
        System.out.println("扩容后的最大步数: " + newMaxSteps);

        // ReasonTask 逻辑：maxSteps + 10
        Assertions.assertEquals(20, newMaxSteps, "扩容后的步数应为 10 + 10 = 20");

        // 验证决策状态已被清理
        Assertions.assertNull(HITL.getDecision(session, FeedbackTool.TOOL_NAME), "执行后决策状态应清理");
    }

    /**
     * 模拟工具类
     */
    public static class LoopTools {
        private int count = 0;

        @ToolMapping(description = "增加计数并返回当前状态")
        public String iterate_count() {
            count++;
            return "当前计数值为: " + count + "。请继续下一步。";
        }
    }
}