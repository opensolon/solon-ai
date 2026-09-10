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
package features.ai.core;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.event.ChatStreamContextDefault;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 响应门面契约（4.1 第二阶段：纯结果化）
 *
 * <p>锁定三件事：</p>
 * <ol>
 *   <li><b>取值只有一个入口</b>：终态的 {@code getMessage()} 就是完整聚合，
 *       调用方不再需要在它与旧 {@code getAggregationMessage()} 之间做选择。</li>
 *   <li><b>分片帧语义不变</b>：中间帧仍给出当帧分片。</li>
 *   <li><b>结果与累积器隔离</b>：{@link ChatAccumulator} 是可变工作台（框架内部），
 *       {@link ChatResponseDefault} 是不可变结果；累积器继续变化不影响已发布结果。</li>
 * </ol>
 *
 * @author noear
 */
public class ChatResponseFacadeTest {
    @Test
    public void terminalMessageIsAggregation() {
        ChatAccumulator acc = newStreamAcc();

        //模拟两个分片：文本分两次到达
        appendChoice(acc, "你好", "");
        appendChoice(acc, "，世界", "");

        ChatResponse terminal = acc.snapshotTerminal();

        assertTrue(terminal.isTerminal());
        assertNotNull(terminal.getMessage());
        assertEquals("你好，世界", terminal.getMessage().getContent(), "终态应为完整聚合，不是最后一片");
        assertEquals("你好，世界", terminal.getText());

        //构造期算定，不重复构造
        assertSame(terminal.getMessage(), terminal.getMessage());
    }

    /**
     * 分片帧：保持旧语义（当帧分片）
     */
    @Test
    public void frameUsesChatEventDeltaSemantic() {
        ChatEvent frame1 = ChatEventDefault.of(ChatEventType.TEXT_DELTA).text("你好").build();
        ChatEvent frame2 = ChatEventDefault.of(ChatEventType.TEXT_DELTA).text("，世界").build();

        assertEquals("你好", frame1.getTextOrEmpty(), "流式分片类型与负载应从 ChatEvent 读取");
        assertEquals("，世界", frame2.getTextOrEmpty());
        assertTrue(frame1.is(ChatEventType.TEXT_DELTA));

        //事件不可变，帧之间互不污染
        assertEquals("你好", frame1.getTextOrEmpty());
    }

    /**
     * 思考聚合同样按终态给出
     */
    @Test
    public void terminalAggregatesThinking() {
        ChatAccumulator acc = newStreamAcc();

        appendChoice(acc, "", "先想一步");
        appendChoice(acc, "", "再想一步");

        assertEquals("先想一步再想一步", acc.snapshotTerminal().getThinking());
    }

    /**
     * getFinishReason()：归一化；完成原因是响应级属性，唯一来源是累积器的 lastFinishReason
     */
    @Test
    public void finishReasonIsNormalized() {
        //方言记录的原始值 → 归一化（终态构造期算定）
        assertEquals("tool", terminalOf("tool_calls").getFinishReason());
        assertEquals("stop", terminalOf("end_turn").getFinishReason());
        //非工具/结束类原样透传
        assertEquals("length", terminalOf("length").getFinishReason());

        //内容项不再携带完成原因：只有内容项、未记录 lastFinishReason 时，结果是默认终态
        ChatAccumulator acc2 = newStreamAcc();
        acc2.mergeTerminalMessage(new AssistantMessage("hi"));
        assertEquals("stop", acc2.snapshotTerminal().getFinishReason());

        //完全无信号时给出默认值
        assertEquals("stop", newStreamAcc().snapshotTerminal().getFinishReason());
    }

    /**
     * getToolCalls()：无工具调用时为空集合而非 null
     */
    @Test
    public void toolCallsNeverNull() {
        ChatAccumulator acc = newStreamAcc();
        appendChoice(acc, "hi", "");
        assertEquals(Collections.emptyList(), acc.snapshotTerminal().getToolCalls());

        ToolCall call = new ToolCall("0", "call_1", "getWeather", null, null);
        acc.mergeTerminalMessage(new AssistantMessage(null, null, Collections.singletonList(call), null));

        List<ToolCall> calls = acc.snapshotTerminal().getToolCalls();
        assertEquals(1, calls.size());
        assertEquals("getWeather", calls.get(0).getName());
    }

