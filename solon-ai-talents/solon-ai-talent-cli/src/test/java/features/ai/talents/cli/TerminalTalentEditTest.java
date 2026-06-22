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

    // ============================================================
    // 基础精确匹配（oldStrStartLine 定位）
    // ============================================================

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
    public void editAcceptsTrailingWhitespaceWithOldStrStartLine() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // 文件有尾部空格，oldStr 没有（尾部空格差异应被忽略）
            Files.write(file, Arrays.asList("foo();    ", "bar();"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "foo();\nbaz();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("foo();", "baz();"), Files.readAllLines(file));
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

    // ============================================================
    // 宽松匹配成功场景（忽略行首缩进差异）
    // ============================================================

    @Test
    public void editLooseMatchIgnoresIndentationDifference() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // 文件每行有不同缩进，oldStr 没有缩进
            Files.write(file, Arrays.asList("  foo();", "    bar();", "end"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "newFoo();\nnewBar();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("  newFoo();", "    newBar();", "end"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editLooseMatchWithTabIndentation() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // 文件使用 Tab 缩进，oldStr 没有缩进
            Files.write(file, Arrays.asList("\t\tfoo();", "\tbar();", "end"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "newFoo();\nnewBar();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("\t\tnewFoo();", "\tnewBar();", "end"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editLooseMatchWithMixedTabAndSpaceIndentation() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // 文件混合缩进，oldStr 没有缩进
            Files.write(file, Arrays.asList("\t  foo();", "  \tbar();", "end"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "newFoo();\nnewBar();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("\t  newFoo();", "  \tnewBar();", "end"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editLooseMatchWithPartialIndentationInOldStr() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // 文件 4 空格缩进，oldStr 有 2 空格缩进（部分匹配）
            Files.write(file, Arrays.asList("    foo();", "    bar();", "end"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "  foo();\n  bar();";
            edit.newStr = "  newFoo();\n  newBar();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            // newStr 自带 2 空格缩进，应以用户为准
            assertEquals(Arrays.asList("  newFoo();", "  newBar();", "end"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editLooseMatchStartingFromMiddleOfFile() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // oldStrStartLine 指向文件中间，且该处有缩进
            Files.write(file, Arrays.asList("header", "  foo();", "    bar();", "footer"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 2;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "newFoo();\nnewBar();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("header", "  newFoo();", "    newBar();", "footer"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editLooseMatchSingleLine() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // 单行场景：文件有缩进，oldStr 没有
            Files.write(file, Arrays.asList("  foo();", "end"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();";
            edit.newStr = "newFoo();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("  newFoo();", "end"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editLooseMatchWithFewerLinesInOldStrThanFile() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // oldStr 只匹配文件的前 2 行，第 3 行在文件但不在 oldStr 中
            Files.write(file, Arrays.asList("  foo();", "    bar();", "end"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "newFoo();\nnewBar();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("  newFoo();", "    newBar();", "end"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editLooseMatchPreservesContentBeforeMatch() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList("prefix", "  foo();", "    bar();", "suffix"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 2;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "replaced();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("prefix", "  replaced();", "suffix"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editLooseMatchNoTrailingNewlineInFile() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // 文件末尾没有换行符
            Files.write(file, "  foo();\n  bar();".getBytes());

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "newFoo();\nnewBar();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals("  newFoo();\n  newBar();", new String(Files.readAllBytes(file)));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editLooseMatchWithCrlfLineEndings() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // 文件使用 \r\n 换行，文件有缩进，oldStr 没有
            Files.write(file, "  foo();\r\n    bar();\r\nend".getBytes());

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "newFoo();\nnewBar();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            // \r\n 应被保留
            assertEquals("  newFoo();\r\n    newBar();\r\nend", new String(Files.readAllBytes(file)));
        } finally {
            deleteRecursively(workDir);
        }
    }

    // ============================================================
    // 宽松匹配失败场景
    // ============================================================

    @Test
    public void editLooseMatchFailsWhenContentDiffers() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList("  foo();", "    baz();", "end"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "newFoo();\nnewBar();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("预检查失败"));
            assertEquals(Arrays.asList("  foo();", "    baz();", "end"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editLooseMatchFailsWhenOldStrHasMoreLinesThanAvailable() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList("  foo();", "end"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();\nbaz();";
            edit.newStr = "replaced();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("预检查失败"));
            assertEquals(Arrays.asList("  foo();", "end"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editLooseMatchAcceptsTrailingWhitespace() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // 文件有尾部空格，oldStr 没有（尾部空格差异应被忽略）
            Files.write(file, Arrays.asList("  foo();    ", "  bar();"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "newFoo();\nnewBar();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("  newFoo();", "  newBar();"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editLooseMatchAcceptsTrailingWhitespaceOnSecondLine() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // 第 2 行有尾部空格（尾部空格差异应被忽略）
            Files.write(file, Arrays.asList("  foo();", "  bar();    "));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "newFoo();\nnewBar();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("  newFoo();", "  newBar();"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editLooseMatchFailsWhenWhitespaceDiffersInMiddleOfLine() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // 行中间空格差异（只有行首空白被忽略）
            Files.write(file, Arrays.asList("  foo ();", "  bar();"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "newFoo();\nnewBar();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("预检查失败"));
            assertEquals(Arrays.asList("  foo ();", "  bar();"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    // ============================================================
    // 精确匹配优先于宽松匹配
    // ============================================================

    @Test
    public void editExactMatchTakesPriorityOverLooseMatch() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // 两处 "target"，第 2 处精确匹配，第 1 处有缩进
            Files.write(file, Arrays.asList("  target", "target"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 2;
            edit.oldStr = "target";
            edit.newStr = "changed";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            // 第 2 行被精确匹配替换，第 1 行保持不动
            assertEquals(Arrays.asList("  target", "changed"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editLooseMatchUsedWhenExactMatchFailsOnStartLine() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // 第 1 行有缩进，精确匹配不通过，应走宽松匹配
            Files.write(file, Arrays.asList("  foo();", "bar();"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "newFoo();\nnewBar();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("  newFoo();", "newBar();"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    // ============================================================
    // 多次编辑混合场景
    // ============================================================

    @Test
    public void editMultipleLooseMatchesInDescendingLineOrder() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList("header", "  a", "  b", "middle", "    a", "    b", "footer"));

            TerminalTalent.EditOp first = new TerminalTalent.EditOp();
            first.oldStrStartLine = 5;
            first.oldStr = "a\nb";
            first.newStr = "x\ny";

            TerminalTalent.EditOp second = new TerminalTalent.EditOp();
            second.oldStrStartLine = 2;
            second.oldStr = "a\nb";
            second.newStr = "p\nq";

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            String result = talent.edit("demo.txt", Arrays.asList(first, second), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("header", "  p", "  q", "middle", "    x", "    y", "footer"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editMixedExactAndLooseMatch() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList("a", "  b"));

            TerminalTalent.EditOp exact = new TerminalTalent.EditOp();
            exact.oldStrStartLine = 1;
            exact.oldStr = "a";
            exact.newStr = "x";

            TerminalTalent.EditOp loose = new TerminalTalent.EditOp();
            loose.oldStrStartLine = 2;
            loose.oldStr = "b";
            loose.newStr = "y";

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            String result = talent.edit("demo.txt", Arrays.asList(exact, loose), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("x", "  y"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    // ============================================================
    // 折叠空白匹配（collapseWhitespace 兜底策略）
    // ============================================================

    @Test
    public void editCollapseWhitespaceMatchDoubleSpaces() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            // 文件有行内多余空格（双空格），oldStr 使用单空格
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList("  int  x  =  1;", "  bar();"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "int x = 1;\nbar();";
            edit.newStr = "int y = 2;\nbaz();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("  int y = 2;", "  baz();"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editCollapseWhitespaceMatchMixedTabAndSpaces() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            // 文件使用 Tab + 空格混合内间距（int\tx  =  1;），oldStr 使用单空格
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList("  int\tx  =  1;", "  bar();"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "int x = 1;\nbar();";
            edit.newStr = "int y = 2;\nbaz();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("  int y = 2;", "  baz();"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editCollapseWhitespaceStillFailsWhenContentDiffers() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            // 即使折叠空白后，内容仍然不同
            Files.write(file, Arrays.asList("  int  x  =  1;", "  baz();"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "int x = 2;\nbar();";
            edit.newStr = "int y = 2;\nbaz();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("预检查失败"));
            assertEquals(Arrays.asList("  int  x  =  1;", "  baz();"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    // ============================================================
    // 诊断信息行号正确性测试
    // ============================================================

    /**
     * 验证当 oldStrStartLine > 1 且匹配失败时，
     * 诊断信息能正确报告文件中的实际行号，而非将行号误当作字符偏移量使用。
     *
     * 旧版 bug：findLooseMatchDiagnostics 收到 lineStartIndex=2 后，
     * 直接将其作为字符偏移量传递给 extractTrimmedLines，导致从错误位置读取内容。
     */
    @Test
    public void editLooseMatchDiagnosticShowsCorrectLineNumber() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            // 一个多行文件，第 3 行是目标内容，第 4 行不同
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList(
                    "line one",
                    "line two",
                    "line three",
                    "line four different"
            ));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            // oldStrStartLine = 3 指向第 3 行；oldStr 内容与第 3 行的 "line three" 不匹配
            // 且 "x_nonexistent_99z" 在文件中不存在（确保 indexOf 也失败，触发诊断路径）
            edit.oldStrStartLine = 3;
            edit.oldStr = "x_nonexistent_99z";
            edit.newStr = "changed";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("预检查失败"));
            // 诊断应提到 "第 3 行"（因为 oldStrStartLine=3）
            assertTrue(result.contains("第 3 行"),
                    "诊断应报告第 3 行，而非错误地使用行号 3 作为字符偏移量: " + result);
            // 诊断应显示第 3 行的内容是 "line three"
            assertTrue(result.contains("line three"),
                    "诊断应正确显示第 3 行的文件内容，而非从字符位置 3 读取的碎片: " + result);
            // 文件未被修改
            assertEquals(Arrays.asList("line one", "line two", "line three", "line four different"),
                    Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    // ============================================================
    // 宽松匹配缩进保留规则：逐行填充（fill-in-the-blank）
    // ============================================================

    /**
     * 验证：newStr 带行首缩进时，以用户缩进为准（不继承原文件缩进）。
     * 文件缩进 4 空格，newStr 含 2 空格缩进 → 保留 2 空格。
     */
    @Test
    public void editLooseMatchPreservesUserIndent() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList("    foo();", "end"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();";
            edit.newStr = "  bar();";  // 用户主动写了 2 空格

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("  bar();", "end"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * 验证：newStr 部分行有缩进、部分行无缩进时，各行独立判断。
     * 有缩进的行保留用户缩进，无缩进的行继承原文件缩进。
     */
    @Test
    public void editLooseMatchMixedIndentAndNoIndent() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList("    foo();", "    bar();", "    baz();", "end"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();\nbaz();";
            // 第 2 行带 2 空格缩进，其余不带
            edit.newStr = "newFoo();\n  newBar();\nnewBaz();";

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("    newFoo();", "  newBar();", "    newBaz();", "end"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * 验证：用户通过 newStr 有意改变缩进级别（与原文件不同）时，以用户为准。
     * 文件缩进 4 空格，newStr 缩进 6 空格 → 保留 6 空格。
     */
    @Test
    public void editLooseMatchUserIntentChangesIndentLevel() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-edit-");
        try {
            Path file = workDir.resolve("demo.txt");
            Files.write(file, Arrays.asList("    foo();", "    bar();", "end"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStrStartLine = 1;
            edit.oldStr = "foo();\nbar();";
            edit.newStr = "      newFoo();\n      newBar();";  // 6 空格，与原文件不同

            String result = talent.edit("demo.txt", Collections.singletonList(edit), workDir.toString());

            assertTrue(result.contains("成功完成"));
            assertEquals(Arrays.asList("      newFoo();", "      newBar();", "end"), Files.readAllLines(file));
        } finally {
            deleteRecursively(workDir);
        }
    }
}
