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
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.lsp.exception.LspEnvironmentException;
import org.noear.solon.ai.talents.lsp.exception.LspStalledException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「语言服务器不再消费输入」这一类故障的回归测试。
 *
 * <p>背景：写管道是阻塞操作。当语言服务器自己卡住（典型成因是没人读它的 stderr，
 * 它便阻塞在写日志上并停止读自己的 stdin）时，didOpen/didChange 会永久卡在 native
 * write —— 一次编辑工具调用就此再也不返回，整轮对话失去响应。这里锁定两条底线：
 *
 * <ul>
 *   <li>写入方永远有超时可退：绝不把业务线程押在一条不可自愈的写上；</li>
 *   <li>子进程的 stderr 必须被持续排空：从源头上不让对端有卡住的机会。</li>
 * </ul>
 *
 * @author noear
 * @since 4.1
 */
public class LspClientStallTest {
    /** 与 LspClientImpl.SEND_TIMEOUT_MS 的默认值一致 */
    private static final long SEND_TIMEOUT_MS = 2000L;

    private Path tempDir;
    private Path sample;
    private LspClientImpl client;

    @BeforeEach
    public void setup() throws Exception {
        tempDir = Files.createTempDirectory("lsp-stall-test");
        sample = tempDir.resolve("Sample.java");
        Files.write(sample, "class Sample {}\n".getBytes("UTF-8"));
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

    // ==================== 写入侧：超时可退 ====================

    @Test
    public void syncFile_givesUpWhenServerStopsReadingItsInput() {
        BlockingLanguageServer server = new BlockingLanguageServer();
        client = new LspClientImpl(tempDir.toString(), server);

        String uri = sample.toUri().toString();

        long startAt = System.currentTimeMillis();
        LspStalledException e = assertThrows(LspStalledException.class, () -> client.syncFile(uri, true));
        long cost = System.currentTimeMillis() - startAt;

        //必须在发送预算内退出，而不是跟着对端一起永久卡住
        assertTrue(cost >= SEND_TIMEOUT_MS, "should have waited the send budget, but returned in " + cost + "ms");
        assertTrue(cost < SEND_TIMEOUT_MS + 3000, "should not block far beyond the send budget: " + cost + "ms");
        assertTrue(e.getMessage().contains("didOpen"), "unexpected message: " + e.getMessage());
    }

    @Test
    public void afterStall_clientIsNotAliveAndFurtherSendsFailFast() {
        BlockingLanguageServer server = new BlockingLanguageServer();
        client = new LspClientImpl(tempDir.toString(), server);

        String uri = sample.toUri().toString();
        assertThrows(LspStalledException.class, () -> client.syncFile(uri, true));

        //卡死是终局判定：这个连接已经废掉，上层应据此重建而不是继续往里发消息
        assertFalse(client.isAlive(), "a stalled client must not report itself as alive");

        long startAt = System.currentTimeMillis();
        assertThrows(LspStalledException.class, () -> client.syncFile(uri, true));
        long cost = System.currentTimeMillis() - startAt;
        assertTrue(cost < 500, "a known-stalled client must fail fast, took " + cost + "ms");

        //导航类请求同样立刻失败，而不是把调用方挂在等待上
        CompletableFuture<Hover> hover = client.hover(new HoverParams());
        assertTrue(hover.isCompletedExceptionally());
    }

    @Test
    public void concurrentSync_secondCallerDoesNotQueueBehindAStalledWrite() throws Exception {
        BlockingLanguageServer server = new BlockingLanguageServer();
        server.blockOn = "didChange";
        client = new LspClientImpl(tempDir.toString(), server);

        String uri = sample.toUri().toString();
        assertEquals(1, client.syncFile(uri, false), "didOpen should pass through");

        //让一个线程卡在 didChange 上（模拟对端不再读 stdin）
        Thread stuck = new Thread(() -> {
            try {
                client.syncFile(uri, true);
            } catch (Exception ignored) {
            }
        }, "stuck-writer");
        stuck.setDaemon(true);
        stuck.start();
        assertTrue(server.entered.await(3, TimeUnit.SECONDS), "server should have received didChange");

        long startAt = System.currentTimeMillis();
        int version = LspClient.VERSION_UNSYNCED;
        try {
            version = client.syncFile(uri, true);
        } catch (LspStalledException e) {
            //前一个写入者已经把对端判定为卡死（两者的 2s 预算几乎同时到期），
            //此时快速失败与返回未同步同义：都是「不跟着一起阻塞」
        }
        long cost = System.currentTimeMillis() - startAt;

        //拿不到同步锁时返回「未同步」而不是无限排队——写文件的响应时间必须有上限
        assertEquals(LspClient.VERSION_UNSYNCED, version);
        assertTrue(cost < SEND_TIMEOUT_MS + 3000, "second caller blocked too long: " + cost + "ms");
    }

    @Test
    public void unsyncedResult_isDistinguishableFromASuccessfulVersion() throws Exception {
        NoopLanguageServer server = new NoopLanguageServer();
        client = new LspClientImpl(tempDir.toString(), server);

        int version = client.syncFile(sample.toUri().toString(), false);
        assertTrue(version > 0);
        assertNotEquals(LspClient.VERSION_UNSYNCED, version);
        assertTrue(client.isAlive());
    }

    // ==================== 根因：stderr 必须被持续排空 ====================

    /**
     * 子进程往 stderr 写满一个管道缓冲区（通常 64KB）也不能卡住。
     *
     * <p>没有常驻排空线程时，这个进程会永久阻塞在写 stderr 上（于是也永远不读 stdin），
     * 客户端只能等到 initialize 超时；有排空线程时它能顺利写完并退出，退出码与错误输出
     * 都还能被带进异常信息里。
     */
    @Test
    public void childProcessWritingLotsOfStderrDoesNotBlock() throws Exception {
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            return; //依赖 POSIX shell，Windows 下跳过
        }

        String script = "i=0; while [ $i -lt 4000 ]; do "
                + "echo \"stderr-noise-$i 0123456789012345678901234567890123456789\" >&2; "
                + "i=$((i+1)); done; exit 7";

        String previousInitTimeout = System.getProperty("lsp.initTimeout");
        System.setProperty("lsp.initTimeout", "10");
        try {
            long startAt = System.currentTimeMillis();
            LspEnvironmentException e = assertThrows(LspEnvironmentException.class,
                    () -> new LspClientImpl("stderr-flood", new String[]{"/bin/sh", "-c", script},
                            tempDir.toString(), null, null));
            long cost = System.currentTimeMillis() - startAt;

            //只要 stderr 被排空，进程就能跑完并退出；卡住的话这里会顶到 initialize 超时
            assertTrue(cost < 9000, "child seems blocked on writing stderr, took " + cost + "ms");
            assertTrue(e.getMessage().contains("stderr-noise-"),
                    "stderr tail should be captured for diagnosis: " + e.getMessage());
        } finally {
            if (previousInitTimeout == null) {
                System.clearProperty("lsp.initTimeout");
            } else {
                System.setProperty("lsp.initTimeout", previousInitTimeout);
            }
        }
    }