    /**
     * 非流式：一次响应只有一个结果。方言把它拆成多条内容项时取<b>末条</b>：
     * 工具调用总在最后一项上，取首条会丢掉 toolCalls；这也与 3.x 的 getAggregationMessage
     * 及 ChatRequestDescDefault 写入记忆时的取值保持同一来源
     */
    @Test
    public void nonStreamTakesLastContentItem() {
        ChatAccumulator acc = newCallAcc();
        acc.mergeTerminalMessage(new AssistantMessage("首条"));
        acc.mergeTerminalMessage(new AssistantMessage("次条"));
        ChatResponse terminal = acc.snapshotTerminal();

        assertEquals("次条", terminal.getMessage().getContent());
        assertEquals("次条", terminal.getText());
        assertEquals("stop", terminal.getFinishReason());
    }

    /**
     * 非流式拆成「思考项 + 工具调用项」时，终态必须仍能拿到 toolCalls
     * （取末条的直接后果，也是工具循环能不能进下一轮的前提）
     */
    @Test
    public void nonStreamKeepsToolCallsOnLastItem() {
        ChatAccumulator acc = newCallAcc();
        acc.lastFinishReason = "tool_calls";
        acc.mergeTerminalMessage(new AssistantMessage("", "思考中"));
        acc.mergeTerminalMessage(new AssistantMessage(null, null,
                Collections.singletonList(new ToolCall("0", "call_1", "getWeather", null, null)), null));

        ChatResponse terminal = acc.snapshotTerminal();

        assertEquals(1, terminal.getToolCalls().size());
        assertEquals("getWeather", terminal.getToolCalls().get(0).getName());
        assertEquals("tool", terminal.getFinishReason());
    }

    /**
     * 流式终态仍走聚合通道（不受内容项条数影响）：文本为 textBuilder 的完整累积，
     * 而不是任何单条内容项
     */
    @Test
    public void streamTerminalUsesAggregation() {
        ChatAccumulator acc = newStreamAcc();
        acc.appendText("分片A");
        acc.appendText("分片B");
        acc.mergeTerminalMessage(new AssistantMessage("分片A"));
        acc.mergeTerminalMessage(new AssistantMessage("分片B"));

        assertEquals("分片A分片B", acc.snapshotTerminal().getText());
        assertNull(acc.snapshotTerminal().getMessage().getContentRaw(),
                "普通正文不再生成已弃用 contentRaw 镜像");
    }

    @Test
    public void terminalProtocolStateAloneProducesNonEmptyMessage() {
        ChatAccumulator acc = newStreamAcc();
        acc.putTerminalProtocolState("vendor.protocol",
                new MessageProtocolState(1, Collections.<String, Object>singletonMap("cursor", "next")));

        ChatResponse terminal = acc.snapshotTerminal();

        assertNotNull(terminal.getMessage());
        assertFalse(terminal.isEmpty(), "协议续跑状态本身就是有效终态载荷");
        MessageProtocolState state = terminal.getMessage().getProtocolState("vendor.protocol");
        assertNotNull(state);
        assertNotNull(state.getSemanticHash());
    }

    @Test
    public void streamProtocolStatesSurviveAndBindFinalSemanticHash() {
        ChatAccumulator acc = newStreamAcc();
        AssistantMessage carrier = AssistantMessage.snapshot("", "", null, null, null, null,
                Collections.singletonMap("anthropic.messages", new MessageProtocolState(1,
                        Collections.<String, Object>singletonMap("thinkingSignature", "sig_1"))));
        acc.mergeTerminalMessage(carrier);
        acc.appendText("final text");

        AssistantMessage terminal = acc.snapshotTerminal().getMessage();
        assertNotNull(terminal.getProtocolState("anthropic.messages"));
        assertNotNull(terminal.getProtocolState("anthropic.messages").getSemanticHash());
        assertEquals("final text", terminal.getText());
    }

    @Test
    public void streamProtocolContentRawSurvivesTextFragments() {
        ChatAccumulator acc = newStreamAcc();
        AssistantMessage legacyCarrier = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"text\":\"\"," +
                        "\"contentRaw\":{\"type\":\"protocol-carrier\"}}");
        acc.mergeTerminalMessage(legacyCarrier);
        appendChoice(acc, "分片A", "");
        appendChoice(acc, "分片B", "");

