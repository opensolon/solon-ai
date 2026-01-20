package demo.ai.react;

import demo.ai.llm.LlmUtil;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

public class ReActHitlDemo {
    public static void main(String[] args) throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义拦截器：如果是 node_tools 节点且没审批，就拦住它
        ReActInterceptor hitlInterceptor = new ReActInterceptor() {
            @Override
            public void onNodeStart(FlowContext ctx, Node node) {
                if (ReActAgent.ID_ACTION.equals(node.getId())) {
                    if (ctx.get("approved") == null) {
                        System.out.println("[拦截器] 发现敏感操作，需要人工审批...");
                        ctx.stop(); // 挂起流程
                    }
                }
            }
        };

        ReActAgent agent = ReActAgent.of(chatModel)
                .defaultInterceptorAdd(hitlInterceptor) // 注入拦截器
                .defaultToolAdd(new MethodToolProvider(new WeatherTools()))
                .build();

        AgentSession session = InMemoryAgentSession.of("hitl_session_1");

        // --- 第一次执行：会触发拦截 ---
        System.out.println("第一次运行...");
        String response1 = agent.call(Prompt.of("帮我查询北京天气并转账100元"), session).getContent();
        System.out.println("response1: " + response1);

        if (session.getSnapshot().isStopped()) {
            System.out.println("流程已挂起，当前节点：" + session.getSnapshot().lastNodeId());
        }

        // --- 模拟人工介入 ---
        System.out.println("\n--- 人工在 UI 界面点击了‘同意’ ---");
        session.getSnapshot().put("approved", true);

        // --- 第二次执行：恢复运行 ---
        System.out.println("恢复运行...");
        String response2 = agent.call(null, session).getContent(); // 恢复时 prompt 传 null 即可，状态在 context 里

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