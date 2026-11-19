package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.sandbox.config.FilesystemConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ls 优化前后的差分测试：同一目录树、同一配置下，{@code ls}（扁平与递归两种）的结果必须与
 * {@link LegacyList}（优化前逐字副本）完全一致。
 *
 * <p>复用 {@link TerminalSearchEquivalenceTest} 的目录树与配置矩阵（沙盒开/关、物化缓存根的各种状态、
 * 受保护状态目录、符号链接、随机目录树），额外覆盖 {@link FilesystemConfig} 的 denyRead/allowRead 组合。</p>
 */
class TerminalListEquivalenceTest {

    @org.junit.jupiter.api.BeforeAll
    static void setUp() throws java.io.IOException {
        TerminalSearchEquivalenceTest.ensureEnvs();
    }

    /** 扁平列举与递归树的样例路径（含逻辑挂载路径与不存在的路径）。 */
    static final List<String> PATHS = Arrays.asList(".", "src", "a", "nested", "@pool", "secret");

    static List<FilesystemConfig> fsConfigs() {
        return TerminalSearchEquivalenceTest.fsConfigs();
    }

    @Test
    void flatListMatchesLegacyImplementation() throws Exception {
        int compared = 0;
        for (TerminalSearchEquivalenceTest.Env env : TerminalSearchEquivalenceTest.ENVS) {
            String cwd = env.ws.toString();
            for (Object[] c : TerminalSearchEquivalenceTest.configs()) {
                TerminalTalent t = TerminalSearchEquivalenceTest.talent(env,
                        (TerminalSearchEquivalenceTest.CacheMode) c[0], (Boolean) c[1], (Boolean) c[2]);
                for (String path : PATHS) {
                    for (boolean hidden : new boolean[]{false, true}) {
                        String expected = TerminalSearchEquivalenceTest.outcome(() -> LegacyList.flat(t, path, hidden, cwd));
                        String actual = TerminalSearchEquivalenceTest.outcome(() -> list(t, path, false, hidden, cwd));
                        assertEquals(expected, actual, "ls -1 path=" + path + " hidden=" + hidden
                                + " cache=" + c[0] + " sandbox=" + c[1] + " state=" + c[2]);
                        compared++;
                    }
                }
            }
        }
        assertTrue(compared > 250, "compared=" + compared);
    }

    @Test
    void treeListMatchesLegacyImplementation() throws Exception {
        int compared = 0;
        for (TerminalSearchEquivalenceTest.Env env : TerminalSearchEquivalenceTest.ENVS) {
            String cwd = env.ws.toString();
            for (Object[] c : TerminalSearchEquivalenceTest.configs()) {
                TerminalTalent t = TerminalSearchEquivalenceTest.talent(env,
                        (TerminalSearchEquivalenceTest.CacheMode) c[0], (Boolean) c[1], (Boolean) c[2]);
                for (String path : PATHS) {
                    for (boolean hidden : new boolean[]{false, true}) {
                        String expected = TerminalSearchEquivalenceTest.outcome(() -> LegacyList.tree(t, path, hidden, cwd));
                        String actual = TerminalSearchEquivalenceTest.outcome(() -> list(t, path, true, hidden, cwd));
                        assertEquals(expected, actual, "ls -r path=" + path + " hidden=" + hidden
                                + " cache=" + c[0] + " sandbox=" + c[1] + " state=" + c[2]);
                        compared++;
                    }
                }
            }
        }
        assertTrue(compared > 250, "compared=" + compared);
    }

    /**
     * 带 FilesystemConfig 的差分：直接把同一个配置注入 support 的判定路径。
     *
     * <p>{@code ls} 工具层当前恒传 null（见 {@code TerminalTalent#fs()}），这里用反射调用内部的
     * flatListLogic 重载，确保判定器在非 null 策略下也与原实现一致。</p>
     */
    @Test
    void flatListMatchesLegacyUnderFilesystemConfigs() throws Exception {
        int compared = 0;
        TerminalSearchEquivalenceTest.Env env = TerminalSearchEquivalenceTest.ENVS.get(0);
        String cwd = env.ws.toString();
        for (Object[] c : TerminalSearchEquivalenceTest.configs()) {
            TerminalTalent t = TerminalSearchEquivalenceTest.talent(env,
                    (TerminalSearchEquivalenceTest.CacheMode) c[0], (Boolean) c[1], (Boolean) c[2]);
            TerminalSupport support = LegacySearch.support(t);
            Path target = LegacyList.target(t, ".", cwd);
            Path policyRoot = support.getSandboxPolicyRoot(LegacyList.workPath(cwd), ".");
            for (FilesystemConfig fs : fsConfigs()) {
                for (boolean hidden : new boolean[]{false, true}) {
                    String expected = TerminalSearchEquivalenceTest.outcome(
                            () -> legacyFlat(support, LegacyList.workPath(cwd), policyRoot, target, hidden,
                                    (Boolean) c[1], fs));
                    String actual = TerminalSearchEquivalenceTest.outcome(
                            () -> support.flatListLogic(LegacyList.workPath(cwd), policyRoot, target, ".", hidden,
                                    (Boolean) c[1], fs, null));
                    assertEquals(expected, actual, "ls fs=" + describe(fs) + " hidden=" + hidden
                            + " sandbox=" + c[1] + " cache=" + c[0]);
                    compared++;
                }
            }
        }
        assertTrue(compared > 20, "compared=" + compared);
    }

