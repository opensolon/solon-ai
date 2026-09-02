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
package features.ai.core.event;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.event.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.noear.solon.ai.chat.event.ChatEventType.*;

/**
 * 归一化器不变量
 *
 * <p>这些不变量是订阅方唯一可依赖的边界契约：方言只做翻译，边界由核心统一保证。</p>
 *
 * @author noear
 */
public class ChatEventNormalizerTest {
    private final List<ChatEvent> out = new ArrayList<>();
    private final ChatEventNormalizer normalizer = new ChatEventNormalizer();
    private final ChatEventEmitter sink = out::add;

    private void feed(ChatEventType type) {
        feed(type, null);
    }

    private void feed(ChatEventType type, String text) {
        normalizer.apply(ChatEventDefault.of(type).text(text).build(), sink);
    }

    private List<ChatEventType> types() {
        List<ChatEventType> list = new ArrayList<>();
        for (ChatEvent e : out) {
            list.add(e.getType());
        }
        return list;
    }

    /**
     * 裸 DELTA 自动补 START；流结束自动补 END
     */
    @Test
    public void bareDeltaGetsWrapped() {
        feed(TEXT_DELTA, "a");
        feed(TEXT_DELTA, "b");
        normalizer.complete(sink);

        assertEquals(java.util.Arrays.asList(TEXT_START, TEXT_DELTA, TEXT_DELTA, TEXT_END), types());
        assertEquals("a", out.get(1).getText());
        assertEquals("b", out.get(2).getText());
    }

    /**
     * 思考与正文交替：切换时自动关闭前一个内容块
     */
    @Test
    public void alternatingContentClosesPrevious() {
        feed(THINKING_DELTA, "think");
        feed(TEXT_DELTA, "answer");
        normalizer.complete(sink);

        assertEquals(java.util.Arrays.asList(
                THINKING_START, THINKING_DELTA, THINKING_END,
                TEXT_START, TEXT_DELTA, TEXT_END), types());
    }


    /**
     * 重复 START 被丢弃（方言可能对同一块多次声明开始）
     */
    @Test
    public void duplicateStartIsDropped() {
        feed(TEXT_START);
        feed(TEXT_START);
        feed(TEXT_DELTA, "x");
        normalizer.complete(sink);

        assertEquals(java.util.Arrays.asList(TEXT_START, TEXT_DELTA, TEXT_END), types());
    }

    /**
     * 无配对的 END 被丢弃（避免订阅方收到不成对的边界）
     */
    @Test
    public void unpairedEndIsDropped() {
        feed(TEXT_END);
        assertTrue(out.isEmpty());

        feed(THINKING_DELTA, "t");
        feed(TEXT_END);

        assertEquals(java.util.Arrays.asList(THINKING_START, THINKING_DELTA), types());
    }

    /**
     * STEP_END 前自动补齐未闭合内容块（顺序：先补 END，再发 STEP_END）
     */
    @Test
    public void stepEndClosesOpenBlock() {
        feed(TEXT_DELTA, "a");
        feed(STEP_END);

        assertEquals(java.util.Arrays.asList(TEXT_START, TEXT_DELTA, TEXT_END, STEP_END), types());
    }

    /**
     * RESPONSE_END 前自动补齐（对应旧实现中「整轮只吐 reasoning 时补一帧 &lt;/think&gt;」的兜底）
     */
    @Test
    public void responseEndClosesOpenThinking() {
        feed(THINKING_DELTA, "only reasoning");
        feed(RESPONSE_END);

        assertEquals(java.util.Arrays.asList(
                THINKING_START, THINKING_DELTA, THINKING_END, RESPONSE_END), types());
    }

    /**
     * 非边界事件透传，且不影响已打开的内容块
     */
    @Test
    public void nonBoundaryEventsPassThrough() {
        feed(TEXT_DELTA, "a");
        feed(USAGE);
        feed(HEARTBEAT);
        feed(SERVER_TOOL_RESULT);
        feed(TEXT_DELTA, "b");
        normalizer.complete(sink);

        assertEquals(java.util.Arrays.asList(
                TEXT_START, TEXT_DELTA, USAGE, HEARTBEAT, SERVER_TOOL_RESULT, TEXT_DELTA, TEXT_END), types());
    }

