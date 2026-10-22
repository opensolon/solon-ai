/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package features.ai.react.task;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.LlmErrorClassifier;
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
 * 模型服务基础设施错误分类测试。
 */
public class ReasonTaskErrorClassificationTest {
    @Test
    public void classifiesInfrastructureErrors() {
        assertEquals(LlmErrorEvent.Type.SSL,
                LlmErrorClassifier.classify(new SSLHandshakeException("PKIX path building failed")));
        assertEquals(LlmErrorEvent.Type.DNS,
                LlmErrorClassifier.classify(new UnknownHostException("llm.example.com")));
        assertEquals(LlmErrorEvent.Type.CONNECTION_REFUSED,
                LlmErrorClassifier.classify(new ConnectException("Connection refused")));
        assertEquals(LlmErrorEvent.Type.NETWORK,
                LlmErrorClassifier.classify(new NoRouteToHostException("No route to host")));
        assertEquals(LlmErrorEvent.Type.TIMEOUT,
                LlmErrorClassifier.classify(new SocketTimeoutException("Read timed out")));
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
    public void classifiesGatewayHttpStatuses() {
        assertEquals(LlmErrorEvent.Type.RATE_LIMIT, LlmErrorClassifier.classifyHttpStatus(429));
        assertEquals(LlmErrorEvent.Type.UPSTREAM_UNAVAILABLE, LlmErrorClassifier.classifyHttpStatus(502));
        assertEquals(LlmErrorEvent.Type.UPSTREAM_UNAVAILABLE, LlmErrorClassifier.classifyHttpStatus(503));
        assertEquals(LlmErrorEvent.Type.UPSTREAM_UNAVAILABLE, LlmErrorClassifier.classifyHttpStatus(504));
        assertEquals(LlmErrorEvent.Type.SERVER_ERROR, LlmErrorClassifier.classifyHttpStatus(500));
        assertEquals(LlmErrorEvent.Type.TIMEOUT, LlmErrorClassifier.classifyHttpStatus(408));
        assertEquals(LlmErrorEvent.Type.HTTP, LlmErrorClassifier.classifyHttpStatus(401));
    }

    @Test
    public void errorTypeKeepsStableProtocolValues() {
        assertEquals("rate_limit", LlmErrorEvent.Type.RATE_LIMIT.value());
        assertEquals("upstream_unavailable", LlmErrorEvent.Type.UPSTREAM_UNAVAILABLE.value());
        assertEquals("server_error", LlmErrorEvent.Type.SERVER_ERROR.value());
        assertEquals("connection_refused", LlmErrorEvent.Type.CONNECTION_REFUSED.value());
        assertEquals("ssl", LlmErrorEvent.Type.SSL.value());
    }

    private boolean invokeRetryableHttpStatus(int code) throws Exception {
        Method method = ReasonTask.class.getDeclaredMethod("isRetryableHttpStatus", int.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, code);
    }
}
