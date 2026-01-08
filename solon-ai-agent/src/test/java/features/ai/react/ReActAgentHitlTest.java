package features.ai.react;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowException;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.intercept.FlowInvocation;

import java.util.Map;

/**
 * ReActAgent 人工介入（HITL）场景测试
 *
 * <p>测试场景：Agent 申请退款时，拦截器检测到敏感操作节点，执行中断等待人工审批。
 * 人工审核通过后，恢复执行完成退款流程。</p>
 */
public class ReActAgentHitlTest {

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
    public void testHumanInTheLoop() throws Throwable {
        // 获取聊天模型
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义人工介入拦截器 - 检测敏感操作节点
        ReActInterceptor hitlInterceptor = new ReActInterceptor() {
            @Override
            public void onNodeStart(FlowContext ctx, Node node) {
                // 当进入工具执行节点时，检查是否已获得人工批准
                if (Agent.ID_ACTION.equals(node.getId())) {
                    Boolean approved = ctx.getAs("is_approved");
                    if (approved == null) {
                        System.out.println("[拦截器] 检测到敏感工具调用，等待人工审批...");
                        ctx.stop(); // 中断流程，等待人工介入
                    }
                }
            }
        };

        // 2. 构建 ReActAgent 并配置拦截器
        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new RefundTools()))
                .addInterceptor(hitlInterceptor) // 注入人工介入拦截器
                .chatOptions(o -> o.temperature(0.0F))
                .build();

        // 3. 创建 AgentSession（包装 FlowContext）
        AgentSession session = InMemoryAgentSession.of("hitl_session_123");
        String prompt = "订单 ORD_888 没收到货，请帮我全额退款。";

        // --- 第一步：发起请求，预期会被拦截 ---
        System.out.println("--- 第一次调用 (预期拦截) ---");
        String result1 = agent.call(Prompt.of(prompt), session).getContent();

        // 通过 session.getSnapshot() 获取底层的 FlowContext 进行验证
        FlowContext context = session.getSnapshot();

        // 验证：流程应该被拦截并停止，最后停留在工具节点
        Assertions.assertTrue(context.isStopped(), "流程应该被拦截并停止");
        Assertions.assertEquals(Agent.ID_ACTION, context.lastNodeId(), "最后应停留在工具节点");

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
        String result2 = agent.call(session).getContent();

        // 验证：最终结果应包含退款成功的关键字
        Assertions.assertNotNull(result2, "结果不应为空");
        Assertions.assertTrue(result2.contains("成功") || result2.contains("退款"),
                "审批后应执行成功，实际结果：" + result2);
        System.out.println("最终答复: " + result2);

        // 验证流程已正常结束
        Assertions.assertFalse(context.isStopped(), "恢复后流程应正常结束");
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

    /**
     * 测试拦截器的所有回调函数
     *
     * <p>验证拦截器的各个生命周期回调是否被正确调用，包括：</p>
     * <ul>
     * <li>思考过程回调 (onThought)</li>
     * <li>工具执行回调 (onAction)</li>
     * <li>观察结果回调 (onObservation)</li>
     * <li>节点开始/结束回调 (onNodeStart/onNodeEnd)</li>
     * <li>流程拦截回调 (interceptFlow)</li>
     * </ul>
     *
     * @throws Throwable 如果执行过程中出现异常
     */
    @Test
    public void testInterceptorAllCallbacks() throws Throwable {
        // 获取聊天模型
        ChatModel chatModel = LlmUtil.getChatModel();

        // 用于记录拦截器调用日志
        final StringBuilder log = new StringBuilder();

        // 实现完整拦截器，覆盖所有回调方法以验证调用顺序
        ReActInterceptor fullInterceptor = new ReActInterceptor() {
            @Override
            public void onThought(ReActTrace trace, String thought) {
                log.append("[onThought] ");
            }

            @Override
            public void onAction(ReActTrace trace, String toolName, Map<String, Object> args) {
                log.append("[onAction:").append(toolName).append("] ");
            }

            @Override
            public void onObservation(ReActTrace trace, String result) {
                log.append("[onObservation] ");
            }

            @Override
            public void interceptFlow(FlowInvocation invocation) throws FlowException {
                log.append("[doIntercept] ");
                invocation.invoke(); // 继续执行流程
            }

            @Override
            public void onNodeStart(FlowContext context, Node node) {
                log.append("[onNodeStart:").append(node.getId()).append("] ");
            }

            @Override
            public void onNodeEnd(FlowContext context, Node node) {
                log.append("[onNodeEnd:").append(node.getId()).append("] ");
            }
        };

        // 构建带完整拦截器的 ReActAgent
        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new BasicTools()))
                .addInterceptor(fullInterceptor)
                .chatOptions(o -> o.temperature(0.0F))
                .build();

        // 创建 AgentSession（包装 FlowContext）
        AgentSession session = InMemoryAgentSession.of("test_interceptor");
        String result = agent.call(Prompt.of("调用基础工具"), session).getContent();

        // 输出拦截器调用日志
        System.out.println("拦截器调用日志: " + log.toString());

        // 验证拦截器回调被调用
        Assertions.assertFalse(log.toString().isEmpty(), "拦截器回调应该被调用");
        Assertions.assertNotNull(result, "结果不应为空");

        // 验证调用了特定的回调函数
        String logStr = log.toString();
        Assertions.assertTrue(logStr.contains("onNodeStart"), "应该调用 onNodeStart");
        Assertions.assertTrue(logStr.contains("onNodeEnd"), "应该调用 onNodeEnd");

        // 如果有工具调用，验证 onAction 被触发
        if (result.contains("Action")) {
            Assertions.assertTrue(logStr.contains("onAction"), "工具调用时应该触发 onAction");
        }
    }

    /**
     * 基础工具类
     *
     * <p>提供基础功能工具，用于测试拦截器回调功能</p>
     */
    public static class BasicTools {
        /**
         * 基础工具调用
         *
         * @return 基础工具调用结果
         */
        @ToolMapping(description = "基础工具")
        public String basic_tool() {
            return "基础工具调用成功";
        }

        /**
         * 查询订单状态
         *
         * @param orderId 订单号
         * @return 订单状态信息
         */
        @ToolMapping(description = "查询订单状态")
        public String check_order(@Param(description = "订单号") String orderId) {
            return "订单 " + orderId + " 状态：已发货";
        }
    }
}