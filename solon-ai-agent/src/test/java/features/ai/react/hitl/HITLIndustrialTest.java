package features.ai.react.hitl;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.annotation.Param;

import java.util.HashMap;
import java.util.Map;

public class HITLIndustrialTest {

    @Test
    public void testRejectFlow() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 配置拦截器：transfer 工具始终需要介入
        HITLInterceptor hitlInterceptor = new HITLInterceptor()
                .onSensitiveTool("transfer");

        ReActAgent agent = ReActAgent.of(chatModel)
                .defaultToolAdd(new BankTools())
                .defaultInterceptorAdd(hitlInterceptor)
                .build();

        AgentSession session = InMemoryAgentSession.of("user_002");

        // 1. 发起请求
        String prompt = "给李四转账 2000 元。已确认过";
        System.out.println(">>> 尝试转账...");
        ReActResponse resp1 = agent.prompt(prompt).session(session).call();

        // 验证拦截
        Assertions.assertTrue(resp1.getTrace().isInterrupted(), "应该是被中断状态");

        // 2. 人工拒绝
        HITLTask pendingTask = HITL.getPendingTask(session);
        System.out.println("收到审批申请: " + pendingTask.getToolName());

        System.out.println(">>> 人工介入：拒绝该操作");
        HITL.reject(session, pendingTask.getToolName());

        // 3. 恢复执行
        System.out.println(">>> 恢复执行，观察 AI 如何处理拒绝...");
        ReActResponse resp2 = agent.prompt().session(session).call();

        // 验证结果
        System.out.println("AI 最终回复: " + resp2.getContent());
        //Assertions.assertFalse(resp2.getTrace().isInterrupted(), "拒绝后流程应继续并结束");

        // 通常 AI 会回复类似：“抱歉，转账申请被拒绝了”
        boolean toldUserRejected = resp2.getContent().contains("拒绝") || resp2.getContent().contains("未通过");
        Assertions.assertTrue(toldUserRejected, "AI 应该告知用户操作被拒绝");

        // 验证清理工作
        Assertions.assertNull(HITL.getPendingTask(session), "任务结束后 pendingTask 应该被清理");
    }

    @Test
    public void testFullHITLFlow() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 初始化拦截器：配置超过 1000 元必须审批的策略
        HITLInterceptor hitlInterceptor = new HITLInterceptor()
                .onTool("transfer", (trace, args) -> {
                    double amount = Double.parseDouble(args.get("amount").toString());
                    return amount > 1000;
                });

        ReActAgent agent = ReActAgent.of(chatModel)
                .defaultToolAdd(new BankTools())
                .defaultInterceptorAdd(hitlInterceptor)
                .build();

        AgentSession session = InMemoryAgentSession.of("user_001");

        // --- 场景：用户要求转账 5000 元（触发拦截） ---
        String prompt = "帮我给张三转账 5000 元。已确认过";

        System.out.println(">>> 第一次调用：尝试大额转账");
        ReActResponse resp1 = agent.prompt(prompt).session(session).call();

        // 验证是否被中断
        Assertions.assertTrue(resp1.getTrace().isInterrupted(), "应该被拦截器中断");
        System.out.println("中断原因: " + resp1.getTrace().getInterruptReason());

        // --- 场景：人工检查挂起任务，并决定修改参数（比如只允许转 800） ---
        HITLTask pendingTask = HITL.getPendingTask(session);
        Assertions.assertEquals("transfer", pendingTask.getToolName());
        System.out.println("待处理工具: " + pendingTask.getToolName());
        System.out.println("原始参数: " + pendingTask.getArgs());

        // 人工介入：修改金额为 800，并批准
        Map<String, Object> modifiedArgs = new HashMap<>();
        modifiedArgs.put("to", "张三");
        modifiedArgs.put("amount", 800.0);

        System.out.println(">>> 人工介入：将金额修改为 800 并批准");
        HITL.approveWithModifiedArgs(session, "transfer", modifiedArgs);

        // --- 场景：恢复执行 ---
        System.out.println(">>> 第二次调用：恢复执行");
        // 注意：恢复执行时 prompt() 传空，会自动加载 session 里的状态
        ReActResponse resp2 = agent.prompt().session(session).call();

        String finalContent = resp2.getContent();
        System.out.println("最终结果: " + finalContent);

        // 验证：最终执行的是修改后的金额
        Assertions.assertTrue(finalContent.contains("800"), "执行结果应包含修改后的金额");
        Assertions.assertTrue(finalContent.contains("成功"), "流程应执行完毕");
    }

    public static class BankTools {
        @ToolMapping(description = "执行银行转账操作")
        public String transfer(@Param(description = "收款人姓名") String to,
                               @Param(description = "转账金额") double amount) {
            return "成功向 " + to + " 转账 ￥" + amount;
        }
    }
}