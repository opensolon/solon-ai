package org.noear.solon.ai.harness.extension.loop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.talents.mount.source.FileMountSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * tasks.json 存储契约：
 * <ul>
 *   <li>D1：本插件不认识的条目（type=GOAL、缺 sessionId、解析失败）在任何修改后都原样保留；</li>
 *   <li>孤儿清理只删本插件的条目，且不会以空快照清空文件；</li>
 *   <li>宿主扩展字段（automation）往返不变；</li>
 *   <li>P2：整个文件读不懂时先备份原文件，绝不以空快照覆盖；读不出来时只读。</li>
 * </ul>
 */
class LoopStoreTest {
    @TempDir
    Path dir;

    private Path tasksFile() {
        return dir.resolve("tasks.json");
    }

    private void writeFile(String json) throws Exception {
        Files.write(tasksFile(), json.getBytes(StandardCharsets.UTF_8));
    }

    private ONode readFile() throws Exception {
        return ONode.ofJson(new String(Files.readAllBytes(tasksFile()), StandardCharsets.UTF_8));
    }

    private static List<ONode> entries(ONode root) {
        return new ArrayList<>(root.get("tasks").getArray());
    }

    private static String loopEntry(String id, String sessionId) {
        return "{\"id\":\"" + id + "\",\"prompt\":\"p-" + id + "\",\"intervalMinutes\":5,"
                + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"autoInterval\":false,\"enabled\":true,"
                + "\"currentIteration\":0,\"sessionId\":\"" + sessionId + "\"}";
    }

    private LoopExtension loaded(java.util.function.Predicate<String> alive) {
        LoopExtension ext = LoopExtension.builder().jobManager(null).defaultExecutor(false)
                .store(FileMountSource.of(dir), "tasks.json").sessionAlive(alive).build();
        ext.scheduler().load();
        return ext;
    }

    @Test
    void foreignEntriesSurviveAnyMutation() throws Exception {
        writeFile("{\"version\":2,\"tasks\":["
                + loopEntry("aaaa0001", "s1") + ","
                + "{\"id\":\"g1\",\"type\":\"GOAL\",\"sessionId\":\"s1\",\"prompt\":\"goal body\"},"
                + "{\"id\":\"bad1\",\"sessionId\":\"s2\"},"            // 缺必填字段：解析失败
                + "{\"id\":\"nos\",\"prompt\":\"no session\"}"          // 缺 sessionId
                + "]}");

        LoopExtension ext = loaded(id -> true);
        assertEquals(1, ext.list("s1").size(), "只有 LOOP 条目参与调度");
        assertTrue(ext.list("s2").isEmpty(), "解析失败的条目不参与调度");

        // 触发一次修改 → 整体落盘
        ext.schedule("s1", LoopSpec.of("new").everyMinutes(10));

        List<ONode> after = entries(readFile());
        assertEquals(5, after.size(), "原 4 条 + 新增 1 条，外来条目一条不能少");
        assertTrue(after.stream().anyMatch(n -> "g1".equals(n.get("id").getString())
                && "GOAL".equals(n.get("type").getString())
                && "goal body".equals(n.get("prompt").getString())), "GOAL 条目必须原样写回");
        assertTrue(after.stream().anyMatch(n -> "bad1".equals(n.get("id").getString())), "坏条目必须原样写回");
        assertTrue(after.stream().anyMatch(n -> "nos".equals(n.get("id").getString())), "缺 sessionId 的条目必须原样写回");

        // 再删一次，外来条目仍在
        ext.removeAll("s1");
        List<ONode> afterStop = entries(readFile());
        assertEquals(3, afterStop.size(), "删光本插件任务后只剩外来条目");
    }

    @Test
    void goalEntryIsNeverScheduledAsLoop() throws Exception {
        writeFile("{\"version\":2,\"tasks\":["
                + "{\"id\":\"g1\",\"type\":\"GOAL\",\"sessionId\":\"s1\",\"prompt\":\"x\",\"intervalMinutes\":5,"
                + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"autoInterval\":false}"
                + "]}");
        LoopExtension ext = loaded(id -> true);
        assertTrue(ext.list("s1").isEmpty(), "type=GOAL 即使字段齐全也不得当作 loop 任务调度");
    }

    @Test
    void heartbeatTypedEntryIsLoop() throws Exception {
        writeFile("{\"version\":2,\"tasks\":[" + loopEntry("aaaa0002", "s1").replace("\"sessionId\"", "\"type\":\"HEARTBEAT\",\"sessionId\"") + "]}");
        LoopExtension ext = loaded(id -> true);
        assertEquals(1, ext.list("s1").size(), "历史 type=HEARTBEAT 属于 loop 任务");
    }

