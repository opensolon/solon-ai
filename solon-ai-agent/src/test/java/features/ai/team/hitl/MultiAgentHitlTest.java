package features.ai.team.hitl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamResponse;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatChoice;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.annotation.Param;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多 Agent + HITL 审批流测试。
 * <p>
 * 使用 mock ChatModel 保证确定性：会计先查余额，出纳触发 transfer 拦截；
 * 人工修正金额后恢复执行，验证修正参数落地与状态清理。
 * </p>
 */
public class MultiAgentHitlTest {

    @Test
    public void testMultiAgentApproveFlow() throws Throwable {
        HITLInterceptor hitlInterceptor = new HITLInterceptor()
                .onTool("transfer", (trace, args) ->
                        "转账操作需要人工最终核实"
                );

        // 1. 会计：确定性调用 checkBalance 后给出结论
        ReActAgent accountant = ReActAgent.of(mockAccountantModel())
                .name("accountant")
                .role("会计")
                .instruction("负责查询账户余额")
                .defaultToolAdd(new AccountTools())
                .build();

        // 2. 出纳：确定性调用 transfer，触发 HITL
        ReActAgent cashier = ReActAgent.of(mockCashierModel())
                .name("cashier")
                .role("出纳")
                .instruction("负责执行转账。必须在会计确认余额后进行。")
                .defaultToolAdd(new BankTools())
                .defaultInterceptorAdd(hitlInterceptor)
                .build();

        // 3. 顺序团队：不依赖 Supervisor LLM
        TeamAgent financeTeam = TeamAgent.of(null)
                .name("finance_team")
                .role("财务部")
                .protocol(TeamProtocols.SEQUENTIAL)
                .feedbackMode(false)
                .agentAdd(accountant)
                .agentAdd(cashier)
                .build();

        AgentSession session = InMemoryAgentSession.of("team_session_007");

        // --- 第一步：启动任务 ---
        System.out.println(">>> 任务启动：给张三转账 3000 元");
        TeamResponse resp1 = financeTeam.prompt("查询余额并给张三转账 3000 元").session(session).call();
        System.out.println("阶段1回复: " + resp1.getContent());

        // 验证：会计完成、出纳在 transfer 处挂起
        Assertions.assertTrue(session.isPending(), "流程应在出纳节点中断");
        Assertions.assertTrue(resp1.getTrace().getFormattedHistory().contains("accountant")
                        || resp1.getTrace().getFormattedHistory().contains("余额"),
                "会计应已产出: " + resp1.getTrace().getFormattedHistory());

        // --- 第二步：人工干预（修改金额并批准） ---
        HITLTask task = HITL.getPendingTask(session);
        Assertions.assertNotNull(task, "应能读到挂起的 HITL 任务");
        Assertions.assertEquals("transfer", task.getToolName());
        System.out.println("拦截到出纳请求: " + task.getToolName() + " 参数: " + task.getArgs());

        Map<String, Object> newArgs = new HashMap<>();
        newArgs.put("to", "张三");
        newArgs.put("amount", 1000.0);

        HITLDecision decision = HITLDecision.approve()
                .comment("主管审批：金额过大，先转 1000 元测试")
                .modifiedArgs(newArgs);

        HITL.submit(session, task.getToolName(), decision);

        // --- 第三步：恢复执行 ---
        System.out.println(">>> 任务恢复...");
        TeamResponse resp2 = financeTeam.prompt().session(session).call();

        String finalContent = nullToEmpty(resp2.getContent());
        String history = resp2.getTrace() == null ? "" : nullToEmpty(resp2.getTrace().getFormattedHistory());
        String all = finalContent + "\n" + history;
        System.out.println("最终团队回复: " + finalContent);
        System.out.println("协作轨迹:\n" + history);

        // 1. 出纳应收到修正后的 1000 元并执行
        Assertions.assertTrue(all.contains("1000") || all.contains("1,000"),
                "应执行修正后的 1000 元: " + all);
        Assertions.assertTrue(all.contains("张三") || all.contains("转账"),
                "应包含转账结果: " + all);
        // 2. 状态清理
        Assertions.assertFalse(session.isPending(), "恢复后不应再挂起");
        Assertions.assertNull(HITL.getPendingTask(session), "HITL 挂起任务应清理");
    }

    /**
     * 会计模型：第 1 次推理返回 checkBalance 工具调用，第 2 次给出最终答复。
     */
    private ChatModel mockAccountantModel() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ChatRequestDesc reqDesc = mock(ChatRequestDesc.class);
        when(chatModel.prompt(anyList())).thenReturn(reqDesc);
        when(reqDesc.options(any(Consumer.class))).thenReturn(reqDesc);

        AtomicInteger calls = new AtomicInteger();
        when(reqDesc.call()).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return mockResponse(toolCallMessage("checkBalance", "{}"));
            }
            return mockResponse(textMessage("账户余额充足，当前余额 ￥50000.00，可继续转账。"));
        });
        return chatModel;
    }

    /**
     * 出纳模型：
     * <ul>
     *   <li>第 1 次：发起 transfer(3000) —— 触发 HITL 挂起</li>
     *   <li>第 2 次（恢复后）：再次发起 transfer，使 HITL 决策生效并执行修正参数</li>
     *   <li>第 3 次：根据 Observation 输出最终答复</li>
     * </ul>
     */
    private ChatModel mockCashierModel() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ChatRequestDesc reqDesc = mock(ChatRequestDesc.class);
        when(chatModel.prompt(anyList())).thenReturn(reqDesc);
        when(reqDesc.options(any(Consumer.class))).thenReturn(reqDesc);

        AtomicInteger calls = new AtomicInteger();
        when(reqDesc.call()).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n <= 2) {
                return mockResponse(toolCallMessage(
                        "transfer",
                        "{\"to\":\"张三\",\"amount\":3000}"
                ));
            }
            return mockResponse(textMessage("转账已完成：成功向 张三 转账 ￥1000.0"));
        });
        return chatModel;
    }

    private ChatResponse mockResponse(AssistantMessage message) {
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.isStream()).thenReturn(false);
        when(resp.isEmpty()).thenReturn(false);
        when(resp.getMessage()).thenReturn(message);
        when(resp.getAggregationMessage()).thenReturn(message);
        when(resp.getChoices()).thenReturn(Collections.singletonList(
                new ChatChoice(0, new Date(), "stop", message)));
        when(resp.getUsage()).thenReturn(null);
        return resp;
    }

    private AssistantMessage toolCallMessage(String toolName, String argsJson) {
        String json = "{" +
                "  \"role\": \"assistant\"," +
                "  \"content\": \"\"," +
                "  \"toolCalls\": [{" +
                "    \"id\": \"call_" + Math.abs(System.nanoTime()) + "\"," +
                "    \"name\": \"" + toolName + "\"," +
                "    \"arguments\": " + argsJson +
                "  }]" +
                "}";
        return (AssistantMessage) ChatMessage.fromJson(json);
    }

    private AssistantMessage textMessage(String content) {
        return (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"content\":\"" + content.replace("\"", "\\\"") + "\"}");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public static class AccountTools extends AbsToolProvider {
        @ToolMapping(description = "查询指定账户的余额")
        public String checkBalance() {
            return "当前账户余额为：￥50000.00";
        }
    }

    public static class BankTools extends AbsToolProvider {
        @ToolMapping(description = "执行银行转账操作")
        public String transfer(@Param(description = "收款人") String to,
                               @Param(description = "金额") double amount) {
            return "【银行通知】成功向 " + to + " 转账 ￥" + amount;
        }
    }
}
