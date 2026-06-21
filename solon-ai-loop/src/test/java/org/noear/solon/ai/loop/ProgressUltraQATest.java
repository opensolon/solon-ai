package org.noear.solon.ai.loop;

import org.junit.jupiter.api.*;
import org.noear.solon.ai.loop.progress.*;
import org.noear.solon.ai.loop.state.LoopState;
import org.noear.solon.ai.loop.state.LoopStateData;
import org.noear.solon.ai.loop.state.disk.DiskStateManager;
import org.noear.solon.ai.loop.strategy.CommandExecutor;
import org.noear.solon.ai.loop.strategy.LoopContext;
import org.noear.solon.ai.loop.strategy.ProcessCommandExecutor;
import org.noear.solon.ai.loop.strategy.UltraQAStrategy;
import org.noear.solon.ai.loop.validator.QualityGate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第三轮新增测试——进度记忆系统与 UltraQA 扩展覆盖
 *
 * 覆盖对标 oh-my-claudecode 中缺失的：
 * - ProgressEntry/ProgressLog 序列化与反序列化
 * - ProgressManager 追加/读取/模式管理
 * - UltraQA goalType -> 命令映射
 * - UltraQA Builder 配置
 */
public class ProgressUltraQATest {

    // ==========================================
    // 1. ProgressEntry 单元测试
    // ==========================================

    @Nested
    class ProgressEntryUnitTest {

        @Test
        void testProgressEntryCreation() {
            ProgressEntry entry = new ProgressEntry("US-001");
            assertEquals("US-001", entry.getStoryId());
            assertNotNull(entry.getTimestamp());
            assertTrue(entry.getImplementation().isEmpty());
            assertTrue(entry.getFilesChanged().isEmpty());
            assertTrue(entry.getLearnings().isEmpty());
        }

        @Test
        void testProgressEntryWithData() {
            List<String> impl = Arrays.asList("Implemented login", "Added validation");
            List<String> files = Arrays.asList("LoginController.java", "UserService.java");
            List<String> learnings = Arrays.asList("Need to handle edge cases");

            ProgressEntry entry = new ProgressEntry("US-001", impl, files, learnings);

            assertEquals("US-001", entry.getStoryId());
            assertEquals(2, entry.getImplementation().size());
            assertEquals(2, entry.getFilesChanged().size());
            assertEquals(1, entry.getLearnings().size());
        }

        @Test
        void testProgressEntryAddMethods() {
            ProgressEntry entry = new ProgressEntry("US-002");

            entry.addImplementation("Added feature X");
            entry.addFileChanged("FeatureX.java");
            entry.addLearning("Learned about pattern Y");

            assertEquals(1, entry.getImplementation().size());
            assertEquals(1, entry.getFilesChanged().size());
            assertEquals(1, entry.getLearnings().size());
            assertEquals("Added feature X", entry.getImplementation().get(0));
        }

        @Test
        void testFormatWithAllFields() {
            ProgressEntry entry = new ProgressEntry("US-001",
                    Arrays.asList("Implemented feature"),
                    Arrays.asList("File1.java"),
                    Arrays.asList("Important lesson"));

            String formatted = entry.format();
            assertTrue(formatted.contains("US-001"), "Should contain story ID");
            assertTrue(formatted.contains("<story_id>"), "Should contain XML story_id tag");
            assertTrue(formatted.contains("Implemented feature"), "Should contain implementation");
            assertTrue(formatted.contains("<implementation>"), "Should contain XML implementation tag");
            assertTrue(formatted.contains("File1.java"), "Should contain file name");
            assertTrue(formatted.contains("<files_changed>"), "Should contain XML files_changed tag");
            assertTrue(formatted.contains("Important lesson"), "Should contain learning");
            assertTrue(formatted.contains("<learnings>"), "Should contain XML learnings tag");
        }

        @Test
        void testFormatWithMinimalFields() {
            ProgressEntry entry = new ProgressEntry("US-002");

            String formatted = entry.format();
            assertTrue(formatted.contains("US-002"));
            // 没有实现内容、文件变更和学习经验
        }
    }

    // ==========================================
    // 2. ProgressLog 单元测试
    // ==========================================

    @Nested
    class ProgressLogUnitTest {

        @Test
        void testProgressLogCreation() {
            ProgressLog log = new ProgressLog();
            assertNotNull(log.getStartedAt());
            assertTrue(log.getPatterns().isEmpty());
            assertTrue(log.getEntries().isEmpty());
        }

