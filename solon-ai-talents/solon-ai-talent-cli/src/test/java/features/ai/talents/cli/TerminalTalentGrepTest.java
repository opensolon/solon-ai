package features.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.mount.MountManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * grep 方法测试：验证关键字匹配、正则匹配、无效正则回退 contains、无匹配等场景
 */
public class TerminalTalentGrepTest {

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        ArrayList<Path> paths = new ArrayList<>();
        Files.walk(root).forEach(paths::add);
        Collections.sort(paths, Collections.reverseOrder());
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    // ==================== 基本关键字匹配 ====================

    @Test
    public void grepBasicSubstringMatch() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-grep-");
        try {
            Files.createDirectories(workDir.resolve("src"));
            Files.write(workDir.resolve("src/App.java"),
                    Arrays.asList("public class App {", "    private String name = \"hello world\";", "}"));
            Files.write(workDir.resolve("README.md"),
                    Arrays.asList("# My Project", "hello everyone", "goodbye"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            String result = talent.grep("hello", ".", null, workDir.toString());

            assertTrue(result.contains("hello"), "应该匹配到 'hello' 关键字");
            // 两个文件都应出现
            assertTrue(result.contains("src/App.java"), "应该包含 src/App.java");
            assertTrue(result.contains("README.md"), "应该包含 README.md");
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void grepMatchMultipleLines() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-grep-");
        try {
            Files.write(workDir.resolve("log.txt"),
                    Arrays.asList("INFO: server started", "DEBUG: loading config", "INFO: listening on port 8080"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            String result = talent.grep("INFO", ".", null, workDir.toString());

            assertTrue(result.contains("server started"), "应该匹配第一条 INFO");
            assertTrue(result.contains("listening on port 8080"), "应该匹配第二条 INFO");
            assertFalse(result.contains("DEBUG"), "不应该包含 DEBUG 行");
        } finally {
            deleteRecursively(workDir);
        }
    }

    // ==================== 正则匹配 ====================

    @Test
    public void grepRegexDigits() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-grep-");
        try {
            Files.write(workDir.resolve("data.txt"),
                    Arrays.asList("id=12345, name=alice", "id=67890, name=bob", "id=999, name=charlie"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            // \d{5} 匹配恰好 5 位数字
            String result = talent.grep("\\d{5}", ".", null, workDir.toString());

            assertTrue(result.contains("12345"), "应该匹配 12345");
            assertTrue(result.contains("67890"), "应该匹配 67890");
            assertFalse(result.contains("999"), "999 不足5位，不应该匹配");
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void grepRegexAlternation() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-grep-");
        try {
            Files.write(workDir.resolve("log.txt"),
                    Arrays.asList("INFO: server started", "WARN: low memory", "ERROR: connection failed"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            // | 表示"或"
            String result = talent.grep("WARN|ERROR", ".", null, workDir.toString());

            assertTrue(result.contains("WARN"), "应该匹配 WARN");
            assertTrue(result.contains("ERROR"), "应该匹配 ERROR");
            assertFalse(result.contains("INFO"), "不应该匹配 INFO");
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void grepRegexCaseInsensitive() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-grep-");
        try {
            Files.write(workDir.resolve("log.txt"),
                    Arrays.asList("Error: something failed", "info: all good", "ERROR: critical"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            // (?i) 忽略大小写
            String result = talent.grep("(?i)error", ".", null, workDir.toString());

            assertTrue(result.contains("Error"), "应该匹配 Error");
            assertTrue(result.contains("ERROR"), "应该匹配 ERROR");
            assertFalse(result.contains("info"), "不应该匹配 info");
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void grepRegexWordBoundary() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-grep-");
        try {
            Files.write(workDir.resolve("code.java"),
                    Arrays.asList("int age = 10;", "int agentCount = 20;", "String message = \"age is 10\";"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            // \b 词边界：只匹配独立单词 age
            String result = talent.grep("\\bage\\b", ".", null, workDir.toString());

            assertTrue(result.contains("int age = 10"), "应该匹配独立单词 age");
            assertTrue(result.contains("age is 10"), "应该匹配字符串中的独立单词 age");
            assertFalse(result.contains("agentCount"), "agentCount 不应该匹配 \\bage\\b");
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void grepRegexStartOfLine() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-grep-");
        try {
            Files.write(workDir.resolve("config.properties"),
                    Arrays.asList("server.port=8080", "# server.host=localhost", "server.host=0.0.0.0"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            // ^ 行首：匹配非注释行
            String result = talent.grep("^server\\.", ".", null, workDir.toString());

            assertTrue(result.contains("server.port"), "应该匹配 server.port");
            assertTrue(result.contains("server.host=0.0.0.0"), "应该匹配第二个 server.host");
            assertFalse(result.contains("# server.host"), "注释行不应该匹配 ^server\\.");
        } finally {
            deleteRecursively(workDir);
        }
    }

    // ==================== 无效正则回退 contains ====================

    @Test
    public void grepInvalidRegexFallbackToContains() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-grep-");
        try {
            Files.write(workDir.resolve("test.txt"),
                    Arrays.asList("line with [unclosed bracket", "another line"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            // "[unclosed" 括号未闭合，是非法正则，应回退到 contains 子串匹配
            String result = talent.grep("[unclosed", ".", null, workDir.toString());

            assertTrue(result.contains("[unclosed"), "非法正则应回退到 contains 匹配");
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void grepInvalidRegexUnmatchedParenthesis() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-grep-");
        try {
            Files.write(workDir.resolve("test.txt"),
                    Arrays.asList("foo(bar) baz", "other"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            // "(bar" 括号未闭合，是非法正则，应回退到 contains
            String result = talent.grep("(bar", ".", null, workDir.toString());

            assertTrue(result.contains("foo(bar)"), "非法正则 ' (bar' 应回退到 contains 匹配");
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void grepInvalidRegexTrailingBackslash() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-grep-");
        try {
            Files.write(workDir.resolve("path.txt"),
                    Arrays.asList("C:\\Users\\test\\file.txt", "D:\\data"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            // "Users\" 末尾反斜杠，非法正则，应回退到 contains
            String result = talent.grep("Users\\", ".", null, workDir.toString());

            assertTrue(result.contains("Users\\test"), "非法正则 ' Users\\' 应回退到 contains");
        } finally {
            deleteRecursively(workDir);
        }
    }

    // ==================== 无匹配 ====================

    @Test
    public void grepNoMatchReturnsEmptyMessage() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-grep-");
        try {
            Files.write(workDir.resolve("test.txt"),
                    Arrays.asList("nothing here"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            String result = talent.grep("nonexistent", ".", null, workDir.toString());

            assertEquals("未找到结果。", result, "无匹配时应返回固定提示");
        } finally {
            deleteRecursively(workDir);
        }
    }

    // ==================== 子目录搜索 ====================

    @Test
    public void grepSubdirectorySearch() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-grep-");
        try {
            Files.createDirectories(workDir.resolve("src/main"));
            Files.createDirectories(workDir.resolve("src/test"));
            Files.write(workDir.resolve("src/main/App.java"),
                    Arrays.asList("// TODO: implement", "class App {}"));
            Files.write(workDir.resolve("src/test/AppTest.java"),
                    Arrays.asList("// TODO: add tests", "class AppTest {}"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            // 只在 src/main 下搜索
            String result = talent.grep("TODO", "src/main", null, workDir.toString());

            assertTrue(result.contains("implement"), "应该匹配 src/main 下的 TODO");
            assertFalse(result.contains("add tests"), "不应该搜索到 src/test 下的 TODO");
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void grepSearchFromSubdirectoryCwd() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-grep-");
        try {
            Files.createDirectories(workDir.resolve("module-a/src"));
            Files.createDirectories(workDir.resolve("module-b/src"));
            Files.write(workDir.resolve("module-a/src/App.java"),
                    Arrays.asList("class App {", "    // FIXME: bug here", "}"));
            Files.write(workDir.resolve("module-b/src/Util.java"),
                    Arrays.asList("class Util {", "    // FIXME: optimize", "}"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            // cwd 设为 module-a，path 用 "." 表示当前目录
            String result = talent.grep("FIXME", ".", null, workDir.resolve("module-a").toString());

            assertTrue(result.contains("bug here"), "应该匹配 module-a 下的 FIXME");
            assertFalse(result.contains("optimize"), "不应该匹配 module-b 下的 FIXME");
        } finally {
            deleteRecursively(workDir);
        }
    }
}