package demo.ai.mcp.server;

import io.modelcontextprotocol.spec.McpSchema;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.rx.Completable;
import org.noear.solon.rx.handle.RxContext;
import org.noear.solon.rx.handle.RxFilter;
import org.noear.solon.rx.handle.RxFilterChain;

/**
 * @author noear 2025/4/30 created
 */
@Component
public class McpMessageFilter implements RxFilter {
    @Override
    public Completable doFilter(RxContext rxCtx, RxFilterChain chain) {
        McpSchema.JSONRPCMessage message = rxCtx.attr("message");

        if (message instanceof McpSchema.JSONRPCRequest) {
            //说明是 mcp 消息调用
            Context ctx = rxCtx.toContext();
            McpSchema.JSONRPCRequest req = (McpSchema.JSONRPCRequest) message;
            String messageEndpoint = ctx.pathNew();
            String toolName = req.getMethod();

            if (false) {
                ctx.status(401);
                return Completable.complete();
            }
        }

        return chain.doFilter(rxCtx);
    }
}
