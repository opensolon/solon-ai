package org.noear.solon.ai.talents.toolgateway;

import org.noear.snack4.codec.TypeRef;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.tool.AbsTool;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpClientProviders;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.util.RetryUtil;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.annotation.ToolMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MCP 网关工具包：按配置名管理 MCP 服务，支持动态添加与移除。
 *
 * <p>通过工具数量自动切换四阶段模式，平衡模型推理精度与 Token 消耗（与 ToolGatewayTalent 相同机制）：
 * 1. FULL (全量): 数量 &lt;= dynamicThreshold。平铺所有工具的完整 Schema。
 * 2. SUMMARY (摘要): 数量 &lt;= listThreshold。在指令内展示"工具名 + 描述"清单。
 * 3. LIST (名字): 数量 &lt;= searchThreshold。仅展示"工具名"清单。
 * 4. SEARCH (搜索): 数量 &gt; searchThreshold。完全折叠，强制 LLM 使用关键词检索。
 *
 * <p>支持按 name 进行动态添加和移除（含连接关闭）。
 *
 * @author noear 2026/5/29 created
 * @since 3.10
 */
public class McpGatewayTalent extends AbsTalent {
    private static final Logger LOG = LoggerFactory.getLogger(McpGatewayTalent.class);

    // 工具索引（自建，完全由本类管理）
    private final Map<String, Map<String, FunctionTool>> categoryTools = new ConcurrentHashMap<>();
    private final Map<String, FunctionTool> allTools = new ConcurrentHashMap<>();

    // MCP 连接管理
    private final Map<String, McpClientProvider> providerMap = new ConcurrentHashMap<>();
    // name -> 该 provider 注册的 toolNames（小写），加速移除
    private final Map<String, Set<String>> serverToolIndex = new ConcurrentHashMap<>();

    // 四阶段阈值
    private int dynamicThreshold = 8;
    private int listThreshold = 40;
    private int searchThreshold = 100;

    private int maxRetries = 3;

    private final McpToolCallTool callTool;

    public McpGatewayTalent() {
        this.callTool = new McpToolCallTool(this);
        internalAddTool(callTool);
    }

