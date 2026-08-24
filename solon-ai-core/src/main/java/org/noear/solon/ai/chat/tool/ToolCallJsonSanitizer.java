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
            // 严格解析（不启用 AutoRepair）：截断串返回 null；单引号等宽松格式可解析并重序列化规范化
            JsonReader reader = new JsonReader(argStr, Options.of());
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
}
