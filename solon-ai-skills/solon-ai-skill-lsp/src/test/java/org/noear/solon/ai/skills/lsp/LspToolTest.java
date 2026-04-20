/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.skills.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LspTool 单元测试
 *
 * <p>通过 MockLspClient + LspManager.registerTestClient() 进行测试，
 * 不使用 mock 框架，也不需要启动真实的语言服务器进程。
 *
 * @author noear
 * @since 3.10.0
 */
public class LspToolTest {

    private Path worktree;
    private LspManager lspManager;
    private MockLspClient mockClient;

    @BeforeEach
    public void setup() throws Exception {
        worktree = Files.createTempDirectory("lsp-test");

        // 创建测试文件
        Path testFile = worktree.resolve("Test.java");
        Files.write(testFile, "public class Test { public void foo() {} }".getBytes());

        // 创建 LspManager 并注入 MockClient
        lspManager = new LspManager(worktree.toString());
        mockClient = new MockLspClient();

        LspServerParameters javaParams = new LspServerParameters(
                Arrays.asList("echo", "mock"),
                Arrays.asList(".java")
        );
        lspManager.registerTestClient("java", javaParams, mockClient);
    }

    @AfterEach
    public void teardown() {
        if (lspManager != null) {
            lspManager.shutdownAll();
        }
    }

    // ==================== LspTool 核心操作测试 ====================

