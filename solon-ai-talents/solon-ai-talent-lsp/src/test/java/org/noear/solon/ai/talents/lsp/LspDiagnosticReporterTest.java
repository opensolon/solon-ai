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

    private static Diagnostic newDiag(DiagnosticSeverity severity, String message, int line, int character) {
        Diagnostic d = new Diagnostic();
        d.setSeverity(severity);
        d.setMessage(message);
        d.setRange(new Range(new Position(line, character), new Position(line, character + 1)));
        return d;
    }
}
