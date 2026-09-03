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
package org.noear.solon.ai.llm.dialect.anthropic;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anthropic 方言剩余对齐项：原生结构化输出（{@code output_config.format}）、严格工具
 * （{@code tools[].strict}）、beta 能力头协商、{@code server_tool_use.caller}。
 *
 * <p>这些是 GA 协议里已存在、旧实现完全无法表达的字段：结构化输出只能靠“把 schema 写进 system
 * 提示词”兜底；strict 无处可写；beta 声明若放进请求体会变成非法顶层字段；caller 被整块丢弃，
 * programmatic tool calling 场景分不清是模型直调还是代码执行内嵌调用。</p>
 *
 * @author noear
 */
public class AnthropicStructuredOutputAlignTest {
    private final AnthropicRequestBuilder requestBuilder = new AnthropicRequestBuilder();
    private final AnthropicResponseParser parser = new AnthropicResponseParser();

    private final List<ChatEvent> events = new ArrayList<>();

    /**
     * 嵌套一层对象的 schema：用于验证规范化是递归的（items 里的对象也要补齐）
     */
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"name\":{\"type\":\"string\"},"
            + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"object\","
            + "\"properties\":{\"k\":{\"type\":\"string\"}}}}"
            + "}}";

    private ONode build(String model, ChatOptions options) {
        ChatConfig config = new ChatConfig();
        config.setModel(model);
        return requestBuilder.build(config, options, Arrays.asList(ChatMessage.ofUser("hi")), false);
    }

