package org.noear.solon.ai.skills.toolgateway;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpProviders;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * MCP 网关工具包：按配置名管理 MCP 服务，支持动态添加与移除。
 *
 * <p>完全自建工具管理（categoryTools + allTools），提供与 ToolGatewaySkill 相同的四阶段路由。
 * 支持按 name 进行动态添加和移除（含连接关闭）。
 *
 * @author noear 2026/5/29 created
 * @since 3.10
 */
public class McpGatewaySkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(McpGatewaySkill.class);

    // 工具索引（自建，完全由本类管理）
    private final Map<String, Map<String, FunctionTool>> categoryTools = new LinkedHashMap<>();
    private final Map<String, FunctionTool> allTools = new LinkedHashMap<>();

    // MCP 连接管理
    private final Map<String, McpClientProvider> providerMap = new LinkedHashMap<>();
    // name -> 该 provider 注册的 toolNames（小写），加速移除
    private final Map<String, Set<String>> serverToolIndex = new LinkedHashMap<>();

    // 四阶段阈值
    private int dynamicThreshold = 8;
    private int listThreshold = 40;
    private int searchThreshold = 100;

    private int maxRetries = 3;

    public McpGatewaySkill() {
    }

    public McpGatewaySkill retryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        return this;
    }

    public McpGatewaySkill retryConfig(int maxRetries) {
        this.maxRetries = Math.max(1, maxRetries);
        return this;
    }

    public McpGatewaySkill dynamicThreshold(int dynamicThreshold) {
        this.dynamicThreshold = dynamicThreshold;
        return this;
    }

    public McpGatewaySkill listThreshold(int listThreshold) {
        this.listThreshold = listThreshold;
        return this;
    }

    public McpGatewaySkill searchThreshold(int searchThreshold) {
        this.searchThreshold = searchThreshold;
        return this;
    }

    // ========== 添加 ==========

    /**
     * 添加 MCP 服务（通过 McpClientProvider）
     *
     * @param name 服务配置名（如 "weather"），用于后续按名移除
     */
    public McpGatewaySkill addMcpServer(String name, McpClientProvider mcpProvider) {
        if (Utils.isEmpty(name) || mcpProvider == null) {
            return this;
        }

        // 同名已存在则先移除
        if (providerMap.containsKey(name)) {
            removeMcpServer(name);
        }

        providerMap.put(name, mcpProvider);

        Set<String> toolNames = new LinkedHashSet<>();
        for (FunctionTool tool : mcpProvider.getTools()) {
            String key = tool.name().toLowerCase();
            categoryTools.computeIfAbsent(name, k -> new LinkedHashMap<>())
                    .put(key, tool);
            allTools.put(key, tool);
            toolNames.add(key);
        }
        serverToolIndex.put(name, toolNames);

        LOG.info("McpGatewaySkill: Added '{}' ({} tools)", name, toolNames.size());
        return this;
    }

    /**
     * 添加 MCP 服务（通过 McpServerParameters，内部自动构建 Provider）
     */
    public McpGatewaySkill addMcpServer(String name, McpServerParameters mcpParameters) {
        if (Utils.isEmpty(name) || mcpParameters == null) {
            return this;
        }

        try {
            McpClientProvider provider = McpProviders.fromMcpServer(mcpParameters);
            addMcpServer(name, provider);
        } catch (IOException e) {
            throw new RuntimeException("Mcp server '" + name + "' create failed", e);
        }
        return this;
    }

    // ========== 移除 ==========

    /**
     * 移除 MCP 服务（按配置名），并关闭连接
     */
    public McpGatewaySkill removeMcpServer(String name) {
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
                LOG.warn("McpGatewaySkill: Provider '{}' close failed", name, e);
            }
        }

        if (toolNames != null && !toolNames.isEmpty()) {
            LOG.info("McpGatewaySkill: Removed '{}' ({} tools)", name, toolNames.size());
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

    // ========== Skill 接口实现（自建四阶段路由） ==========

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
        return allTools.values();
    }
}