    // ==================== Mock ====================

    /**
     * 只在指定通知上阻塞的服务器：模拟「对端不再消费输入」
     */
    static class BlockingLanguageServer extends NoopLanguageServer {
        volatile String blockOn = "didOpen";
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicInteger openCount = new AtomicInteger();

        @Override
        public void didOpen(DidOpenTextDocumentParams params) {
            openCount.incrementAndGet();
            if ("didOpen".equals(blockOn)) {
                blockForever();
            }
        }

        @Override
        public void didChange(DidChangeTextDocumentParams params) {
            if ("didChange".equals(blockOn)) {
                blockForever();
            }
        }

        private void blockForever() {
            entered.countDown();
            try {
                //模拟卡在 native write 上：不响应中断，只能靠关管道/杀进程解除
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 什么都不做的服务器（只实现 LspClientImpl 会用到的部分）
     */
    static class NoopLanguageServer implements LanguageServer, TextDocumentService, WorkspaceService {
        final AtomicReference<String> lastCall = new AtomicReference<>();

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            return CompletableFuture.completedFuture(new InitializeResult(new ServerCapabilities()));
        }

        @Override
        public void initialized(InitializedParams params) {
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {
        }

        @Override
        public TextDocumentService getTextDocumentService() {
            return this;
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            return this;
        }

        @Override
        public void didOpen(DidOpenTextDocumentParams params) {
            lastCall.set("didOpen");
        }

        @Override
        public void didChange(DidChangeTextDocumentParams params) {
            lastCall.set("didChange");
        }

        @Override
        public void didClose(DidCloseTextDocumentParams params) {
        }

        @Override
        public void didSave(DidSaveTextDocumentParams params) {
        }

        @Override
        public void didChangeConfiguration(DidChangeConfigurationParams params) {
        }

        @Override
        public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        }
    }
}
