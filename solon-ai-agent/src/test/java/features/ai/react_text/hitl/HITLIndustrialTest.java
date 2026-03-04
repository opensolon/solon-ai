package features.ai.react_text.hitl;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.agent.react.ReActStyle;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.annotation.Param;

public class HITLIndustrialTest {

    @Test
    public void testRejectFlow() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 使用新接口：注册敏感工具
        HITLInterceptor hitlInterceptor = new HITLInterceptor()
                .onSensitiveTool("transfer");

        ReActAgent agent = ReActAgent.of(chatModel)
                .style(ReActStyle.STRUCTURED_TEXT)
                .role("银行专员（使用工具给账号转钱）")
                .instruction("不要做多余的确认")
                .defaultToolAdd(new BankTools())
                .defaultInterceptorAdd(hitlInterceptor)
                .build();

        AgentSession session = InMemoryAgentSession.of("user_002");

        // 1. 发起请求
        String prompt = "给李四转账 2000 元。";
        System.out.println(">>> 第一次调用：尝试转账");
        ReActResponse resp1 = agent.prompt(prompt).session(session).call();

        // 验证被拦截
        Assertions.assertTrue(resp1.getTrace().isPending(), "应该是被中断状态");

        // 2. 人工拒绝
        HITLTask pendingTask = HITL.getPendingTask(session);
        Assertions.assertNotNull(pendingTask);
        System.out.println("收到审批申请: " + pendingTask.getToolName() + ", 原因: " + pendingTask.getComment());

        System.out.println(">>> 人工介入：拒绝该操作并给出理由");
        String rejectMsg = "抱歉，由于风险控制，此操作已被管理员拒绝。";
        HITL.reject(session, pendingTask.getToolName(), rejectMsg);

        // 3. 恢复执行
        System.out.println(">>> 第二次调用：恢复执行（验证拒绝逻辑）");
        ReActResponse resp2 = agent.prompt().session(session).call();
        String content = resp2.getContent();
        System.out.println(content);
        Assertions.assertFalse(resp2.getTrace().isPending(), "不应该被中断状态（而是结束）");

        // 验证清理工作
        //Assertions.assertNull(HITL.getPendingTask(session), "任务结束后状态必须清理干净");

        System.out.println("session: " + resp2.getSession().getMessages());
        System.out.println("workingMemory: " + resp2.getTrace().getWorkingMemory().getMessages());

        Assertions.assertEquals(3, resp2.getSession().getMessages().size());
        //Assertions.assertEquals(3, resp2.getTrace().getWorkingMemory().getMessages().size());
    }

    @Test
    public void testSkipFlow() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 注册拦截策略：模拟一个可选的审计工具或高危工具
        HITLInterceptor hitlInterceptor = new HITLInterceptor()
                .onTool("transfer", (trace, args) -> "当前账户存在波动，建议人工确认执行还是跳过");

        ReActAgent agent = ReActAgent.of(chatModel)
                .style(ReActStyle.STRUCTURED_TEXT)
                .role("银行专员（使用工具给账号转钱）")
                .instruction("不要做多余的确认")
                .defaultToolAdd(new BankTools())
                .defaultInterceptorAdd(hitlInterceptor)
                .build();

        AgentSession session = InMemoryAgentSession.of("user_003");

        // 1. 发起请求，触发拦截
        String prompt = "给李四转账 2000 元";
        System.out.println(">>> 第一次调用：尝试转账 500");
        ReActResponse resp1 = agent.prompt(prompt).session(session).call();
        Assertions.assertTrue(resp1.getTrace().isPending());

        // 2. 人工介入：选择“跳过”
        HITLTask pendingTask = HITL.getPendingTask(session);
        Assertions.assertNotNull(pendingTask);
        System.out.println("拦截任务: " + pendingTask.getToolName() + ", 参数: " + pendingTask.getArgs());

        System.out.println(">>> 人工介入：跳过该转账，并告知理由");
        String skipMsg = "财务系统正在维护，暂时跳过该笔转账，请先告知用户稍后再试。";
        HITL.skip(session, pendingTask.getToolName(), skipMsg);

        // 3. 恢复执行（验证 Agent 接收到 Observation 后继续思考）
        System.out.println(">>> 第二次调用：恢复执行（验证跳过逻辑）");
        ReActResponse resp2 = agent.prompt().session(session).call();

        String finalContent = resp2.getContent();
        System.out.println("最终回复: " + finalContent);

        // 验证逻辑：
        // 1. 最终回复不应包含“成功转账”（因为工具没被调用）
        // 2. 最终回复应包含 skipMsg 里的核心语意（证明 Agent 读取了反馈）
        Assertions.assertFalse(finalContent.contains("成功向 王五 转账"), "工具不应被执行");
        Assertions.assertFalse(resp2.getTrace().isPending(), "流程应正常结束");

        // 验证清理
        Assertions.assertNull(HITL.getPendingTask(session), "状态应清理");
    }

    @Test
    public void testApproveFlow() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 使用 evaluate 接口：动态判定
        HITLInterceptor hitlInterceptor = new HITLInterceptor()
                .onTool("transfer", (trace, args) -> {
                    double amount = Double.parseDouble(args.get("amount").toString());
                    return amount > 1000 ? "大额转账审批" : null;
                });

        ReActAgent agent = ReActAgent.of(chatModel)
                .style(ReActStyle.STRUCTURED_TEXT)
                .role("银行专员（使用工具给账号转钱）")
                .instruction("不要做多余的确认")
                .defaultToolAdd(new BankTools())
                .defaultInterceptorAdd(hitlInterceptor)
                .build();

        AgentSession session = InMemoryAgentSession.of("user_001");

        // 1. 触发拦截
        System.out.println(">>> 第一次调用：转账 5000");
        ReActResponse resp1 = agent.prompt("帮我给张三转账 5000 元。已确认过").session(session).call();
        Assertions.assertTrue(resp1.getTrace().isPending());

        // 2. 获取任务并使用新 Fluent API 批准且修正参数
        HITLTask pendingTask = HITL.getPendingTask(session);
        System.out.println("拦截原因: " + pendingTask.getComment());

        System.out.println(">>> 人工介入：修正金额为 800 并批准");
        HITLDecision decision = HITLDecision.approve()
                .comment("同意转账，但修正了金额")
                .modifiedArgs(Utils.asMap("to", "张三", "amount", 800.0));

        HITL.submit(session, pendingTask.getToolName(), decision);

        // 3. 恢复执行
        System.out.println(">>> 第二次调用：恢复执行");
        ReActResponse resp2 = agent.prompt().session(session).call();

        String finalContent = resp2.getContent();
        System.out.println("最终回复: " + finalContent);

        // 验证修正结果
        Assertions.assertTrue(finalContent.contains("800"), "应该执行修正后的 800 元");
        Assertions.assertNull(HITL.getPendingTask(session), "执行完后状态应清理");
    }

    public static class BankTools {
        @ToolMapping(description = "执行银行转账操作")
        public String transfer(@Param(description = "收款人姓名") String to,
                               @Param(description = "转账金额") double amount) {
            return "成功向 " + to + " 转账 ￥" + amount;
        }
    }
}