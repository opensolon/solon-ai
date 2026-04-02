package labs;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.WebSocketSolonAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

/**
 *
 * @author noear 2026/2/10 created
 *
 */
public class CliAcpClientTest {
    public static void main(String[] args) {
        WebSocketSolonAcpClientTransport transport = new WebSocketSolonAcpClientTransport(
                URI.create("ws://localhost:8080/acp"),
                McpJsonMapper.getDefault());

        AcpSyncClient client = AcpClient.sync(transport)
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

        try {
            // 1. 尝试直接 initialize。
            // 如果 SDK 够智能，它会发现连接没开并自动开启；
            // 如果它报错 Failed to enqueue，说明我们得用下面的“方案B”。
            AcpSchema.InitializeResponse initResp = client.initialize();

            System.out.println("✅ 初始化成功: " + initResp.agentCapabilities());

            AcpSchema.NewSessionResponse sessionResp = client.newSession(new AcpSchema.NewSessionRequest(
                    "./acp-test", Collections.emptyList()));

            System.out.println("✅ 会话已创建: " + sessionResp.sessionId());


            AcpSchema.PromptResponse promptResponse = client.prompt(new AcpSchema.PromptRequest(
                    sessionResp.sessionId(), Arrays.asList(new AcpSchema.TextContent("你好"))));

            System.out.println("🎉 交互完成: " + promptResponse.stopReason());

        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            System.out.println("🧹 正在清理连接...");
            client.close();
        }

        System.out.println("🏁 测试结束。");
    }
}