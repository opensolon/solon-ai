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
package org.noear.solon.ai.talents.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

        // 走测试专用构造：不启动子进程，直接注入 MockLanguageServer
        mockServer = new MockLanguageServer();
        client = new LspClientImpl(tempDir.toString(), mockServer);
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

    // ==================== publishDiagnostics 测试（结构化） ====================

    @Test
    public void testPublishDiagnostics_SingleError() {
        AtomicReference<String> receivedUri = new AtomicReference<>();
        AtomicReference<List<Diagnostic>> receivedItems = new AtomicReference<>();

        client.setDiagnosticsConsumer((uri, items) -> {
            receivedUri.set(uri);
            receivedItems.set(items);
        });

        Diagnostic diag = new Diagnostic(
                new Range(new Position(2, 5), new Position(2, 10)),
                "missing semicolon"
        );
        diag.setSeverity(DiagnosticSeverity.Error);
        diag.setSource("javac");

        client.publishDiagnostics(new PublishDiagnosticsParams(
                "file:///Test.java", Collections.singletonList(diag)));

        assertEquals("file:///Test.java", receivedUri.get());
        assertEquals(1, receivedItems.get().size());
        assertEquals("missing semicolon", receivedItems.get().get(0).getMessage());
        //严重度不再被压成文本，保留给渲染层过滤
        assertEquals(DiagnosticSeverity.Error, receivedItems.get().get(0).getSeverity());
        //客户端自身也缓存一份，供 waitForDiagnostics/getDiagnostics 读取
        assertEquals(1, client.getDiagnostics("file:///Test.java").size());
        //渲染由 LspDiagnosticReporter 负责：1-based 行列，无 uri 前缀
        assertEquals("ERROR [3:6] missing semicolon (javac)",
                LspDiagnosticReporter.renderItem(receivedItems.get().get(0)));
    }

    @Test
    public void testPublishDiagnostics_EmptyOrNull() {
        AtomicReference<List<Diagnostic>> receivedItems = new AtomicReference<>();
        client.setDiagnosticsConsumer((uri, items) -> receivedItems.set(items));

        //lsp4j 的双参构造禁止 null，用无参构造模拟"服务器未给 diagnostics 字段"
        PublishDiagnosticsParams bare = new PublishDiagnosticsParams();
        bare.setUri("file:///Test.java");
        client.publishDiagnostics(bare);
        assertNotNull(receivedItems.get());
        assertTrue(receivedItems.get().isEmpty());
        assertTrue(client.getDiagnostics("file:///Test.java").isEmpty());

        client.publishDiagnostics(new PublishDiagnosticsParams("file:///Test.java", Collections.emptyList()));
        assertTrue(receivedItems.get().isEmpty());
    }

    @Test
    public void testPublishDiagnostics_MixedSeverity_OnlyErrorsRendered() {
        AtomicReference<List<Diagnostic>> receivedItems = new AtomicReference<>();
        client.setDiagnosticsConsumer((uri, items) -> receivedItems.set(items));

        List<Diagnostic> diags = new ArrayList<>();
        diags.add(newDiag(DiagnosticSeverity.Error, "error1", 0, 0));
        diags.add(newDiag(DiagnosticSeverity.Warning, "warning1", 2, 3));
        diags.add(newDiag(DiagnosticSeverity.Information, "info1", 5, 1));
        diags.add(newDiag(DiagnosticSeverity.Hint, "hint1", 10, 0));

        client.publishDiagnostics(new PublishDiagnosticsParams("file:///Test.java", diags));

        //回调拿到全量（保留原始严重度）
        assertEquals(4, receivedItems.get().size());
        //渲染只留 ERROR
        String block = LspDiagnosticReporter.renderBlock("Test.java", receivedItems.get());
        assertTrue(block.contains("error1"));
        assertFalse(block.contains("warning1"));
        assertFalse(block.contains("info1"));
        assertFalse(block.contains("hint1"));
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
        assertEquals(1, client.getDiagnostics("file:///Test.java").size());
    }

    // ==================== setDiagnosticsConsumer 测试 ====================

    @Test
    public void testSetDiagnosticsConsumer() {
        AtomicBoolean called = new AtomicBoolean(false);
        client.setDiagnosticsConsumer((uri, items) -> called.set(true));

        client.publishDiagnostics(new PublishDiagnosticsParams(
                "file:///Test.java", Collections.emptyList()));

        assertTrue(called.get());
    }

    // ==================== syncFile / touchFile 测试 ====================

    @Test
    public void testSyncFile_FirstTimeOpens() throws Exception {
        Path testFile = tempDir.resolve("Demo.java");
        Files.write(testFile, "public class Demo {}".getBytes());

        String uri = testFile.toUri().toString();
        mockServer.textDocumentService.didOpenCalled = false;

        int version = client.syncFile(uri, false);

        assertEquals(1, version);
        assertTrue(mockServer.textDocumentService.didOpenCalled);
        assertFalse(mockServer.textDocumentService.didChangeCalled);
    }

    @Test
    public void testSyncFile_ContentUnchanged_NoDidChange() throws Exception {
        Path testFile = tempDir.resolve("Demo.java");
        Files.write(testFile, "public class Demo {}".getBytes());
        String uri = testFile.toUri().toString();

        client.touchFile(uri);
        mockServer.textDocumentService.didOpenCalled = false;
        mockServer.textDocumentService.didChangeCalled = false;

        client.touchFile(uri);

        //内容没变：既不重复 didOpen，也不空发 didChange
        assertFalse(mockServer.textDocumentService.didOpenCalled);
        assertFalse(mockServer.textDocumentService.didChangeCalled);
    }

    @Test
    public void testSyncFile_ContentChanged_SendsDidChangeWithNewVersion() throws Exception {
        Path testFile = tempDir.resolve("Demo.java");
        Files.write(testFile, "public class Demo {}".getBytes());
        String uri = testFile.toUri().toString();

        assertEquals(1, client.syncFile(uri, false));

        //模拟 write/edit 改盘
        Files.write(testFile, "public class Demo { int x = ; }".getBytes());

        int version = client.syncFile(uri, false);

        assertEquals(2, version);
        assertTrue(mockServer.textDocumentService.didChangeCalled);
        assertEquals(2, mockServer.textDocumentService.lastDidChangeVersion);
        assertTrue(mockServer.textDocumentService.lastDidChangeText.contains("int x = ;"));
        //文档已 didOpen，内容真相归客户端所有，变更只能走 didChange：
        //jdtls 之类服务器收到 watched-files 会自行重读磁盘，与 didChange 形成双重应用而撕裂文本
        assertFalse(mockServer.workspaceService.didChangeWatchedFilesCalled);
    }

    @Test
    public void testSyncFile_ConcurrentCallsApplyChangeOnce() throws Exception {
        Path testFile = tempDir.resolve("Demo.java");
        Files.write(testFile, "public class Demo {}".getBytes());
        String uri = testFile.toUri().toString();

        client.syncFile(uri, false); // didOpen, version=1

        //模拟 write/edit 改盘后，钩子与文件监听等多个入口同时触发同步
        Files.write(testFile, "public class Demo { int x = ; }".getBytes());
        mockServer.textDocumentService.didChangeCount = 0;

        int threads = 4;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    client.syncFile(uri, false);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));

        //只能发一次：重复应用同一份基于旧文本的增量范围会把服务器端文本错位
        assertEquals(1, mockServer.textDocumentService.didChangeCount);
        assertEquals(2, mockServer.textDocumentService.lastDidChangeVersion);
    }

    @Test
    public void testSyncFile_ForceChange() throws Exception {
        Path testFile = tempDir.resolve("Demo.java");
        Files.write(testFile, "public class Demo {}".getBytes());
        String uri = testFile.toUri().toString();

        client.syncFile(uri, false);
        mockServer.textDocumentService.didChangeCalled = false;

        //内容未变但强制同步（写入钩子用这条路径，确保服务器不会拿旧文本回答）
        int version = client.syncFile(uri, true);

        assertEquals(2, version);
        assertTrue(mockServer.textDocumentService.didChangeCalled);
    }

    @Test
    public void testSyncFile_NonexistentFile() {
        String uri = tempDir.resolve("NotExist.java").toUri().toString();
        assertDoesNotThrow(() -> client.touchFile(uri));
        assertTrue(mockServer.textDocumentService.didOpenCalled);
    }

    // ==================== waitForDiagnostics 测试 ====================

    @Test
    public void testWaitForDiagnostics_NeverSynced() {
        assertTrue(client.waitForDiagnostics("file:///Nope.java", 50).isEmpty());
    }

    @Test
    public void testWaitForDiagnostics_ReturnsFreshPush() throws Exception {
        Path testFile = tempDir.resolve("Demo.java");
        Files.write(testFile, "public class Demo {}".getBytes());
        String uri = testFile.toUri().toString();

        client.syncFile(uri, false);

        //在等待期间异步推送诊断
        new Thread(() -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {
            }
            client.publishDiagnostics(new PublishDiagnosticsParams(uri,
                    Collections.singletonList(newDiag(DiagnosticSeverity.Error, "late error", 0, 0))));
        }).start();

        List<Diagnostic> items = client.waitForDiagnostics(uri, 3000);

        assertEquals(1, items.size());
        assertEquals("late error", items.get(0).getMessage());
    }

    @Test
    public void testWaitForDiagnostics_StalePushIgnoredUntilTimeout() throws Exception {
        Path testFile = tempDir.resolve("Demo.java");
        Files.write(testFile, "public class Demo {}".getBytes());
        String uri = testFile.toUri().toString();

        //旧诊断：早于本次同步
        client.publishDiagnostics(new PublishDiagnosticsParams(uri,
                Collections.singletonList(newDiag(DiagnosticSeverity.Error, "stale error", 0, 0))));

        Thread.sleep(5);
        Files.write(testFile, "public class Demo { }".getBytes());
        client.syncFile(uri, false);

        long begin = System.currentTimeMillis();
        List<Diagnostic> items = client.waitForDiagnostics(uri, 120);
        long cost = System.currentTimeMillis() - begin;

        //超时后退回已有诊断（宁可给旧的，也不阻塞写入返回）
        assertEquals(1, items.size());
        assertTrue(cost >= 60, "should have waited for a fresh push, cost=" + cost);
    }

    @Test
    public void testWaitForDiagnostics_VersionMismatchRejected() throws Exception {
        Path testFile = tempDir.resolve("Demo.java");
        Files.write(testFile, "public class Demo {}".getBytes());
        String uri = testFile.toUri().toString();

        client.syncFile(uri, false); // version = 1

        PublishDiagnosticsParams stale = new PublishDiagnosticsParams(uri,
                Collections.singletonList(newDiag(DiagnosticSeverity.Error, "v0 error", 0, 0)));
        stale.setVersion(0); //服务器明确标注这是旧版本的诊断
        client.publishDiagnostics(stale);

        long begin = System.currentTimeMillis();
        client.waitForDiagnostics(uri, 120);
        long cost = System.currentTimeMillis() - begin;

        assertTrue(cost >= 60, "version mismatch should not be treated as fresh, cost=" + cost);
    }

    // ==================== waitForDiagnosticsResult 测试（确认态 vs 超时态）====================

    @Test
    public void testWaitForDiagnosticsResult_NeverSyncedIsUnconfirmed() {
        LspDiagnosticsResult r = client.waitForDiagnosticsResult("file:///Nope.java", 50);
        assertFalse(r.isConfirmed(), "从未同步过的文件不能给出结论");
        assertTrue(r.getItems().isEmpty());
    }

    @Test
    public void testWaitForDiagnosticsResult_EmptyPushIsConfirmedClean() throws Exception {
        Path testFile = tempDir.resolve("Demo.java");
        Files.write(testFile, "public class Demo {}".getBytes());
        String uri = testFile.toUri().toString();

        client.syncFile(uri, false);

        //服务器明确推送空诊断：这才是「检查过、没有错误」
        new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, Collections.emptyList()));
        }).start();

        LspDiagnosticsResult r = client.waitForDiagnosticsResult(uri, 3000);

        assertTrue(r.isConfirmed(), "收到本轮推送即为已确认");
        assertTrue(r.getItems().isEmpty());
    }

    @Test
    public void testWaitForDiagnosticsResult_TimeoutIsUnconfirmed() throws Exception {
        Path testFile = tempDir.resolve("Demo.java");
        Files.write(testFile, "public class Demo {}".getBytes());
        String uri = testFile.toUri().toString();

        client.syncFile(uri, false);

        //冷启动场景：等待预算内一条推送都没来，空列表不等于无错误
        LspDiagnosticsResult r = client.waitForDiagnosticsResult(uri, 80);

        assertFalse(r.isConfirmed(), "超时未收到推送不能断言无错误");
        assertTrue(r.getItems().isEmpty());
    }

    private static Diagnostic newDiag(DiagnosticSeverity severity, String message, int line, int character) {
        Diagnostic d = new Diagnostic(
                new Range(new Position(line, character), new Position(line, character + 1)), message);
        d.setSeverity(severity);
        return d;
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
        //请求的发送已下沉到发送线程（写管道不能阻塞业务线程），因此要等 future 完成后再断言
        result.join();
        assertTrue(mockServer.textDocumentService.definitionCalled);
    }

    @Test
    public void testReferences() {
        ReferenceParams params = new ReferenceParams(
                new TextDocumentIdentifier("file:///Test.java"), new Position(0, 5), new ReferenceContext()
        );
        CompletableFuture<List<? extends Location>> result = client.references(params);

        assertNotNull(result);
        //请求的发送已下沉到发送线程（写管道不能阻塞业务线程），因此要等 future 完成后再断言
        result.join();
        assertTrue(mockServer.textDocumentService.referencesCalled);
    }

    @Test
    public void testHover() {
        HoverParams params = new HoverParams(
                new TextDocumentIdentifier("file:///Test.java"), new Position(0, 5)
        );
        CompletableFuture<Hover> result = client.hover(params);

        assertNotNull(result);
        //请求的发送已下沉到发送线程（写管道不能阻塞业务线程），因此要等 future 完成后再断言
        result.join();
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
        //请求的发送已下沉到发送线程（写管道不能阻塞业务线程），因此要等 future 完成后再断言
        result.join();
        assertTrue(mockServer.textDocumentService.documentSymbolCalled);
    }

    @Test
    public void testWorkspaceSymbol() {
        WorkspaceSymbolParams params = new WorkspaceSymbolParams("test");
        CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> result =
                client.workspaceSymbol(params);

        assertNotNull(result);
        //请求的发送已下沉到发送线程（写管道不能阻塞业务线程），因此要等 future 完成后再断言
        result.join();
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
        //请求的发送已下沉到发送线程（写管道不能阻塞业务线程），因此要等 future 完成后再断言
        result.join();
        assertTrue(mockServer.textDocumentService.implementationCalled);
    }

    @Test
    public void testPrepareCallHierarchy() {
        CallHierarchyPrepareParams params = new CallHierarchyPrepareParams(
                new TextDocumentIdentifier("file:///Test.java"), new Position(0, 5)
        );
        CompletableFuture<List<CallHierarchyItem>> result = client.prepareCallHierarchy(params);

        assertNotNull(result);
        //请求的发送已下沉到发送线程（写管道不能阻塞业务线程），因此要等 future 完成后再断言
        result.join();
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
        boolean didChangeCalled;
        volatile int didChangeCount;
        int lastDidChangeVersion;
        String lastDidChangeText;
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
        public synchronized void didChange(DidChangeTextDocumentParams params) {
            didChangeCalled = true;
            didChangeCount++;
            lastDidChangeVersion = params.getTextDocument().getVersion();
            lastDidChangeText = params.getContentChanges().get(0).getText();
        }

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
        boolean didChangeWatchedFilesCalled;

        @Override
        public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
            symbolCalled = true;
            return CompletableFuture.completedFuture(Either.forRight(new ArrayList<>()));
        }

        @Override
        public void didChangeConfiguration(DidChangeConfigurationParams params) {}

        @Override
        public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
            didChangeWatchedFilesCalled = true;
        }
    }
}
