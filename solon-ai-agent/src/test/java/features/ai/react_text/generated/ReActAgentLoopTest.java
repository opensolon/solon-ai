package features.ai.react_text.generated;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.agent.react.ReActStyle;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;

/**
 * ReActAgent 缺失资料索要场景测试
 * * 验证流程：
 * 1. 用户下达指令，但缺少关键参数。
 * 2. Agent 正常结束。
 * 3. 业务方通过收集新数据。
 * 4. Agent 再次执行，顺利完成任务。
 */
public class ReActAgentLoopTest {

    @Test
    public void testAskForInformationAndResume() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 构建 Agent
        // 注意：框架内部已经自动注册了 AskTool
        ReActAgent agent = ReActAgent.of(chatModel)
                .style(ReActStyle.STRUCTURED_TEXT)
                .defaultToolAdd(new BankTools())
                .modelOptions(o -> o.temperature(0.0))
                .build();

        // 2. 准备 Session
        AgentSession session = InMemoryAgentSession.of("ask_tool_session_001");

        // --- 第一阶段：信息缺失 ---
        System.out.println("--- 第一轮：下达转账指令（未提供卡号） ---");
        String prompt = "帮我转账 500 元给老张。";

        ReActResponse resp1 = agent.prompt(prompt)
                .session(session)
                .call();

        String result1 = resp1.getContent();
        System.out.println("模型答复1: " + result1);

        // 验证：应该触发了 AskTool，最终答复应该是询问卡号
        Assertions.assertTrue(result1.contains("卡号") || result1.contains("银行卡") || result1.contains("交易流水"),
                "模型应该主动询问银行卡号");

        FlowContext context = session.getSnapshot();
        ReActTrace trace = resp1.getTrace();

        Assertions.assertEquals(Agent.ID_END, trace.getRoute(), "流程应该已经标记为结束(挂起)");
        Assertions.assertNotNull(trace.getFinalAnswer(), "FinalAnswer 应该被填充为询问内容");

        // --- 第二阶段：注入缺失属性 ---
        System.out.println("\n--- 模拟外部操作：在下次提示词中注入卡号 ---");
        String bankCard = "6222021001008888";

        // --- 第三阶段：恢复执行并感知属性 ---
        System.out.println("--- 第二轮：带上卡号再次调用 ---");
        ReActResponse resp2 = agent.prompt("我已经准备好卡号了: " + bankCard) // 注入关键属性)
                .session(session)
                .call();

        String result2 = resp2.getContent();
        System.out.println("模型答复2: " + result2);

        Assertions.assertTrue(result2.contains("成功"), "任务应该最终执行成功");
    }

    /**
     * 模拟银行工具类
     */
    public static class BankTools {
        @ToolMapping(description = "执行银行转账操作")
        public String do_transfer(@Param(description = "金额") double amount,
                                  @Param(description = "收款人姓名") String payee,
                                  @Param(description = "收款人银行卡号") String bank_card_number) {
            System.out.println("[工具调用] 正在向 " + payee + " (" + bank_card_number + ") 转账 " + amount + " 元...");
            return "成功向 " + payee + " 转账 " + amount + " 元，交易流水号：TX" + System.currentTimeMillis();
        }
    }
}