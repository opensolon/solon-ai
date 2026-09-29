package features.ai.talents.mount;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.mount.source.ClasspathMountSource;
import org.noear.solon.ai.talents.mount.source.FindOptions;
import org.noear.solon.ai.talents.mount.MountEntry;

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
