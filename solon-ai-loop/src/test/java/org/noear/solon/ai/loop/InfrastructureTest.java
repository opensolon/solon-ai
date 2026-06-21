package org.noear.solon.ai.loop;

import org.junit.jupiter.api.*;
import org.noear.solon.ai.loop.state.disk.AtomicWrite;
import org.noear.solon.ai.loop.state.disk.DiskStateManager;
import org.noear.solon.ai.loop.state.LoopState;
import org.noear.solon.ai.loop.state.LoopStateData;
import org.noear.solon.ai.loop.state.MutualExclusionGuard;
import org.noear.solon.ai.loop.state.SessionIdentityValidator;
import org.noear.solon.ai.loop.prd.*;
import org.noear.solon.ai.loop.progress.*;
import org.noear.solon.ai.loop.validator.verify.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基础设施与核心组件测试。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class InfrastructureTest {

    private static Path tempDir;
    private static DiskStateManager diskStateManager;

    @BeforeAll
    static void setup() throws IOException {
        tempDir = Files.createTempDirectory("solon-loop-test-");
        diskStateManager = new DiskStateManager(tempDir.toString());
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
    }

    // ===== 1. AtomicWrite 测试 =====

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class AtomicWriteTest {

        @Test
        @Order(1)
        void testWriteAndRead() throws IOException {
            Path file = tempDir.resolve("test-atomic.txt");
            AtomicWrite.write(file, "Hello, World!");
            assertTrue(AtomicWrite.exists(file));

            String content = AtomicWrite.read(file);
            assertEquals("Hello, World!", content);
        }

        @Test
        @Order(2)
        void testOverwrite() throws IOException {
            Path file = tempDir.resolve("test-atomic.txt");
            AtomicWrite.write(file, "Overwritten content");
            String content = AtomicWrite.read(file);
            assertEquals("Overwritten content", content);
        }

        @Test
        @Order(3)
        void testDelete() throws IOException {
            Path file = tempDir.resolve("test-delete.txt");
            AtomicWrite.write(file, "To be deleted");
            assertTrue(AtomicWrite.exists(file));

            boolean deleted = AtomicWrite.delete(file);
            assertTrue(deleted);
            assertFalse(AtomicWrite.exists(file));
        }

        @Test
        @Order(4)
        void testEmptyContent() throws IOException {
            Path file = tempDir.resolve("test-empty.txt");
            AtomicWrite.write(file, "");
            String content = AtomicWrite.read(file);
            assertEquals("", content);
        }

        @Test
        @Order(5)
        void testStaleTmpCleanup() throws IOException {
            Path staleTmp = tempDir.resolve("stale.tmp");
            Files.write(staleTmp, "stale".getBytes());
            assertTrue(Files.exists(staleTmp));

            AtomicWrite.cleanupStaleTmpFiles(tempDir);
            assertFalse(Files.exists(staleTmp));
        }
    }

    // ===== 2. DiskStateManager 测试 =====

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DiskStateManagerTest {

        private static final String SESSION_ID = "disk-session-001";
        private static final String MODE = "ralph";

        @Test
        @Order(1)
        void testWriteState() {
            LoopStateData state = new LoopStateData();
            state.setSessionId(SESSION_ID);
            state.setState(LoopState.EXECUTING);
            state.setIterationCount(5);

            boolean written = diskStateManager.writeState(MODE, state, SESSION_ID);
            assertTrue(written);
        }

        @Test
        @Order(2)
        void testReadState() {
            LoopStateData state = diskStateManager.readState(MODE, SESSION_ID);
            assertNotNull(state);
            assertEquals(SESSION_ID, state.getSessionId());
            assertEquals(LoopState.EXECUTING, state.getState());
            assertEquals(5, state.getIterationCount());
        }

        @Test
        @Order(3)
        void testHasState() {
            assertTrue(diskStateManager.hasState(MODE, SESSION_ID));
            assertFalse(diskStateManager.hasState(MODE, "non-existent"));
        }

        @Test
        @Order(4)
        void testSessionIsolation() {
            String sessionA = "session-a";
            String sessionB = "session-b";

            LoopStateData stateA = new LoopStateData();
            stateA.setSessionId(sessionA);
            stateA.setState(LoopState.EXECUTING);

            LoopStateData stateB = new LoopStateData();
            stateB.setSessionId(sessionB);
            stateB.setState(LoopState.PLANNING);

            diskStateManager.writeState(MODE, stateA, sessionA);
            diskStateManager.writeState(MODE, stateB, sessionB);

            LoopStateData readA = diskStateManager.readState(MODE, sessionA);
            LoopStateData readB = diskStateManager.readState(MODE, sessionB);

            assertNotNull(readA);
            assertNotNull(readB);
            assertEquals(sessionA, readA.getSessionId());
            assertEquals(sessionB, readB.getSessionId());
        }

        @Test
        @Order(5)
        void testClearState() {
            String clearSession = "session-to-clear";
            LoopStateData state = new LoopStateData();
            state.setSessionId(clearSession);
            diskStateManager.writeState(MODE, state, clearSession);
            assertTrue(diskStateManager.hasState(MODE, clearSession));

            diskStateManager.clearState(MODE, clearSession);
            assertFalse(diskStateManager.hasState(MODE, clearSession));
        }

        @Test
        @Order(6)
        void testPrdReadWrite() {
            String prdSession = "prd-session";

            boolean written = diskStateManager.writePrd("{\"project\":\"test-proj\"}", prdSession);
            assertTrue(written);

            String content = diskStateManager.readPrd(prdSession);
            assertNotNull(content);
            assertTrue(content.contains("test-proj"));
        }

        @Test
        @Order(7)
        void testProgressReadWrite() {
            String progSession = "prog-session";

            boolean written = diskStateManager.writeProgress("## Progress log", progSession);
            assertTrue(written);

            String content = diskStateManager.readProgress(progSession);
            assertNotNull(content);
            assertTrue(content.contains("Progress"));

            // 追加
            diskStateManager.appendProgress("New entry", progSession);
            content = diskStateManager.readProgress(progSession);
            assertTrue(content.contains("New entry"));
        }

        @Test
        @Order(8)
        void testSessionValidation() {
            diskStateManager.registerSession("valid-session", "ralph", "/usr/test/proj");

            assertTrue(diskStateManager.validateSession("valid-session", "ralph", "/usr/test/proj"));
            assertFalse(diskStateManager.validateSession("valid-session", "ultraqa", "/usr/test/proj"));
            assertFalse(diskStateManager.validateSession("valid-session", "ralph", "/usr/other/proj"));
            assertFalse(diskStateManager.validateSession("unknown", "ralph", "/usr/test/proj"));
        }
    }

    // ===== 3. MutualExclusionGuard 测试 =====

    @Nested
    class MutualExclusionTest {

        @Test
        void testRalphAndUltraqaCannotCoexist() {
            MutualExclusionGuard guard = new MutualExclusionGuard(diskStateManager);

            // Acquire Ralph
            boolean acquiredRalph = guard.acquire("ralph-session", "ralph");
            assertTrue(acquiredRalph);

            // UltraQA should fail
            boolean acquiredUltraQa = guard.acquire("ultraqa-session", "ultraqa");
            assertFalse(acquiredUltraQa);

            // Release Ralph
            guard.release("ralph-session");

            // UltraQA should now succeed
            acquiredUltraQa = guard.acquire("ultraqa-session", "ultraqa");
            assertTrue(acquiredUltraQa);
        }

        @Test
        void testTeamCanCoexist() {
            MutualExclusionGuard guard = new MutualExclusionGuard(diskStateManager);

            // Ralph acquired
            guard.acquire("ralph-s-1", "ralph");

            // Team should fail (Ralph is active)
            boolean teamAcquired = guard.acquire("team-s-1", "team");
            assertFalse(teamAcquired);

            guard.release("ralph-s-1");

            // Team should succeed now
            teamAcquired = guard.acquire("team-s-1", "team");
            assertTrue(teamAcquired);

            // UltraQA should fail (Team is active, but Ralph check is separate from Team check
            // Wait... actually the guard checks UltraQA only against Ralph.
            // Team only checks against Ralph and UltraQA. So with team active:
            boolean ralphAcquired = guard.acquire("ralph-s-2", "ralph");
            // Ralph checks only against UltraQA, not Team, so this should succeed
            assertTrue(ralphAcquired);
        }

        @Test
        void testForceRelease() {
            MutualExclusionGuard guard = new MutualExclusionGuard(diskStateManager);

            guard.acquire("session-fr", "ralph");
            assertTrue(guard.isActive("ralph", "session-fr"));

            // Force release
            boolean released = guard.forceRelease("ralph", "session-fr");
            assertTrue(released);
            assertFalse(guard.isActive("ralph", "session-fr"));
        }

        @Test
        void testNonExistentMode() {
            MutualExclusionGuard guard = new MutualExclusionGuard(diskStateManager);
            boolean acquired = guard.acquire("bad-session", "unknown-mode");
            assertFalse(acquired);
        }

        @Test
        void testMultipleSessions() {
            MutualExclusionGuard guard = new MutualExclusionGuard(diskStateManager);
            assertEquals(0, guard.getActiveCount());

            guard.acquire("s1", "ralph");
            assertEquals(1, guard.getActiveCount());

            guard.acquire("s2", "ralph");
            assertEquals(2, guard.getActiveCount());

            guard.release("s1");
            assertEquals(1, guard.getActiveCount());

            guard.release("s2");
            assertEquals(0, guard.getActiveCount());
        }
    }

    // ===== 4. SessionIdentityValidator 测试 =====

    @Nested
    class SessionIdentityTest {

        @Test
        void testIdentityValidation() {
            SessionIdentityValidator validator = new SessionIdentityValidator(diskStateManager);

            // Register session
            diskStateManager.registerSession("identity-session", "ralph", "/proj/alpha");

            SessionIdentityValidator.ValidationResult result =
                    validator.validate("identity-session", "ralph", "/proj/alpha");
            assertTrue(result.isValid());

            // Wrong mode
            result = validator.validate("identity-session", "ultraqa", "/proj/alpha");
            assertFalse(result.isValid());
        }

        @Test
        void testNullInputs() {
            SessionIdentityValidator validator = new SessionIdentityValidator(diskStateManager);

            assertFalse(validator.validate(null, "ralph", "/proj").isValid());
            assertFalse(validator.validate("sid", null, "/proj").isValid());
            assertFalse(validator.validate("sid", "ralph", null).isValid());
            assertFalse(validator.validate("", "ralph", "/proj").isValid());
        }

        @Test
        void testCanCreateSession() {
            SessionIdentityValidator validator = new SessionIdentityValidator(diskStateManager);

            SessionIdentityValidator.ValidationResult result =
                    validator.canCreateSession("new-session", "ralph", "/proj/new");
            assertTrue(result.isValid());
        }
    }

    // ===== 5. PRD 系统测试 =====

    @Nested
    class PRDTest {

        @Test
        void testUserStoryCreation() {
            UserStory story = new UserStory("US-001", "Login Feature", "User should be able to login");
            story.addAcceptanceCriteria("Email and password fields exist");
            story.addAcceptanceCriteria("Login button is clickable");
            story.setPriority(1);

            assertEquals("US-001", story.getId());
            assertEquals("Login Feature", story.getTitle());
            assertEquals(2, story.getAcceptanceCriteria().size());
            assertEquals(1, story.getPriority());
            assertFalse(story.isPasses());
            assertFalse(story.isArchitectVerified());
        }

        @Test
        void testUserStoryFullCycle() {
            UserStory story = new UserStory("US-002", "Logout", "User can logout");
            assertFalse(story.isFullyComplete());

            story.setPasses(true);
            assertFalse(story.isFullyComplete()); // not yet architect verified

            story.setArchitectVerified(true);
            assertTrue(story.isFullyComplete());
        }

        @Test
        void testPRDDocument() {
            PRDDocument prd = new PRDDocument("MyApp", "main", "A sample app");

            UserStory s1 = new UserStory("US-001", "Login", "Login feature", Arrays.asList("Email"), 1);
            UserStory s2 = new UserStory("US-002", "Logout", "Logout feature", Arrays.asList("Button"), 2);

            prd.addUserStory(s1);
            prd.addUserStory(s2);

            assertEquals(2, prd.getUserStories().size());
            assertFalse(prd.allStoriesCompleted());

            // Complete first story
            s1.setPasses(true);
            UserStory next = prd.getNextIncompleteStory();
            assertNotNull(next);
            assertEquals("US-002", next.getId());

            // Complete all
            s2.setPasses(true);
            assertTrue(prd.allStoriesCompleted());
        }

        @Test
        void testPRDStatusCalculator() {
            PRDStatusCalculator calculator = new PRDStatusCalculator();
            PRDDocument prd = new PRDDocument("Proj", "main", "desc");

            prd.addUserStory(new UserStory("US-001", "S1", "desc", Arrays.asList("A"), 1));
            prd.addUserStory(new UserStory("US-002", "S2", "desc", Arrays.asList("B"), 1));
            prd.addUserStory(new UserStory("US-003", "S3", "desc", Arrays.asList("C"), 1));

            PRDStatus status = calculator.calculate(prd);
            assertEquals(3, status.getTotal());
            assertEquals(0, status.getCompleted());
            assertFalse(status.isAllComplete());
            // 0/3*100 = 0%
            assertEquals(0.0, status.getCompletionPercentage(), 0.001);
            assertEquals(3, status.getPending());

            // Complete 2 stories
            prd.getUserStories().get(0).setPasses(true);
            prd.getUserStories().get(1).setPasses(true);

            status = calculator.calculate(prd);
            assertEquals(2, status.getCompleted());
            assertEquals(1, status.getPending());
            assertEquals(66.666, status.getCompletionPercentage(), 0.01);
            assertEquals("US-003", status.getNextStory().getId());
        }

        @Test
        void testPRDFileManager() {
            PRDFileManager fileManager = new PRDFileManager(diskStateManager);
            String sessionId = "prd-manager-test";

            // Initialize PRD
            PRDFileManager.UserStoryInput input1 =
                    new PRDFileManager.UserStoryInput("Feature A", "Implement A", Arrays.asList("AC1"), 1);
            PRDFileManager.UserStoryInput input2 =
                    new PRDFileManager.UserStoryInput("Feature B", "Implement B", Arrays.asList("AC2"), 2);

            PRDDocument prd = fileManager.initPrd(sessionId, "TestProj", "main",
                    "Test project", Arrays.asList(input1, input2));

            assertNotNull(prd);
            assertEquals("TestProj", prd.getProject());
            assertEquals(2, prd.getUserStories().size());

            // Read back
            PRDDocument readBack = fileManager.readPrd(sessionId);
            assertNotNull(readBack);
            assertEquals("TestProj", readBack.getProject());

            // Mark story complete
            boolean marked = fileManager.markStoryComplete(sessionId, "US-001", "Done!");
            assertTrue(marked);

            readBack = fileManager.readPrd(sessionId);
            assertTrue(readBack.findStoryById("US-001").isPasses());
            assertFalse(readBack.findStoryById("US-002").isPasses());

            // Architect verify
            fileManager.markStoryArchitectVerified(sessionId, "US-001");
            readBack = fileManager.readPrd(sessionId);
            assertTrue(readBack.findStoryById("US-001").isArchitectVerified());
        }

        @Test
        void testPRDStatusCalculatorFormat() {
            PRDStatusCalculator calculator = new PRDStatusCalculator();
            PRDDocument prd = new PRDDocument("App", "dev", "Sample app");
            prd.addUserStory(new UserStory("US-001", "Login", "Login feature",
                    Arrays.asList("Email field", "Password field"), 1));
            prd.getUserStories().get(0).setPasses(true);

            String context = calculator.formatForContext(prd);
            assertTrue(context.contains("PRD: App"));
            assertTrue(context.contains("Branch: dev"));
            assertTrue(context.contains("Login"));
            assertTrue(context.contains("US-001"));
        }
    }

    // ===== 6. 进度记忆系统测试 =====

    @Nested
    class ProgressTest {

        @Test
        void testProgressEntry() {
            ProgressEntry entry = new ProgressEntry("US-001");
            entry.addImplementation("Created UserService class");
            entry.addFileChanged("src/main/java/UserService.java");
            entry.addLearning("Use constructor injection for dependencies");

            String formatted = entry.format();
            assertTrue(formatted.contains("US-001"));
            assertTrue(formatted.contains("UserService"));
            assertTrue(formatted.contains("constructor injection"));
        }

        @Test
        void testProgressLog() {
            ProgressLog log = new ProgressLog();

            log.addPattern("Always use builder pattern for complex objects");

            ProgressEntry entry1 = new ProgressEntry("US-001",
                    Arrays.asList("Implemented login"), Arrays.asList("LoginController.java"),
                    Arrays.asList("Add validation"));
            log.addEntry(entry1);

            ProgressEntry entry2 = new ProgressEntry("US-002",
                    Arrays.asList("Implemented logout"), Arrays.asList("LogoutController.java"),
                    Arrays.asList("Check session before logout"));
            log.addEntry(entry2);

            assertEquals(1, log.getPatterns().size());
            assertEquals(2, log.getEntries().size());

            List<String> learnings = log.getRecentLearnings(1);
            assertEquals(1, learnings.size());
            assertEquals("Check session before logout", learnings.get(0));

            String context = log.formatForContext();
            assertTrue(context.contains("US-001"));
            assertTrue(context.contains("US-002"));
            assertTrue(context.contains("builder pattern"));
        }

        @Test
        void testProgressManager() {
            ProgressManager manager = new ProgressManager(diskStateManager);
            String sessionId = "progress-test";

            // Init
            boolean inited = manager.initProgress(sessionId);
            assertTrue(inited);

            // Append entry
            ProgressEntry entry = new ProgressEntry("US-001",
                    Arrays.asList("Work done"), Arrays.asList("File.java"),
                    Arrays.asList("Important lesson"));
            boolean appended = manager.appendProgress(sessionId, entry);
            assertTrue(appended);

            // Add pattern
            boolean patternAdded = manager.addPattern(sessionId, "Test patterns first");
            assertTrue(patternAdded);

            // Read back
            String context = manager.getProgressContext(sessionId);
            assertNotNull(context);
            assertTrue(context.contains("US-001"));
        }
    }

    // ===== 7. 验证状态机测试 =====

    @Nested
    class VerificationTest {

        @Test
        void testVerificationState() {
            assertEquals("PENDING", VerificationState.PENDING.name());
            assertFalse(VerificationState.PENDING.isTerminal());
            assertTrue(VerificationState.ARCHITECT_APPROVED.isTerminal());
            assertTrue(VerificationState.FAILED.isTerminal());

            assertTrue(VerificationState.IMPLEMENTED.canTransitionToReview());
            assertFalse(VerificationState.PENDING.canTransitionToReview());
        }

        @Test
        void testArchitectVerifier() {
            ArchitectVerifier verifier = new ArchitectVerifier();
            UserStory story = new UserStory("US-001", "Login", "Login feature");
            story.setPasses(true);
            story.addAcceptanceCriteria("Login");

            org.noear.solon.ai.loop.strategy.LoopContext context =
                    createTestContext("US-001 verification");

            ArchitectVerifier.VerificationResult result =
                    verifier.verify(story, "Login page works well", context);
            assertTrue(result.isPassed());

            // Story not implemented yet
            story.setPasses(false);
            result = verifier.verify(story, null, context);
            assertFalse(result.isPassed());

            // Null story
            result = verifier.verify(null, null, context);
            assertFalse(result.isPassed());
        }

        @Test
        void testCriticVerifier() {
            CriticVerifier critic = new CriticVerifier("architect");

            UserStory story = new UserStory("US-001", "Login", "Login feature");

            // Default mode review
            CriticVerifier.CriticResult result =
                    critic.review(story, "Login implementation", createTestContext("critic"));
            assertTrue(result.isApproved());

            // Code review mode
            CriticVerifier codeCritic = new CriticVerifier("codex");
            result = codeCritic.review(story, "Login without tests", createTestContext("codex"));
            assertFalse(result.isApproved());

            // With tests
            result = codeCritic.review(story, "Login with unit test", createTestContext("codex"));
            assertTrue(result.isApproved());
        }
    }

    // ===== 辅助方法 =====

    private static org.noear.solon.ai.loop.strategy.LoopContext createTestContext(String description) {
        return new org.noear.solon.ai.loop.strategy.LoopContext(
                "test-session", description,
                LoopState.IDLE, 0, 10,
                java.time.Instant.now(),
                new HashMap<>(), new ArrayList<>(), new HashMap<>()
        );
    }
}
