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
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LspClientImpl 单元测试
 *
 * <p>通过反射注入 MockLanguageServer，绕过真实进程启动，
 * 测试 LspClientImpl 的各个业务方法。
 *
 * @author noear
 * @since 3.10.0
 */
public class LspClientImplTest {

    private LspClientImpl client;
    private Path tempDir;
    private MockLanguageServer mockServer;

    @BeforeEach
    public void setup() throws Exception {
        tempDir = Files.createTempDirectory("lsp-client-impl-test");

        // 使用 "true" 命令（Unix 下立即退出），进程启动后马上结束
        client = new LspClientImpl(new String[]{"true"}, tempDir.toString());

        // 通过反射注入 MockLanguageServer，覆盖真实连接
        mockServer = new MockLanguageServer();
        setField(client, "remoteServer", mockServer);
    }

    @AfterEach
    public void teardown() {
        if (client != null) {
            try {
                client.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== publishDiagnostics 测试 ====================

    @Test
    public void testPublishDiagnostics_SingleError() {
        AtomicReference<String> receivedUri = new AtomicReference<>();
        AtomicReference<String> receivedText = new AtomicReference<>();

        client.setDiagnosticsConsumer((uri, text) -> {
            receivedUri.set(uri);
            receivedText.set(text);
        });

        Diagnostic diag = new Diagnostic(
                new Range(new Position(2, 5), new Position(2, 10)),
                "missing semicolon"
        );
        diag.setSeverity(DiagnosticSeverity.Error);
        diag.setSource("javac");

        PublishDiagnosticsParams params = new PublishDiagnosticsParams(
                "file:///Test.java", Collections.singletonList(diag)
        );
        client.publishDiagnostics(params);

        assertEquals("file:///Test.java", receivedUri.get());
        assertNotNull(receivedText.get());
        assertTrue(receivedText.get().contains("missing semicolon"));
        assertTrue(receivedText.get().contains("ERROR"));
        assertTrue(receivedText.get().contains("(javac)"));
    }

    @Test
    public void testPublishDiagnostics_PositionFormatting() {
        // 验证 0-based -> 1-based 的位置转换
        AtomicReference<String> receivedText = new AtomicReference<>();
        client.setDiagnosticsConsumer((uri, text) -> receivedText.set(text));

        Diagnostic diag = new Diagnostic(
                new Range(new Position(3, 7), new Position(3, 12)),
                "some error"
        );
        diag.setSeverity(DiagnosticSeverity.Error);

        PublishDiagnosticsParams params = new PublishDiagnosticsParams(
                "file:///Test.java", Collections.singletonList(diag)
        );
        client.publishDiagnostics(params);

        assertNotNull(receivedText.get());
        // start.line=3 -> 输出 4, start.character=7 -> 输出 8
        assertTrue(receivedText.get().contains("file:///Test.java:4:8"));
    }

    @Test
    public void testPublishDiagnostics_NullDiagnostics() {
        AtomicReference<String> receivedUri = new AtomicReference<>();
        AtomicReference<String> receivedText = new AtomicReference<>();

        client.setDiagnosticsConsumer((uri, text) -> {
            receivedUri.set(uri);
            receivedText.set(text);
        });

        PublishDiagnosticsParams params = new PublishDiagnosticsParams(
                "file:///Test.java", null
        );
        client.publishDiagnostics(params);

        assertEquals("file:///Test.java", receivedUri.get());
        assertNull(receivedText.get());
    }

    @Test
    public void testPublishDiagnostics_EmptyDiagnostics() {
        AtomicReference<String> receivedUri = new AtomicReference<>();
        AtomicReference<String> receivedText = new AtomicReference<>();

        client.setDiagnosticsConsumer((uri, text) -> {
            receivedUri.set(uri);
            receivedText.set(text);
        });

        PublishDiagnosticsParams params = new PublishDiagnosticsParams(
                "file:///Test.java", Collections.emptyList()
        );
        client.publishDiagnostics(params);

        assertEquals("file:///Test.java", receivedUri.get());
        assertNull(receivedText.get());
    }

    @Test
    public void testPublishDiagnostics_MultipleDiagnostics() {
        AtomicReference<String> receivedText = new AtomicReference<>();
        client.setDiagnosticsConsumer((uri, text) -> receivedText.set(text));

        List<Diagnostic> diags = new ArrayList<>();
        Diagnostic d1 = new Diagnostic(new Range(new Position(0, 0), new Position(0, 5)), "error1");
        d1.setSeverity(DiagnosticSeverity.Error);
        diags.add(d1);

        Diagnostic d2 = new Diagnostic(new Range(new Position(2, 3), new Position(2, 8)), "warning1");
        d2.setSeverity(DiagnosticSeverity.Warning);
        diags.add(d2);

        Diagnostic d3 = new Diagnostic(new Range(new Position(5, 1), new Position(5, 6)), "info1");
        d3.setSeverity(DiagnosticSeverity.Information);
        diags.add(d3);

        Diagnostic d4 = new Diagnostic(new Range(new Position(10, 0), new Position(10, 4)), "hint1");
        d4.setSeverity(DiagnosticSeverity.Hint);
        diags.add(d4);

        PublishDiagnosticsParams params = new PublishDiagnosticsParams("file:///Test.java", diags);
        client.publishDiagnostics(params);

        String text = receivedText.get();
        assertNotNull(text);
        assertTrue(text.contains("error1"));
        assertTrue(text.contains("warning1"));
        assertTrue(text.contains("info1"));
        assertTrue(text.contains("hint1"));
        assertTrue(text.contains("ERROR"));
        assertTrue(text.contains("WARN"));
        assertTrue(text.contains("INFO"));
        assertTrue(text.contains("HINT"));
    }

    @Test
    public void testPublishDiagnostics_NoConsumer() {
        // 不设置 consumer，不应抛异常
        Diagnostic diag = new Diagnostic(
                new Range(new Position(0, 0), new Position(0, 5)), "some error"
        );
        PublishDiagnosticsParams params = new PublishDiagnosticsParams(
                "file:///Test.java", Collections.singletonList(diag)
        );
        assertDoesNotThrow(() -> client.publishDiagnostics(params));
    }

    @Test
    public void testPublishDiagnostics_SeverityNull() {
        AtomicReference<String> receivedText = new AtomicReference<>();
        client.setDiagnosticsConsumer((uri, text) -> receivedText.set(text));

        Diagnostic diag = new Diagnostic(
                new Range(new Position(0, 0), new Position(0, 5)), "unknown severity"
        );
        diag.setSeverity(null);

        PublishDiagnosticsParams params = new PublishDiagnosticsParams(
                "file:///Test.java", Collections.singletonList(diag)
        );
        client.publishDiagnostics(params);

        assertNotNull(receivedText.get());
        assertTrue(receivedText.get().contains("[?]"));
        assertTrue(receivedText.get().contains("unknown severity"));
    }

    @Test
    public void testPublishDiagnostics_SourceNull() {
        AtomicReference<String> receivedText = new AtomicReference<>();
        client.setDiagnosticsConsumer((uri, text) -> receivedText.set(text));

        Diagnostic diag = new Diagnostic(
                new Range(new Position(0, 0), new Position(0, 5)), "error msg"
        );
        diag.setSeverity(DiagnosticSeverity.Error);
        diag.setSource(null);

        PublishDiagnosticsParams params = new PublishDiagnosticsParams(
                "file:///Test.java", Collections.singletonList(diag)
        );
        client.publishDiagnostics(params);

        assertNotNull(receivedText.get());
        assertFalse(receivedText.get().contains("()"));
        assertFalse(receivedText.get().contains("(null)"));
    }

    @Test
    public void testPublishDiagnostics_SourcePresent() {
        AtomicReference<String> receivedText = new AtomicReference<>();
        client.setDiagnosticsConsumer((uri, text) -> receivedText.set(text));

        Diagnostic diag = new Diagnostic(
                new Range(new Position(0, 0), new Position(0, 5)), "error msg"
        );
        diag.setSeverity(DiagnosticSeverity.Error);
        diag.setSource("eslint");

        PublishDiagnosticsParams params = new PublishDiagnosticsParams(
                "file:///app.ts", Collections.singletonList(diag)
        );
        client.publishDiagnostics(params);

        assertTrue(receivedText.get().contains("(eslint)"));
    }

    // ==================== setDiagnosticsConsumer 测试 ====================

    @Test
    public void testSetDiagnosticsConsumer() {
        AtomicBoolean called = new AtomicBoolean(false);
        client.setDiagnosticsConsumer((uri, text) -> called.set(true));

        PublishDiagnosticsParams params = new PublishDiagnosticsParams(
                "file:///Test.java", Collections.emptyList()
        );
        client.publishDiagnostics(params);

        assertTrue(called.get());
    }

    // ==================== touchFile 测试 ====================

    @Test
    public void testTouchFile_OpensFile() throws Exception {
        Path testFile = tempDir.resolve("Demo.java");
        Files.write(testFile, "public class Demo {}".getBytes());

        String uri = testFile.toUri().toString();
        mockServer.textDocumentService.didOpenCalled = false;
        client.touchFile(uri);

        assertTrue(mockServer.textDocumentService.didOpenCalled);
    }

    @Test
    public void testTouchFile_SkipDuplicateOpen() throws Exception {
        Path testFile = tempDir.resolve("Demo.java");
        Files.write(testFile, "public class Demo {}".getBytes());

        String uri = testFile.toUri().toString();
        client.touchFile(uri);
        mockServer.textDocumentService.didOpenCalled = false;
        // 第二次调用应该跳过
        client.touchFile(uri);

        assertFalse(mockServer.textDocumentService.didOpenCalled);
    }

    @Test
    public void testTouchFile_NonexistentFile() {
        String uri = tempDir.resolve("NotExist.java").toUri().toString();
        assertDoesNotThrow(() -> client.touchFile(uri));
        assertTrue(mockServer.textDocumentService.didOpenCalled);
    }

    // ==================== LSP 操作方法测试 ====================

    @Test
    public void testDefinition() {
        DefinitionParams params = new DefinitionParams(
                new TextDocumentIdentifier("file:///Test.java"), new Position(0, 5)
        );
        CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> result =
                client.definition(params);

        assertNotNull(result);
        assertTrue(mockServer.textDocumentService.definitionCalled);
    }

    @Test
    public void testReferences() {
        ReferenceParams params = new ReferenceParams(
                new TextDocumentIdentifier("file:///Test.java"), new Position(0, 5), new ReferenceContext()
        );
        CompletableFuture<List<? extends Location>> result = client.references(params);

        assertNotNull(result);
        assertTrue(mockServer.textDocumentService.referencesCalled);
    }

    @Test
    public void testHover() {
        HoverParams params = new HoverParams(
                new TextDocumentIdentifier("file:///Test.java"), new Position(0, 5)
        );
        CompletableFuture<Hover> result = client.hover(params);

        assertNotNull(result);
        assertTrue(mockServer.textDocumentService.hoverCalled);
    }

    @Test
    public void testDocumentSymbol() {
        DocumentSymbolParams params = new DocumentSymbolParams(
                new TextDocumentIdentifier("file:///Test.java")
        );
        CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> result =
                client.documentSymbol(params);

        assertNotNull(result);
        assertTrue(mockServer.textDocumentService.documentSymbolCalled);
    }

    @Test
    public void testWorkspaceSymbol() {
        WorkspaceSymbolParams params = new WorkspaceSymbolParams("test");
        CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> result =
                client.workspaceSymbol(params);

        assertNotNull(result);
        assertTrue(mockServer.workspaceService.symbolCalled);
    }

    @Test
    public void testImplementation() {
        ImplementationParams params = new ImplementationParams(
                new TextDocumentIdentifier("file:///Test.java"), new Position(0, 5)
        );
        CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> result =
                client.implementation(params);

        assertNotNull(result);
        assertTrue(mockServer.textDocumentService.implementationCalled);
    }

    @Test
    public void testPrepareCallHierarchy() {
        CallHierarchyPrepareParams params = new CallHierarchyPrepareParams(
                new TextDocumentIdentifier("file:///Test.java"), new Position(0, 5)
        );
        CompletableFuture<List<CallHierarchyItem>> result = client.prepareCallHierarchy(params);

        assertNotNull(result);
        assertTrue(mockServer.textDocumentService.prepareCallHierarchyCalled);
    }

    @Test
    public void testIncomingCalls_EmptyPrepareResult() throws Exception {
        // 默认 callHierarchyItems 为空列表
        CompletableFuture<List<CallHierarchyIncomingCall>> result =
                client.incomingCalls("file:///Test.java", 0, 5);

        assertNull(result.get());
    }

    @Test
    public void testOutgoingCalls_EmptyPrepareResult() throws Exception {
        CompletableFuture<List<CallHierarchyOutgoingCall>> result =
                client.outgoingCalls("file:///Test.java", 0, 5);

        assertNull(result.get());
    }

    @Test
    public void testIncomingCalls_WithItems() throws Exception {
        List<CallHierarchyItem> items = new ArrayList<>();
        CallHierarchyItem item = new CallHierarchyItem();
        item.setName("foo");
        item.setUri("file:///Test.java");
        items.add(item);
        mockServer.textDocumentService.callHierarchyItems = items;

        CompletableFuture<List<CallHierarchyIncomingCall>> result =
                client.incomingCalls("file:///Test.java", 0, 5);
        List<CallHierarchyIncomingCall> calls = result.get();

        assertNotNull(calls);
        assertTrue(mockServer.textDocumentService.incomingCallsCalled);
    }

    @Test
    public void testOutgoingCalls_WithItems() throws Exception {
        List<CallHierarchyItem> items = new ArrayList<>();
        CallHierarchyItem item = new CallHierarchyItem();
        item.setName("bar");
        item.setUri("file:///Test.java");
        items.add(item);
        mockServer.textDocumentService.callHierarchyItems = items;

        CompletableFuture<List<CallHierarchyOutgoingCall>> result =
                client.outgoingCalls("file:///Test.java", 0, 5);
        List<CallHierarchyOutgoingCall> calls = result.get();

        assertNotNull(calls);
        assertTrue(mockServer.textDocumentService.outgoingCallsCalled);
    }

    @Test
    public void testIncomingCalls_NullPrepareResult() throws Exception {
        mockServer.textDocumentService.callHierarchyItems = null;

        CompletableFuture<List<CallHierarchyIncomingCall>> result =
                client.incomingCalls("file:///Test.java", 0, 5);

        assertNull(result.get());
    }

    @Test
    public void testOutgoingCalls_NullPrepareResult() throws Exception {
        mockServer.textDocumentService.callHierarchyItems = null;

        CompletableFuture<List<CallHierarchyOutgoingCall>> result =
                client.outgoingCalls("file:///Test.java", 0, 5);

        assertNull(result.get());
    }

    // ==================== LanguageClient 默认方法测试 ====================

    @Test
    public void testTelemetryEvent() {
        assertDoesNotThrow(() -> client.telemetryEvent("test"));
    }

    @Test
    public void testShowMessage() {
        assertDoesNotThrow(() -> client.showMessage(new MessageParams(MessageType.Info, "hello")));
    }

    @Test
    public void testShowMessageRequest() {
        CompletableFuture<MessageActionItem> result =
                client.showMessageRequest(new ShowMessageRequestParams());
        assertNotNull(result);
        assertNull(result.join());
    }

    @Test
    public void testLogMessage() {
        assertDoesNotThrow(() -> client.logMessage(new MessageParams(MessageType.Log, "test log")));
    }

    // ==================== shutdown 测试 ====================

    @Test
    public void testShutdown() {
        assertDoesNotThrow(() -> client.shutdown());
    }

    @Test
    public void testShutdown_CalledTwice() {
        client.shutdown();
        // 第二次不应抛异常（remoteServer 仍不为 null，但已 shutdown）
        assertDoesNotThrow(() -> client.shutdown());
    }

    // ==================== 辅助方法 ====================

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ==================== Mock 类 ====================

    /**
     * Mock LanguageServer
     */
    static class MockLanguageServer implements LanguageServer {
        final MockTextDocumentService textDocumentService = new MockTextDocumentService();
        final MockWorkspaceService workspaceService = new MockWorkspaceService();

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            ServerCapabilities caps = new ServerCapabilities();
            return CompletableFuture.completedFuture(new InitializeResult(caps));
        }

        @Override
        public void initialized(InitializedParams params) {}

        @Override
        public CompletableFuture<Object> shutdown() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {}

        @Override
        public TextDocumentService getTextDocumentService() {
            return textDocumentService;
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            return workspaceService;
        }
    }

    /**
     * Mock TextDocumentService，仅实现 LspClientImpl 使用的方法
     */
    static class MockTextDocumentService implements TextDocumentService {
        boolean didOpenCalled;
        boolean definitionCalled;
        boolean referencesCalled;
        boolean hoverCalled;
        boolean documentSymbolCalled;
        boolean implementationCalled;
        boolean prepareCallHierarchyCalled;
        boolean incomingCallsCalled;
        boolean outgoingCallsCalled;

        List<CallHierarchyItem> callHierarchyItems = new ArrayList<>();

        @Override
        public void didOpen(DidOpenTextDocumentParams params) {
            didOpenCalled = true;
        }

        @Override
        public void didChange(DidChangeTextDocumentParams params) {}

        @Override
        public void didClose(DidCloseTextDocumentParams params) {}

        @Override
        public void didSave(DidSaveTextDocumentParams params) {}

        @Override
        public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
            definitionCalled = true;
            return CompletableFuture.completedFuture(Either.forLeft(new ArrayList<>()));
        }

        @Override
        public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
            referencesCalled = true;
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        @Override
        public CompletableFuture<Hover> hover(HoverParams params) {
            hoverCalled = true;
            return CompletableFuture.completedFuture(new Hover());
        }

        @Override
        public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
            documentSymbolCalled = true;
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        @Override
        public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(ImplementationParams params) {
            implementationCalled = true;
            return CompletableFuture.completedFuture(Either.forLeft(new ArrayList<>()));
        }

        @Override
        public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params) {
            prepareCallHierarchyCalled = true;
            return CompletableFuture.completedFuture(callHierarchyItems);
        }

        @Override
        public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams params) {
            incomingCallsCalled = true;
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        @Override
        public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams params) {
            outgoingCallsCalled = true;
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
    }

    /**
     * Mock WorkspaceService
     */
    static class MockWorkspaceService implements WorkspaceService {
        boolean symbolCalled;

        @Override
        public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
            symbolCalled = true;
            return CompletableFuture.completedFuture(Either.forRight(new ArrayList<>()));
        }

        @Override
        public void didChangeConfiguration(DidChangeConfigurationParams params) {}

        @Override
        public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}
    }
}
