package features.ai.react.hitl;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActResponse;
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
                .defaultToolAdd(new BankTools())
                .defaultInterceptorAdd(hitlInterceptor)
                .build();

        AgentSession session = InMemoryAgentSession.of("user_002");

        // 1. 发起请求
        String prompt = "给李四转账 2000 元。已确认过（不要再确认了）";
        System.out.println(">>> 第一次调用：尝试转账");
        ReActResponse resp1 = agent.prompt(prompt).session(session).call();

        // 验证被拦截
        Assertions.assertTrue(resp1.getTrace().isInterrupted(), "应该是被中断状态");

        // 2. 人工拒绝
        HITLTask pendingTask = HITL.getPendingTask(session);
        Assertions.assertNotNull(pendingTask);
        System.out.println("收到审批申请: " + pendingTask.getToolName() + ", 原因: " + pendingTask.getComment());

        System.out.println(">>> 人工介入：拒绝该操作并给出理由");
        String rejectMsg = "抱歉，由于风险控制，此操作已被管理员拒绝。";
        HITL.reject(session, pendingTask.getToolName(), rejectMsg);

        // 3. 恢复执行
        System.out.println(">>> 第二次调用：恢复执行（验证拒绝逻辑）");
        String content = agent.prompt().session(session).call().getContent();
        System.out.println(content);

        // 验证清理工作
        Assertions.assertNull(HITL.getPendingTask(session), "任务结束后状态必须清理干净");
    }

    @Test
    public void testFullHITLFlow() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 使用 evaluate 接口：动态判定
        HITLInterceptor hitlInterceptor = new HITLInterceptor()
                .onTool("transfer", (trace, args) -> {
                    double amount = Double.parseDouble(args.get("amount").toString());
                    return amount > 1000 ? "大额转账审批" : null;
                });

        ReActAgent agent = ReActAgent.of(chatModel)
                .defaultToolAdd(new BankTools())
                .defaultInterceptorAdd(hitlInterceptor)
                .build();

        AgentSession session = InMemoryAgentSession.of("user_001");

        // 1. 触发拦截
        System.out.println(">>> 第一次调用：转账 5000");
        ReActResponse resp1 = agent.prompt("帮我给张三转账 5000 元。已确认过").session(session).call();
        Assertions.assertTrue(resp1.getTrace().isInterrupted());

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