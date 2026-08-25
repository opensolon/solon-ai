package org.noear.solon.ai.talents.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 诊断渲染层测试：只报 ERROR、限条数、行列 1-based、无 uri 前缀
 */
public class LspDiagnosticReporterTest {

    @Test
    @DisplayName("空列表/null 应返回 null（不产生任何注入）")
    public void testEmpty() {
        assertNull(LspDiagnosticReporter.renderBlock("a.java", null));
        assertNull(LspDiagnosticReporter.renderBlock("a.java", Collections.<Diagnostic>emptyList()));
    }

    @Test
    @DisplayName("仅 WARN/INFO/HINT 时应返回 null")
    public void testOnlyNonErrors() {
        List<Diagnostic> items = Arrays.asList(
                newDiag(DiagnosticSeverity.Warning, "unused import", 3, 5),
                newDiag(DiagnosticSeverity.Information, "consider final", 4, 1),
                newDiag(DiagnosticSeverity.Hint, "simplify", 5, 1));

        assertNull(LspDiagnosticReporter.renderBlock("a.java", items));
    }

    @Test
    @DisplayName("单条 ERROR：格式为 ERROR [line:col] message，行列 1-based")
    public void testSingleError() {
        List<Diagnostic> items = Arrays.asList(
                newDiag(DiagnosticSeverity.Error, "cannot find symbol", 11, 4),
                newDiag(DiagnosticSeverity.Warning, "unused import", 3, 5));

        String block = LspDiagnosticReporter.renderBlock("src/App.java", items);

        assertNotNull(block);
        assertEquals("<diagnostics file=\"src/App.java\">\n"
                + "ERROR [12:5] cannot find symbol\n"
                + "</diagnostics>", block);
        //只保留 ERROR
        assertFalse(block.contains("unused import"));
        //不带 file:// 前缀
        assertFalse(block.contains("file://"));
    }

    @Test
    @DisplayName("超过每文件上限时应截断并提示剩余条数")
    public void testTruncate() {
        List<Diagnostic> items = new ArrayList<>();
        for (int i = 0; i < LspDiagnosticReporter.MAX_PER_FILE + 5; i++) {
            items.add(newDiag(DiagnosticSeverity.Error, "err-" + i, i, 0));
        }

        String block = LspDiagnosticReporter.renderBlock("a.java", items);

        assertNotNull(block);
        assertTrue(block.contains("err-" + (LspDiagnosticReporter.MAX_PER_FILE - 1)));
        assertFalse(block.contains("err-" + LspDiagnosticReporter.MAX_PER_FILE));
        assertTrue(block.contains("... and 5 more"));
    }

    @Test
    @DisplayName("工具输出片段应带祈使句前缀，且限定 this file")
    public void testToolOutput() {
        List<Diagnostic> items = Arrays.asList(newDiag(DiagnosticSeverity.Error, "boom", 0, 0));

        String text = LspDiagnosticReporter.renderForToolOutput("a.java", items);

        assertNotNull(text);
        assertTrue(text.startsWith(LspDiagnosticReporter.PROMPT_PREFIX));
        assertTrue(text.contains("this file"));
        assertTrue(text.contains("<diagnostics file=\"a.java\">"));

        assertNull(LspDiagnosticReporter.renderForToolOutput("a.java",
                Arrays.asList(newDiag(DiagnosticSeverity.Warning, "meh", 0, 0))));
    }

    @Test
    @DisplayName("source 存在时附在消息后")
    public void testSourceSuffix() {
        Diagnostic d = newDiag(DiagnosticSeverity.Error, "bad type", 0, 0);
        d.setSource("javac");

        assertEquals("ERROR [1:1] bad type (javac)", LspDiagnosticReporter.renderItem(d));
    }

