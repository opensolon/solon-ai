package org.noear.solon.ai.i18n.testkit;

import org.junit.jupiter.api.Assertions;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.I18nMessages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审校过的文案快照（{@code i18n-golden/messages_<语言>.txt}），用作用例的期望值
 *
 * <p>用例里用 {@code Xxx.get(locale, key)} 现取期望值时，资源被改坏，期望值也跟着变，断言校验不到文案本身。
 * 期望值改从本快照取，就和现行资源脱钩了：资源被改坏，用例失败。</p>
 *
 * <p>快照每行一个 key，值用双引号包裹并转义换行、制表、反斜杠与引号，首尾空白清晰可见。
 * 快照由 {@link #verifySnapshot} 维护（与资源逐字一致，-Dgolden.update=true 时重新生成，需人工审校）。</p>
 */
public final class FrozenMessages {
    private static final Map<Path, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private final Map<String, String> values;

    private FrozenMessages(Map<String, String> values) {
        this.values = values;
    }

    /**
     * 某语言的快照（位于当前模块的 {@code src/test/resources/i18n-golden/messages_<语言>.txt}；
     * null 为默认语言，地区变体按语言归并）
     */
    public static FrozenMessages of(Locale locale) {
        Path file = fileOf(AiI18n.resolve(locale));
        Map<String, String> values = CACHE.get(file);
        if (values == null) {
            values = load(file);
            CACHE.put(file, values);
        }
        return new FrozenMessages(values);
    }

    public String get(String key) {
        String val = values.get(key);
        Assertions.assertNotNull(val, "key not in frozen messages (regenerate after review): " + key);
        return val;
    }

    /**
     * 取值并替换命名占位符（name1, value1, name2, value2...）
     */
    public String fmt(String key, Object... nameValues) {
        Map<String, Object> vars = new LinkedHashMap<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            vars.put((String) nameValues[i], nameValues[i + 1]);
        }
        return AiI18n.format(get(key), vars);
    }

    // ---------------- 快照维护 ----------------

    /**
     * 校验快照与资源逐字一致（-Dgolden.update=true 时重新生成）
     */
    public static void verifySnapshot(I18nMessages messages, Locale locale) throws IOException {
        Path file = fileOf(locale);
        String actual = render(messages.messages(locale));

        if (GoldenFiles.UPDATE) {
            Files.createDirectories(file.getParent());
            Files.write(file, actual.getBytes(StandardCharsets.UTF_8));
            CACHE.remove(file);
            return;
        }

        Assertions.assertTrue(Files.exists(file), "missing snapshot (run with -Dgolden.update=true): " + file);
        String expected = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (expected.equals(actual) == false) {
            Assertions.fail("Messages snapshot mismatch: " + file + "\n" + diff(expected, actual));
        }
    }

    static Path fileOf(Locale locale) {
        return GoldenFiles.GOLDEN_DIR.resolve("messages_" + locale.getLanguage() + ".txt");
    }

    public static String render(Map<String, String> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> kv : new TreeMap<>(messages).entrySet()) {
            sb.append(kv.getKey()).append(" = \"").append(escape(kv.getValue())).append("\"\n");
        }
        return sb.toString();
    }

    static Map<String, String> load(Path file) {
        try {
            Map<String, String> map = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                int eq = line.indexOf(" = \"");
                Assertions.assertTrue(eq > 0 && line.endsWith("\""), "bad snapshot line: " + line);
                map.put(line.substring(0, eq), unescape(line.substring(eq + 4, line.length() - 1)));
            }
            return Collections.unmodifiableMap(map);
        } catch (IOException e) {
            throw new IllegalStateException("missing frozen messages: " + file, e);
        }
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    default:
                        sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String diff(String expected, String actual) {
        String[] a = expected.split("\n", -1);
        String[] b = actual.split("\n", -1);
        List<String> lines = new ArrayList<>();
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n && lines.size() < 20; i++) {
            String x = i < a.length ? a[i] : "<eof>";
            String y = i < b.length ? b[i] : "<eof>";
            if (x.equals(y) == false) {
                lines.add("  expected: " + x + "\n  actual  : " + y);
            }
        }
        return String.join("\n", lines);
    }
}
