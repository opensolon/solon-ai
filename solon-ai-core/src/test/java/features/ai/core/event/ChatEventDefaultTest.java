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
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventGroup;
import org.noear.solon.ai.chat.event.ChatEventPhase;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.lang.Nullable;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件对象自身的契约：不可变、字段直取、谓词、兜底非空
 *
 * <p>这些是订阅方每个事件都会碰到的基础语义，集成测试只能覆盖到「碰巧出现的类型」，
 * 因此在单测里逐项锁定。</p>
 *
 * @author noear
 */
public class ChatEventDefaultTest {
    /**
     * 全字段透传，不做任何加工
     */
    @Test
    public void allFieldsPassThrough() {
        ONode raw = ONode.ofJson("{\"a\":1}");

        ChatEvent e = ChatEventDefault.of(ChatEventType.TOOL_CALL_ARGS_DELTA)
                .rawType("response.function_call_arguments.delta")
                .subType("web_search")
                .responseId("resp-1")
                .step(2)
                .itemId("item-9")
                .toolCallId("call-7")
                .index(3)
                .text("{\"city\":")
                .raw(raw)
                .attr("k", "v")
                .build();

        assertEquals(ChatEventType.TOOL_CALL_ARGS_DELTA, e.getType());
        assertEquals("response.function_call_arguments.delta", e.getRawType());
        assertEquals("web_search", e.getSubType());
        assertEquals("resp-1", e.getResponseId());
        assertEquals(2, e.getStep());
        assertEquals("item-9", e.getItemId());
        assertEquals("call-7", e.getToolCallId());
        assertEquals(3, e.getIndex());
        assertEquals("{\"city\":", e.getText());
        assertEquals(raw, e.getRaw());
        assertEquals("v", e.attrAs("k"));
    }

    /**
     * group / phase 由 type 静态绑定，实现不可自行漂移
     */
    @Test
    public void groupAndPhaseComeFromType() {
        for (ChatEventType type : ChatEventType.values()) {
            ChatEvent e = ChatEventDefault.of(type).build();

            assertEquals(type.getGroup(), e.getGroup(), "group 应取自 type: " + type);
            assertEquals(type.getPhase(), e.getPhase(), "phase 应取自 type: " + type);
            assertEquals(type.isDelta(), e.isDelta(), "isDelta 应与 type 一致: " + type);
            assertEquals(type.isTerminal(), e.isTerminal(), "isTerminal 应与 type 一致: " + type);
        }
    }

    /**
     * 未设置时的默认值：index 为 -1，raw 为空节点而非 null，attrs 为空表
     */
    @Test
    public void defaultsAreSafe() {
        ChatEvent e = ChatEventDefault.of(ChatEventType.HEARTBEAT).build();

        assertEquals(-1, e.getIndex(), "index 未知应为 -1");
        assertNotNull(e.getRaw(), "getRaw() 不应为 null");
        assertNotNull(e.getAttrs(), "getAttrs() 不应为 null");
        assertTrue(e.getAttrs().isEmpty());
        assertEquals(0, e.getStep());

        assertNull(e.getText());
        assertNull(e.getToolCall());
        assertNull(e.getBlock());
        assertNull(e.getUsage());
        assertNull(e.getError());
        assertNull(e.getResponse());
        assertNull(e.getRawType());
        assertNull(e.getSubType());
        assertNull(e.getResponseId());
        assertNull(e.getItemId());
        assertNull(e.getToolCallId());
        assertNull(e.attrAs("nothing"));
    }

    /**
     * 不可变：attrs 是快照且只读，构建后改源表不影响事件
     */
    @Test
    public void attrsAreImmutableSnapshot() {
        Map<String, Object> src = new HashMap<>();
        src.put("a", 1);

        ChatEvent e = ChatEventDefault.of(ChatEventType.CUSTOM).attrs(src).build();

        src.put("b", 2);

        assertEquals(1, e.getAttrs().size(), "构建后修改源表不应影响事件");
        assertThrows(UnsupportedOperationException.class, () -> e.getAttrs().put("c", 3));
    }

