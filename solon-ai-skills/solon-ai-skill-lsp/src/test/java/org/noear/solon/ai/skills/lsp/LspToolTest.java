package org.noear.solon.ai.skills.lsp;

import org.junit.jupiter.api.*;
import org.noear.solon.ai.rag.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class LspToolTest {

    private Path worktree;
    private LspManager lspManager;
    private LspSkill tool;

    @BeforeEach
    public void setup() throws Exception {
        System.out.println("[Setup] Creating temp dir...");
        worktree = Files.createTempDirectory("lsp-test");

        String mainPy =
                "def greet(name):\n" +
                "    return \"Hello, \" + name + \"!\"\n" +
                "\n" +
                "result = greet(\"world\")\n" +
                "print(result)\n";
        Files.write(worktree.resolve("main.py"), mainPy.getBytes("UTF-8"));
        Files.write(worktree.resolve("readme.txt"), "hello world".getBytes());

        System.out.println("[Setup] Worktree: " + worktree);
        System.out.println("[Setup] main.py exists: " + Files.exists(worktree.resolve("main.py")));

        lspManager = new LspManager(worktree.toString());
        lspManager.registerServer("python", new LspServerParameters(
                Arrays.asList("pylsp"),
                Arrays.asList(".py", ".pyi")
        ));

        tool = new LspSkill(lspManager, worktree.toString());
        lspManager.setDiagnosticsCallback((uri, text) -> tool.updateDiagnostics(uri, text));

        System.out.println("[Setup] Done. tool=" + tool + ", lspManager=" + lspManager);
    }

    @AfterEach
    public void teardown() {
        System.out.println("[Teardown] Shutting down...");
        if (lspManager != null) {
            lspManager.shutdownAll();
        }
    }

    @Test
    public void testSetupWorks() {
        assertNotNull(tool, "tool should be initialized by @BeforeEach");
        assertNotNull(worktree, "worktree should be initialized");
        System.out.println("[testSetupWorks] tool=" + tool);
    }

    @Test
    public void testGoToDefinition() throws Exception {
        System.out.println("[testGoToDefinition] tool=" + tool);
        assertNotNull(tool, "tool should not be null");
        Document doc = tool.lsp("goToDefinition", "main.py", 4, 9, null, null);
        assertNotNull(doc);
        assertEquals("goToDefinition", doc.getMetadata("operation"));
        String content = doc.getContent();
        assertNotNull(content);
        assertFalse(content.contains("No results found"), "pylsp should find definition: " + content);
        assertTrue(content.contains("main.py"), "Definition should point to main.py");
    }

    @Test
    public void testFindReferences() throws Exception {
        assertNotNull(tool);
        Document doc = tool.lsp("findReferences", "main.py", 1, 5, null, null);
        assertNotNull(doc);
        assertEquals("findReferences", doc.getMetadata("operation"));
        String content = doc.getContent();
        assertNotNull(content);
        assertFalse(content.contains("No results found"), "pylsp should find references: " + content);
    }

    @Test
    public void testHover() throws Exception {
        assertNotNull(tool);
        Document doc = tool.lsp("hover", "main.py", 4, 9, null, null);
        assertNotNull(doc);
        assertEquals("hover", doc.getMetadata("operation"));
        String content = doc.getContent();
        assertNotNull(content);
        assertFalse(content.contains("No results found"), "pylsp should return hover info: " + content);
    }

    @Test
    public void testDocumentSymbol() throws Exception {
        assertNotNull(tool);
        Document doc = tool.lsp("documentSymbol", "main.py", 1, 1, null, null);
        assertNotNull(doc);
        assertEquals("documentSymbol", doc.getMetadata("operation"));
        String content = doc.getContent();
        assertNotNull(content);
        assertFalse(content.contains("No results found"), "pylsp should return symbols: " + content);
    }

    @Test
    public void testDiagnostics_NoCache() throws Exception {
        assertNotNull(tool);
        Document doc = tool.lsp("diagnostics", "main.py", 1, 1, null, null);
        assertNotNull(doc);
        assertEquals("diagnostics", doc.getMetadata("operation"));
        assertTrue(doc.getContent().contains("No diagnostics available"));
    }

    @Test
    public void testDiagnostics_WithCache() throws Exception {
        assertNotNull(tool);
        Path testFile = worktree.resolve("main.py");
        String uri = testFile.toUri().toString();
        tool.updateDiagnostics(uri, "main.py:1:1 [ERROR] missing semicolon");
        Document doc = tool.lsp("diagnostics", "main.py", 1, 1, null, null);
        assertNotNull(doc);
        assertTrue(doc.getContent().contains("missing semicolon"));
    }

    @Test
    public void testUpdateDiagnostics_RemoveWhenNull() {
        assertNotNull(tool);
        tool.updateDiagnostics("file:///test_null.py", "some error");
        tool.updateDiagnostics("file:///test_null.py", null);
    }

    @Test
    public void testUpdateDiagnostics_RemoveWhenEmpty() {
        assertNotNull(tool);
        tool.updateDiagnostics("file:///test_empty.py", "some error");
        tool.updateDiagnostics("file:///test_empty.py", "");
    }

    @Test
    public void testPathSecurityCheck() {
        assertNotNull(tool);
        assertThrows(SecurityException.class, () -> {
            tool.lsp("goToDefinition", "../outside.java", 1, 1, null, null);
        });
    }

    @Test
    public void testFileNotFound() {
        assertNotNull(tool);
        assertThrows(RuntimeException.class, () -> {
            tool.lsp("goToDefinition", "NotExists.py", 1, 1, null, null);
        });
    }

    @Test
    public void testUnknownOperation() {
        assertNotNull(tool);
        assertThrows(IllegalArgumentException.class, () -> {
            tool.lsp("unknownOperation", "main.py", 1, 1, null, null);
        });
    }

    @Test
    public void testNoLspServerConfigured() throws Exception {
        assertNotNull(tool);
        Document doc = tool.lsp("hover", "readme.txt", 1, 1, null, null);
        assertNotNull(doc);
        assertTrue(doc.getContent().contains("No LSP server configured"));
    }

    @Test
    public void testCustomCwd() throws Exception {
        assertNotNull(tool);
        Path subdir = worktree.resolve("sub");
        Files.createDirectories(subdir);
        String subPy = "def sub_func():\n    pass\n";
        Files.write(subdir.resolve("sub.py"), subPy.getBytes("UTF-8"));
        Document doc = tool.lsp("documentSymbol", "sub.py", 1, 1, subdir.toString(), null);
        assertNotNull(doc);
        assertEquals("documentSymbol", doc.getMetadata("operation"));
    }
}
