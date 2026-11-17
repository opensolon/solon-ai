package org.noear.solon.ai.talents.gateway;

import io.modelcontextprotocol.spec.McpSchema;
import org.noear.snack4.codec.TypeRef;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.tool.AbsTool;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpClientProviders;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.talents.gateway.i18n.McpGatewayI18n;
import org.noear.solon.ai.talents.gateway.i18n.McpGatewayI18nKeys;
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
 * <p>通过工具数量（与服务数量）自动切换五阶段模式，平衡模型推理精度与 Token 消耗：
 * 1. FULL (全量): 数量 &lt;= dynamicThreshold。平铺所有工具的完整 Schema。
 * 2. SUMMARY (摘要): 数量 &lt;= listThreshold。在指令内展示"工具名 + 描述"清单。
 * 3. LIST (名字): 数量 &lt;= searchThreshold。仅展示"工具名"清单。
 * 4. CATALOG (服务目录): 数量 &gt; searchThreshold 且服务数 &lt;= catalogThreshold。只展示"服务名 + 工具数 + 描述"，
 *    模型用 list_server_tools 下钻到服务内的工具。
 * 5. SEARCH (搜索): 数量 &gt; searchThreshold 且服务数 &gt; catalogThreshold。完全折叠，强制 LLM 使用关键词检索。
 *
 * <p>服务描述取值：配置的描述（{@link McpClientProvider#getDescription()}）&gt; 服务端握手上报的 description &gt;
 * 服务端 instructions；都没有时，服务目录用工具名自动生成一句。
 *
 * <p>工具按「服务 → 工具」定位：不同服务可以有同名工具。search_tools 结果带 server_name，
 * get_tool_detail / call_tool 用 server_name + tool_name 点名（server_name 为空且工具名唯一时自动补上）。
 * 视图里有跨服务的同名工具时不走 FULL（直接调用只按工具名，无法区分服务），降为摘要模式。
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

    /**
     * 强制边界（与「视野」不同）：键存在时，只有名单内的服务可见、可搜、可调用（含 {@code call_tool} 点名调用）；
     * 空名单表示全部禁止。由宿主的请求级作用域写入。
     *
     * @since 4.2.0
     */
    public static final String CTX_MCPS_ALLOWED = "_mcps_allowed";

    /**
     * 读取强制边界（未设置返回 null = 不限制）
     *
     * @since 4.2.0
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static List<String> getAllowed(@Nullable Map<String, Object> map) {
        return map == null ? null : (List<String>) map.get(CTX_MCPS_ALLOWED);
    }

    /**
     * 生效视野：选择（视野）与强制边界取交集；两者都未设置时返回 null（= 全量）
     */
    @Nullable
    private static List<String> effectiveSelection(@Nullable Prompt prompt) {
        List<String> selected = getSelected(prompt);
        List<String> allowed = prompt == null ? null : getAllowed(prompt.attrs());

        if (allowed == null) {
            return selected.isEmpty() ? null : selected;
        }

        if (selected.isEmpty()) {
            return new ArrayList<>(allowed);
        }

        List<String> view = new ArrayList<>();
        for (String s : selected) {
            if (allowed.contains(s)) {
                view.add(s);
            }
        }
        return view;
    }

    // 工具索引：服务名 -> (工具名小写 -> 工具)。服务是定位工具的第一级，不同服务允许有同名工具。
    // 每个服务的工具表构建完即只读，整表替换（不做原地修改）。
    private final Map<String, Map<String, FunctionTool>> categoryTools = new ConcurrentHashMap<>();

    // MCP 连接管理
    private final Map<String, McpClientProvider> providerMap = new ConcurrentHashMap<>();

    // 四阶段阈值
    private int dynamicThreshold = 8;
    private int listThreshold = 40;
    private int searchThreshold = 100;
    // 服务目录模式的服务数上限（超过则目录本身太长，退回搜索模式）
    private int catalogThreshold = 30;
    // list_server_tools 单次最多返回的工具数
    private int listToolsLimit = 30;

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

    /**
     * 服务目录模式的服务数上限（工具数超过 searchThreshold 时，服务数不超过它就展示服务目录，否则退回搜索模式）
     *
     * @since 4.2.0
     */
    public McpGatewayTalent catalogThreshold(int catalogThreshold) {
        this.catalogThreshold = catalogThreshold;
        return this;
    }

    /**
     * list_server_tools 单次最多返回的工具数（超出时提示用 keyword 缩小范围）
     *
     * @since 4.2.0
     */
    public McpGatewayTalent listToolsLimit(int listToolsLimit) {
        this.listToolsLimit = Math.max(1, listToolsLimit);
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

        // disabled: 仅注册到管理表，不加入工具索引
        if (mcpProvider.isEnabled() == false) {
            LOG.info("Mcp server '{}': is disabled, registered to management only", name);
            return this;
        }

        try {
            Map<String, FunctionTool> tools = loadTools(mcpProvider);
            putTools(name, tools);

            LOG.info("Mcp server {}: added ({} tools)", name, tools.size());
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

    /** 拉取服务的工具表（工具名小写为键，保持服务端顺序） */
    private static Map<String, FunctionTool> loadTools(McpClientProvider provider) {
        Map<String, FunctionTool> tools = new LinkedHashMap<>();
        for (FunctionTool tool : provider.getToolsActivated()) {
            tools.put(tool.name().toLowerCase(), tool);
        }
        return Collections.unmodifiableMap(tools);
    }

    /** 整表替换某服务的工具（空表即移除，不留空分组） */
    private void putTools(String name, Map<String, FunctionTool> tools) {
        if (tools.isEmpty()) {
            categoryTools.remove(name);
        } else {
            categoryTools.put(name, tools);
        }
    }

    // ========== 移除 ==========

    /**
     * 移除 MCP 服务（按配置名），并关闭连接
     *
     * <p>只移除该服务自己的工具：工具按「服务 → 工具」索引，其它服务的同名工具不受影响。
     */
    public McpGatewayTalent removeMcpServer(String name) {
        if (Utils.isEmpty(name)) {
            return this;
        }

        // 1. 从工具索引中移除
        Map<String, FunctionTool> tools = categoryTools.remove(name);

        // 2. 关闭 provider 连接
        McpClientProvider provider = providerMap.remove(name);
        if (provider != null) {
            try {
                provider.close();
            } catch (Exception e) {
                LOG.warn("Mcp server {}: close failed", name, e);
            }
        }

        if (tools != null && !tools.isEmpty()) {
            LOG.info("Mcp server {}: removed ({} tools)", name, tools.size());
        }
        return this;
    }

    // ========== 刷新 ==========

    /**
     * 刷新指定 MCP 服务的工具列表（当 McpClientProvider 的 allowedTools / disallowedTools 变更后调用）
     *
     * <p>先在局部变量中构建新工具表，再整表替换，调用中的线程不会看到半更新的状态。
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
            Map<String, FunctionTool> tools = categoryTools.remove(name);
            LOG.info("Mcp server {}: is disabled, removed {} tools from index", name,
                    tools != null ? tools.size() : 0);
            return this;
        }

        final Map<String, FunctionTool> tools;
        try {
            tools = loadTools(provider);
        } catch (Throwable e) {
            LOG.error("Mcp server {}: loading failed", name, e);
            return this;
        }

        putTools(name, tools);

        LOG.info("Mcp server {}: refreshed ({} tools)", name, tools.size());
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

    /**
     * 获取服务描述（服务名大小写不敏感）
     *
     * <p>取值顺序：配置的描述 &gt; 服务端握手上报的 description &gt; 服务端 instructions。
     * 服务端信息只读取已完成的握手结果，不会为此建立连接。都没有或服务不存在时返回 null。
     *
     * @since 4.2.0
     */
    @Nullable
    public String getMcpServerDescription(@Nullable String name) {
        if (Utils.isBlank(name)) {
            return null;
        }

        String server = canonicalName(providerMap.keySet(), name);
        McpClientProvider provider = server == null ? null : providerMap.get(server);
        if (provider == null) {
            return null;
        }

        String description = provider.getDescription();
        if (Utils.isNotBlank(description)) {
            return description.trim();
        }

        try {
            McpSchema.Implementation info = provider.getServerInfo();
            if (info != null && Utils.isNotBlank(info.description())) {
                return info.description().trim();
            }

            String instructions = provider.getServerInstructions();
            if (Utils.isNotBlank(instructions)) {
                return instructions.trim();
            }
        } catch (Throwable e) {
            LOG.debug("Mcp server {}: read server info failed", server, e);
        }

        return null;
    }

    /**
     * 获取服务下已索引的全部工具（服务名大小写不敏感；顺序同服务端；只读）。
     *
     * <p>停用、加载失败或不存在的服务返回空集合。工具名与描述见 {@link FunctionTool#name()} / {@link FunctionTool#description()}。
     *
     * @since 4.2.0
     */
    public Collection<FunctionTool> getMcpServerTools(@Nullable String name) {
        if (Utils.isBlank(name)) {
            return Collections.emptyList();
        }

        String server = canonicalServer(name);
        Map<String, FunctionTool> tools = server == null ? null : categoryTools.get(server);
        if (tools == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * 工具名 → 归属服务名
     *
     * <p>不是本网关的工具，或同名工具属于多个服务时返回 null（需要区分时用 {@link #getServersOfTool(String, String)}）。
     *
     * @since 4.2.0
     */
    @Nullable
    public String getServerOfTool(@Nullable String toolName) {
        List<String> servers = getServersOfTool(null, toolName);
        return servers.size() == 1 ? servers.get(0) : null;
    }

    /**
     * 定位工具的归属服务（结果按服务名排序）
     *
     * @param serverName 服务名（大小写不敏感）；给出时只在该服务内找，结果至多一个；为空时返回拥有该工具的全部服务
     * @param toolName   工具名（大小写不敏感）
     * @return 归属服务名列表（找不到为空列表）
     * @since 4.2.0
     */
    public List<String> getServersOfTool(@Nullable String serverName, @Nullable String toolName) {
        if (Utils.isEmpty(toolName)) {
            return Collections.emptyList();
        }

        String key = toolName.trim().toLowerCase();

        if (Utils.isEmpty(serverName) == false) {
            String server = canonicalServer(serverName);
            if (server != null) {
                Map<String, FunctionTool> tools = categoryTools.get(server);
                if (tools != null && tools.containsKey(key)) {
                    return Collections.singletonList(server);
                }
            }
            return Collections.emptyList();
        }

        List<String> servers = new ArrayList<>();
        for (Map.Entry<String, Map<String, FunctionTool>> entry : categoryTools.entrySet()) {
            if (entry.getValue().containsKey(key)) {
                servers.add(entry.getKey());
            }
        }
        Collections.sort(servers);
        return servers;
    }

    /** 服务名规范化（只在已索引工具的服务里找） */
    @Nullable
    private String canonicalServer(String serverName) {
        return canonicalName(categoryTools.keySet(), serverName);
    }

    /** 名字规范化：精确匹配优先，否则取大小写不敏感的唯一匹配（多个或没有返回 null） */
    @Nullable
    private static String canonicalName(Set<String> names, String serverName) {
        String name = serverName.trim();
        if (names.contains(name)) {
            return name;
        }

        String found = null;
        for (String server : names) {
            if (server.equalsIgnoreCase(name)) {
                if (found != null) {
                    return null;
                }
                found = server;
            }
        }
        return found;
    }

    /** 服务是否在强制边界内（allowed 为 null 时不限制） */
    private static boolean isServerAllowed(String server, @Nullable List<String> allowed) {
        return allowed == null || allowed.contains(server);
    }

    /** 服务是否在视野内（未选择时不限制）。只用于发现（search_tools），点名使用不受它限制 */
    private static boolean isServerSelected(String server, @Nullable List<String> selected) {
        return selected == null || selected.isEmpty() || selected.contains(server);
    }

    /**
     * 中转目标的定位结果：要么定位到唯一工具，要么是有多个候选服务（需要模型指定 server_name）
     */
    private static final class Target {
        final String server;
        final FunctionTool tool;
        final List<String> candidates;

        Target(String server, FunctionTool tool, List<String> candidates) {
            this.server = server;
            this.tool = tool;
            this.candidates = candidates;
        }
    }

    /**
     * 定位点名使用（get_tool_detail / call_tool）的目标，只在强制边界内找。
     *
     * <p>给了服务名：只在该服务内找。没给服务名：边界内只有一个服务有这个工具就自动补上（兼容旧写法）；
     * 有多个时返回候选，由调用方提示指定。越界与不存在不加区分（返回 null），不泄露边界外的服务。
     */
    @Nullable
    private Target resolveTarget(@Nullable String serverName, String toolName, @Nullable List<String> allowed) {
        List<String> servers = new ArrayList<>();
        for (String server : getServersOfTool(serverName, toolName)) {
            if (isServerAllowed(server, allowed)) {
                servers.add(server);
            }
        }

        if (servers.isEmpty()) {
            return null;
        }

        if (servers.size() > 1) {
            return new Target(null, null, servers);
        }

        String server = servers.get(0);
        Map<String, FunctionTool> tools = categoryTools.get(server);
        FunctionTool tool = tools == null ? null : tools.get(toolName.trim().toLowerCase());
        return tool == null ? null : new Target(server, tool, null);
    }

    /** 工具总数（按「服务 + 工具」计，同名工具分别计数） */
    private static int countTools(Map<String, Map<String, FunctionTool>> view) {
        int size = 0;
        for (Map<String, FunctionTool> tools : view.values()) {
            size += tools.size();
        }
        return size;
    }

    /** 视图里是否有跨服务的同名工具（有则不能直接平铺：直接调用只按工具名，无法区分服务） */
    private static boolean hasDuplicateName(Map<String, Map<String, FunctionTool>> view) {
        Set<String> names = new HashSet<>();
        for (Map<String, FunctionTool> tools : view.values()) {
            for (String key : tools.keySet()) {
                if (names.add(key) == false) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 是否走 FULL 模式：数量不超过阈值，且没有同名工具（有同名时降为摘要模式，走带服务名的中转） */
    private boolean isFullMode(Map<String, Map<String, FunctionTool>> view, int size) {
        return size <= dynamicThreshold && hasDuplicateName(view) == false;
    }

    // ========== Talent 接口实现（四阶段路由） ==========

    @Override
    public String description(Prompt prompt) {
        return McpGatewayI18n.fmt(AiI18n.localeOf(prompt), McpGatewayI18nKeys.GATEWAY_MCP_DESCRIPTION,
                "servers", providerMap.size(), "tools", countTools(categoryTools));
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return countTools(viewCategories(prompt)) > 0;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        final Locale locale = AiI18n.localeOf(prompt);
        final Map<String, Map<String, FunctionTool>> view = viewCategories(prompt);
        final int size = countTools(view);
        StringBuilder sb = new StringBuilder();
        sb.append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_TITLE, "count", size)).append("\n");

        switch (modeOf(view, size)) {
            case FULL:
                // --- FULL 模式 (全铺) ---
                sb.append(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_FULL));
                break;
            case SUMMARY:
                // --- SUMMARY 模式 (摘要) ---
                sb.append(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_SUMMARY)).append("\n");
                view.forEach((serverName, tools) -> {
                    sb.append("- **[").append(serverName).append("]**:");
                    String description = briefDescription(serverName, CATALOG_DESCRIPTION_MAX);
                    if (description != null) {
                        sb.append(" ").append(description);
                    }
                    sb.append("\n");
                    tools.values().forEach(t ->
                            sb.append("  - `").append(t.name()).append("`: ").append(t.description()).append("\n")
                    );
                });
                break;
            case LIST:
                // --- LIST 模式 (名字) ---
                sb.append(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_LIST)).append("\n");
                view.forEach((serverName, tools) -> {
                    String names = String.join(", ", tools.keySet());
                    sb.append("- **").append(serverName).append("**");
                    String description = briefDescription(serverName, CATALOG_DESCRIPTION_MAX);
                    if (description != null) {
                        sb.append(" (").append(description).append(")");
                    }
                    sb.append(": `").append(names).append("`\n");
                });
                sb.append("\n").append(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_LIST_TIP));
                break;
            case CATALOG:
                // --- CATALOG 模式 (服务目录：按服务名排序，保持指令稳定) ---
                sb.append(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_CATALOG)).append("\n");
                new TreeMap<>(view).forEach((serverName, tools) -> {
                    String description = briefDescription(serverName, CATALOG_DESCRIPTION_MAX);
                    if (description == null) {
                        description = fallbackDescription(locale, tools);
                    }
                    sb.append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_CATALOG_ITEM,
                            "server", serverName, "count", tools.size(), "description", description)).append("\n");
                });
                break;
            default:
                // --- SEARCH 模式 (搜索) ---
                sb.append(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_SEARCH));
                break;
        }

        return sb.toString();
    }

    /** 发现模式 */
    private enum Mode {
        FULL, SUMMARY, LIST, CATALOG, SEARCH
    }

    private Mode modeOf(Map<String, Map<String, FunctionTool>> view, int size) {
        if (isFullMode(view, size)) {
            return Mode.FULL;
        } else if (size <= listThreshold) {
            return Mode.SUMMARY;
        } else if (size <= searchThreshold) {
            return Mode.LIST;
        } else if (view.size() <= catalogThreshold) {
            return Mode.CATALOG;
        } else {
            return Mode.SEARCH;
        }
    }

    // 指令里服务描述的最大字符数（超出截断）
    private static final int CATALOG_DESCRIPTION_MAX = 150;
    // list_server_tools 返回里服务描述的最大字符数
    private static final int DETAIL_DESCRIPTION_MAX = 500;
    // 自动生成描述时列出的工具名个数
    private static final int FALLBACK_TOOL_NAMES = 5;

    /** 服务描述的单行短文本（没有描述时返回 null） */
    @Nullable
    private String briefDescription(String serverName, int max) {
        String description = getMcpServerDescription(serverName);
        if (description == null) {
            return null;
        }

        String text = description.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    /** 没有描述时，用前几个工具名自动生成一句 */
    private static String fallbackDescription(Locale locale, Map<String, FunctionTool> tools) {
        List<String> names = new ArrayList<>();
        for (FunctionTool tool : tools.values()) {
            if (names.size() >= FALLBACK_TOOL_NAMES) {
                break;
            }
            names.add(tool.name());
        }

        String text = String.join(", ", names);
        if (tools.size() > names.size()) {
            text = text + ", …";
        }
        return McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_CATALOG_FALLBACK, "names", text);
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        Map<String, Map<String, FunctionTool>> view = viewCategories(prompt);
        int size = countTools(view);

        switch (modeOf(view, size)) {
            case FULL: {
                // FULL 模式：直接交付原始工具（已保证没有同名工具）
                List<FunctionTool> tools = new ArrayList<>(size);
                for (Map<String, FunctionTool> group : view.values()) {
                    tools.addAll(group.values());
                }
                return tools;
            }
            case SUMMARY:
                // SUMMARY 模式：上下文已包含摘要，无需搜索与下钻，只需提供详情与代理工具
                return super.getTools(prompt).stream()
                        .filter(f -> !TOOL_SEARCH.equals(f.name()) && !TOOL_LIST_SERVER.equals(f.name()))
                        .collect(Collectors.toList());
            case LIST:
                // LIST 模式：指令已列出全部工具名，下钻意义不大（详情用 get_tool_detail）
                return super.getTools(prompt).stream()
                        .filter(f -> !TOOL_LIST_SERVER.equals(f.name()))
                        .collect(Collectors.toList());
            default:
                // CATALOG & SEARCH 模式：提供完整的中转工具集（含 search_tools 与 list_server_tools）
                return super.getTools(prompt);
        }
    }

    private static final String TOOL_SEARCH = "search_tools";
    private static final String TOOL_LIST_SERVER = "list_server_tools";

    // ========== 视野（per-request 选择） ==========

    /**
     * 本次请求的 MCP 服务视野（未选择时返回全量）
     * <p>
     * 空视图<b>不</b>回退全量：选中一个不存在 / 已移除的服务时应当没有工具可用，
     * 回退全量会让「我明明只勾了一个服务」变成「全都开着」，属于静默越权。
     */
    private Map<String, Map<String, FunctionTool>> viewCategories(@Nullable Prompt prompt) {
        List<String> selected = effectiveSelection(prompt);
        if (selected == null) {
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

    // ========== 中转工具方法（由 AbsTalent 的 MethodToolProvider 自动扫描） ==========

    /**
     * 搜索 MCP 工具（_mcps_allowed、_mcps_selected、__locale 由工具上下文注入）
     *
     * <p>每条结果带 server_name，供 get_tool_detail / call_tool 定位（不同服务可能有同名工具）。
     * 结果同时按强制边界与视野收窄（两者叠加即交集）；视野为空表示不限制。
     * get_tool_detail / call_tool 属于点名使用，不按视野收窄。
     *
     * @since 4.2
     */
    @ToolMapping(name = "search_tools", description = "搜索MCP工具。支持多个关键词用空隔隔开（如：'杭州 旅游'）")
    public Object searchTools(@Param("keyword") String keyword, List<String> _mcps_allowed, List<String> _mcps_selected, String __locale) {
        Locale locale = AiI18n.toLocale(__locale);
        if (Utils.isEmpty(keyword)) return McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_SEARCH_BLANK);

        String[] keys = keyword.toLowerCase().split("[\\s,;，；]+");

        List<Map<String, String>> results = categoryTools.entrySet().stream()
                .filter(e -> isServerAllowed(e.getKey(), _mcps_allowed))
                .filter(e -> isServerSelected(e.getKey(), _mcps_selected))
                .sorted(Map.Entry.comparingByKey())
                .flatMap(e -> e.getValue().values().stream()
                        .filter(t -> {
                            String content = (t.name() + " " + t.description()).toLowerCase();
                            return Arrays.stream(keys).allMatch(content::contains);
                        })
                        .map(t -> {
                            Map<String, String> map = new LinkedHashMap<>();
                            map.put("server_name", e.getKey());
                            map.put("tool_name", t.name());
                            map.put("tool_description", t.description());
                            return map;
                        }))
                .limit(10)
                .collect(Collectors.toList());

        if (results.isEmpty()) {
            return McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_SEARCH_NONE, "keyword", keyword);
        }

        return results;
    }

    /**
     * 获取 MCP 工具详情（_mcps_allowed、__locale 由工具上下文注入）
     *
     * <p>server_name 可选（schema 不设必填，框架会对必填参数做运行期校验）：为空时，边界内只有一个服务有该工具就自动补上；
     * 有多个时提示指定服务。
     *
     * @since 4.2
     */
    @ToolMapping(name = "get_tool_detail", description = "获取指定MCP工具的完整参数 Schema")
    public String getToolDetail(@Param(value = "server_name", required = false) String serverName, @Param("tool_name") String name,
                                List<String> _mcps_allowed, String __locale) {
        Locale locale = AiI18n.toLocale(__locale);
        if (Utils.isEmpty(name)) return McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_NAME_BLANK);

        Target target = resolveTarget(serverName, name, _mcps_allowed);
        if (target == null) {
            return McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_DETAIL_MISSING, "name", name);
        }

        if (target.tool == null) {
            return ambiguousText(locale, name, target.candidates);
        }

        return McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_DETAIL_FOUND,
                "server", target.server, "name", target.tool.name(), "description", target.tool.description()) +
                " \n```json\n" + target.tool.inputSchema() + "\n```";
    }

    /**
     * 列出服务下的工具（_mcps_allowed、_mcps_selected、__locale 由工具上下文注入）
     *
     * <p>属于发现：与 search_tools 一样按强制边界与视野收窄，越界或不在视野内的服务按「不存在」处理。
     * 服务名大小写不敏感；keyword 可选，在服务内按名称与描述过滤（多个关键词须全部命中）。
     * 单次最多返回 listToolsLimit 个，超出时提示用 keyword 缩小范围。
     *
     * @since 4.2.0
     */
    @ToolMapping(name = TOOL_LIST_SERVER, description = "列出指定MCP服务下的工具（名称与描述），可用 keyword 在服务内过滤")
    public Object listServerTools(@Param("server_name") String serverName,
                                  @Param(value = "keyword", required = false) String keyword,
                                  List<String> _mcps_allowed, List<String> _mcps_selected, String __locale) {
        Locale locale = AiI18n.toLocale(__locale);
        if (Utils.isBlank(serverName)) {
            return McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_LIST_SERVER_BLANK);
        }

        String server = canonicalServer(serverName);
        Map<String, FunctionTool> tools = server == null ? null : categoryTools.get(server);
        if (tools == null || isServerAllowed(server, _mcps_allowed) == false || isServerSelected(server, _mcps_selected) == false) {
            return McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_LIST_MISSING, "server", serverName);
        }

        String[] keys = Utils.isBlank(keyword) ? new String[0] : keyword.trim().toLowerCase().split("[\\s,;，；]+");
        List<Map<String, String>> matched = new ArrayList<>();
        for (FunctionTool t : tools.values()) {
            String content = (t.name() + " " + t.description()).toLowerCase();
            if (Arrays.stream(keys).allMatch(content::contains)) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("tool_name", t.name());
                row.put("tool_description", t.description());
                matched.add(row);
            }
        }

        if (matched.isEmpty()) {
            return McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_LIST_NONE, "server", server, "keyword", keyword);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("server_name", server);
        String description = briefDescription(server, DETAIL_DESCRIPTION_MAX);
        if (description != null) {
            result.put("server_description", description);
        }
        result.put("tool_count", matched.size());
        if (matched.size() > listToolsLimit) {
            result.put("tools", new ArrayList<>(matched.subList(0, listToolsLimit)));
            result.put("tip", McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_LIST_TRUNCATED,
                    "limit", listToolsLimit, "total", matched.size()));
        } else {
            result.put("tools", matched);
        }
        return result;
    }

    private static String ambiguousText(Locale locale, String toolName, List<String> candidates) {
        return McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_AMBIGUOUS,
                "name", toolName, "servers", String.join(", ", candidates));
    }

    //测试用
    public ToolResult callTool(String server_name,
                               String tool_name,
                               Map<String, Object> tool_args) {
        try {
            return callTool.call(Utils.asMap(
                    "server_name", server_name,
                    "tool_name", tool_name,
                    "tool_args", tool_args));
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    //测试用（不带服务名：工具名唯一时自动定位）
    public ToolResult callTool(String tool_name,
                               Map<String, Object> tool_args) {
        return callTool(null, tool_name, tool_args);
    }

    // ========== 内部中转代理工具 ==========

    public static class McpToolCallTool extends AbsTool {
        private final McpGatewayTalent gatewayTalent;

        public McpToolCallTool(McpGatewayTalent gatewayTalent) {
            this.gatewayTalent = gatewayTalent;

            // server_name 可选：为空且边界内工具名唯一时自动补上（兼容不带服务名的旧调用），重名时由返回值提示指定
            addParam("server_name", String.class, false, "");
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
            String server_name = textOf(args.get("server_name"));
            String tool_name = textOf(args.get("tool_name"));
            Map<String, Object> tool_args = (Map) args.get("tool_args");

            //传导工具上下文
            if (tool_args == null) {
                tool_args = new HashMap<>();
            } else {
                tool_args = new HashMap<>(tool_args); // 复制一份，避免修改原始参数
            }
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                String key = entry.getKey();
                if (!"server_name".equals(key) && !"tool_name".equals(key) && !"tool_args".equals(key)) {
                    tool_args.put(key, entry.getValue());
                }
            }
            final Map<String, Object> finalToolArgs = tool_args;

            //-------
            Locale locale = AiI18n.toLocale(args.get(AiI18n.ATTR_LOCALE));

            if (Utils.isEmpty(tool_name)) {
                return ToolResult.success(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_NAME_BLANK));
            }

            // selected 是视野不是权限：call_tool 是点名调用，不按视野硬控；
            // 但 _mcps_allowed 是强制边界，越界按「不存在」处理（不泄露其它租户服务是否存在）。
            Target target = gatewayTalent.resolveTarget(server_name, tool_name, getAllowed(args));
            if (target == null) {
                return ToolResult.success(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_CALL_MISSING, "name", tool_name));
            }

            if (target.tool == null) {
                return ToolResult.success(ambiguousText(locale, tool_name, target.candidates));
            }

            // 边界、视野、语言等框架键不转发给 MCP 服务（第三方服务不认识，也不应看到）
            finalToolArgs.remove(CTX_MCPS_ALLOWED);
            finalToolArgs.remove(CTX_MCPS_SELECTED);
            finalToolArgs.remove(AiI18n.ATTR_LOCALE);

            final FunctionTool tool = target.tool;
            try {
                return RetryUtil.callWithRetry(gatewayTalent.maxRetries, gatewayTalent.retryDelayMs, gatewayTalent.retryDelayMs * 4, () -> tool.call(finalToolArgs));
            } catch (Throwable e) {
                LOG.error("McpTool gateway execution failed: {}/{}", target.server, tool_name, e);
                return ToolResult.success(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_CALL_ERROR,
                        "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }

        @Nullable
        private static String textOf(@Nullable Object value) {
            return value == null ? null : value.toString();
        }
    }
}
