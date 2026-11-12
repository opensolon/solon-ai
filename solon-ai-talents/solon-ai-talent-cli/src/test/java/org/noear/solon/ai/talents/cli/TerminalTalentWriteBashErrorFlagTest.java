package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mount.MountManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * write / bash 的成败标记：与 edit 同一语义，失败必须由 {@link ToolResult#isError()} 表达，
 * 宿主侧才能不解析提示文案就判断成败（前端据此显示红叉/绿勾）。
 */
public class TerminalTalentWriteBashErrorFlagTest {

    private static TerminalTalent newTalent(Path workDir) {
        return new TerminalTalent(new MountManager(workDir.toString()));
    }

    @Test
    public void writeSuccessIsNotError() throws Throwable {
        Path workDir = Files.createTempDirectory("cli-write-flag-ok");
        try {
            ToolResult result = newTalent(workDir).write("demo.txt", "hello", workDir.toString(), null);

            assertFalse(result.isError());
            assertEquals("hello", new String(Files.readAllBytes(workDir.resolve("demo.txt")), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void bashZeroExitIsNotError() throws Throwable {
        Path workDir = Files.createTempDirectory("cli-bash-flag-ok");
        try {
            ToolResult result = newTalent(workDir).bash("echo hi", 10_000, 64_000, workDir.toString(), null);

            assertFalse(result.isError(), "退出码 0 不应标记为失败");
            assertTrue(result.getContent().contains("hi"));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void bashNonZeroExitIsError() throws Throwable {
        Path workDir = Files.createTempDirectory("cli-bash-flag-fail");
        try {
            ToolResult result = newTalent(workDir).bash("exit 3", 10_000, 64_000, workDir.toString(), null);

            assertTrue(result.isError(), "非零退出码必须标记为失败");
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void bashViolationIsError() throws Throwable {
        Path workDir = Files.createTempDirectory("cli-bash-flag-violation");
        try {
            // 安全校验拒绝（kill 类命令）：不应以普通文本返回
            ToolResult result = newTalent(workDir).bash("kill -9 1", 10_000, 64_000, workDir.toString(), null);

            assertTrue(result.isError());
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * 端到端：经 {@link FunctionTool#call(Map)}（工具注册路径）调用，错误标记要能传到 ToolResult。
     */
    @Test
    public void bashErrorFlagReachesRegisteredTool() throws Throwable {
        Path workDir = Files.createTempDirectory("cli-bash-flag-tool");
        try {
            FunctionTool tool = newTalent(workDir).getToolMap().get("bash");
            assertNotNull(tool, "bash 工具应已注册");

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("command", "exit 7");
            args.put("__cwd", workDir.toString());

            ToolResult result = tool.call(args);
            assertTrue(result.isError(), "失败标记必须经工具注册路径传出");
        } finally {
            deleteRecursively(workDir);
        }
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