    /**
     * THINKING_SIGNATURE 属 NONE 阶段：透传且不打断思考块
     */
    @Test
    public void signatureDoesNotBreakThinkingBlock() {
        feed(THINKING_DELTA, "t");
        feed(THINKING_SIGNATURE, "sig");
        feed(THINKING_DELTA, "t2");
        normalizer.complete(sink);

        assertEquals(java.util.Arrays.asList(
                THINKING_START, THINKING_DELTA, THINKING_SIGNATURE, THINKING_DELTA, THINKING_END), types());
        assertEquals("sig", out.get(2).getText());
    }

    /**
     * 显式 START/DELTA/END 完整序列原样保留，不重复补齐
     */
    @Test
    public void explicitTripleIsPreserved() {
        feed(TEXT_START);
        feed(TEXT_DELTA, "a");
        feed(TEXT_END);
        normalizer.complete(sink);

        assertEquals(java.util.Arrays.asList(TEXT_START, TEXT_DELTA, TEXT_END), types());
        assertFalse(normalizer.hasOpen());
    }

    /**
     * complete 幂等（重复收尾不产生多余 END）
     */
    @Test
    public void completeIsIdempotent() {
        feed(TEXT_DELTA, "a");
        normalizer.complete(sink);
        normalizer.complete(sink);

        assertEquals(java.util.Arrays.asList(TEXT_START, TEXT_DELTA, TEXT_END), types());
    }

    @Test
    public void multipleSameGroupBlocksAreTrackedIndependently() {
        normalizer.apply(ChatEventDefault.of(TEXT_DELTA).itemId("a").index(0).text("a1").build(), sink);
        normalizer.apply(ChatEventDefault.of(TEXT_DELTA).itemId("b").index(1).text("b1").build(), sink);
        normalizer.apply(ChatEventDefault.of(TEXT_END).itemId("a").index(0).build(), sink);
        normalizer.complete(sink);

        assertEquals(java.util.Arrays.asList(
                TEXT_START, TEXT_DELTA, TEXT_START, TEXT_DELTA, TEXT_END, TEXT_END), types());
        assertEquals("a", out.get(0).getItemId());
        assertEquals("b", out.get(2).getItemId());
        assertEquals("a", out.get(4).getItemId());
        assertEquals("b", out.get(5).getItemId());
        assertFalse(normalizer.hasOpen());
    }

    /**
     * 全流边界配平：任意事件序列下 START 与 END 数量必须相等
     */
    @Test
    public void boundariesAlwaysBalanced() {
        ChatEventType[] script = {
                TEXT_DELTA, THINKING_DELTA, TEXT_END, THINKING_START,
                THINKING_DELTA, USAGE, TEXT_DELTA, STEP_END, TEXT_DELTA
        };

        for (ChatEventType t : script) {
            feed(t, "x");
        }
        normalizer.complete(sink);

        int textStart = 0, textEnd = 0, thinkStart = 0, thinkEnd = 0;
        for (ChatEvent e : out) {
            if (e.getType() == TEXT_START) textStart++;
            if (e.getType() == TEXT_END) textEnd++;
            if (e.getType() == THINKING_START) thinkStart++;
            if (e.getType() == THINKING_END) thinkEnd++;
        }

        assertEquals(textStart, textEnd, "TEXT boundaries unbalanced");
        assertEquals(thinkStart, thinkEnd, "THINKING boundaries unbalanced");
        assertFalse(normalizer.hasOpen());
    }

    /**
     * 流收尾补齐的事件仍须携带 responseId / step
     *
     * <p>{@code complete()} 路径没有参照事件，若不回落到最近见过的标识，
     * 补出的 TEXT_END 就会是一个 responseId 为 null 的事件，破坏「responseId 全流一致」。</p>
     */
    @Test
    public void completedBoundaryKeepsIdentity() {
        normalizer.apply(ChatEventDefault.of(TEXT_DELTA)
                .text("a")
                .responseId("resp-1")
                .step(3)
                .build(), sink);

        normalizer.complete(sink);

        assertEquals(java.util.Arrays.asList(TEXT_START, TEXT_DELTA, TEXT_END), types());

        for (ChatEvent e : out) {
            assertEquals("resp-1", e.getResponseId(), "responseId 应全流一致: " + e.getType());
            assertEquals(3, e.getStep(), "step 应全流一致: " + e.getType());
        }
    }

    /// //////////////////////////
    // TOOL_CALL 组：宽松补齐（只补不删，与 TEXT/THINKING 的严格配对不同）

