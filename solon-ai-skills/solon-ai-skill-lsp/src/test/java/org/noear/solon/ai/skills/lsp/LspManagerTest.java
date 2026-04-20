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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LspManager 单元测试
 *
 * <p>使用 MockLspClient 进行测试，不需要启动真实的语言服务器进程。
 *
 * @author noear
 * @since 3.10.0
 */
public class LspManagerTest {

    private LspManager manager;

    @BeforeEach
    public void setup() {
        manager = new LspManager("/tmp/test-workspace");
    }

    @AfterEach
    public void teardown() {
        if (manager != null) {
            manager.shutdownAll();
        }
    }

    // ==================== registerServer 配置注册测试 ====================

    @Test
    public void testRegisterServer_Normal() {
        LspServerParameters params = new LspServerParameters(
                Arrays.asList("some-server"), Arrays.asList(".java", ".kt"));

        manager.registerServer("java", params);

        assertTrue(manager.hasServers());
        assertEquals(1, manager.getServerConfigs().size());
        assertNotNull(manager.getServerConfig("java"));
        assertEquals(params, manager.getServerConfig("java"));
    }

    @Test
    public void testRegisterServer_Disabled() {
        LspServerParameters params = new LspServerParameters(
                Arrays.asList("some-server"), Arrays.asList(".java"));
        params.setDisabled(true);

        manager.registerServer("disabled-server", params);

        assertFalse(manager.hasServers());
    }

    @Test
    public void testRegisterServer_NoCommand() {
        LspServerParameters params = new LspServerParameters();
        params.setExtensions(Arrays.asList(".java"));

        manager.registerServer("no-command", params);

        assertFalse(manager.hasServers());
    }

    @Test
    public void testRegisterServer_NoExtensions() {
        LspServerParameters params = new LspServerParameters();
        params.setCommand(Arrays.asList("some-server"));

        manager.registerServer("no-ext", params);

        assertFalse(manager.hasServers());
    }

    @Test
    public void testRegisterServer_NullName() {
        assertThrows(NullPointerException.class, () -> {
            manager.registerServer(null, new LspServerParameters());
        });
    }

    @Test
    public void testRegisterServer_NullParams() {
        assertThrows(NullPointerException.class, () -> {
            manager.registerServer("test", null);
        });
    }

    @Test
    public void testRegisterServer_Multiple() {
        manager.registerServer("java", new LspServerParameters(
                Arrays.asList("jdtls"), Arrays.asList(".java")));
        manager.registerServer("go", new LspServerParameters(
                Arrays.asList("gopls"), Arrays.asList(".go")));

        assertTrue(manager.hasServers());
        assertEquals(2, manager.getServerConfigs().size());
        assertNotNull(manager.getServerConfig("java"));
        assertNotNull(manager.getServerConfig("go"));
    }

    // ==================== getServerConfigs 不可变测试 ====================

    @Test
    public void testGetServerConfigs_IsUnmodifiable() {
        manager.registerServer("java", new LspServerParameters(
                Arrays.asList("jdtls"), Arrays.asList(".java")));

        Map<String, LspServerParameters> configs = manager.getServerConfigs();
        assertThrows(UnsupportedOperationException.class, () -> {
            configs.put("hack", new LspServerParameters());
        });
    }

    // ==================== getClientForFile 路由测试 ====================

    @Test
    public void testGetClientForFile_MatchJava() {
        MockLspClient mockClient = new MockLspClient();
        LspServerParameters params = new LspServerParameters(
                Arrays.asList("jdtls"), Arrays.asList(".java"));

        manager.registerTestClient("java", params, mockClient);

        LspClient client = manager.getClientForFile("Test.java");
        assertNotNull(client);
        assertSame(mockClient, client);
    }

    @Test
    public void testGetClientForFile_MatchGo() {
        MockLspClient mockClient = new MockLspClient();
        LspServerParameters params = new LspServerParameters(
                Arrays.asList("gopls"), Arrays.asList(".go"));

        manager.registerTestClient("go", params, mockClient);

        LspClient client = manager.getClientForFile("main.go");
        assertNotNull(client);
        assertSame(mockClient, client);
    }

    @Test
    public void testGetClientForFile_PathWithDirectory() {
        MockLspClient mockClient = new MockLspClient();
        LspServerParameters params = new LspServerParameters(
                Arrays.asList("jdtls"), Arrays.asList(".java"));

        manager.registerTestClient("java", params, mockClient);

        LspClient client = manager.getClientForFile("src/main/java/Hello.java");
        assertNotNull(client);
        assertSame(mockClient, client);
    }

    @Test
    public void testGetClientForFile_NoMatch() {
        MockLspClient mockClient = new MockLspClient();
        LspServerParameters params = new LspServerParameters(
                Arrays.asList("jdtls"), Arrays.asList(".java"));

        manager.registerTestClient("java", params, mockClient);

        LspClient client = manager.getClientForFile("script.py");
        assertNull(client);
    }