        AssistantMessage terminal = acc.snapshotTerminal().getMessage();
        assertEquals("分片A分片B", terminal.getText());
        assertEquals("protocol-carrier", ((Map<?, ?>) terminal.getContentRaw()).get("type"),
                "协议型 raw 必须保留");
    }

    @Test
    public void streamTerminalCarrierSurvivesContentItemsClear() {
        ChatAccumulator acc = newStreamAcc();
        AssistantMessage carrier = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"text\":\"\"," +
                        "\"contentRaw\":{\"type\":\"output_text\"}," +
                        "\"toolCallsRaw\":[{\"id\":\"call_raw\"}]," +
                        "\"toolCalls\":[{\"index\":\"0\",\"id\":\"call_1\",\"name\":\"search\"}]," +
                        "\"searchResultsRaw\":[{\"query\":\"solon\"}]," +
                        "\"reasoningFieldName\":\"reasoning_content\"," +
                        "\"metadata\":{\"reasoning_item_id\":\"rs_1\"}}");

        acc.mergeTerminalMessage(carrier);
        acc.mergeTerminalMessage(new AssistantMessage("", "", null,
                Collections.singletonList(ImageBlock.ofUrl("https://example.com/carrier.png"))));
        acc.mergeTerminalMessage(new AssistantMessage("", ""));
        AssistantMessage terminal = acc.snapshotTerminal().getMessage();
        assertNotNull(terminal);
        assertEquals("output_text", ((Map<?, ?>) terminal.getContentRaw()).get("type"));
        assertEquals("call_1", terminal.getToolCalls().get(0).getId());
        assertEquals("call_raw", terminal.getToolCallsRaw().get(0).get("id"));
        assertEquals("solon", terminal.getSearchResultsRaw().get(0).get("query"));
        assertEquals("reasoning_content", terminal.getReasoningFieldName());
        assertEquals("rs_1", terminal.getMetadataAs("reasoning_item_id"));
        assertTrue(terminal.hasMedia());
    }

    @Test
    public void pureThinkingTerminalIsNotEmpty() {
        ChatAccumulator acc = newStreamAcc();
        acc.appendThinking("只思考，不输出正文");

        ChatResponse terminal = acc.snapshotTerminal();
        assertNotNull(terminal.getMessage());
        assertFalse(terminal.isEmpty());
        assertFalse(terminal.hasContent());
        assertEquals("", terminal.getContent());
        assertEquals("只思考，不输出正文", terminal.getThinking());
        assertTrue(terminal.getMessage().isThinkingOnly());
    }

    @Test
    public void frameSnapshotNeverContainsFinalMessage() {
        ChatAccumulator acc = newStreamAcc();
        acc.appendText("partial");
        acc.addMediaBlocks(Collections.singletonList(ImageBlock.ofUrl("https://example.com/frame.png")));

        ChatResponse frame = acc.snapshotFrame();
        assertFalse(frame.isTerminal());
        assertTrue(frame.isEmpty());
        assertEquals(null, frame.getMessage());
    }

    @Test
    public void streamToolFinalizationMergePreservesProtocolCarrier() {
        ChatAccumulator acc = newStreamAcc();
        AssistantMessage legacyCarrier = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"text\":\"\"," +
                        "\"contentRaw\":{\"type\":\"reasoning\"}," +
                        "\"metadata\":{\"reasoning_item_id\":\"rs_1\"}}");
        acc.mergeTerminalMessage(legacyCarrier);
        acc.mergeTerminalMessage(new AssistantMessage("", "", null,
                Collections.singletonList(ImageBlock.ofUrl("https://example.com/tool-carrier.png"))));

        ToolCall call = new ToolCall("0", "call_1", "search", "{}", Collections.emptyMap());
        acc.mergeTerminalMessage(new AssistantMessage("", "", Collections.singletonList(call), null)
                .addMetadata("tool_meta", "kept"));

        AssistantMessage terminal = acc.snapshotTerminal().getMessage();
        assertEquals("reasoning", ((Map<?, ?>) terminal.getContentRaw()).get("type"));
        assertTrue(terminal.hasMedia());
        assertEquals("rs_1", terminal.getMetadataAs("reasoning_item_id"));
        assertEquals("kept", terminal.getMetadataAs("tool_meta"));
        assertEquals("call_1", terminal.getToolCalls().get(0).getId());
    }

    /**
     * 结果对象的集合视图只读：getToolCalls() / getBlocks() 不可修改
     */
    @Test
    public void collectionViewsAreReadOnly() {
        ChatAccumulator acc = newCallAcc();
        ToolCall call = new ToolCall("0", "call_1", "getWeather", null, null);
        acc.mergeTerminalMessage(new AssistantMessage(null, null, Collections.singletonList(call), null));

        ChatResponse terminal = acc.snapshotTerminal();

        assertThrows(UnsupportedOperationException.class, () -> terminal.getToolCalls().add(null));
        assertThrows(UnsupportedOperationException.class, () -> terminal.getBlocks().add(null));
    }

    /**
     * 已发布结果与累积器隔离：累积器继续变化不影响结果（不可变由类型保证——无任何写入方法）
     */
    @Test
    public void publishedResultIsIsolatedFromAccumulator() {
        ChatAccumulator acc = newStreamAcc();
        appendChoice(acc, "hi", "");
        ChatResponse terminal = acc.snapshotTerminal();

        appendChoice(acc, "后续内容", "");
        assertEquals("hi", terminal.getMessage().getContent());
    }

    @Test
    public void nonStreamEventFactsSurviveTerminalSnapshot() {
        ChatAccumulator acc = newCallAcc();
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(acc);
        ImageBlock image = ImageBlock.ofUrl("https://example.com/event.png");

        ctx.emit(ctx.event(ChatEventType.TEXT_DELTA).text("event text").build());
        ctx.emit(ctx.event(ChatEventType.THINKING_DELTA).text("event thinking").build());
        ctx.emit(ctx.event(ChatEventType.MEDIA_DONE).block(image).build());

        ChatResponse terminal = acc.snapshotTerminal();
        assertEquals("event text", terminal.getText());
        assertEquals("event thinking", terminal.getThinking());
        assertEquals(1, terminal.getBlocks().stream().filter(b -> !(b instanceof org.noear.solon.ai.chat.content.TextBlock)).count());
    }

    @Test
    public void eventFactsTakePriorityAndCarrierIsFallback() {
        ChatAccumulator acc = newStreamAcc();
        acc.mergeTerminalMessage(new AssistantMessage("carrier text", "carrier thinking"));
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(acc);
        ctx.emit(ctx.event(ChatEventType.TEXT_DELTA).text("event text").build());
        ctx.emit(ctx.event(ChatEventType.THINKING_DELTA).text("event thinking").build());

        ChatResponse terminal = acc.snapshotTerminal();
        assertEquals("event text", terminal.getText());
        assertEquals("event thinking", terminal.getThinking());

        ChatAccumulator fallback = newStreamAcc();
        fallback.mergeTerminalMessage(new AssistantMessage("carrier only", "thinking only"));
        assertEquals("carrier only", fallback.snapshotTerminal().getText());
        assertEquals("thinking only", fallback.snapshotTerminal().getThinking());
    }

    @Test
    public void nonStreamCarrierMediaIsFallbackWhenNoMediaEvent() {
        ChatAccumulator acc = newCallAcc();
        acc.setTerminalMessage(new AssistantMessage("", "", null,
                Collections.singletonList(ImageBlock.ofUrl("https://example.com/carrier.png"))));

        ChatResponse terminal = acc.snapshotTerminal();
        assertNotNull(terminal.getMessage());
        assertEquals(1, terminal.getBlocks().size());
        assertEquals("https://example.com/carrier.png", terminal.getBlocks().get(0).getContent());
    }

    @Test
    public void nonStreamCarrierDoesNotDuplicateTextBlock() {
        ChatAccumulator acc = newCallAcc();
        acc.setTerminalMessage(new AssistantMessage("hello", "", null,
                java.util.Arrays.asList(
                        org.noear.solon.ai.chat.content.TextBlock.of("hello"),
                        ImageBlock.ofUrl("https://example.com/carrier.png"))));

        ChatResponse terminal = acc.snapshotTerminal();
        assertEquals(2, terminal.getBlocks().size());
        assertEquals(1, terminal.getBlocks().stream()
                .filter(b -> b instanceof org.noear.solon.ai.chat.content.TextBlock).count());
    }

    /// //////////////////////////

    private static ChatAccumulator newStreamAcc() {
        return newAcc(true);
    }

    private static ChatAccumulator newCallAcc() {
        return newAcc(false);
    }

    private static ChatAccumulator newAcc(boolean stream) {
        ChatRequest req = new ChatRequest(
                new ChatConfig(),
                new NoopDialect(),
                ChatOptions.of(),
                InMemoryChatSession.builder().build(),
                null,
                null,
                stream);
        return new ChatAccumulator(req, stream);
    }

    private static ChatResponse terminalOf(String lastFinishReason) {
        ChatAccumulator acc = newStreamAcc();
        appendChoice(acc, "hi", "");
        acc.lastFinishReason = lastFinishReason;
        return acc.snapshotTerminal();
    }

    /**
     * 模拟一个分片到达：与 {@code publishItem} 的聚合方式一致（分片入 choice + 计入聚合缓冲）
     */
    private static void appendChoice(ChatAccumulator acc, String text, String thinking) {
        AssistantMessage msg = new AssistantMessage(text, thinking);

        acc.reset();
        acc.mergeTerminalMessage(msg);
        acc.appendText(text);
        acc.appendThinking(thinking);
    }

    static class NoopDialect extends AbstractChatDialect {
        @Override
        public boolean matched(ChatConfig config) {
            return true;
        }

        @Override
        public void parseResponseJson(ChatStreamContext ctx, String data) {
            //测试方言不解析响应
        }
    }
}
