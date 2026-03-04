package features.ai.team.hitl;

import demo.ai.llm.LlmUtil;
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
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.annotation.Param;

import java.util.HashMap;
import java.util.Map;

public class MultiAgentHitlTest {

    @Test
    public void testMultiAgentApproveFlow() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        HITLInterceptor hitlInterceptor = new HITLInterceptor()
                .onTool("transfer", (trace, args) ->
                        "转账操作需要人工最终核实"
                );

        // 1. 定义会计：负责查询余额/计算（无拦截）
        ReActAgent accountant = ReActAgent.of(chatModel)
                .name("accountant")
                .role("会计")
                .instruction("负责查询账户余额")
                .defaultToolAdd(new AccountTools())
                .build();

        // 2. 定义出纳：负责转账（带 HITL 拦截）
        ReActAgent cashier = ReActAgent.of(chatModel)
                .name("cashier")
                .role("出纳")
                .instruction("负责执行转账。必须在会计确认余额后进行。")
                .defaultToolAdd(new BankTools())
                .defaultInterceptorAdd(hitlInterceptor)
                .build();

        // 3. 组建团队
        TeamAgent financeTeam = TeamAgent.of(chatModel)
                .name("finance_team")
                .role("财务部")
                .protocol(TeamProtocols.SEQUENTIAL)
                .agentAdd(accountant)
                .agentAdd(cashier)
                .build();

        AgentSession session = InMemoryAgentSession.of("team_session_007");

        // --- 第一步：启动任务 ---
        System.out.println(">>> 任务启动：给张三转账 3000 元");
        TeamResponse resp1 = financeTeam.prompt("查询余额并给张三转账 3000 元").session(session).call();

        // 验证：此时会计应该已经完成了工作，而出纳在转账时被拦截
        Assertions.assertTrue(resp1.getTrace().isPending(), "流程应在出纳节点中断");

        // --- 第二步：人工干预（修改金额并批准） ---
        HITLTask task = HITL.getPendingTask(session);
        Assertions.assertNotNull(task);
        System.out.println("拦截到出纳请求: " + task.getToolName() + " 参数: " + task.getArgs());

        // 模拟主管决定：余额虽够，但只允许转 1000
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

        String finalContent = resp2.getContent();
        System.out.println("最终团队回复: " + finalContent);

        // 验证：
        // 1. 出纳是否收到了 1000 元的修正参数并执行
        String realFinalContent = resp2.getContent();

        System.out.println("真正的最后回复: " + realFinalContent);
        Assertions.assertTrue(realFinalContent.contains("1000") || realFinalContent.contains("1,000"), "这次应该稳了！");
        // 2. 状态是否清理
        Assertions.assertNull(HITL.getPendingTask(session));
    }

    // 会计工具
    public static class AccountTools {
        @ToolMapping(description = "查询指定账户的余额")
        public String checkBalance() {
            return "当前账户余额为：￥50000.00";
        }
    }

    // 出纳工具
    public static class BankTools {
        @ToolMapping(description = "执行银行转账操作")
        public String transfer(@Param(description = "收款人") String to,
                               @Param(description = "金额") double amount) {
            return "【银行通知】成功向 " + to + " 转账 ￥" + amount;
        }
    }
}