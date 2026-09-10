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
package org.noear.solon.ai.llm.dialect.gemini.models;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.llm.dialect.gemini.GeminiChatDialect;
import org.noear.solon.ai.llm.dialect.gemini.GeminiMessageStateSupport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * thoughtSignature 跨轮回传链路的离线回归（Gemini Generate Content API）
 *
 * <p>Gemini 3 的 thoughtSignature 位于 part 级别（functionCall 同级），并行调用时仅第一个 part 携带。
 * 新响应把签名写入协议状态与 acc.thinkingSignature，不再写 deprecated ToolCall 字段；
 * 出站优先读取有效协议状态，仅在没有对应状态时兼容旧 ToolCall JSON。</p>
 *
 * @author noear
 */
public class GeminiThoughtSignatureTest {
    private final GeminiThoughtProcessor processor = new GeminiThoughtProcessor();
    private final GeminiRequestBuilder builder = new GeminiRequestBuilder();

    private ChatAccumulator newAccumulator(boolean stream) {
        ChatConfig config = new ChatConfig();
        config.setModel("gemini-3-pro");
        ChatRequest req = new ChatRequest(config, GeminiChatDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);
        return new ChatAccumulator(req, stream);
    }

    /**
     * 入站 → 出站闭环：新响应只通过协议状态保存签名，并在下一轮 parts 上原样回传。
     */
    @Test
    public void parsedThoughtSignature_replayedOnFunctionCallPart() {
        ChatAccumulator acc = newAccumulator(true);
        ONode oContent = ONode.ofJson("{\"parts\":[{\"thoughtSignature\":\"sig_gem\","
                + "\"functionCall\":{\"name\":\"getWeather\",\"args\":{\"city\":\"hz\"},\"id\":\"call-1\"}}]}");

        List<AssistantMessage> messages = processor.parse(acc, oContent);

        AssistantMessage assistantMessage = messages.get(messages.size() - 1);
        ToolCall call = assistantMessage.getToolCalls().get(0);
        assertNull(call.getThoughtSignature(), "新解析消息不得双写 deprecated ToolCall 字段");
        assertNull(assistantMessage.getContentRaw(), "Generate Content 新响应不得生成 legacy contentRaw");
        ONode serialized = ONode.ofJson(ChatMessage.toJson(assistantMessage));
        assertFalse(serialized.hasKey("contentRaw"), "新状态 JSON 不应出现 legacy contentRaw");
        assertFalse(serialized.get("toolCalls").get(0).hasKey("thoughtSignature"),
                "新消息 JSON 不应出现 deprecated 签名字段");
        MessageProtocolState state = assistantMessage.getProtocolState(
                GeminiMessageStateSupport.GENERATE_CONTENT_PROTOCOL_ID);
        assertNotNull(state, assistantMessage.toString());
        assertNotNull(state.getSemanticHash(), assistantMessage.toString());
        assertEquals("sig_gem", acc.thinkingSignature, "acc.thinkingSignature 应同步置位");

        // JSON 恢复后优先从协议状态回放，不依赖运行时对象。
        AssistantMessage restored = (AssistantMessage) ChatMessage.fromJson(ChatMessage.toJson(assistantMessage));
        ONode restoredNode = builder.buildMessageNode(restored);
        assertEquals("sig_gem", restoredNode.get("parts").get(0).get("thoughtSignature").getString(),
                restoredNode.toJson());

        // 出站：part 级别（functionCall 同级）回传
        ONode node = builder.buildMessageNode(assistantMessage);
        ONode part = node.get("parts").get(0);
        assertEquals("sig_gem", part.get("thoughtSignature").getString(), node.toJson());
        assertTrue(part.hasKey("functionCall"), node.toJson());
    }

    /**
     * snake_case 兼容：部分网关按 REST 原始字段名下发 thought_signature。
     */
    @Test
    public void parsedThoughtSignature_snakeCaseAccepted() {
        ChatAccumulator acc = newAccumulator(true);
        ONode oContent = ONode.ofJson("{\"parts\":[{\"thought_signature\":\"sig_snake\","
                + "\"functionCall\":{\"name\":\"getWeather\",\"args\":{},\"id\":\"call-1\"}}]}");

        List<AssistantMessage> messages = processor.parse(acc, oContent);

        assertEquals("sig_snake", acc.thinkingSignature);
        AssistantMessage message = messages.get(messages.size() - 1);
        assertNull(message.getToolCalls().get(0).getThoughtSignature());
        assertNotNull(message.getProtocolState(GeminiMessageStateSupport.GENERATE_CONTENT_PROTOCOL_ID));
    }

