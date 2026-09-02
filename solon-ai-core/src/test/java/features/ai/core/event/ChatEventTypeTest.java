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

/**
 * 事件类型枚举一致性
 *
 * @author noear
 */
public class ChatEventTypeTest {
    /**
     * 每个类型都必须绑定 group 与 phase（构造期绑定，不允许遗漏）
     */
    @Test
    public void allTypesHaveGroupAndPhase() {
        for (ChatEventType type : ChatEventType.values()) {
            assertNotNull(type.getGroup(), "group is null: " + type);
            assertNotNull(type.getPhase(), "phase is null: " + type);
        }
    }

    /**
     * 事件实例的 group/phase 必须与其 type 静态绑定值一致（永不漂移）
     */
    @Test
    public void eventGroupPhaseNeverDrift() {
        for (ChatEventType type : ChatEventType.values()) {
            ChatEvent e = ChatEventDefault.of(type).build();

            assertSame(type, e.getType());
            assertSame(type.getGroup(), e.getGroup());
            assertSame(type.getPhase(), e.getPhase());
            assertEquals(type.isDelta(), e.isDelta());
            assertEquals(type.isTerminal(), e.isTerminal());
        }
    }

    /**
     * 分组为闭集：只 switch 在 9 个分组上的消费者，不会漏掉任何类型
     */
    @Test
    public void groupIsClosedSet() {
        List<ChatEventType> unhandled = new ArrayList<>();

        for (ChatEventType type : ChatEventType.values()) {
            switch (type.getGroup()) {
                case LIFECYCLE:
                case STEP:
                case TEXT:
                case THINKING:
                case TOOL_CALL:
                case SERVER_TOOL:
                case MEDIA:
                case SAFETY:
                case META:
                    break;
                default:
                    unhandled.add(type);
                    break;
            }
        }

        assertTrue(unhandled.isEmpty(), "unhandled types: " + unhandled);
    }

    /**
     * 每个内容分组必须齐备 START/DELTA/END（归一化器依赖这个前提）
     */
    @Test
    public void contentGroupsHaveFullPhases() {
        assertBoundaryTriple(ChatEventGroup.TEXT);
        assertBoundaryTriple(ChatEventGroup.THINKING);
    }

    private void assertBoundaryTriple(ChatEventGroup group) {
        boolean hasStart = false, hasDelta = false, hasEnd = false;

        for (ChatEventType type : ChatEventType.values()) {
            if (type.getGroup() != group) {
                continue;
            }

            if (type.getPhase() == ChatEventPhase.START) {
                hasStart = true;
            } else if (type.getPhase() == ChatEventPhase.DELTA) {
                hasDelta = true;
            } else if (type.getPhase() == ChatEventPhase.END) {
                hasEnd = true;
            }
        }

        assertTrue(hasStart, group + " missing START");
        assertTrue(hasDelta, group + " missing DELTA");
        assertTrue(hasEnd, group + " missing END");
    }

    /**
     * 构建器默认值：index 为 -1（未知），attrs/raw 非 null
     */
    @Test
    public void builderDefaults() {
        ChatEvent e = ChatEventDefault.of(ChatEventType.TEXT_DELTA).build();

        assertEquals(-1, e.getIndex());
        assertEquals(0, e.getStep());
        assertNotNull(e.getRaw());
        assertNotNull(e.getAttrs());
        assertTrue(e.getAttrs().isEmpty());
        assertNull(e.getText());
    }

    /**
     * attrs 不可变（事件对外只读）
     */
    @Test
    public void attrsAreImmutable() {
        ChatEvent e = ChatEventDefault.of(ChatEventType.RAW).attr("k", "v").build();

        assertEquals("v", e.attrAs("k"));
        assertThrows(UnsupportedOperationException.class, () -> e.getAttrs().put("k2", "v2"));
    }

    /**
     * 谓词：is / isGroup
     */
    @Test
    public void predicates() {
        ChatEvent e = ChatEventDefault.of(ChatEventType.THINKING_DELTA).build();

        assertTrue(e.is(ChatEventType.TEXT_DELTA, ChatEventType.THINKING_DELTA));
        assertFalse(e.is(ChatEventType.TEXT_DELTA));
        assertTrue(e.isGroup(ChatEventGroup.THINKING));
        assertFalse(e.isGroup(ChatEventGroup.TEXT, ChatEventGroup.META));
        assertTrue(e.isDelta());
    }

    /**
     * 类型不能为 null
     */
    @Test
    public void typeCannotBeNull() {
        assertThrows(IllegalArgumentException.class, () -> ChatEventDefault.of(null));
    }
}
