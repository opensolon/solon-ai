package features.ai.talents.mount;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.mount.source.ClasspathMountSource;
import org.noear.solon.ai.talents.mount.source.FindOptions;
import org.noear.solon.ai.talents.mount.source.MountEntry;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
public class ClasspathMountSourceTest {
    @Test
    public void readsAndFindsClasspathResources() throws Exception {
        ClasspathMountSource source = ClasspathMountSource.of(
                Thread.currentThread().getContextClassLoader(), "test-skills");

        assertTrue(source.isDirectory("demo"));
        try (InputStream input = source.openRead("demo/SKILL.md")) {
            String content = new String(readAll(input), StandardCharsets.UTF_8);
            assertTrue(content.contains("Demo classpath skill"));
        }

        List<MountEntry> entries = source.find("demo", FindOptions.builder()
                .glob("**/*.md").filesOnly(true).maxDepth(3).build());
        assertEquals(2, entries.size());
        assertTrue(source.find("demo", FindOptions.builder().maxEntries(0).build()).isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> source.openWrite("demo/new.md", null));
        assertFalse(source.capabilities().isShellAccessible());
    }

    /**
     * 多个技能：独立目录布局（skills/<name>/SKILL.md），index 清单仅兜底
     */
    @Test
    public void listsMultipleSkills() throws Exception {
        ClasspathMountSource source = ClasspathMountSource.of(
                Thread.currentThread().getContextClassLoader(), "test-multi/skills");

        // 顶层：两个技能目录 + index 文件
        List<MountEntry> top = source.list("");
        assertTrue(containsPath(top, "weather"));
        assertTrue(containsPath(top, "translate"));
        assertEquals(3, top.size());

        for (MountEntry entry : top) {
            if ("weather".equals(entry.getName()) || "translate".equals(entry.getName())) {
                assertTrue(entry.isDirectory());
            }
        }

        // 各技能可读且内容正确
        try (InputStream input = source.openRead("weather/SKILL.md")) {
            assertTrue(readText(input).contains("Weather lookup skill"));
        }
        try (InputStream input = source.openRead("translate/SKILL.md")) {
            assertTrue(readText(input).contains("Translation skill"));
        }

        // 递归查找所有 SKILL.md（index 存在时真实扫描优先，不产生重复条目）
        List<MountEntry> manifests = source.find("", FindOptions.builder()
                .glob("**/SKILL.md").filesOnly(true).maxDepth(4).build());
        assertEquals(2, manifests.size());
        assertTrue(containsPath(manifests, "weather/SKILL.md"));
        assertTrue(containsPath(manifests, "translate/SKILL.md"));

        // stat 返回真实 size（真实扫描的文件，不是 index 兜底条目）
        MountEntry weatherStat = source.stat("weather/SKILL.md");
        assertNotNull(weatherStat);
        assertFalse(weatherStat.isDirectory());
        assertTrue(weatherStat.getSize() > 0);

        // stat 目录
        MountEntry dirStat = source.stat("translate/references");
        assertNotNull(dirStat);
        assertTrue(dirStat.isDirectory());

        // 读不到的路径
        assertNull(source.stat("no-such-skill/SKILL.md"));
        assertThrows(Exception.class, () -> source.openRead("no-such-skill/SKILL.md"));
    }

    /**
     * 多个 agent：平铺 md 布局（agents/*.md），无 index
     */
    @Test
    public void listsMultipleAgents() throws Exception {
        ClasspathMountSource source = ClasspathMountSource.of(
                Thread.currentThread().getContextClassLoader(), "test-multi/agents");

        // 顶层直接列出所有 agent 文件
        List<MountEntry> top = source.list("");
        assertEquals(2, top.size());
        assertTrue(containsPath(top, "coder.md"));
        assertTrue(containsPath(top, "reviewer.md"));

        // 根 stat 可用
        MountEntry root = source.stat("");
        assertNotNull(root);
        assertTrue(root.isDirectory());

        // 每个 agent 可读，frontmatter 完整
        try (InputStream input = source.openRead("coder.md")) {
            String text = readText(input);
            assertTrue(text.contains("name: \"coder\""));
            assertTrue(text.contains("tools: [\"read\", \"write\", \"bash\"]"));
        }
        try (InputStream input = source.openRead("reviewer.md")) {
            String text = readText(input);
            assertTrue(text.contains("name: \"reviewer\""));
        }

        // glob 定位单个 agent
        List<MountEntry> found = source.find("", FindOptions.builder()
                .glob("coder.md").filesOnly(true).maxDepth(2).build());
        assertEquals(1, found.size());
        assertEquals("coder.md", found.get(0).getPath());

        // 平铺布局下不存在 agent 子目录，且没有 index 文件（无 index 用况）
        assertNull(source.stat("index"));
        List<MountEntry> dirs = source.find("", FindOptions.builder()
                .directoriesOnly(true).maxDepth(3).build());
        assertEquals(0, dirs.size());
    }