    /**
     * 并行调用：仅第一个 part 回传 thoughtSignature（官方规范）。
     */
    @Test
    public void parallelCalls_onlyFirstPartCarriesSignature() {
        ChatAccumulator acc = newAccumulator(true);
        ONode oContent = ONode.ofJson("{\"parts\":["
                + "{\"thoughtSignature\":\"sig_gem\",\"functionCall\":{\"name\":\"getWeather\",\"args\":{\"city\":\"hz\"},\"id\":\"call-1\"}},"
                + "{\"functionCall\":{\"name\":\"getWeather\",\"args\":{\"city\":\"bj\"},\"id\":\"call-2\"}}]}");

        List<AssistantMessage> messages = processor.parse(acc, oContent);
        AssistantMessage assistantMessage = messages.get(messages.size() - 1);

        ONode node = builder.buildMessageNode(assistantMessage);

        assertEquals("sig_gem", node.get("parts").get(0).get("thoughtSignature").getString(), node.toJson());
        assertFalse(node.get("parts").get(1).hasKey("thoughtSignature"),
                "并行调用的后续 part 不应携带签名: " + node.toJson());
    }

    /**
     * 流式聚合出站路径：acc.thinkingSignature 置位后，仅第一个 functionCall part 携带签名。
     */
    @Test
    public void streamAggregation_signatureOnFirstPartOnly() {
        ChatAccumulator acc = newAccumulator(true);
        acc.thinkingSignature = "sig_gem";

        Map<String, ToolCallBuilder> builders = new LinkedHashMap<>();
        builders.put("getWeather", toolCallBuilder("call-1", "getWeather", "{\"city\":\"hz\"}"));
        builders.put("getWeather#1", toolCallBuilder("call-2", "getWeather", "{\"city\":\"bj\"}"));

        ONode node = builder.buildAssistantToolCallMessageNode(acc, builders);

        assertEquals("model", node.get("role").getString());
        assertEquals("sig_gem", node.get("parts").get(0).get("thoughtSignature").getString(), node.toJson());
        assertFalse(node.get("parts").get(1).hasKey("thoughtSignature"), node.toJson());
    }

    /**
     * Gemini 完整值快照被核心按字符串追加时，终态应取最后一个完整对象，不能取到前面的空占位对象。
     */
    @Test
    public void streamAggregation_usesLastJsonObjectSnapshot() {
        ChatAccumulator acc = newAccumulator(true);

        Map<String, ToolCallBuilder> builders = new LinkedHashMap<>();
        builders.put("getWeather", toolCallBuilder("call-1", "getWeather",
                "{}{\"location\":\"杭州\"}"));

        ONode node = builder.buildAssistantToolCallMessageNode(acc, builders);
        ONode args = node.get("parts").get(0).get("functionCall").get("args");

        assertTrue(args.isObject(), node.toJson());
        assertEquals("杭州", args.get("location").getString(), node.toJson());
    }

    @Test
    public void staleProtocolState_doesNotFallBackToLegacySignature() {
        AssistantMessage original = processor.parse(newAccumulator(false), ONode.ofJson(
                "{\"parts\":[{\"thoughtSignature\":\"sig_stale\","
                        + "\"functionCall\":{\"name\":\"getWeather\",\"args\":{},\"id\":\"call-1\"}}]}"))
                .get(0);
        original.getToolCalls().get(0).setThoughtSignature("sig_legacy");
        AssistantMessage changed = AssistantMessage.snapshot(
                "changed", "", original.getToolCalls(), null, null, null,
                Collections.singletonMap(
                        GeminiMessageStateSupport.GENERATE_CONTENT_PROTOCOL_ID,
                        original.getProtocolState(GeminiMessageStateSupport.GENERATE_CONTENT_PROTOCOL_ID)));

        ONode node = builder.buildMessageNode(changed);
        assertFalse(node.toJson().contains("thoughtSignature"), node.toJson());
    }

    @Test
    public void legacyToolCallJson_replaysSignatureWithoutProtocolState() {
        AssistantMessage restored = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"text\":\"\",\"thinking\":\"\"," +
                        "\"toolCalls\":[{\"index\":\"0\",\"id\":\"call-1\"," +
                        "\"name\":\"getWeather\",\"argumentsStr\":\"{}\"," +
                        "\"arguments\":{},\"thoughtSignature\":\"sig_old\"}]}");

        assertFalse(restored.hasProtocolStates());
        assertEquals("sig_old", restored.getToolCalls().get(0).getThoughtSignature());
        ONode node = builder.buildMessageNode(restored);
        assertEquals("sig_old", node.get("parts").get(0).get("thoughtSignature").getString(), node.toJson());
    }

    /**
     * 反向锚点：无签名时不写出 thoughtSignature 字段。
     */
    @Test
    public void streamAggregation_noSignature_fieldOmitted() {
        ChatAccumulator acc = newAccumulator(true);

        Map<String, ToolCallBuilder> builders = new LinkedHashMap<>();
        builders.put("getWeather", toolCallBuilder("call-1", "getWeather", "{}"));

        ONode node = builder.buildAssistantToolCallMessageNode(acc, builders);

        assertFalse(node.toJson().contains("thoughtSignature"), node.toJson());
    }

    private ToolCallBuilder toolCallBuilder(String id, String name, String args) {
        ToolCallBuilder b = new ToolCallBuilder();
        b.idBuilder.append(id);
        b.nameBuilder.append(name);
        b.argumentsBuilder.append(args);
        return b;
    }
}
