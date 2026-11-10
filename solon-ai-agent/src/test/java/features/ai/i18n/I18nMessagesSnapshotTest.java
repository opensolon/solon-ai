package features.ai.i18n;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.i18n.AgentI18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 内置文案的逐字快照（加载后的最终值，与资源文件格式无关）
 *
 * <p>资源格式迁移、首尾空白标记、占位符改名等改造，都必须让本快照保持不变（占位符改名除外，
 * 需按映射核对后用 -Dgolden.update=true 重新生成）。快照每行一个 key，值用双引号包裹并转义
 * 换行、制表、反斜杠与引号，首尾空白因此清晰可见。</p>
 */
public class I18nMessagesSnapshotTest {
    static final Path GOLDEN_DIR = Paths.get("src/test/resources/i18n-golden");
    static final boolean UPDATE = Boolean.getBoolean("golden.update");

    @Test
    public void testZh() throws IOException {
        verify("messages_zh", Locale.CHINESE);
    }

    @Test
    public void testEn() throws IOException {
        verify("messages_en", Locale.ENGLISH);
    }

    static void verify(String name, Locale locale) throws IOException {
        AgentI18n.setBundleFactory(null);
        String actual = render(AgentI18n.messages(locale));
        Path file = GOLDEN_DIR.resolve(name + ".txt");

        if (UPDATE) {
            Files.createDirectories(GOLDEN_DIR);
            Files.write(file, actual.getBytes(StandardCharsets.UTF_8));
            return;
        }

        Assertions.assertTrue(Files.exists(file), "missing snapshot (run with -Dgolden.update=true): " + file);
        String expected = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (expected.equals(actual) == false) {
            Assertions.fail("Messages snapshot mismatch: " + name + "\n" + diff(expected, actual));
        }
    }

    static String render(Map<String, String> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> kv : new TreeMap<>(messages).entrySet()) {
            sb.append(kv.getKey()).append(" = \"").append(escape(kv.getValue())).append("\"\n");
        }
        return sb.toString();
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