    public McpGatewayTalent retryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        return this;
    }

    public McpGatewayTalent retryConfig(int maxRetries) {
        this.maxRetries = Math.max(1, maxRetries);
        return this;
    }

    public McpGatewayTalent dynamicThreshold(int dynamicThreshold) {
        this.dynamicThreshold = dynamicThreshold;
        return this;
    }

    public McpGatewayTalent listThreshold(int listThreshold) {
        this.listThreshold = listThreshold;
        return this;
    }

    public McpGatewayTalent searchThreshold(int searchThreshold) {
        this.searchThreshold = searchThreshold;
        return this;
    }

    // ========== 添加 ==========

    /**
     * 添加 MCP 服务（通过 McpClientProvider）
     *
     * @param name 服务配置名（如 "weather"），用于后续按名移除
     */
    public McpGatewayTalent addMcpServer(String name, McpClientProvider mcpProvider) {
        if (Utils.isEmpty(name) || mcpProvider == null) {
            return this;
        }

        // 同名已存在则先移除（幂等，不存在的 name 是空操作）
        removeMcpServer(name);

        providerMap.put(name, mcpProvider);

        Set<String> toolNames = new LinkedHashSet<>();
        for (FunctionTool tool : mcpProvider.getTools()) {
            String key = tool.name().toLowerCase();
            categoryTools.computeIfAbsent(name, k -> new ConcurrentHashMap<>())
                    .put(key, tool);
            allTools.put(key, tool);
            toolNames.add(key);
        }
        serverToolIndex.put(name, toolNames);

        LOG.info("McpGatewayTalent: Added '{}' ({} tools)", name, toolNames.size());
        return this;
    }

    /**
     * 添加 MCP 服务（通过 McpServerParameters，内部自动构建 Provider）
     */
    public McpGatewayTalent addMcpServer(String name, McpServerParameters mcpParameters) {
        if (Utils.isEmpty(name) || mcpParameters == null) {
            return this;
        }

        if (mcpParameters.isEnabled() == false) {
            LOG.info("MCP server '{}' is disabled, skipping registration", name);
            return this;
        }

        try {
            McpClientProvider provider = McpClientProviders.fromMcpServer(mcpParameters);
            addMcpServer(name, provider);
        } catch (IOException e) {
            LOG.error("Mcp server '{}' create failed", name, e);
        }
        return this;
    }

    // ========== 移除 ==========

    /**
     * 移除 MCP 服务（按配置名），并关闭连接
     */
    public McpGatewayTalent removeMcpServer(String name) {
        if (Utils.isEmpty(name)) {
            return this;
        }

        // 1. 从索引中收集并移除工具
        Set<String> toolNames = serverToolIndex.remove(name);
        if (toolNames != null) {
            for (String key : toolNames) {
                allTools.remove(key);
            }
        }

        // 2. 从 categoryTools 中移除
        categoryTools.remove(name);

        // 3. 关闭 provider 连接
        McpClientProvider provider = providerMap.remove(name);
        if (provider != null) {
            try {
                provider.close();
            } catch (Exception e) {
                LOG.warn("McpGatewayTalent: Provider '{}' close failed", name, e);
            }
        }

        if (toolNames != null && !toolNames.isEmpty()) {
            LOG.info("McpGatewayTalent: Removed '{}' ({} tools)", name, toolNames.size());
        }
        return this;
    }

    // ========== 查询 ==========

    /**
     * 获取已注册的 MCP 服务名集合
     */
    public Set<String> getMcpServerNames() {
        return Collections.unmodifiableSet(providerMap.keySet());
    }

    /**
     * 获取指定名称的 MCP Provider
     */
    public McpClientProvider getMcpServer(String name) {
        return providerMap.get(name);
    }

    /**
     * 是否包含指定名称的 MCP 服务
     */
    public boolean hasMcpServer(String name) {
        return providerMap.containsKey(name);
    }

    // ========== Talent 接口实现（四阶段路由） ==========

    @Override
    public String description() {
        return "MCP工具网关（共 " + providerMap.size() + " 个服务，" + allTools.size() + " 个工具）";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return allTools.size() > 0;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        final int size = allTools.size();
        StringBuilder sb = new StringBuilder();
        sb.append("## MCP工具发现规范 (共 ").append(size).append(" 个工具)\n");

        if (size <= dynamicThreshold) {
            // --- FULL 模式 (全铺) ---
            sb.append("### 运行模式: 直接调用\n");
            sb.append("当前已加载全量MCP工具定义。请直接分析需求并调用最匹配的工具，无需中转。");
        } else if (size <= listThreshold) {
            // --- SUMMARY 模式 (摘要) ---
            sb.append("### 运行模式: 动态发现 (摘要)\n");
            sb.append("由于工具较多，请遵循以下流程：\n");
            sb.append("- **Step 1 (锁定)**: 从下方\"可用MCP工具清单\"中根据功能描述选定工具名。\n");
            sb.append("- **Step 2 (详情)**: 调用 `get_tool_detail` 获取参数 Schema。\n");
            sb.append("- **Step 3 (执行)**: 确认参数后，通过 `call_tool` 提交执行。\n\n");

            sb.append("#### 可用MCP工具清单:\n");
            categoryTools.forEach((serverName, tools) -> {
                sb.append("- **[").append(serverName).append("]**:\n");
                tools.values().forEach(t ->
                        sb.append("  - `").append(t.name()).append("`: ").append(t.description()).append("\n")
                );
            });
        } else if (size <= searchThreshold) {
            // --- LIST 模式 (名字) ---
            sb.append("### 运行模式: 动态发现 (列表)\n");
            sb.append("由于工具库规模较大，仅展示名称。请遵循以下流程：\n");
            sb.append("- **Step 1 (预选)**: 从下方\"工具列表\"中根据名称推断功能，或用 `search_tools` 检索。\n");
            sb.append("- **Step 2 (详情)**: 调用 `get_tool_detail` 获取参数 Schema。\n");
            sb.append("- **Step 3 (执行)**: 确认参数后，通过 `call_tool` 提交执行。\n\n");

            sb.append("#### 可用MCP工具列表 (按服务展示):\n");
            categoryTools.forEach((serverName, tools) -> {
                String names = String.join(", ", tools.keySet());
                sb.append("- **").append(serverName).append("**: `").append(names).append("`\n");
            });
            sb.append("\n> 提示：服务名和工具名具有语义参考价值，锁定目标后务必先查详情。");
        } else {
            // --- SEARCH 模式 (搜索) ---
            sb.append("### 运行模式: 搜索发现\n");
            sb.append("MCP工具库规模巨大，清单已折叠。请遵循以下流程：\n");
            sb.append("- **Step 1 (搜索)**: 必须先使用 `search_tools` 通过关键词寻找匹配的工具。\n");
            sb.append("- **Step 2 (详情)**: 调用 `get_tool_detail` 获取参数 Schema。\n");
            sb.append("- **Step 3 (执行)**: 确认参数后，通过 `call_tool` 提交执行。\n\n");

            sb.append("> **注意**: 请勿盲目猜测工具名。建议搜索关键词，如：search_tools('天气')");
        }

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        int size = allTools.size();

        // FULL 模式：直接交付原始工具
        if (size <= dynamicThreshold) {
            return allTools.values();
        }

        // SUMMARY 模式：上下文已包含摘要，无需搜索功能，只需提供详情与代理工具
        if (size <= listThreshold) {
            return this.getToolAry().stream()
                    .filter(f -> !"search_tools".equals(f.name()))
                    .collect(Collectors.toList());
        }

        // LIST & SEARCH 模式：提供完整的中转工具集（包含 search_tools）
        return this.getToolAry();
    }

    // ========== 中转工具方法（由 AbsTalent 的 MethodToolProvider 自动扫描） ==========

    @ToolMapping(name = "search_tools", description = "搜索MCP工具。支持多个关键词用空隔隔开（如：'杭州 旅游'）")
    public Object searchTools(@Param("keyword") String keyword) {
        if (Utils.isEmpty(keyword)) return "错误：搜索关键词不能为空。";

        String[] keys = keyword.toLowerCase().split("[\\s,;，；]+");

        List<Map<String, String>> results = allTools.values().stream()
                .filter(t -> {
                    String content = (t.name() + " " + t.description()).toLowerCase();
                    return Arrays.stream(keys).allMatch(content::contains);
                })
                .limit(10)
                .map(t -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("tool_name", t.name());
                    map.put("tool_description", t.description());
                    return map;
                })
                .collect(Collectors.toList());

        if (results.isEmpty()) {
            return "提醒：未找到完全匹配关键词 '" + keyword + "' 的MCP工具。\n" +
                    "您可以尝试：\n" +
                    "1. 检查关键词是否使用了空格分隔（如：'杭州 旅游'）。\n" +
                    "2. 换用更通用的词汇，或减少关键词数量。\n" +
                    "3. 如果确定无此功能，请告知用户。禁止重复尝试相似搜索。";
        }

        return results;
    }

    @ToolMapping(name = "get_tool_detail", description = "获取指定MCP工具的完整参数 Schema")
    public String getToolDetail(@Param("tool_name") String name) {
        if (Utils.isEmpty(name)) return "错误：tool_name 不能为空";

        FunctionTool tool = allTools.get(name.trim().toLowerCase());
        if (tool != null) {
            return "### MCP工具详情: " + tool.name() + "\n" +
                    "- 功能描述: " + tool.description() + "\n" +
                    "- 参数架构 (JSON Schema): \n```json\n" + tool.inputSchema() + "\n```";
        }

        return "错误：未找到MCP工具 '" + name + "'，请检查名称拼写是否正确。";
    }

    //测试用
    public ToolResult callTool(String tool_name,
                               Map<String, Object> tool_args) {
        try {
            return callTool.call(Utils.asMap(
                    "tool_name", tool_name,
                    "tool_args", tool_args));
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    // ========== 内部中转代理工具 ==========

    public static class McpToolCallTool extends AbsTool {
        private final McpGatewayTalent gatewayTalent;

        public McpToolCallTool(McpGatewayTalent gatewayTalent) {
            this.gatewayTalent = gatewayTalent;

            addParam("tool_name", String.class, "");
            addParam("tool_args", TypeRef.mapOf(String.class, Object.class).getType(), "");
        }

        @Override
        public String name() {
            return "call_tool";
        }

        @Override
        public String description() {
            return "代理执行特定的MCP工具";
        }

        @Override
        public Object handle(Map<String, Object> args) {
            String tool_name = (String) args.get("tool_name");
            Map<String, Object> tool_args = (Map) args.get("tool_args");

            //传导工具上下文
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                if (!"tool_name".equals(entry.getKey()) && !"tool_args".equals(entry.getKey())) {
                    tool_args.put(entry.getKey(), entry.getValue());
                }
            }

            //-------

            if (Utils.isEmpty(tool_name)) {
                return ToolResult.success("错误：tool_name 不能为空");
            }

            FunctionTool tool = gatewayTalent.allTools.get(tool_name.trim().toLowerCase());

            if (tool == null) {
                return ToolResult.success("错误：未找到MCP工具 '" + tool_name + "'");
            }

            try {
                return RetryUtil.callWithRetry(gatewayTalent.maxRetries, () -> tool.call(tool_args));
            } catch (Throwable e) {
                LOG.error("McpTool gateway execution failed: {}", tool_name, e);
                return ToolResult.success("执行异常: " +
                        (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }
    }
}
