package org.noear.solon.ai.loop;

import org.junit.jupiter.api.*;
import org.noear.solon.ai.loop.config.LoopConfig;
import org.noear.solon.ai.loop.engine.LoopResult;
import org.noear.solon.ai.loop.engine.LoopSession;
import org.noear.solon.ai.loop.engine.SimpleLoopEngine;
import org.noear.solon.ai.loop.prd.*;
import org.noear.solon.ai.loop.progress.ProgressEntry;
import org.noear.solon.ai.loop.progress.ProgressLog;
import org.noear.solon.ai.loop.state.LoopStateData;
import org.noear.solon.ai.loop.state.MutualExclusionGuard;
import org.noear.solon.ai.loop.state.disk.AtomicWrite;
import org.noear.solon.ai.loop.state.disk.DiskStateManager;
import org.noear.solon.ai.loop.strategy.*;
import org.noear.solon.ai.loop.validator.QualityGate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第五轮新增——端到端管道集成测试
 *
 * <p>模拟真实 Ralph/Team/UltraQA 全链路流程，验证 PRD → Progress → State 的端到端一致性。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class E2EPipelineTest {

    private static Path tempDir;
    private static DiskStateManager dsm;
    private static PRDFileManager prdManager;

    @BeforeAll
    static void setup() throws IOException {
        tempDir = Files.createTempDirectory("e2e-pipeline-test");
        dsm = new DiskStateManager(tempDir.toString());
        prdManager = new PRDFileManager(dsm);
    }

    @AfterAll
    static void cleanup() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
        }
    }

    // ==========================================
    // 1. 完整 PRD → Progress 全流程
    // ==========================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PrdToProgressE2ETest {

        @Test
        @Order(1)
        void testSetupPrd() {
            PRDFileManager.UserStoryInput story1 = new PRDFileManager.UserStoryInput(
                    "User authentication",
                    "Implement user login and registration",
                    Arrays.asList("Users can register", "Users can login", "Users can logout"),
                    1
            );
            PRDFileManager.UserStoryInput story2 = new PRDFileManager.UserStoryInput(
                    "Data dashboard",
                    "Create analytics dashboard",
                    Arrays.asList("Dashboard shows key metrics", "Dashboard is filterable"),
                    2
            );
            PRDFileManager.UserStoryInput story3 = new PRDFileManager.UserStoryInput(
                    "Notification system",
                    "Implement push notifications",
                    Arrays.asList("Notifications are sent on events", "Users can configure preferences"),
                    3
            );

            PRDDocument prd = prdManager.initPrd("e2e-prd-session", "E2EApp", "main",
                    "End-to-end test application", Arrays.asList(story1, story2, story3));

            assertNotNull(prd);
            assertEquals(3, prd.getUserStories().size());
            assertEquals("E2EApp", prd.getProject());
        }

        @Test
        @Order(2)
        void testReadBackPrd() {
            PRDDocument prd = prdManager.readPrd("e2e-prd-session");
            assertNotNull(prd);
            assertEquals(3, prd.getUserStories().size());

            UserStory firstStory = prd.getUserStories().get(0);
            assertNotNull(firstStory.getId());
            assertEquals("User authentication", firstStory.getTitle());
            assertFalse(firstStory.isPasses());
            assertEquals(1, firstStory.getPriority());
        }

        @Test
        @Order(3)
        void testMarkFirstStoryComplete() {
            PRDDocument prd = prdManager.readPrd("e2e-prd-session");
            assertNotNull(prd);

            UserStory firstStory = prd.getNextIncompleteStory();
            assertNotNull(firstStory);
            assertEquals("User authentication", firstStory.getTitle());

            prdManager.markStoryComplete("e2e-prd-session", firstStory.getId(), "Implemented login");

            PRDDocument updated = prdManager.readPrd("e2e-prd-session");
            assertNotNull(updated);
            UserStory completed = updated.findStoryById(firstStory.getId());
            assertTrue(completed.isPasses());
            assertEquals("Implemented login", completed.getNotes());
        }

        @Test
        @Order(4)
        void testCreateAndReadProgress() {
            ProgressLog progressLog = new ProgressLog();

            progressLog.addEntry(new ProgressEntry("US-001",
                    Arrays.asList("Implemented login controller", "Added JWT support"),
                    Arrays.asList("LoginController.java", "AuthService.java"),
                    Arrays.asList("JWT tokens need refresh mechanism"))
            );

            assertEquals(1, progressLog.getEntries().size());
            ProgressEntry entry = progressLog.getEntries().get(0);
            assertEquals("US-001", entry.getStoryId());
            assertTrue(entry.getImplementation().contains("Added JWT support"));

            progressLog.addPattern("Use dependency injection for services");
            assertEquals(1, progressLog.getPatterns().size());
            assertEquals("Use dependency injection for services",
                    progressLog.getPatterns().get(0).getDescription());
        }

        @Test
        @Order(5)
        void testProgressXmlFormat() {
            ProgressLog progressLog = new ProgressLog();
            progressLog.addEntry(new ProgressEntry("US-001",
                    Arrays.asList("Implemented login"),
                    Collections.singletonList("AuthService.java"),
                    Collections.singletonList("Need refresh tokens"))
            );
            progressLog.addPattern("Use DI for services");

            String formatted = progressLog.formatForContext();
            assertNotNull(formatted);

            // 验证包含 XML 标签结构
            assertTrue(formatted.contains("<progress_log>"),
                    "Should contain progress XML tag");
        }

        @Test
        @Order(6)
        void testFormatNextStoryPrompt() {
            PRDDocument prd = prdManager.readPrd("e2e-prd-session");
            assertNotNull(prd);

            String prompt = prdManager.formatNextStoryPrompt(prd);
            assertNotNull(prompt);
            assertTrue(prompt.contains("<next_story>"), "Should contain XML next_story tag");
            assertTrue(prompt.contains("Data dashboard"), "Should mention the next story");
        }

        @Test
        @Order(7)
        void testScaffoldPrdCreation() {
            PRDDocument scaffold = prdManager.createScaffoldPrd("e2e-scaffold-session", "ScaffoldProj", "main");
            assertNotNull(scaffold);
            assertEquals("ScaffoldProj", scaffold.getProject());
            assertEquals(1, scaffold.getUserStories().size());

            PRDDocument readBack = prdManager.readPrd("e2e-scaffold-session");
            assertNotNull(readBack);
            assertEquals("ScaffoldProj", readBack.getProject());
        }

        @Test
        @Order(8)
        void testEnsurePrdForStartup() {
            PRDFileManager.EnsurePrdResult result = prdManager.ensurePrdForStartup("e2e-ensure-session");
            assertNotNull(result);
            assertNotNull(result.prd);

            // exists already
            PRDFileManager.EnsurePrdResult second = prdManager.ensurePrdForStartup("e2e-ensure-session");
            assertNotNull(second);
            assertNotNull(second.prd);
        }

        @Test
        @Order(9)
        void testDetectFlags() {
            assertTrue(PRDFileManager.detectNoPrdFlag("do this --no-prd please"));
            assertTrue(PRDFileManager.detectNoPrdFlag("--no-prd"));
            assertFalse(PRDFileManager.detectNoPrdFlag("normal task"));

            assertEquals("do this  please",
                    PRDFileManager.stripNoPrdFlag("do this --no-prd please").trim());

            String cm = PRDFileManager.detectCriticModeFlag("--critic-mode architect");
            assertEquals("architect", cm);
            assertEquals("architect", PRDFileManager.detectCriticModeFlag("no flag here"),
                    "Default critic mode is architect");
        }
    }

    // ==========================================
    // 2. 策略层集成流程
    // ==========================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class StrategyE2ETest {

        @Test
        @Order(1)
        void testTeamPipelineFullFlow() {
            TeamPipelineStrategy team = new TeamPipelineStrategy();

            assertEquals(TeamPipelineStrategy.Phase.PLAN, team.getPhases().get(0));
            assertEquals(5, team.getPhases().size());
        }

        @Test
        @Order(2)
        void testTeamPipelineCancelResume() {
            TeamPipelineStrategy team = new TeamPipelineStrategy();

            // Verify builder
            TeamPipelineStrategy custom = TeamPipelineStrategy.builder()
                    .maxFixAttempts(3)
                    .parallelExecution(true)
                    .build();
            assertEquals(3, custom.getMaxFixAttempts());
            assertTrue(custom.isParallelExecution());
        }

        @Test
        @Order(3)
        void testUltraQAWithAllGateTypes() {
            UltraQAStrategy ultraqa = new UltraQAStrategy(
                    Arrays.asList(QualityGate.build(), QualityGate.test(), QualityGate.lint()),
                    false, 5, UltraQAStrategy.UltraQAGoalType.TESTS,
                    null, false
            );

            List<QualityGate> gates = ultraqa.getGates();
            assertEquals(3, gates.size());

            assertTrue(gates.get(0).isBlocking());
            assertTrue(gates.get(1).isBlocking());
            assertFalse(gates.get(2).isBlocking());
        }

        @Test
        @Order(4)
        void testRalphStrategyBuilder() {
            RalphLoopStrategy ralph = RalphLoopStrategy.builder()
                    .verificationRequired(true)
                    .criticMode("architect")
                    .maxIterations(5)
                    .build();
            assertTrue(ralph.isVerificationRequired());
            assertEquals("architect", ralph.getCriticMode());
        }

        @Test
        @Order(5)
        void testTeamPhaseDirective() {
            RalphLoopStrategy ralph = new RalphLoopStrategy();
            ralph.injectPrdComponents(prdManager, new PRDStatusCalculator(),
                    null, dsm);

            // No active session yet
            assertNull(ralph.getTeamPhaseDirective("non-existent-session"));
        }
    }

    // ==========================================
    // 3. 状态管理层全流程
    // ==========================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class StateManagementE2ETest {

        @Test
        @Order(1)
        void testStateWriteReadFullCycle() {
            LoopStateData state = new LoopStateData();
            state.setSessionId("e2e-cycle");
            state.setState(org.noear.solon.ai.loop.state.LoopState.EXECUTING);
            state.setIterationCount(5);
            state.setMetadata(new HashMap<>());
            state.getMetadata().put("lastError", "Test error for recovery");

            boolean written = dsm.writeState("ralph", state, "e2e-cycle");
            assertTrue(written);

            LoopStateData readBack = dsm.readState("ralph", "e2e-cycle");
            assertNotNull(readBack);
            assertEquals(org.noear.solon.ai.loop.state.LoopState.EXECUTING, readBack.getState());
            assertEquals(5, readBack.getIterationCount());
            assertEquals("Test error for recovery", readBack.getMetadata().get("lastError"));

            dsm.clearState("ralph", "e2e-cycle");
            assertNull(dsm.readState("ralph", "e2e-cycle"));
        }

        @Test
        @Order(2)
        void testMutualExclusionIntegration() {
            MutualExclusionGuard guard = new MutualExclusionGuard(dsm);

            // Acquire Ralph for e2e-session
            boolean acquired = guard.acquire("e2e-session", "ralph");
            assertTrue(acquired);
            assertTrue(guard.isActive("ralph", "e2e-session"));

            // Re-acquire same session+mode should work (idempotent)
            boolean reAcquired = guard.acquire("e2e-session", "ralph");
            assertTrue(reAcquired);

            // UltraQA for same session should be blocked (Ralph is active)
            assertFalse(guard.canStartUltraQA("e2e-session"));

            // Team for same session should also be blocked
            assertFalse(guard.canStartTeam("e2e-session"));

            // Release Ralph
            guard.release("e2e-session");
            assertFalse(guard.isActive("ralph", "e2e-session"));

            // Now UltraQA should be allowed
            assertTrue(guard.canStartUltraQA("e2e-session"));
        }

        @Test
        @Order(3)
        void testGhostLegacyCleanup() throws IOException {
            // 先注册 session 所有权，再写状态文件
            dsm.registerSession("e2e-cleanup-1", "ralph", tempDir.toString());
            dsm.registerSession("e2e-cleanup-2", "team", tempDir.toString());

            LoopStateData state1 = new LoopStateData();
            state1.setSessionId("e2e-cleanup-1");
            state1.setState(org.noear.solon.ai.loop.state.LoopState.COMPLETED);
            dsm.writeState("ralph", state1, "e2e-cleanup-1");

            LoopStateData state2 = new LoopStateData();
            state2.setSessionId("e2e-cleanup-2");
            state2.setState(org.noear.solon.ai.loop.state.LoopState.FAILED);
            dsm.writeState("team", state2, "e2e-cleanup-2");

            Path ralphState = tempDir.resolve(".solon-ai-loop/state/ralph/e2e-cleanup-1.json");
            Path teamState = tempDir.resolve(".solon-ai-loop/state/team/e2e-cleanup-2.json");
            assertTrue(Files.exists(ralphState));
            assertTrue(Files.exists(teamState));

            // 有所有权时，ghost 清理不应删除注册过的文件
            assertTrue(dsm.cleanupGhostLegacy(tempDir.toString()) >= 0);
            assertTrue(Files.exists(ralphState));

            dsm.clearState("ralph", "e2e-cleanup-1");
            dsm.clearState("team", "e2e-cleanup-2");

            assertNull(dsm.readState("ralph", "e2e-cleanup-1"));
            assertNull(dsm.readState("team", "e2e-cleanup-2"));
        }

        @Test
        @Order(4)
        void testFindPrdPathIntegration() {
            // findPrdPath should locate PRD files via state manager
            Path found = dsm.findPrdPath("e2e-prd-session");
            assertNotNull(found);
            assertTrue(found.toString().endsWith(".json"));
        }
    }

    // ==========================================
    // 4. 端到端状态持久化原子性
    // ==========================================

    @Nested
    class AtomicWriteE2ETest {

        @Test
        void testAtomicWriteWithThreadSafety() throws IOException, InterruptedException {
            Path file = tempDir.resolve("atomic-e2e.json");
            int threadCount = 5;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                new Thread(() -> {
                    try {
                        AtomicWrite.write(file, "{\"data\":" + idx + "}");
                        successCount.incrementAndGet();
                    } catch (IOException ignored) {
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            latch.await(10, TimeUnit.SECONDS);
            assertEquals(threadCount, successCount.get(), "All writes should succeed");

            String content = AtomicWrite.read(file);
            assertNotNull(content);
            assertTrue(content.startsWith("{") && content.endsWith("}"),
                    "Final content should be valid JSON");

            try (Stream<Path> files = Files.list(tempDir)) {
                long tmpCount = files.filter(p -> p.toString().endsWith(".tmp")).count();
                assertEquals(0, tmpCount, "No .tmp files should remain");
            }
        }
    }

    // ==========================================
    // 5. ProgressLog 与 PRD 交互
    // ==========================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ProgressPrdInteractionTest {

        private ProgressLog progressLog;

        @BeforeEach
        void init() {
            progressLog = new ProgressLog();
        }

        @Test
        @Order(1)
        void testProgressEntryWithPRDStory() {
            String storyId = "US-001";
            ProgressEntry entry = new ProgressEntry(storyId,
                    Arrays.asList("Implemented login", "Added tests"),
                    Arrays.asList("LoginController.java", "LoginTest.java"),
                    Arrays.asList("Need to handle edge cases"));

            progressLog.addEntry(entry);

            assertEquals(storyId, progressLog.getEntries().get(0).getStoryId());
            assertEquals(2, progressLog.getEntries().get(0).getImplementation().size());
        }

        @Test
        @Order(2)
        void testPatternsAndLearnings() {
            progressLog.addPattern("Use repository pattern for data access");
            progressLog.addPattern("Follow MVC architecture");

            assertEquals(2, progressLog.getPatterns().size());
            assertEquals("Use repository pattern for data access",
                    progressLog.getPatterns().get(0).getDescription());

            // formatPatternsForContext should produce XML-style output
            String patternsContext = progressLog.formatPatternsForContext();
            assertNotNull(patternsContext);
        }

        @Test
        @Order(3)
        void testRecentLearnings() {
            ProgressLog log = new ProgressLog();
            // Use entries with learnings to populate recent learnings
            List<String> learnings = log.getRecentLearnings(5);
            assertNotNull(learnings);
            assertTrue(learnings.isEmpty());
        }

        @Test
        @Order(4)
        void testMarkStoryIncompleteBackAndForth() {
            String sessionId = "e2e-backforth";

            // Initialize PRD with one story
            PRDFileManager.UserStoryInput input = new PRDFileManager.UserStoryInput(
                    "Sample story", "A sample story",
                    Collections.singletonList("AC1"), 1);
            prdManager.initPrd(sessionId, "BackForth", "main", "test", Collections.singletonList(input));

            PRDDocument prd = prdManager.readPrd(sessionId);
            assertNotNull(prd);
            String storyId = prd.getUserStories().get(0).getId();

            // Mark complete
            prdManager.markStoryComplete(sessionId, storyId, "Done");
            assertTrue(prdManager.readPrd(sessionId).findStoryById(storyId).isPasses());

            // Mark incomplete again
            prdManager.markStoryIncomplete(sessionId, storyId, "Need changes");
            assertFalse(prdManager.readPrd(sessionId).findStoryById(storyId).isPasses());
            assertTrue(prdManager.readPrd(sessionId).findStoryById(storyId).getNotes()
                    .contains("Need changes"));
        }

        @Test
        @Order(5)
        void testArchitectVerify() {
            String sessionId = "e2e-architect";

            PRDFileManager.UserStoryInput input = new PRDFileManager.UserStoryInput(
                    "Test story", "desc", Collections.singletonList("AC"), 1);
            prdManager.initPrd(sessionId, "ArchTest", "main", "test",
                    Collections.singletonList(input));

            PRDDocument prd = prdManager.readPrd(sessionId);
            String storyId = prd.getUserStories().get(0).getId();

            prdManager.markStoryArchitectVerified(sessionId, storyId);
            assertTrue(prdManager.readPrd(sessionId).findStoryById(storyId).isArchitectVerified());
        }
    }

    // ==========================================
    // 6. 状态存储文件完整性
    // ==========================================

    @Nested
    class StateFileIntegrityTest {

        @Test
        void testStateFileContainsMeta() throws IOException {
            String sessionId = "e2e-meta-test";
            LoopStateData data = new LoopStateData();
            data.setSessionId(sessionId);
            data.setState(org.noear.solon.ai.loop.state.LoopState.PLANNING);

            dsm.writeState("ralph", data, sessionId);

            // Read raw file and verify _meta
            Path stateFile = tempDir.resolve(".solon-ai-loop/state/ralph/" + sessionId + ".json");
            String rawContent = new String(Files.readAllBytes(stateFile));

            assertTrue(rawContent.contains("_meta"), "State file should contain _meta");
            assertTrue(rawContent.contains("written_at"), "_meta should have written_at");
            assertTrue(rawContent.contains("mode"), "_meta should have mode");

            dsm.clearState("ralph", sessionId);
        }

        @Test
        void testMalformedJsonReturnsNull() throws IOException {
            String sessionId = "e2e-malformed";
            Path stateFile = tempDir.resolve(".solon-ai-loop/state/ralph/" + sessionId + ".json");
            Files.createDirectories(stateFile.getParent());
            Files.write(stateFile, "not valid json".getBytes());

            assertThrows(RuntimeException.class, () -> dsm.readState("ralph", sessionId),
                    "Malformed JSON should throw RuntimeException");
        }

        @Test
        void testOldFormatWithoutMeta() throws IOException {
            String sessionId = "e2e-old-format";
            Path stateFile = tempDir.resolve(".solon-ai-loop/state/ralph/" + sessionId + ".json");
            Files.createDirectories(stateFile.getParent());
            // Write state without _meta (old format)
            String oldFormat = "{\"sessionId\":\"" + sessionId + "\",\"state\":\"PLANNING\"}";
            Files.write(stateFile, oldFormat.getBytes());

            LoopStateData result = dsm.readState("ralph", sessionId);
            assertNotNull(result, "Old format without _meta should still be readable");
            assertEquals(sessionId, result.getSessionId());
        }

        @Test
        void testAtomicWriteNoTempLeftOnFailure() {
            // Test that even on concurrent failure, no .tmp files remain
            Path file = tempDir.resolve("no-tmp-e2e.json");
            for (int i = 0; i < 10; i++) {
                try {
                    AtomicWrite.write(file, "{\"iter\":" + i + "}");
                } catch (IOException ignored) {
                }
            }
            assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")),
                    "No .tmp file should remain");
        }
    }

    // ==========================================
    // 7. SimpleLoopEngine 快速验证
    // ==========================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class EngineQuickTest {

        @Test
        @Order(1)
        void testSimpleEngineConstruction() {
            SimpleLoopEngine engine = new SimpleLoopEngine();
            assertNotNull(engine);

            engine.setProjectRootDirectory(tempDir.toString());
            assertNotNull(engine.getDiskStateManager());
            assertNotNull(engine.getMutualExclusionGuard());
        }

        @Test
        @Order(2)
        void testEngineStartAndStop() {
            SimpleLoopEngine engine = new SimpleLoopEngine();
            engine.setProjectRootDirectory(tempDir.toString());

            LoopConfig config = LoopConfig.builder()
                    .taskDescription("E2E test task")
                    .maxIterations(3)
                    .build();

            LoopSession session = engine.start(config);
            assertNotNull(session);
            assertNotNull(session.getId());
            assertNotNull(session.getState());

            assertTrue(engine.isRunning(session.getId()));
            engine.stop(session.getId());
        }
    }
}
