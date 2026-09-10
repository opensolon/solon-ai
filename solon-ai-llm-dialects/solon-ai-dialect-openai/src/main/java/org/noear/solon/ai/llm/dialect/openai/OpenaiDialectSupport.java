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
     * 规范化接口地址：去掉 fragment、查询串与结尾斜杠，仅用于端点路径比较。
     * <p>真实请求地址由 {@link #buildApiUrl(String, String)} 构建并保留查询参数。</p>
     *
     * @since 4.1
     */
    static String normalizeApiUrl(String apiUrl) {
        if (apiUrl == null) {
            return "";
        }
        String url = apiUrl;
        int hashIndex = url.indexOf('#');
        if (hashIndex >= 0) {
            url = url.substring(0, hashIndex);
        }
        int queryIndex = url.indexOf('?');
        if (queryIndex >= 0) {
            url = url.substring(0, queryIndex);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * 共享的接口地址自动补全：去掉 fragment；已带端点路径（如 /chat/completions、/responses）原样返回；
     * 已带版本号（/v1、/v4 等）则补端点；否则补 /v1 + 端点。请求查询参数会原样保留。
     * <p>对齐 OpenAI 官方路径：{@code /v1/chat/completions}、{@code /v1/responses}。</p>
     *
     * @param apiUrl        配置的原始地址
     * @param endpointPath 端点路径（不带前导斜杠，如 "chat/completions"、"responses"）
     * @since 4.1
     */
    static String buildApiUrl(String apiUrl, String endpointPath) {
        if (apiUrl == null || apiUrl.isEmpty()) {
            return apiUrl;
        }

        // fragment 不会发送给服务端；query 则可能承载 api-version、签名或租户信息，必须保留。
        int hashIndex = apiUrl.indexOf('#');
        if (hashIndex >= 0) {
            apiUrl = apiUrl.substring(0, hashIndex);
        }

        String query = "";
        int queryIndex = apiUrl.indexOf('?');
        if (queryIndex >= 0) {
            query = apiUrl.substring(queryIndex);
            apiUrl = apiUrl.substring(0, queryIndex);
        }

        String baseUrl = normalizeApiUrl(apiUrl);
        String result;
        if (baseUrl.endsWith("/" + endpointPath)) {
            result = baseUrl;
        } else if (VERSION_PATTERN.matcher(baseUrl).find()) { // 匹配 /v1,/v4/ 等，已带版本
            result = baseUrl + "/" + endpointPath;
        } else {
            result = baseUrl + "/v1/" + endpointPath;
        }

        return result + query;
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

    /**
     * 判断模型名是否属于指定模型族（如 "gpt-5"、"o3"）。
     *
     * <p>匹配要求模型族片段出现在 token 边界处：片段位于开头，或前一个字符是供应商分隔符
     * （{@code / : . _ -}，兼容 {@code azure/o4-mini}、{@code openai:gpt-5}、{@code vendor_o3}
     * 等厂商前缀拼接形态）；片段结尾处须是结尾或 {@code -}/{@code .} 后缀。统一了 Chat Completions
     * 与 Responses 两处的能力判定，避免同一模型名在两个方言下得到不同结论。</p>
     *
     * @param model  小写化的模型名
     * @param family 小写化的模型族（不含通配）
     * @since 4.1
     */
    static boolean matchesModelFamily(String model, String family) {
        int fromIndex = 0;
        while (fromIndex < model.length()) {
            int start = model.indexOf(family, fromIndex);
            if (start < 0) {
                return false;
            }

            int end = start + family.length();
            boolean validPrefix = start == 0 || isModelTokenBoundary(model.charAt(start - 1));
            boolean validSuffix = end == model.length()
                    || model.charAt(end) == '-'
                    || model.charAt(end) == '.';
            if (validPrefix && validSuffix) {
                return true;
            }
            fromIndex = start + 1;
        }
        return false;
    }

    /**
     * matchesModelFamily 的兼容扩展：部分兼容网关会省略 GPT 主系列名称中的连字符（如 gpt5）。
     * 只放宽能力判断，不改写出站 model。
     *
     * @since 4.1
     */
    static boolean isModelFamily(String model, String family) {
        if (matchesModelFamily(model, family)) {
            return true;
        }

        if (family.startsWith("gpt-") && family.length() > 4) {
            return matchesModelFamily(model, "gpt" + family.substring(4));
        }
        return false;
    }

    private static boolean isModelTokenBoundary(char ch) {
        return ch == '/' || ch == ':' || ch == '.' || ch == '_' || ch == '-';
    }
}