    /**
     * 裸 ARGS_DELTA 自动补 START；流结束补 END（第三方方言只发增量时的安全网）
     */
    @Test
    public void bareToolArgsDeltaGetsWrapped() {
        normalizer.apply(ChatEventDefault.of(TOOL_CALL_ARGS_DELTA).toolCallId("call-1").text("{\"a\"").build(), sink);
        normalizer.complete(sink);

        assertEquals(java.util.Arrays.asList(TOOL_CALL_START, TOOL_CALL_ARGS_DELTA, TOOL_CALL_END), types());
    }

    /**
     * 并行工具调用的多个 START 全部保留（绝不去重）
     */
    @Test
    public void parallelToolCallStartsAreKept() {
        normalizer.apply(ChatEventDefault.of(TOOL_CALL_START).toolCallId("call-1").build(), sink);
        normalizer.apply(ChatEventDefault.of(TOOL_CALL_START).toolCallId("call-2").build(), sink);
        normalizer.apply(ChatEventDefault.of(TOOL_CALL_END).toolCallId("call-1").build(), sink);
        normalizer.apply(ChatEventDefault.of(TOOL_CALL_END).toolCallId("call-2").build(), sink);
        normalizer.complete(sink);

        assertEquals(java.util.Arrays.asList(
                TOOL_CALL_START, TOOL_CALL_START, TOOL_CALL_END, TOOL_CALL_END), types());
    }

    /**
     * 无配对的 END 不丢弃（分片协议只在末片给出 id，误删会吞掉完成信号）
     */
    @Test
    public void unpairedToolCallEndIsKept() {
        normalizer.apply(ChatEventDefault.of(TOOL_CALL_END).toolCallId("call-x").build(), sink);

        assertEquals(java.util.Arrays.asList(TOOL_CALL_END), types());
    }

    /**
     * 分片末片才给出 id 时，END 回退关闭最早开启的那个（不产生多余 END）
     */
    @Test
    public void shardedEndFallsBackToEarliest() {
        normalizer.apply(ChatEventDefault.of(TOOL_CALL_START).toolCallId("call-1").build(), sink);
        //后续分片不带 id（匿名），END 也对不上
        normalizer.apply(ChatEventDefault.of(TOOL_CALL_ARGS_DELTA).text("}").build(), sink);
        normalizer.apply(ChatEventDefault.of(TOOL_CALL_END).toolCallId("call-unknown").build(), sink);
        normalizer.complete(sink);

        //END 已回退消费 call-1，complete 不再补 END
        assertEquals(java.util.Arrays.asList(
                TOOL_CALL_START, TOOL_CALL_ARGS_DELTA, TOOL_CALL_END), types());
    }

    /**
     * RESPONSE_END 前补齐未闭合的工具调用，并携带标识
     */
    @Test
    public void responseEndClosesOpenToolCall() {
        normalizer.apply(ChatEventDefault.of(TOOL_CALL_START).toolCallId("call-1").build(), sink);
        normalizer.apply(ChatEventDefault.of(RESPONSE_END).responseId("r1").build(), sink);

        assertEquals(java.util.Arrays.asList(TOOL_CALL_START, TOOL_CALL_END, RESPONSE_END), types());
        assertEquals("call-1", out.get(1).getToolCallId());
    }

    /**
     * 正文与工具调用交替：TOOL_CALL_START 前自动关闭未闭合的文本块
     */
    @Test
    public void toolCallStartClosesOpenText() {
        feed(TEXT_DELTA, "a");
        normalizer.apply(ChatEventDefault.of(TOOL_CALL_START).toolCallId("call-1").build(), sink);
        normalizer.complete(sink);

        assertEquals(java.util.Arrays.asList(
                TEXT_START, TEXT_DELTA, TEXT_END, TOOL_CALL_START, TOOL_CALL_END), types());
    }

    /**
     * TOOL_CALL_CHUNK 同样展开为 START + ARGS_DELTA + END
     */
    @Test
    public void toolCallChunkIsExpanded() {
        normalizer.apply(ChatEventDefault.of(TOOL_CALL_CHUNK).toolCallId("call-1").text("{}").build(), sink);

        assertEquals(java.util.Arrays.asList(TOOL_CALL_START, TOOL_CALL_ARGS_DELTA, TOOL_CALL_END), types());
        assertEquals("{}", out.get(1).getText());
    }

    /**
     * TOOL_RESULT 不是边界事件：原样透传，不参与配对
     */
    @Test
    public void toolResultPassesThrough() {
        normalizer.apply(ChatEventDefault.of(TOOL_RESULT).toolCallId("call-1").text("ok").build(), sink);

        assertEquals(java.util.Arrays.asList(TOOL_RESULT), types());
    }
}
