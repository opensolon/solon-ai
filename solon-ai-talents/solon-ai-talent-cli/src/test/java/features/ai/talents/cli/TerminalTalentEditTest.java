package features.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.mount.MountManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * edit 方法测试：验证 Unified Diff 清洗与 fuzzy 兜底能力。
 */
public class TerminalTalentEditTest {
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
    public void editShouldApplyDiffWhenHunkLineNumberIsShifted() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("app.txt");
            Files.write(file, (String.join("\n",
                    "header-1",
                    "header-2",
                    "alpha",
                    "beta",
                    "gamma",
                    "footer") + "\n").getBytes(StandardCharsets.UTF_8));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            String diff = "--- a/app.txt\n" +
                    "+++ b/app.txt\n" +
                    "@@ -1,3 +1,3 @@\n" +
                    " alpha\n" +
                    "-beta\n" +
                    "+BETA\n" +
                    " gamma\n";

            String result = talent.edit("app.txt", diff, workDir.toString());

            assertTrue(result.contains("成功应用补丁"), result);
            assertEquals(String.join("\n",
                    "header-1",
                    "header-2",
                    "alpha",
                    "BETA",
                    "gamma",
                    "footer") + "\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editShouldIgnoreOnlyStandardNoNewlineMarker() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("script.txt");
            Files.write(file, (String.join("\n",
                    "start",
                    "\\literal-line",
                    "end") + "\n").getBytes(StandardCharsets.UTF_8));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            String diff = "```diff\n" +
                    "--- a/script.txt\n" +
                    "+++ b/script.txt\n" +
                    "@@ -1,3 +1,3 @@\n" +
                    " start\n" +
                    " \\literal-line\n" +
                    "-end\n" +
                    "+done\n" +
                    "\\ No newline at end of file\n" +
                    "```";

            String result = talent.edit("script.txt", diff, workDir.toString());

            assertTrue(result.contains("成功应用补丁"), result);
            assertEquals(String.join("\n",
                    "start",
                    "\\literal-line",
                    "done") + "\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editShouldReturnActionableMessageWhenDiffCannotMatch() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("app.txt");
            Files.write(file, (String.join("\n",
                    "alpha",
                    "beta",
                    "gamma") + "\n").getBytes(StandardCharsets.UTF_8));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            String diff = "--- a/app.txt\n" +
                    "+++ b/app.txt\n" +
                    "@@ -1,3 +1,3 @@\n" +
                    " alpha\n" +
                    "-missing-line\n" +
                    "+BETA\n" +
                    " gamma\n";

            String result = talent.edit("app.txt", diff, workDir.toString());

            assertTrue(result.contains("编辑失败：Unified Diff 补丁未能应用，文件未被修改。"), result);
            assertTrue(result.contains("目标文件：app.txt"), result);
            assertTrue(result.contains("失败阶段：补丁应用"), result);
            assertTrue(result.contains("失败原因：补丁中的某个 @@ 块无法匹配目标文件"), result);
            assertTrue(result.contains("修复建议："), result);
            assertTrue(result.contains("重新读取目标文件的最新内容"), result);
            assertEquals(String.join("\n",
                    "alpha",
                    "beta",
                    "gamma") + "\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editShouldReturnActionableMessageWhenDiffIsEmpty() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("app.txt");
            Files.write(file, "alpha\n".getBytes(StandardCharsets.UTF_8));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            String result = talent.edit("app.txt", "   ", workDir.toString());

            assertTrue(result.contains("失败阶段：参数校验"), result);
            assertTrue(result.contains("失败原因：diff 内容不能为空"), result);
            assertTrue(result.contains("文件未被修改"), result);
        } finally {
            deleteRecursively(workDir);
        }
    }
}
