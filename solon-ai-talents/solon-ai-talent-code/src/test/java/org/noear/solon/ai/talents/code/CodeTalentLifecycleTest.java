package org.noear.solon.ai.talents.code;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeTalent 的生命周期：准入判定、缓存、挂载副作用、指令注入与 .gitignore 维护。
 */
public class CodeTalentLifecycleTest {

    private static void write(Path dir, String relative, String content) throws IOException {
        Path f = dir.resolve(relative);
        Files.createDirectories(f.getParent());
        Files.write(f, content.getBytes(StandardCharsets.UTF_8));
    }

    private static CodeTalent talentOf(Path root) {
        return new CodeTalent(root.toString(), ".soloncode");
    }

    private static Prompt promptOf(Path cwd) {
        return Prompt.of("hi").attrPut(CodeTalent.ATTR_CWD, cwd.toString());
    }

    private static Path codeMd(Path root) {
        return root.resolve(".soloncode/CODE.md");
    }

    // ---------- isSupported ----------

    @Test
    public void isSupported_falseForBareDir(@TempDir Path root) {
        assertFalse(talentOf(root).isSupported(promptOf(root)));
    }

    @Test
    public void isSupported_trueWhenCodeMdExists(@TempDir Path root) throws Exception {
        write(root, ".soloncode/CODE.md", "# 已有规范");

        assertTrue(talentOf(root).isSupported(promptOf(root)));
    }

    @Test
    public void isSupported_trueForRootHints(@TempDir Path root) throws Exception {
        for (String hint : new String[]{".git", ".github", ".gitee", "src", "lib"}) {
            Path dir = root.resolve("case-" + hint);
            Files.createDirectories(dir.resolve(hint));

            assertTrue(talentOf(dir).isSupported(promptOf(dir)), hint + " 应被视为项目线索");
        }
    }

    @Test
    public void isSupported_trueForRootMarker(@TempDir Path root) throws Exception {
        write(root, "go.mod", "module demo\n");

        assertTrue(talentOf(root).isSupported(promptOf(root)));
    }

    @Test
    public void isSupported_trueForDeepMarker(@TempDir Path root) throws Exception {
        write(root, "svc/api/pom.xml", "<project></project>");

        assertTrue(talentOf(root).isSupported(promptOf(root)), "深层构建标记也应触发准入");
    }

    @Test
    public void isSupported_ignoredDirsDoNotCount(@TempDir Path root) throws Exception {
        write(root, "node_modules/pkg/package.json", "{}");

        assertFalse(talentOf(root).isSupported(promptOf(root)), "依赖目录里的标记文件不算项目结构");
    }

    @Test
    public void isSupported_falseWhenWorkDirMissing() {
        CodeTalent talent = new CodeTalent(null, ".soloncode");

        assertFalse(talent.isSupported(Prompt.of("hi")), "工作目录未设置应判定为不适用，而非抛异常");
        assertFalse(talent.isSupported(null));
    }

    @Test
    public void isSupported_cwdOverridesWorkDir(@TempDir Path root) throws Exception {
        Path other = root.resolve("other");
        Files.createDirectories(other.resolve("src"));

        CodeTalent talent = new CodeTalent(root.toString(), ".soloncode");

        assertTrue(talent.isSupported(promptOf(other)));
        assertFalse(talent.isSupported(promptOf(root)), "root 自身没有线索（other 是普通子目录）");
    }

    @Test
    public void isSupported_resultIsCachedPerRoot(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project></project>");

        CodeTalent talent = talentOf(root);
        assertTrue(talent.isSupported(promptOf(root)));
        // 目录未变动：重复判定应命中缓存（不再重复全树遍历），结论保持一致
        assertTrue(talent.isSupported(promptOf(root)), "同一实例、目录未变动时应命中缓存");

        Path other = Files.createTempDirectory("other-root");
        assertFalse(talent.isSupported(promptOf(other)), "缓存以根目录为维度，不应串用");
    }

    @Test
    public void isSupported_cacheInvalidatedWhenRootChanges(@TempDir Path root) throws Exception {
        CodeTalent talent = talentOf(root);
        assertFalse(talent.isSupported(promptOf(root)));

        write(root, "pom.xml", "<project></project>");

        assertTrue(talent.isSupported(promptOf(root)),
                "根目录结构变化后应立即重新判定（会话中途才生成工程文件的场景）");
    }

    @Test
    public void isSupported_cacheIsBounded(@TempDir Path root) throws Exception {
        CodeTalent talent = talentOf(root);

        // 超过缓存容量上限（64）后仍应正常工作，不无界增长
        for (int i = 0; i < 80; i++) {
            Path dir = root.resolve("p" + i);
            Files.createDirectories(dir);
            assertFalse(talent.isSupported(promptOf(dir)));
        }

        Path last = root.resolve("p79");
        write(last, "pom.xml", "<project></project>");
        assertTrue(talentOf(last).isSupported(promptOf(last)));
    }

    // ---------- onAttach / getInstruction ----------

    @Test
    public void onAttach_performsInitialization(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project></project>");
        CodeTalent talent = talentOf(root);

        assertFalse(Files.exists(codeMd(root)));

        talent.onAttach(promptOf(root));

        assertTrue(Files.exists(codeMd(root)), "扫描与落盘应发生在挂载钩子，而不是拼提示词时");
    }

