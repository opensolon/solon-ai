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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LspTool 单元测试
 */
public class LspToolTest {

    private Path worktree;
    private MockLspClient mockClient;

    @BeforeEach
    public void setup() throws Exception {
        // 创建临时目录
        worktree = Files.createTempDirectory("lsp-test");

        // 创建一个测试文件
        Path testFile = worktree.resolve("Test.java");
        Files.write(testFile, "public class Test { public void foo() {} }".getBytes());

        mockClient = new MockLspClient();
    }

    @Test
    public void testGoToDefinition() throws Exception {
        if (worktree == null) {
            worktree = Files.createTempDirectory("lsp-test");
            Path testFile = worktree.resolve("Test.java");
            Files.write(testFile, "public class Test { public void foo() {} }".getBytes());
        }

        LspTool tool = new LspTool(mockClient, worktree.toString());
        Document doc = tool.lsp("goToDefinition", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
        assertEquals("goToDefinition Test.java:1:10", doc.getTitle());
        assertTrue(doc.getContent().contains("location"));
    }

    @Test
    public void testFindReferences() throws Exception {
        LspTool tool = new LspTool(mockClient, worktree.toString());

        Document doc = tool.lsp("findReferences", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
        assertEquals("findReferences Test.java:1:10", doc.getTitle());
    }

    @Test
    public void testHover() throws Exception {
        LspTool tool = new LspTool(mockClient, worktree.toString());

        Document doc = tool.lsp("hover", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
        assertTrue(doc.getMetadata("operation").equals("hover"));
    }

    @Test
    public void testDocumentSymbol() throws Exception {
        LspTool tool = new LspTool(mockClient, worktree.toString());

        Document doc = tool.lsp("documentSymbol", "Test.java", 1, 1, null, null);

        assertNotNull(doc);
    }

    @Test
    public void testWorkspaceSymbol() throws Exception {
        LspTool tool = new LspTool(mockClient, worktree.toString());

        Document doc = tool.lsp("workspaceSymbol", "Test.java", 1, 1, null, null);

        assertNotNull(doc);
    }

    @Test
    public void testGoToImplementation() throws Exception {
        LspTool tool = new LspTool(mockClient, worktree.toString());

        Document doc = tool.lsp("goToImplementation", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
    }

    @Test
    public void testPrepareCallHierarchy() throws Exception {
        LspTool tool = new LspTool(mockClient, worktree.toString());

        Document doc = tool.lsp("prepareCallHierarchy", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
    }

    @Test
    public void testIncomingCalls() throws Exception {
        LspTool tool = new LspTool(mockClient, worktree.toString());

        Document doc = tool.lsp("incomingCalls", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
    }

    @Test
    public void testOutgoingCalls() throws Exception {
        LspTool tool = new LspTool(mockClient, worktree.toString());

        Document doc = tool.lsp("outgoingCalls", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
    }

    @Test
    public void testNoResults() throws Exception {
        MockLspClient emptyClient = new MockLspClient() {
            @Override
            public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
                return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
            }
        };

        LspTool tool = new LspTool(emptyClient, worktree.toString());

        Document doc = tool.lsp("goToDefinition", "Test.java", 1, 10, null, null);

        assertNotNull(doc);
        assertTrue(doc.getContent().contains("No results found"));
    }

    @Test
    public void testPathSecurityCheck() {
        LspTool tool = new LspTool(mockClient, worktree.toString());

        assertThrows(SecurityException.class, () -> {
            tool.lsp("goToDefinition", "../outside.java", 1, 1, null, null);
        });
    }

    @Test
    public void testFileNotFound() {
        LspTool tool = new LspTool(mockClient, worktree.toString());

        assertThrows(RuntimeException.class, () -> {
            tool.lsp("goToDefinition", "NotExists.java", 1, 1, null, null);
        });
    }

    @Test
    public void testUnknownOperation() {
        LspTool tool = new LspTool(mockClient, worktree.toString());

        assertThrows(IllegalArgumentException.class, () -> {
            tool.lsp("unknownOperation", "Test.java", 1, 1, null, null);
        });
    }

    @Test
    public void testCoordinateConversion() throws Exception {
        MockLspClient trackingClient = new MockLspClient() {
            @Override
            public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
                // 验证坐标已被转换为 0-based
                assertEquals(0, params.getPosition().getLine());   // line 1 -> 0
                assertEquals(9, params.getPosition().getCharacter()); // char 10 -> 9
                return super.definition(params);
            }
        };

        LspTool tool = new LspTool(trackingClient, worktree.toString());
        tool.lsp("goToDefinition", "Test.java", 1, 10, null, null);
    }

    /**
     * Mock LspClient 实现
     */
    static class MockLspClient implements LspClient {
        private String lastUri;

        @Override
        public void touchFile(String uri) {
            // 空实现，避免 NPE
            this.lastUri = uri;
        }

        @Override
        public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
            List<Location> locations = new ArrayList<>();
            locations.add(new Location(params.getTextDocument().getUri(), new Range(new Position(0, 0), new Position(0, 10))));
            return CompletableFuture.completedFuture(Either.forLeft(locations));
        }

        @Override
        public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
            List<Location> locations = new ArrayList<>();
            locations.add(new Location(params.getTextDocument().getUri(), new Range(new Position(1, 0), new Position(1, 10))));
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
    }
}