        @Test
        void testAddPattern() {
            ProgressLog log = new ProgressLog();
            log.addPattern("Use builder pattern for complex objects");
            log.addPattern("Prefer constructor injection");

            assertEquals(2, log.getPatterns().size());
            assertEquals("Use builder pattern for complex objects",
                    log.getPatterns().get(0).getDescription());
            assertEquals("Prefer constructor injection",
                    log.getPatterns().get(1).getDescription());
        }

        @Test
        void testAddEntry() {
            ProgressLog log = new ProgressLog();
            ProgressEntry entry = new ProgressEntry("US-001",
                    Arrays.asList("Implemented X"),
                    Collections.emptyList(),
                    Arrays.asList("Learning A", "Learning B"));
            log.addEntry(entry);

            assertEquals(1, log.getEntries().size());
        }

        @Test
        void testGetRecentLearningsLimit() {
            ProgressLog log = new ProgressLog();

            for (int i = 0; i < 5; i++) {
                ProgressEntry entry = new ProgressEntry("US-00" + (i + 1),
                        Collections.singletonList("Item"),
                        Collections.emptyList(),
                        Collections.singletonList("Learning " + (i + 1)));
                log.addEntry(entry);
            }

            List<String> recent = log.getRecentLearnings(3);
            assertEquals(3, recent.size());
            // 最近的学习经验
            assertTrue(recent.contains("Learning 3"));
            assertTrue(recent.contains("Learning 4"));
            assertTrue(recent.contains("Learning 5"));
        }

        @Test
        void testGetRecentLearningsUnderLimit() {
            ProgressLog log = new ProgressLog();

            ProgressEntry entry = new ProgressEntry("US-001",
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.singletonList("Single learning"));
            log.addEntry(entry);

            List<String> recent = log.getRecentLearnings(10);
            assertEquals(1, recent.size());
        }

        @Test
        void testGetRecentLearningsEmpty() {
            ProgressLog log = new ProgressLog();
            assertTrue(log.getRecentLearnings(5).isEmpty());
        }

        @Test
        void testFormatPatternsForContextWithPatterns() {
            ProgressLog log = new ProgressLog();
            log.addPattern("Pattern 1");
            log.addPattern("Pattern 2");

            String formatted = log.formatPatternsForContext();
            assertTrue(formatted.contains("<codebase_patterns>"), "Should contain opening tag");
            assertTrue(formatted.contains("</codebase_patterns>"), "Should contain closing tag");
            assertTrue(formatted.contains("<pattern>"), "Should contain pattern tag");
            assertTrue(formatted.contains("Pattern 1"), "Should contain pattern 1");
            assertTrue(formatted.contains("Pattern 2"), "Should contain pattern 2");
        }

        @Test
        void testFormatPatternsForContextEmpty() {
            ProgressLog log = new ProgressLog();
            assertEquals("", log.formatPatternsForContext());
        }

        @Test
        void testFormatForContext() {
            ProgressLog log = new ProgressLog();
            log.addPattern("Test pattern");

            ProgressEntry entry = new ProgressEntry("US-001",
                    Arrays.asList("Work done"),
                    Arrays.asList("file.java"),
                    Arrays.asList("Lesson learned"));
            log.addEntry(entry);

            String formatted = log.formatForContext();
            assertTrue(formatted.contains("<progress_log>"), "Should contain progress_log tag");
            assertTrue(formatted.contains("</progress_log>"), "Should contain closing tag");
            assertTrue(formatted.contains("<pattern>"), "Should contain pattern tag");
            assertTrue(formatted.contains("Test pattern"), "Should contain pattern text");
            assertTrue(formatted.contains("US-001"), "Should contain story ID");
            assertTrue(formatted.contains("Work done"), "Should contain implementation");
        }
    }

    // ==========================================
    // 3. ProgressManager 集成测试
    // ==========================================

    @Nested
    class ProgressManagerTest {

