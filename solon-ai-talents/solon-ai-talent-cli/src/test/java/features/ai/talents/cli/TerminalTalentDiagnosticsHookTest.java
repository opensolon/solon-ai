package features.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.mount.MountManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 写入后自动注入诊断 / 读取后预热 的钩子契约测试。
 *
 * <p>核心约束：诊断是「附加信息」，无论钩子返回什么或抛什么，都不能改变写入本身的成败。
 */
public class TerminalTalentDiagnosticsHookTest {

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

    @Test
    public void writeAppendsDiagnostics() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-lsp-hook-");
        try {
            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));

            AtomicReference<Path> seen = new AtomicReference<>();
            talent.setFileDiagnosticsHook(p -> {
                seen.set(p);
                return "LSP errors detected in this file, please fix:\n"
                        + "<diagnostics file=\"demo.java\">\nERROR [1:1] boom\n</diagnostics>";
            });

            String result = talent.write("demo.java", "class Demo {", null);

            //写入结论仍在最前（诊断只是追加）
            assertTrue(result.startsWith("文件成功写入: demo.java"), result);
            assertTrue(result.contains("ERROR [1:1] boom"), result);
            assertTrue(result.contains("<diagnostics file=\"demo.java\">"), result);
            //钩子拿到的是绝对路径
            assertNotNull(seen.get());
            assertTrue(seen.get().isAbsolute());
            assertEquals("demo.java", seen.get().getFileName().toString());
            //文件确实落盘
            assertEquals("class Demo {", new String(Files.readAllBytes(workDir.resolve("demo.java")), "UTF-8"));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editAppendsDiagnostics() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-lsp-hook-");
        try {
            Path file = workDir.resolve("demo.java");
            Files.write(file, Arrays.asList("class Demo {", "}"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            talent.setFileDiagnosticsHook(p -> "LSP errors detected in this file, please fix:\n"
                    + "<diagnostics file=\"demo.java\">\nERROR [1:7] bad name\n</diagnostics>");

            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "class Demo {";
            edit.newStr = "class demo {";

            String result = talent.edit("demo.java", Arrays.asList(edit), null);

            assertTrue(result.startsWith("文件 demo.java 成功完成 1 处修改。"), result);
            assertTrue(result.contains("ERROR [1:7] bad name"), result);
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void nullOrEmptyDiagnosticsKeepsOutputUnchanged() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-lsp-hook-");
        try {
            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));

            //无诊断（最常见：文件类型没有匹配的语言服务器）→ 输出与改造前完全一致
            talent.setFileDiagnosticsHook(p -> null);
            assertEquals("文件成功写入: a.txt", talent.write("a.txt", "x", null));

            talent.setFileDiagnosticsHook(p -> "");
            assertEquals("文件成功写入: b.txt", talent.write("b.txt", "x", null));

            //未接钩子时同样保持原样
            TerminalTalent bare = new TerminalTalent(new MountManager(workDir.toString()));
            assertEquals("文件成功写入: c.txt", bare.write("c.txt", "x", null));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void hookFailureMustNotBreakWrite() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-lsp-hook-");
        try {
            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            talent.setFileDiagnosticsHook(p -> {
                throw new IllegalStateException("lsp server exploded");
            });

            String result = talent.write("demo.java", "class Demo {}", null);

            assertEquals("文件成功写入: demo.java", result);
            assertTrue(Files.exists(workDir.resolve("demo.java")));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void readTriggersWarmupAndSurvivesHookFailure() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-lsp-hook-");
        try {
            Path file = workDir.resolve("demo.java");
            Files.write(file, Arrays.asList("class Demo {}"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));

            AtomicReference<Path> warmed = new AtomicReference<>();
            talent.setFileWarmupHook(warmed::set);

            String content = talent.read("demo.java", null, null, null);

            assertTrue(content.contains("class Demo {}"), content);
            assertNotNull(warmed.get());
            assertEquals("demo.java", warmed.get().getFileName().toString());

            //预热钩子异常也不能影响读取
            talent.setFileWarmupHook(p -> {
                throw new IllegalStateException("warmup exploded");
            });
            assertTrue(talent.read("demo.java", null, null, null).contains("class Demo {}"));
        } finally {
            deleteRecursively(workDir);
        }
    }
}
