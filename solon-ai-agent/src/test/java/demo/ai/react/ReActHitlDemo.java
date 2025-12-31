package demo.ai.react;

import demo.ai.agent.LlmUtil;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NodeType;

public class ReActHitlDemo {
    public static void main(String[] args) throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义拦截器：如果是 node_tools 节点且没审批，就拦住它
        ReActInterceptor hitlInterceptor = ReActInterceptor.builder()
                .onNodeStart((ctx, node) -> {
                    if (ReActTrace.ROUTE_ACTION.equals(node.getId())) {
                        if (ctx.get("approved") == null) {
                            System.out.println("[拦截器] 发现敏感操作，需要人工审批...");
                            ctx.stop(); // 挂起流程
                        }
                    }
                })
                .build();

        ReActAgent agent = ReActAgent.builder(chatModel)
                .interceptor(hitlInterceptor) // 注入拦截器
                .addTool(new MethodToolProvider(new WeatherTools()))
                .build();

        FlowContext context = FlowContext.of("hitl_session_1");

        // --- 第一次执行：会触发拦截 ---
        System.out.println("第一次运行...");
        String response1 = agent.call(context, "帮我查询北京天气并转账100元");
        System.out.println("response1: " + response1);

        if (context.lastNode().getType() != NodeType.END) {
            System.out.println("流程已挂起，当前节点：" + context.lastNodeId());
        }

        // --- 模拟人工介入 ---
        System.out.println("\n--- 人工在 UI 界面点击了‘同意’ ---");
        context.put("approved", true);

        // --- 第二次执行：恢复运行 ---
        System.out.println("恢复运行...");
        String response2 = agent.call(context, null); // 恢复时 prompt 传 null 即可，状态在 context 里

        System.out.println("最终回复：" + response2);
    }

    public static class WeatherTools {
        @ToolMapping(name = "get_weather", description = "查询指定城市的天气状况")
        public String get_weather(@Param(description = "城市名称") String city) {
            if (city.contains("北京")) {
                return "北京现在晴朗，气温 10 摄氏度，风力 4 级。";
            } else {
                return city + "小雨，18 度。";
            }
        }
    }
}