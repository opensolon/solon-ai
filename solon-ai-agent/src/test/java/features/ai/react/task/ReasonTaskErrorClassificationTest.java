/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package features.ai.react.task;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.LlmErrorEvent;
import org.noear.solon.ai.agent.react.task.ReasonTask;

import javax.net.ssl.SSLHandshakeException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReasonTask 模型服务基础设施错误分类测试。
 */
public class ReasonTaskErrorClassificationTest {
    @Test
    public void classifiesInfrastructureErrors() throws Exception {
        assertTrue(invoke("isSslException", new SSLHandshakeException("PKIX path building failed")));
        assertTrue(invoke("isDnsException", new UnknownHostException("llm.example.com")));
        assertTrue(invoke("isConnectionRefused", new ConnectException("Connection refused")));
        assertTrue(invoke("isNetworkException", new NoRouteToHostException("No route to host")));
        assertTrue(invoke("isTimeoutException", new SocketTimeoutException("Read timed out")));
    }

    @Test
    public void usesConservativeRetryStatuses() throws Exception {
        assertTrue(invokeRetryableHttpStatus(429));
        assertTrue(invokeRetryableHttpStatus(502));
        assertTrue(invokeRetryableHttpStatus(504));
        assertTrue(invokeRetryableHttpStatus(529));
        assertTrue(!invokeRetryableHttpStatus(501));
        assertTrue(!invokeRetryableHttpStatus(505));
        assertTrue(!invokeRetryableHttpStatus(525));
    }

    @Test
    public void classifiesGatewayHttpStatuses() throws Exception {
        assertEquals(LlmErrorEvent.Type.RATE_LIMIT, invokeHttpClassification(429));
        assertEquals(LlmErrorEvent.Type.UPSTREAM_UNAVAILABLE, invokeHttpClassification(502));
        assertEquals(LlmErrorEvent.Type.UPSTREAM_UNAVAILABLE, invokeHttpClassification(503));
        assertEquals(LlmErrorEvent.Type.UPSTREAM_UNAVAILABLE, invokeHttpClassification(504));
        assertEquals(LlmErrorEvent.Type.SERVER_ERROR, invokeHttpClassification(500));
        assertEquals(LlmErrorEvent.Type.TIMEOUT, invokeHttpClassification(408));
        assertEquals(LlmErrorEvent.Type.HTTP, invokeHttpClassification(401));
    }

    @Test
    public void errorTypeKeepsStableProtocolValues() {
        assertEquals("rate_limit", LlmErrorEvent.Type.RATE_LIMIT.value());
        assertEquals("upstream_unavailable", LlmErrorEvent.Type.UPSTREAM_UNAVAILABLE.value());
        assertEquals("server_error", LlmErrorEvent.Type.SERVER_ERROR.value());
        assertEquals("connection_refused", LlmErrorEvent.Type.CONNECTION_REFUSED.value());
        assertEquals("ssl", LlmErrorEvent.Type.SSL.value());
    }

    private LlmErrorEvent.Type invokeHttpClassification(int code) throws Exception {
        Method method = ReasonTask.class.getDeclaredMethod("classifyHttpStatus", int.class);
        method.setAccessible(true);
        return (LlmErrorEvent.Type) method.invoke(null, code);
    }

    private boolean invokeRetryableHttpStatus(int code) throws Exception {
        Method method = ReasonTask.class.getDeclaredMethod("isRetryableHttpStatus", int.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, code);
    }

    private boolean invoke(String name, Throwable error) throws Exception {
        Method method = ReasonTask.class.getDeclaredMethod(name, Throwable.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, error);
    }
}
