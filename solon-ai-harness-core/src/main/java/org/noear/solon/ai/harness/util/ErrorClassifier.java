/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.util;

/**
 * 异常分类器 —— loop 与 goal 两引擎共用的错误分类工具。
 *
 * <p>按异常消息的特征串归类为 SSL / NETWORK / HTTP_4XX / HTTP_5XX / TOOL_EXECUTION / OTHER，
 * 供日志与重试/熔断策略使用。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public final class ErrorClassifier {
    private ErrorClassifier() {
    }

    /**
     * 对异常进行分类，用于日志与重试策略。
     */
    public static String classifyError(Exception e) {
        String msg = getFullMessage(e);
        if (msg.contains("SSLHandshakeException") || msg.contains("PKIX")
                || msg.contains("certificate") || msg.contains("trust")
                || msg.contains("unable to find valid certification")) {
            return "SSL";
        }
        if (msg.contains("ConnectException") || msg.contains("Connection refused")
                || msg.contains("Connection reset") || msg.contains("SocketTimeoutException")
                || msg.contains("connect timed out") || msg.contains("Read timed out")) {
            return "NETWORK";
        }
        if (msg.contains("HTTP 4") || msg.contains("status code: 4")
                || msg.contains("400") || msg.contains("401") || msg.contains("403")
                || msg.contains("404") || msg.contains("405") || msg.contains("429")) {
            return "HTTP_4XX";
        }
        if (msg.contains("HTTP 5") || msg.contains("status code: 5")
                || msg.contains("500") || msg.contains("502") || msg.contains("503")
                || msg.contains("504")) {
            return "HTTP_5XX";
        }
        if (msg.contains("execution failed") || msg.contains("MethodFunctionTool")
                || msg.contains("ActionTask")) {
            return "TOOL_EXECUTION";
        }
        return "OTHER";
    }

    /** 拼接异常链（最多 5 层）的完整消息，避免特征串藏在 cause 里被漏判。 */
    public static String getFullMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 5) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(" ");
            }
            sb.append(current.getClass().getSimpleName()).append(" ");
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }
}
