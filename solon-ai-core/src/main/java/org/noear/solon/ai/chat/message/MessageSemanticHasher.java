/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.chat.message;

import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.snack4.json.JsonReader;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallJsonSanitizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AssistantMessage 通用语义摘要工具。
 * <p>摘要不包含 metadata、协议状态与创建时间，用于阻止消息语义被修改后继续精确回放陈旧协议快照。</p>
 *
 * @author noear
 * @since 4.1
 */
public final class MessageSemanticHasher {
    private MessageSemanticHasher() {
    }

    public static String hash(AssistantMessage message) {
        if (message == null) {
            return null;
        }

        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("text", message.getText());
        semantic.put("thinking", message.getThinking());
        semantic.put("blocks", message.getBlocks());
        java.util.List<org.noear.solon.ai.chat.source.SearchResult> searchResults = message.resolveSearchResults();
        if (Utils.isNotEmpty(searchResults)) {
            semantic.put("searchResults", searchResults);
        }
        if (Utils.isNotEmpty(message.getCitations())) {
            semantic.put("citations", message.getCitations());
        }

        List<ToolCall> toolCalls = ToolCallJsonSanitizer.resolveToolCalls(
                message.getToolCalls(), message.getToolCallsRaw());
        if (Utils.isNotEmpty(toolCalls)) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCall call : toolCalls) {
                if (call == null) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", call.getIndex());
                item.put("id", call.getId());
                item.put("name", call.getName());
                if (Utils.isNotEmpty(call.getArgumentsStr())) {
                    // argumentsStr 是线协议权威语义；严格合法的 object 规范化后参与摘要，
                    // 截断/非法字符串按原文参与，避免不同坏串都退化为空 Map 后产生相同摘要。
                    try {
                        ONode parsed = new JsonReader(call.getArgumentsStr(), Options.of()).readLast();
                        if (parsed != null && parsed.isObject()) {
                            item.put("arguments", parsed.toBean());
                        } else {
                            item.put("argumentsRaw", call.getArgumentsStr());
                        }
                    } catch (Throwable e) {
                        item.put("argumentsRaw", call.getArgumentsStr());
                    }
                } else if (call.getArguments() != null) {
                    item.put("arguments", call.getArguments());
                } else {
                    item.put("arguments", Collections.emptyMap());
                }
                calls.add(item);
            }
            semantic.put("toolCalls", calls);
        }

        String canonical = canonicalJson(ONode.ofBean(semantic));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder buf = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                buf.append(Character.forDigit((b >>> 4) & 0x0f, 16));
                buf.append(Character.forDigit(b & 0x0f, 16));
            }
            return buf.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate message semantic hash", e);
        }
    }

    public static boolean matches(AssistantMessage message, MessageProtocolState state) {
        return message != null && state != null
                && Utils.isNotEmpty(state.getSemanticHash())
                && state.getSemanticHash().equals(hash(message));
    }

    private static String canonicalJson(ONode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isArray()) {
            StringBuilder buf = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) buf.append(',');
                buf.append(canonicalJson(node.get(i)));
            }
            return buf.append(']').toString();
        }
        if (node.isObject()) {
            List<String> keys = new ArrayList<>(node.getObject().keySet());
            Collections.sort(keys);
            StringBuilder buf = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) buf.append(',');
                String key = keys.get(i);
                buf.append(ONode.ofBean(key).toJson()).append(':').append(canonicalJson(node.get(key)));
            }
            return buf.append('}').toString();
        }
        return node.toJson();
    }
}
