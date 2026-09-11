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

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.event.ChatStreamContextDefault;
import org.noear.solon.ai.chat.event.ChatStreamSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.generate.GenerateResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审查补测：此前无任何测试锚定的回归价值点
 *
 * <p>覆盖：Azure 部署路径补全、Responses URL 自动识别（matched）、legacy function_call
 * 的 id 回退链、keepalive 心跳、annotation 跨 item 全局幂等（锚定设计语义）、
 * prompt_cache_key、output_format=jpg 的 mimeType 归一、strict schema 嵌套补全。</p>
 */
public class OpenaiDialectRefinementTest {
    private final OpenaiResponsesResponseParser responsesParser = new OpenaiResponsesResponseParser();
    private final OpenaiChatDialect chatDialect = OpenaiChatDialect.getInstance();

    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newResponsesCtx() {
        events.clear();
        ChatConfig config = new ChatConfig();
        config.setModel("gpt-5.4");
        ChatRequest req = new ChatRequest(config, OpenaiResponsesDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);
        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, true),
                new ChatStreamSession(), 0, events::add);
    }

    private ChatStreamContext newChatCtx(boolean stream) {
        events.clear();
        ChatConfig config = new ChatConfig();
        config.setModel("gpt-4o");
        ChatRequest req = new ChatRequest(config, chatDialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);
        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, stream),
                new ChatStreamSession(), 0, events::add);
    }

    private long countOf(ChatEventType type) {
        long count = 0;
        for (ChatEvent event : events) {
            if (event.is(type)) {
                count++;
            }
        }
        return count;
    }

    // ==================== Azure 部署路径 ====================

    @Test
    public void azureDeploymentsUrl_appendsEndpointWithoutV1() {
        // Azure OpenAI 部署形态不带 /vN 版本段，此前会被误拼成 /openai/deployments/gpt-4o/v1/chat/completions；
        // api-version 查询串必须保留
        assertEquals("https://demo.openai.azure.com/openai/deployments/gpt-4o/chat/completions?api-version=2025-04-01-preview",
                OpenaiDialectSupport.buildApiUrl(
                        "https://demo.openai.azure.com/openai/deployments/gpt-4o?api-version=2025-04-01-preview",
                        "chat/completions"));
        assertEquals("https://demo.openai.azure.com/openai/deployments/gpt-4o/responses",
                OpenaiDialectSupport.buildApiUrl(
                        "https://demo.openai.azure.com/openai/deployments/gpt-4o/", "responses"));
        // 非 Azure 路径不受影响
        assertEquals("https://api.openai.com/v1/responses",
                OpenaiDialectSupport.buildApiUrl("https://api.openai.com", "responses"));
    }

    // ==================== matched：Responses URL 自动识别 ====================

    @Test
    public void responsesDialectMatchedByApiUrlSuffix() {
        ChatConfig config = new ChatConfig();
        config.setApiUrl("https://api.openai.com/v1/responses/");
        assertTrue(OpenaiResponsesDialect.getInstance().matched(config), "无 provider 时按 /responses 后缀识别");

        config.setApiUrl("https://api.openai.com/v1/responses?x=1#note");
        assertTrue(OpenaiResponsesDialect.getInstance().matched(config), "查询串与 fragment 不影响识别");

        config.setApiUrl("https://api.openai.com/v1/chat/completions");
        assertFalse(OpenaiResponsesDialect.getInstance().matched(config));
    }

    // ==================== legacy function_call 的 id 回退链 ====================

    @Test
    public void legacyFunctionCallWithoutId_fallsBackToLastToolCallId() {
        ChatStreamContext ctx = newChatCtx(true);

        // 第一帧：带 id 的 tool_calls 分片（index=5，与 legacy 归一的常量 index 0 错开，避免并入同一调用），
        // 让 acc.lastToolCallId 有值
        chatDialect.parseResponseJson(ctx, "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\","
                + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
                + "{\"id\":\"call_prev\",\"index\":5,\"type\":\"function\","
                + "\"function\":{\"name\":\"lookup\",\"arguments\":\"\"}}]}}]}");
        // 第二帧：legacy function_call 无 id → 回退 acc.lastToolCallId
        chatDialect.parseResponseJson(ctx, "{\"id\":\"chatcmpl-2\",\"object\":\"chat.completion.chunk\","
                + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"function_call\":"
                + "{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"hz\\\"}\"}}}]}");

        // 终态只包含第二帧的调用（第一帧空参数 lookup 不形成终态调用）；
        // 其 id 为 call_prev —— 正是 lastToolCallId 回退链生效的铁证
        List<org.noear.solon.ai.chat.tool.ToolCall> calls =
                ctx.getAccumulator().snapshotTerminal().getToolCalls();
        assertEquals(1, calls.size(), calls.toString());
        org.noear.solon.ai.chat.tool.ToolCall legacy = calls.get(0);
        assertEquals("call_prev", legacy.getId(), "无 id 的 legacy function_call 应回退 lastToolCallId");
        assertEquals("weather", legacy.getName());
        assertEquals("hz", legacy.getArguments().get("city"));
    }

    @Test
    public void legacyFunctionCallWithoutAnyId_usesConstantFallback() {
        ChatStreamContext ctx = newChatCtx(false);

        // 无前置 tool_calls：id 回退链落到 "legacy_function_call" 常量
        chatDialect.parseResponseJson(ctx, "{\"id\":\"chatcmpl-3\",\"object\":\"chat.completion\","
                + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"function_call\":{\"name\":\"ping\",\"arguments\":\"{}\"}},\"finish_reason\":\"stop\"}]}");

        List<org.noear.solon.ai.chat.tool.ToolCall> calls =
                ctx.getAccumulator().snapshotTerminal().getToolCalls();
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("legacy_function_call", calls.get(0).getId());
        assertEquals("ping", calls.get(0).getName());
    }

    // ==================== Responses keepalive → HEARTBEAT ====================

    @Test
    public void responsesKeepaliveBecomesHeartbeatEvent() {
        ChatStreamContext ctx = newResponsesCtx();

        assertTrue(responsesParser.parseStreamResponse(ctx, "{\"type\":\"keepalive\"}"));
        assertEquals(1, countOf(ChatEventType.HEARTBEAT));
        assertEquals(0, countOf(ChatEventType.RAW), "keepalive 不应落入未建模 RAW 分支");
        assertEquals(0, countOf(ChatEventType.TEXT_DELTA), "keepalive 不是模型内容");
    }

    // ==================== annotation 跨 item 全局幂等（锚定设计语义） ====================

    @Test
    public void annotationIdempotencySurvivesItemSwitching() {
        ChatStreamContext ctx = newResponsesCtx();

        String annotation = "{\"type\":\"response.output_text.annotation.added\",\"item_id\":\"msg_1\","
                + "\"output_index\":0,\"content_index\":0,\"annotation_index\":0,"
                + "\"annotation\":{\"type\":\"url_citation\",\"url\":\"https://example.com/a\"}}";

        // item 交错场景：annotation 与其它 item 的输出交替到达
        responsesParser.parseStreamResponse(ctx, annotation);
        responsesParser.parseStreamResponse(ctx, "{\"type\":\"response.output_item.added\","
                + "\"output_index\":1,\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\"}}");
        responsesParser.parseStreamResponse(ctx, "{\"type\":\"response.reasoning_text.delta\","
                + "\"item_id\":\"rs_1\",\"output_index\":1,\"content_index\":0,\"delta\":\"think\"}");
        // 同一 annotation 在 item 切换后重放：不得重复发射 CITATION
        responsesParser.parseStreamResponse(ctx, annotation);
        // 终态回放（completed 携带 annotations 的 message）
        responsesParser.parseStreamResponse(ctx, "{\"type\":\"response.completed\",\"response\":{\"output\":[{"
                + "\"id\":\"msg_1\",\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"a\","
                + "\"annotations\":[{\"type\":\"url_citation\",\"url\":\"https://example.com/a\"}]}]}}}");

        assertEquals(1, countOf(ChatEventType.CITATION), events.toString());
    }

    // ==================== prompt_cache_key ====================

    @Test
    public void promptCacheKeyMappedToResponsesField() {
        ChatOptions options = ChatOptions.of().cacheControl(CacheControl.ofPromptKey("session:abc:v1"));

        ONode root = new OpenaiResponsesRequestBuilder().build(
                new ChatConfig(), options, Collections.singletonList(ChatMessage.ofUser("hi")), false);

        assertEquals("session:abc:v1", root.get("prompt_cache_key").getString(), root.toJson());
        assertFalse(root.hasKey("prompt_cache_options"), "纯 cache key 不应触发 Anthropic 风格缓存选项: " + root.toJson());
    }

    // ==================== output_format=jpg 的 mimeType 归一 ====================

    @Test
    public void generateOutputFormatJpg_normalizesToJpegMimeType() {
        String json = "{\"model\":\"gpt-image-1\",\"output_format\":\"jpg\","
                + "\"data\":[{\"b64_json\":\"QUJD\"}]}";

        GenerateResponse resp = OpenaiGenerateDialect.getInstance().parseResponseJson(new GenerateConfigForTest(), json);

        assertNotNull(resp.getData());
        assertEquals(1, resp.getData().size());
        assertEquals("image/jpeg", resp.getData().get(0).getMimeType(), "output_format=jpg 应归一为 image/jpeg");
    }

    /** GenerateConfig 最小可用实例（model/taskUrl 为 null 即可覆盖本用例路径） */
    private static class GenerateConfigForTest extends org.noear.solon.ai.generate.GenerateConfig {
    }

    // ==================== strict schema 嵌套补全 ====================

    @Test
    public void strictSchemaAppliesToNestedObjectsInsideArrays() {
        // array items 内的 object 子 schema 同样要补 additionalProperties=false 与全量 required
        ChatOptions options = ChatOptions.of().outputSchema("{\"type\":\"object\",\"properties\":{\"items\":"
                + "{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":"
                + "{\"name\":{\"type\":\"string\"},\"age\":{\"type\":\"integer\"}}}}}}");
        OpenaiResponsesDialect.getInstance().prepareOutputFormatOptions(options);

        ONode root = new OpenaiResponsesRequestBuilder().build(
                new ChatConfig(), options, Collections.singletonList(ChatMessage.ofUser("hi")), false);

        ONode nested = root.get("text").get("format").get("schema")
                .get("properties").get("items").get("items");
        assertFalse(nested.get("additionalProperties").getBoolean(), root.toJson());
        assertEquals(2, nested.get("required").getArray().size(), "嵌套 required 必须覆盖全部 properties: " + root.toJson());
        assertTrue(nested.get("required").getArray().stream()
                .anyMatch(n -> "age".equals(n.getString())), root.toJson());
    }
}
