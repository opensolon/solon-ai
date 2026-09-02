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
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    /**
     * 终态：getMessage() == 完整聚合（跨分片），且构造期算定、多次取值恒等
     */
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
    public void frameKeepsLastChunkSemantic() {
        ChatAccumulator acc = newStreamAcc();

        appendChoice(acc, "你好", "");
        ChatResponse frame1 = acc.snapshotFrame();
        appendChoice(acc, "，世界", "");
        ChatResponse frame2 = acc.snapshotFrame();

        assertEquals("你好", frame1.getMessage().getContent(), "分片帧应为当帧分片");
        assertEquals("，世界", frame2.getMessage().getContent());

        //帧之间互不污染（旧实现是同一个被 reset 复用的可变实例）
        assertEquals("你好", frame1.getMessage().getContent());
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
        acc2.addContentItem(new AssistantMessage("hi"));
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
        acc.addContentItem(new AssistantMessage(null, null, false, null, null,
                        Collections.singletonList(call), null));

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
        acc.addContentItem(new AssistantMessage("首条"));
        acc.addContentItem(new AssistantMessage("次条"));

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
        acc.addContentItem(new AssistantMessage("", "思考中", true));
        acc.addContentItem(new AssistantMessage(null, null, false, null, null,
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
        acc.addContentItem(new AssistantMessage("分片A"));
        acc.addContentItem(new AssistantMessage("分片B"));

        assertEquals("分片A分片B", acc.snapshotTerminal().getText());
    }

    /**
     * 结果对象的集合视图只读：getToolCalls() / getBlocks() 不可修改
     */
    @Test
    public void collectionViewsAreReadOnly() {
        ChatAccumulator acc = newCallAcc();
        ToolCall call = new ToolCall("0", "call_1", "getWeather", null, null);
        acc.addContentItem(new AssistantMessage(null, null, false, null, null,
                        Collections.singletonList(call), null));

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
        boolean thinkingOnly = text.isEmpty() && !thinking.isEmpty();
        AssistantMessage msg = new AssistantMessage(text, thinking, thinkingOnly);

        acc.reset();
        acc.addContentItem(msg);
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
