package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.noear.solon.ai.mount.MountManager;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * glob / grep 优化前后耗时对比（手动运行：{@code mvn test -Dtest=TerminalSearchBenchmarkTest -Dsearch.bench=true}，
 * 可用 {@code -Dsearch.bench.root=目录} 指定被搜索的目录，默认是本仓库根目录）。同时校验两边结果一致。
 */
@EnabledIfSystemProperty(named = "search.bench", matches = "true")
class TerminalSearchBenchmarkTest {

    @Test
    void compareLegacyAndOptimized() throws Exception {
        Path root = Paths.get(System.getProperty("search.bench.root", "../..")).toAbsolutePath().normalize();
        String cwd = root.toString();
        List<String> globs = Arrays.asList(
                "**/pom.xml",
                "**/TerminalTalent.java",
                "**/*.java",
                "solon-ai-talents/**/*.java",
                "solon-ai-talents/solon-ai-talent-cli/src/main/**/*.java");

        System.out.println("[bench] root=" + root);
        for (boolean sandbox : new boolean[]{true, false}) {
            TerminalTalent t = new TerminalTalent(new MountManager(cwd));
            t.setSandboxEnabled(sandbox);
            for (String pattern : globs) {
                String legacy = LegacySearch.glob(t, pattern, ".", cwd);
                String now = t.glob(pattern, ".", cwd, null).getContent();
                assertEquals(legacy, now, pattern);
                long a = median(() -> LegacySearch.glob(t, pattern, ".", cwd));
                long b = median(() -> t.glob(pattern, ".", cwd, null).getContent());
                report("glob", pattern, sandbox, legacy.split("\n").length, a, b);
            }
            for (String[] grepCase : GREP_CASES) {
                String pattern = grepCase[0];
                String include = grepCase[1];
                String legacy = LegacySearch.grepReplacing(t, pattern, ".", include, cwd);
                assertEquals(legacy, t.grep(pattern, ".", include, cwd, null).getContent(), pattern + " / " + include);
                long a = median(() -> LegacySearch.grep(t, pattern, ".", include, cwd));
                long b = median(() -> t.grep(pattern, ".", include, cwd, null).getContent());
                report("grep", pattern + " (" + include + ")", sandbox, legacy.split("\n").length, a, b);
            }

            for (String[] lsCase : LS_CASES) {
                String path = lsCase[0];
                boolean recursive = Boolean.parseBoolean(lsCase[1]);
                String flatLegacy = recursive
                        ? LegacyList.tree(t, path, false, cwd)
                        : LegacyList.flat(t, path, false, cwd);
                String flatNow = t.ls(path, recursive, false, cwd, null).getContent();
                assertEquals(flatLegacy, flatNow, "ls " + path + " recursive=" + recursive);
                long la = median(() -> recursive ? LegacyList.tree(t, path, false, cwd) : LegacyList.flat(t, path, false, cwd));
                long lb = median(() -> t.ls(path, recursive, false, cwd, null).getContent());
                report("ls", path + (recursive ? " -r" : " -1"), sandbox, flatLegacy.split("\n").length, la, lb);
            }
        }
    }

    /** grep 基准场景：字面量（走字节预筛）、字面量无命中、正则（不预筛）、不带 include。 */
    private static final List<String[]> GREP_CASES = Arrays.asList(
            new String[]{"class TerminalSupport", "*.java"},
            new String[]{"no-such-literal-anywhere-42", "*.java"},
            new String[]{"class\\s+Terminal\\w+Test", "*.java"},
            new String[]{"class TerminalSupport", null});

    /** ls 基准场景：目录树深度有限，取有代表性的目录。 */
    private static final List<String[]> LS_CASES = Arrays.asList(
            new String[]{".", "false"},
            new String[]{".", "true"},
            new String[]{"solon-ai-talents", "true"},
            new String[]{"solon-ai-talents/solon-ai-talent-cli/src", "true"});

    private static void report(String tool, String pattern, boolean sandbox, int lines, long legacyNs, long nowNs) {
        System.out.println(String.format("[bench] %s | %-58s | sandbox=%-5s | lines=%4d | legacy=%8.1f ms | now=%8.1f ms | x%.1f",
                tool, pattern, sandbox, lines, legacyNs / 1e6, nowNs / 1e6, (double) legacyNs / nowNs));
    }

    private static long median(Callable<String> call) throws Exception {
        for (int i = 0; i < 2; i++) {
            call.call();
        }
        long[] times = new long[5];
        for (int i = 0; i < times.length; i++) {
            long s = System.nanoTime();
            call.call();
            times[i] = System.nanoTime() - s;
        }
        Arrays.sort(times);
        return times[times.length / 2];
    }
}
