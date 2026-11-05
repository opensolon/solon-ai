package org.noear.solon.ai.harness.extension.loop;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务模型：序列化往返、copyWithUpdate 保留身份/状态/扩展字段、扩展字段原样保留。
 */
class LoopTaskTest {

    @Test
    void copyWithUpdateKeepsIdentityAndRuntimeState() {
        LoopTask task = new LoopTask("old prompt", 1, null, false);
        task.updateLastExecution("last");
        task.incrementIteration();

        LoopTask updated = task.copyWithUpdate("new prompt", 5, null, false);

        assertEquals(task.getId(), updated.getId());
        assertEquals(task.getCreatedAt(), updated.getCreatedAt());
        assertEquals(1, updated.getCurrentIteration());
        assertEquals("last", updated.getLastResult());
        assertEquals("new prompt", updated.getPrompt());
    }

    @Test
    void oneShotSurvivesSerializationRoundTrip() {
        LoopTask task = new LoopTask("one shot", 0, "0 0 18 5 9 ? 2026", false);
        task.setOneShot(true);

        ONode node = task.toONode();
        assertTrue(node.getOrNull("oneShot") != null && node.get("oneShot").getBoolean());
        assertTrue(LoopTask.fromONode(node).isOneShot());
    }

    @Test
    void legacyJsonWithoutOneShotDefaultsToFalse() {
        LoopTask task = new LoopTask("legacy", 5, null, false);
        ONode node = task.toONode();
        assertNull(node.getOrNull("oneShot"), "未开启时不写字段");
        assertFalse(LoopTask.fromONode(node).isOneShot());
    }

    @Test
    void plainTaskHasNoExtras() {
        LoopTask task = new LoopTask("心跳检查", 5, null, false);
        assertFalse(task.toONode().hasKey("automation"));
        assertNull(task.getExtra("automation"));
    }

    @Test
    void unknownTopLevelFieldsRoundTripAsExtras() {
        LoopTask task = new LoopTask("每日日报", 0, "0 0 22 * * ?", false);
        ONode node = task.toONode();
        node.set("sessionId", "auto-1");
        node.getOrNew("automation").set("id", "a1").set("name", "日报任务");
        node.set("hostFlag", "x");

        LoopTask restored = LoopTask.fromONode(node);
        assertNotNull(restored.getExtra("automation"));
        assertEquals("日报任务", restored.getExtra("automation").get("name").getString());
        assertEquals("x", restored.getExtra("hostFlag").getString());
        assertNull(restored.getExtra("sessionId"), "sessionId 是存储层定位键，不进 extras");

        ONode again = restored.toONode();
        assertEquals("a1", again.get("automation").get("id").getString(), "宿主字段必须原样写回");
        assertEquals("x", again.get("hostFlag").getString());
        assertFalse(again.hasKey("sessionId"));
    }

    @Test
    void copyWithUpdateCarriesExtras() {
        LoopTask task = new LoopTask("旧任务", 5, null, false);
        task.putExtra("automation", new ONode().set("id", "a1"));

        LoopTask updated = task.copyWithUpdate("新提示词", 10, null, null);
        assertNotNull(updated.getExtra("automation"), "更新任务定义时必须保留扩展字段");
        assertEquals("新提示词", updated.getPrompt());
    }

    @Test
    void putExtraRejectsReservedAndRemovesOnNull() {
        LoopTask task = new LoopTask("t", 5, null, false);
        assertThrows(IllegalArgumentException.class, () -> task.putExtra("prompt", new ONode()));
        assertThrows(IllegalArgumentException.class, () -> task.putExtra("sessionId", new ONode()));

        task.putExtra("k", new ONode().set("v", 1));
        assertNotNull(task.getExtra("k"));
        task.putExtra("k", null);
        assertNull(task.getExtra("k"));
    }

    @Test
    void serializationIsStableForUntouchedTask() {
        LoopTask task = new LoopTask("p", 5, null, false);
        task.putExtra("automation", new ONode().set("id", "a1").set("name", "n"));
        String first = task.toONode().toJson();
        String second = LoopTask.fromONode(ONode.ofJson(first)).toONode().toJson();
        assertEquals(first, second, "未修改的任务往返后内容必须逐字节一致");
    }
}
