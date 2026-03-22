package demo.ai.mcp.server_group;

import io.modelcontextprotocol.server.transport.WebRxStatelessServerTransport;
import org.noear.solon.Solon;
import org.noear.solon.ai.annotation.PromptMapping;
import org.noear.solon.ai.annotation.ResourceMapping;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.MethodPromptProvider;
import org.noear.solon.ai.chat.resource.MethodResourceProvider;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.McpServerEndpointProvider;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.Context;

import java.util.Arrays;
import java.util.Collection;

public class App {
    public static void main(String[] args) {
        Solon.start(App.class, args);

        WebRxStatelessServerTransport serverTransport1 = buildMcpServerTransport("group1");
        WebRxStatelessServerTransport serverTransport2 = buildMcpServerTransport("group2");

        //接入时进行路由分发
        Solon.app().router().get("/mcp", ctx -> {
            int role = ctx.paramAsInt("role");

            if (role == 1) {
                serverTransport1.handleGet(ctx);
            } else {
                serverTransport2.handleGet(ctx);
            }
        });

        Solon.app().router().post("/mcp", ctx -> {
            int role = ctx.paramAsInt("role");

            if (role == 1) {
                serverTransport1.handlePost(ctx);
            } else {
                serverTransport2.handlePost(ctx);
            }
        });
    }

    public static WebRxStatelessServerTransport buildMcpServerTransport(String group) {
        Object mcpBean = Solon.context().getBean(group);

        if (mcpBean == null) {
            return null;
        }

        McpServerEndpointProvider endpointProvider = McpServerEndpointProvider.builder()
                .name(group)
                .channel(McpChannel.STREAMABLE_STATELESS)
                .sseEndpoint("/mcp")
                .build();

        //不同的 group ，用不同的 tools
        endpointProvider.addTool(new MethodToolProvider(mcpBean));
        endpointProvider.addResource(new MethodResourceProvider(mcpBean));
        endpointProvider.addPrompt(new MethodPromptProvider(mcpBean));

        //这里用 build（不用 start，否则会自动注册到路由器）
        //(WebRxStatelessServerTransport) 要与 .channel(McpChannel.STREAMABLE_STATELESS) 对应起来
        return (WebRxStatelessServerTransport) endpointProvider.getServer().build();
    }

    @Component("group1")
    public static class McpServerTool {
        //@Inject //容器形态可以注入（否则，要自己组件需要的服务）
        //UserService userService;

        @ToolMapping(description = "查询天气预报")
        public String getWeather(@Param(description = "城市位置") String location, Context ctx) {
            return "晴，14度";
        }

        @ResourceMapping(uri = "config://app-version", description = "获取应用版本号", mimeType = "text/config")
        public String getAppVersion() {
            return "v3.2.0";
        }

        @PromptMapping(description = "生成关于某个主题的提问")
        public Collection<ChatMessage> askQuestion(@Param(description = "主题") String topic) {
            return Arrays.asList(
                    ChatMessage.ofUser("请解释一下'" + topic + "'的概念？")
            );
        }
    }
}