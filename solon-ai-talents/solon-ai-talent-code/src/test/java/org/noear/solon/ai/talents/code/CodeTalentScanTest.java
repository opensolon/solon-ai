package org.noear.solon.ai.talents.code;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeTalent 的扫描与 CODE.md 生成。
 *
 * <p>生成结果会被原样注入系统提示词，因此这里既校验「信息是否齐全」，也校验「有没有 token 浪费」。
 */
public class CodeTalentScanTest {

    private static void write(Path dir, String relative, String content) throws IOException {
        Path f = dir.resolve(relative);
        Files.createDirectories(f.getParent());
        Files.write(f, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void mkdirs(Path dir, String relative) throws IOException {
        Files.createDirectories(dir.resolve(relative));
    }

    private static String codeMd(Path root) throws IOException {
        Path f = root.resolve(".soloncode/CODE.md");
        assertTrue(Files.exists(f), "CODE.md 应已生成");
        return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
    }

    private static CodeTalent talentOf(Path root) {
        return new CodeTalent(root.toString(), ".soloncode");
    }

    private static int countOf(String text, String token) {
        int n = 0, i = 0;
        while ((i = text.indexOf(token, i)) >= 0) {
            n++;
            i += token.length();
        }
        return n;
    }

    // ---------- 路径与描述 ----------

    @Test
    public void codeMdPath_normalizesCodeDir() {
        assertEquals("CODE.md", new CodeTalent("w", null).codeMdPath());
        assertEquals("CODE.md", new CodeTalent("w", "").codeMdPath());
        assertEquals(".soloncode/CODE.md", new CodeTalent("w", ".soloncode").codeMdPath());
        assertEquals(".soloncode/CODE.md", new CodeTalent("w", ".soloncode/").codeMdPath());

        CodeTalent talent = new CodeTalent("w", ".soloncode");
        assertEquals(talent.codeMdPath(), talent.HOME_CODE_MD(), "废弃方法应与新方法等价");
        assertTrue(talent.description().contains(".soloncode/CODE.md"));
    }

    // ---------- 根项目识别 ----------

    @Test
    public void mavenRoot_generatesRootCommandsAndVersion(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project><properties><java.version>17</java.version></properties></project>");

        String msg = talentOf(root).init(null);

        assertTrue(msg.startsWith("已更新项目工程规范"), msg);
        assertTrue(msg.contains("检测到技术栈: Maven (Root)"), msg);
        assertTrue(msg.contains("环境版本: Maven (Root): Java 17"), msg + " —— 版本应带上归属，否则多模块时无法分辨");

        String md = codeMd(root);
        assertTrue(md.contains("### 根项目 (Maven)"));
        assertTrue(md.contains("mvn clean compile"));
        assertTrue(md.contains("## 环境版本 (Environment Versions)"));
        assertTrue(md.contains("- Maven (Root): Java 17"));
    }

    @Test
    public void emptyDir_stillWritesGuidelines(@TempDir Path root) throws Exception {
        String msg = talentOf(root).init(null);

        assertTrue(msg.contains("未检测到明确的技术栈"), msg);

        String md = codeMd(root);
        assertTrue(md.contains("## 工程规约 (Guidelines)"));
        assertFalse(md.contains("## 环境版本"), "没探测到版本时不应留空章节");
        assertFalse(md.contains("### 子模块与子项目"), "没有子模块时不应留空章节");
    }

    @Test
    public void guidelines_labelIsReadBeforeEdit(@TempDir Path root) throws Exception {
        talentOf(root).init(null);

        String md = codeMd(root);
        assertTrue(md.contains("- **改前必读**"), "规约标签应为「改前必读」");
        assertFalse(md.contains("读前必改"), "「读前必改」是把因果写反的错别字");
    }

    @Test
    public void secondInit_reportsVerifiedInsteadOfUpdated(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project></project>");
        CodeTalent talent = talentOf(root);

        assertTrue(talent.init(null).startsWith("已更新"));
        assertTrue(talent.init(null).startsWith("已验证"), "内容未变时不应报告为更新");
    }

    // ---------- 同构子模块折叠 ----------

    @Test
    public void homogeneousModules_areFoldedIntoOneLine(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project><modules><module>a</module></modules></project>");
        write(root, "mod-a/pom.xml", "<project></project>");
        write(root, "mod-b/pom.xml", "<project></project>");
        write(root, "group/mod-c/pom.xml", "<project></project>");

        talentOf(root).init(null);
        String md = codeMd(root);

        assertTrue(md.contains("### 子模块与子项目"));
        assertTrue(md.contains("mod-a"));
        assertTrue(md.contains("mod-b"));
        assertTrue(md.contains("group/mod-c"));
        assertTrue(md.contains("Maven 模块 (3 个)"), "应给出模块总数：\n" + md);

        assertEquals(1, countOf(md, "Maven 模块"),
                "3 个同构模块应折叠为一行，而不是逐行复述同一句套话");
        assertEquals(0, countOf(md, "受根项目指令统一控制。"),
                "旧实现的逐行套话应已消除");
        assertEquals(1, countOf(md, "受根项目指令统一控制"),
                "统一控制的说明只保留一处");
    }

    @Test
    public void homogeneousModules_carryDeclaredVersion(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project></project>");
        write(root, "mod-a/pom.xml", "<project><properties><java.version>21</java.version></properties></project>");

        talentOf(root).init(null);

        assertTrue(codeMd(root).contains("mod-a（Java 21）"), codeMd(root));
    }

    // ---------- 聚合器 ----------

    @Test
    public void aggregatorModule_doesNotHideItsChildren(@TempDir Path root) throws Exception {
        // 标准 Maven 聚合布局：分组目录自身也有一个声明了 <modules> 的父 POM
        write(root, "pom.xml", "<project><modules><module>group</module></modules></project>");
        write(root, "group/pom.xml", "<project><packaging>pom</packaging><modules><module>m1</module><module>m2</module></modules></project>");
        write(root, "group/m1/pom.xml", "<project></project>");
        write(root, "group/m2/pom.xml", "<project></project>");

        talentOf(root).init(null);
        String md = codeMd(root);

        assertTrue(md.contains("group/m1"), "聚合器不得吞掉其下真实模块：\n" + md);
        assertTrue(md.contains("group/m2"));
        assertTrue(md.contains("Maven 模块 (3 个)"), "聚合器自身也是一个模块：\n" + md);
    }

    @Test
    public void leafModule_prunesNestedDirs(@TempDir Path root) throws Exception {
        // 叶子模块（未声明 modules）内部的嵌套 pom 属于噪声，应被剪掉
        write(root, "pom.xml", "<project></project>");
        write(root, "leaf/pom.xml", "<project><packaging>jar</packaging></project>");
        write(root, "leaf/nested/pom.xml", "<project></project>");

        talentOf(root).init(null);
        String md = codeMd(root);

        assertTrue(md.contains("leaf"));
        assertFalse(md.contains("leaf/nested"), "叶子模块内的嵌套模块不应单独列出：\n" + md);
        assertTrue(md.contains("Maven 模块 (1 个)"));
    }

    // ---------- 异构模块 ----------

    @Test
    public void heterogeneousModule_getsItsOwnCommandSection(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project></project>");
        write(root, "web/package.json", "{\"engines\":{\"node\":\">=18\"}}");

        String msg = talentOf(root).init(null);
        String md = codeMd(root);

        assertTrue(msg.contains("web (Node)"), msg);
        assertTrue(msg.contains("web (Node): Node >=18"), msg);
        assertTrue(md.contains("### 模块 (Module): web (Node/TS)"), md);
        assertTrue(md.contains("cd web && npm install"), md);
        assertTrue(md.contains("- web (Node): Node >=18"), md);
    }

    @Test
    public void multipleRootStacks_areAllReported(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project></project>");
        write(root, "package.json", "{}");

        String msg = talentOf(root).init(null);
        String md = codeMd(root);

        assertTrue(msg.contains("Maven (Root)"), msg);
        assertTrue(msg.contains("Node (Root)"), msg);
        assertTrue(md.contains("### 根项目 (Maven)"));
        assertTrue(md.contains("### 根项目 (Node/TS)"));
    }

    // ---------- 忽略与深度 ----------

    @Test
    public void ignoredDirs_areNotScanned(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project></project>");
        write(root, "target/generated/pom.xml", "<project></project>");
        write(root, "node_modules/pkg/package.json", "{}");
        write(root, "build/tmp/pom.xml", "<project></project>");
        write(root, ".hidden/pom.xml", "<project></project>");
        write(root, "real/pom.xml", "<project></project>");

        talentOf(root).init(null);
        String md = codeMd(root);

        assertFalse(md.contains("target/generated"), md);
        assertFalse(md.contains("node_modules"), md);
        assertFalse(md.contains("build/tmp"), md);
        assertFalse(md.contains(".hidden"), md);
        assertTrue(md.contains("Maven 模块 (1 个)"), md);
    }

    @Test
    public void scanDepth_isLimited(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project></project>");
        write(root, "d1/d2/d3/pom.xml", "<project></project>");
        write(root, "e1/e2/e3/e4/pom.xml", "<project></project>");

        talentOf(root).init(null);
        String md = codeMd(root);

        assertTrue(md.contains("d1/d2/d3"), "根目录往下 3 层仍应在扫描范围内：\n" + md);
        assertFalse(md.contains("e1/e2/e3/e4"), "超出深度上限的目录不应被扫描：\n" + md);
    }

    @Test
    public void unreadableEntries_doNotBreakScan(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project></project>");
        mkdirs(root, "empty-dir");
        write(root, "readme.md", "hi");

        // 目录中混有普通文件、空目录时应正常完成
        assertTrue(talentOf(root).init(null).startsWith("已更新"));
    }

    // ---------- SPI 扩展 ----------

    @Test
    public void spiProvider_isLoadedAndUsed(@TempDir Path root) throws Exception {
        CodeTalent talent = talentOf(root);

        assertTrue(talent.providers().size() >= 12, "内置 11 个 + SPI 扩展");
        assertTrue(talent.providers().stream().anyMatch(p -> "MyLang".equals(p.id())),
                "META-INF/services 注册的 Provider 应被加载");

        write(root, "mytool.cfg", "version = \"1.2\"\n");
        String msg = talent.init(null);

        assertTrue(msg.contains("MyLang (Root)"), msg);
        assertTrue(codeMd(root).contains("mytool build"), codeMd(root));
    }

    // ---------- 失败路径 ----------

    @Test
    public void missingDir_reportsUnwritableWithoutThrowing(@TempDir Path root) {
        String msg = new CodeTalent(root.resolve("not-exists").toString(), ".soloncode").init(null);

        assertTrue(msg.startsWith("未能生成项目工程规范"), msg);
        assertTrue(msg.contains("目录不可写"), msg);
        // 失败信息也会进提示词，必须给模型一个可行的兜底动作
        assertTrue(msg.contains("请直接阅读源码"), msg);
    }

    @Test
    public void workDirNotSet_reportsFailureInsteadOfThrowing() {
        String msg = new CodeTalent(null, ".soloncode").init(null);

        assertTrue(msg.startsWith("未能生成项目工程规范"), msg);
        assertTrue(msg.contains("初始化异常"), msg);
    }

    @Test
    public void cwdPointsToFile_reportsFailureInsteadOfThrowing(@TempDir Path root) throws Exception {
        Path file = root.resolve("a.txt");
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));

        String msg = new CodeTalent(root.toString(), ".soloncode").init(file.toString());

        assertTrue(msg.startsWith("未能生成项目工程规范"), msg);
        assertTrue(msg.contains("初始化异常"), msg);
    }

    @Test
    public void cwdOverridesWorkDir(@TempDir Path root) throws Exception {
        Path other = root.resolve("other");
        Files.createDirectories(other);
        write(other, "go.mod", "module demo\n\ngo 1.22\n");

        String msg = new CodeTalent(root.toString(), ".soloncode").init(other.toString());

        assertTrue(msg.contains("Go (Root)"), msg);
        assertTrue(Files.exists(other.resolve(".soloncode/CODE.md")));
        assertFalse(Files.exists(root.resolve(".soloncode/CODE.md")), "不应写到 workDir");
    }

    @Test
    public void codeDirEmpty_writesCodeMdAtRoot(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project></project>");

        new CodeTalent(root.toString(), "").init(null);

        assertTrue(Files.exists(root.resolve("CODE.md")));
    }
}
