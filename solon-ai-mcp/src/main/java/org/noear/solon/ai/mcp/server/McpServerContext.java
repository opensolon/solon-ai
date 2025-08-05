package org.noear.solon.ai.mcp.server;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebRxSseServerTransportProvider;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.handle.SessionState;
import org.noear.solon.core.util.KeyValues;
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
    private Context context;

    public McpServerContext(McpSyncServerExchange serverExchange) {
        this.serverExchange = serverExchange;

        this.context = (Context) serverExchange.transportContext().get(Context.class.getName());

        if (this.context != null) {
            for (KeyValues<String> kv : context.paramMap()) {
                for (String v : kv.getValues()) {
                    this.paramMap().add(kv.getKey(), v);
                    this.headerMap().add(kv.getKey(), v);
                }
            }

            for (KeyValues<String> kv : context.headerMap()) {
                for (String v : kv.getValues()) {
                    this.headerMap().add(kv.getKey(), v);
                }
            }
        } else {
            this.context = new ContextEmpty();
            this.headerMap().addAll(System.getenv());
        }
    }

    /**
     * 获取会话 id
     */
    @Override
    public String sessionId() {
        return serverExchange.sessionId();
    }

    /// ////////////////

    @Override
    public String realIp() {
        return context.realIp();
    }

    @Override
    public String remoteIp() {
        return context.remoteIp();
    }

    @Override
    public int remotePort() {
        return context.remotePort();
    }

    @Override
    public String referer() {
        return context.referer();
    }

    @Override
    public String userAgent() {
        return context.userAgent();
    }

    @Override
    public String protocol() {
        return context.protocol();
    }

    @Override
    public String queryString() {
        return context.queryString();
    }

    /// ////////////////

    @Override
    public SessionState sessionState() {
        return context.sessionState();
    }

    @Override
    public <T> T session(String name, Class<T> clz) {
        return context.session(name, clz);
    }

    @Override
    public double sessionAsDouble(String name) {
        return context.sessionAsDouble(name);
    }

    @Override
    public double sessionAsDouble(String name, double def) {
        return context.sessionAsDouble(name, def);
    }

    @Override
    public int sessionAsInt(String name) {
        return context.sessionAsInt(name);
    }

    @Override
    public int sessionAsInt(String name, int def) {
        return context.sessionAsInt(name, def);
    }

    @Override
    public long sessionAsLong(String name) {
        return context.sessionAsLong(name);
    }

    @Override
    public long sessionAsLong(String name, long def) {
        return context.sessionAsLong(name, def);
    }

    @Override
    public <T> T sessionOrDefault(String name, T def) {
        return context.sessionOrDefault(name, def);
    }

    @Override
    public void sessionSet(String name, Object val) {
        context.sessionSet(name, val);
    }

    @Override
    public void sessionClear() {
        context.sessionClear();
    }

    @Override
    public void sessionRemove(String name) {
        context.sessionRemove(name);
    }

    @Override
    public void sessionReset() {
        context.sessionReset();
    }

    /// /////////////////


    @Override
    public String cookie(String name) {
        return context.cookie(name);
    }

    @Override
    public String cookieOrDefault(String name, String def) {
        return context.cookieOrDefault(name, def);
    }

    @Override
    public String[] cookieValues(String name) {
        return context.cookieValues(name);
    }

    @Override
    public Collection<String> cookieNames() {
        return context.cookieNames();
    }

    @Override
    public MultiMap<String> cookieMap() {
        throw new UnsupportedOperationException();
    }
}