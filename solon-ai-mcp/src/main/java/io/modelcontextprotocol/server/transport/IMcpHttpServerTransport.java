/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.server.transport;

import org.noear.solon.SolonApp;

/**
 * MCP (Model Context Protocol) HTTP 服务端传输接口。
 *
 * <p>定义了 MCP 服务端传输层在 Solon 应用中注册与清理 HTTP 路由端点的行为规范。</p>
 *
 * @author noear
 */
public interface IMcpHttpServerTransport {
    /**
     * 将 MCP 服务所需的 HTTP 路由端点注册到指定的 Solon 应用中。
     *
     * @param app 当前 Solon 应用实例
     */
    void setupHttpHandlers(SolonApp app);

    /**
     * 从指定的 Solon 应用中注销并清理已注册的 MCP HTTP 路由端点。
     *
     * @param app 当前 Solon 应用实例
     */
    void cleanupHttpHandlers(SolonApp app);

    /**
     * 获取 MCP 服务的主要 HTTP 端点路径（例如 SSE 或 Message 端点基础路径）。
     *
     * @return MCP 端点 URI 路径字符串
     */
    String getMcpEndpoint();
}
