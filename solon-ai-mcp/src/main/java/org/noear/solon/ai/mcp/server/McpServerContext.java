package org.noear.solon.ai.mcp.server;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.WebRxSseServerTransportProvider;
import org.noear.solon.core.FactoryManager;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextHolder;
import org.noear.solon.lang.Nullable;

/**
 * Mcp 服务端请求上下文
 *
 * @author noear
 * @since 3.2
 */
public class McpServerContext {
    private final static ThreadLocal<McpServerContext> threadLocal = FactoryManager.getGlobal().newThreadLocal(ContextHolder.class, false);

    /**
     * 设置当前线程的上下文
     */
    public static void currentSet(McpServerContext context) {
        threadLocal.set(context);
    }

    /**
     * 移除当前线程的上下文
     */
    public static void currentRemove() {
        threadLocal.remove();
    }

    /**
     * 获取当前线程的上下文
     */
    public static McpServerContext current() {
        return threadLocal.get();
    }


    /// /////////////////////////
    private final McpSyncServerExchange serverExchange;
    private final Context context;

    public McpServerContext(McpSyncServerExchange serverExchange) {
        this.serverExchange = serverExchange;
        this.context = ((WebRxSseServerTransportProvider.WebRxMcpSessionTransport) serverExchange.getSession().getTransport()).getContext();
    }

    /**
     * 获取会话Id
     */
    public String getSessionId() {
        return serverExchange.getSession().getId();
    }

    /**
     * 获取会话请求头
     */
    public String getHeader(String name) {
        return context.header(name);
    }

    /**
     * 获取会话请求头
     */
    public String[] getHeaderValues(String name) {
        return context.headerValues(name);
    }

    /**
     * 获取会话属性
     */
    public @Nullable <T> T getAttr(String name) {
        return context.attr(name);
    }
}