    @Test
    public void getInstruction_afterAttach_readsCacheOnly(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project></project>");
        CodeTalent talent = talentOf(root);

        talent.onAttach(promptOf(root));
        Files.delete(codeMd(root));

        String ins = talent.getInstruction(promptOf(root));

        assertFalse(Files.exists(codeMd(root)), "TTL 内不应重复扫描与重写文件");
        assertTrue(ins.contains("已更新项目工程规范"), ins);
    }

    @Test
    public void getInstruction_containsProtocolAndPath(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project></project>");

        String ins = talentOf(root).getInstruction(promptOf(root));

        assertTrue(ins.contains("## 核心工程规约 (Core Engineering Protocol)"));
        assertTrue(ins.contains("项目当前上下文:"));
        assertTrue(ins.contains("`.soloncode/CODE.md`"));
        assertTrue(ins.contains("**动作前导**"));
        assertTrue(ins.contains("**验证驱动**"));
    }

    @Test
    public void getInstruction_withoutAttach_stillInitializes(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project></project>");

        // 兼容直接调用（未走 onAttach）的场景
        String ins = talentOf(root).getInstruction(promptOf(root));

        assertTrue(Files.exists(codeMd(root)));
        assertTrue(ins.contains("Maven (Root)"), ins);
    }

    @Test
    public void getInstruction_nullPrompt_fallsBackToWorkDir(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project></project>");

        String ins = talentOf(root).getInstruction(null);

        assertTrue(ins.contains("Maven (Root)"), ins);
    }

    @Test
    public void getInstruction_failureMessageIsActionable() {
        String ins = new CodeTalent(null, ".soloncode").getInstruction(null);

        // 失败信息会被原样注入系统提示词，不能是一句无从下手的报错
        assertTrue(ins.contains("未能生成项目工程规范"), ins);
        assertTrue(ins.contains("请直接阅读源码"), ins);
    }

    // ---------- .gitignore ----------

    private static int countIgnoreLines(Path root, String token) throws IOException {
        List<String> lines = Files.readAllLines(root.resolve(".gitignore"), StandardCharsets.UTF_8);
        int n = 0;
        for (String line : lines) {
            if (line.trim().contains(token) && !line.trim().startsWith("#")) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void gitignore_notCreatedWhenAbsent(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project></project>");

        talentOf(root).init(null);

        assertFalse(Files.exists(root.resolve(".gitignore")), "未纳入版本管理的项目不应被塞入 .gitignore");
    }

    @Test
    public void gitignore_appendsEntry(@TempDir Path root) throws Exception {
        write(root, ".gitignore", "target/\n");

        talentOf(root).init(null);

        assertEquals(1, countIgnoreLines(root, ".soloncode"));
    }

    @Test
    public void gitignore_appendsWhenLastLineHasNoNewline(@TempDir Path root) throws Exception {
        write(root, ".gitignore", "target/");

        talentOf(root).init(null);

        List<String> lines = Files.readAllLines(root.resolve(".gitignore"), StandardCharsets.UTF_8);
        assertEquals("target/", lines.get(0), "原有内容不应被粘连破坏");
        assertEquals(".soloncode/", lines.get(1));
    }

    @Test
    public void gitignore_slashVariantsAreTreatedAsSame(@TempDir Path root) throws Exception {
        for (String existing : new String[]{".soloncode", ".soloncode/", "/.soloncode", "/.soloncode/"}) {
            Path dir = root.resolve("case" + existing.hashCode());
            Files.createDirectories(dir);
            write(dir, ".gitignore", existing + "\n");

            talentOf(dir).init(null);

            assertEquals(1, countIgnoreLines(dir, ".soloncode"),
                    "已写作 " + existing + " 时不应重复追加");
        }
    }

    @Test
    public void gitignore_inlineCommentIsTolerated(@TempDir Path root) throws Exception {
        write(root, ".gitignore", ".soloncode/ # 工程规范缓存\n");

        talentOf(root).init(null);

        assertEquals(1, countIgnoreLines(root, ".soloncode"));
    }

    @Test
    public void gitignore_commentedEntryDoesNotCount(@TempDir Path root) throws Exception {
        write(root, ".gitignore", "#.soloncode/\n");

        talentOf(root).init(null);

        assertEquals(1, countIgnoreLines(root, ".soloncode"), "被注释掉的条目不生效，应补一条");
    }

    @Test
    public void gitignore_isIdempotent(@TempDir Path root) throws Exception {
        write(root, ".gitignore", "target/\n");
        CodeTalent talent = talentOf(root);

        talent.init(null);
        talent.init(null);
        talent.init(null);

        assertEquals(1, countIgnoreLines(root, ".soloncode"));
    }

    @Test
    public void gitignore_usesFileNameWhenCodeDirEmpty(@TempDir Path root) throws Exception {
        write(root, ".gitignore", "target/\n");

        new CodeTalent(root.toString(), "").init(null);

        assertEquals(1, countIgnoreLines(root, "CODE.md"));
    }
}