    @Test
    @DisplayName("完全重复的消息只出一行，且不挤占其它类型的名额")
    public void testDuplicatesCollapsed() {
        //真实场景：24 条同一句类型错误排在前面，2 条其它类型排在最后
        List<Diagnostic> items = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            items.add(newDiag(DiagnosticSeverity.Error, "Type 'string' is not assignable to type 'number'.", i, 10));
        }
        items.add(newDiag(DiagnosticSeverity.Error, "Cannot find name 'missingHelper'.", 30, 4));
        items.add(newDiag(DiagnosticSeverity.Error, "Expected 1 arguments, but got 2.", 31, 8));

        String block = LspDiagnosticReporter.renderBlock("a.ts", items);

        assertNotNull(block);
        //三类各一行，不多不少
        List<String> errorLines = errorLinesOf(block);
        assertEquals(3, errorLines.size(), "每类错误只应出一行");
        assertTrue(block.contains("Cannot find name 'missingHelper'."), "少数类型错误应被保留");
        assertTrue(block.contains("Expected 1 arguments, but got 2."), "少数类型错误应被保留");
        //被折叠的 23 条重复项计入剩余计数，错误总数（行数 + more）仍为 26
        assertTrue(block.contains("... and 23 more"), "重复项应计入剩余计数，不能丢失总数");
    }

    @Test
    @DisplayName("去重保留首次出现的那条（行号最小者）")
    public void testDedupeKeepsFirstOccurrence() {
        List<Diagnostic> items = new ArrayList<>();
        for (int i = 5; i < 25; i++) {
            items.add(newDiag(DiagnosticSeverity.Error, "same message", i, 0));
        }

        List<Diagnostic> shown = LspDiagnosticReporter.selectForOutput(
                LspDiagnosticReporter.filterErrors(items), LspDiagnosticReporter.MAX_PER_FILE);

        assertEquals(1, shown.size(), "单一类型只应剩一条");
        assertEquals(5, shown.get(0).getRange().getStart().getLine());
    }

    @Test
    @DisplayName("source 不同不算重复；输出仍按行号递增")
    public void testDedupeBySourceAndOrder() {
        List<Diagnostic> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            items.add(newDiag(DiagnosticSeverity.Error, "kind-A", i, 0));
        }
        Diagnostic other = newDiag(DiagnosticSeverity.Error, "kind-A", 20, 0);
        other.setSource("eslint");
        items.add(other);
        items.add(newDiag(DiagnosticSeverity.Error, "kind-B", 25, 0));

        List<Diagnostic> shown = LspDiagnosticReporter.selectForOutput(
                LspDiagnosticReporter.filterErrors(items), LspDiagnosticReporter.MAX_PER_FILE);

        //同句 message 但 source 不同，属于不同类型
        assertEquals(3, shown.size());

        int prevLine = -1;
        for (Diagnostic d : shown) {
            int line = d.getRange().getStart().getLine();
            assertTrue(line > prevLine, "输出应按行号递增");
            prevLine = line;
        }
    }

    @Test
    @DisplayName("不同类型数超过上限时仍按上限截断")
    public void testDistinctKindsExceedLimit() {
        List<Diagnostic> items = new ArrayList<>();
        for (int i = 0; i < LspDiagnosticReporter.MAX_PER_FILE + 5; i++) {
            items.add(newDiag(DiagnosticSeverity.Error, "kind-" + i, i, 0));
        }

        String block = LspDiagnosticReporter.renderBlock("a.ts", items);

        assertNotNull(block);
        assertEquals(LspDiagnosticReporter.MAX_PER_FILE, errorLinesOf(block).size());
        assertTrue(block.contains("... and 5 more"));
    }

    private static List<String> errorLinesOf(String block) {
        List<String> lines = new ArrayList<>();
        for (String line : block.split("\n")) {
            if (line.startsWith("ERROR ")) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static Diagnostic newDiag(DiagnosticSeverity severity, String message, int line, int character) {
        Diagnostic d = new Diagnostic();
        d.setSeverity(severity);
        d.setMessage(message);
        d.setRange(new Range(new Position(line, character), new Position(line, character + 1)));
        return d;
    }
}
