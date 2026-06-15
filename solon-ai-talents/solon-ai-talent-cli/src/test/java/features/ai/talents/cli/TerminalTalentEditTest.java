package features.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.mount.MountManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
    public void editUsesOldStrStartLineToDisambiguateRepeatedBlocks() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList("one", "target", "middle", "target", "end"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 4;
            edit.oldStr = "target";
            edit.newStr = "changed";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("one", "target", "middle", "changed", "end"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editDoesNotUseNearbyLineWhenOldStrStartLineIsWrong() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList("one", "target", "middle", "target", "end"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 3;
            edit.oldStr = "target";
            edit.newStr = "changed";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("预检查失败"));
            assertEquals(Arrays.asList("one", "target", "middle", "target", "end"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editDoesNotIgnoreTrailingWhitespaceWithOldStrStartLine() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList("foo();    ", "bar();"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "foo();\nbaz();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("预检查失败"));
            assertEquals(Arrays.asList("foo();    ", "bar();"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editRunsLineScopedSingleReplacesInDescendingLineOrder() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList("top", "same", "middle", "same", "bottom"));

            TerminalTalent.EditOp first = new TerminalTalent.EditOp();
            first.oldStrStartLine = 2;
            first.oldStr = "same";
            first.newStr = "first\ninserted";

            TerminalTalent.EditOp second = new TerminalTalent.EditOp();
            second.oldStrStartLine = 4;
            second.oldStr = "same";
            second.newStr = "second";

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            String result = talent.edit("demo.txt", Arrays.asList(first, second), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("top", "first", "inserted", "middle", "second", "bottom"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editKeepsInputOrderWhenNotAllOperationsAreLineScopedSingleReplaces() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList("a", "b"));

            TerminalTalent.EditOp first = new TerminalTalent.EditOp();
            first.oldStr = "a";
            first.newStr = "b";

            TerminalTalent.EditOp second = new TerminalTalent.EditOp();
            second.oldStr = "b";
            second.newStr = "c";

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            String result = talent.edit("demo.txt", Arrays.asList(first, second), workDir.toString());

            assertTrue(result.contains("执行失败"));
            assertEquals(Arrays.asList("a", "b"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }
}
