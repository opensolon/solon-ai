package org.noear.solon.ai.mcp.server;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.WebRxSseServerTransportProvider;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.util.MultiMap;

import java.util.Collection;

/**
 * Mcp 服务端请求上下文
 *
 * @author noear
 * @since 3.2
 */
public class McpServerContext extends ContextEmpty {
    /// /////////////////////////
    private final McpSyncServerExchange serverExchange;
    private final Context context;

    public McpServerContext(McpSyncServerExchange serverExchange) {
        this.serverExchange = serverExchange;
        this.context = ((WebRxSseServerTransportProvider.WebRxMcpSessionTransport) serverExchange.getSession().getTransport()).getContext();
    }

    /**
     * 获取会话 id
     */
    @Override
    public String sessionId() {
        return serverExchange.getSession().getId();
    }

    /**
     * 获取会话 header
     */
    @Override
    public String header(String name) {
        return context.header(name);
    }

    /**
     * 获取会话 header
     */
    @Override
    public String[] headerValues(String name) {
        return context.headerValues(name);
    }

    /**
     * 获取会话 headerNames
     */
    @Override
    public Collection<String> headerNames() {
        return context.headerNames();
    }

    @Override
    public MultiMap<String> headerMap() {
        throw new UnsupportedOperationException();
    }
}