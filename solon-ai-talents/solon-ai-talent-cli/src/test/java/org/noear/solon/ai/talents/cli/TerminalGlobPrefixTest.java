package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.mount.MountManager;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * glob 字面前缀剪枝：什么时候剪、剪到哪里，以及剪枝后安全语义（忽略目录、受保护目录、物化缓存、链接、500 上限）不变。
 */
class TerminalGlobPrefixTest {

    private static List<String> rel(Path base, List<Path> dirs) {
        List<String> list = new ArrayList<>();
        for (Path d : dirs) {
            list.add(base.relativize(d).toString().replace("\\", "/"));
        }
        return list;
    }

    private static List<String> prefix(Path ws, String pattern) {
        return rel(ws, TerminalSupport.globLiteralPrefixDirs(ws, pattern));
    }

    private static boolean symlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | SecurityException | FileSystemException e) {
            return false;
        }
    }

    @Test
    void prefixDirsFollowLiteralSegmentsOnly(@TempDir Path tmp) throws IOException {
        Path ws = tmp.toRealPath();
        SearchFixture.write(ws.resolve("src/main/java/Foo.java"), "x");
        SearchFixture.write(ws.resolve("a/b/c/d.txt"), "x");
        SearchFixture.write(ws.resolve("file.txt"), "x");
        SearchFixture.write(ws.resolve("with space/中文/x.md"), "x");

        assertEquals(Arrays.asList("src", "src/main"), prefix(ws, "src/main/**/*.java"));
        assertEquals(Arrays.asList("src", "src/main", "src/main/java"), prefix(ws, "src/main/java/Foo.java"));
        assertEquals(Arrays.asList("a", "a/b", "a/b/c"), prefix(ws, "a/b/c/d.txt"));
        assertEquals(Arrays.asList("with space", "with space/中文"), prefix(ws, "with space/中文/*.md"));
        // 最后一段是文件名部分，不参与
        assertEquals(Collections.singletonList("src"), prefix(ws, "src/main"));
        assertEquals(Collections.singletonList("src"), prefix(ws, "src/"));

        // 不以字面目录开头
        assertEquals(Collections.emptyList(), prefix(ws, "**/*.java"));
        assertEquals(Collections.emptyList(), prefix(ws, "*.java"));
        assertEquals(Collections.emptyList(), prefix(ws, "Foo.java"));
        assertEquals(Collections.emptyList(), prefix(ws, "{src,a}/**"));
        assertEquals(Collections.emptyList(), prefix(ws, "[s]rc/**"));
        assertEquals(Collections.emptyList(), prefix(ws, "s?c/**"));
        assertEquals(Collections.emptyList(), prefix(ws, "sr*/**"));
        assertEquals(Collections.emptyList(), prefix(ws, "src\\main/**"));
        // 中途遇到通配段即停
        assertEquals(Collections.singletonList("src"), prefix(ws, "src/m*n/java/**"));
    }

    @Test
    void prefixDirsRejectUnsafeOrAmbiguousSegments(@TempDir Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.toRealPath().resolve("ws"));
        SearchFixture.write(ws.resolve("src/main/x.java"), "x");
        SearchFixture.write(ws.resolve("file.txt"), "x");
        SearchFixture.write(tmp.resolve("sibling/y.java"), "y");

        // 绝对路径、盘符
        assertEquals(Collections.emptyList(), prefix(ws, "/src/**"));
        assertEquals(Collections.emptyList(), prefix(ws, ws.toString().replace("\\", "/") + "/src/**"));
        assertEquals(Collections.emptyList(), prefix(ws, "C:/src/**"));
        // . 与 ..：原遍历得到的相对路径不含这两种段，不能剪到别处
        assertEquals(Collections.emptyList(), prefix(ws, "./src/**"));
        assertEquals(Collections.emptyList(), prefix(ws, "../sibling/**"));
        assertEquals(Collections.singletonList("src"), prefix(ws, "src/../src/**"));
        assertEquals(Collections.singletonList("src"), prefix(ws, "src/./main/**"));
        // 空段
        assertEquals(Collections.singletonList("src"), prefix(ws, "src//main/**"));
        // 不存在、是文件
        assertEquals(Collections.emptyList(), prefix(ws, "missing/**"));
        assertEquals(Collections.singletonList("src"), prefix(ws, "src/missing/**"));
        assertEquals(Collections.emptyList(), prefix(ws, "file.txt/**"));
        // 大小写不同：Unix 下 glob 区分大小写，原实现没有结果；不能借大小写不敏感的文件系统解析到 src
        assertEquals(Collections.emptyList(), prefix(ws, "SRC/**"));
        // target 不是目录 / 为空
        assertEquals(Collections.emptyList(), prefix(ws.resolve("file.txt"), "a/**"));
        assertEquals(Collections.emptyList(), TerminalSupport.globLiteralPrefixDirs(null, "a/**"));
        assertEquals(Collections.emptyList(), TerminalSupport.globLiteralPrefixDirs(ws, null));
        assertEquals(Collections.emptyList(), TerminalSupport.globLiteralPrefixDirs(ws, ""));
    }

    @Test
    void prefixDirsDoNotEnterSymlinkDirectories(@TempDir Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.toRealPath().resolve("ws"));
        SearchFixture.write(ws.resolve("real/inner/x.java"), "x");
        assumeTrue(symlink(ws.resolve("link"), ws.resolve("real")), "symlink not supported");
        assertTrue(symlink(ws.resolve("real/inner-link"), ws.resolve("real/inner")));

        // 原遍历不跟随链接目录：链接后面不可能有结果，也不能从链接目标开始遍历
        assertEquals(Collections.emptyList(), prefix(ws, "link/**"));
        assertEquals(Collections.emptyList(), prefix(ws, "link/inner/**"));
        assertEquals(Collections.singletonList("real"), prefix(ws, "real/inner-link/**"));
    }

    @Test
    void prefixDirsRequireUniqueMatchUnderPlatformCaseRules(@TempDir Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.toRealPath().resolve("ws"));
        Files.createDirectories(ws.resolve("dup"));
        boolean caseSensitiveFs;
        try {
            Files.createDirectory(ws.resolve("Dup"));
            caseSensitiveFs = true;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            caseSensitiveFs = false;
        }
        boolean caseInsensitiveGlob = ws.getFileSystem().getPathMatcher("glob:dup").matches(ws.getFileSystem().getPath("DUP"));
        if (caseSensitiveFs && caseInsensitiveGlob) {
            // 如 Windows 目录开启了区分大小写：两个目录都能被匹配，不能只遍历其中一个
            assertEquals(Collections.emptyList(), prefix(ws, "dup/**"));
        } else {
            assertEquals(Collections.singletonList("dup"), prefix(ws, "dup/**"));
        }
        if (!caseInsensitiveGlob) {
            // 区分大小写的匹配：写法不同的段不能借文件系统解析过去
            assertEquals(caseSensitiveFs ? Collections.singletonList("Dup") : Collections.emptyList(), prefix(ws, "Dup/**"));
        }
    }

    // ---------- 剪枝后 glob 的行为 ----------

    private static TerminalTalent talent(Path ws, Path cacheRoot) {
        return new TerminalTalent(new MountManager(ws.toString()), ShellCommandFactory.detect(), cacheRoot, ".solon/");
    }

    private static String glob(TerminalTalent t, String pattern, Path ws) throws IOException {
        return t.glob(pattern, ".", ws.toString(), null).getContent();
    }

    @Test
    void prunedGlobKeepsSkippingIgnoredAndProtectedIntermediateDirs(@TempDir Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        SearchFixture.write(ws.resolve("src/main/java/Foo.java"), "x");
        SearchFixture.write(ws.resolve("target/classes/deep/X.java"), "x");
        SearchFixture.write(ws.resolve("node_modules/pkg/lib/index.java"), "x");
        SearchFixture.write(ws.resolve("nested/.solon/commands/sub/c.md"), "x");
        SearchFixture.write(ws.resolve("cache-root/mount/sub/z.txt"), "x");
        SearchFixture.write(ws.resolve("cache-root/other/sub/z.txt"), "x");
        TerminalTalent t = talent(ws, ws.resolve("cache-root/mount"));
        String none = LegacySearch.glob(t, "no-such-file-anywhere", ".", ws.toString());

        assertEquals("[FILE] src/main/java/Foo.java", glob(t, "src/main/java/*.java", ws));
        assertEquals("[FILE] src/main/java/Foo.java", glob(t, "src/main/**/Foo.java", ws));
        // 中间层是忽略目录
        assertEquals(none, glob(t, "target/classes/deep/*.java", ws));
        assertEquals(none, glob(t, "node_modules/pkg/lib/*.java", ws));
        assertEquals(none, glob(t, "nested/.solon/commands/sub/*.md", ws));
        // 中间层是物化缓存目录（不在忽略列表里，只靠读拒绝判定）
        assertEquals(none, glob(t, "cache-root/mount/sub/*.txt", ws));
        assertEquals("[FILE] cache-root/other/sub/z.txt", glob(t, "cache-root/other/sub/*.txt", ws));

        // 沙盒关闭时物化缓存仍然拒绝
        t.setSandboxEnabled(false);
        assertEquals(none, glob(t, "cache-root/mount/sub/*.txt", ws));
        assertEquals("[FILE] cache-root/other/sub/z.txt", glob(t, "cache-root/other/sub/*.txt", ws));
    }

    @Test
    void prunedGlobKeepsMandatoryDenyOnFiles(@TempDir Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        SearchFixture.write(ws.resolve("conf/.bashrc"), "x");
        SearchFixture.write(ws.resolve("conf/.mcp.json"), "x");
        SearchFixture.write(ws.resolve("conf/ok.txt"), "x");
        TerminalTalent t = talent(ws, tmp.resolve("home/.solon/cache/mount"));

        assertEquals("[FILE] conf/ok.txt", glob(t, "conf/*", ws));
        assertEquals("[FILE] conf/ok.txt", glob(t, "conf/**", ws));
        assertFalse(glob(t, "conf/.bashrc", ws).contains(".bashrc"));
        assertFalse(glob(t, "**/.mcp.json", ws).contains(".mcp.json"));
    }

    @Test
    void prunedGlobDoesNotEscapeThroughSymlinks(@TempDir Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        SearchFixture.write(outside.resolve("deep/secret.txt"), "x");
        SearchFixture.write(ws.resolve("dir/ok.txt"), "x");
        assumeTrue(symlink(ws.resolve("out"), outside), "symlink not supported");
        assertTrue(symlink(ws.resolve("dir/out"), outside));
        TerminalTalent t = talent(ws, tmp.resolve("home/.solon/cache/mount"));
        String none = LegacySearch.glob(t, "no-such-file-anywhere", ".", ws.toString());

        assertEquals(none, glob(t, "out/deep/*.txt", ws));
        assertEquals(none, glob(t, "dir/out/deep/*.txt", ws));
        assertEquals("[FILE] dir/ok.txt", glob(t, "dir/*.txt", ws));
        // 指向工作区外的链接条目本身也不列出（沙盒边界）
        assertEquals("[FILE] dir/ok.txt", glob(t, "dir/**", ws));
        assertEquals(LegacySearch.glob(t, "dir/**", ".", ws.toString()), glob(t, "dir/**", ws));

        // 开放模式同样不跟随链接目录；链接条目本身按原实现列出
        t.setSandboxEnabled(false);
        assertEquals(none, glob(t, "out/deep/*.txt", ws));
        assertEquals(LegacySearch.glob(t, "dir/**", ".", ws.toString()), glob(t, "dir/**", ws));
        assertTrue(glob(t, "dir/**", ws).contains("dir/out"));
    }

    @Test
    void prunedGlobKeepsTheSameFirst500Results(@TempDir Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 180; j++) {
                SearchFixture.write(ws.resolve("big/d" + i + "/f" + j + ".txt"), "x");
            }
        }
        SearchFixture.write(ws.resolve("other/f.txt"), "x");
        TerminalTalent t = talent(ws, tmp.resolve("home/.solon/cache/mount"));

        for (String pattern : Arrays.asList("big/**/*.txt", "big/**", "big/d1/*.txt", "**/*.txt")) {
            String expected = LegacySearch.glob(t, pattern, ".", ws.toString());
            String actual = glob(t, pattern, ws);
            assertEquals(expected, actual, pattern);
        }
        assertEquals(500, glob(t, "big/**/*.txt", ws).split("\n").length);
    }

    @Test
    void prunedGlobWorksOnMountPath(@TempDir Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path pool = Files.createDirectories(tmp.resolve("pool"));
        SearchFixture.write(pool.resolve("skills/a/SKILL.md"), "x");
        SearchFixture.write(pool.resolve("skills/a/.bashrc"), "x");
        SearchFixture.write(pool.resolve("other/b.md"), "x");
        MountManager mm = new MountManager(ws.toString());
        mm.register(org.noear.solon.ai.mount.Mount.builder()
                .alias("@pool")
                .source(org.noear.solon.ai.mount.source.FileMountSource.of(pool))
                .type(org.noear.solon.ai.mount.MountType.FILES)
                .build());
        TerminalTalent t = new TerminalTalent(mm, ShellCommandFactory.detect(), tmp.resolve("home/.solon/cache/mount"), ".solon/");

        assertEquals("[FILE] @pool/skills/a/SKILL.md", t.glob("skills/**", "@pool", ws.toString(), null).getContent());
        assertEquals(LegacySearch.glob(t, "skills/a/*", "@pool", ws.toString()),
                t.glob("skills/a/*", "@pool", ws.toString(), null).getContent());
    }

    /**
     * 有意的行为改进：原实现遍历全树时碰到不可读目录会整体抛错；带字面前缀的模式现在不再遍历无关目录，不受其影响。
     * 不带前缀的模式行为不变（仍会遍历到该目录）。
     */
    @Test
    void prunedGlobIsNotBrokenByUnrelatedUnreadableDir(@TempDir Path tmp) throws IOException {
        assumeTrue(!SearchFixture.WINDOWS);
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        SearchFixture.write(ws.resolve("src/Foo.java"), "x");
        Path locked = Files.createDirectories(ws.resolve("locked"));
        SearchFixture.write(locked.resolve("x.java"), "x");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assumeTrue(!Files.isReadable(locked), "running as root");
            TerminalTalent t = talent(ws, tmp.resolve("home/.solon/cache/mount"));
            assertEquals("[FILE] src/Foo.java", glob(t, "src/*.java", ws));

            String legacy;
            try {
                legacy = LegacySearch.glob(t, "src/*.java", ".", ws.toString());
            } catch (IOException e) {
                legacy = "EX:" + e.getClass().getSimpleName();
            }
            assertEquals("EX:AccessDeniedException", legacy);

            String full;
            try {
                full = glob(t, "**/*.java", ws);
            } catch (IOException e) {
                full = "EX:" + e.getClass().getSimpleName();
            }
            assertEquals("EX:AccessDeniedException", full);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }
}
