package org.noear.solon.ai.harness.extension.loop;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.mount.source.InMemoryMountSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 算存分离：任务存储经挂载来源（这里用非本地的内存来源代替 db / http），不依赖本地文件系统。
 */
class LoopStoreMountTest {
    private static LoopExtension loaded(InMemoryMountSource source) {
        LoopExtension ext = LoopExtension.builder().jobManager(null).defaultExecutor(false)
                .store(source, "loop/tasks.json").build();
        ext.scheduler().load();
        return ext;
    }

    @Test
    void roundTripThroughNonLocalSource() throws Exception {
        InMemoryMountSource source = new InMemoryMountSource();
        LoopExtension first = loaded(source);
        LoopTask task = first.schedule("s1", LoopSpec.of("hello").everyMinutes(10));

        assertEquals(32, task.getId().length(), "新任务使用完整 128 bit ID");
        String json = source.readStringOrNull("loop/tasks.json");
        assertNotNull(json, "必须写入来源，而不是本地文件");
        assertEquals("s1", ONode.ofJson(json).get("tasks").get(0).get("sessionId").getString());

        LoopExtension second = loaded(source);
        assertNotNull(second.get("s1", task.getId()), "另一个实例从同一来源恢复任务");
        assertTrue(second.scheduler().isPersistent());
    }

    @Test
    void corruptContentIsBackedUpInsideSource() throws Exception {
        InMemoryMountSource source = new InMemoryMountSource();
        source.writeString("loop/tasks.json", "{not json");
        LoopExtension ext = loaded(source);
        ext.schedule("s1", LoopSpec.of("x").everyMinutes(10));

        long backups = source.list("loop").stream()
                .filter(e -> e.getName().startsWith("tasks.json.corrupt-")).count();
        assertEquals(1, backups, "读不懂的内容在来源内改名备份，绝不被空快照覆盖");
    }
}
