package org.noear.solon.ai.skills.cli;

import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.annotation.Param;

import java.util.HashMap;
import java.util.Map;

/**
 * Web 搜索工具 (基于 Solon MCP Client 实现)
 */
public class WebsearchTool {

    private static final int DEFAULT_NUM_RESULTS = 8;

    private static final WebsearchTool instance = new WebsearchTool();

    public static WebsearchTool getInstance() {
        return instance;
    }

    private final McpClientProvider mcpClient;

    public WebsearchTool() {
        // 使用 STREAMABLE 适配 Exa 的 text/event-stream 模式
        this.mcpClient = ExaMcp.getMcpClient();
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
        args.put("livecrawl", livecrawl != null ? livecrawl : "fallback");
        args.put("type", type != null ? type : "auto");
        args.put("contextMaxCharacters", contextMaxCharacters != null ? contextMaxCharacters : 10000);

        // 2. 通过 MCP 协议调用工具
        ToolResult result = mcpClient.callTool("web_search_exa", args);

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
                    .metadata("source", "exa.ai");
        }

        // 5. 兜底处理 (与原版文案完全对齐)
        return new Document()
                .title("Web search: " + query)
                .content("No search results found. Please try a different query.");

    }
}