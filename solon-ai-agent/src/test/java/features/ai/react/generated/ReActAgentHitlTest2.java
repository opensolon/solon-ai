package features.ai.react.generated;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

/**
 * ReActAgent 人工介入（HITL）场景测试
 *
 * <p>测试场景：Agent 申请退款时，通过自定义节点检测敏感操作，执行中断等待人工审批。
 * 人工审核通过后，恢复执行完成退款流程。</p>
 */
public class ReActAgentHitlTest2 {

    /**
     * 测试人工介入审批流程
     *
     * <p>验证流程：</p>
     * <ol>
     * <li>发起退款请求 -> 触发拦截器中断</li>
     * <li>人工介入审批 -> 注入批准信号</li>
     * <li>恢复执行 -> 完成退款操作</li>
     * </ol>
     *
     * @throws Throwable 如果执行过程中出现异常
     */
    @Test
    public void testHumanInTheLoop2() throws Throwable {
        // 获取聊天模型
        ChatModel chatModel = LlmUtil.getChatModel();

        // 2. 构建 ReActAgent 并配置拦截器
        ReActAgent agent = ReActAgent.of(chatModel)
                .defaultToolAdd(new RefundTools())
                .modelOptions(o -> o.temperature(0.0))
                .graphAdjuster(spec-> {
                    spec.getNode(ReActAgent.ID_ACTION_AFT).task(new HumanAuditTask());
                })
                .build();

        // 3. 创建 AgentSession（包装 FlowContext）
        AgentSession session = InMemoryAgentSession.of("hitl_session_123");
        String prompt = "订单 ORD_888 没收到货，请帮我全额退款。";

        // --- 第一步：发起请求，预期会被拦截 ---
        System.out.println("--- 第一次调用 (预期拦截) ---");
        String result1 = agent.prompt(prompt)
                .session(session)
                .call()
                .getContent();

        // 通过 session.getSnapshot() 获取底层的 FlowContext 进行验证
        FlowContext context = session.getSnapshot();

        // 验证：流程应该被拦截并停止，最后停留在工具节点
        Assertions.assertTrue(context.isStopped(), "流程应该被拦截并停止");
        Assertions.assertEquals(ReActAgent.ID_ACTION_AFT, context.lastNodeId(), "最后应停留在工具节点之前");

        // 获取执行状态追踪，验证已有执行步骤
        ReActTrace state = context.getAs("__" + agent.name());
        Assertions.assertTrue(state.getStepCount() > 0, "应该已有执行步骤");
        System.out.println("当前状态：" + state.getRoute());

        // --- 第二步：人工介入，注入批准信号 ---
        System.out.println("\n--- 人工介入：批准退款 ---");
        context.put("is_approved", true);

        // --- 第三步：恢复执行 ---
        System.out.println("--- 第二次调用 (恢复执行) ---");
        // 恢复时传入相同的 session，prompt 会从 state 中自动获取
        String result2 = agent.prompt(prompt)
                .session(session)
                .call()
                .getContent();

        // 验证：最终结果应包含退款成功的关键字
        Assertions.assertNotNull(result2, "结果不应为空");
        Assertions.assertTrue(result2.contains("成功") || result2.contains("退款"),
                "审批后应执行成功，实际结果：" + result2);
        System.out.println("最终答复: " + result2);

        // 验证流程已正常结束
        Assertions.assertFalse(context.isStopped(), "恢复后流程应正常结束");
    }

    /**
     * 自定义人工审核任务组件
     * 逻辑：检查 is_approved 标志，若无则 stop 流程，若有则路由至下一个节点（工具执行节点）
     */
    static class HumanAuditTask implements TaskComponent {
        @Override
        public void run(FlowContext ctx, Node node) throws Throwable {
            // 当进入工具执行节点时，检查是否已获得人工批准
            Boolean approved = ctx.getAs("is_approved");
            if (approved == null) {
                System.out.println("[拦截器] 检测到敏感工具调用，等待人工审批...");
                ctx.stop(); // 中断流程，等待人工介入
            }
        }
    }

    /**
     * 退款工具类
     *
     * <p>提供退款相关功能工具，用于测试敏感操作拦截场景</p>
     */
    public static class RefundTools {
        /**
         * 执行退款操作
         *
         * @param orderId 订单号
         * @return 退款结果信息
         */
        @ToolMapping(description = "执行退款操作")
        public String do_refund(@Param(description = "订单号") String orderId) {
            return "订单 " + orderId + " 已退款成功，金额将原路返回。";
        }
    }
}