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