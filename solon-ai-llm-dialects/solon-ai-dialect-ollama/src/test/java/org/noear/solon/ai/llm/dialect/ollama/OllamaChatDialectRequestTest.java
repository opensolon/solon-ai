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
package org.noear.solon.ai.llm.dialect.ollama;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ollama 出站报文规约
 *
 * <p>覆盖 {@code buildRequestJson}（keep_alive / prompt_cache_key / options 透传 / thinking 过滤 / tools）、
 * {@code buildUserMessageNodeDo} 与 {@code buildAssistantMessageNodeDo}（content 为字符串 +
 * images/audios/videos 侧车，而非 OpenAI content 数组）、以及
 * {@code buildAssistantToolCallMessageNode}（arguments 为对象节点）。</p>
 *
 * @author noear
 */
public class OllamaChatDialectRequestTest {
    private final OllamaChatDialect dialect = OllamaChatDialect.getInstance();

    private ChatConfig config() {
        ChatConfig config = new ChatConfig();
        config.setStandard("ollama");
        config.setApiUrl("http://localhost:11434/api/chat");
        config.setModel("qwen3:8b");
        return config;
    }

    private List<ONode> arrayOf(ONode node) {
        assertTrue(node.isArray(), "expect an array node");
        return node.getArray();
    }

