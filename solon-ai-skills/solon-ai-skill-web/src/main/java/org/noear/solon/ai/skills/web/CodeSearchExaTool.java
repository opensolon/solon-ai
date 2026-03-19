package org.noear.solon.ai.skills.web;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.AbsTool;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.client.McpClientProvider;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CodeSearchTool
 */
public class CodeSearchExaTool extends AbsTool {
    private static CodeSearchExaTool instance = new CodeSearchExaTool();
    public static CodeSearchExaTool getInstance(){
        return instance;
    }

    private static final int DEFAULT_TOKENS = 5000;
    private final McpClientProvider mcpClient;

    public CodeSearchExaTool() {
        this.mcpClient = ExaMcpClient.getMcpClient();

        addParam("query", String.class, true,
                "搜索查询词，用于查找 API、库和 SDK 的相关上下文。 " +
                        "例如：'React useState 钩子示例'、'Python pandas 数据框过滤'、" +
                        "'Express.js 中间件'、'Next.js 局部预渲染配置'");

        addParam("tokensNum", Integer.class, false,
                "返回的 Token 数量 (1000-50000)。默认为 5000。 " +
                        "根据需要的上下文量进行调整：针对特定问题使用较低值，针对全面文档使用较高值。",
                "5000");
    }

    @Override
    public String name() {
        return "codesearch";
    }

    @Override
    public String description() {
        return "使用 Exa Code API 搜索并获取任何编程任务的相关上下文\n" +
                "- 为库、SDK 和 API 提供最高质量且最实时的上下文信息\n" +
                "- 适用于任何与编程相关的疑问或任务\n" +
                "- 返回详尽的代码示例、技术文档和 API 参考\n" +
                "- 针对寻找特定编程模式和解决方案进行了优化\n\n" +
                "使用说明：\n" +
                "- 可调节 Token 数量 (1000-50000) 以获得精确或详尽的结果\n" +
                "- 默认 5000 Token 为大多数查询提供均衡的上下文\n" +
                "- 支持关于框架、库、API 以及编程概念的查询\n" +
                "- 示例：'React 状态管理'、'Spring Boot 响应式编程'、'Solon 插件开发'";
    }

    @Override
    public Object handle(Map<String, Object> args0) throws Throwable {
        String query = (String) args0.get("query");
        Object tokensNumObj = args0.get("tokensNum");

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
            result = mcpClient.callTool("get_code_context_exa", toolArgs);
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