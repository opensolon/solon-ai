package demo.ai.mcp.server;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;

/**
 * @author noear 2025/5/28 created
 */
@Slf4j
@Component
public class FilterImpl implements Filter {
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        if (ctx.pathNew().equals("/demo2/sse") || ctx.pathNew().equals("/demo4/sse")) {
            log.warn(">> params: " + ctx.paramMap());
            log.warn(">> headers: " + ctx.headerMap());
        }

        chain.doFilter(ctx);
    }
}