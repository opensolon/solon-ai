/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.exception.LlmNoReturnException;
import org.noear.solon.net.http.HttpResponseException;

import javax.net.ssl.SSLException;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * 模型服务异常分类器。
 *
 * <p>这里只负责将异常或 HTTP 状态码转换为稳定的错误类型，不负责重试、日志或用户提示。</p>
 *
 * @author noear
 * @since 4.2
 */
public final class LlmErrorClassifier {
    private LlmErrorClassifier() {
    }

    /**
     * 按异常链分类。已取得 HTTP 响应时，优先使用响应状态码；否则再判断传输层异常。
     */
    public static LlmErrorEvent.Type classify(Throwable e) {
        HttpResponseException httpRespEx = findCause(e, HttpResponseException.class);
        if (httpRespEx != null) {
            return classifyHttpStatus(httpRespEx.code());
        }
        if (isSslException(e)) {
            return LlmErrorEvent.Type.SSL;
        }
        if (isDnsException(e)) {
            return LlmErrorEvent.Type.DNS;
        }
        if (findCause(e, LlmNoReturnException.class) != null) {
            return LlmErrorEvent.Type.NO_RETURN;
        }
        if (isTimeoutException(e)) {
            return LlmErrorEvent.Type.TIMEOUT;
        }
        if (isConnectionRefused(e)) {
            return LlmErrorEvent.Type.CONNECTION_REFUSED;
        }
        if (isConnectException(e)) {
            return LlmErrorEvent.Type.CONNECTION;
        }
        if (isNetworkException(e)) {
            return LlmErrorEvent.Type.NETWORK;
        }
        return LlmErrorEvent.Type.UNKNOWN;
    }

    /**
     * 按 HTTP 状态码做保守分类：502/503/504 只能说明网关或上游暂时不可用，
     * 不能仅凭状态码断言一定是上游限流。
     */
    public static LlmErrorEvent.Type classifyHttpStatus(int code) {
        if (code == 429) {
            return LlmErrorEvent.Type.RATE_LIMIT;
        }
        if (code == 408) {
            return LlmErrorEvent.Type.TIMEOUT;
        }
        if (code == 502 || code == 503 || code == 504
                || code == 520 || code == 521 || code == 522
                || code == 523 || code == 524 || code == 525
                || code == 526 || code == 529) {
            return LlmErrorEvent.Type.UPSTREAM_UNAVAILABLE;
        }
        if (code >= 500 && code <= 599) {
            return LlmErrorEvent.Type.SERVER_ERROR;
        }
        return LlmErrorEvent.Type.HTTP;
    }

    private static boolean isTimeoutException(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("timeout")
                        || lower.contains("timed out")
                        || lower.contains("deadline exceeded")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static boolean isConnectException(Throwable e) {
        return findCause(e, ConnectException.class) != null;
    }

    private static boolean isConnectionRefused(Throwable e) {
        ConnectException connectException = findCause(e, ConnectException.class);
        if (connectException == null || connectException.getMessage() == null) {
            return false;
        }
        return connectException.getMessage().toLowerCase(Locale.ROOT).contains("refused");
    }

    private static boolean isDnsException(Throwable e) {
        return findCause(e, UnknownHostException.class) != null;
    }

    private static boolean isSslException(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SSLException
                    || cause instanceof CertificateException
                    || cause instanceof CertPathValidatorException) {
                return true;
            }

            String message = cause.getMessage();
            if (message != null) {
                String lowerMessage = message.toLowerCase(Locale.ROOT);
                if (lowerMessage.contains("ssl")
                        || lowerMessage.contains("certificate")
                        || lowerMessage.contains("handshake")
                        || lowerMessage.contains("pkix")
                        || lowerMessage.contains("trust anchor")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static boolean isNetworkException(Throwable e) {
        return findCause(e, NoRouteToHostException.class) != null
                || findCause(e, SocketException.class) != null
                || findCause(e, EOFException.class) != null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T findCause(Throwable e, Class<T> clazz) {
        Throwable cause = e;
        while (cause != null) {
            if (clazz.isInstance(cause)) {
                return (T) cause;
            }
            cause = cause.getCause();
        }
        return null;
    }
}
