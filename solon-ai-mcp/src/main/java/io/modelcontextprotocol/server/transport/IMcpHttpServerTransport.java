/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.server.transport;

import org.noear.solon.SolonApp;

/**
 * @author noear
 */
public interface IMcpHttpServerTransport {
    void toHttpHandler(SolonApp app);

    String getMcpEndpoint();
}
