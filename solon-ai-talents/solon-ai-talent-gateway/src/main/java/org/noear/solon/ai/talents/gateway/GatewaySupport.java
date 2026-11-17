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
package org.noear.solon.ai.talents.gateway;

import io.modelcontextprotocol.spec.McpError;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.util.RetryTask;
import org.noear.solon.lang.Nullable;
import org.noear.solon.util.CallableTx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三个网关共用的小工具（包内）：视野名单与参数的宽松读取、关键词切分、重试判定、单行文本。
 *
 * @author noear
 * @since 4.2.0
 */
final class GatewaySupport {
    private static final Logger LOG = LoggerFactory.getLogger(GatewaySupport.class);

    private GatewaySupport() {
    }

    // ========== 名单与参数 ==========

    /**
     * 宽松读取视野名单（{@code _mcps_selected}，与 SkillTalent 的 {@code _skills_selected} 同口径）：值可以是 Collection、String[] 或逗号分隔的字符串。
     * 去掉空白项。强制边界（{@code _mcps_allowed}）不走这里，只接受 {@code List<String>}。
     *
     * @return 键不存在（或值为 null）时返回 null；否则返回归一化后的名单（可能为空）
     */
    @Nullable
    static List<String> namesOf(@Nullable Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }

        Object tmp = map.get(key);
        if (tmp == null) {
            return null;
        }

        List<String> result = new ArrayList<>();
        if (tmp instanceof Collection) {
            for (Object item : (Collection<?>) tmp) {
                addName(result, item);
            }
        } else if (tmp instanceof Object[]) {
            for (Object item : (Object[]) tmp) {
                addName(result, item);
            }
        } else {
            for (String item : tmp.toString().split(",")) {
                addName(result, item);
            }
        }
        return result;
    }

    private static void addName(List<String> result, @Nullable Object item) {
        if (item != null) {
            String name = item.toString().trim();
            if (name.length() > 0) {
                result.add(name);
            }
        }
    }

    /**
     * 模型给的参数对象转为参数表（总是返回副本，不改动原始参数）。
     *
     * <p>缺省或空串为空表；有的模型会把对象序列化成 JSON 字符串传来，这里解析一次；
     * 解析不出 JSON 对象（数组、数字、写坏的 JSON 等）返回 null，由调用方报参数格式错误。
     */
    @Nullable
    @SuppressWarnings("unchecked")
    static Map<String, Object> argsOf(@Nullable Object raw) {
        if (raw == null) {
            return new LinkedHashMap<>();
        }

        if (raw instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) raw);
        }

        if (raw instanceof String) {
            String json = ((String) raw).trim();
            if (json.isEmpty()) {
                return new LinkedHashMap<>();
            }

            try {
                ONode node = ONode.ofJson(json);
                if (node.isObject()) {
                    return new LinkedHashMap<>((Map<String, Object>) node.toBean(Map.class));
                }
            } catch (Throwable e) {
                LOG.debug("Gateway: arguments are not a JSON object: {}", json, e);
            }
        }

        return null;
    }

    /** 参数值转文本（null 保持 null） */
    @Nullable
    static String textOf(@Nullable Object value) {
        return value == null ? null : value.toString();
    }

    // ========== 关键词 ==========

    /** 关键词切分（空白与中英文逗号、分号都算分隔，转小写，忽略空项）；没有关键词时返回空数组 */
    static String[] keywordsOf(@Nullable String keyword) {
        if (Utils.isBlank(keyword)) {
            return new String[0];
        }

        List<String> keys = new ArrayList<>();
        for (String key : keyword.trim().toLowerCase().split("[\\s,;，；]+")) {
            if (key.isEmpty() == false) {
                keys.add(key);
            }
        }
        return keys.toArray(new String[0]);
    }

    /** 文本（会转小写）是否包含全部关键词；没有关键词时视为命中 */
    static boolean containsAll(@Nullable String text, String[] keys) {
        String content = text == null ? "" : text.toLowerCase();
        for (String key : keys) {
            if (content.contains(key) == false) {
                return false;
            }
        }
        return true;
    }

    // ========== 重试 ==========

    /**
     * 带重试执行：只在请求确定没有送达时重试（见 {@link #isNotDelivered(Throwable)}），退避从 retryDelayMs 起、最多其 4 倍。
     */
    static <T> T callWithRetry(int maxRetries, long retryDelayMs, CallableTx<T, Throwable> callable) throws Throwable {
        return new RetryTask()
                .maxRetries(maxRetries)
                .initialDelayMs(retryDelayMs)
                .maxDelayMs(retryDelayMs * 4)
                .retryIf(GatewaySupport::isNotDelivered)
                .callWithRetry(callable);
    }

    /**
     * 请求是否确定没有送达目标（连接被拒、域名解析失败、无路由、连接阶段超时），只有这类失败重试才安全。
     *
     * <p>目标已应答的错误（如 MCP 的 {@link McpError}）重试结果不变；读超时、传输中断、5xx 时请求可能已经执行，
     * 对创建、转账一类非幂等操作重发会重复执行，交给模型看到报错后自行决定。
     */
    static boolean isNotDelivered(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth < 10; t = t.getCause(), depth++) {
            if (t instanceof McpError) {
                return false;
            }

            if (t instanceof ConnectException || t instanceof UnknownHostException || t instanceof NoRouteToHostException) {
                return true;
            }

            String message = t.getMessage();
            if (t instanceof SocketTimeoutException) {
                // 只有连接阶段的超时（请求还没发出去）；读超时时请求可能已经执行
                if (message != null && message.contains("connect timed out")) {
                    return true;
                }
            } else if (t.getClass().getName().endsWith("HttpConnectTimeoutException")) {
                // java.net.http 的连接超时（按类名识别，兼容 Java 8 编译）
                return true;
            }

            if (message != null && message.contains("Connection refused")) {
                return true;
            }
        }
        return false;
    }

    /** 异常的说明文字（没有 message 时用类名） */
    static String errorOf(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    // ========== 文本 ==========

    /** 按字符数截取，不拆开代理对（emoji 等） */
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }

        int end = max;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /** 压成一行并截断（超出以 … 结尾）；null 视为空串 */
    static String oneLine(@Nullable String text, int max) {
        if (text == null) {
            return "";
        }

        String line = text.replaceAll("\\s+", " ").trim();
        return line.length() > max ? cut(line, max) + "…" : line;
    }
}