        @Test
        void testInitProgress() throws IOException {
            Path tempDir = Files.createTempDirectory("pm-init-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                ProgressManager pm = new ProgressManager(dsm);

                assertTrue(pm.initProgress("init-session"));

                // 验证进度文件已创建
                assertNotNull(dsm.readProgress("init-session"));

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testAppendProgress() throws IOException {
            Path tempDir = Files.createTempDirectory("pm-append-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                ProgressManager pm = new ProgressManager(dsm);

                pm.initProgress("append-session");

                ProgressEntry entry = new ProgressEntry("US-001",
                        Arrays.asList("Feature X"),
                        Arrays.asList("FileX.java"),
                        Arrays.asList("Keep it simple"));
                assertTrue(pm.appendProgress("append-session", entry));

                // 验证 entry 可读回
                List<String> learnings = pm.getRecentLearnings("append-session", 10);
                assertEquals(1, learnings.size());
                assertTrue(learnings.get(0).contains("Keep it simple"));

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testAppendMultipleEntries() throws IOException {
            Path tempDir = Files.createTempDirectory("pm-multi-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                ProgressManager pm = new ProgressManager(dsm);

                pm.initProgress("multi-session");

                ProgressEntry e1 = new ProgressEntry("US-001",
                        Collections.singletonList("Feature 1"),
                        Collections.emptyList(),
                        Collections.singletonList("Learning 1"));
                pm.appendProgress("multi-session", e1);

                ProgressEntry e2 = new ProgressEntry("US-002",
                        Collections.singletonList("Feature 2"),
                        Collections.emptyList(),
                        Collections.singletonList("Learning 2"));
                pm.appendProgress("multi-session", e2);

                // 验证两个 learnings 都保留
                List<String> learnings = pm.getRecentLearnings("multi-session", 10);
                assertEquals(2, learnings.size());
                assertTrue(learnings.contains("Learning 1"));
                assertTrue(learnings.contains("Learning 2"));

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testAddPattern() throws IOException {
            Path tempDir = Files.createTempDirectory("pm-pattern-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                ProgressManager pm = new ProgressManager(dsm);

                pm.initProgress("pattern-session");
                assertTrue(pm.addPattern("pattern-session", "Factory pattern for object creation"));

                String formatted = pm.formatPatternsForContext("pattern-session");
                assertTrue(formatted.contains("Factory pattern"), "Should contain pattern text");
                assertTrue(formatted.contains("<pattern>"), "Should contain XML pattern tag");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testGetProgressContextNoProgress() throws IOException {
            Path tempDir = Files.createTempDirectory("pm-noprogress-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                ProgressManager pm = new ProgressManager(dsm);

                String ctx = pm.getProgressContext("non-existent");
                assertEquals("No progress log available.", ctx);

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testGetRecentLearningsNoProgress() throws IOException {
            Path tempDir = Files.createTempDirectory("pm-nolearnings-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                ProgressManager pm = new ProgressManager(dsm);

                assertTrue(pm.getRecentLearnings("non-existent", 5).isEmpty());

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testFormatPatternsForContextNoPatterns() throws IOException {
            Path tempDir = Files.createTempDirectory("pm-nopatterns-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                ProgressManager pm = new ProgressManager(dsm);

                pm.initProgress("nopattern-session");
                assertEquals("", pm.formatPatternsForContext("nopattern-session"));

            } finally {
                deleteDirectory(tempDir);
            }
        }
    }

    // ==========================================
    // 4. UltraQA 目标类型与命令映射测试
    // ==========================================

    @Nested
    class UltraQAGoalTypeTest {

        @Test
        void testDefaultGoalType() {
            UltraQAStrategy strategy = new UltraQAStrategy();
            assertEquals(UltraQAStrategy.UltraQAGoalType.TESTS, strategy.getGoalType());
        }

        @Test
        void testGoalTypeCommandMapping() {
            // TESTS
            UltraQAStrategy testsStrategy = UltraQAStrategy.builder()
                    .goalType(UltraQAStrategy.UltraQAGoalType.TESTS)
                    .build();
            assertEquals("mvn test", testsStrategy.getGoalCommand());

            // BUILD
            UltraQAStrategy buildStrategy = UltraQAStrategy.builder()
                    .goalType(UltraQAStrategy.UltraQAGoalType.BUILD)
                    .build();
            assertEquals("mvn compile", buildStrategy.getGoalCommand());

            // LINT
            UltraQAStrategy lintStrategy = UltraQAStrategy.builder()
                    .goalType(UltraQAStrategy.UltraQAGoalType.LINT)
                    .build();
            assertEquals("mvn checkstyle:check", lintStrategy.getGoalCommand());

            // TYPECHECK
            UltraQAStrategy typecheckStrategy = UltraQAStrategy.builder()
                    .goalType(UltraQAStrategy.UltraQAGoalType.TYPECHECK)
                    .build();
            assertEquals("mvn compile", typecheckStrategy.getGoalCommand());

            // CUSTOM
            UltraQAStrategy customStrategy = UltraQAStrategy.builder()
                    .goalType(UltraQAStrategy.UltraQAGoalType.CUSTOM)
                    .build();
            assertEquals("custom check", customStrategy.getGoalCommand());
        }

        @Test
        void testGoalTypeBuilder() {
            UltraQAStrategy strategy = UltraQAStrategy.builder()
                    .goalType(UltraQAStrategy.UltraQAGoalType.BUILD)
                    .maxTestAttempts(5)
                    .parallelTesting(true)
                    .strictMode(true)
                    .build();

            assertEquals(UltraQAStrategy.UltraQAGoalType.BUILD, strategy.getGoalType());
            assertEquals(5, strategy.getMaxTestAttempts());
            assertTrue(strategy.isParallelTesting());
        }

        @Test
        void testExitReasonEnumValues() {
            // 验证所有退出原因都存在
            assertNotNull(UltraQAStrategy.UltraQAExitReason.valueOf("GOAL_MET"));
            assertNotNull(UltraQAStrategy.UltraQAExitReason.valueOf("MAX_CYCLES"));
            assertNotNull(UltraQAStrategy.UltraQAExitReason.valueOf("SAME_FAILURE"));
            assertNotNull(UltraQAStrategy.UltraQAExitReason.valueOf("ENV_ERROR"));
            assertNotNull(UltraQAStrategy.UltraQAExitReason.valueOf("CANCELLED"));
        }

        @Test
        void testQualityGateBasics() {
            QualityGate buildGate = QualityGate.build();
            QualityGate testGate = QualityGate.test();

            assertNotNull(buildGate);
            assertNotNull(testGate);

            // QualityGate 是不同的实例
            assertNotSame(buildGate, testGate);
        }

        @Test
        void testDefaultMaxTestAttempts() {
            UltraQAStrategy strategy = new UltraQAStrategy();
            assertEquals(10, strategy.getMaxTestAttempts());
        }

        @Test
        void testCustomMaxTestAttempts() {
            UltraQAStrategy strategy = UltraQAStrategy.builder()
                    .maxTestAttempts(3)
                    .build();
            assertEquals(3, strategy.getMaxTestAttempts());
        }

        @Test
        void testParallelTestingDefault() {
            UltraQAStrategy strategy = new UltraQAStrategy();
            assertFalse(strategy.isParallelTesting());
        }

        @Test
        void testParallelTestingEnabled() {
            UltraQAStrategy strategy = UltraQAStrategy.builder()
                    .parallelTesting(true)
                    .build();
            assertTrue(strategy.isParallelTesting());
        }
    }

    // ==========================================
    // 5. UltraQA 退出原因测试
    // ==========================================

    @Nested
    class UltraQAExitTest {

        @Test
        void testCancelExitReason() {
            UltraQAStrategy strategy = UltraQAStrategy.builder().build();
            LoopContext ctx = createTestContext("ultraqa-exit-cancel");

            strategy.cancel(ctx);
            assertEquals(UltraQAStrategy.UltraQAExitReason.CANCELLED,
                    strategy.getFinalExitReason(ctx));
        }

        @Test
        void testEnvErrorExitReason() {
            UltraQAStrategy strategy = UltraQAStrategy.builder().build();
            LoopContext ctx = createTestContext("ultraqa-exit-env");

            strategy.reportEnvError(ctx, "Out of memory");
            assertEquals(UltraQAStrategy.UltraQAExitReason.ENV_ERROR,
                    strategy.getFinalExitReason(ctx));
        }

        @Test
        void testExitReasonLastWriterWins() {
            UltraQAStrategy strategy = UltraQAStrategy.builder().build();
            LoopContext ctx = createTestContext("ultraqa-exit-order");

            // 实现是 last-writer-wins：后调用的覆盖先调用的
            strategy.reportEnvError(ctx, "Disk full");
            assertEquals(UltraQAStrategy.UltraQAExitReason.ENV_ERROR,
                    strategy.getFinalExitReason(ctx),
                    "reportEnvError should set ENV_ERROR");

            strategy.cancel(ctx);
            assertEquals(UltraQAStrategy.UltraQAExitReason.CANCELLED,
                    strategy.getFinalExitReason(ctx),
                    "cancel should overwrite previous exit reason");
        }
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    private static LoopContext createTestContext(String description) {
        return new LoopContext(
                "test-session", description,
                LoopState.IDLE, 0, 100,
                Instant.now(),
                new HashMap<>(), new ArrayList<>(), new HashMap<>()
        );
    }

    // ==========================================
    // 6. CommandExecutor 测试（第五轮新增）
    // ==========================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class CommandExecutorTest {

        @Test
        @Order(1)
        void testCommandResultSuccess() {
            CommandExecutor.CommandResult result =
                    new CommandExecutor.CommandResult(0, "Build successful", "", 100);
            assertTrue(result.isSuccess());
            assertEquals(0, result.getExitCode());
            assertEquals("Build successful", result.getStdout());
            assertEquals(100, result.getExecutionTimeMs());
            assertEquals("Build successful", result.getSummary());
        }

        @Test
        @Order(2)
        void testCommandResultFailure() {
            CommandExecutor.CommandResult result =
                    new CommandExecutor.CommandResult(1, "", "Compilation error", 200);
            assertFalse(result.isSuccess());
            assertEquals(1, result.getExitCode());
            assertEquals("[err] Compilation error", result.getSummary());
            assertTrue(result.getDiagnosis().contains("exit code: 1"));
        }

        @Test
        @Order(3)
        void testCommandResultEmptyOutput() {
            CommandExecutor.CommandResult result =
                    new CommandExecutor.CommandResult(0, "", "", 50);
            assertTrue(result.isSuccess());
            assertEquals("OK", result.getSummary());
        }

        @Test
        @Order(4)
        void testProcessCommandExecutorExecuteEcho() throws IOException {
            ProcessCommandExecutor executor = new ProcessCommandExecutor(5000);
            assertTrue(executor.isAvailable(), "Shell should be available");

            CommandExecutor.CommandResult result = executor.execute("echo 'hello world'");
            assertTrue(result.isSuccess());
            assertTrue(result.getStdout().contains("hello world"));
            assertTrue(result.getExecutionTimeMs() >= 0);
        }

        @Test
        @Order(5)
        void testProcessCommandExecutorExitCode() throws IOException {
            ProcessCommandExecutor executor = new ProcessCommandExecutor(5000);

            // false should return non-zero exit code
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                CommandExecutor.CommandResult result = executor.execute("exit /b 1");
                assertFalse(result.isSuccess());
                assertEquals(1, result.getExitCode());
            } else {
                CommandExecutor.CommandResult result = executor.execute("false");
                assertFalse(result.isSuccess());
                assertEquals(1, result.getExitCode());
            }
        }

        @Test
        @Order(6)
        void testProcessCommandExecutorTimeout() throws IOException {
            ProcessCommandExecutor executor = new ProcessCommandExecutor(200); // 200ms timeout

            // sleep longer than timeout
            String sleepCmd = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "ping -n 3 127.0.0.1"
                    : "sleep 3";
            CommandExecutor.CommandResult result = executor.execute(sleepCmd);
            assertFalse(result.isSuccess(), "Should time out");
            assertTrue(result.getExitCode() < 0 || result.getStdout().contains("TIMEOUT"),
                    "Should indicate timeout");
        }

        @Test
        @Order(7)
        void testCommandExecutorWithWorkDir() throws IOException {
            Path tempDir = Files.createTempDirectory("exec-workdir");
            try {
                ProcessCommandExecutor executor = new ProcessCommandExecutor(5000);

                if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                    CommandExecutor.CommandResult result = executor.execute("pwd", tempDir.toString());
                    assertTrue(result.isSuccess());
                    assertTrue(result.getStdout().contains(tempDir.getFileName().toString()),
                            "Should execute in the given workdir");
                }
            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        @Order(8)
        void testCommandExecutorInvalidCommand() {
            ProcessCommandExecutor executor = new ProcessCommandExecutor(5000);

            assertThrows(IOException.class, () -> {
                executor.execute(""); // Empty command should throw
            });
        }

        @Test
        @Order(9)
        void testUltraQAWithCommandExecutor() throws IOException {
            Path tempDir = Files.createTempDirectory("ultraqa-exec");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                ProcessCommandExecutor exec = new ProcessCommandExecutor(5000);

                // Create UltraQA with CommandExecutor
                UltraQAStrategy strategy = new UltraQAStrategy(
                        Arrays.asList(QualityGate.build(), QualityGate.test()),
                        false, 10, UltraQAStrategy.UltraQAGoalType.TESTS,
                        null, false, exec);

                assertEquals(UltraQAStrategy.UltraQAGoalType.TESTS, strategy.getGoalType());
                assertNotNull(strategy.getGates());
                assertEquals(2, strategy.getGates().size());
            } finally {
                deleteDirectory(tempDir);
            }
        }
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
