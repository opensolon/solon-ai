package org.noear.solon.ai.mcp.server;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.WebRxSseServerTransportProvider;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.MultiMap;

import java.io.OutputStream;
import java.util.Collection;
import java.util.Map;

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
    public <T> T attr(String name) {
        return context.attr(name);
    }

    @Override
    public Collection<String> attrNames() {
        return context.attrNames();
    }

    /// /////////////////////

    @Override
    public Map<String, Object> attrMap() {
        throw new UnsupportedOperationException();
    }

    @Override
    public MultiMap<String> headerMap() {
        throw new UnsupportedOperationException();
    }

    @Override
    public MultiMap<String> paramMap() {
        throw new UnsupportedOperationException();
    }

    @Override
    public MultiMap<String> cookieMap() {
        throw new UnsupportedOperationException();
    }

    @Override
    public MultiMap<UploadedFile> fileMap() {
        throw new UnsupportedOperationException();
    }

    @Override
    public OutputStream outputStream() {
        throw new UnsupportedOperationException();
    }
}