    /** 直接调用工具方法，返回内容（异常按 outcome 口径转成字符串）。 */
    private static String list(TerminalTalent t, String path, boolean recursive, boolean hidden, String cwd) {
        return TerminalSearchEquivalenceTest.outcome(() -> t.ls(path, recursive, hidden, cwd, null).getContent());
    }

    /** 优化前 flatListLogic 的逐字副本（带 FilesystemConfig 参数）。 */
    private static String legacyFlat(TerminalSupport support, Path workPath, Path policyRoot, Path target,
                                     boolean showHidden, boolean sandboxEnabled, FilesystemConfig fs) {
        return TerminalSearchEquivalenceTest.outcome(() -> {
            try (java.util.stream.Stream<Path> stream = Files.list(target)) {
                List<String> lines = new ArrayList<>();
                java.util.Iterator<Path> it = stream.iterator();
                while (it.hasNext()) {
                    Path p = it.next();
                    if (support.isIgnored(workPath, p)) continue;
                    if (support.isReadDenied(policyRoot, p, sandboxEnabled, fs)) continue;
                    if (!showHidden && p.getFileName().toString().startsWith(".")) continue;
                    boolean isDir = Files.isDirectory(p);
                    String displayPath = support.formatDisplayPath(workPath, ".", target, p, sandboxEnabled);
                    lines.add((isDir ? "[DIR] " : "[FILE] ") + displayPath + (isDir ? "/" : ""));
                }
                Collections.sort(lines);
                return lines.isEmpty()
                        ? org.noear.solon.ai.talents.cli.i18n.CliI18n.get(null, org.noear.solon.ai.talents.cli.i18n.CliI18nKeys.TERMINAL_LS_EMPTY)
                        : String.join("\n", lines);
            }
        });
    }

    private static String describe(FilesystemConfig fs) {
        return fs == null ? "null" : ("deny=" + fs.getDenyRead() + ",allow=" + fs.getAllowRead());
    }

    /**
     * “是否目录”仍按跟随链接的口径（同原 {@link Files#isDirectory}）：
     * 指向目录的符号链接排为 [DIR]，断链当 [FILE]；指向工作区外的目录在沙盒模式下则被拒绝。
     */
    @Test
    void directoryDetectionStillFollowsLinks() throws Exception {
        TerminalSearchEquivalenceTest.Env env = TerminalSearchEquivalenceTest.ENVS.get(0);
        org.junit.jupiter.api.Assumptions.assumeTrue(env.links, "当前平台不支持符号链接");
        String cwd = env.ws.toString();

        // 沙盒开：工作区内的链接目录为 [DIR]，越界的 link-out-dir 被拒绝，断链为 [FILE]
        TerminalTalent sandboxed = TerminalSearchEquivalenceTest.talent(env,
                TerminalSearchEquivalenceTest.CacheMode.HOME_ABSENT, true, false);
        String flat = sandboxed.ls(".", false, false, cwd, null).getContent();
        List<String> lines = Arrays.asList(flat.split("\n"));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("[DIR] link-in-dir")), flat);
        assertTrue(lines.stream().noneMatch(l -> l.contains("link-out-dir")), flat);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("[FILE] broken.txt")), flat);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("[FILE] link-in-file.java")), flat);
        assertEquals(LegacyList.flat(sandboxed, ".", false, cwd), flat);

        // 沙盒关：越界链接目录也可见，且按跟随链接口径排为 [DIR]
        TerminalTalent open = TerminalSearchEquivalenceTest.talent(env,
                TerminalSearchEquivalenceTest.CacheMode.HOME_ABSENT, false, false);
        String openFlat = open.ls(".", false, false, cwd, null).getContent();
        List<String> openLines = Arrays.asList(openFlat.split("\n"));
        assertTrue(openLines.stream().anyMatch(l -> l.startsWith("[DIR] link-out-dir")), openFlat);
        assertEquals(LegacyList.flat(open, ".", false, cwd), openFlat);
        assertEquals(LegacyList.tree(open, ".", false, cwd), open.ls(".", true, false, cwd, null).getContent());
    }
}
