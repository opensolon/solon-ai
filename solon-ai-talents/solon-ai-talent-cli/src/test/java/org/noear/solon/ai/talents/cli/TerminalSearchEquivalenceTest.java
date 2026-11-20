package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.mount.Mount;
import org.noear.solon.ai.mount.MountManager;
import org.noear.solon.ai.mount.MountType;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.sandbox.config.FilesystemConfig;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * glob / grep 优化前后的差分测试：同一目录树、同一配置下，新实现与 {@link LegacySearch}（优化前逐字副本）结果必须完全一致；
 * 读拒绝判定器 {@link TerminalSupport.WalkReadGuard} 与 {@link TerminalSupport#isReadDenied} 逐条目一致。
 *
 * <p>覆盖：沙盒开/关；物化缓存根不存在/存在、在用户目录/工作区内；受保护状态目录；挂载目录；符号链接（文件/目录、
 * 指向工作区内受保护目录/工作区外/缓存/回环/断链）；macOS 临时目录 /var → /private/var 的真实路径差异；
 * 随机目录树；带字面前缀与不带前缀的各类模式。</p>
 */
class TerminalSearchEquivalenceTest {

    enum CacheMode {NONE, HOME_ABSENT, HOME_EXISTS, WS_EXISTS, WS_ABSENT}

    static final class Env {
        Path ws;
        Path outside;
        Path pool;
        Path home;
        boolean links;
    }

    /** 共享目录树的根（自建临时目录，JVM 退出时删除；供本类与 ls 差分测试共用）。 */
    private static Path fallbackBase;

    static final List<Env> ENVS = new ArrayList<>();

    @BeforeAll
    static void setUp() throws IOException {
        ensureEnvs();
    }

    /** 共享目录树的根：自建临时目录（JUnit 的 @TempDir 在本类结束后会被删除，其它类复用时不安全）。 */
    private static synchronized Path baseDir() throws IOException {
        if (fallbackBase == null) {
            fallbackBase = Files.createTempDirectory("cli-equiv");
            final Path created = fallbackBase;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteQuietly(created)));
        }
        return fallbackBase;
    }

    private static void deleteQuietly(Path root) {
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** 构建（或复用）共享的差分测试目录树，供本类与 ls 差分测试共用。 */
    static synchronized void ensureEnvs() throws IOException {
        if (!ENVS.isEmpty()) {
            return;
        }
        Path base0 = baseDir();
        for (long seed : new long[]{7L, 2026L}) {
            Path base = Files.createDirectories(base0.resolve("env" + seed));
            Env env = new Env();
            env.ws = Files.createDirectories(base.resolve("ws"));
            env.outside = Files.createDirectories(base.resolve("outside"));
            env.pool = Files.createDirectories(base.resolve("pool"));
            env.home = Files.createDirectories(base.resolve("home"));
            SearchFixture.fixed(env.ws);
            SearchFixture.random(env.ws, seed, 3);
            SearchFixture.random(env.pool, seed + 1, 2);
            env.links = SearchFixture.links(env.ws, env.outside, env.pool);
            ENVS.add(env);
        }
    }

    static Path cacheRoot(Env env, CacheMode mode) throws IOException {
        switch (mode) {
            case HOME_ABSENT:
                return env.home.resolve(".solon/cache/mount");
            case HOME_EXISTS:
                return Files.createDirectories(env.home.resolve(".solon-exists/cache/mount"));
            case WS_EXISTS:
                return env.ws.resolve("cache-root/mount");
            case WS_ABSENT:
                return env.ws.resolve("cache-root/notyet/mount");
            default:
                return null;
        }
    }

    static TerminalTalent talent(Env env, CacheMode mode, boolean sandbox, boolean state) throws IOException {
        MountManager mm = new MountManager(env.ws.toString());
        mm.register(Mount.builder()
                .alias("@pool")
                .source(FileMountSource.of(env.pool))
                .type(MountType.FILES)
                .writable(false)
                .build());
        TerminalTalent t = new TerminalTalent(mm, ShellCommandFactory.detect(), cacheRoot(env, mode), ".solon/");
        t.setSandboxEnabled(sandbox);
        if (state) {
            t.setProtectedStatePaths(Collections.singletonList("sessions"));
        }
        return t;
    }

    /** 所有要比对的 talent 配置。 */
    static List<Object[]> configs() {
        List<Object[]> list = new ArrayList<>();
        for (CacheMode mode : CacheMode.values()) {
            for (boolean sandbox : new boolean[]{true, false}) {
                list.add(new Object[]{mode, sandbox, false});
            }
        }
        list.add(new Object[]{CacheMode.WS_EXISTS, true, true});
        list.add(new Object[]{CacheMode.HOME_ABSENT, true, true});
        return list;
    }

    static final List<String> PATHS = Arrays.asList(".", "src", "a", "nested", "@pool");

    static List<String> globPatterns(Env env) {
        List<String> list = new ArrayList<>(Arrays.asList(
                "**/*", "**", "*", "**/*.java", "**/Foo.java", "*.txt", "**/*.txt", "**/*.{txt,md}",
                "src/**", "src/main/**/*.java", "src/main/java/Foo.java", "src/main/java/*.java",
                "src/*/java/**", "src/**/Bar.java", "SRC/**", "Src/**", "src/../src/**", "./src/**",
                "src//main/**", "src/", "{src,a}/**", "[s]rc/**", "src\\main\\**", "a/b/c/d.txt",
                "a/link-up/**", "link-in-dir/**", "link-out-dir/**", "secret/keys/**", "secret/**",
                "sub/.vscode/**", "sub/.git/hooks/*", ".bashrc", "**/.bashrc", "**/.mcp.json",
                "nested/.solon/**", "nested/.solon/commands/*", "nested/.solon/sessions/**",
                "cache-root/**", "cache-root/mount/*", "target/**", "target/classes/*.java",
                "node_modules/**", "main/**", "java/**", "中文目录/**", "with space/**", "docs/*.md",
                "missing/**", "*.java", "link-*", "**/link-*", "broken.txt", "odd\\name.txt",
                "link-ws/**", "link-ws/src/**"
        ));
        // 绝对路径模式：走 matcher.matches(file) 分支，不参与前缀剪枝
        list.add(env.ws.toString().replace("\\", "/") + "/**/*.java");
        list.add(env.ws.toString().replace("\\", "/") + "/src/**");
        list.add("/**/*.txt");
        return list;
    }

    static String outcome(Callable<String> call) {
        try {
            return call.call();
        } catch (Throwable e) {
            return "EX:" + e.getClass().getName() + ":" + e.getMessage();
        }
    }

    @Test
    void globMatchesLegacyImplementation() throws Exception {
        int compared = 0;
        for (Env env : ENVS) {
            String cwd = env.ws.toString();
            for (Object[] c : configs()) {
                TerminalTalent t = talent(env, (CacheMode) c[0], (Boolean) c[1], (Boolean) c[2]);
                for (String path : PATHS) {
                    for (String pattern : globPatterns(env)) {
                        String expected = outcome(() -> LegacySearch.glob(t, pattern, path, cwd));
                        String actual = outcome(() -> t.glob(pattern, path, cwd, null).getContent());
                        assertEquals(expected, actual, "glob pattern=" + pattern + " path=" + path
                                + " cache=" + c[0] + " sandbox=" + c[1] + " state=" + c[2] + " ws=" + env.ws);
                        compared++;
                    }
                }
            }
        }
        assertTrue(compared > 1000, "compared=" + compared);
    }

    @Test
    void grepMatchesLegacyImplementation() throws Exception {
        List<String> patterns = Arrays.asList("token", "TODO", "^token", "[unclosed", "nothing-matches-this");
        List<String> includes = Arrays.asList(null, "*.java", "*.{txt,md}", "*.TXT");
        int compared = 0;
        for (Env env : ENVS) {
            String cwd = env.ws.toString();
            for (Object[] c : configs()) {
                TerminalTalent t = talent(env, (CacheMode) c[0], (Boolean) c[1], (Boolean) c[2]);
                for (String path : PATHS) {
                    for (String pattern : patterns) {
                        for (String include : includes) {
                            String expected = outcome(() -> LegacySearch.grepReplacing(t, pattern, path, include, cwd));
                            String actual = outcome(() -> t.grep(pattern, path, include, cwd, null).getContent());
                            assertEquals(expected, actual, "grep pattern=" + pattern + " include=" + include + " path=" + path
                                    + " cache=" + c[0] + " sandbox=" + c[1] + " state=" + c[2]);
                            compared++;
                        }
                    }
                }
            }
        }
        assertTrue(compared > 1000, "compared=" + compared);
    }

    @Test
    void grepTruncationMatchesLegacyImplementation() throws Exception {
        Env env = ENVS.get(0);
        TerminalTalent t = talent(env, CacheMode.HOME_ABSENT, true, false);
        t.setMaxCharacterLimit(300);
        String cwd = env.ws.toString();
        String expected = LegacySearch.grepReplacing(t, "token", ".", null, cwd);
        assertEquals(expected, t.grep("token", ".", null, cwd, null).getContent());
    }

    static List<FilesystemConfig> fsConfigs() {
        return Arrays.asList(
                null,
                new FilesystemConfig(Collections.singletonList("secret/keys"), null, null, null, null),
                new FilesystemConfig(Collections.singletonList("a"), Collections.singletonList("a/b"), null, null, null),
                new FilesystemConfig(Collections.singletonList("."), Collections.singletonList("src"), null, null, null),
                new FilesystemConfig(Arrays.asList("link-in-dir", "src/main"), Collections.singletonList("src/main/java/org"), null, null, null));
    }

    /**
     * 判定器逐条目与原实现一致：遍历所有条目（不剪枝，被拒绝的目录也进入），每个条目同时问两边。
     */
    @Test
    void walkReadGuardMatchesIsReadDeniedForEveryEntry() throws Exception {
        long derivedTotal = 0;
        for (Env env : ENVS) {
            for (Object[] c : configs()) {
                TerminalTalent t = talent(env, (CacheMode) c[0], (Boolean) c[1], (Boolean) c[2]);
                TerminalSupport support = LegacySearch.support(t);
                boolean sandbox = (Boolean) c[1];
                for (FilesystemConfig fs : fsConfigs()) {
                    derivedTotal += compareWalk(support, env.ws, env.ws, sandbox, fs, false);
                    derivedTotal += compareWalk(support, env.ws, env.ws.resolve("src"), sandbox, fs, false);
                    derivedTotal += compareWalk(support, env.pool, env.pool, sandbox, fs, false);
                    // 策略根为工作区、遍历挂载目录（越界场景）
                    derivedTotal += compareWalk(support, env.ws, env.pool, sandbox, fs, false);
                    // 跟随链接遍历：模拟旧 JDK 下 walkFileTree 会进入 Windows junction 的情形
                    derivedTotal += compareWalk(support, env.ws, env.ws, sandbox, fs, true);
                }
                // 起点未规范化：判定器退回原实现
                assertEquals(0, compareWalk(support, env.ws, env.ws.resolve("src").resolve("..").resolve("src"), sandbox, null, false));
                // 策略根不存在（真实路径解析失败）：沙盒模式下退回原实现
                long d = compareWalk(support, env.ws.resolve("no-such-root"), env.ws, sandbox, null, false);
                if (sandbox) {
                    assertEquals(0, d);
                }
            }
        }
        assertTrue(derivedTotal > 10000, "快速路径应覆盖绝大多数条目: " + derivedTotal);
    }

    /** 返回判定器走快速路径的条目数。 */
    private static long compareWalk(TerminalSupport support, Path policyRoot, Path start, boolean sandbox,
                                    FilesystemConfig fs, boolean followLinks) throws IOException {
        TerminalSupport.WalkReadGuard guard = support.newWalkReadGuard(policyRoot, start, sandbox, fs);
        int[] entries = {0};
        Files.walkFileTree(start, followLinks ? EnumSet.of(FileVisitOption.FOLLOW_LINKS) : EnumSet.noneOf(FileVisitOption.class),
                followLinks ? 6 : Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        BasicFileAttributes own = followLinks ? Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS) : attrs;
                        check(dir, own);
                        guard.enter(dir, own);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        BasicFileAttributes own = followLinks ? Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS) : attrs;
                        check(file, own);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        // 跟随链接时的回环、断链
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                        guard.exit(dir);
                        return FileVisitResult.CONTINUE;
                    }

                    private void check(Path entry, BasicFileAttributes attrs) {
                        entries[0]++;
                        boolean expected = support.isReadDenied(policyRoot, entry, sandbox, fs);
                        boolean actual = guard.isDenied(entry, attrs);
                        assertEquals(expected, actual, "entry=" + entry + " policyRoot=" + policyRoot + " start=" + start
                                + " sandbox=" + sandbox + " follow=" + followLinks + " fs=" + describe(fs));
                    }
                });
        assertTrue(entries[0] > 0);
        return guard.getDerivedCount();
    }

    private static String describe(FilesystemConfig fs) {
        return fs == null ? "null" : ("deny=" + fs.getDenyRead() + ",allow=" + fs.getAllowRead());
    }

    @Test
    void fixtureReallyExercisesLinksAndRealPathDifferences() throws IOException {
        Env env = ENVS.get(0);
        if (!SearchFixture.WINDOWS) {
            assertTrue(env.links, "当前平台应支持符号链接");
        }
        // 记录：临时目录是否经过链接（macOS 为 /var → /private/var），用于确认推算真实路径的场景被覆盖
        System.out.println("[fixture] ws=" + env.ws + " real=" + env.ws.toRealPath());
    }
}
