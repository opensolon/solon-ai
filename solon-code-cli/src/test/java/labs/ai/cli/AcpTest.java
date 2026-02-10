package labs.ai.cli;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.sun.tools.javac.util.List;
import io.modelcontextprotocol.json.McpJsonMapper;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;

/**
 *
 * @author noear 2026/2/10 created
 *
 */
public class AcpTest {
    public static void main(String[] args) {
        WebSocketAcpClientTransport transport = new WebSocketAcpClientTransport(
                URI.create("ws://localhost:8080/acp"),
                McpJsonMapper.getDefault());

        AcpAsyncClient client = AcpClient.async(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .build();

        System.out.println("🚀 启动测试流程...");

        try {
            // 1. 尝试直接 initialize。
            // 如果 SDK 够智能，它会发现连接没开并自动开启；
            // 如果它报错 Failed to enqueue，说明我们得用下面的“方案B”。
            AcpSchema.InitializeResponse initResp = client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()))
                    // 注意：不要在 then 里放 close，我们手动在外面 block 完再关
                    .block(Duration.ofMinutes(2));

            System.out.println("✅ 初始化成功: " + initResp.agentCapabilities());

            AcpSchema.NewSessionResponse sessionResp = client.newSession(new AcpSchema.NewSessionRequest("./acp-test", java.util.Collections.emptyList()))
                    .block(Duration.ofMinutes(2));

            System.out.println("✅ 会话已创建: " + sessionResp.sessionId());

            client.prompt(new AcpSchema.PromptRequest(sessionResp.sessionId(), Arrays.asList(new AcpSchema.TextContent("你好"))))
                    .doOnNext(resp -> {
                        System.out.println("🎉 交互完成: " + resp.stopReason());
                    })
                    .doOnError(e -> System.err.println("❌ 链路失败: " + e.getMessage()))
                    .block(Duration.ofMinutes(2));

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.out.println("🧹 正在清理连接...");
            client.closeGracefully().block();
        }

        System.out.println("🏁 测试结束。");
    }
}