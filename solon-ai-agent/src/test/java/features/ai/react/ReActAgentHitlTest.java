package features.ai.react;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowException;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.intercept.FlowInvocation;

import java.util.Map;

/**
 * 人工介入（HITL）场景测试
 * 场景：Agent 申请退款，拦截器发现是 node_tools 节点且涉及敏感操作，执行中断。
 * 人工审核通过后，恢复执行。
 */
public class ReActAgentHitlTest {

    @Test
    public void testHumanInTheLoop() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义人工介入拦截器
        ReActInterceptor hitlInterceptor = new ReActInterceptor() {
            @Override
            public void onNodeStart(FlowContext ctx, Node node) {
                // 如果进入工具执行节点，且尚未获得人工批准
                if (Agent.ID_ACTION.equals(node.getId())) {
                    Boolean approved = ctx.getAs("is_approved");
                    if (approved == null) {
                        System.out.println("[拦截器] 检测到敏感工具调用，等待人工审批...");
                        ctx.stop(); // 关键：中断流程
                    }
                }
            }
        };

        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new RefundTools()))
                .addInterceptor(hitlInterceptor) // 注入拦截器
                .chatOptions(o -> o.temperature(0.0F))
                .build();

        FlowContext context = FlowContext.of("hitl_session_123");
        String prompt = "订单 ORD_888 没收到货，请帮我全额退款。";

        // --- 第一步：发起请求，预期会被拦截 ---
        System.out.println("--- 第一次调用 (预期拦截) ---");
        String result1 = agent.call(context, prompt).getContent();

        // 验证：结果应为空（或中间态），且 context 处于 stopped 状态
        Assertions.assertTrue(context.isStopped(), "流程应该被拦截并停止");
        Assertions.assertEquals(Agent.ID_ACTION, context.lastNodeId(), "最后停留在工具节点");

        ReActTrace state = context.getAs("__" + agent.name());
        Assertions.assertTrue(state.getStepCount() > 0);
        System.out.println("当前状态：" + state.getRoute());

        // --- 第二步：人工介入，注入批准信号 ---
        System.out.println("\n--- 人工介入：批准退款 ---");
        context.put("is_approved", true);

        // --- 第三步：恢复执行 ---
        System.out.println("--- 第二次调用 (恢复执行) ---");
        // 恢复时传入原 context，prompt 会从 state 中自动获取
        String result2 = agent.call(context).getContent();

        // 验证：最终结果应包含退款成功的关键字
        Assertions.assertNotNull(result2);
        Assertions.assertTrue(result2.contains("成功") || result2.contains("退款"), "审批后应执行成功");
        System.out.println("最终答复: " + result2);
    }

    public static class RefundTools {
        @ToolMapping(description = "执行退款操作")
        public String do_refund(@Param(description = "订单号") String orderId) {
            return "订单 " + orderId + " 已退款成功，金额将原路返回。";
        }
    }


    @Test
    public void testInterceptorAllCallbacks() throws Throwable {
        // 测试：拦截器的所有回调函数
        ChatModel chatModel = LlmUtil.getChatModel();

        final StringBuilder log = new StringBuilder();

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
                invocation.invoke();
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

        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new BasicTools()))
                .addInterceptor(fullInterceptor)
                .chatOptions(o -> o.temperature(0.0F))
                .build();

        FlowContext context = FlowContext.of("test_interceptor");
        String result = agent.call(context, "调用基础工具").getContent();

        System.out.println("拦截器调用日志: " + log.toString());
        Assertions.assertFalse(log.toString().isEmpty(), "拦截器回调应该被调用");
        Assertions.assertNotNull(result);

        // 验证调用了特定的回调
        String logStr = log.toString();
        Assertions.assertTrue(logStr.contains("onNodeStart"), "应该调用 onNodeStart");
        Assertions.assertTrue(logStr.contains("onNodeEnd"), "应该调用 onNodeEnd");
        if (result.contains("Action")) {
            Assertions.assertTrue(logStr.contains("onAction"), "工具调用时应该触发 onAction");
        }
    }

    public static class BasicTools {
        @ToolMapping(description = "基础工具")
        public String basic_tool() {
            return "基础工具调用成功";
        }

        @ToolMapping(description = "查询订单状态")
        public String check_order(@Param(description = "订单号") String orderId) {
            return "订单 " + orderId + " 状态：已发货";
        }
    }
}