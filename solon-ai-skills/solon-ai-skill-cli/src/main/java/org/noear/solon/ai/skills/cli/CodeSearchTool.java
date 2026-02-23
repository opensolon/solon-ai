package org.noear.solon.ai.skills.cli;

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
 * CodeSearchTool - 对齐 OpenCode 逻辑
 * 使用 Exa Code API 搜索编程任务相关的上下文、库和 SDK 文档。
 */
public class CodeSearchTool {
    private static final int DEFAULT_TOKENS = 5000;

    private static final CodeSearchTool instance = new CodeSearchTool();

    public static CodeSearchTool getInstance() {
        return instance;
    }

    private final McpClientProvider mcpClient;

    public CodeSearchTool() {
        // 使用 STREAMABLE 适配 Exa 的 JSON-RPC over SSE 模式
        this.mcpClient = ExaMcp.getMcpClient();
    }

    @ToolMapping(name = "codesearch", description = "搜索并获取任何编程任务的相关上下文。" +
            "为库、SDK 和 API 提供高质量、新鲜的上下文。适用于任何与编程相关的问题。" +
            "返回全面的代码示例、文档和 API 参考。优化用于查找特定编程模式和解决方案。"
    )
    public Document codesearch(
            @Param(name = "query", description = "查找 API、库和 SDK 相关上下文的搜索查询。例如：'React useState hook examples', 'Python pandas dataframe filtering'") String query,
            @Param(name = "tokensNum", required = false, defaultValue = "5000", description = "返回的 Token 数量 (1000-50000)。默认 5000。针对具体问题使用较小值，针对全面文档使用较大值。") Integer tokensNum
    ) throws Exception {

        // 1. 参数校验与准备 (对齐参数范围约束)
        int finalTokens = (tokensNum == null) ? DEFAULT_TOKENS : Math.max(1000, Math.min(tokensNum, 50000));

        Map<String, Object> args = new HashMap<>();
        args.put("query", query);
        args.put("tokensNum", finalTokens);

        // 2. 通过 MCP 调用 get_code_context_exa 工具 (对齐 OpenCode 调用的工具名)
        ToolResult result;
        try {
            result = mcpClient.callTool("get_code_context_exa", args);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                throw new RuntimeException("Code search request timed out");
            }
            throw e;
        }

        // 3. 处理错误反馈
        if (result.isError()) {
            String errorMsg = Utils.isNotEmpty(result.getContent()) ? result.getContent() : "Code search error";
            throw new RuntimeException(errorMsg);
        }

        // 4. 解析并返回内容
        if (Utils.isNotEmpty(result.getContent())) {
            return new Document()
                    .title("Code search: " + query)
                    .content(result.getContent())
                    .metadata("query", query)
                    .metadata("tokensNum", finalTokens)
                    .metadata("source", "exa.ai");
        }

        // 5. 兜底处理 (与原版文案完全对齐)
        String fallbackMsg = "No code snippets or documentation found. Please try a different query, " +
                "be more specific about the library or programming concept, or check the spelling of framework names.";

        return new Document()
                .title("Code search: " + query)
                .content(fallbackMsg);
    }
}