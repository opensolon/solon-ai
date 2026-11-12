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
 * edit 工具的成败标记：失败必须由 {@link ToolResult#isError()} 表达，
 * 宿主侧（ToolCallEndEvent）才能不解析文案就判断成败。
 */
public class TerminalTalentEditErrorFlagTest {

    private static TerminalTalent.EditOp op(String oldStr, String newStr) {
        TerminalTalent.EditOp op = new TerminalTalent.EditOp();
        op.oldStr = oldStr;
        op.newStr = newStr;
        return op;
    }

    private static TerminalTalent newTalent(Path workDir) {
        return new TerminalTalent(new MountManager(workDir.toString()));
    }

    @Test
    public void editSuccessIsNotError() throws Exception {
        Path workDir = Files.createTempDirectory("cli-edit-flag-ok");
        try {
        Path file = workDir.resolve("demo.txt");
        Files.write(file, "hello world".getBytes());

        ToolResult result = newTalent(workDir).edit("demo.txt", Collections.singletonList(op("hello", "hi")), workDir.toString(), null);

        assertFalse(result.isError());
        assertTrue(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8).contains("hi world"));
        } finally { deleteRecursively(workDir); }
    }

    @Test
    public void editMissingFileIsError() throws Exception {
        Path workDir = Files.createTempDirectory("cli-edit-flag-missing");
        try {
        ToolResult result = newTalent(workDir).edit("none.txt", Collections.singletonList(op("a", "b")), workDir.toString(), null);

        assertTrue(result.isError());
        assertNotNull(result.getContent());
        } finally { deleteRecursively(workDir); }
    }

    @Test
    public void editMismatchIsError() throws Exception {
        Path workDir = Files.createTempDirectory("cli-edit-flag-mismatch");
        try {
        Files.write(workDir.resolve("demo.txt"), "hello world".getBytes());

        ToolResult result = newTalent(workDir).edit("demo.txt", Collections.singletonList(op("not-there", "x")), workDir.toString(), null);

        assertTrue(result.isError());
        } finally { deleteRecursively(workDir); }
    }

    @Test
    public void editNotUniqueIsError() throws Exception {
        Path workDir = Files.createTempDirectory("cli-edit-flag-dup");
        try {
        Files.write(workDir.resolve("demo.txt"), "a\na\n".getBytes());

        ToolResult result = newTalent(workDir).edit("demo.txt", Collections.singletonList(op("a", "b")), workDir.toString(), null);

        assertTrue(result.isError());
        } finally { deleteRecursively(workDir); }
    }

    /**
     * 端到端：经 {@link FunctionTool#call(Map)} 调用（工具注册路径），错误标记要能传到 ToolResult
     */
    @Test
    public void toolCallCarriesErrorFlag() throws Throwable {
        Path workDir = Files.createTempDirectory("cli-edit-flag-tool");
        try {
        FunctionTool tool = newTalent(workDir).getToolMap().get(TerminalTalent.TOOL_EDIT);
        assertNotNull(tool, "edit 工具应已注册");

        // 失败：文件不存在
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("file_path", "none.txt");
        List<Map<String, Object>> edits = new ArrayList<>();
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("old_str", "a");
        edit.put("new_str", "b");
        edits.add(edit);
        args.put("edits", edits);
        args.put("__cwd", workDir.toString());

        ToolResult failed = tool.call(args);
        assertTrue(failed.isError(), "失败必须是结构化错误");

        // 成功
        Files.write(workDir.resolve("demo.txt"), "hello world".getBytes());
        args.put("file_path", "demo.txt");
        edit.put("old_str", "hello");
        edit.put("new_str", "hi");

        ToolResult ok = tool.call(args);
        assertFalse(ok.isError());
        assertTrue(new String(Files.readAllBytes(workDir.resolve("demo.txt")), java.nio.charset.StandardCharsets.UTF_8).contains("hi world"));
        } finally { deleteRecursively(workDir); }
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