    /**
     * 类型不可为 null（早失败，避免 null type 流入 switch）
     */
    @Test
    public void typeCannotBeNull() {
        assertThrows(IllegalArgumentException.class, () -> ChatEventDefault.of(null));
    }

    /**
     * is / isGroup 变参谓词
     */
    @Test
    public void predicatesMatchVarargs() {
        ChatEvent e = ChatEventDefault.of(ChatEventType.TEXT_DELTA).build();

        assertTrue(e.is(ChatEventType.TEXT_DELTA));
        assertTrue(e.is(ChatEventType.TEXT_END, ChatEventType.TEXT_DELTA));
        assertFalse(e.is(ChatEventType.TEXT_END));
        assertFalse(e.is());

        assertTrue(e.isGroup(ChatEventGroup.TEXT));
        assertTrue(e.isGroup(ChatEventGroup.META, ChatEventGroup.TEXT));
        assertFalse(e.isGroup(ChatEventGroup.THINKING));
        assertFalse(e.isGroup());

        assertTrue(e.isDelta());
        assertEquals(ChatEventPhase.DELTA, e.getPhase());
        assertFalse(e.isTerminal());
    }

    /**
     * toString 便于日志诊断：含类型、步、文本摘要，且长文本被截断
     */
    @Test
    public void toStringIsDiagnostic() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            longText.append("0123456789");
        }

        String s = ChatEventDefault.of(ChatEventType.TEXT_DELTA)
                .step(1)
                .index(2)
                .itemId("it")
                .rawType("raw.t")
                .text(longText.toString())
                .build()
                .toString();

        assertTrue(s.contains("TEXT_DELTA"), s);
        assertTrue(s.contains("step=1"), s);
        assertTrue(s.contains("index=2"), s);
        assertTrue(s.contains("itemId=it"), s);
        assertTrue(s.contains("raw.t"), s);
        assertTrue(s.contains("..."), "长文本应被截断: " + s);
        assertFalse(s.contains(longText.toString()), "不应整段打印长文本: " + s);
    }

    /// ////////////////////////// getMessage 取值契约

    /**
     * 正文增量投正文槽
     */
    @Test
    public void messageProjectsTextDeltaIntoTextSlot() {
        AssistantMessage m = ChatEventDefault.of(ChatEventType.TEXT_DELTA)
                .text("你好")
                .build()
                .getMessage();

        assertNotNull(m);
        assertEquals("你好", m.getText());
        assertEquals("你好", m.getContent());
        assertFalse(m.isThinking());
    }

    /**
     * 思考增量投思考槽、正文留空：前端才能与正文分区渲染，否则思考会混进对话
     */
    @Test
    public void messageProjectsThinkingDeltaIntoThinkingSlot() {
        AssistantMessage m = ChatEventDefault.of(ChatEventType.THINKING_DELTA)
                .text("想一下")
                .build()
                .getMessage();

        assertNotNull(m);
        assertTrue(m.isThinking());
        assertEquals("想一下", m.getThinking());
        assertEquals("", m.getText(), "思考不得同时落进正文槽");
    }

    /**
     * 文本负载缺失时投空串而非 null：消费方普遍直接拼接
     */
    @Test
    public void messageProjectsEmptyTextWhenPayloadMissing() {
        assertEquals("", ChatEventDefault.of(ChatEventType.TEXT_DELTA).build().getMessage().getText());
        assertEquals("", ChatEventDefault.of(ChatEventType.THINKING_DELTA).build().getMessage().getThinking());
    }

    /**
     * 媒体完成帧进内容块槽，<b>不投文本</b>
     *
     * <p>url / base64 数据串不是模型正文：投进 text 会被当对话拼接、丢掉 mimeType，
     * 也会让「各增量帧拼接 == 终态聚合」不成立（终态的媒体在 blocks 里）。</p>
     */
    @Test
    public void messageProjectsMediaDoneIntoBlocks() {
        ImageBlock image = ImageBlock.ofUrl("https://example.com/a.png");

        AssistantMessage m = ChatEventDefault.of(ChatEventType.MEDIA_DONE)
                .block(image)
                .build()
                .getMessage();

        assertNotNull(m);
        assertEquals(1, m.getBlocks().size());
        assertSame(image, m.getBlocks().get(0));
        assertTrue(m.hasMedia());
        assertEquals("", m.getText(), "媒体不投文本槽");

        assertNull(ChatEventDefault.of(ChatEventType.MEDIA_DONE).build().getMessage(),
                "没有内容块的媒体帧无内容可取");
    }

    /**
     * 带响应的帧直取响应的消息（终态即完整聚合）：等价于 {@code getResponse().getMessage()}，省一次判空
     *
     * <p>因此消费方必须先按类型选帧：对每一帧无条件追加，会把增量和聚合加两遍。</p>
     */
    @Test
    public void messageComesFromResponseWhenPresent() {
        AssistantMessage aggregation = new AssistantMessage("你好世界");

        assertSame(aggregation, ChatEventDefault.of(ChatEventType.RESPONSE_END)
                .response(respOf(aggregation))
                .build()
                .getMessage());

        assertSame(aggregation, ChatEventDefault.of(ChatEventType.STEP_END)
                .response(respOf(aggregation))
                .build()
                .getMessage());

        //未产出任何内容就失败：响应自身无消息，不得兑成空消息冒充
        assertNull(ChatEventDefault.of(ChatEventType.ERROR)
                .response(respOf(null))
                .build()
                .getMessage());
    }

    /**
     * 不带响应的非内容帧一律为 null：取值只认类型，负载再多也不给
     *
     * <p>边界帧（{@code TEXT_START} / {@code TEXT_END} 等）与工具调用帧本身也可能带文本负载，
     * 若一并上抛，前端会与增量重复渲染、把工具参数分片当正文。</p>
     */
    @Test
    public void messageIsNullOnNonContentEventsWithoutResponse() {
        for (ChatEventType type : ChatEventType.values()) {
            if (type == ChatEventType.TEXT_DELTA
                    || type == ChatEventType.THINKING_DELTA
                    || type == ChatEventType.MEDIA_DONE) {
                continue;
            }

            //带上各种负载，确认取值只认类型
            ChatEvent e = ChatEventDefault.of(type)
                    .text("x")
                    .block(ImageBlock.ofUrl("https://example.com/b.png"))
                    .build();

            assertNull(e.getMessage(), "非内容帧不应给消息: " + type);
        }
    }

    /**
     * getMessageOrEmpty 恒非空：无内容可取时兑成空消息，访问器不必逐个判空
     */
    @Test
    public void messageOrEmptyIsNeverNull() {
        AssistantMessage m = ChatEventDefault.of(ChatEventType.HEARTBEAT).build().getMessageOrEmpty();

        assertNotNull(m);
        assertEquals("", m.getContent());
        assertFalse(m.isThinking());

        assertEquals("你好", ChatEventDefault.of(ChatEventType.TEXT_DELTA)
                .text("你好")
                .build()
                .getMessageOrEmpty()
                .getText());
    }

    /**
     * 轻量响应替身：本测试只关心「帧带响应时取哪个消息」，不依赖真实响应的构造链
     */
    private static ChatResponse respOf(@Nullable AssistantMessage message) {
        return (ChatResponse) Proxy.newProxyInstance(
                ChatResponse.class.getClassLoader(),
                new Class[]{ChatResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getMessage":
                            return message;
                        case "isEmpty":
                            return message == null;
                        case "toString":
                            return "resp(" + message + ")";
                        default:
                            return null;
                    }
                });
    }
}
