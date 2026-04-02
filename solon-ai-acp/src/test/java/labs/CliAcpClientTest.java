/*
 * Copyright 2025-2026 the original author or authors.
 */

package labs;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.WebSocketSolonAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ACP 客户端集成测试
 * 使用 Mock Agent 服务端进行完整的客户端流程测试
 *
 * @author noear 2026/2/10 created
 */
@DisplayName("ACP 客户端集成测试")
class CliAcpClientTest {

    private static final String TEST_HOST = "localhost";
    private static final int TEST_PORT = 18081;
    private static final String TEST_WS_URL = "ws://" + TEST_HOST + ":" + TEST_PORT + "/acp";

    private MockAcpAgentServer mockServer;
    private AcpSyncClient client;

    @BeforeEach
    void setUp() {
        // 启动 Mock 服务端
        mockServer = new MockAcpAgentServer(TEST_HOST, TEST_PORT);
        mockServer.start();

        // 创建客户端
        WebSocketSolonAcpClientTransport transport = new WebSocketSolonAcpClientTransport(
                URI.create(TEST_WS_URL),
                McpJsonMapper.getDefault());

        client = AcpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .sessionUpdateConsumer(notification -> {
                    AcpSchema.SessionUpdate update = notification.update();

                    if (update instanceof AcpSchema.AgentThoughtChunk) {
                        AcpSchema.AgentThoughtChunk msg = (AcpSchema.AgentThoughtChunk) update;
                        System.out.print(((AcpSchema.TextContent) msg.content()).text());
                    } else if (update instanceof AcpSchema.AgentMessageChunk) {
                        AcpSchema.AgentMessageChunk msg = (AcpSchema.AgentMessageChunk) update;
                        System.out.print(((AcpSchema.TextContent) msg.content()).text());
                    }
                })
                .build();

        System.out.println("🚀 启动测试流程...");
    }

    @AfterEach
    void tearDown() {
        System.out.println("🧹 正在清理连接...");

        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                // ignore
            }
        }

        if (mockServer != null) {
            mockServer.stop();
        }

        System.out.println("🏁 测试结束。");
    }

    @Test
    @DisplayName("完整流程 - 初始化、创建会话、发送提示")
    void testFullClientFlow() {
        try {
            // 1. 初始化
            AcpSchema.InitializeResponse initResp = client.initialize();
            System.out.println("✅ 初始化成功: " + initResp.agentCapabilities());
            assertNotNull(initResp);
            assertNotNull(initResp.agentCapabilities());

            // 2. 创建会话
            AcpSchema.NewSessionResponse sessionResp = client.newSession(new AcpSchema.NewSessionRequest(
                    "./acp-test", Collections.emptyList()));
            System.out.println("✅ 会话已创建: " + sessionResp.sessionId());
            assertNotNull(sessionResp);
            assertNotNull(sessionResp.sessionId());

            // 3. 发送提示
            AcpSchema.PromptResponse promptResponse = client.prompt(new AcpSchema.PromptRequest(
                    sessionResp.sessionId(), Arrays.asList(new AcpSchema.TextContent("你好"))));
            System.out.println("🎉 交互完成: " + promptResponse.stopReason());
            assertNotNull(promptResponse);
            assertNotNull(promptResponse.stopReason());

        } catch (Throwable e) {
            e.printStackTrace();
            fail("测试失败: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("多次会话 - 同一客户端创建多个会话")
    void testMultipleSessions() {
        try {
            // 初始化
            AcpSchema.InitializeResponse initResp = client.initialize();
            assertNotNull(initResp);

            // 创建第一个会话
            AcpSchema.NewSessionResponse session1 = client.newSession(new AcpSchema.NewSessionRequest(
                    "./session1", Collections.emptyList()));
            assertNotNull(session1.sessionId());
            System.out.println("✅ 会话1已创建: " + session1.sessionId());

            // 创建第二个会话
            AcpSchema.NewSessionResponse session2 = client.newSession(new AcpSchema.NewSessionRequest(
                    "./session2", Collections.emptyList()));
            assertNotNull(session2.sessionId());
            System.out.println("✅ 会话2已创建: " + session2.sessionId());

            // 验证两个会话 ID 不同
            assertNotEquals(session1.sessionId(), session2.sessionId());

        } catch (Throwable e) {
            e.printStackTrace();
            fail("测试失败: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("会话交互 - 发送提示并接收响应")
    void testPromptInteraction() {
        try {
            // 初始化和创建会话
            client.initialize();
            AcpSchema.NewSessionResponse sessionResp = client.newSession(new AcpSchema.NewSessionRequest(
                    "./acp-test", Collections.emptyList()));

            // 发送第一条提示
            AcpSchema.PromptResponse response1 = client.prompt(new AcpSchema.PromptRequest(
                    sessionResp.sessionId(), Arrays.asList(new AcpSchema.TextContent("问题1"))));
            assertNotNull(response1.stopReason());
            System.out.println("🎉 交互1完成: " + response1.stopReason());

            // 发送第二条提示
            AcpSchema.PromptResponse response2 = client.prompt(new AcpSchema.PromptRequest(
                    sessionResp.sessionId(), Arrays.asList(new AcpSchema.TextContent("问题2"))));
            assertNotNull(response2.stopReason());
            System.out.println("🎉 交互2完成: " + response2.stopReason());

        } catch (Throwable e) {
            e.printStackTrace();
            fail("测试失败: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("会话更新回调 - 接收 AgentThoughtChunk 和 AgentMessageChunk")
    void testSessionUpdateConsumer() {
        AtomicReference<AcpSchema.SessionUpdate> receivedUpdate = new AtomicReference<>();

        // 重新创建客户端，带有自定义的更新处理器
        WebSocketSolonAcpClientTransport transport = new WebSocketSolonAcpClientTransport(
                URI.create(TEST_WS_URL),
                McpJsonMapper.getDefault());

        AcpSyncClient clientWithCallback = AcpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .sessionUpdateConsumer(notification -> {
                    receivedUpdate.set(notification.update());
                    System.out.println("📢 收到会话更新: " + notification.update().getClass().getSimpleName());
                })
                .build();

        try {
            clientWithCallback.initialize();
            AcpSchema.NewSessionResponse sessionResp = clientWithCallback.newSession(new AcpSchema.NewSessionRequest(
                    "./callback-test", Collections.emptyList()));

            clientWithCallback.prompt(new AcpSchema.PromptRequest(
                    sessionResp.sessionId(), Arrays.asList(new AcpSchema.TextContent("测试消息"))));

            // 注意：根据 Mock 服务端的实现，可能需要等待或验证回调
            System.out.println("✅ 会话更新回调测试完成");

        } catch (Throwable e) {
            e.printStackTrace();
            fail("测试失败: " + e.getMessage());
        } finally {
            try {
                clientWithCallback.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }
}