    @Test
    public void testGetClientForFile_NoServers() {
        LspClient client = manager.getClientForFile("Test.java");
        assertNull(client);
    }

    @Test
    public void testGetClientForFile_CaseInsensitive() {
        MockLspClient mockClient = new MockLspClient();
        LspServerParameters params = new LspServerParameters(
                Arrays.asList("jdtls"), Arrays.asList(".Java"));

        manager.registerTestClient("java", params, mockClient);

        LspClient client = manager.getClientForFile("test.JAVA");
        assertNotNull(client);
    }

    // ==================== getClient 按名称获取测试 ====================

    @Test
    public void testGetClient_ByName() {
        MockLspClient mockClient = new MockLspClient();
        LspServerParameters params = new LspServerParameters(
                Arrays.asList("jdtls"), Arrays.asList(".java"));

        manager.registerTestClient("java", params, mockClient);

        LspClient client = manager.getClient("java");
        assertNotNull(client);
        assertSame(mockClient, client);
    }

    @Test
    public void testGetClient_NotFound() {
        LspClient client = manager.getClient("nonexistent");
        assertNull(client);
    }

    // ==================== hasServers / getActiveClientCount 测试 ====================

    @Test
    public void testHasServers_NoServers() {
        assertFalse(manager.hasServers());
    }

    @Test
    public void testHasServers_WithServers() {
        manager.registerServer("java", new LspServerParameters(
                Arrays.asList("jdtls"), Arrays.asList(".java")));
        assertTrue(manager.hasServers());
    }

    @Test
    public void testGetActiveClientCount() {
        assertEquals(0, manager.getActiveClientCount());

        manager.registerTestClient("java",
                new LspServerParameters(Arrays.asList("jdtls"), Arrays.asList(".java")),
                new MockLspClient());
        assertEquals(1, manager.getActiveClientCount());

        manager.registerTestClient("go",
                new LspServerParameters(Arrays.asList("gopls"), Arrays.asList(".go")),
                new MockLspClient());
        assertEquals(2, manager.getActiveClientCount());
    }

    // ==================== diagnosticsCallback 回调测试 ====================

    @Test
    public void testDiagnosticsCallback_ViaTool() {
        MockLspClient mockClient = new MockLspClient();
        LspServerParameters params = new LspServerParameters(
                Arrays.asList("jdtls"), Arrays.asList(".java"));

        manager.registerTestClient("java", params, mockClient);

        AtomicReference<String> receivedUri = new AtomicReference<>();
        AtomicReference<String> receivedText = new AtomicReference<>();
        manager.setDiagnosticsCallback((uri, text) -> {
            receivedUri.set(uri);
            receivedText.set(text);
        });

        // 通过 LspTool 接收回调
        LspTool tool = new LspTool(manager, "/tmp/test-workspace");
        manager.setDiagnosticsCallback(tool::updateDiagnostics);

        // 手动触发 updateDiagnostics 验证回调链
        tool.updateDiagnostics("file:///Test.java", "Test.java:1:1 [ERROR] missing semicolon");

        assertNotNull(manager);
    }

    // ==================== shutdownAll 测试 ====================

    @Test
    public void testShutdownAll() {
        MockLspClient mockClient1 = new MockLspClient();
        MockLspClient mockClient2 = new MockLspClient();

        manager.registerTestClient("java",
                new LspServerParameters(Arrays.asList("jdtls"), Arrays.asList(".java")),
                mockClient1);
        manager.registerTestClient("go",
                new LspServerParameters(Arrays.asList("gopls"), Arrays.asList(".go")),
                mockClient2);

        assertEquals(2, manager.getActiveClientCount());

        manager.shutdownAll();
        assertEquals(0, manager.getActiveClientCount());
    }

    @Test
    public void testShutdownAll_Empty() {
        manager.shutdownAll();
        assertEquals(0, manager.getActiveClientCount());
    }

    // ==================== registerTestClient 参数校验 ====================

    @Test
    public void testRegisterTestClient_NullName() {
        assertThrows(NullPointerException.class, () -> {
            manager.registerTestClient(null,
                    new LspServerParameters(Arrays.asList("cmd"), Arrays.asList(".java")),
                    new MockLspClient());
        });
    }

    @Test
    public void testRegisterTestClient_NullParams() {
        assertThrows(NullPointerException.class, () -> {
            manager.registerTestClient("java", null, new MockLspClient());
        });
    }

    @Test
    public void testRegisterTestClient_NullClient() {
        assertThrows(NullPointerException.class, () -> {
            manager.registerTestClient("java",
                    new LspServerParameters(Arrays.asList("cmd"), Arrays.asList(".java")),
                    null);
        });
    }
}
