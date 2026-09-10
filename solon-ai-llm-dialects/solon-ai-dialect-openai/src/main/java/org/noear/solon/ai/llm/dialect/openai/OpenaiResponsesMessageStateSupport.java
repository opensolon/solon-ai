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

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallJsonSanitizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OpenAI Responses assistant 消息协议状态的集中兼容入口。
 *
 * <p>新消息使用 protocolStates；旧消息仍可能把同样的数据放在 metadata 中。
 * 这里统一处理新旧读取优先级，避免请求构建器直接依赖无命名空间的 metadata。</p>
 *
 * @since 4.1
 */
final class OpenaiResponsesMessageStateSupport {
    static final String PROTOCOL_ID = "openai.responses";
    static final int VERSION = 1;

    static final String OUTPUT_ITEMS = "responses_output_items";
    static final String MESSAGE_ITEMS = "response_message_items";
    static final String REASONING_ITEMS = "reasoning_items";
    static final String REASONING_ITEM_ID = "reasoning_item_id";
    static final String REASONING_ENCRYPTED_CONTENT = "reasoning_encrypted_content";
    static final String PHASE = "phase";

    // 解析期工作区键必须与应用 metadata 隔离；提升到 state.data 时再映射成上面的稳定协议键。
    static final String AGGREGATION_OUTPUT_ITEMS = "__openai_responses.output_items";
    static final String AGGREGATION_MESSAGE_ITEMS = "__openai_responses.message_items";
    static final String AGGREGATION_REASONING_ITEMS = "__openai_responses.reasoning_items";
    static final String AGGREGATION_REASONING_ITEM_ID = "__openai_responses.reasoning_item_id";
    static final String AGGREGATION_REASONING_ENCRYPTED_CONTENT = "__openai_responses.reasoning_encrypted_content";
    static final String AGGREGATION_PHASE = "__openai_responses.phase";

    private OpenaiResponsesMessageStateSupport() {
    }

    /**
     * 从流式聚合的旧暂存 metadata 创建新的协议状态。
     * <p>只复制 Responses 自己的键，不能把应用 metadata 误纳入协议状态。</p>
     */
    static MessageProtocolState fromAggregation(Map<String, Object> aggregationMetadata) {
        if (aggregationMetadata == null || aggregationMetadata.isEmpty()) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        copyIfPresent(aggregationMetadata, data, AGGREGATION_OUTPUT_ITEMS, OUTPUT_ITEMS);
        copyIfPresent(aggregationMetadata, data, AGGREGATION_MESSAGE_ITEMS, MESSAGE_ITEMS);
        copyIfPresent(aggregationMetadata, data, AGGREGATION_REASONING_ITEMS, REASONING_ITEMS);
        copyIfPresent(aggregationMetadata, data, AGGREGATION_REASONING_ITEM_ID, REASONING_ITEM_ID);
        copyIfPresent(aggregationMetadata, data, AGGREGATION_REASONING_ENCRYPTED_CONTENT, REASONING_ENCRYPTED_CONTENT);
        copyIfPresent(aggregationMetadata, data, AGGREGATION_PHASE, PHASE);
        return data.isEmpty() ? null : new MessageProtocolState(VERSION, data);
    }