    /**
     * jar 无目录条目时，仅靠 index 清单完成发现（真实 jar 场景模拟）
     */
    @Test
    public void indexFallbackWhenJarHasNoDirectoryEntries(@TempDir Path temp) throws Exception {
        Path jarPath = temp.resolve("skills-no-dir-entries.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            // 仅文件条目，不写目录条目（模拟目录条目被剥离的打包方式）
            zip.putNextEntry(new ZipEntry("test-jar-skills/weather/SKILL.md"));
            zip.write("---\ndescription: Jar weather skill\n---\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("test-jar-skills/translate/SKILL.md"));
            zip.write("---\ndescription: Jar translate skill\n---\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("test-jar-skills/index"));
            zip.write("# fallback index\nweather/SKILL.md\ntranslate/SKILL.md\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarPath.toUri().toURL()}, null)) {
            ClasspathMountSource source = ClasspathMountSource.of(loader, "test-jar-skills");

            // 目录条目不存在，发现完全依赖 index
            List<MountEntry> manifests = source.find("", FindOptions.builder()
                    .glob("**/SKILL.md").filesOnly(true).maxDepth(3).build());
            assertEquals(2, manifests.size());
            assertTrue(containsPath(manifests, "weather/SKILL.md"));
            assertTrue(containsPath(manifests, "translate/SKILL.md"));

            // 父目录由条目路径合成
            assertTrue(source.isDirectory("weather"));
            assertTrue(source.isDirectory("translate"));

            // 内容可读
            try (InputStream input = source.openRead("weather/SKILL.md")) {
                assertTrue(readText(input).contains("Jar weather skill"));
            }
        }
    }

    private static boolean containsPath(List<MountEntry> entries, String path) {
        for (MountEntry entry : entries) {
            if (entry.getPath().equals(path)) return true;
        }
        return false;
    }

    // --- normalize 归一化与越界拒绝 ---

    @Test
    public void normalizeShouldCleanAndRejectEscape() {
        ClasspathMountSource source = ClasspathMountSource.of(
                Thread.currentThread().getContextClassLoader(), "test-skills");

        assertEquals("", source.normalize(null));
        assertEquals("", source.normalize(""));
        assertEquals("", source.normalize("."));
        assertEquals("", source.normalize("/"));
        assertEquals("a/b.md", source.normalize("/a/./b.md"));
        assertEquals("a/b.md", source.normalize("\\a\\b.md"));
        // “..” 片段一律拒绝（不同于 FileMountSource 的 normalize 折叠）
        assertThrows(SecurityException.class, () -> source.normalize("../outside"));
        assertThrows(SecurityException.class, () -> source.normalize("a/../../outside"));
    }

    @Test
    public void basePathShouldBeNormalized() throws Exception {
        // 尾部/头部斜杠被剥离后统一为 basePath/
        ClasspathMountSource source = ClasspathMountSource.of(
                Thread.currentThread().getContextClassLoader(), "/test-skills/");
        assertEquals("test-skills/", source.getBasePath());
        assertEquals("test-skills/", source.getLocation());
        assertEquals("classpath", source.getScheme());

        ClasspathMountSource emptyBase = ClasspathMountSource.of(
                Thread.currentThread().getContextClassLoader(), null);
        assertEquals("", emptyBase.getBasePath());
        assertNotNull(emptyBase.stat(""));
    }

    // --- 只读能力 ---

    @Test
    public void readOnlyOperationsShouldBeRejected() {
        ClasspathMountSource source = ClasspathMountSource.of(
                Thread.currentThread().getContextClassLoader(), "test-skills");

        MountCapabilitiesAssert.assertReadOnlyVirtual(source);
    }

    private static final class MountCapabilitiesAssert {
        static void assertReadOnlyVirtual(ClasspathMountSource source) {
            assertTrue(source.capabilities().isReadable());
            assertTrue(source.capabilities().isSearchable());
            assertFalse(source.capabilities().isWritable());
            assertFalse(source.capabilities().isEditable());
            assertFalse(source.capabilities().isDeletable());
            assertFalse(source.capabilities().isMovable());
            assertFalse(source.capabilities().isShellAccessible());
            assertFalse(source.capabilities().isLocalPathAccessible());
            // classpath 来源已升级为可物化执行（readOnlyMaterializable）
            assertTrue(source.capabilities().isMaterializable());

            assertThrows(UnsupportedOperationException.class, () -> source.openWrite("x", null));
            assertThrows(UnsupportedOperationException.class, () -> source.delete("x"));
            assertThrows(UnsupportedOperationException.class, () -> source.move("a", "b", null));
        }
    }

    // --- 物化与指纹 ---

    @Test
    public void materializeShouldCopySubtreeWithScriptExecutableBit(@TempDir Path temp) throws Exception {
        ClasspathMountSource source = ClasspathMountSource.of(
                Thread.currentThread().getContextClassLoader(), "test-skills");

        Path out = temp.resolve("out");
        java.util.Optional<Path> result = source.materialize("demo", out);
        assertTrue(result.isPresent());
        assertEquals(out, result.get());
        assertTrue(Files.isDirectory(out));
        // 子树内容完整落地
        assertTrue(Files.isRegularFile(out.resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(out.resolve("references/api.md")));
        try (InputStream input = Files.newInputStream(out.resolve("references/api.md"))) {
            assertTrue(readText(input).length() > 0);
        }
    }

    @Test
    public void materializeRootShouldCopyWholeMount(@TempDir Path temp) throws Exception {
        ClasspathMountSource source = ClasspathMountSource.of(
                Thread.currentThread().getContextClassLoader(), "test-multi/skills");

        Path out = temp.resolve("root");
        java.util.Optional<Path> result = source.materialize("", out);
        assertTrue(result.isPresent());
        assertTrue(Files.isRegularFile(out.resolve("weather/SKILL.md")));
        assertTrue(Files.isRegularFile(out.resolve("translate/SKILL.md")));
        assertTrue(Files.isRegularFile(out.resolve("translate/references/glossary.md")));
    }

    @Test
    public void materializeSingleFileShouldKeepStructure(@TempDir Path temp) throws Exception {
        ClasspathMountSource source = ClasspathMountSource.of(
                Thread.currentThread().getContextClassLoader(), "test-skills");

        Path out = temp.resolve("file");
        java.util.Optional<Path> result = source.materialize("demo/SKILL.md", out);
        assertTrue(result.isPresent());
        assertEquals(out.resolve("demo/SKILL.md"), result.get());
        assertTrue(Files.isRegularFile(out.resolve("demo/SKILL.md")));
    }

    @Test
    public void materializeNullTargetShouldReturnEmpty() throws Exception {
        ClasspathMountSource source = ClasspathMountSource.of(
                Thread.currentThread().getContextClassLoader(), "test-skills");
        assertFalse(source.materialize("demo", null).isPresent());
    }

    @Test
    public void scriptSuffixShouldGainExecutableBitOnPosix(@TempDir Path temp) throws Exception {
        // 在 jar 中构造 shell 脚本，验证物化后 +x（POSIX）
        Path jarPath = temp.resolve("exec-bit.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            zip.putNextEntry(new ZipEntry("exec-skills/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("exec-skills/tool/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("exec-skills/tool/run.sh"));
            zip.write("#!/bin/sh\necho ok\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("exec-skills/tool/main.py"));
            zip.write("print('hi')\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarPath.toUri().toURL()}, null)) {
            ClasspathMountSource source = ClasspathMountSource.of(loader, "exec-skills");
            Path out = temp.resolve("exec-out");
            source.materialize("", out);

            if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                assertTrue(Files.isExecutable(out.resolve("tool/run.sh")), ".sh 应获得可执行位");
            }
            // 解释器脚本无需 +x，但内容完整
            assertTrue(Files.isRegularFile(out.resolve("tool/main.py")));
        }
    }

    @Test
    public void fingerprintShouldBeStableAndContentSensitive(@TempDir Path temp) throws Exception {
        ClasspathMountSource source = ClasspathMountSource.of(
                Thread.currentThread().getContextClassLoader(), "test-skills");
        String fp1 = source.getFingerprint();
        String fp2 = source.getFingerprint();
        assertNotNull(fp1);
        assertEquals(fp1, fp2, "同一内容指纹应稳定");

        // 两个内容不同的 jar：指纹不同
        Path jarA = temp.resolve("a.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jarA))) {
            zip.putNextEntry(new ZipEntry("fp-skills/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("fp-skills/a/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("fp-skills/a/SKILL.md"));
            zip.write("A".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path jarB = temp.resolve("b.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jarB))) {
            zip.putNextEntry(new ZipEntry("fp-skills/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("fp-skills/a/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("fp-skills/a/SKILL.md"));
            zip.write("A+different content".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        try (URLClassLoader loaderA = new URLClassLoader(new URL[]{jarA.toUri().toURL()}, null);
             URLClassLoader loaderB = new URLClassLoader(new URL[]{jarB.toUri().toURL()}, null)) {
            String fpA = ClasspathMountSource.of(loaderA, "fp-skills").getFingerprint();
            String fpB = ClasspathMountSource.of(loaderB, "fp-skills").getFingerprint();
            assertNotEquals(fpA, fpB, "内容变化应改变指纹");
        }
    }

    @Test
    public void jarFingerprintShouldUseJarIdentity(@TempDir Path temp) throws Exception {
        Path jar = temp.resolve("identity.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("id-skills/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("id-skills/a/SKILL.md"));
            zip.write("x".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            String fp = ClasspathMountSource.of(loader, "id-skills").getFingerprint();
            // jar 协议下指纹应携带 jar 标识（而非索引聚合形态）
            assertTrue(fp.startsWith("jar:"), fp);
        }
    }

    // --- index 兑底语义 ---

    @Test
    public void indexFallbackShouldSkipCommentsAndDuplicates(@TempDir Path temp) throws Exception {
        Path jarPath = temp.resolve("skills-index-semantics.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            zip.putNextEntry(new ZipEntry("idx-skills/real/SKILL.md"));
            zip.write("real".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            // index 含注释、空行、重复条目、不存在的兑底条目
            zip.putNextEntry(new ZipEntry("idx-skills/index"));
            zip.write("# comment\n\n  \nreal/SKILL.md\nghost/SKILL.md\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarPath.toUri().toURL()}, null)) {
            ClasspathMountSource source = ClasspathMountSource.of(loader, "idx-skills");

            List<MountEntry> manifests = source.find("", FindOptions.builder()
                    .glob("**/SKILL.md").filesOnly(true).maxDepth(3).build());
            // 注释/空行不产生条目；真实扫描优先，index 重复不叠加
            assertEquals(2, manifests.size());
            assertTrue(containsPath(manifests, "real/SKILL.md"));
            assertTrue(containsPath(manifests, "ghost/SKILL.md"));

            // ghost 由 index 兑底：条目存在但 size 未知
            MountEntry ghost = source.stat("ghost/SKILL.md");
            assertNotNull(ghost);
            assertEquals(-1, ghost.getSize());
        }
    }

    @Test
    public void jarWithDirectoryEntriesShouldListSynthesizedParents(@TempDir Path temp) throws Exception {
        Path jarPath = temp.resolve("skills-with-dir-entries.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            zip.putNextEntry(new ZipEntry("dir-entry-skills/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("dir-entry-skills/weather/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("dir-entry-skills/weather/SKILL.md"));
            zip.write("content".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarPath.toUri().toURL()}, null)) {
            ClasspathMountSource source = ClasspathMountSource.of(loader, "dir-entry-skills");

            // 目录条目直接入索引
            MountEntry dir = source.stat("weather");
            assertNotNull(dir);
            assertTrue(dir.isDirectory());

            // list 顶层返回合成目录
            List<MountEntry> top = source.list("");
            assertEquals(1, top.size());
            assertEquals("weather", top.get(0).getName());
            assertTrue(top.get(0).isDirectory());

            // list 子目录返回文件
            List<MountEntry> children = source.list("weather");
            assertEquals(1, children.size());
            assertEquals("SKILL.md", children.get(0).getName());
            assertEquals(7, children.get(0).getSize());
        }
    }

    @Test
    public void refreshShouldReenumerateClasspath(@TempDir Path temp) throws Exception {
        // 基于 file 协议的目录扫描：新建文件后 refresh 可见（验证索引重建）
        Path scanRoot = Files.createDirectories(temp.resolve("refresh-skills/demo"));
        Files.write(scanRoot.resolve("SKILL.md"), "v1".getBytes(StandardCharsets.UTF_8));

        try (URLClassLoader loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, null)) {
            ClasspathMountSource source = ClasspathMountSource.of(loader, "refresh-skills");
            assertNotNull(source.stat("demo/SKILL.md"));

            // 索引存在时新增文件不可见
            Files.write(scanRoot.resolve("EXTRA.md"), "v2".getBytes(StandardCharsets.UTF_8));
            assertNull(source.stat("demo/EXTRA.md"));

            // refresh 后可见
            source.refresh();
            assertNotNull(source.stat("demo/EXTRA.md"));
        }
    }

    @Test
    public void openReadShouldFailForMissingResource() throws Exception {
        ClasspathMountSource source = ClasspathMountSource.of(
                Thread.currentThread().getContextClassLoader(), "test-skills");
        assertThrows(java.io.IOException.class, () -> source.openRead("ghost/SKILL.md"));
    }

    private static String readText(InputStream input) throws Exception {
        return new String(readAll(input), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
