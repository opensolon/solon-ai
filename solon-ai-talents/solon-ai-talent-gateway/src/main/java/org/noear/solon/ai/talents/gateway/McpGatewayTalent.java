package org.noear.solon.ai.talents.gateway;

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
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MCP 网关：按配置名管理 MCP 服务，支持动态添加与移除。
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
    public static final String CTX_MCPS_SELECTED = "_mcps_selected";
    public static List<String> getSelected(Prompt prompt) {
        if (prompt == null) {
            return Collections.emptyList();
        }

        return getSelected(prompt.attrs());
    }

    public static List<String> getSelected(Map<String,Object> toolContext) {
        return toSelected(toolContext, CTX_MCPS_SELECTED);
    }

    /**
     * 弱类型读取视野：toolContext 本质是 {@code Map<String,Object>}，强转会在类型不符时
     * 抛 ClassCastException 打死整轮对话。
     */
    public static List<String> toSelected(Map<String,Object> map, String key) {
        if (map == null) {
            return Collections.emptyList();
        }

        Object tmp = map.get(key);
        if (tmp == null) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        if (tmp instanceof Collection) {
            for (Object item : (Collection<?>) tmp) {
                if (item != null && item.toString().trim().length() > 0) {
                    result.add(item.toString().trim());
                }
            }
        } else if (tmp instanceof String[]) {
            for (String item : (String[]) tmp) {
                if (item != null && item.trim().length() > 0) {
                    result.add(item.trim());
                }
            }
        } else {
            for (String item : tmp.toString().split(",")) {
                if (item.trim().length() > 0) {
                    result.add(item.trim());
                }
            }
        }

        return result;
    }

    public static void putSelected(Prompt prompt, List<String> mcpServerNames){
        prompt.attrPut(CTX_MCPS_SELECTED, mcpServerNames);
    }

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
    private long retryDelayMs = 1000L; // 默认初始延迟

    private final McpToolCallTool callTool;

    public McpGatewayTalent() {
        this.callTool = new McpToolCallTool(this);
        internalAddTool(callTool);
    }

    public McpGatewayTalent retryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(100, retryDelayMs);
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

        // disabled: 仅注册到管理表，不加入 categoryTools 和 allTools
        if (mcpProvider.isEnabled() == false) {
            LOG.info("Mcp server '{}': is disabled, registered to management only", name);
            return this;
        }

        try {
            Set<String> toolNames = new LinkedHashSet<>();
            for (FunctionTool tool : mcpProvider.getToolsActivated()) {
                String key = tool.name().toLowerCase();
                categoryTools.computeIfAbsent(name, k -> new ConcurrentHashMap<>())
                        .put(key, tool);
                allTools.put(key, tool);
                toolNames.add(key);
            }
            serverToolIndex.put(name, toolNames);

            LOG.info("Mcp server {}: added ({} tools)", name, toolNames.size());
        } catch (Throwable e) {
            LOG.error("Mcp server {}: loading failed", name, e);
        }

        return this;
    }

    /**
     * 添加 MCP 服务（通过 McpServerParameters，内部自动构建 Provider）
     */
    public McpGatewayTalent addMcpServer(String name, McpServerParameters mcpParameters) {
        if (Utils.isEmpty(name) || mcpParameters == null) {
            return this;
        }

        // 同名已存在则先移除（幂等，不存在的 name 是空操作）
        removeMcpServer(name);

        try {
            McpClientProvider mcpProvider = McpClientProviders.fromMcpServer(mcpParameters);
            mcpProvider.setEnabled(mcpParameters.isEnabled());

            addMcpServer(name, mcpProvider);
        } catch (Throwable e) {
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
                LOG.warn("Mcp server {}: close failed", name, e);
            }
        }

        if (toolNames != null && !toolNames.isEmpty()) {
            LOG.info("Mcp server {}: removed ({} tools)", name, toolNames.size());
        }
        return this;
    }

    // ========== 刷新 ==========

    /**
     * 刷新指定 MCP 服务的工具列表（当 McpClientProvider 的 allowedTools / disallowedTools 变更后调用）
     *
     * <p>采用影子交换策略：先在局部变量中构建新索引，再一次性替换，
     * 缩小中间状态窗口，避免调用中的线程因工具短暂丢失而失败。
     */
    public McpGatewayTalent refreshMcpServer(String name) {
        if (Utils.isEmpty(name)) {
            return this;
        }

        McpClientProvider provider = providerMap.get(name);
        if (provider == null) {
            return this;
        }

        // disabled: 只从工具索引移除，保留 provider（不关闭连接）
        if (provider.isEnabled() == false) {
            Set<String> toolNames = serverToolIndex.remove(name);
            if (toolNames != null) {
                for (String key : toolNames) {
                    allTools.remove(key);
                }
            }
            categoryTools.remove(name);
            LOG.info("Mcp server {}: is disabled, removed {} tools from index", name,
                    toolNames != null ? toolNames.size() : 0);
            return this;
        }

        // 1. 在局部变量中构建新的工具索引
        Set<String> newToolNames = new LinkedHashSet<>();
        Map<String, FunctionTool> newCategoryMap = new ConcurrentHashMap<>();

        try {
            for (FunctionTool tool : provider.getToolsActivated()) {
                String key = tool.name().toLowerCase();
                newCategoryMap.put(key, tool);
                newToolNames.add(key);
            }
        } catch (Throwable e) {
            LOG.error("Mcp server {}: loading failed", name, e);
            return this;
        }

        // 2. 收集旧工具名（仅属于当前 server 且不属于新工具集的）
        Set<String> oldToolNames = serverToolIndex.get(name);
        Set<String> toRemove = new LinkedHashSet<>();
        if (oldToolNames != null) {
            for (String key : oldToolNames) {
                if (!newToolNames.contains(key)) {
                    toRemove.add(key);
                }
            }
        }

        // 3. 原子性替换：先加新，再删旧（保证 add-before-remove）
        categoryTools.put(name, newCategoryMap);
        serverToolIndex.put(name, newToolNames);
        for (String key : newToolNames) {
            allTools.put(key, newCategoryMap.get(key));
        }
        for (String key : toRemove) {
            allTools.remove(key);
        }

        LOG.info("Mcp server {}: refreshed ({} tools)", name, newToolNames.size());
        return this;
    }

    /**
     * 刷新所有 MCP 服务的工具列表
     */
    public McpGatewayTalent refreshMcpServerAll() {
        // 拷贝快照再遍历，避免 refreshMcpServer 期间并发修改 providerMap
        for (String name : new ArrayList<>(providerMap.keySet())) {
            refreshMcpServer(name);
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
        return viewTools(prompt).isEmpty() == false;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        final Map<String, Map<String, FunctionTool>> view = viewCategories(prompt);
        final int size = viewTools(prompt).size();
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
            view.forEach((serverName, tools) -> {
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
            view.forEach((serverName, tools) -> {
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
        Map<String, FunctionTool> view = viewTools(prompt);
        int size = view.size();

        // FULL 模式：直接交付原始工具
        if (size <= dynamicThreshold) {
            return view.values();
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

    // ========== 视野（per-request 选择） ==========

    /**
     * 本次请求的 MCP 服务视野（未选择时返回全量）
     * <p>
     * 空视图<b>不</b>回退全量：选中一个不存在 / 已移除的服务时应当没有工具可用，
     * 回退全量会让「我明明只勾了一个服务」变成「全都开着」，属于静默越权。
     */
    private Map<String, Map<String, FunctionTool>> viewCategories(@Nullable Prompt prompt) {
        List<String> selected = getSelected(prompt);
        if (selected.isEmpty()) {
            return categoryTools;
        }

        Map<String, Map<String, FunctionTool>> view = new LinkedHashMap<>();
        for (String serverName : selected) {
            Map<String, FunctionTool> tools = categoryTools.get(serverName);
            if (tools != null) {
                view.put(serverName, tools);
            }
        }
        return view;
    }

    /** 本次请求可见的工具（toolKey 小写） */
    private Map<String, FunctionTool> viewTools(@Nullable Prompt prompt) {
        List<String> selected = getSelected(prompt);
        if (selected.isEmpty()) {
            return allTools;
        }

        Map<String, FunctionTool> view = new LinkedHashMap<>();
        for (String serverName : selected) {
            Map<String, FunctionTool> tools = categoryTools.get(serverName);
            if (tools != null) {
                view.putAll(tools);
            }
        }
        return view;
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
            if (tool_args == null) {
                tool_args = new HashMap<>();
            } else {
                tool_args = new HashMap<>(tool_args); // 复制一份，避免修改原始参数
            }
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                if (!"tool_name".equals(entry.getKey()) && !"tool_args".equals(entry.getKey())) {
                    tool_args.put(entry.getKey(), entry.getValue());
                }
            }
            final Map<String, Object> finalToolArgs = tool_args;

            //-------

            if (Utils.isEmpty(tool_name)) {
                return ToolResult.success("错误：tool_name 不能为空");
            }

            FunctionTool tool = gatewayTalent.allTools.get(tool_name.trim().toLowerCase());

            if (tool == null) {
                return ToolResult.success("错误：未找到MCP工具 '" + tool_name + "'");
            }

            // selected 是视野不是权限：call_tool 是点名调用，不做执行侧硬控。

            try {
                return RetryUtil.callWithRetry(gatewayTalent.maxRetries, gatewayTalent.retryDelayMs, gatewayTalent.retryDelayMs * 4, () -> tool.call(finalToolArgs));
            } catch (Throwable e) {
                LOG.error("McpTool gateway execution failed: {}", tool_name, e);
                return ToolResult.success("执行异常: " +
                        (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }
    }
}
