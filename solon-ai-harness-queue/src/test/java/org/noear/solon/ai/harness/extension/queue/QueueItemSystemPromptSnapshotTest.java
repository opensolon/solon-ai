package org.noear.solon.ai.harness.extension.queue;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.input.HarnessInput;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 积压快照带上本轮 systemPrompt / locale：排队续发不得静默回退到智能体默认提示词。
 *
 * <p>本测试与 {@link QueueItem} 同包：快照采集与还原是包内细节。</p>
 */
class QueueItemSystemPromptSnapshotTest {

    @Test
    void snapshotKeepsSystemPromptAndLocale() {
        HarnessInput input = HarnessInput.of("hi")
                .systemPrompt("You are a shell assistant.")
                .locale(Locale.ENGLISH);

        Map<String, Object> snapshot = QueueItem.snapshotOf(input);

        assertEquals("You are a shell assistant.", snapshot.get("systemPrompt"));
        assertEquals("en", snapshot.get("locale"));
        assertEquals(QueueItem.SNAPSHOT_VERSION, snapshot.get("v"));
    }

    @Test
    void unsetValuesAreNotPersisted() {
        Map<String, Object> snapshot = QueueItem.snapshotOf(HarnessInput.of("hi"));

        assertFalse(snapshot.containsKey("systemPrompt"), "未设置时不该落空值");
        assertFalse(snapshot.containsKey("locale"));
        assertTrue(snapshot.isEmpty(), "无任何选择时不写快照记号");
    }

    @Test
    void restoredInputCarriesSystemPromptAndLocale() {
        HarnessInput input = HarnessInput.of("hi")
                .systemPrompt("You are a shell assistant.")
                .locale(Locale.ENGLISH);

        QueueItem item = new QueueItem("q1", "hi", null, 0, "PENDING");
        item.setInputSnapshot(QueueItem.snapshotOf(input));

        // 经历一次落盘 / 读盘
        QueueItem reloaded = QueueItem.fromMap(item.toPersistMap());
        AgentSession session = InMemoryAgentSession.of("s1");
        HarnessInput restored = reloaded.toInput(session);

        assertEquals("You are a shell assistant.", restored.getSystemPrompt());
        assertEquals(Locale.ENGLISH, restored.getLocale());
        assertEquals("hi", restored.getText());
    }

    @Test
    void legacySnapshotWithoutNewKeysIsReadable() {
        QueueItem item = new QueueItem("q1", "hi", null, 0, "PENDING");
        Map<String, Object> legacy = new java.util.LinkedHashMap<>();
        legacy.put("v", 1);
        legacy.put("terminal", true);
        item.setInputSnapshot(legacy);

        HarnessInput restored = item.toInput(InMemoryAgentSession.of("s1"));

        assertNull(restored.getSystemPrompt(), "旧数据没有这两项，按默认处理");
        assertNull(restored.getLocale());
        assertTrue(restored.isTerminal(), "旧键照旧生效");
    }
}
