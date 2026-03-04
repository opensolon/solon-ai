/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.mcp.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.handle.SessionState;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.KeyValues;
import org.noear.solon.core.util.MultiMap;

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
    private final McpAsyncServerExchange exchange;
    private Context context;
    private String sessionId;

    public McpServerContext(McpAsyncServerExchange exchange, McpTransportContext transportContext) {
        //通响 transportContext 获取连接时的 context
        this.exchange = exchange;
        this.context = (Context) transportContext.get(Context.class.getName());

        if (this.context != null) {
            this.sessionId = context.sessionId();
            //如果有，则是 http
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

            //传递上下文的attr
            if(Assert.isNotEmpty(context.attrMap())) {
                this.attrSet(context.attrMap());
            }
        } else {
            //如果没有，则是 stdio
            this.sessionId = "null";
            this.context = new ContextEmpty();
            this.headerMap().addAll(System.getenv());
        }
    }

    @Override
    public Object request() {
        return exchange;
    }

    /**
     * 获取会话 id
     */
    @Override
    public String sessionId() {
        return sessionId;
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