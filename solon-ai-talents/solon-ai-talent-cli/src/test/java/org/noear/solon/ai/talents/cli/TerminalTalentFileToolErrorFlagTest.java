package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mount.MountManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * read / ls / grep / glob / bash_start / bash_wait 的成败标记。
 *
 * <p>口径：没完成要做的事（路径不存在、二进制、起始行越界、拒绝执行、被硬超时终止）算失败；
 * 结果为空（空目录、空文件、未找到）算成功。给模型的文本不变（由渲染快照保证）。</p>
 */
public class TerminalTalentFileToolErrorFlagTest {

    private static TerminalTalent newTalent(Path workDir) {
        TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
        talent.setBashAsyncEnabled(true);
        return talent;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Test
    public void lsFlags() throws Throwable {
        Path workDir = Files.createTempDirectory("cli-flag-ls");
        try {
            TerminalTalent t = newTalent(workDir);
            String cwd = workDir.toString();
            Files.createDirectories(workDir.resolve("empty"));

            ToolResult missing = t.ls("no_such_dir", false, false, cwd, null);
            assertTrue(missing.isError(), missing.getContent());

            assertFalse(t.ls("empty", false, false, cwd, null).isError(), "空目录不算失败");
            assertFalse(t.ls(".", true, false, cwd, null).isError());
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void readFlags() throws Throwable {
        Path workDir = Files.createTempDirectory("cli-flag-read");
        try {
            TerminalTalent t = newTalent(workDir);
            String cwd = workDir.toString();
            Files.write(workDir.resolve("a.txt"), "l1\nl2\n".getBytes(StandardCharsets.UTF_8));
            Files.write(workDir.resolve("empty.txt"), new byte[0]);
            Files.write(workDir.resolve("bin.dat"), new byte[]{0x7F, 'E', 'L', 'F', 0, 1, 2, 0, 3});

            ToolResult missing = t.read("no_such.txt", null, null, cwd, null);
            assertTrue(missing.isError(), missing.getContent());

            ToolResult binary = t.read("bin.dat", null, null, cwd, null);
            assertTrue(binary.isError(), binary.getContent());

            ToolResult offsetOut = t.read("a.txt", 100, null, cwd, null);
            assertTrue(offsetOut.isError(), offsetOut.getContent());

            assertFalse(t.read("empty.txt", null, null, cwd, null).isError(), "空文件不算失败");

            ToolResult ok = t.read("a.txt", null, null, cwd, null);
            assertFalse(ok.isError());
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void grepAndGlobFlags() throws Throwable {
        Path workDir = Files.createTempDirectory("cli-flag-search");
        try {
            TerminalTalent t = newTalent(workDir);
            String cwd = workDir.toString();
            Files.createDirectories(workDir.resolve("src"));
            Files.write(workDir.resolve("src/a.txt"), "hello\n".getBytes(StandardCharsets.UTF_8));

            ToolResult grepMissing = t.grep("hello", "no_such_dir", null, cwd, null);
            assertTrue(grepMissing.isError(), grepMissing.getContent());
            assertTrue(t.grep("hello", "src;lib", null, cwd, null).isError(), "多路径拼接算失败");
            assertFalse(t.grep("not_exists_text", "src", null, cwd, null).isError(), "未找到结果不算失败");
            assertFalse(t.grep("hello", "src", null, cwd, null).isError());

            ToolResult globMissing = t.glob("**/*.txt", "no_such_dir", cwd, null);
            assertTrue(globMissing.isError(), globMissing.getContent());
            assertFalse(t.glob("**/*.java", "src", cwd, null).isError(), "未找到匹配文件不算失败");
            assertFalse(t.glob("*.txt", "src", cwd, null).isError());
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void bashStartFlags() throws Throwable {
        Path workDir = Files.createTempDirectory("cli-flag-bash-start");
        try {
            TerminalTalent t = newTalent(workDir);
            String cwd = workDir.toString();

            ToolResult violation = t.bashStart("exit 3", null, 1000, 64000, 120000, cwd, null);
            assertTrue(violation.isError(), "安全校验拒绝算失败");

            ToolResult ok = t.bashStart("echo hi", null, 5000, 64000, 120000, cwd, null);
            assertFalse(ok.isError(), ok.getContent());

            // 与 bash 同口径：进程正常结束即成功，不论退出码
            ToolResult nonZero = t.bashStart("ls __no_such_file__", null, 5000, 64000, 120000, cwd, null);
            assertFalse(nonZero.isError(), nonZero.getContent());
            assertTrue(nonZero.getContent().contains("exit_code: "), nonZero.getContent());
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void bashWaitHardTimeoutIsError() throws Throwable {
        if (isWindows()) {
            return;
        }
        Path workDir = Files.createTempDirectory("cli-flag-bash-wait");
        try {
            TerminalTalent t = newTalent(workDir);

            ToolResult started = t.bashStart("sleep 5", null, 0, 64000, 300, workDir.toString(), null);
            assertFalse(started.isError(), "仍在运行不算失败：" + started.getContent());
            String sessionId = sessionIdOf(started.getContent());

            ToolResult waited = t.bashWait(sessionId, null, 3000, 64000);
            assertTrue(waited.getContent().contains("hard_timeout: true"), waited.getContent());
            assertTrue(waited.isError(), "被硬超时终止算失败");
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * 经 {@link FunctionTool#call(Map)}（工具注册路径）调用：注册的必须是返回 ToolResult 的新签名，标记才能传出。
     */
    @Test
    public void errorFlagReachesRegisteredTools() throws Throwable {
        Path workDir = Files.createTempDirectory("cli-flag-tool");
        try {
            Map<String, FunctionTool> tools = newTalent(workDir).getToolMap();

            assertTrue(call(tools, "read", workDir, "file_path", "no_such.txt").isError());
            assertTrue(call(tools, "ls", workDir, "path", "no_such_dir").isError());
            assertTrue(call(tools, "grep", workDir, "pattern", "x", "path", "no_such_dir").isError());
            assertTrue(call(tools, "glob", workDir, "pattern", "*.txt", "path", "no_such_dir").isError());
            assertTrue(call(tools, "bash_start", workDir, "command", "exit 3").isError());
            assertFalse(call(tools, "glob", workDir, "pattern", "*.none", "path", ".").isError());
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static ToolResult call(Map<String, FunctionTool> tools, String name, Path workDir, String... kv) throws Throwable {
        FunctionTool tool = tools.get(name);
        assertNotNull(tool, name + " 工具应已注册");
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            args.put(kv[i], kv[i + 1]);
        }
        args.put("__cwd", workDir.toString());
        return tool.call(args);
    }

    private static String sessionIdOf(String snapshot) {
        for (String line : snapshot.split("\n")) {
            if (line.startsWith("session_id: ")) {
                return line.substring("session_id: ".length()).trim();
            }
        }
        fail("快照中没有 session_id：" + snapshot);
        return null;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        Files.walk(root).forEach(paths::add);
        Collections.sort(paths, Collections.reverseOrder());
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