    private List<Map> toolCallsRaw(String name, String arguments) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("arguments", arguments);

        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "call_1");
        call.put("type", "function");
        call.put("function", function);

        List<Map> raw = new ArrayList<>();
        raw.add(call);
        return raw;
    }

    /// ////////////////////// buildRequestJson

    /**
     * 基础形态：model + messages + stream，无缓存相关字段
     */
    @Test
    public void requestCarriesModelMessagesAndStreamFlag() {
        ONode node = dialect.buildRequestJson(config(), ChatOptions.of(),
                Arrays.asList(ChatMessage.ofSystem("你是助手"), ChatMessage.ofUser("你好")), true);

        assertEquals("qwen3:8b", node.get("model").getString());
        assertTrue(node.get("stream").getBoolean());
        assertEquals(2, arrayOf(node.get("messages")).size());
        assertEquals("system", arrayOf(node.get("messages")).get(0).get("role").getString());
        assertEquals("你好", arrayOf(node.get("messages")).get(1).get("content").getString());

        assertFalse(node.hasKey("keep_alive"), "无 keep_alive 选项、无 cacheControl 时不应写出该字段");
        assertFalse(node.hasKey("prompt_cache_key"));
        assertFalse(node.hasKey("tools"));
    }

    /**
     * 非流式：stream=false
     */
    @Test
    public void requestStreamFalseForSyncCall() {
        ONode node = dialect.buildRequestJson(config(), ChatOptions.of(),
                Arrays.asList(ChatMessage.ofUser("你好")), false);

        assertFalse(node.get("stream").getBoolean());
    }

    /**
     * model 为空时不写出该键（由服务端或网关决定）
     */
    @Test
    public void requestOmitsModelWhenEmpty() {
        ChatConfig config = config();
        config.setModel(null);

        ONode node = dialect.buildRequestJson(config, ChatOptions.of(),
                Arrays.asList(ChatMessage.ofUser("你好")), false);

        assertFalse(node.hasKey("model"));
    }

    /**
     * keep_alive：显式选项优先于 cacheControl.ttl
     */
    @Test
    public void keepAliveFromOptionWinsOverCacheTtl() {
        ChatOptions options = ChatOptions.of()
                .optionSet("keep_alive", "30m")
                .cacheControl(CacheControl.ofEphemeral("1h"));

        ONode node = dialect.buildRequestJson(config(), options,
                Arrays.asList(ChatMessage.ofUser("你好")), false);

        assertEquals("30m", node.get("keep_alive").getString());
    }

    /**
     * keep_alive：无显式选项时取 cacheControl.ttl（KV Cache 驻留时长）
     */
    @Test
    public void keepAliveFallsBackToCacheControlTtl() {
        ONode node = dialect.buildRequestJson(config(),
                ChatOptions.of().cacheControl(CacheControl.ofEphemeral("1h")),
                Arrays.asList(ChatMessage.ofUser("你好")), false);

        assertEquals("1h", node.get("keep_alive").getString());
    }

    /**
     * keep_alive：ttl 未显式指定时用供应商默认 5m
     */
    @Test
    public void keepAliveUsesDefaultTtlWhenUnset() {
        ONode node = dialect.buildRequestJson(config(),
                ChatOptions.of().cacheControl(CacheControl.ofEphemeral()),
                Arrays.asList(ChatMessage.ofUser("你好")), false);

        assertEquals("5m", node.get("keep_alive").getString());
        assertFalse(node.hasKey("prompt_cache_key"), "ephemeral 缓存不带 promptCacheKey");
    }

    /**
     * prompt_cache_key：OpenAI 兼容模式的前缀缓存键
     */
    @Test
    public void promptCacheKeyWrittenFromCacheControl() {
        ONode node = dialect.buildRequestJson(config(),
                ChatOptions.of().cacheControl(CacheControl.ofPromptKey("session:abc:v1")),
                Arrays.asList(ChatMessage.ofUser("你好")), false);

        assertEquals("session:abc:v1", node.get("prompt_cache_key").getString());
        //ofPromptKey 不带 type，但 ttl 有默认值，故 keep_alive 仍写出
        assertEquals("5m", node.get("keep_alive").getString());
    }

    /**
     * options 原样透传（含 Ollama 私有的嵌套 options 对象）
     */
    @Test
    public void optionsArePassedThrough() {
        Map<String, Object> ollamaOptions = new LinkedHashMap<>();
        ollamaOptions.put("num_ctx", 4096);
        ollamaOptions.put("num_predict", 512);

        ChatOptions options = ChatOptions.of()
                .temperature(0.7D)
                .optionSet("options", ollamaOptions)
                .optionSet("think", true);

        ONode node = dialect.buildRequestJson(config(), options,
                Arrays.asList(ChatMessage.ofUser("你好")), true);

        assertEquals(0.7D, node.get("temperature").getDouble(), 0.0001D);
        assertEquals(4096, node.get("options").get("num_ctx").getInt());
        assertEquals(512, node.get("options").get("num_predict").getInt());
        assertTrue(node.get("think").getBoolean());
    }

    /**
     * 统一 thinking(Boolean) 映射为 Ollama 原生 think，内部 thinking 键不得泄漏。
     */
    @Test
    public void unifiedThinkingMapsToNativeThink() {
        ONode enabled = dialect.buildRequestJson(config(), ChatOptions.of().thinking(true),
                Arrays.asList(ChatMessage.ofUser("你好")), false);
        assertTrue(enabled.get("think").getBoolean());
        assertFalse(enabled.hasKey("thinking"));

        ONode disabled = dialect.buildRequestJson(config(), ChatOptions.of().thinking(false),
                Arrays.asList(ChatMessage.ofUser("你好")), false);
        assertTrue(disabled.hasKey("think"));
        assertFalse(disabled.get("think").getBoolean());
        assertFalse(disabled.hasKey("thinking"));
    }

    /**
     * 原生 think 逃生舱保持原值，并优先于统一 thinking 映射。
     */
    @Test
    public void nativeThinkOptionIsPreserved() {
        ChatOptions options = ChatOptions.of().thinking(true).optionSet("think", false);
        ONode node = dialect.buildRequestJson(config(), options,
                Arrays.asList(ChatMessage.ofUser("你好")), false);

        assertTrue(node.hasKey("think"));
        assertFalse(node.get("think").getBoolean());
        assertFalse(node.hasKey("thinking"));
    }

    /**
     * 方言本地工具参数模式只用于响应解析，不得进入 Ollama 请求体。
     */
    @Test
    public void localToolArgumentModeIsNotPassedThrough() {
        ONode node = dialect.buildRequestJson(config(), ChatOptions.of()
                        .optionSet("ollama_tool_arguments_mode", "snapshot")
                        .optionSet("think", true),
                Arrays.asList(ChatMessage.ofUser("你好")), true);

        assertFalse(node.hasKey("ollama_tool_arguments_mode"));
        assertTrue(node.get("think").getBoolean());
    }

    /**
     * 思考内容由目标 Ollama 方言统一构建为 thinking，不依赖源 reasoningFieldName。
     */
    @Test
    public void thinkingContentIsWrittenByTargetDialect() {
        List<ToolCall> toolCalls = new ArrayList<>();
        toolCalls.add(new ToolCall("get_weather", "call_1", "get_weather", "{\"city\":\"杭州\"}", null));

        AssistantMessage thinkingOnly = new AssistantMessage("", "让我想想");
        AssistantMessage mixed = new AssistantMessage("混合正文", "混合思考");
        AssistantMessage carrier = new AssistantMessage("", "载体思考");
        carrier.addMetadata("provider_state", "keep");
        AssistantMessage thinkingWithToolCalls = new AssistantMessage("", "让我想想", toolCalls, null);

        ONode node = dialect.buildRequestJson(config(), ChatOptions.of(),
                Arrays.asList(ChatMessage.ofUser("杭州天气"), thinkingOnly, mixed, carrier, thinkingWithToolCalls,
                        ChatMessage.ofUser("继续")), false);

        List<ONode> messages = arrayOf(node.get("messages"));
        assertEquals(6, messages.size());
        assertEquals("user", messages.get(0).get("role").getString());
        assertEquals("让我想想", messages.get(1).get("thinking").getString());
        assertFalse(messages.get(1).hasKey("reasoning"));
        assertEquals("混合正文", messages.get(2).get("content").getString());
        assertEquals("混合思考", messages.get(2).get("thinking").getString());
        assertFalse(messages.get(2).hasKey("reasoning"));
        assertEquals("assistant", messages.get(3).get("role").getString(), "metadata 不影响目标字段选择");
        assertEquals("载体思考", messages.get(3).get("thinking").getString());
        assertEquals("assistant", messages.get(4).get("role").getString());
        assertEquals("让我想想", messages.get(4).get("thinking").getString());
        assertEquals("get_weather",
                arrayOf(messages.get(4).get("tool_calls")).get(0).get("function").get("name").getString());
        assertEquals("继续", messages.get(5).get("content").getString());
    }

    /**
     * tools 节点：type=function + function{name,description,parameters}
     */
    @Test
    public void toolsNodeIsBuiltFromOptions() {
        ChatOptions options = ChatOptions.of()
                .toolAdd("get_weather", desc -> desc
                        .description("查询天气")
                        .stringParamAdd("city", "城市"));

        ONode node = dialect.buildRequestJson(config(), options,
                Arrays.asList(ChatMessage.ofUser("杭州天气")), false);

        List<ONode> tools = arrayOf(node.get("tools"));
        assertEquals(1, tools.size());
        assertEquals("function", tools.get(0).get("type").getString());

        ONode function = tools.get(0).get("function");
        assertEquals("get_weather", function.get("name").getString());
        assertTrue(function.get("description").getString().contains("查询天气"));
        assertTrue(function.get("parameters").isObject());
        assertTrue(function.get("parameters").get("properties").hasKey("city"));
    }

    /// ////////////////////// user message

    /**
     * 单模态用户消息：content 为字符串，无媒体侧车
     */
    @Test
    public void userSingleModalKeepsPlainContent() {
        ONode node = dialect.buildChatMessageNode(config(), ChatMessage.ofUser("你好"));

        assertEquals("user", node.get("role").getString());
        assertEquals("你好", node.get("content").getString());
        assertFalse(node.hasKey("images"));
        assertFalse(node.hasKey("audios"));
        assertFalse(node.hasKey("videos"));
    }

    /**
     * 多模态用户消息：content 仍是字符串，媒体走 images/audios/videos 侧车；
     * base64 不加 data: 前缀（Ollama 协议要求裸 base64），url 原样
     */
    @Test
    public void userMultiModalUsesOllamaSidecarArrays() {
        ONode node = dialect.buildChatMessageNode(config(), ChatMessage.ofUser("看看这些",
                ImageBlock.ofBase64("QUJD", "image/png"),
                AudioBlock.ofBase64("YXVkaW8=", "audio/wav"),
                VideoBlock.ofUrl("https://example.com/v.mp4")));

        assertEquals("看看这些", node.get("content").getString());
        assertFalse(node.get("content").isArray(), "Ollama 不使用 OpenAI 的 content 数组");

        assertEquals(1, arrayOf(node.get("images")).size());
        assertEquals("QUJD", arrayOf(node.get("images")).get(0).getString());
        assertEquals("YXVkaW8=", arrayOf(node.get("audios")).get(0).getString());
        assertEquals("https://example.com/v.mp4", arrayOf(node.get("videos")).get(0).getString());
    }

    /**
     * 同类媒体多块：追加进同一侧车数组
     */
    @Test
    public void userMultiImagesGoIntoOneArray() {
        ONode node = dialect.buildChatMessageNode(config(), ChatMessage.ofUser("两张图",
                ImageBlock.ofBase64("QUE="), ImageBlock.ofUrl("https://example.com/2.png")));

        List<ONode> images = arrayOf(node.get("images"));
        assertEquals(2, images.size());
        assertEquals("QUE=", images.get(0).getString());
        assertEquals("https://example.com/2.png", images.get(1).getString());
    }

    /**
     * Session 截断后的空媒体：不可播（无 data/url/id）与仅有 id 的占位都不写出侧车
     */
    @Test
    public void userSkipsUnplayableAndEmptyMedia() {
        ONode node = dialect.buildChatMessageNode(config(), ChatMessage.ofUser("被截断了",
                ImageBlock.ofUrl(null),
                ImageBlock.ofUrl(null).metaAdd("id", "img-1")));

        assertEquals("被截断了", node.get("content").getString());
        assertFalse(node.hasKey("images"), "空媒体不得写出 null/空串侧车");
    }

    /// ////////////////////// assistant message

    /**
     * 助理消息：有文本时 content 为该文本
     */
    @Test
    public void assistantTextGoesToContent() {
        ONode node = dialect.buildChatMessageNode(config(), ChatMessage.ofAssistant("杭州今天晴"));

        assertEquals("assistant", node.get("role").getString());
        assertEquals("杭州今天晴", node.get("content").getString());
        assertFalse(node.hasKey("tool_calls"));
        assertFalse(node.hasKey("reasoning"));
    }

    /**
     * 助理消息：无文本时也必须写出 content 键（空串），否则空消息会被服务端拒绝
     */
    @Test
    public void assistantEmptyTextStillWritesContentKey() {
        ONode node = dialect.buildChatMessageNode(config(), ChatMessage.ofAssistant(""));

        assertTrue(node.hasKey("content"));
        assertEquals("", node.get("content").getString());
    }

    /**
     * 助理消息：Ollama 固定使用原生 thinking，旧字段名不能改变目标 key。
     */
    @Test
    public void assistantThinkingWrittenByTargetDialect() {
        AssistantMessage msg = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"text\":\"答案\",\"thinking\":\"推理过程\"," +
                        "\"reasoningFieldName\":\"reasoning\"}");

        ONode node = dialect.buildChatMessageNode(config(), msg);

        assertEquals("答案", node.get("content").getString());
        assertEquals("推理过程", node.get("thinking").getString());
        assertFalse(node.hasKey("reasoning"));
        assertFalse(node.hasKey("reasoning_content"));
    }

    /**
     * 助理消息：思考为空时不写 thinking。
     */
    @Test
    public void assistantThinkingSkippedWhenThinkingEmpty() {
        ONode noFieldName = dialect.buildChatMessageNode(config(),
                new AssistantMessage("答案", "推理过程"));
        assertEquals("推理过程", noFieldName.get("thinking").getString());
        assertFalse(noFieldName.hasKey("reasoning"));

        ONode noThinking = dialect.buildChatMessageNode(config(), new AssistantMessage("答案", ""));
        assertFalse(noThinking.hasKey("thinking"));
        assertFalse(noThinking.hasKey("reasoning"));
    }

    /**
     * 助理消息（多模态）：媒体同样走 Ollama 侧车
     */
    @Test
    public void assistantMultiModalUsesSidecarArrays() {
        ONode node = dialect.buildChatMessageNode(config(),
                ChatMessage.ofAssistant("生成好了", ImageBlock.ofBase64("QUJD", "image/png")));

        assertEquals("生成好了", node.get("content").getString());
        assertEquals("QUJD", arrayOf(node.get("images")).get(0).getString());
    }

    /**
     * 助理消息：合法 arguments 规范化后回传
     */
    @Test
    public void assistantToolCallsRawKeptWhenValid() {
        AssistantMessage msg = legacyAssistantWithToolCalls(
                toolCallsRaw("get_weather", "{\"city\":\"杭州\"}"));

        ONode node = dialect.buildChatMessageNode(config(), msg);

        List<ONode> calls = arrayOf(node.get("tool_calls"));
        assertEquals(1, calls.size());
        assertEquals("call_1", calls.get(0).get("id").getString());
        assertEquals("杭州",
                calls.get(0).get("function").get("arguments").get("city").getString());
    }

    @Test
    public void assistantTypedToolCallsUseObjectArguments() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("city", "杭州");
        ToolCall typed = new ToolCall("0", "typed_1", "get_weather", null, args);
        ONode legacy = ONode.ofJson(ChatMessage.toJson(
                new AssistantMessage("", "", Arrays.asList(typed), null)));
        legacy.set("toolCallsRaw", toolCallsRaw("raw_weather", "{\"city\":\"上海\"}"));
        AssistantMessage msg = (AssistantMessage) ChatMessage.fromJson(legacy.toJson());

        ONode call = dialect.buildChatMessageNode(config(), msg).get("tool_calls").get(0);
        assertEquals("typed_1", call.get("id").getString());
        assertEquals("get_weather", call.get("function").get("name").getString());
        assertEquals("杭州", call.get("function").get("arguments").get("city").getString());
    }

    /**
     * 助理消息：截断损坏的 arguments 出站前被净化为 {}，避免服务端 400（会话中毒）
     */
    @Test
    public void assistantToolCallsRawSanitizedWhenBroken() {
        AssistantMessage msg = legacyAssistantWithToolCalls(
                toolCallsRaw("get_weather", "{\"city\":\"杭"));

        ONode node = dialect.buildChatMessageNode(config(), msg);

        assertEquals("{}", arrayOf(node.get("tool_calls")).get(0)
                .get("function").get("arguments").getString());
    }

    private AssistantMessage legacyAssistantWithToolCalls(List<Map> toolCallsRaw) {
        ONode legacy = new ONode().set("role", "assistant").set("text", "").set("thinking", "");
        legacy.set("toolCallsRaw", toolCallsRaw);
        return (AssistantMessage) ChatMessage.fromJson(legacy.toJson());
    }

    /// ////////////////////// buildAssistantToolCallMessageNode

    /**
     * 流式聚合出口：Ollama 的 arguments 是 JSON 对象节点（不是 JSON 字符串），
     * 且截断分片在出口被净化为空对象
     */
    @Test
    public void toolCallMessageNodeWritesArgumentsAsObject() {
        ChatConfig config = config();
        ChatRequest req = new ChatRequest(config, dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);
        ChatAccumulator acc = new ChatAccumulator(req, true);
        acc.appendText("正在查询");

        Map<String, ToolCallBuilder> builders = new LinkedHashMap<>();

        ToolCallBuilder good = new ToolCallBuilder();
        good.idBuilder.append("call_1");
        good.nameBuilder.append("get_weather");
        good.argumentsBuilder.append("{\"city\":\"杭州\"}");
        builders.put("get_weather", good);

        ToolCallBuilder broken = new ToolCallBuilder();
        broken.nameBuilder.append("get_air");
        broken.argumentsBuilder.append("{\"city\":\"杭");
        builders.put("get_air", broken);

        ONode node = dialect.buildAssistantToolCallMessageNode(acc, builders);

        assertEquals("assistant", node.get("role").getString());
        assertEquals("正在查询", node.get("content").getString());

        List<ONode> calls = arrayOf(node.get("tool_calls"));
        assertEquals(2, calls.size());

        ONode first = calls.get(0);
        assertEquals("call_1", first.get("id").getString());
        assertEquals("function", first.get("type").getString());
        assertEquals("get_weather", first.get("function").get("name").getString());
        assertTrue(first.get("function").get("arguments").isObject(),
                "ollama 的 arguments 必须是对象节点");
        assertEquals("杭州", first.get("function").get("arguments").get("city").getString());

        ONode second = calls.get(1);
        assertEquals("", second.get("id").getString(), "ollama 协议无 tool call id");
        assertTrue(second.get("function").get("arguments").isObject());
        assertTrue(second.get("function").get("arguments").getObject().isEmpty(),
                "截断的 arguments 应被净化为空对象");
    }
}
