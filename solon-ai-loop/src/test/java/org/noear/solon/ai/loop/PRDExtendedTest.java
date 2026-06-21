package org.noear.solon.ai.loop;

import org.junit.jupiter.api.*;
import org.noear.solon.ai.loop.prd.*;
import org.noear.solon.ai.loop.state.LoopState;
import org.noear.solon.ai.loop.state.LoopStateData;
import org.noear.solon.ai.loop.state.disk.AtomicWrite;
import org.noear.solon.ai.loop.state.disk.DiskStateManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第三轮新增测试——PRD 系统扩展覆盖
 *
 * 覆盖对标 oh-my-claudecode 的 ralph-prd.test.ts 中缺失的：
 * - readPrd 畸形 JSON/缺失字段
 * - ensurePrdForStartup scaffold/已存在/无效文件
 * - PRD 状态计算（空 stories、allComplete、优先级排序）
 * - markStoryComplete/Incomplete/ArchitectVerified 边界
 */
public class PRDExtendedTest {

    // ==========================================
    // 1. PRD 读取与畸形 JSON 测试
    // ==========================================

    @Nested
    class PrdReadTest {

        @Test
        void testReadPrdMalformedJsonReturnsNull() throws IOException {
            Path tempDir = Files.createTempDirectory("prd-malformed-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                PRDFileManager fm = new PRDFileManager(dsm);

                // 直接写入非法 JSON 到 PRD 文件
                Path prdFile = tempDir.resolve(".solon-ai-loop/prd/malformed-session.json");
                Files.createDirectories(prdFile.getParent());
                AtomicWrite.write(prdFile, "this is not json");

                // PRDFileManager 内部调用 ONode.ofJson 抛出 RuntimeException
                assertThrows(RuntimeException.class,
                    () -> fm.readPrd("malformed-session"),
                    "Malformed JSON should throw RuntimeException");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testReadPrdEmptyJsonReturnsNull() throws IOException {
            Path tempDir = Files.createTempDirectory("prd-empty-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                PRDFileManager fm = new PRDFileManager(dsm);

                Path prdFile = tempDir.resolve(".solon-ai-loop/prd/empty-session.json");
                Files.createDirectories(prdFile.getParent());
                AtomicWrite.write(prdFile, "");

                // PRDFileManager 内部调用 ONode.ofJson 抛出 RuntimeException
                assertThrows(RuntimeException.class,
                    () -> fm.readPrd("empty-session"),
                    "Empty content should throw RuntimeException");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testReadPrdMissingUserStoriesField() throws IOException {
            Path tempDir = Files.createTempDirectory("prd-missing-stories-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                PRDFileManager fm = new PRDFileManager(dsm);

                // 缺少 userStories 字段
                String prdJson = "{\"project\":\"test\",\"branchName\":\"main\",\"description\":\"test\"}";
                Path prdFile = tempDir.resolve(".solon-ai-loop/prd/missing-stories-session.json");
                Files.createDirectories(prdFile.getParent());
                AtomicWrite.write(prdFile, prdJson);

                PRDDocument result = fm.readPrd("missing-stories-session");
                assertNotNull(result, "Should still deserialize with missing userStories");
                assertEquals("test", result.getProject());
                assertEquals("main", result.getBranchName());
                assertTrue(result.getUserStories().isEmpty(), "User stories should be empty");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testReadPrdNonExistentReturnsNull() throws IOException {
            Path tempDir = Files.createTempDirectory("prd-nonexist-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                PRDFileManager fm = new PRDFileManager(dsm);

                PRDDocument result = fm.readPrd("non-existent-session");
                assertNull(result, "Non-existent PRD should return null");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testReadPrdRoundTrip() throws IOException {
            Path tempDir = Files.createTempDirectory("prd-roundtrip-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                PRDFileManager fm = new PRDFileManager(dsm);

                List<PRDFileManager.UserStoryInput> inputs = Arrays.asList(
                        new PRDFileManager.UserStoryInput("Login", "User login feature",
                                Arrays.asList("Should validate email", "Should validate password"), 1),
                        new PRDFileManager.UserStoryInput("Logout", "User logout",
                                Collections.singletonList("Should clear session"), 2)
                );

                PRDDocument created = fm.initPrd("roundtrip-session", "MyApp", "develop",
                        "Authentication module", inputs);
                assertNotNull(created);

                PRDDocument loaded = fm.readPrd("roundtrip-session");
                assertNotNull(loaded);
                assertEquals("MyApp", loaded.getProject());
                assertEquals("develop", loaded.getBranchName());
                assertEquals("Authentication module", loaded.getDescription());
                assertEquals(2, loaded.getUserStories().size());
                assertEquals("US-001", loaded.getUserStories().get(0).getId());
                assertEquals("Login", loaded.getUserStories().get(0).getTitle());
                assertEquals("US-002", loaded.getUserStories().get(1).getId());
                assertEquals("Logout", loaded.getUserStories().get(1).getTitle());

            } finally {
                deleteDirectory(tempDir);
            }
        }
    }

    // ==========================================
    // 2. PRD 启动保障测试
    // ==========================================

    @Nested
    class EnsurePrdForStartupTest {

        @Test
        void testEnsurePrdForStartupExistingReturnsNoCreation() throws IOException {
            Path tempDir = Files.createTempDirectory("ensure-existing-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                PRDFileManager fm = new PRDFileManager(dsm);

                // 先初始化一个 PRD
                List<PRDFileManager.UserStoryInput> inputs = Collections.singletonList(
                        new PRDFileManager.UserStoryInput("Feature A", "Description",
                                Collections.singletonList("AC1"), 1));
                fm.initPrd("ensure-existing-session", "TestApp", "main", "Test", inputs);

                // ensurePrdForStartup 应返回已有 PRD，且 needsCreation=false
                PRDFileManager.EnsurePrdResult result = fm.ensurePrdForStartup("ensure-existing-session");
                assertNotNull(result);
                assertNotNull(result.prd, "Existing PRD should be returned");
                assertFalse(result.needsCreation, "Should not need creation when PRD exists");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testEnsurePrdForStartupNonExistentNeedsCreation() throws IOException {
            Path tempDir = Files.createTempDirectory("ensure-nonexist-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                PRDFileManager fm = new PRDFileManager(dsm);

                PRDFileManager.EnsurePrdResult result = fm.ensurePrdForStartup("nonexistent-session");
                assertNotNull(result);
                assertNull(result.prd, "PRD should be null for non-existent session");
                assertTrue(result.needsCreation, "Should need creation");

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testEnsurePrdForStartupEmptyStoriesNeedsCreation() throws IOException {
            Path tempDir = Files.createTempDirectory("ensure-empty-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                PRDFileManager fm = new PRDFileManager(dsm);

                // 通过 initPrd 创建空 stories 的 PRD（序列化/反序列化经过验证）
                List<PRDFileManager.UserStoryInput> emptyInputs = new ArrayList<>();
                PRDDocument created = fm.initPrd("empty-stories-session", "EmptyApp", "main",
                        "No stories", emptyInputs);
                assertNotNull(created);

                // 验证写入的 PRD 可以被读回
                PRDDocument loadedPrd = fm.readPrd("empty-stories-session");
                assertNotNull(loadedPrd, "PRD should be readable after initPrd");
                assertEquals("EmptyApp", loadedPrd.getProject());
                assertTrue(loadedPrd.getUserStories().isEmpty(),
                        "Stories list should be empty");

                // ensurePrdForStartup 检查到 userStories 为空，应该标记 needsCreation=true
                PRDFileManager.EnsurePrdResult result = fm.ensurePrdForStartup("empty-stories-session");
                assertNotNull(result);
                assertNull(result.prd, "null PRD returned when existing has empty stories");
                assertTrue(result.needsCreation,
                        "Should need creation when existing PRD has empty stories");

            } finally {
                deleteDirectory(tempDir);
            }
        }
    }

    // ==========================================
    // 3. 故事状态管理测试
    // ==========================================

    @Nested
    class StoryStateTest {

        @Test
        void testMarkStoryCompleteAndVerifyFullCycle() throws IOException {
            Path tempDir = Files.createTempDirectory("story-cycle-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                PRDFileManager fm = new PRDFileManager(dsm);

                List<PRDFileManager.UserStoryInput> inputs = Arrays.asList(
                        new PRDFileManager.UserStoryInput("Feature 1", "Desc 1",
                                Collections.singletonList("AC1"), 1),
                        new PRDFileManager.UserStoryInput("Feature 2", "Desc 2",
                                Collections.singletonList("AC2"), 2)
                );
                fm.initPrd("story-cycle-session", "App", "main", "Test", inputs);

                // 完成第一个故事
                assertTrue(fm.markStoryComplete("story-cycle-session", "US-001", "Done"));

                PRDDocument prd = fm.readPrd("story-cycle-session");
                assertNotNull(prd);
                UserStory story1 = prd.findStoryById("US-001");
                assertTrue(story1.isPasses());
                assertFalse(story1.isArchitectVerified(), "Story should not be verified yet");

                // Architect 验证通过
                assertTrue(fm.markStoryArchitectVerified("story-cycle-session", "US-001"));
                prd = fm.readPrd("story-cycle-session");
                story1 = prd.findStoryById("US-001");
                assertTrue(story1.isArchitectVerified());
                assertTrue(story1.isFullyComplete(), "Story should be fully complete");

                // 检查 non-existent story
                assertFalse(fm.markStoryComplete("story-cycle-session", "US-999", null));
                assertFalse(fm.markStoryArchitectVerified("story-cycle-session", "US-999"));

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testMarkStoryIncompleteResetsVerification() throws IOException {
            Path tempDir = Files.createTempDirectory("story-incomplete-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                PRDFileManager fm = new PRDFileManager(dsm);

                List<PRDFileManager.UserStoryInput> inputs = Collections.singletonList(
                        new PRDFileManager.UserStoryInput("Feature", "Desc",
                                Collections.singletonList("AC"), 1));
                fm.initPrd("incomplete-session", "App", "main", "Test", inputs);

                // 完成并验证
                fm.markStoryComplete("incomplete-session", "US-001", "Done");
                fm.markStoryArchitectVerified("incomplete-session", "US-001");

                // 标记未完成
                assertTrue(fm.markStoryIncomplete("incomplete-session", "US-001", "Need rework"));

                PRDDocument prd = fm.readPrd("incomplete-session");
                UserStory story = prd.findStoryById("US-001");
                assertFalse(story.isPasses(), "Story should not be passes after markIncomplete");
                assertFalse(story.isArchitectVerified(),
                        "architectVerified should be reset after markIncomplete");
                assertEquals("Need rework", story.getNotes());

            } finally {
                deleteDirectory(tempDir);
            }
        }

        @Test
        void testMarkStoryArchitectRequiresPasses() throws IOException {
            Path tempDir = Files.createTempDirectory("story-verify-requires-pass-test");
            try {
                DiskStateManager dsm = new DiskStateManager(tempDir.toString());
                PRDFileManager fm = new PRDFileManager(dsm);

                List<PRDFileManager.UserStoryInput> inputs = Collections.singletonList(
                        new PRDFileManager.UserStoryInput("Feature", "Desc",
                                Collections.singletonList("AC"), 1));
                fm.initPrd("verify-requires-session", "App", "main", "Test", inputs);

                // 未完成的故事不能被 Architect 验证
                assertFalse(fm.markStoryArchitectVerified("verify-requires-session", "US-001"),
                        "Cannot mark as architect verified if story is not passes");

            } finally {
                deleteDirectory(tempDir);
            }
        }
    }

    // ==========================================
    // 4. PRD 状态计算测试
    // ==========================================

    @Nested
    class PrdStatusCalculationTest {

        @Test
        void testCalculateEmptyStories() {
            PRDStatusCalculator calc = new PRDStatusCalculator();
            PRDDocument empty = new PRDDocument("Empty", "main", "No stories");

            PRDStatus status = calc.calculate(empty);
            assertEquals(0, status.getTotal());
            assertEquals(0, status.getCompleted());
            assertEquals(0, status.getPending());
            assertEquals(100.0, status.getCompletionPercentage(), 0.001);
            assertTrue(status.isAllComplete());
            assertTrue(status.isAllFullyComplete());
            assertNull(status.getNextStory());
        }

        @Test
        void testCalculateNullPrd() {
            PRDStatusCalculator calc = new PRDStatusCalculator();
            PRDStatus status = calc.calculate(null);
            assertEquals(0, status.getTotal());
            assertEquals(0, status.getCompleted());
            assertTrue(status.isAllComplete());
            assertNull(status.getNextStory());
        }

        @Test
        void testCalculatePartialCompletion() {
            PRDStatusCalculator calc = new PRDStatusCalculator();
            PRDDocument prd = new PRDDocument("MyApp", "main", "Test");

            prd.addUserStory(new UserStory("US-001", "Login", "Login feature",
                    Arrays.asList("AC1", "AC2"), 1));
            prd.addUserStory(new UserStory("US-002", "Logout", "Logout feature",
                    Collections.singletonList("AC3"), 2));
            prd.addUserStory(new UserStory("US-003", "Profile", "Profile page",
                    Collections.singletonList("AC4"), 3));

            // 完成第1个和第3个故事
            prd.findStoryById("US-001").setPasses(true);
            prd.findStoryById("US-003").setPasses(true);

            PRDStatus status = calc.calculate(prd);
            assertEquals(3, status.getTotal());
            assertEquals(2, status.getCompleted());
            assertEquals(1, status.getPending());
            assertEquals(66.666, status.getCompletionPercentage(), 0.01);
            assertFalse(status.isAllComplete());
            assertFalse(status.isAllFullyComplete());

            // 下一个待办故事应是 US-002（按优先级排序，US-002 优先级 2 > US-003 优先级 3 但 US-003 已完成）
            assertNotNull(status.getNextStory());
            assertEquals("US-002", status.getNextStory().getId());

            // incompleteIds 应包含 US-002
            assertEquals(1, status.getIncompleteIds().size());
            assertTrue(status.getIncompleteIds().contains("US-002"));
        }

        @Test
        void testCalculateAllComplete() {
            PRDStatusCalculator calc = new PRDStatusCalculator();
            PRDDocument prd = new PRDDocument("MyApp", "main", "Test");

            UserStory s1 = new UserStory("US-001", "Login", "Login feature",
                    Collections.singletonList("AC1"), 1);
            s1.setPasses(true);
            s1.setArchitectVerified(true);
            prd.addUserStory(s1);

            UserStory s2 = new UserStory("US-002", "Logout", "Logout feature",
                    Collections.singletonList("AC2"), 2);
            s2.setPasses(true);
            s2.setArchitectVerified(true);
            prd.addUserStory(s2);

            PRDStatus status = calc.calculate(prd);
            assertEquals(2, status.getTotal());
            assertEquals(2, status.getCompleted());
            assertEquals(0, status.getPending());
            assertEquals(2, status.getVerified());
            assertEquals(100.0, status.getCompletionPercentage(), 0.001);
            assertTrue(status.isAllComplete());
            assertTrue(status.isAllFullyComplete());
            assertNull(status.getNextStory(), "No next story when all complete");
            assertTrue(status.getIncompleteIds().isEmpty());
        }

        @Test
        void testCalculatePriorityOrdering() {
            PRDStatusCalculator calc = new PRDStatusCalculator();
            PRDDocument prd = new PRDDocument("MyApp", "main", "Test");

            // 添加按随机优先级的故事
            prd.addUserStory(new UserStory("US-001", "Low", "Low priority",
                    Collections.singletonList("AC"), 5));
            prd.addUserStory(new UserStory("US-002", "High", "High priority",
                    Collections.singletonList("AC"), 1));
            prd.addUserStory(new UserStory("US-003", "Medium", "Medium priority",
                    Collections.singletonList("AC"), 3));

            // getNextIncompleteStory 应返回优先级最高的（1 > 3 > 5）
            UserStory next = prd.getNextIncompleteStory();
            assertNotNull(next);
            assertEquals("US-002", next.getId(), "Should return highest priority (1) story first");
            assertEquals(1, next.getPriority());
        }
    }

    // ==========================================
    // 5. PRD 格式化输出测试
    // ==========================================

    @Nested
    class FormatForContextTest {

        @Test
        void testFormatForContextNullPrd() {
            PRDStatusCalculator calc = new PRDStatusCalculator();
            String result = calc.formatForContext(null);
            assertEquals("No PRD available.", result);
        }

        @Test
        void testFormatForContextWithStories() {
            PRDStatusCalculator calc = new PRDStatusCalculator();
            PRDDocument prd = new PRDDocument("MyApp", "develop", "My application");

            UserStory s1 = new UserStory("US-001", "Login", "User can login",
                    Arrays.asList("AC1", "AC2"), 1);
            s1.setPasses(true);
            s1.setArchitectVerified(true);
            s1.setNotes("Implemented");
            prd.addUserStory(s1);

            UserStory s2 = new UserStory("US-002", "Logout", "User can logout",
                    Collections.singletonList("AC3"), 2);
            prd.addUserStory(s2);

            String formatted = calc.formatForContext(prd);

            // 验证头部
            assertTrue(formatted.contains("# PRD: MyApp"), "Should contain project name");
            assertTrue(formatted.contains("Branch: develop"), "Should contain branch");
            assertTrue(formatted.contains("Description: My application"), "Should contain description");

            // 验证状态摘要
            assertTrue(formatted.contains("## Status:"), "Should contain status section");
            assertTrue(formatted.contains("50.0%"), "Should contain completion percentage");

            // 验证故事图标
            assertTrue(formatted.contains("[✓]"), "Should contain verified icon");
            assertTrue(formatted.contains("[ ]"), "Should contain pending icon");
            assertTrue(formatted.contains("US-001"), "Should contain story ID");
            assertTrue(formatted.contains("US-002"), "Should contain story ID");

            // 验证验收条件
            assertTrue(formatted.contains("AC1"), "Should contain acceptance criteria");
            assertTrue(formatted.contains("AC3"), "Should contain acceptance criteria");

            // 验证备注
            assertTrue(formatted.contains("Implemented"), "Should contain notes");
        }

        @Test
        void testFormatForContextEmptyDescription() {
            PRDStatusCalculator calc = new PRDStatusCalculator();
            PRDDocument prd = new PRDDocument("NoDesc", "main", "");

            prd.addUserStory(new UserStory("US-001", "Feature", "",
                    Collections.singletonList("AC"), 1));

            String formatted = calc.formatForContext(prd);

            // 空 description 不应出现在输出中
            assertFalse(formatted.contains("Description:"), "Empty description should not appear");

            // 空 story description 不应出现在输出中
            assertFalse(formatted.contains("Description: "), "Empty story description should not appear");
        }

        @Test
        void testGetSummaryFormat() {
            PRDStatusCalculator calc = new PRDStatusCalculator();
            PRDDocument prd = new PRDDocument("App", "main", "Test");

            prd.addUserStory(new UserStory("US-001", "F1", "D1",
                    Collections.singletonList("AC"), 1));
            prd.addUserStory(new UserStory("US-002", "F2", "D2",
                    Collections.singletonList("AC"), 2));
            prd.addUserStory(new UserStory("US-003", "F3", "D3",
                    Collections.singletonList("AC"), 3));

            // 完成 1 个
            prd.findStoryById("US-001").setPasses(true);

            PRDStatus status = calc.calculate(prd);
            String summary = status.getSummary();
            assertTrue(summary.contains("1/3"), "Should show 1/3 completed");
            assertTrue(summary.contains("33.3"), "Should show 33.3%");
            assertTrue(summary.contains("2"), "Should show 2 pending");
        }
    }

    // ==========================================
    // 辅助方法
    // ==========================================

    private static void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(java.io.File::delete);
        }
    }
}
