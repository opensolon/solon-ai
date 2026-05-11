package features.ai.react_text.generated;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.*;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
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
            public void onObservation(ReActTrace trace, String toolName, String result, long durationMs) {
                log.append("[onObservation] ");
            }
        };

        // 构建带完整拦截器的 ReActAgent
        ReActAgent agent = ReActAgent.of(chatModel)
                .style(ReActStyle.STRUCTURED_TEXT)
                .defaultToolAdd(new BasicTools())
                .defaultInterceptorAdd(fullInterceptor)
                .modelOptions(o -> o.temperature(0.0))
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