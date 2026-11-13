package org.noear.solon.ai.i18n.testkit;

import org.junit.jupiter.api.Assertions;
import org.noear.solon.ai.chat.tool.FunctionTool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 渲染快照（逐字节比对）
 *
 * <p>快照位于模块的 {@code src/test/resources/i18n-golden/}。只有显式加 {@code -Dgolden.update=true}
 * 才会写入（新增或覆盖）；快照缺失时直接失败——英文等新快照必须经人工审校后再生成，
 * 不能让“缺失即生成”把未审校的输出悄悄变成期望值。</p>
 */
public final class GoldenFiles {
    public static final Path GOLDEN_DIR = Paths.get("src/test/resources/i18n-golden");
    public static final boolean UPDATE = Boolean.getBoolean("golden.update");

    private GoldenFiles() {
    }

    /**
     * 逐个比对（全部比完再报告）
     */
    public static void verify(Map<String, String> actual) throws IOException {
        List<String> diffs = new ArrayList<>();

        for (Map.Entry<String, String> kv : actual.entrySet()) {
            String diff = check(kv.getKey(), kv.getValue());
            if (diff != null) {
                diffs.add(diff);
            }
        }

        Assertions.assertTrue(diffs.isEmpty(), "Golden mismatch:\n" + String.join("\n", diffs));
    }

    public static void verify(String name, String text) throws IOException {
        String diff = check(name, text);
        Assertions.assertNull(diff, "Golden mismatch:\n" + diff);
    }

    private static String check(String name, String text) throws IOException {
        Path file = GOLDEN_DIR.resolve(name + ".txt");
        String actual = (text == null ? "<null>" : text);

        if (UPDATE) {
            Files.createDirectories(GOLDEN_DIR);
            Files.write(file, actual.getBytes(StandardCharsets.UTF_8));
            return null;
        }

        if (Files.exists(file) == false) {
            return name + " (missing, run with -Dgolden.update=true after review)";
        }

        String expected = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (expected.equals(actual)) {
            return null;
        } else {
            return name + firstDiff(expected, actual);
        }
    }

    /**
     * 工具对模型可见的部分（名字、描述、输入 schema）
     */
    public static String dumpTool(FunctionTool tool) {
        return "name: " + tool.name()
                + "\n--- description ---\n" + tool.description()
                + "\n--- inputSchema ---\n" + tool.inputSchema();
    }

    /**
     * 多个工具（按名字排序：注解工具的顺序来自反射，不稳定）
     */
    public static String dumpTools(Collection<FunctionTool> tools) {
        List<FunctionTool> sorted = new ArrayList<>(tools);
        sorted.sort(Comparator.comparing(FunctionTool::name));

        StringBuilder sb = new StringBuilder();
        for (FunctionTool tool : sorted) {
            if (sb.length() > 0) {
                sb.append("\n\n===\n\n");
            }
            sb.append(dumpTool(tool));
        }
        return sb.toString();
    }

    public static String firstDiff(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        int from = Math.max(0, i - 40);
        return " @" + i + "\n  expected: " + snippet(a, from) + "\n  actual  : " + snippet(b, from);
    }

    private static String snippet(String s, int from) {
        int to = Math.min(s.length(), from + 120);
        return from >= s.length() ? "<eof>" : s.substring(from, to).replace("\n", "\\n");
    }
}
