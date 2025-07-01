package demo.ai.mcp.server;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;

/**
 * @author noear 2025/7/1 created
 */
@Component
@McpServerEndpoint(sseEndpoint = "/auth/sse")
public class McpServerAuth implements Filter {
    @ToolMapping(description = "查询天气预报")
    public String getWeather(@Param(description = "城市位置") String location) {
        return "晴，14度";
    }

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        if (ctx.pathNew().endsWith("/auth/sse")) {
            if (ctx.param("user").equals("1") == false) {
                ctx.status(401);
                return;
            }
        }

        chain.doFilter(ctx);
    }
}
