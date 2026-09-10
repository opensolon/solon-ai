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
package org.noear.solon.ai.chat.tool;

import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.snack4.json.JsonReader;
import org.noear.solon.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用参数净化器（出站协议防线）
 *
 * <p>背景：模型输出被截断时（如 finish_reason=length、流中断），tool_call 的 arguments
 * 可能是非法 JSON（如 Unterminated string）。若原样写入会话历史并在下一轮回传，
 * OpenAI 兼容服务端会因无法将 arguments 解析为 JSON object 而返回 400；
 * 且该消息持久化后会话每次请求都会失败（会话中毒）。</p>
 *
 * <p>净化策略：仅保留能被严格解析为 JSON object 的串（重序列化输出标准 JSON，
 * 顺带规范化单引号等宽松格式与多余空白）；其余（含 null、空串、标量、数组、
 * 双重编码、截断损坏）一律替换为 "{}"，不做“自动修复”--半截参数被执行比缺参更危险，
 * 由工具执行链路报参数缺失，驱动模型在下一轮自行纠正重发。</p>
 *
 * @author noear
 * @since 4.0.6
 */
public final class ToolCallJsonSanitizer {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallJsonSanitizer.class);

    private ToolCallJsonSanitizer() {
        //工具类
    }

    /**
     * 净化单个 tool_call 的 arguments 字符串，确保可被服务端按严格 JSON 解析为 object。
     *
     * @param argStr 原始 arguments 字符串
     * @param fnName 函数名（仅用于日志定位）
     * @return 合法的 JSON object 字符串
     */
    public static String sanitizeArguments(String argStr, String fnName) {
        if (Utils.isEmpty(argStr)) {
            return "{}";
        }

        try {
            // 严格解析（不启用 AutoRepair）：词法边界已确保只有一个完整 object 根。
            JsonReader reader = new JsonReader(argStr);
            ONode parsed = reader.readLast();

            if (parsed != null && parsed.isObject()) {
                return parsed.toJson();
            }
        } catch (Throwable ignore) {
            //无法解析，走兜底替换
        }

        String raw = argStr.length() > 200 ? argStr.substring(0, 200) + "..." : argStr;
        // 注意：SLF4J 的占位符不支持 '{{}}' 形式的转义（会被当成内层 '{}' 消耗掉一个参数），
        // 故此处用字面文本描述兜底结果，避免日志把 raw 错位吞掉造成误导
        LOG.warn("Tool call arguments is not a valid JSON object (fn: '{}'), reset to empty object. raw: {}", fnName, raw);
        return "{}";
    }

    /**
     * 判断文本是否恰好包含一个完整 JSON object 根；允许 Snack4 支持的单双引号字符串。
     */
    public static boolean isSingleJsonObject(String text) {
        if (Utils.isEmpty(text)) {
            return false;
        }
        int length = text.length();
        int start = 0;
        while (start < length && Character.isWhitespace(text.charAt(start))) start++;
        if (start >= length || text.charAt(start) != '{') return false;

        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = start; i < length; i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '{' || ch == '[') {
                depth++;
            } else if (ch == '}' || ch == ']') {
                depth--;
                if (depth < 0) return false;
                if (depth == 0) {
                    for (int j = i + 1; j < length; j++) {
                        if (!Character.isWhitespace(text.charAt(j))) return false;
                    }
                    return quote == 0;
                }
            }
        }
        return false;
    }

    /**
     * 出站净化 tool_calls 原始数据（返回深拷贝，不修改入参）。
     *
     * <p>结构对齐 OpenAI 兼容协议：{@code [{id, type, function: {name, arguments}}]}。
     * 仅净化 String 型 arguments（含 null）；已是对象形态的原样保留，避免误伤特殊方言。</p>
     *
     * @param toolCallsRaw 原始 tool_calls 数据
     * @return 净化后的新列表
     */
    public static List<Map> sanitizeToolCallsRaw(List<Map> toolCallsRaw) {
        if (Utils.isEmpty(toolCallsRaw)) {
            return toolCallsRaw;
        }

        List<Map> result = new ArrayList<>(toolCallsRaw.size());
        for (Map raw : toolCallsRaw) {
            if (raw == null) {
                continue;
            }

            Map item = new LinkedHashMap(raw);

            Object fnObj = item.get("function");
            if (fnObj instanceof Map) {
                Map fn = new LinkedHashMap((Map) fnObj);
                Object argsObj = fn.get("arguments");

                if (argsObj == null || argsObj instanceof String) {
                    fn.put("arguments", sanitizeArguments((String) argsObj, (String) fn.get("name")));
                }

                item.put("function", fn);
            }

            result.add(item);
        }

        return result;
    }

    /**
     * 获取通用工具调用。类型化数据优先；仅当其为空时解析旧 {@code toolCallsRaw}。
     * <p>旧 raw 只识别 OpenAI-compatible 的 function 调用；未知类型和非法结构不会被猜测。</p>
     *
     * @since 4.1
     */
    public static List<ToolCall> resolveToolCalls(List<ToolCall> toolCalls, List<Map> toolCallsRaw) {
        if (Utils.isNotEmpty(toolCalls)) {
            return toolCalls;
        }
        return parseLegacyToolCallsRaw(toolCallsRaw);
    }

    /**
     * 将类型化工具调用构建为 OpenAI-compatible {@code tool_calls}。
     * <p>类型化数据存在时不合并旧 raw，避免陈旧 raw 覆盖已经修改过的通用语义；
     * 仅 raw 存在时保留其未知字段，并只净化 arguments。</p>
     *
     * @since 4.1
     */
    public static List<Map> buildOpenAiCompatibleToolCalls(List<ToolCall> toolCalls, List<Map> toolCallsRaw) {
        if (Utils.isEmpty(toolCalls)) {
            return sanitizeToolCallsRaw(toolCallsRaw);
        }

        List<Map> result = new ArrayList<>(toolCalls.size());
        for (ToolCall call : toolCalls) {
            if (call == null) {
                continue;
            }

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", call.getName());
            function.put("arguments", sanitizeArguments(call));

            Map<String, Object> item = new LinkedHashMap<>();
            if (Utils.isNotEmpty(call.getId())) {
                item.put("id", call.getId());
            }
            item.put("type", "function");
            item.put("function", function);
            result.add(item);
        }
        return result;
    }

    /**
     * 获取类型化工具调用的安全 JSON object 参数。
     * <p>{@code argumentsStr} 非空时为权威来源；非法或截断字符串直接降级为空对象，
     * 不回退到可能由宽松解析得到的半截 Map。字符串为空时才使用结构化 arguments。</p>
     *
     * @since 4.1
     */
    public static String sanitizeArguments(ToolCall call) {
        if (call == null) {
            return "{}";
        }
        if (Utils.isNotEmpty(call.getArgumentsStr())) {
            return sanitizeArguments(call.getArgumentsStr(), call.getName());
        }
        if (call.getArguments() == null) {
            return "{}";
        }

        ONode argsNode = ONode.ofBean(call.getArguments());
        return argsNode != null && argsNode.isObject() ? argsNode.toJson() : "{}";
    }

    /**
     * 将旧 OpenAI-compatible {@code toolCallsRaw} 投影为通用工具调用。
     * <p>该方法用于跨协议兼容重建，不保留供应商未知字段；原协议精确回放仍应使用 raw fallback。</p>
     *
     * @since 4.1
     */
    public static List<ToolCall> parseLegacyToolCallsRaw(List<Map> toolCallsRaw) {
        if (Utils.isEmpty(toolCallsRaw)) {
            return Collections.emptyList();
        }

        List<ToolCall> result = new ArrayList<>(toolCallsRaw.size());
        for (Map raw : toolCallsRaw) {
            if (raw == null) {
                continue;
            }

            Object type = raw.get("type");
            if (type != null && !"function".equals(String.valueOf(type))) {
                continue;
            }

            Object functionObj = raw.get("function");
            if (!(functionObj instanceof Map)) {
                continue;
            }
            Map function = (Map) functionObj;
            Object nameObj = function.get("name");
            String name = nameObj == null ? null : String.valueOf(nameObj);
            if (Utils.isEmpty(name)) {
                continue;
            }

            String argumentsStr;
            Map<String, Object> arguments;
            Object argumentsObj = function.get("arguments");
            if (argumentsObj instanceof Map) {
                arguments = new LinkedHashMap<>((Map<String, Object>) argumentsObj);
                argumentsStr = ONode.ofBean(arguments).toJson();
            } else {
                argumentsStr = sanitizeArguments(argumentsObj instanceof String ? (String) argumentsObj : null, name);
                arguments = ONode.ofJson(argumentsStr).toBean(Map.class);
            }

            Object indexObj = raw.get("index");
            Object idObj = raw.get("id");
            result.add(new ToolCall(indexObj == null ? null : String.valueOf(indexObj),
                    idObj == null ? null : String.valueOf(idObj), name, argumentsStr, arguments));
        }
        return result;
    }
}
