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
package org.noear.solon.ai.skills.web;

import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;

import java.time.Duration;

/**
 *
 * @author noear 2026/2/23 created
 *
 */
public class ExaAiClient {
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