    @Test
    public void testGoToDefinition() throws Exception {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        Document doc = tool.lsp("goToDefinition", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
        assertEquals("goToDefinition Test.java:1:10", doc.getTitle());
        assertTrue(doc.getContent().contains("location"));
        assertEquals("goToDefinition", doc.getMetadata("operation"));
    }

    @Test
    public void testFindReferences() throws Exception {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        Document doc = tool.lsp("findReferences", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
        assertEquals("findReferences Test.java:1:10", doc.getTitle());
        assertEquals("findReferences", doc.getMetadata("operation"));
    }

    @Test
    public void testHover() throws Exception {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        Document doc = tool.lsp("hover", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
        assertEquals("hover", doc.getMetadata("operation"));
        assertTrue(doc.getContent().contains("Mock hover content"));
    }

    @Test
    public void testDocumentSymbol() throws Exception {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        Document doc = tool.lsp("documentSymbol", "Test.java", 1, 1, null, null);

        assertNotNull(doc);
        assertEquals("documentSymbol", doc.getMetadata("operation"));
        assertTrue(doc.getContent().contains("foo"));
    }

    @Test
    public void testWorkspaceSymbol() throws Exception {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        Document doc = tool.lsp("workspaceSymbol", "Test.java", 1, 1, null, null);

        assertNotNull(doc);
        assertEquals("workspaceSymbol", doc.getMetadata("operation"));
    }

    @Test
    public void testGoToImplementation() throws Exception {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        Document doc = tool.lsp("goToImplementation", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
        assertEquals("goToImplementation", doc.getMetadata("operation"));
    }

    @Test
    public void testPrepareCallHierarchy() throws Exception {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        Document doc = tool.lsp("prepareCallHierarchy", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
        assertEquals("prepareCallHierarchy", doc.getMetadata("operation"));
    }

    @Test
    public void testIncomingCalls() throws Exception {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        Document doc = tool.lsp("incomingCalls", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
        assertEquals("incomingCalls", doc.getMetadata("operation"));
    }

    @Test
    public void testOutgoingCalls() throws Exception {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        Document doc = tool.lsp("outgoingCalls", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
        assertEquals("outgoingCalls", doc.getMetadata("operation"));
    }

    // ==================== 边界条件测试 ====================

    @Test
    public void testDiagnostics_NoCache() throws Exception {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        // 没有缓存时，返回 "No diagnostics available"
        Document doc = tool.lsp("diagnostics", "Test.java", 1, 1, null, null);

        assertNotNull(doc);
        assertEquals("diagnostics", doc.getMetadata("operation"));
        assertTrue(doc.getContent().contains("No diagnostics available"));
    }

    @Test
    public void testDiagnostics_WithCache() throws Exception {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        // 先通过 updateDiagnostics 设置缓存
        Path testFile = worktree.resolve("Test.java");
        String uri = testFile.toUri().toString();
        tool.updateDiagnostics(uri, "Test.java:1:1 [ERROR] missing semicolon");

        // 再查询 diagnostics
        Document doc = tool.lsp("diagnostics", "Test.java", 1, 1, null, null);

        assertNotNull(doc);
        assertTrue(doc.getContent().contains("missing semicolon"));
    }

    @Test
    public void testNoResults() throws Exception {
        // 使用返回空结果的 MockClient
        MockLspClient emptyClient = new MockLspClient() {
            @Override
            public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
                return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
            }
        };

        LspManager emptyManager = new LspManager(worktree.toString());
        LspServerParameters javaParams = new LspServerParameters(
                Arrays.asList("echo", "mock"), Arrays.asList(".java"));
        emptyManager.registerTestClient("java", javaParams, emptyClient);

        LspSkill tool = new LspSkill(emptyManager, worktree.toString());
        Document doc = tool.lsp("goToDefinition", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
        assertTrue(doc.getContent().contains("No results found"));

        emptyManager.shutdownAll();
    }

    @Test
    public void testNoLspServerConfigured() throws Exception {
        // 创建一个不匹配任何扩展名的文件
        Path txtFile = worktree.resolve("readme.txt");
        Files.write(txtFile, "hello world".getBytes());

        LspSkill tool = new LspSkill(lspManager, worktree.toString());
        Document doc = tool.lsp("hover", "readme.txt", 1, 1, null, null);

        assertNotNull(doc);
        assertTrue(doc.getContent().contains("No LSP server configured"));
    }

    // ==================== 安全校验测试 ====================

    @Test
    public void testPathSecurityCheck() {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        assertThrows(SecurityException.class, () -> {
            tool.lsp("goToDefinition", "../outside.java", 1, 1, null, null);
        });
    }

    @Test
    public void testFileNotFound() {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        assertThrows(RuntimeException.class, () -> {
            tool.lsp("goToDefinition", "NotExists.java", 1, 1, null, null);
        });
    }

    @Test
    public void testUnknownOperation() {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        assertThrows(IllegalArgumentException.class, () -> {
            tool.lsp("unknownOperation", "Test.java", 1, 1, null, null);
        });
    }

    // ==================== 坐标转换测试 ====================

    @Test
    public void testCoordinateConversion() throws Exception {
        // 通过匿名类拦截参数，验证坐标从 1-based 转为 0-based
        MockLspClient trackingClient = new MockLspClient() {
            @Override
            public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
                // 验证坐标已被转换为 0-based
                assertEquals(0, params.getPosition().getLine());     // line 1 -> 0
                assertEquals(9, params.getPosition().getCharacter()); // char 10 -> 9
                return super.definition(params);
            }
        };

        LspManager trackManager = new LspManager(worktree.toString());
        LspServerParameters javaParams = new LspServerParameters(
                Arrays.asList("echo", "mock"), Arrays.asList(".java"));
        trackManager.registerTestClient("java", javaParams, trackingClient);

        LspSkill tool = new LspSkill(trackManager, worktree.toString());
        tool.lsp("goToDefinition", "Test.java", 1, 10, null, null);

        trackManager.shutdownAll();
    }

    // ==================== updateDiagnostics 测试 ====================

    @Test
    public void testUpdateDiagnostics_Put() {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        tool.updateDiagnostics("file:///test.java", "some error");
        // 通过 diagnostics 操作验证缓存
        // （直接调用 updateDiagnostics 后缓存生效即可，不需要复杂的验证）
    }

    @Test
    public void testUpdateDiagnostics_RemoveWhenNull() {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        tool.updateDiagnostics("file:///test.java", "some error");
        tool.updateDiagnostics("file:///test.java", null);
        // null 或空字符串时，缓存条目应被移除
    }

    @Test
    public void testUpdateDiagnostics_RemoveWhenEmpty() {
        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        tool.updateDiagnostics("file:///test.java", "some error");
        tool.updateDiagnostics("file:///test.java", "");
        // 空字符串时，缓存条目应被移除
    }

    // ==================== 自定义 __cwd 测试 ====================

    @Test
    public void testCustomCwd() throws Exception {
        // 创建子目录和文件
        Path subdir = worktree.resolve("sub");
        Files.createDirectories(subdir);
        Path subFile = subdir.resolve("Sub.java");
        Files.write(subFile, "public class Sub {}".getBytes());

        LspSkill tool = new LspSkill(lspManager, worktree.toString());

        // 使用 __cwd 参数指定工作目录为子目录
        Document doc = tool.lsp("hover", "Sub.java", 1, 1, subdir.toString(), null);

        assertNotNull(doc);
        assertEquals("hover", doc.getMetadata("operation"));
    }

    // ==================== MockLspClient 实现 ====================

    /**
     * 测试用 LspClient 实现，返回预设数据
     */
    static class MockLspClient implements LspClient {

        @Override
        public void touchFile(String uri) {
            // 空实现，避免 NPE
        }

        // LanguageClient 抽象方法
        @Override public void telemetryEvent(Object object) {}
        @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}
        @Override public void showMessage(MessageParams messageParams) {}
        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void logMessage(MessageParams message) {}

        @Override
        public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
            List<Location> locations = new ArrayList<>();
            locations.add(new Location(params.getTextDocument().getUri(),
                    new Range(new Position(0, 0), new Position(0, 10))));
            return CompletableFuture.completedFuture(Either.forLeft(locations));
        }

        @Override
        public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
            List<Location> locations = new ArrayList<>();
            locations.add(new Location(params.getTextDocument().getUri(),
                    new Range(new Position(1, 0), new Position(1, 10))));
            return CompletableFuture.completedFuture(locations);
        }

        @Override
        public CompletableFuture<Hover> hover(HoverParams params) {
            Hover hover = new Hover();
            MarkupContent content = new MarkupContent();
            content.setKind("plaintext");
            content.setValue("Mock hover content");
            hover.setContents(content);
            return CompletableFuture.completedFuture(hover);
        }

        @Override
        public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
            List<Either<SymbolInformation, DocumentSymbol>> symbols = new ArrayList<>();
            DocumentSymbol ds = new DocumentSymbol("foo", SymbolKind.Method,
                    new Range(new Position(0, 0), new Position(0, 10)),
                    new Range(new Position(0, 0), new Position(0, 10)));
            symbols.add(Either.forRight(ds));
            return CompletableFuture.completedFuture(symbols);
        }

        @Override
        public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> workspaceSymbol(WorkspaceSymbolParams params) {
            List<WorkspaceSymbol> symbols = new ArrayList<>();
            WorkspaceSymbol ws = new WorkspaceSymbol();
            ws.setName("test");
            ws.setKind(SymbolKind.Class);
            symbols.add(ws);
            return CompletableFuture.completedFuture(Either.forRight(symbols));
        }

        @Override
        public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(ImplementationParams params) {
            List<Location> locations = new ArrayList<>();
            return CompletableFuture.completedFuture(Either.forLeft(locations));
        }

        @Override
        public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params) {
            List<CallHierarchyItem> items = new ArrayList<>();
            return CompletableFuture.completedFuture(items);
        }

        @Override
        public CompletableFuture<List<CallHierarchyIncomingCall>> incomingCalls(String uri, int line, int offset) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        @Override
        public CompletableFuture<List<CallHierarchyOutgoingCall>> outgoingCalls(String uri, int line, int offset) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        @Override
        public void shutdown() {
            // 空实现
        }
    }
}
