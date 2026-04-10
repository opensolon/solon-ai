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
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.annotation.Param;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Web 搜索工具 (基于 Solon MCP Client 实现)
 *
 * @author noear
 * @since 3.9.6
 */
public class WebsearchTool {
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

    //---------

    private static final int DEFAULT_NUM_RESULTS = 8;
    private static final int DEFAULT_CONTEXT_CHARS = 10000;
    private static final String DEFAULT_LIVECRAWL = "fallback";
    private static final String DEFAULT_TYPE = "auto";

    private static final WebsearchTool instance = new WebsearchTool();

    public static WebsearchTool getInstance() {
        return instance;
    }


    @ToolMapping(name = "websearch", description = "执行实时web搜索")
    public Document websearch(
            @Param(name = "query", description = "查询关键字") String query,
            @Param(name = "numResults", required = false, defaultValue = "8", description = "返回的结果数量") Integer numResults,
            @Param(name = "livecrawl", required = false, defaultValue = "fallback", description = "实时爬行模式 (fallback/preferred)") String livecrawl,
            @Param(name = "type", required = false, defaultValue = "auto", description = "搜索类型 (auto/fast/deep)") String type,
            @Param(name = "contextMaxCharacters", required = false, defaultValue = "10000", description = "针对LLM优化的最大字符数") Integer contextMaxCharacters
    ) throws Exception {
        // 1. 准备参数 (保持不变)
        Map<String, Object> args = new HashMap<>();
        args.put("query", query);
        args.put("numResults", numResults != null ? numResults : DEFAULT_NUM_RESULTS);
        args.put("livecrawl", livecrawl != null ? livecrawl : DEFAULT_LIVECRAWL);
        args.put("type", type != null ? type : DEFAULT_TYPE);
        args.put("contextMaxCharacters", contextMaxCharacters != null ? contextMaxCharacters : DEFAULT_CONTEXT_CHARS);

        // 2. 通过 MCP 协议调用工具
        ToolResult result;

        try {
            result = mcpClient.callTool("web_search_exa", args);
        } catch (Exception e) {
            // 如果是超时相关的异常，转换为与 opencode 一致的文案
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout")) {
                throw new RuntimeException("Search request timed out");
            }
            throw e;
        }

        // 3. 处理异常反馈
        if (result.isError()) {
            // 对齐原版逻辑：优先获取服务返回的错误文本
            String errorMsg = Utils.isNotEmpty(result.getContent()) ? result.getContent() : "Search service error";
            throw new RuntimeException(errorMsg);
        }

        // 4. 解析内容
        if (Utils.isNotEmpty(result.getContent())) {
            return new Document()
                    .title("Web search: " + query)
                    .content(result.getContent())
                    .metadata("query", query)
                    .metadata("type", type) // 增加搜索类型溯源
                    .metadata("source", "exa.ai");
        }

        // 5. 兜底处理 (与原版文案完全对齐)
        return new Document()
                .title("Web search: " + query)
                .content("No search results found. Please try a different query.");

    }
}