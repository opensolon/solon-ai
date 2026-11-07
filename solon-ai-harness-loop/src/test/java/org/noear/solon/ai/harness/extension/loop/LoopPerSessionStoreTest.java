package org.noear.solon.ai.harness.extension.loop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.mount.source.MountSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 按会话分存：单会话变更只写该会话文件、会话 ID 不能穿越目录、旧单文件一次性迁移、每会话配额
 */
class LoopPerSessionStoreTest {
    @TempDir
    Path dir;

    private LoopExtension ext(MountSource source) {
        return LoopExtension.builder().jobManager(null).defaultExecutor(false)
                .store(source, "loop", "tasks.json")
                .build();
    }

    @Test
    void eachSessionHasOwnFileAndOnlyChangedOneIsRewritten() throws Exception {
        MountSource source = FileMountSource.of(dir, true);
        LoopExtension ext = ext(source);
        LoopScheduler s = ext.scheduler();
        s.load();

        s.schedule("s1", LoopSpec.of("a").everyMinutes(5));
        s.schedule("s2", LoopSpec.of("b").everyMinutes(5));
        Path f1 = dir.resolve("loop/s1.json");
        Path f2 = dir.resolve("loop/s2.json");
        assertTrue(Files.exists(f1));
        assertTrue(Files.exists(f2));

        long m2 = Files.getLastModifiedTime(f2).toMillis();
        String c2 = new String(Files.readAllBytes(f2), StandardCharsets.UTF_8);
        Thread.sleep(20);
        s.schedule("s1", LoopSpec.of("c").everyMinutes(5));
        assertEquals(c2, new String(Files.readAllBytes(f2), StandardCharsets.UTF_8));
        assertEquals(m2, Files.getLastModifiedTime(f2).toMillis(), "未变更的会话不重写");

        s.removeAll("s2");
        assertFalse(Files.exists(f2), "会话无任务时删除其文件");

        // 重新加载
        LoopScheduler again = ext(source).scheduler();
        again.load();
        assertEquals(2, again.list("s1").size());
        assertTrue(again.list("s2").isEmpty());
    }

    @Test
    void sessionIdCannotEscapeStoreDir() {
        MountSource source = FileMountSource.of(dir, true);
        LoopScheduler s = ext(source).scheduler();
        s.load();
        s.schedule("../evil", LoopSpec.of("x").everyMinutes(5));
        assertFalse(Files.exists(dir.resolve("evil.json")));
        try (Stream<Path> files = Files.list(dir.resolve("loop"))) {
            assertEquals(1, files.count());
        } catch (Exception e) {
            fail(e);
        }

        LoopScheduler again = ext(source).scheduler();
        again.load();
        assertEquals(1, again.list("../evil").size(), "编码后的文件名可还原会话 ID");
    }

    @Test
    void legacySingleFileIsMigratedOnceAndBackedUp() throws Exception {
        MountSource source = FileMountSource.of(dir, true);
        // 旧版单文件（含外来条目）
        String entry = "\"intervalMinutes\":5,\"createdAt\":\"2026-01-01T00:00:00Z\",\"autoInterval\":false,\"enabled\":true";
        String json = "{\"version\":2,\"tasks\":[{\"type\":\"GOAL\",\"sessionId\":\"g\"},"
                + "{\"id\":\"t1\",\"prompt\":\"a\"," + entry + ",\"sessionId\":\"s1\"},"
                + "{\"id\":\"t2\",\"prompt\":\"b\"," + entry + ",\"sessionId\":\"s2\"}]}";
        Files.write(dir.resolve("tasks.json"), json.getBytes(StandardCharsets.UTF_8));

        LoopScheduler s = ext(source).scheduler();
        s.load();
        assertEquals(1, s.list("s1").size());
        assertEquals(1, s.list("s2").size());
        assertFalse(Files.exists(dir.resolve("tasks.json")), "旧文件迁移后改名");
        try (Stream<Path> files = Files.list(dir)) {
            Path backup = files.filter(p -> p.getFileName().toString().startsWith("tasks.json.migrated-"))
                    .findFirst().orElseThrow(AssertionError::new);
            assertTrue(new String(Files.readAllBytes(backup), StandardCharsets.UTF_8).contains("GOAL"),
                    "外来条目随备份完整保留");
        }

        // 再次加载不重复迁移
        LoopScheduler again = ext(source).scheduler();
        again.load();
        assertEquals(1, again.list("s1").size());
    }

    @Test
    void emptyLegacyFileIsDeletedWithoutBackup() throws Exception {
        Files.write(dir.resolve("tasks.json"), "{\"version\":2,\"tasks\":[]}".getBytes(StandardCharsets.UTF_8));
        LoopScheduler s = ext(FileMountSource.of(dir, true)).scheduler();
        s.load();
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(0, files.filter(p -> p.getFileName().toString().startsWith("tasks.json")).count(),
                    "空任务表没有可救内容：删除且不留备份（避免每次启动堆积）");
        }
    }

    @Test
    void perSessionQuotaAndTenantQuota() {
        LoopScheduler s = LoopExtension.builder().jobManager(null).defaultExecutor(false)
                .quota(id -> 2).build().scheduler();
        s.schedule("a", LoopSpec.of("1").everyMinutes(5));
        s.schedule("a", LoopSpec.of("2").everyMinutes(5));
        assertThrows(IllegalStateException.class, () -> s.schedule("a", LoopSpec.of("3").everyMinutes(5)));
        s.schedule("b", LoopSpec.of("1").everyMinutes(5)); // 另一个会话不受影响

        LoopScheduler q = LoopExtension.builder().jobManager(null).defaultExecutor(false)
                .quota(sid -> sid.startsWith("vip:") ? 3 : 1)
                .build().scheduler();
        q.schedule("vip:x", LoopSpec.of("1").everyMinutes(5));
        q.schedule("vip:x", LoopSpec.of("2").everyMinutes(5));
        q.schedule("free:y", LoopSpec.of("1").everyMinutes(5));
        assertThrows(IllegalStateException.class, () -> q.schedule("free:y", LoopSpec.of("2").everyMinutes(5)));
    }
}
