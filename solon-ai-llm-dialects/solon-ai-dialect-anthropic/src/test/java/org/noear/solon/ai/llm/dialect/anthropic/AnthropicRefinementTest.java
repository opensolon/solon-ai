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
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anthropic 方言优化项的回归锁定。
 *
 * <p>对应 2026-09 审查后落地的改动：流式工具参数延迟解析、模型代次正则常量化、
 * 合成 assistant 节点回写正文 text 块、usage 显式 0 覆盖、max_completion_tokens
 * 与 thinking 预算钳制的联动、container 回填三形态、非流式无 content 空终态、
 * stop 的 String[] 形态。</p>
 *
 * <p>已有覆盖（不重复）：parallel_tool_calls 三边界（RoundTripAlignTest）、
 * 非法 ttl 拦截（ProtocolAlignTest）、file_id 误判防线（RoundTripAlignTest）、
 * text/plain 损坏 base64 fail-fast（RoundTripAlignTest）。</p>
 *
 * @author noear
 * @since 4.1
 */
public class AnthropicRefinementTest {
    private final AnthropicResponseParser parser = new AnthropicResponseParser();
    private final AnthropicRequestBuilder requestBuilder = new AnthropicRequestBuilder();

    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx(boolean stream) {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");
        ChatRequest req = new ChatRequest(config, AnthropicChatDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, stream),
                new ChatStreamSession(), 0, events::add);
    }

    private ONode build(ChatOptions options, List<ChatMessage> messages) {
        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");
        return requestBuilder.build(config, options, messages, false);
    }

    /// ///////////////// 一、流式工具参数：分片只累计，block_stop 才落定 input

    /**
     * 大量 input_json_delta 分片下，中间帧不再对累计串做 JSON 解析尝试（原 O(n²) 浪费），
     * 最终 input 仍由 content_block_stop 统一解析写入。
     */
    @Test
    public void toolInputSettlesOnlyAtBlockStop() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"write\",\"input\":{}}}");
        // 五个分片，累计串在中途必然不是合法 JSON（如 {"a":"x"  ）
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"a\\\":\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"x\\\"\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\",\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"b\\\":2}\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_stop\"}");

        AssistantMessage message = acc.snapshotTerminal().getMessage();
        Map<String, Object> stateData = AnthropicMessageStateSupport.resolveData(message);
        assertNotNull(stateData);
        List<?> rawBlocks = (List<?>) stateData.get(AnthropicResponseParser.CONTENT_BLOCKS_RAW_KEY);
        assertNotNull(rawBlocks, "有序块载体必须存在");
        ONode toolBlock = ONode.ofJson((String) rawBlocks.get(0));
        assertEquals("x", toolBlock.get("input").get("a").getString());
        assertEquals(2, toolBlock.get("input").get("b").getInt());
    }

    /**
     * 缺 content_block_stop 的截断流：终态收口时对残留工具状态兑底解析，
     * 回放载体里的 input 不能停留在 start 的空对象。
     */
    @Test
    public void truncatedStreamToolInputSettlesAtTerminal() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"write\",\"input\":{}}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}");
        // 无 content_block_stop，直接终态
        parser.parseStreamResponse(ctx, "{\"type\":\"message_stop\"}");

        AssistantMessage message = acc.snapshotTerminal().getMessage();
        Map<String, Object> stateData = AnthropicMessageStateSupport.resolveData(message);
        assertNotNull(stateData);
        List<?> rawBlocks = (List<?>) stateData.get(AnthropicResponseParser.CONTENT_BLOCKS_RAW_KEY);
        assertNotNull(rawBlocks);
        ONode toolBlock = ONode.ofJson((String) rawBlocks.get(0));
        assertEquals("a.txt", toolBlock.get("input").get("path").getString(),
                "截断流的参数由终态兑底解析，不退化为空对象");
    }

    /// ///////////////// 二、合成 assistant 节点回写正文

    /**
     * buildAssistantToolCallMessageNode(acc, builders) 在无回放载体（无 content_block_start 流，
     * 如核心经事件通道聚合）时，也要把本轮正文写进合成节点的 text 块——
     * 否则「先行文后调工具」轮次的多轮上下文会丢掉模型自述。
     */
    @Test
    public void syntheticToolNodeCarriesAggregatedText() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        // 只有事件通道聚合（textBuilder/toolCallBuilders 已有内容），不经 parser 的块留档
        acc.appendText("正在查询");
        acc.appendThinking("先想一下");
        acc.thinkingSignature = "sig_1";

        ToolCallBuilder builder = acc.getToolCallBuilders()
                .computeIfAbsent("idx:0", k -> new ToolCallBuilder());
        builder.idBuilder.append("toolu_9");
        builder.nameBuilder.append("search");
        builder.argumentsBuilder.append("{\"q\":\"solon\"}");

        ONode node = requestBuilder.buildAssistantToolCallMessageNode(acc, acc.getToolCallBuilders());
        ONode content = node.get("content");

        assertEquals("thinking", content.get(0).get("type").getString());
        assertEquals("text", content.get(1).get("type").getString());
        assertEquals("正在查询", content.get(1).get("text").getString());
        assertEquals("tool_use", content.get(2).get("type").getString());
        assertEquals("solon", content.get(2).get("input").get("q").getString());
    }

    /// ///////////////// 三、usage：显式 0 覆盖（官方 MessageDeltaUsage 累计快照语义）

    /**
     * message_delta 的 usage 是累计快照：显式 0 必须覆盖 message_start 的旧值，
     * 缺失字段沿用旧值。若用「值>0」判断存在性，服务端修正为 0 的场景会被旧值遮蔽。
     */
    @Test
    public void messageDeltaUsageExplicitZeroOverrides() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        parser.parseStreamResponse(ctx, "{\"type\":\"message_start\",\"message\":{\"model\":\"claude-sonnet-4-5\","
                + "\"id\":\"msg_1\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"service_tier\":\"standard\"}}}");
        AiUsage first = acc.getUsage();
        assertNotNull(first);
        assertEquals(10L, first.promptTokens());
        assertEquals(5L, first.completionTokens());
        assertEquals("standard", first.serviceTier());

        // output_tokens 显式 0：修正早期快照；service_tier 缺失：沿用
        parser.parseStreamResponse(ctx, "{\"type\":\"message_delta\",\"delta\":{},"
                + "\"usage\":{\"output_tokens\":0}}");
        AiUsage second = acc.getUsage();
        assertNotNull(second);
        assertEquals(0L, second.completionTokens(), "显式 0 必须覆盖旧值");
        assertEquals(10L, second.promptTokens(), "缺失字段沿用旧值");
        assertEquals("standard", second.serviceTier(), "message_start 独有字段不因 delta 缺失而丢失");
    }

    /// ///////////////// 四、max_completion_tokens 与 thinking 预算钳制联动

    /**
     * 仅用 OpenAI 风格别名 max_completion_tokens 时：并入 max_tokens 后，
     * thinking 预算的钳制基准必须是别名值（而非默认 32000）。
     */
    @Test
    public void maxCompletionTokensAliasFeedsThinkingClamp() {
        ONode root = build(ChatOptions.of()
                        .optionSet("max_completion_tokens", 8192)
                        .optionSet("thinking", true),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        assertEquals(8192, root.get("max_tokens").getInt());
        assertEquals("enabled", root.get("thinking").get("type").getString());
        assertEquals(8191, root.get("thinking").get("budget_tokens").getInt(),
                "默认预算 10000 超过 max_tokens 8192 时应压到 8191");
    }

    /**
     * 显式 max_tokens 优先于别名（两者同给时别名不生效）。
     */
    @Test
    public void explicitMaxTokensWinsOverAlias() {
        ONode root = build(ChatOptions.of()
                        .optionSet("max_tokens", 4096)
                        .optionSet("max_completion_tokens", 8192),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        assertEquals(4096, root.get("max_tokens").getInt());
        assertFalse(root.hasKey("max_completion_tokens"));
    }

    /// ///////////////// 五、container 回填三形态

    /**
     * 字符串形态 / 带 expires_at 的 Map 对象（只取 id）/ 历史多条取最近一条。
     */
    @Test
    public void containerBackfillCoversThreeShapes() {
        // 形态 1：Map 对象只取 id（expires_at 不透传，否则被请求侧 schema 拒）
        Map<String, Object> containerObj = new LinkedHashMap<>();
        containerObj.put("id", "cnt_1");
        containerObj.put("expires_at", "2026-09-12T00:00:00Z");
        Map<String, Object> data1 = new LinkedHashMap<>();
        data1.put(AnthropicResponseParser.CONTAINER_RAW_KEY, containerObj);
        AssistantMessage older = AssistantMessage.snapshot("旧一轮", "", null, null, null, null,
                Collections.singletonMap(AnthropicMessageStateSupport.PROTOCOL_ID,
                        new MessageProtocolState(AnthropicMessageStateSupport.VERSION, data1)));

        // 形态 2：纯字符串 id
        Map<String, Object> data2 = new LinkedHashMap<>();
        data2.put(AnthropicResponseParser.CONTAINER_RAW_KEY, "cnt_2");
        AssistantMessage newer = AssistantMessage.snapshot("最近一轮", "", null, null, null, null,
                Collections.singletonMap(AnthropicMessageStateSupport.PROTOCOL_ID,
                        new MessageProtocolState(AnthropicMessageStateSupport.VERSION, data2)));

        ONode root = build(ChatOptions.of(),
                Arrays.asList(ChatMessage.ofUser("跑代码"), older, newer));
        assertEquals("cnt_2", root.get("container").getString(), "历史多条时取最近一条");
        assertFalse(root.toJson().contains("expires_at"), "expires_at 不得透传");
    }

    /**
     * 容器形态为 JSON 字符串（contentRaw 序列化后的形态）时同样只取 id。
     */
    @Test
    public void containerBackfillFromJsonStringShape() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(AnthropicResponseParser.CONTAINER_RAW_KEY,
                "{\"id\":\"cnt_3\",\"expires_at\":\"2026-09-12T00:00:00Z\"}");
        AssistantMessage carrier = AssistantMessage.snapshot("上一轮", "", null, null, null, null,
                Collections.singletonMap(AnthropicMessageStateSupport.PROTOCOL_ID,
                        new MessageProtocolState(AnthropicMessageStateSupport.VERSION, data)));

        ONode root = build(ChatOptions.of(),
                Arrays.asList(ChatMessage.ofUser("跑代码"), carrier));
        assertEquals("cnt_3", root.get("container").getString());
        assertFalse(root.toJson().contains("expires_at"));
    }

    /// ///////////////// 六、非流式无 content：空终态契约

    /**
     * 非流式响应缺 content（如空回复 / 特定网关形态）时必须建立空终态消息并标记完成，
     * 而不是让调用方拿到未完成的聚合。
     */
    @Test
    public void nonStreamWithoutContentYieldsEmptyTerminal() {
        ChatStreamContext ctx = newCtx(false);

        boolean parsed = parser.parseNonStreamResponse(ctx,
                "{\"id\":\"msg_2\",\"model\":\"claude-sonnet-4-5\",\"stop_reason\":\"end_turn\","
                        + "\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}");

        assertTrue(parsed);
        ChatAccumulator acc = ctx.getAccumulator();
        assertTrue(acc.isFinished());
        AssistantMessage terminal = acc.snapshotTerminal().getMessage();
        assertNotNull(terminal);
        assertEquals("", terminal.getText());
        assertNotNull(acc.getUsage(), "usage 仍应正常解析");
        assertEquals(3L, acc.getUsage().promptTokens());
    }

    /// ///////////////// 七、stop 的 String[] 形态

    /**
     * writeStopSequences 三种入参（String / Collection / String[]）中最少被覆盖的 String[]：
     * 非空数组要转成协议的 stop_sequences 数组。
     */
    @Test
    public void stopSequencesFromArrayShape() {
        ONode root = build(ChatOptions.of().optionSet("stop", new String[]{"</done>", "END"}),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        assertTrue(root.get("stop_sequences").isArray());
        assertEquals("</done>", root.get("stop_sequences").get(0).getString());
        assertEquals("END", root.get("stop_sequences").get(1).getString());
        assertEquals(2, root.get("stop_sequences").size());
    }
}
