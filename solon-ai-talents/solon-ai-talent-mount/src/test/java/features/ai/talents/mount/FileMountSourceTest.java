package features.ai.talents.mount;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.mount.MountEntry;
import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.ai.talents.mount.source.FindOptions;
import org.noear.solon.ai.talents.mount.source.MoveOptions;
import org.noear.solon.ai.talents.mount.source.WriteOptions;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地文件挂载来源的独立行为验证：
 *
 * <ul>
 *   <li>normalize：空白/./.. 路径归一化与越界拒绝；</li>
 *   <li>stat/list/find：条目信息与过滤维度（glob / filesOnly / directoriesOnly / maxDepth / maxEntries）；</li>
 *   <li>openRead/openWrite/move/delete：读写通道、父目录创建、追加与替换语义；</li>
 *   <li>安全：符号链接越界、根目录删除保护、不存在的挂载根（AGENTS 挂载常见形态）。</li>
 * </ul>
 */
public class FileMountSourceTest {
    @TempDir
    Path tempDir;

    private Path newSourceDir(String name) throws Exception {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    private static String readText(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // --- normalize ---

    @Test
    public void normalizeShouldHandleBlankAndDotOnly() {
        FileMountSource source = FileMountSource.of(newSourceDirQuiet("n"));
        assertEquals("", source.normalize(null));
        assertEquals("", source.normalize(""));
        assertEquals("", source.normalize("."));
        assertEquals("", source.normalize("/"));
        assertEquals("a/b.md", source.normalize("\\a\\b.md"));
    }

    @Test
    public void normalizeShouldRejectEscape() {
        FileMountSource source = FileMountSource.of(newSourceDirQuiet("n"));
        assertThrows(SecurityException.class, () -> source.normalize("../outside"));
        assertThrows(SecurityException.class, () -> source.normalize("a/../../outside"));
    }

    @Test
    public void constructorShouldRejectNullRoot() {
        assertThrows(IllegalArgumentException.class, () -> new FileMountSource(null));
    }

    private Path newSourceDirQuiet(String name) {
        try {
            return newSourceDir(name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // --- stat / list / find ---

    @Test
    public void statShouldExposeDirectoryAndFileAttributes() throws Exception {
        Path root = newSourceDir("stat-root");
        Files.write(root.resolve("note.txt"), "hello".getBytes(StandardCharsets.UTF_8));
        FileMountSource source = FileMountSource.of(root);

        MountEntry file = source.stat("note.txt");
        assertNotNull(file);
        assertFalse(file.isDirectory());
        assertEquals(5, file.getSize());
        assertEquals("note.txt", file.getName());
        assertNotNull(file.getLastModified());

        MountEntry dir = source.stat("");
        assertNotNull(dir);
        assertTrue(dir.isDirectory());
        // 根条目名称来自挂载根目录名
        assertEquals(root.getFileName().toString(), dir.getName());

        assertNull(source.stat("missing.txt"));
    }

    @Test
    public void statMissingRootShouldReturnNullInsteadOfThrowing() throws Exception {
        Path missing = tempDir.resolve("never-created");
        FileMountSource source = FileMountSource.of(missing);

        assertNull(source.stat(""));
        assertNull(source.stat("a.txt"));
        // 挂载根缺失时本地路径仍可解析（写入前由 resolveChecked 放行）
        assertTrue(source.getLocalPath("a.txt").isPresent());
        assertTrue(source.getLocalRoot().isPresent());
        assertEquals(missing.toAbsolutePath().normalize(), source.getLocalRoot().get());
    }

    @Test
    public void listShouldSortEntriesAndFailOnNonDirectory() throws Exception {
        Path root = newSourceDir("list-root");
        Files.write(root.resolve("b.txt"), "b".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("a.txt"), "a".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("sub"));
        FileMountSource source = FileMountSource.of(root);

        java.util.List<MountEntry> entries = source.list("");
        assertEquals(3, entries.size());
        assertEquals("a.txt", entries.get(0).getName());
        assertEquals("b.txt", entries.get(1).getName());
        assertEquals("sub", entries.get(2).getName());

        assertThrows(java.io.IOException.class, () -> source.list("a.txt"));
        assertThrows(java.io.IOException.class, () -> source.list("missing-dir"));
    }

    @Test
    public void findShouldApplyGlobDepthAndTypeFilters() throws Exception {
        Path root = newSourceDir("find-root");
        Files.write(root.resolve("top.md"), "top".getBytes(StandardCharsets.UTF_8));
        Path sub = Files.createDirectories(root.resolve("sub"));
        Files.write(sub.resolve("inner.md"), "inner".getBytes(StandardCharsets.UTF_8));
        Files.write(sub.resolve("inner.txt"), "txt".getBytes(StandardCharsets.UTF_8));
        Path deep = Files.createDirectories(sub.resolve("deep"));
        Files.write(deep.resolve("leaf.md"), "leaf".getBytes(StandardCharsets.UTF_8));
        FileMountSource source = FileMountSource.of(root);

        // glob 递归：** 需至少一层目录，顶层的 top.md 不命中 **/*.md
        java.util.List<MountEntry> mds = source.find("", FindOptions.builder()
                .glob("**/*.md").filesOnly(true).maxDepth(5).build());
        assertEquals(2, mds.size());

        // glob * 单层匹配顶层文件
        java.util.List<MountEntry> tops = source.find("", FindOptions.builder()
                .glob("*.md").filesOnly(true).maxDepth(1).build());
        assertEquals(1, tops.size());
        assertEquals("top.md", tops.get(0).getPath());

        // maxDepth 限制：depth 2 之外不可见（sub/inner.md 深度 2，sub/deep/leaf.md 深度 3）
        java.util.List<MountEntry> shallow = source.find("", FindOptions.builder()
                .glob("**/*.md").filesOnly(true).maxDepth(2).build());
        assertEquals(1, shallow.size());
        assertEquals("sub/inner.md", shallow.get(0).getPath());

        // directoriesOnly
        java.util.List<MountEntry> dirs = source.find("", FindOptions.builder()
                .directoriesOnly(true).maxDepth(3).build());
        assertEquals(2, dirs.size());

        // 带基路径的查找只返回基路径内条目
        java.util.List<MountEntry> inSub = source.find("sub", FindOptions.builder()
                .glob("*.md").filesOnly(true).maxDepth(1).build());
        assertEquals(1, inSub.size());
        assertEquals("sub/inner.md", inSub.get(0).getPath());

        // 不存在的起点返回空集合
        assertTrue(source.find("no-such", FindOptions.defaults()).isEmpty());
    }

    @Test
    public void findShouldCapResultByMaxEntries() throws Exception {
        Path root = newSourceDir("cap-root");
        for (int i = 0; i < 5; i++) {
            Files.write(root.resolve("f" + i + ".txt"), "x".getBytes(StandardCharsets.UTF_8));
        }
        FileMountSource source = FileMountSource.of(root);

        assertEquals(3, source.find("", FindOptions.builder().maxEntries(3).build()).size());
        assertTrue(source.find("", FindOptions.builder().maxEntries(0).build()).isEmpty());
    }

    // --- read / write / move / delete ---

    @Test
    public void openWriteShouldCreateParentsAndSupportAppend() throws Exception {
        Path root = newSourceDir("write-root");
        FileMountSource source = FileMountSource.of(root);

        try (OutputStream out = source.openWrite("a/b/c.txt", WriteOptions.replace())) {
            out.write("first".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("first", readText(source.openRead("a/b/c.txt")));

        try (OutputStream out = source.openWrite("a/b/c.txt", WriteOptions.builder().append(true).createParents(true).build())) {
            out.write("-second".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("first-second", readText(source.openRead("a/b/c.txt")));

        // null options 等价 replace
        try (OutputStream out = source.openWrite("a/b/c.txt", null)) {
            out.write("third".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("third", readText(source.openRead("a/b/c.txt")));
    }

    @Test
    public void moveShouldCreateTargetParentsAndOptionallyReplace() throws Exception {
        Path root = newSourceDir("move-root");
        FileMountSource source = FileMountSource.of(root);
        try (OutputStream out = source.openWrite("from.txt", WriteOptions.replace())) {
            out.write("data".getBytes(StandardCharsets.UTF_8));
        }

        source.move("from.txt", "x/y/to.txt", null);
        assertNull(source.stat("from.txt"));
        assertEquals("data", readText(source.openRead("x/y/to.txt")));

        try (OutputStream out = source.openWrite("other.txt", WriteOptions.replace())) {
            out.write("other".getBytes(StandardCharsets.UTF_8));
        }
        // 不带 replaceExisting 且目标已存在 → 失败
        assertThrows(java.io.IOException.class,
                () -> source.move("other.txt", "x/y/to.txt", null));
        // 带 replaceExisting → 成功
        source.move("other.txt", "x/y/to.txt", MoveOptions.replaceExisting());
        assertEquals("other", readText(source.openRead("x/y/to.txt")));
    }

    @Test
    public void deleteShouldRefuseMountRootButRemoveEntries() throws Exception {
        Path root = newSourceDir("delete-root");
        FileMountSource source = FileMountSource.of(root);
        try (OutputStream out = source.openWrite("f.txt", WriteOptions.replace())) {
            out.write("x".getBytes(StandardCharsets.UTF_8));
        }

        assertThrows(SecurityException.class, () -> source.delete(""));
        source.delete("f.txt");
        assertNull(source.stat("f.txt"));
        assertThrows(java.io.IOException.class, () -> source.delete("f.txt"));
    }

    // --- 符号链接与安全 ---

    @Test
    public void symlinkEscapeShouldBeRejected() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(!isWindows());
        Path root = newSourceDir("link-root");
        Path outside = Files.createTempDirectory("file-mount-outside");
        try {
            // 链接目标目录中真实存在文件：stat 会走到 toRealPath 校验并拒绝
            Files.write(outside.resolve("stolen.txt"), "secret".getBytes(StandardCharsets.UTF_8));
            Files.createSymbolicLink(root.resolve("out-link"), outside);

            FileMountSource source = FileMountSource.of(root);
            assertThrows(SecurityException.class, () -> source.stat("out-link/stolen.txt"));

            // 不存在的目标经符号链接访问 → 不抛异常，返回不存在（越界检测只针对已存在路径）
            assertNull(source.stat("out-link/ghost.txt"));

            // 写入路径：目标不存在但父目录是越界链接 → 拒绝
            assertThrows(SecurityException.class,
                    () -> source.openWrite("out-link/new.txt", WriteOptions.replace()));
            assertFalse(Files.exists(outside.resolve("new.txt")));
        } finally {
            Files.walk(outside).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        }
    }

    @Test
    public void writeIntoExistingParentUnderMissingRootIsAllowedForLazyCreation() throws Exception {
        // AGENTS 挂载根目录可能尚未创建：写入时应按需创建根目录，读取时返回不存在
        Path root = tempDir.resolve("lazy-agents-root");
        FileMountSource source = FileMountSource.of(root);

        assertNull(source.stat("agent.md"));
        try (OutputStream out = source.openWrite("agent.md", WriteOptions.replace())) {
            out.write("agent".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(Files.exists(root.resolve("agent.md")));
        assertEquals("agent", readText(source.openRead("agent.md")));
    }

    @Test
    public void capabilitiesAndSchemeShouldExposeLocalFileSemantics() {
        FileMountSource source = FileMountSource.of(newSourceDirQuiet("cap-root"));
        assertEquals("file", source.getScheme());
        assertEquals(true, source.getLocation().contains("cap-root"));

        assertTrue(source.capabilities().isReadable());
        assertTrue(source.capabilities().isWritable());
        assertTrue(source.capabilities().isLocalPathAccessible());
        assertTrue(source.capabilities().isShellAccessible());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