    @Test
    void orphanCleanupKeepsForeignAndDoesNotWipeFile() throws Exception {
        writeFile("{\"version\":2,\"tasks\":["
                + loopEntry("aaaa0003", "alive") + ","
                + loopEntry("aaaa0004", "gone") + ","
                + "{\"id\":\"g1\",\"type\":\"GOAL\",\"sessionId\":\"gone\"}"
                + "]}");

        LoopExtension ext = loaded(id -> !"gone".equals(id));

        assertEquals(1, ext.list("alive").size());
        assertTrue(ext.list("gone").isEmpty(), "孤儿会话的任务不再恢复");
        List<ONode> after = entries(readFile());
        assertEquals(2, after.size(), "孤儿任务被删，存活任务与外来条目保留（不得以空快照清空）");
        assertTrue(after.stream().anyMatch(n -> "aaaa0003".equals(n.get("id").getString())));
        assertTrue(after.stream().anyMatch(n -> "g1".equals(n.get("id").getString())));
    }

    @Test
    void automationExtraRoundTripsThroughFile() throws Exception {
        writeFile("{\"version\":2,\"tasks\":["
                + loopEntry("aaaa0005", "auto-1").replace("\"sessionId\"",
                "\"automation\":{\"id\":\"a1\",\"name\":\"日报\",\"modelName\":\"m\"},\"sessionId\"")
                + "]}");

        LoopExtension ext = loaded(id -> true);
        LoopTask t = ext.get("auto-1", "aaaa0005");
        assertEquals("日报", t.getExtra("automation").get("name").getString());

        ext.toggle("auto-1", "aaaa0005"); // 任意修改触发落盘
        ONode node = entries(readFile()).get(0);
        assertEquals("a1", node.get("automation").get("id").getString());
        assertEquals("m", node.get("automation").get("modelName").getString());
        assertEquals("auto-1", node.get("sessionId").getString());
    }

    @Test
    void memoryModeNeverTouchesDisk() {
        LoopExtension ext = LoopExtension.builder().jobManager(null).build();
        ext.scheduler().load();
        ext.schedule("s1", LoopSpec.of("p").everyMinutes(5));
        assertFalse(Files.exists(tasksFile()));
        assertEquals(1, ext.list("s1").size());
    }

    @Test
    void loadIsIdempotent() throws Exception {
        writeFile("{\"version\":2,\"tasks\":[" + loopEntry("aaaa0006", "s1") + "]}");
        LoopExtension ext = loaded(id -> true);
        ext.scheduler().load();
        assertEquals(1, ext.list("s1").size(), "重复 load 不得重复装载");
    }

    // ==================== P2：损坏文件 ====================

    private List<Path> corruptBackups() throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().startsWith("tasks.json.corrupt-"))
                    .collect(Collectors.toList());
        }
    }

    @Test
    void corruptFileIsBackedUpBeforeAnyWrite() throws Exception {
        String broken = "{\"version\":2,\"tasks\":[" + loopEntry("aaaa0007", "s1") + ",{oops";
        writeFile(broken);

        LoopExtension ext = loaded(id -> true);
        assertTrue(ext.list("s1").isEmpty());

        List<Path> backups = corruptBackups();
        assertEquals(1, backups.size(), "读不懂的文件必须先备份");
        assertEquals(broken, new String(Files.readAllBytes(backups.get(0)), StandardCharsets.UTF_8),
                "备份必须是原文件逐字节内容（可人工救回）");

        ext.schedule("s1", LoopSpec.of("after").everyMinutes(5)); // 之后照常写新文件
        assertTrue(new String(Files.readAllBytes(tasksFile()), StandardCharsets.UTF_8).contains("after"));
        assertEquals(broken, new String(Files.readAllBytes(backups.get(0)), StandardCharsets.UTF_8));
    }

    @Test
    void wrongShapeIsTreatedAsCorrupt() throws Exception {
        writeFile("[1,2,3]");
        LoopExtension ext = loaded(id -> true);
        ext.schedule("s1", LoopSpec.of("p").everyMinutes(5));
        assertEquals(1, corruptBackups().size(), "结构不对同样不能被空快照覆盖");
    }

    @Test
    void blankFileNeedsNoBackup() throws Exception {
        writeFile("  ");
        loaded(id -> true);
        assertTrue(corruptBackups().isEmpty(), "空文件没有可救的内容，不留备份");
    }

    @Test
    void unreadableFileSwitchesToReadOnly() throws Exception {
        assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"), "依赖 POSIX 权限");
        String original = "{\"version\":2,\"tasks\":[" + loopEntry("aaaa0008", "s1") + "]}";
        writeFile(original);
        Files.setPosixFilePermissions(tasksFile(), PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(tasksFile()), "以 root 运行时权限不生效");
            LoopExtension ext = loaded(id -> true);
            ext.schedule("s1", LoopSpec.of("p").everyMinutes(5));
        } finally {
            Files.setPosixFilePermissions(tasksFile(), PosixFilePermissions.fromString("rw-r--r--"));
        }
        assertEquals(original, new String(Files.readAllBytes(tasksFile()), StandardCharsets.UTF_8),
                "读不出来不等于没有数据：本进程不得写盘覆盖");
    }
}