    /**
     * 读取可用于当前 Responses 请求的协议数据。
     * <p>如果新状态存在但版本或语义摘要不合法，则 fail-closed，不再回退到可能陈旧的 legacy metadata。</p>
     */
    static Map<String, Object> resolveData(AssistantMessage message) {
        if (message == null) {
            return null;
        }
        MessageProtocolState state = message.getProtocolState(PROTOCOL_ID);
        if (state != null) {
            if (state.getVersion() != VERSION
                    || Utils.isEmpty(state.getSemanticHash())
                    || !org.noear.solon.ai.chat.message.MessageSemanticHasher.matches(message, state)) {
                return Collections.emptyMap();
            }
            return state.getData() == null ? Collections.<String, Object>emptyMap() : state.getData();
        }

        // 兼容已有持久化消息和旧的公开 metadata 用法；只在不存在新状态时使用。
        if (!message.hasMetadata()) {
            return null;
        }
        Map<String, Object> metadata = message.getMetadata();
        if (!containsProtocolKey(metadata)) {
            return null;
        }
        if (hasLegacyOutputConflict(message, metadata.get(OUTPUT_ITEMS))) {
            // output_items 是旧格式的精确快照；一旦能确认其正文或函数调用已与当前消息分叉，
            // 就只禁用依赖该快照的 message 回放。独立的 reasoning id/encrypted_content
            // 仍可安全用于无状态续接，保持已有持久化数据兼容。
            Map<String, Object> compatible = new LinkedHashMap<>(metadata);
            compatible.remove(OUTPUT_ITEMS);
            compatible.remove(MESSAGE_ITEMS);
            return compatible;
        }
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private static boolean hasLegacyOutputConflict(AssistantMessage message, Object value) {
        if (!(value instanceof Collection)) {
            return false;
        }

        StringBuilder replayText = new StringBuilder();
        boolean textComparable = false;
        boolean toolCallsComparable = true;
        List<ReplayToolCall> replayToolCalls = new ArrayList<>();
        for (Object wrapperObj : (Collection<?>) value) {
            if (!(wrapperObj instanceof Map)) {
                continue;
            }
            Object itemObj = ((Map<?, ?>) wrapperObj).get("item");
            if (!(itemObj instanceof Map)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) itemObj;
            String type = stringValue(item.get("type"));
            if ("message".equals(type)) {
                String itemText = comparableMessageText(item.get("content"));
                if (itemText != null) {
                    replayText.append(itemText);
                    textComparable = true;
                }
            } else if ("function_call".equals(type)) {
                String callId = stringValue(item.get("call_id"));
                String name = stringValue(item.get("name"));
                if (Utils.isEmpty(callId) || Utils.isEmpty(name)) {
                    toolCallsComparable = false;
                    continue;
                }
                replayToolCalls.add(new ReplayToolCall(callId, name,
                        ToolCallJsonSanitizer.sanitizeArguments(stringValue(item.get("arguments")), name)));
            }
        }

        String currentText = message.getText() == null ? "" : message.getText();
        if (textComparable && !Objects.equals(replayText.toString(), currentText)) {
            return true;
        }

        if (toolCallsComparable && !replayToolCalls.isEmpty()) {
            List<ToolCall> currentToolCalls = ToolCallJsonSanitizer.resolveToolCalls(
                    message.getToolCalls(), message.getToolCallsRaw());
            // 旧持久化数据可能只保存 output_items 而没有投影 toolCalls；两边都有调用时才可明确比较。
            if (currentToolCalls == null || currentToolCalls.isEmpty()) {
                return false;
            }
            if (currentToolCalls.size() != replayToolCalls.size()) {
                return true;
            }
            for (int i = 0; i < replayToolCalls.size(); i++) {
                ReplayToolCall replay = replayToolCalls.get(i);
                ToolCall current = currentToolCalls.get(i);
                if (current == null
                        || !Objects.equals(replay.callId, current.getId())
                        || !Objects.equals(replay.name, current.getName())
                        || !Objects.equals(replay.arguments,
                        ToolCallJsonSanitizer.sanitizeArguments(current))) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static String comparableMessageText(Object contentObj) {
        if (!(contentObj instanceof Collection)) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (Object partObj : (Collection<?>) contentObj) {
            if (!(partObj instanceof Map)) {
                return null;
            }
            Map<String, Object> part = (Map<String, Object>) partObj;
            String type = stringValue(part.get("type"));
            if ("output_text".equals(type) || "text".equals(type)) {
                appendIfPresent(text, part.get("text"));
            } else if ("refusal".equals(type)) {
                Object value = part.get("refusal");
                appendIfPresent(text, value == null ? part.get("text") : value);
            } else if ("output_audio".equals(type)) {
                appendIfPresent(text, part.get("transcript"));
            } else if (!("output_image".equals(type) || "image".equals(type)
                    || part.containsKey("image_url"))) {
                // 未知内容项未来可能有文本投影；不能据此判定旧快照已冲突。
                return null;
            }
        }
        return text.toString();
    }

    private static void appendIfPresent(StringBuilder target, Object value) {
        if (value != null) {
            target.append(String.valueOf(value));
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static final class ReplayToolCall {
        final String callId;
        final String name;
        final String arguments;

        ReplayToolCall(String callId, String name, String arguments) {
            this.callId = callId;
            this.name = name;
            this.arguments = arguments;
        }
    }

    static Object get(AssistantMessage message, String key) {
        Map<String, Object> data = resolveData(message);
        return data == null ? null : data.get(key);
    }

    static boolean hasReplayData(AssistantMessage message) {
        Map<String, Object> data = resolveData(message);
        return data != null && !data.isEmpty();
    }

    /** 删除已提升到协议状态的内部键，保留应用自定义 metadata。 */
    static void removeProtocolKeys(Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        metadata.remove(AGGREGATION_OUTPUT_ITEMS);
        metadata.remove(AGGREGATION_MESSAGE_ITEMS);
        metadata.remove(AGGREGATION_REASONING_ITEMS);
        metadata.remove(AGGREGATION_REASONING_ITEM_ID);
        metadata.remove(AGGREGATION_REASONING_ENCRYPTED_CONTENT);
        metadata.remove(AGGREGATION_PHASE);
    }

    private static boolean containsProtocolKey(Map<String, Object> data) {
        return data != null && (data.containsKey(OUTPUT_ITEMS)
                || data.containsKey(MESSAGE_ITEMS)
                || data.containsKey(REASONING_ITEMS)
                || data.containsKey(REASONING_ITEM_ID)
                || data.containsKey(REASONING_ENCRYPTED_CONTENT));
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target,
                                      String sourceKey, String targetKey) {
        if (source.containsKey(sourceKey) && source.get(sourceKey) != null) {
            target.put(targetKey, source.get(sourceKey));
        }
    }
}
