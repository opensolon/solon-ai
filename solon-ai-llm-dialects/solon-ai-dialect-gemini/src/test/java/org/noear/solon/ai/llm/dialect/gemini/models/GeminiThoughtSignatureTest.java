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
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.llm.dialect.gemini.GeminiChatDialect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * thoughtSignature 跨轮回传链路的离线回归（Gemini Generate Content API）
 *
 * <p>Gemini 3 的 thoughtSignature 位于 part 级别（functionCall 同级），并行调用时仅第一个 part 携带。
 * 入站由 {@link GeminiThoughtProcessor} 写入 ToolCall.thoughtSignature 与 acc.thinkingSignature，
 * 出站由 {@link GeminiRequestBuilder} 从这两条路径任一取回。签名丢失会导致下一轮思考上下文断裂。</p>
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
     * 入站 → 出站闭环：解析出的 ToolCall.thoughtSignature 在下一轮 parts 上原样回传。
     */
    @Test
    public void parsedThoughtSignature_replayedOnFunctionCallPart() {
        ChatAccumulator acc = newAccumulator(true);
        ONode oContent = ONode.ofJson("{\"parts\":[{\"thoughtSignature\":\"sig_gem\","
                + "\"functionCall\":{\"name\":\"getWeather\",\"args\":{\"city\":\"hz\"},\"id\":\"call-1\"}}]}");

        List<AssistantMessage> messages = processor.parse(acc, oContent);

        AssistantMessage assistantMessage = messages.get(messages.size() - 1);
        ToolCall call = assistantMessage.getToolCalls().get(0);
        assertEquals("sig_gem", call.getThoughtSignature(), "ToolCall 应携带解析到的 thoughtSignature");
        assertEquals("sig_gem", acc.thinkingSignature, "acc.thinkingSignature 应同步置位");

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
        assertEquals("sig_snake",
                messages.get(messages.size() - 1).getToolCalls().get(0).getThoughtSignature());
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