    private static List<String> stringList(ONode arrayNode) {
        List<String> list = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (ONode item : arrayNode.getArray()) {
                list.add(item.getString());
            }
        }
        return list;
    }

    /// ///////////////// 原生结构化输出

    /**
     * GA 形态：{@code output_config.format = {type:"json_schema", schema:...}}，不需要 anthropic-beta 头。
     * 旧实现只有“schema 塞进 system 提示词”的兜底，模型可以不遵守
     */
    @Test
    public void nativeStructuredOutputOnSupportedModel() {
        ONode root = build("claude-sonnet-4-5", ChatOptions.of().outputSchema(SCHEMA));

        ONode format = root.get("output_config").get("format");
        assertEquals("json_schema", format.get("type").getString());

        ONode schema = format.get("schema");
        assertEquals("object", schema.get("type").getString());
        //协议硬性要求：对象必须显式 additionalProperties:false
        assertEquals(Boolean.FALSE, schema.get("additionalProperties").getBoolean());
        //协议硬性要求：required 列全属性
        assertEquals(Arrays.asList("name", "tags"), stringList(schema.get("required")));
    }

    /**
     * 规范化必须递归：数组 items 内的对象同样要补 additionalProperties/required，
     * 只补根对象仍会被服务端拒
     */
    @Test
    public void schemaNormalizationIsRecursive() {
        ONode root = build("claude-opus-4-5-20251101", ChatOptions.of().outputSchema(SCHEMA));

        ONode items = root.get("output_config").get("format").get("schema")
                .get("properties").get("tags").get("items");

        assertEquals(Boolean.FALSE, items.get("additionalProperties").getBoolean());
        assertEquals(Arrays.asList("k"), stringList(items.get("required")));
    }

    /**
     * 官方支持列表是 Claude 4.5 及以后。更早模型传 output_config.format 会 400，
     * 必须退回提示词兜底
     */
    @Test
    public void legacyModelFallsBackToInstruction() {
        ONode root = build("claude-3-5-sonnet-20241022", ChatOptions.of().outputSchema(SCHEMA));
        assertFalse(root.hasKey("output_config"), "3.5 不支持结构化输出，不能写 output_config");

        ONode root37 = build("claude-3-7-sonnet-20250219", ChatOptions.of().outputSchema(SCHEMA));
        assertFalse(root37.hasKey("output_config"));

        //倒置命名（SAP 等网关）同样要能识别出 3.5
        ONode inverted = build("claude-3-5-sonnet", ChatOptions.of().outputSchema(SCHEMA));
        assertFalse(inverted.hasKey("output_config"));
    }

    /**
     * 版本号解析的日期陷阱：{@code claude-sonnet-4-20250514} 的日期串不能被当成次版本号，
     * 否则 Sonnet 4（不支持）会被误判成 4.5+ 并写出 output_config
     */
    @Test
    public void dateSuffixIsNotParsedAsMinorVersion() {
        ONode sonnet4 = build("claude-sonnet-4-20250514", ChatOptions.of().outputSchema(SCHEMA));
        assertFalse(sonnet4.hasKey("output_config"), "Sonnet 4 = 4.0，不满足 4.5 门槛");

        ONode opus41 = build("claude-opus-4-1-20250805", ChatOptions.of().outputSchema(SCHEMA));
        assertFalse(opus41.hasKey("output_config"), "Opus 4.1 不在 GA 支持列表内");

        ONode haiku3 = build("claude-3-haiku-20240307", ChatOptions.of().outputSchema(SCHEMA));
        assertFalse(haiku3.hasKey("output_config"));
    }

    /**
     * 4.5 及以后的各种命名都应识别（含 4.6/5 与 fable/mythos 系列）
     */
    @Test
    public void newerModelFamiliesAreSupported() {
        for (String model : Arrays.asList("claude-haiku-4-5-20251001", "claude-sonnet-4-6",
                "claude-opus-4-7", "claude-sonnet-5", "claude-opus-5",
                "claude-fable-5", "claude-mythos-preview", "claude-4-6-sonnet")) {
            ONode root = build(model, ChatOptions.of().outputSchema(SCHEMA));
            assertTrue(root.hasKey("output_config"), model + " 应支持原生结构化输出");
        }
    }

    /**
     * 逃生门：网关不支持该字段时用 structured_outputs=false 强制退回提示词兜底；
     * true 则跳过模型判定强制启用（自建网关代理新模型但模型名不规范时）
     */
    @Test
    public void structuredOutputToggleOverridesModelGate() {
        ONode disabled = build("claude-sonnet-4-5",
                ChatOptions.of().outputSchema(SCHEMA).optionSet("structured_outputs", false));
        assertFalse(disabled.hasKey("output_config"));

        ONode forced = build("my-claude-proxy",
                ChatOptions.of().outputSchema(SCHEMA).optionSet("structured_outputs", true));
        assertEquals("json_schema", forced.get("output_config").get("format").get("type").getString());

        //开关本身是方言合成选项，不能出现在请求体里
        assertFalse(disabled.hasKey("structured_outputs"));
        assertFalse(forced.hasKey("structured_outputs"));
    }

    /**
     * 用户已显式给出原生 output_config.format 时不覆盖，也不做规范化改写
     */
    @Test
    public void explicitOutputConfigFormatIsPreserved() {
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        format.put("schema", schema);
        Map<String, Object> outputConfig = new LinkedHashMap<>();
        outputConfig.put("format", format);

        ONode root = build("claude-sonnet-4-5", ChatOptions.of()
                .outputSchema(SCHEMA)
                .optionSet("output_config", outputConfig));

        assertFalse(root.get("output_config").get("format").get("schema").hasKey("properties"),
                "用户显式 format 不应被 outputSchema 覆盖");
    }

    /**
     * output_config 是 effort（adaptive thinking）与 format（结构化输出）共用的节点，
     * 两者必须共存而不是互相覆盖
     */
    @Test
    public void structuredOutputCoexistsWithAdaptiveEffort() {
        ONode root = build("claude-sonnet-5", ChatOptions.of()
                .outputSchema(SCHEMA)
                .optionSet("reasoning_effort", "high"));

        assertEquals("adaptive", root.get("thinking").get("type").getString());
        assertEquals("high", root.get("output_config").get("effort").getString());
        assertEquals("json_schema", root.get("output_config").get("format").get("type").getString());
    }

    /**
     * 没有 outputSchema 就不该凭空写出 output_config.format
     */
    @Test
    public void noSchemaNoOutputFormat() {
        ONode root = build("claude-sonnet-4-5", ChatOptions.of());
        assertFalse(root.hasKey("output_config"));
    }

    /// ///////////////// 严格工具

    /**
     * 协议 {@code Tool.strict}：与结构化输出同期 GA，旧实现无处表达。
     * 只能显式 opt-in——开启后服务端会校验每个 input_schema，存量工具可能整批被拒
     */
    @Test
    public void strictToolsIsOptIn() {
        ChatOptions strict = ChatOptions.of()
                .optionSet("strict_tools", true)
                .toolAdd("get_weather", t -> t.description("查天气").stringParamAdd("city", "城市"));
        ONode root = build("claude-sonnet-4-5", strict);

        assertEquals(Boolean.TRUE, root.get("tools").get(0).get("strict").getBoolean());
        //合成开关不进请求体
        assertFalse(root.hasKey("strict_tools"));

        ChatOptions plain = ChatOptions.of()
                .toolAdd("get_weather", t -> t.description("查天气").stringParamAdd("city", "城市"));
        ONode plainRoot = build("claude-sonnet-4-5", plain);
        assertFalse(plainRoot.get("tools").get(0).hasKey("strict"),
                "默认不写 strict，保持既有行为");
    }

    /// ///////////////// beta 能力协商

    /**
     * beta 能力在协议上只走请求头：GA 的 MessageCreateParams 没有 betas 字段，
     * 放进请求体就是非法顶层字段
     */
    @Test
    public void betaOptionsNeverEnterRequestBody() {
        ONode root = build("claude-sonnet-4-5", ChatOptions.of()
                .optionSet("anthropic_beta", "context-1m-2025-08-07")
                .optionSet("betas", Arrays.asList("fine-grained-tool-streaming-2025-05-14")));

        assertFalse(root.hasKey("anthropic_beta"));
        assertFalse(root.hasKey("betas"));
    }

    /**
     * 多来源 beta 声明汇总成单个逗号分隔头值，按声明序去重
     */
    @Test
    public void betaHeaderIsMergedAndDeduped() {
        String header = AnthropicRequestBuilder.resolveBetaHeader(ChatOptions.of()
                .optionSet("anthropic_beta", "context-1m-2025-08-07, skills-2025-10-02")
                .optionSet("betas", Arrays.asList("skills-2025-10-02", "code-execution-2025-05-22")));

        assertEquals("context-1m-2025-08-07,skills-2025-10-02,code-execution-2025-05-22", header);

        assertNull(AnthropicRequestBuilder.resolveBetaHeader(ChatOptions.of()));
        assertNull(AnthropicRequestBuilder.resolveBetaHeader(null));
    }

    /**
     * 数组与空白值的宽容解析
     */
    @Test
    public void betaCollectionAcceptsArrayAndTrimsBlank() {
        Set<String> out = new LinkedHashSet<>();
        AnthropicRequestBuilder.collectBetas(new String[]{"a", " b ", ""}, out);
        AnthropicRequestBuilder.collectBetas(",,", out);
        assertEquals(Arrays.asList("a", "b"), new ArrayList<>(out));
    }

    /// ///////////////// server_tool_use.caller

    private ChatStreamContext newCtx() {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");
        ChatRequest req = new ChatRequest(config, AnthropicChatDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, true),
                new ChatStreamSession(), 0, events::add);
    }

    private ChatEvent firstOf(ChatEventType type) {
        for (ChatEvent e : events) {
            if (e.getType() == type) {
                return e;
            }
        }
        return null;
    }

    /**
     * 协议 {@code ServerToolUseBlock.caller} 是三变体联合，代码执行变体还带 tool_id。
     * 不读它就无法分辨 programmatic tool calling 里是模型直调还是代码执行内嵌调用
     */
    @Test
    public void serverToolCallerIsExposed() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\","
                + "\"name\":\"web_search\",\"input\":{},"
                + "\"caller\":{\"type\":\"code_execution_20250825\",\"tool_id\":\"srvtoolu_0\"}}}");

        ChatEvent event = firstOf(ChatEventType.SERVER_TOOL_START);
        assertNotNull(event);
        assertEquals("code_execution_20250825", event.attrAs("caller"));
        assertEquals("srvtoolu_0", event.attrAs("callerToolId"));
    }

    /**
     * 非流式与流式对称：同一块结构在 call() 下也要透出 caller
     */
    @Test
    public void serverToolCallerIsExposedInNonStream() {
        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");
        ChatRequest req = new ChatRequest(config, AnthropicChatDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, false);
        events.clear();
        ChatStreamContext ctx = new ChatStreamContextDefault(config, req,
                new ChatAccumulator(req, false), new ChatStreamSession(), 0, events::add);

        parser.parseNonStreamResponse(ctx, "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4-5\",\"stop_reason\":\"end_turn\",\"content\":["
                + "{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_2\",\"name\":\"web_search\","
                + "\"input\":{},\"caller\":{\"type\":\"direct\"}}]}");

        ChatEvent event = firstOf(ChatEventType.SERVER_TOOL_START);
        assertNotNull(event);
        assertEquals("direct", event.attrAs("caller"));
        assertNull(event.attrAs("callerToolId"), "direct 变体没有 tool_id");
    }

    /**
     * 缺省 caller（早期响应/网关裁剪）不应产生空属性
     */
    @Test
    public void missingCallerAddsNoAttr() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_3\","
                + "\"name\":\"web_search\",\"input\":{}}}");

        ChatEvent event = firstOf(ChatEventType.SERVER_TOOL_START);
        assertNotNull(event);
        assertNull(event.attrAs("caller"));
    }
}
