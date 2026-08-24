package org.noear.solon.ai.talents.code;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LanguageProvider} 的静态工具方法与默认实现。
 *
 * <p>这些是所有 Provider 的公共地基：listNames 决定了匹配判定的输入，
 * find 决定了全部版本探测的正确性，故需单独固化。
 */
public class LanguageProviderTest {

    /**
     * 只实现必选方法的最小 Provider，用于覆盖接口的各项默认实现
     */
    static class MinimalProvider implements LanguageProvider {
        @Override
        public String id() {
            return "Minimal";
        }

        @Override
        public String typeName() {
            return "最小模块";
        }

        @Override
        public String[] markers() {
            return new String[]{"minimal.toml"};
        }

        @Override
        public void appendRootCommands(StringBuilder buf) {
            buf.append("root\n");
        }

        @Override
        public void appendModuleCommands(StringBuilder buf, String moduleName) {
            buf.append("module:").append(moduleName).append("\n");
        }
    }

    // ---------- listNames ----------

    @Test
    public void listNames_returnsDirectEntriesOnly(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("a.txt"));
        Files.createDirectories(dir.resolve("sub/deep"));

        Set<String> names = LanguageProvider.listNames(dir);

        assertEquals(2, names.size());
        assertTrue(names.contains("a.txt"));
        assertTrue(names.contains("sub"));
        assertFalse(names.contains("deep"), "不应递归到子目录");
    }

    @Test
    public void listNames_missingDir_returnsEmpty(@TempDir Path dir) {
        Set<String> names = LanguageProvider.listNames(dir.resolve("not-exists"));

        assertTrue(names.isEmpty());
    }

    @Test
    public void listNames_regularFile_returnsEmpty(@TempDir Path dir) throws Exception {
        Path file = Files.createFile(dir.resolve("f.txt"));

        // NotDirectoryException 属于 IOException，应被吞掉返回空集合
        assertTrue(LanguageProvider.listNames(file).isEmpty());
    }

    // ---------- readText ----------

    @Test
    public void readText_present_and_missing(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("x.txt"), "内容".getBytes(StandardCharsets.UTF_8));

        assertEquals("内容", LanguageProvider.readText(dir, "x.txt"));
        assertNull(LanguageProvider.readText(dir, "y.txt"));
    }

    @Test
    public void readText_directory_returnsNull(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("sub"));

        assertNull(LanguageProvider.readText(dir, "sub"), "目录不是普通文件，应返回 null");
    }

    // ---------- find ----------

    @Test
    public void find_nullContent_returnsNull() {
        assertNull(LanguageProvider.find(null, "(\\d+)"));
    }

    @Test
    public void find_noMatch_returnsNull() {
        assertNull(LanguageProvider.find("abc", "(\\d+)"));
    }

    @Test
    public void find_returnsFirstGroupTrimmed() {
        assertEquals("17", LanguageProvider.find("<v>  17  </v>", "<v>(.+?)</v>"));
    }

    @Test
    public void find_isCaseSensitive() {
        // XML 标签、TOML/JSON 键都是大小写敏感的，不能让 <Source> 误配 <source>
        assertEquals("8", LanguageProvider.find("<source>8</source>", "<source>(\\d+)<"));
        assertNull(LanguageProvider.find("<Source>8</Source>", "<source>(\\d+)<"));
    }

    @Test
    public void find_dotallCrossesLines() {
        assertEquals("b", LanguageProvider.find("a\nb\nc", "a.(b)"));
    }

    @Test
    public void pattern_isCached() {
        Pattern p1 = LanguageProvider.pattern("(cache-probe-\\d+)");
        Pattern p2 = LanguageProvider.pattern("(cache-probe-\\d+)");

        assertSame(p1, p2, "同一正则应复用已编译对象");
    }

    // ---------- 默认实现 ----------

    @Test
    public void default_isMatch_byMarkers(@TempDir Path dir) throws Exception {
        MinimalProvider p = new MinimalProvider();

        assertFalse(p.isMatch(dir));
        assertFalse(p.isMatch(dir, Collections.singleton("other.toml")));

        Files.createFile(dir.resolve("minimal.toml"));

        assertTrue(p.isMatch(dir), "便捷入口应自行列举目录条目");
        assertTrue(p.isMatch(dir, LanguageProvider.listNames(dir)));
    }

    @Test
    public void default_ignoreFolders_isEmpty() {
        MinimalProvider p = new MinimalProvider();

        assertEquals(0, p.ignoreFolders().length);
        assertFalse(p.isIgnored("target"));
    }

    @Test
    public void default_detectVersion_isNull(@TempDir Path dir) {
        assertNull(new MinimalProvider().detectVersion(dir));
    }

    @Test
    public void default_isAggregator_isFalse(@TempDir Path dir) {
        assertFalse(new MinimalProvider().isAggregator(dir, Collections.emptySet()));
    }
}
