package org.noear.solon.ai.skills.cli;

import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;

import java.time.Duration;

/**
 *
 * @author noear 2026/2/23 created
 *
 */
public class ExaMcp {
    private static final String BASE_URL = "https://mcp.exa.ai/mcp";
    private static final int TIMEOUT_MS = 30_000;

    private static McpClientProvider mcpClient;

    public static McpClientProvider getMcpClient() {
        if (mcpClient == null) {
            // 使用 STREAMABLE 适配 Exa 的 JSON-RPC over SSE 模式
            mcpClient = McpClientProvider.builder()
                    .url(BASE_URL)
                    .channel(McpChannel.STREAMABLE)
                    .timeout(Duration.ofMillis(TIMEOUT_MS))
                    .build();
        }

        return mcpClient;
    }
}