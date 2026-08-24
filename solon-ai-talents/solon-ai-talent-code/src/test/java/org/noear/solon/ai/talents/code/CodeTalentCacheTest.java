package org.noear.solon.ai.talents.code;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 缓存的失效语义：项目在会话进行中「从无到有」时，不能因 TTL 未到而持续返回过期结论。
 * <p>典型场景：会话启动时是空目录，随后由 AI 生成了 pom.xml / package.json 或新的顶层模块目录。
 */
public class CodeTalentCacheTest {

    private static final String POM = "<project><modelVersion>4.0.0</modelVersion></project>";

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

    private static String context(String instruction) {
        for (String line : instruction.split("\n")) {
            if (line.contains("项目当前上下文")) {
                return line.trim();
            }
        }
        return "";
    }

    /**
     * 一轮完整的挂载流程（与 TalentUtil.activeTalents 的调用顺序一致）
     */
    private static String round(CodeTalent talent, Path root) {
        Prompt prompt = promptOf(root);
        if (!talent.isSupported(prompt)) {
            return "<not-supported>";
        }
        talent.onAttach(prompt);
        return context(talent.getInstruction(prompt));
    }

    // ---------- 从无到有：否定结论必须立即自愈 ----------

    @Test
    public void emptyDir_thenBuildFileAppears_isSupportedFlipsWithoutWaitingTtl(@TempDir Path root) throws IOException {
        CodeTalent talent = talentOf(root);

        assertFalse(talent.isSupported(promptOf(root)), "空目录应判定为不适用");

        write(root, "pom.xml", POM);

        assertTrue(talent.isSupported(promptOf(root)),
                "根目录出现构建文件后应立即重新判定，不能沿用 TTL 内的否定缓存");
    }

    @Test
    public void emptyDir_thenScaffolded_generatesCodeMdInSameSession(@TempDir Path root) throws IOException {
        CodeTalent talent = talentOf(root);

        assertEquals("<not-supported>", round(talent, root), "空目录时才能不应被激活");

        write(root, "pom.xml", POM);

        assertTrue(round(talent, root).contains("Maven (Root)"), "同一会话内应识别出新生成的工程");
        assertTrue(new String(Files.readAllBytes(root.resolve(".soloncode/CODE.md")), StandardCharsets.UTF_8)
                .contains("mvn"), "CODE.md 应补上构建指令");
    }

    @Test
    public void weakResult_thenBuildFileAppears_initRefreshesWithoutWaitingTtl(@TempDir Path root) throws IOException {
        // 有 .git 故 isSupported=true，但尚无任何构建文件 —— init 得到「未检测到技术栈」的弱结论
        Files.createDirectories(root.resolve(".git"));
        CodeTalent talent = talentOf(root);

        assertTrue(round(talent, root).contains("未检测到明确的技术栈"));

        write(root, "pom.xml", POM);

        assertTrue(round(talent, root).contains("检测到技术栈: Maven (Root)"),
                "弱结论不能在 TTL 内锁死，构建文件出现后应立即重扫");
    }

    @Test
    public void newTopLevelModuleDir_invalidatesCache(@TempDir Path root) throws IOException {
        write(root, "pom.xml", POM);
        CodeTalent talent = talentOf(root);

        assertFalse(round(talent, root).contains("mod-a"));

        // 新增一个顶层子模块目录：根目录条目集合发生变化
        write(root, "mod-a/pom.xml", POM);

        round(talent, root);
        assertTrue(new String(Files.readAllBytes(root.resolve(".soloncode/CODE.md")), StandardCharsets.UTF_8)
                .contains("mod-a"), "新增顶层模块后 CODE.md 应随之更新");
    }

    // ---------- 反向保证：缓存不能被自身副作用打穿 ----------

    @Test
    public void stableProject_repeatedRounds_hitCache(@TempDir Path root) throws IOException {
        write(root, "pom.xml", POM);
        CodeTalent talent = talentOf(root);

        String first = round(talent, root);
        assertTrue(first.contains("已更新"), "首轮应生成 CODE.md");

        // 生成 CODE.md 会新增 .soloncode/ 目录、改变根目录 mtime；
        // 若指纹在写入前采集，后续每轮都会误判为「目录已变」而重扫（重扫时内容一致会输出「已验证」）
        assertEquals(first, round(talent, root), "第二轮应命中缓存");
        assertEquals(first, round(talent, root), "第三轮应命中缓存");
    }

    @Test
    public void unchangedProject_isSupportedStaysCached(@TempDir Path root) throws IOException {
        write(root, "pom.xml", POM);
        CodeTalent talent = talentOf(root);

        assertTrue(talent.isSupported(promptOf(root)));
        assertTrue(talent.isSupported(promptOf(root)));
        assertTrue(talent.isSupported(promptOf(root)));
    }

    @Test
    public void buildFileRemoved_invalidatesPositiveCache(@TempDir Path root) throws IOException {
        write(root, "pom.xml", POM);
        CodeTalent talent = talentOf(root);

        assertTrue(round(talent, root).contains("Maven (Root)"));

        Files.delete(root.resolve("pom.xml"));

        assertFalse(round(talent, root).contains("Maven (Root)"),
                "构建文件被移除后，肯定结论同样应失效");
    }
}
