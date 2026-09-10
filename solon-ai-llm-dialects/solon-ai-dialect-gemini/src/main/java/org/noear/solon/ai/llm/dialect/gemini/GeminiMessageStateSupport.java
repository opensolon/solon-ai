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
package org.noear.solon.ai.llm.dialect.gemini;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.message.MessageSemanticHasher;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.LinkedHashMap;
import java.util.Map;

/** Gemini 工具调用思考签名的协议状态兼容支持。 */
public final class GeminiMessageStateSupport {
    public static final String GENERATE_CONTENT_PROTOCOL_ID = "google.generate-content";
    public static final String INTERACTIONS_PROTOCOL_ID = "google.interactions";
    public static final int VERSION = 1;
    private static final String THOUGHT_SIGNATURES = "thoughtSignatures";

    private GeminiMessageStateSupport() {
    }

    /** 为终态聚合创建尚未绑定语义摘要的签名状态。 */
    public static MessageProtocolState createSignatureState(ToolCall call, int index, String signature) {
        if (call == null || Utils.isEmpty(signature)) {
            return null;
        }
        Map<String, Object> signatures = new LinkedHashMap<>();
        signatures.put(callKey(call, index), signature);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(THOUGHT_SIGNATURES, signatures);
        return new MessageProtocolState(VERSION, data);
    }

    /** 新状态优先；完全不存在 Gemini 新状态时才兼容旧 ToolCall.thoughtSignature。 */
    public static String resolveSignature(AssistantMessage message, String protocol, ToolCall call, int index) {
        if (message == null || call == null) {
            return null;
        }
        MessageProtocolState state = message.getProtocolState(protocol);
        if (state != null) {
            if (state.getVersion() != VERSION || Utils.isEmpty(state.getSemanticHash())
                    || !MessageSemanticHasher.matches(message, state)) {
                return null;
            }
            Object value = state.getData() == null ? null : state.getData().get(THOUGHT_SIGNATURES);
            if (!(value instanceof Map)) {
                return null;
            }
            Object signature = ((Map<?, ?>) value).get(callKey(call, index));
            return signature instanceof String && Utils.isNotEmpty((String) signature)
                    ? (String) signature : null;
        }

        // 两个 Gemini 新协议状态属于独立命名空间；另一协议已有状态时不得跨协议读取 legacy 字段。
        if (hasGeminiProtocolState(message)) {
            return null;
        }
        return call.getThoughtSignature();
    }

    private static boolean hasGeminiProtocolState(AssistantMessage message) {
        return message.hasProtocolState(GENERATE_CONTENT_PROTOCOL_ID)
                || message.hasProtocolState(INTERACTIONS_PROTOCOL_ID);
    }

    private static String callKey(ToolCall call, int index) {
        if (Utils.isNotEmpty(call.getId())) return "id:" + call.getId();
        if (Utils.isNotEmpty(call.getIndex())) return "index:" + call.getIndex();
        return "position:" + index;
    }
}
