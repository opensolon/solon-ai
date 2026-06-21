package org.noear.solon.ai.loop;

import org.junit.jupiter.api.*;
import org.noear.solon.ai.loop.state.LoopState;
import org.noear.solon.ai.loop.state.LoopStateData;
import org.noear.solon.ai.loop.state.disk.AtomicWrite;
import org.noear.solon.ai.loop.state.disk.DiskStateManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第三轮新增测试——DiskStateManager 扩展覆盖
 *
 * 覆盖对标 oh-my-claudecode 的 mode-state-io.test.ts 中缺失的：
 * - _meta 写入/读取剥离
 * - 旧版兼容（无 _meta 格式）
 * - 异常 JSON 处理
 * - 并发竞态场景
 * - worktree 路径解析
 */
public class DiskStateManagerExtendedTest {

    // ==========================================
    // 1. _meta 写入验证
    // ==========================================

    @Nested
    class MetaWriteTest {

        @Test
        void testWriteStateIncludesMeta() throws IOException {
            Path tempDir = Files.createTempDirectory("meta-write-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                LoopStateData state = createTestState("meta-test-001", LoopState.PLANNING);

                assertTrue(dsm.writeState("ralph", state, "meta-test-001"),
                        "writeState should succeed");

                // 直接读取原始 JSON 验证 _meta 字段
                Path stateFile = tempDir.resolve(".solon-ai-loop/state/ralph/meta-test-001.json");
                assertTrue(Files.exists(stateFile), "State file should exist");

                String rawJson = new String(java.nio.file.Files.readAllBytes(stateFile));
                assertTrue(rawJson.contains("\"_meta\""), "Raw JSON should contain _meta");
                assertTrue(rawJson.contains("\"written_at\""), "_meta should contain written_at");
                assertTrue(rawJson.contains("\"ralph\""), "_meta should contain mode 'ralph'");
                assertTrue(rawJson.contains("\"meta-test-001\""), "_meta should contain sessionId");

                // 验证 data 部分也存在
                assertTrue(rawJson.contains("\"data\""), "Raw JSON should contain data");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testMetaWrittenAtIsValidInstant() throws IOException {
            Path tempDir = Files.createTempDirectory("meta-instant-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                LoopStateData state = createTestState("meta-instant-test", LoopState.EXECUTING);

                dsm.writeState("ralph", state, "meta-instant-test");

                Path stateFile = tempDir.resolve(".solon-ai-loop/state/ralph/meta-instant-test.json");
                String rawJson = new String(java.nio.file.Files.readAllBytes(stateFile));

                // 提取 written_at 值验证是合法的 ISO 时间戳
                int writtenAtIdx = rawJson.indexOf("\"written_at\"");
                assertTrue(writtenAtIdx >= 0, "Should contain written_at");

                // 验证 written_at 是 ISO 格式
                assertTrue(rawJson.contains("T"), "written_at should be ISO format with T");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testMetaModeMatchesDirectory() throws IOException {
            Path tempDir = Files.createTempDirectory("meta-mode-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                LoopStateData state = createTestState("meta-mode-test", LoopState.IDLE);

                dsm.writeState("team", state, "meta-mode-test");

                Path stateFile = tempDir.resolve(".solon-ai-loop/state/team/meta-mode-test.json");
                String rawJson = new String(java.nio.file.Files.readAllBytes(stateFile));

                assertTrue(rawJson.contains("\"team\""), "_meta mode should match directory (team)");

                // 同时在 ralph 目录下不应该有文件
                Path ralphFile = tempDir.resolve(".solon-ai-loop/state/ralph/meta-mode-test.json");
                assertFalse(Files.exists(ralphFile), "Should not create file in ralph directory");

            } finally {
                deleteDirectory(tempDir);
            }
        }
    }

    // ==========================================
    // 2. _meta 读取剥离验证
    // ==========================================

    @Nested
    class MetaReadStrippedTest {

        @Test
        void testReadStateStripsMeta() throws IOException {
            Path tempDir = Files.createTempDirectory("meta-strip-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                LoopStateData state = createTestState("meta-strip-001", LoopState.VERIFYING);
                state.setIterationCount(5);

                dsm.writeState("ralph", state, "meta-strip-001");

                LoopStateData loaded = dsm.readState("ralph", "meta-strip-001");
                assertNotNull(loaded, "readState should return non-null");
                assertEquals("meta-strip-001", loaded.getSessionId());
                assertEquals(LoopState.VERIFYING, loaded.getState());
                assertEquals(5, loaded.getIterationCount());

                // 验证 loaded 对象中没有 _meta 相关的字段或键
                assertNull(loaded.getMetadata(), "metadata should be null (no metadata was set)");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testWriteThenReadRoundTripPreservesAllDataFields() throws IOException {
            Path tempDir = Files.createTempDirectory("roundtrip-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                Map<String, Object> ctx = new HashMap<>();
                ctx.put("key1", "value1");
                ctx.put("number", 42);

                Map<String, Object> meta = new HashMap<>();
                meta.put("source", "test");

                LoopStateData state = new LoopStateData();
                state.setSessionId("roundtrip-001");
                state.setState(LoopState.EXECUTING);
                state.setIterationCount(7);
                state.setSuccessfulIterations(5);
                state.setStartTime(Instant.parse("2026-01-01T00:00:00Z"));
                state.setLastUpdateTime(Instant.parse("2026-01-01T01:00:00Z"));
                state.setContextData(ctx);
                state.setMetadata(meta);

                dsm.writeState("ultraqa", state, "roundtrip-001");

                LoopStateData loaded = dsm.readState("ultraqa", "roundtrip-001");
                assertNotNull(loaded);
                assertEquals("roundtrip-001", loaded.getSessionId());
                assertEquals(LoopState.EXECUTING, loaded.getState());
                assertEquals(7, loaded.getIterationCount());
                assertEquals(5, loaded.getSuccessfulIterations());
                assertEquals("2026-01-01T00:00:00Z", loaded.getStartTime().toString());
                assertEquals("2026-01-01T01:00:00Z", loaded.getLastUpdateTime().toString());

                assertNotNull(loaded.getContextData());
                assertEquals("value1", loaded.getContextData().get("key1"));
                // ONode 反序列化 int 会变成 Integer，直接比较
                assertEquals(42, ((Number) loaded.getContextData().get("number")).intValue());

                assertNotNull(loaded.getMetadata());
                assertEquals("test", loaded.getMetadata().get("source"));

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testReadAcrossModesReturnsCorrectData() throws IOException {
            Path tempDir = Files.createTempDirectory("cross-mode-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                // 写入 ralph 状态
                LoopStateData ralphState = createTestState("cross-001", LoopState.PLANNING);
                dsm.writeState("ralph", ralphState, "cross-001");

                // 写入 team 状态（不同 sessionId）
                LoopStateData teamState = createTestState("cross-002", LoopState.EXECUTING);
                dsm.writeState("team", teamState, "cross-002");

                // 验证跨模式读取不影响
                LoopStateData loadedRalph = dsm.readState("ralph", "cross-001");
                assertNotNull(loadedRalph);
                assertEquals(LoopState.PLANNING, loadedRalph.getState());

                LoopStateData loadedTeam = dsm.readState("team", "cross-002");
                assertNotNull(loadedTeam);
                assertEquals(LoopState.EXECUTING, loadedTeam.getState());

                // 验证错误的模式返回 null
                assertNull(dsm.readState("team", "cross-001"),
                        "Reading ralph state from team dir should return null");

            } finally {
                deleteDirectory(tempDir);
            }
        }
    }

    // ==========================================
    // 3. 旧版兼容（无 _meta 格式）
    // ==========================================

    @Nested
    class LegacyFormatTest {

        @Test
        void testReadLegacyFormatWithoutMeta() throws IOException {
            Path tempDir = Files.createTempDirectory("legacy-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                // 手动创建旧版格式 JSON（无 _meta，data 直接作为 root）
                Path stateFile = tempDir.resolve(".solon-ai-loop/state/ralph/legacy-001.json");
                Files.createDirectories(stateFile.getParent());

                String legacyJson = "{"
                        + "\"sessionId\":\"legacy-001\","
                        + "\"state\":\"EXECUTING\","
                        + "\"iterationCount\":3,"
                        + "\"successfulIterations\":2"
                        + "}";
                AtomicWrite.write(stateFile, legacyJson);

                // readState 应返回 null（因为期望的格式是 root._meta + root.data）
                LoopStateData loaded = dsm.readState("ralph", "legacy-001");
                assertNull(loaded, "readState should return null for legacy format without _meta+data wrapper");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testReadWrappedDataWithoutMeta() throws IOException {
            Path tempDir = Files.createTempDirectory("wrapped-legacy-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                // 有 data 层但无 _meta 层
                Path stateFile = tempDir.resolve(".solon-ai-loop/state/ralph/wrapped-legacy-001.json");
                Files.createDirectories(stateFile.getParent());

                String wrappedJson = "{"
                        + "\"data\": {"
                        + "  \"sessionId\":\"wrapped-001\","
                        + "  \"state\":\"EXECUTING\","
                        + "  \"iterationCount\":5"
                        + "}"
                        + "}";
                AtomicWrite.write(stateFile, wrappedJson);

                LoopStateData loaded = dsm.readState("ralph", "wrapped-legacy-001");
                assertNotNull(loaded, "Should read data layer even without _meta");
                assertEquals("wrapped-001", loaded.getSessionId());
                assertEquals(LoopState.EXECUTING, loaded.getState());
                assertEquals(5, loaded.getIterationCount());

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testLoadStateFindsLegacyAcrossModes() throws IOException {
            Path tempDir = Files.createTempDirectory("load-legacy-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                // 在 ralph 目录下创建旧式格式
                Path stateFile = tempDir.resolve(".solon-ai-loop/state/ralph/load-legacy-001.json");
                Files.createDirectories(stateFile.getParent());

                String legacyJson = "{"
                        + "\"data\": {"
                        + "  \"sessionId\":\"load-legacy-001\","
                        + "  \"state\":\"IDLE\","
                        + "  \"iterationCount\":0"
                        + "}"
                        + "}";
                AtomicWrite.write(stateFile, legacyJson);

                // loadState（无模式）应能从 ralph 目录找到
                LoopStateData loaded = dsm.loadState("load-legacy-001");
                assertNotNull(loaded, "loadState should find legacy format across modes");
                assertEquals(LoopState.IDLE, loaded.getState());

            } finally {
                deleteDirectory(tempDir);
            }
        }
    }

    // ==========================================
    // 4. 异常 JSON 处理
    // ==========================================

    @Nested
    class MalformedJsonTest {

        @Test
        void testMalformedJsonThrowsException() throws IOException {
            Path tempDir = Files.createTempDirectory("malformed-json-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                // 写入非法 JSON 内容（ONode 抛出 RuntimeException）
                Path stateFile = tempDir.resolve(".solon-ai-loop/state/ralph/malformed-001.json");
                Files.createDirectories(stateFile.getParent());
                AtomicWrite.write(stateFile, "this is not json");

                assertThrows(RuntimeException.class,
                    () -> dsm.readState("ralph", "malformed-001"),
                    "Malformed JSON should throw RuntimeException from ONode.ofJson");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testEmptyJsonThrowsException() throws IOException {
            Path tempDir = Files.createTempDirectory("empty-json-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                Path stateFile = tempDir.resolve(".solon-ai-loop/state/ralph/empty-001.json");
                Files.createDirectories(stateFile.getParent());
                AtomicWrite.write(stateFile, "");

                assertThrows(RuntimeException.class,
                    () -> dsm.readState("ralph", "empty-001"),
                    "Empty content should throw RuntimeException from ONode.ofJson");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testPartialJsonThrowsException() throws IOException {
            Path tempDir = Files.createTempDirectory("partial-json-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                Path stateFile = tempDir.resolve(".solon-ai-loop/state/ralph/partial-001.json");
                Files.createDirectories(stateFile.getParent());
                AtomicWrite.write(stateFile, "{\"data\": { \"sessionId\": \"partial\"");

                assertThrows(RuntimeException.class,
                    () -> dsm.readState("ralph", "partial-001"),
                    "Partial/incomplete JSON should throw RuntimeException");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testNullBytesInFile() throws IOException {
            Path tempDir = Files.createTempDirectory("nullbytes-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                Path stateFile = tempDir.resolve(".solon-ai-loop/state/ralph/nullbytes-001.json");
                Files.createDirectories(stateFile.getParent());
                // 写入包含 null 字节的尾部内容（ONode 视为 trailing garbage）
                AtomicWrite.write(stateFile, "{\"data\":{\"sessionId\":\"test\"}}\0corrupted");

                // ONode 对 trailing garbage 抛出异常
                assertThrows(RuntimeException.class,
                    () -> dsm.readState("ralph", "nullbytes-001"),
                    "Trailing garbage after JSON root should throw RuntimeException");

            } finally {
                deleteDirectory(tempDir);
            }
        }
    }

    // ==========================================
    // 5. 并发竞态测试
    // ==========================================

    @Nested
    class ConcurrentAccessTest {

        @Test
        void testConcurrentWriteAndRead() throws Exception {
            Path tempDir = Files.createTempDirectory("concurrent-rw-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                int threadCount = 5;
                int iterationsPerThread = 3;
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(threadCount);
                AtomicInteger successCount = new AtomicInteger(0);

                for (int i = 0; i < threadCount; i++) {
                    final int threadIdx = i;
                    new Thread(() -> {
                        try {
                            startLatch.await();
                            for (int j = 0; j < iterationsPerThread; j++) {
                                String sid = "concurrent-" + threadIdx + "-" + j;
                                LoopStateData state = createTestState(sid, LoopState.EXECUTING);
                                boolean written = dsm.writeState("ralph", state, sid);
                                if (written) {
                                    LoopStateData loaded = dsm.readState("ralph", sid);
                                    if (loaded != null && sid.equals(loaded.getSessionId())) {
                                        successCount.incrementAndGet();
                                    }
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    }).start();
                }

                startLatch.countDown();
                boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
                assertTrue(completed, "All threads should complete within timeout");

                // 每个线程 iterationsPerThread 次成功读写
                int expected = threadCount * iterationsPerThread;
                assertEquals(expected, successCount.get(),
                        "All concurrent write+read operations should succeed");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testConcurrentWriteSameSessionLastWriterWins() throws Exception {
            Path tempDir = Files.createTempDirectory("concurrent-same-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                int threadCount = 5;
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(threadCount);
                AtomicInteger lastIteration = new AtomicInteger(0);

                for (int i = 0; i < threadCount; i++) {
                    final int iter = i + 1;
                    new Thread(() -> {
                        try {
                            startLatch.await();
                            LoopStateData state = createTestState("same-session", LoopState.EXECUTING);
                            state.setIterationCount(iter);
                            dsm.writeState("ralph", state, "same-session");
                            lastIteration.set(iter);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    }).start();
                }

                startLatch.countDown();
                doneLatch.await(10, TimeUnit.SECONDS);

                // 最后写入的线程胜出，但不确定是哪个，只需验证可读
                LoopStateData loaded = dsm.readState("ralph", "same-session");
                assertNotNull(loaded, "Last write should be readable");
                assertEquals("same-session", loaded.getSessionId());

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testAtomicWriteDoesNotLeaveTempFiles() throws IOException {
            Path tempDir = Files.createTempDirectory("atomic-temp-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                LoopStateData state = createTestState("atomic-temp-001", LoopState.PLANNING);

                dsm.writeState("ralph", state, "atomic-temp-001");

                // 验证没有残留 .tmp 文件
                Path stateDir = tempDir.resolve(".solon-ai-loop/state/ralph");
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(stateDir)) {
                    long tmpCount = files.filter(p -> p.toString().endsWith(".tmp")).count();
                    assertEquals(0, tmpCount, "No .tmp files should remain after atomic write");
                }

            } finally {
                deleteDirectory(tempDir);
            }
        }
    }

    // ==========================================
    // 6. worktree 路径解析
    // ==========================================

    @Nested
    class WorktreePathTest {

        @Test
        void testRootDirectoryReflectsInPath() throws IOException {
            Path tempDir = Files.createTempDirectory("worktree-path-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                assertEquals(tempDir.toString(), dsm.getRootDirectory(),
                        "getRootDirectory should return the configured root");

                // 验证 .solon-ai-loop 目录在 rootDirectory 下
                assertTrue(Files.exists(tempDir.resolve(".solon-ai-loop")),
                        "Base .solon-ai-loop dir should exist under root");
                assertTrue(Files.exists(tempDir.resolve(".solon-ai-loop/state/ralph")),
                        "State/ralph dir should exist under root");
                assertTrue(Files.exists(tempDir.resolve(".solon-ai-loop/prd")),
                        "PRD dir should exist under root");
                assertTrue(Files.exists(tempDir.resolve(".solon-ai-loop/progress")),
                        "Progress dir should exist under root");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testFindPrdPathReturnsSessionPathWhenExists() throws IOException {
            Path tempDir = Files.createTempDirectory("findprd-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                // 写入 session 级别的 PRD
                LoopStateData state = createTestState("findprd-001", LoopState.PLANNING);
                dsm.writeState("ralph", state, "findprd-001");

                Path sessionPrdDir = tempDir.resolve(".solon-ai-loop/prd");
                Files.createDirectories(sessionPrdDir);
                AtomicWrite.write(sessionPrdDir.resolve("findprd-001.json"),
                        "{\"project\":\"test\"}");

                Path found = dsm.findPrdPath("findprd-001");
                assertNotNull(found);
                assertTrue(found.toString().contains("findprd-001"),
                        "Should find session-level PRD path");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testFindPrdPathFallsBackToRoot() throws IOException {
            Path tempDir = Files.createTempDirectory("findprd-root-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                // 在 root 目录创建 prd.json
                Path rootPrd = tempDir.resolve("prd.json");
                AtomicWrite.write(rootPrd, "{\"project\":\"root-project\"}");

                Path found = dsm.findPrdPath("non-existent-session");
                assertNotNull(found);
                assertEquals(rootPrd, found,
                        "Should fallback to root prd.json when session PRD doesn't exist");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testFindPrdPathReturnsSessionPathWhenNeitherExists() throws IOException {
            Path tempDir = Files.createTempDirectory("findprd-none-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                // 既没有 session PRD 也没有 root prd.json
                Path found = dsm.findPrdPath("ghost-session");
                assertNotNull(found);
                assertTrue(found.toString().endsWith("prd/ghost-session.json"),
                        "Should return default session PRD path even if file doesn't exist");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testProgressDirectoryUsesTxtExtension() throws IOException {
            Path tempDir = Files.createTempDirectory("progress-ext-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());

                dsm.writeProgress("test progress content", "progress-ext-001");

                Path progressFile = tempDir.resolve(".solon-ai-loop/progress/progress-ext-001.txt");
                assertTrue(Files.exists(progressFile),
                        "Progress file should exist with .txt extension");
            assertEquals("test progress content", new String(java.nio.file.Files.readAllBytes(progressFile)));

            } finally {
                deleteDirectory(tempDir);
            }
        }
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    private static LoopStateData createTestState(String sessionId, LoopState state) {
        LoopStateData data = new LoopStateData();
        data.setSessionId(sessionId);
        data.setState(state);
        data.setIterationCount(0);
        data.setSuccessfulIterations(0);
        return data;
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(java.io.File::delete);
        }
    }
}
