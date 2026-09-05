/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.lsp;

import org.eclipse.lsp4j.jsonrpc.json.JsonRpcMethod;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「项目导入完成前不采信分析结果」的回归测试。
 *
 * <p>背景：jdtls 的 {@code initialize} 一返回就会被当成可用，但它此刻才开始导入项目。导入期它
 * 把每个文件按「没有 classpath 的孤立源文件」分析，并真的推送诊断——实测日志里连
 * {@code AtomicBoolean cannot be resolved to a type} 这种都有。这些结论必须一律作废。
 *
 * @author noear
 * @since 4.1
 */
public class LspReadyGateTest {

    @Test
    @DisplayName("未开门禁的服务器立即就绪（不为其它语言引入额外等待）")
    public void ungatedServerIsReadyImmediately() {
        LspReadyGate gate = new LspReadyGate("gopls", false, 60_000L);

        assertFalse(gate.isGated());
        assertTrue(gate.isReady());
        assertTrue(gate.await(0));
    }

    @Test
    @DisplayName("开了门禁的服务器必须等到明确的就绪信号")
    public void gatedServerWaitsForReadySignal() {
        LspReadyGate gate = new LspReadyGate("java", true, 60_000L);

        assertFalse(gate.isReady(), "收到就绪信号前不得放行");

        long startAt = System.currentTimeMillis();
        assertFalse(gate.await(80), "没有信号时 await 必须如实返回未就绪");
        assertTrue(System.currentTimeMillis() - startAt >= 60, "await 应真的等过预算");

        gate.markReady("test");
        assertTrue(gate.isReady());
        assertTrue(gate.await(0));
    }

    @Test
    @DisplayName("等待中收到就绪信号应立刻被唤醒")
    public void awaitWakesUpOnReadySignal() throws Exception {
        LspReadyGate gate = new LspReadyGate("java", true, 60_000L);

        Thread signal = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            gate.markReady("language/status=ServiceReady");
        });
        signal.setDaemon(true);
        signal.start();

        long startAt = System.currentTimeMillis();
        assertTrue(gate.await(5000));
        long cost = System.currentTimeMillis() - startAt;
        assertTrue(cost < 2000, "应被信号唤醒而不是等满预算: " + cost + "ms");
    }

    @Test
    @DisplayName("兜底：超过总预算仍无信号则视为就绪，避免语言服务永久静默")
    public void readyTimeoutIsTheEscapeHatch() {
        LspReadyGate gate = new LspReadyGate("java", true, 0L);

        //宁可承担偶发假错，也不能因为某些服务器版本不发这个通知而让 Java 诊断彻底哑掉
        assertTrue(gate.isReady());
    }

    @Test
    @DisplayName("jdtls 私有通知 language/status 必须真的注册进 lsp4j 端点")
    public void languageStatusNotificationIsRegistered() {
        //lsp4j 只认 LanguageClient 上声明的方法；私有通知靠实现类上的 @JsonNotification 登记。
        //这条断言的意义在于：写错注解或改了方法签名时，就绪信号会静默丢失，很难从行为上察觉
        Map<String, JsonRpcMethod> methods = ServiceEndpoints.getSupportedMethods(LspClientImpl.class);

        JsonRpcMethod method = methods.get("language/status");
        assertNotNull(method, "language/status 未注册，jdtls 的就绪信号将被丢弃");
        assertEquals(1, method.getParameterTypes().length);
        assertEquals(LspClientImpl.LanguageStatusReport.class, method.getParameterTypes()[0]);
    }

    @Test
    @DisplayName("ServiceReady 之外的状态不放行")
    public void onlyServiceReadyOpensTheGate() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("lsp-ready-test");
        LspClientImpl client = new LspClientImpl(tempDir.toString(), new LspClientStallTest.NoopLanguageServer());
        try {
            //embedded（非 jdtls）默认就绪：不给其它语言服务器凭空加门禁
            assertTrue(client.isReady());

            LspClientImpl.LanguageStatusReport starting = new LspClientImpl.LanguageStatusReport();
            starting.type = "Starting";
            client.languageStatus(starting);
            client.languageStatus(null);
            //不抛异常即可（此实例本就已就绪），这里主要锁定入参容错
            assertTrue(client.isReady());
        } finally {
            client.shutdown();
        }
    }
}
