package demo.ai.mcp.demo;

import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.annotation.ToolParam;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.mcp.server.McpServerEndpointProvider;
import org.noear.solon.core.handle.Context;

/**
 * @author noear 2025/4/15 created
 */
public class DemoApp {
    public static void main(String[] args) throws Throwable {
        McpServerEndpointProvider serverProvider = Utils.loadProps("classpath:demo.yml")
                .getProp("solon.ai.mcp.server")
                .toBean(McpServerEndpointProvider.class);

        //添加工具
        serverProvider.addTool(new MethodToolProvider(new DemoTool()));

        //完成启动
        serverProvider.start();
        serverProvider.postStart();

        Context ctx = Context.current();

//        serverProvider.getTransport().getSseEndpoint();
//        serverProvider.getTransport().getMessageEndpoint();
//        serverProvider.getTransport().handleSseConnection(ctx);
//        serverProvider.getTransport().handleMessage(ctx);
    }

    public static class DemoTool {
        @ToolMapping(description = "查询天气预报")
        public String getWeather(@ToolParam(description = "城市位置") String location) {
            return "晴，14度";
        }
    }
}