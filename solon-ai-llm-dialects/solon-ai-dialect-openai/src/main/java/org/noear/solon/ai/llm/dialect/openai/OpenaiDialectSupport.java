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
package org.noear.solon.ai.llm.dialect.openai;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;

/**
 * OpenAI 兼容协议的公共解析工具
 *
 * @since 4.1
 */
class OpenaiDialectSupport {
    private static final java.util.regex.Pattern VERSION_PATTERN = java.util.regex.Pattern.compile("/v\\d+/?$");

    /**
     * 规范化接口地址：去掉 '#' 后缀说明、查询串与结尾斜杠，便于统一做 endsWith 判断。
     *
     * @since 4.1
     */
    static String normalizeApiUrl(String apiUrl) {
        if (apiUrl == null) {
            return "";
        }
        String url = apiUrl;
        int hashIndex = url.indexOf('#');
        if (hashIndex > 0) {
            url = url.substring(0, hashIndex);
        }
        int queryIndex = url.indexOf('?');
        if (queryIndex > 0) {
            url = url.substring(0, queryIndex);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * 共享的接口地址自动补全：去掉 '#' 后缀；已带端点路径（如 /chat/completions、/responses）原样返回；
     * 已带版本号（/v1、/v4 等）则补端点；否则补 /v1 + 端点。
     * <p>对齐 OpenAI 官方路径：{@code /v1/chat/completions}、{@code /v1/responses}。</p>
     *
     * @param apiUrl        配置的原始地址
     * @param endpointPath 端点路径（不带前导斜杠，如 "chat/completions"、"responses"）
     * @since 4.1
     */
    static String buildApiUrl(String apiUrl, String endpointPath) {
        // 处理后缀 #
        int hashIndex = apiUrl == null ? -1 : apiUrl.indexOf('#');
        if (hashIndex > 0) {
            apiUrl = apiUrl.substring(0, hashIndex);
        }
        if (apiUrl == null || apiUrl.isEmpty()) {
            return apiUrl;
        }

        // 先规范化（去 '#' 后缀/查询串/结尾斜杠）再做端点判断，
        // 避免 ".../responses?x=1" 这类带查询串的地址误走补全分支拼成 "/responses/responses"
        String baseUrl = normalizeApiUrl(apiUrl);

        // 已带端点
        if (baseUrl.endsWith("/" + endpointPath)) {
            return baseUrl;
        }

        if (VERSION_PATTERN.matcher(baseUrl).find()) { // 匹配 /v1,/v4/ 等，已带版本
            return baseUrl + "/" + endpointPath;
        } else {
            return baseUrl + "/v1/" + endpointPath;
        }
    }

    /**
     * 从 OpenAI 标准 error 节点（{message,type,code}）提取可读错误消息。
     * <p>避免把整个对象序列化为 JSON 串作为异常消息；非对象形态（字符串等）原样返回。</p>
     */
    static String extractErrorMessage(ONode errorNode) {
        if (errorNode == null || errorNode.isNull()) {
            return "Unknown error";
        }

        String message = null;
        String type = null;

        if (errorNode.isObject()) {
            message = errorNode.get("message").getString();
            type = errorNode.get("type").getString();
            if (Utils.isEmpty(type)) {
                type = errorNode.get("code").getString();
            }
        }

        if (Utils.isEmpty(message)) {
            message = errorNode.getString();
        }
        if (Utils.isEmpty(message)) {
            message = "Unknown error";
        }

        if (Utils.isNotEmpty(type)) {
            return String.format("[%s] %s", type, message);
        }
        return message;
    }
}
