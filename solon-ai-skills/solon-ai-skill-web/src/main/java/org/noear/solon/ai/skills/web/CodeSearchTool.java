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

import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.util.RetryUtil;
import org.noear.solon.annotation.Param;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CodeSearchTool
 *
 * @author noear
 * @since 3.9.6
 */
public class CodeSearchTool extends AbsToolProvider {
    private static final String BASE_URL = "https://mcp.exa.ai/mcp?tools=get_code_context_exa";
    private static final int TIMEOUT_MS = 30_000;
    private static final int DEFAULT_TOKENS = 5000;

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

    //---------

    private static CodeSearchTool instance = new CodeSearchTool();

    public static CodeSearchTool getInstance() {
        return instance;
    }

    private int maxRetries = 3;
    private long retryDelayMs = 1000L;

    public CodeSearchTool() {
        super();
        getMcpClient();
    }

    public CodeSearchTool retryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(500, retryDelayMs);
        return this;
    }

    public CodeSearchTool retryConfig(int maxRetries) {
        this.maxRetries = Math.max(1, maxRetries);
        return this;
    }

    @ToolMapping(name = "codesearch", description =
            "使用 Exa Code API 搜索并获取任何编程任务的相关上下文\n" +
                    "- 为库、SDK 和 API 提供最高质量且最实时的上下文信息\n" +
                    "- 适用于任何与编程相关的疑问或任务\n" +
                    "- 返回详尽的代码示例、技术文档和 API 参考\n" +
                    "- 针对寻找特定编程模式和解决方案进行了优化\n\n" +
                    "使用说明：\n" +
                    "- 可调节 Token 数量 (1000-50000) 以获得精确或详尽的结果\n" +
                    "- 默认 5000 Token 为大多数查询提供均衡的上下文\n" +
                    "- 支持关于框架、库、API 以及编程概念的查询\n" +
                    "- 示例：'React 状态管理'、'Spring Boot 响应式编程'、'Solon 插件开发'")
    public Object handle(@Param(name = "query", description = "搜索查询词，用于查找 API、库和 SDK 的相关上下文。 " +
                                 "例如：'React useState 钩子示例'、'Python pandas 数据框过滤'、" +
                                 "'Express.js 中间件'、'Next.js 局部预渲染配置'")
                         String query,
                         @Param(name = "tokensNum", required = false, defaultValue = "5000", description = "返回的 Token 数量 (1000-50000)。默认为 5000。 " +
                                 "根据需要的上下文量进行调整：针对特定问题使用较低值，针对全面文档使用较高值。")
                         Integer tokensNumObj) throws Throwable {
        Integer tokensNum = null;
        if (tokensNumObj instanceof Number) {
            tokensNum = ((Number) tokensNumObj).intValue();
        }
        int finalTokens = (tokensNum == null) ? DEFAULT_TOKENS : tokensNum;


        Map<String, Object> toolArgs = new HashMap<>();
        toolArgs.put("query", query);
        toolArgs.put("tokensNum", finalTokens);

        ToolResult result;
        try {
            // 工具名: get_code_context_exa
            result = RetryUtil.callWithRetry(maxRetries, retryDelayMs, () ->
                    getMcpClient().callTool("get_code_context_exa", toolArgs));
        } catch (Throwable e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout")) {
                throw new RuntimeException("代码搜索请求超时");
            }
            throw e;
        }

        if (result.isError()) {
            String errorText = Utils.isNotEmpty(result.getContent()) ? result.getContent() : "Unknown error";
            throw new RuntimeException("代码搜索出错: " + errorText);
        }

        String title = "Code search: " + query;
        Map<String, Object> response = new LinkedHashMap<>();

        if (Utils.isNotEmpty(result.getContent())) {
            response.put("output", result.getContent());
            response.put("title", title);
            response.put("metadata", new HashMap<>()); // 成功时 metadata 为空
        } else {
            String fallback = "未找到相关的代码片段或文档。请尝试更换查询词，" +
                    "明确具体的库或编程概念，并检查框架名称拼写是否正确。";
            response.put("output", fallback);
            response.put("title", title);
            response.put("metadata", new HashMap<>());
        }

        return response;
    }
}