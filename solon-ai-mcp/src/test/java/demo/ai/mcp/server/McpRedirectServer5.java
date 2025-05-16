package demo.ai.mcp.server;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;

/**
 * @author noear 2025/4/8 created
 */
@Mapping("/demo5/jump")
@Controller
@McpServerEndpoint(sseEndpoint = "/demo5/sse")
public class McpRedirectServer5 {
    @ToolMapping(description = "查询天气预报", returnDirect = true)
    public String getWeather(@Param(description = "城市位置") String location, Context ctx) {
        System.out.println("------------: sessionId: " + ctx.sessionId());

        ctx.realIp();

        return "晴，14度";
    }

    @Mapping("sse")
    public void sse(Context ctx) throws Exception {
        ctx.redirect("/demo5/sse", 307);
    }
}