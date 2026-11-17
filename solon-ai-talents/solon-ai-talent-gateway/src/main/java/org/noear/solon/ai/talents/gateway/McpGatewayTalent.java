package org.noear.solon.ai.talents.gateway;

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
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP 网关：按配置名管理 MCP 服务，支持动态添加与移除。
 *
 * <p><b>四种发现模式</b>（按本次请求可见的工具数与服务数切换）：
 * <ol>
 *   <li>FULL (直接调用): 工具数 &lt;= dynamicThreshold，且没有跨服务同名工具。直接交付原始工具。</li>
 *   <li>SUMMARY (摘要): 工具数 &lt;= listThreshold。指令里按服务列出"参数签名 + 单行描述"，模型多数情况下直接 call_tool。</li>
 *   <li>CATALOG (服务目录): 工具数 &gt; listThreshold 且服务数 &lt;= catalogThreshold。只列"服务名 + 工具数 + 描述"，
 *       模型用 list_server_tools 下钻到服务内的工具（带参数签名），再 call_tool。</li>
 *   <li>SEARCH (搜索): 工具数 &gt; listThreshold 且服务数 &gt; catalogThreshold。完全折叠，模型用关键词检索。</li>
 * </ol>
 * 4.2.0 起去掉了只列工具名的 LIST 模式，{@link #searchThreshold(int)} 不再起作用。
 *
 * <p><b>按「服务 → 工具」定位</b>：不同服务可以有同名工具。发现结果带服务名，get_tool_detail / call_tool 用
 * server_name + tool_name 点名（server_name 为空且边界内工具名唯一时自动补上）。
 *
 * <p><b>请求级作用域</b>（宿主写进 Prompt 属性或工具上下文，两者都是服务名名单）：
 * <table border="1">
 *   <caption>视野与边界</caption>
 *   <tr><th></th><th>{@link #CTX_MCPS_SELECTED 视野} _mcps_selected</th><th>{@link #CTX_MCPS_ALLOWED 边界} _mcps_allowed</th></tr>
 *   <tr><td>含义</td><td>这次想用哪些服务（选择）</td><td>这次最多能用哪些服务（权限）</td></tr>
 *   <tr><td>影响</td><td>指令、搜索、list_server_tools（发现）</td><td>发现，以及 get_tool_detail / call_tool（点名使用）</td></tr>
 *   <tr><td>未设置</td><td>不限制</td><td>不限制</td></tr>
 *   <tr><td>空名单</td><td><b>不限制</b>（等同未选择）</td><td><b>全部禁止</b></td></tr>
 * </table>
 * 两者都设置时取交集。写入用 {@link #putSelected(Prompt, List)} / {@link #putAllowed(Prompt, List)}，值为 {@code List<String>}
 * （视野另外兼容数组与逗号分隔的字符串；边界与 SkillTalent 的 {@code _skills_allowed} 同一口径，只接受 {@code List<String>}）。
 * 中转工具方法里的 {@code _mcps_allowed} 等下划线参数由框架从工具上下文注入（不进参数 schema）。
 *
 * <p><b>服务描述</b>：配置的描述 &gt; 服务端握手上报的 description &gt; 服务端 instructions 的第一句；都没有时，
 * 目录这一行改为列出工具名。握手信息留有快照，连接断开时目录与指令不跳变。
 * instructions 全文是写给模型的使用说明（注明由服务提供、不能改变用户或系统的要求），只在一处给出：
 * FULL 与 SUMMARY 模式写进指令；CATALOG 与 SEARCH 模式由 list_server_tools 给出。
 *
 * <p><b>中转调用</b>（call_tool）只转发模型给的 tool_args，不转发工具上下文；失败返回报错结果（isError），
 * 工具报错或服务端报参数非法时附上完整参数定义；只在请求确定没送达时重试。
 *
 * @author noear 2026/5/29 created
 * @since 3.10
 */
public class McpGatewayTalent extends AbsTalent {
    private static final Logger LOG = LoggerFactory.getLogger(McpGatewayTalent.class);

    /**
     * 视野（选择）：本次请求想用的服务名单。只收窄发现（指令、搜索、list_server_tools），点名使用不受它限制；
     * 未设置或空名单表示不限制。
     */
    public static final String CTX_MCPS_SELECTED = "_mcps_selected";

    /**
     * 强制边界（权限，与「视野」不同）：键存在时，只有名单内的服务可见、可搜、可调用（含 {@code call_tool} 点名调用）；
     * <b>空名单表示全部禁止</b>。由宿主的请求级作用域写入。
     *
     * @since 4.2.0
     */
    public static final String CTX_MCPS_ALLOWED = "_mcps_allowed";

    static final String TOOL_SEARCH = "search_tools";
    static final String TOOL_DETAIL = "get_tool_detail";
    static final String TOOL_LIST_SERVER = "list_server_tools";

    /**
     * 读取视野（未设置时返回空名单 = 不限制）
     */
    public static List<String> getSelected(Prompt prompt) {
        if (prompt == null) {
            return Collections.emptyList();
        }

        return getSelected(prompt.attrs());
    }

    /**
     * 读取视野（未设置时返回空名单 = 不限制；值可以是 List、数组或逗号分隔的字符串）
     */
    public static List<String> getSelected(Map<String, Object> toolContext) {
        List<String> selected = GatewaySupport.namesOf(toolContext, CTX_MCPS_SELECTED);
        return selected == null ? Collections.emptyList() : selected;
    }

    /**
     * 写入视野（null 或空名单 = 不限制）
     */
    public static void putSelected(Prompt prompt, List<String> mcpServerNames) {
        prompt.attrPut(CTX_MCPS_SELECTED, mcpServerNames);
    }

    /**
     * 读取强制边界（未设置返回 null = 不限制；空名单 = 全部禁止）。
     *
     * <p>值必须是 {@code List<String>}（用 {@link #putAllowed(Prompt, List)} 写入），与 SkillTalent 的 {@code _skills_allowed} 同一口径。
     *
     * @since 4.2.0
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static List<String> getAllowed(@Nullable Map<String, Object> map) {
        return map == null ? null : (List<String>) map.get(CTX_MCPS_ALLOWED);
    }

    /**
     * 写入强制边界（空名单 = 全部禁止；要取消限制请不写这个键）
     *
     * @since 4.2.0
     */
    public static void putAllowed(Prompt prompt, List<String> mcpServerNames) {
        prompt.attrPut(CTX_MCPS_ALLOWED, mcpServerNames);
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
            return allowed;
        }

        List<String> view = new ArrayList<>();
        for (String s : selected) {
            if (allowed.contains(s)) {
                view.add(s);
            }
        }
        return view;
    }

    private final McpServerRegistry registry = new McpServerRegistry();

    // ---- 模式阈值（判定见 modeOf，工具数与服务数都按本次请求的可见范围统计）----
    //   工具数 <= dynamicThreshold 且没有跨服务同名工具 → FULL
    //   工具数 <= listThreshold                          → SUMMARY
    //   服务数 <= catalogThreshold                       → CATALOG
    //   其余                                             → SEARCH
    // 命名沿用网关与 SkillTalent 的旧约定：xxxThreshold 表示「超过它就进入 xxx 模式」，不是「xxx 模式的上限」
    // （dynamic = 进入动态发现，list = 进入 LIST）。MCP 网关 4.2.0 去掉了 LIST 模式，listThreshold 名字里的
    // list 已没有对应的模式，实际作用是 SUMMARY 的上限；名字是 4.1.1 已发布的 API，为兼容保留。
    // catalogThreshold 是 4.2.0 新增的，按模式本身命名，是 CATALOG 的上限，而且按服务数计。

    /** FULL 模式的工具数上限：超过它就改走网关中转（动态发现） */
    private int dynamicThreshold = 8;
    /** SUMMARY 模式的工具数上限：超过它就不再把工具清单写进指令，按服务数进入 CATALOG 或 SEARCH（名字由来见上） */
    private int listThreshold = 40;
    /** CATALOG 模式的<b>服务数</b>上限：超过它进入 SEARCH */
    private int catalogThreshold = 30;
    /**
     * list_server_tools 单个服务内带完整描述的工具数上限，与模式判定无关。
     * 这里的 list 指 list_server_tools 工具，和 listThreshold 的 list（LIST 模式）不是一回事
     */
    private int listToolsLimit = 30;

    private volatile int maxRetries = 3;
    private volatile long retryDelayMs = 1000L; // 默认初始延迟

    private final McpCallTool callTool;

    public McpGatewayTalent() {
        this.callTool = new McpCallTool(this);
        internalAddTool(callTool);
    }

    McpServerRegistry registry() {
        return registry;
    }

    int maxRetries() {
        return maxRetries;
    }

    long retryDelayMs() {
        return retryDelayMs;
    }

    /**
     * call_tool 的重试配置（总尝试次数与初始退避）。
     *
     * <p>只在请求确定没有送达服务端时重试（连接被拒、域名解析失败等）。服务端已应答的错误、超时、传输中断
     * 不重试：这时请求可能已经执行，对创建类等非幂等工具重发会重复执行，交给模型看到报错后自行决定。
     */
    public McpGatewayTalent retryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(100, retryDelayMs);
        return this;
    }

    /**
     * call_tool 的重试次数（语义同 {@link #retryConfig(int, long)}）
     */
    public McpGatewayTalent retryConfig(int maxRetries) {
        this.maxRetries = Math.max(1, maxRetries);
        return this;
    }

    /**
     * FULL（直接调用）模式的工具数上限（缺省 8）：可见工具不超过它、且没有跨服务同名工具时，直接交付原始工具
     */
    public McpGatewayTalent dynamicThreshold(int dynamicThreshold) {
        this.dynamicThreshold = dynamicThreshold;
        return this;
    }

    /**
     * SUMMARY（摘要）模式的工具数上限（缺省 40）：可见工具不超过它时，指令里按服务列出签名与描述；
     * 超过时按服务数进入 CATALOG 或 SEARCH（见 {@link #catalogThreshold(int)}）。
     *
     * <p>名字沿用「超过它就进入 LIST 模式」的旧约定（与 SkillTalent、OpenApi / Tool 网关同名方法一致）；
     * 4.2.0 起 MCP 网关没有 LIST 模式，这里的 list 已无对应的模式，按「SUMMARY 的上限」理解即可。
     */
    public McpGatewayTalent listThreshold(int listThreshold) {
        this.listThreshold = listThreshold;
        return this;
    }

    /**
     * 已不起作用：4.2.0 起 MCP 网关没有 LIST 模式，工具数超过 listThreshold 后按服务数进入服务目录或搜索模式
     * （见 {@link #catalogThreshold(int)}）。保留方法只为源码兼容。
     *
     * @deprecated 4.2.0
     */
    @Deprecated
    public McpGatewayTalent searchThreshold(int searchThreshold) {
        LOG.warn("McpGatewayTalent.searchThreshold is ignored since 4.2.0 (LIST mode removed), see listThreshold / catalogThreshold");
        return this;
    }

    /**
     * CATALOG（服务目录）模式的<b>服务数</b>上限（缺省 30）：工具数超过 listThreshold 时，
     * 可见服务不超过它就展示服务目录，否则进入 SEARCH（搜索）模式
     *
     * @since 4.2.0
     */
    public McpGatewayTalent catalogThreshold(int catalogThreshold) {
        this.catalogThreshold = catalogThreshold;
        return this;
    }

    /**
     * list_server_tools 带完整描述列出的<b>单个服务内</b>工具数上限（缺省 30）。
     *
     * <p>服务内工具（或 keyword 过滤后的结果）不超过它时每条带签名与描述；超过时每条只留签名与缩短的描述，
     * 一次给全，不让模型多走一轮；也有硬上限（它的 5 倍），再超出提示用 keyword 缩小范围。
     *
     * @since 4.2.0
     */
    public McpGatewayTalent listToolsLimit(int listToolsLimit) {
        this.listToolsLimit = Math.max(1, listToolsLimit);
        return this;
    }

    // ========== 添加 / 移除 / 刷新 ==========

    /**
     * 添加 MCP 服务（通过 McpClientProvider）；同名已存在时替换并关闭旧连接。
     *
     * <p>先用新连接握手、拉取工具，再整体换上：握手期间旧服务的工具仍可用。拉取失败时新服务照样登记（没有工具）。
     *
     * @param name 服务配置名（如 "weather"），用于后续按名移除，也是模型看到的服务名
     */
    public McpGatewayTalent addMcpServer(String name, McpClientProvider mcpProvider) {
        if (Utils.isEmpty(name) || mcpProvider == null) {
            return this;
        }

        registry.add(name, mcpProvider);
        return this;
    }

    /**
     * 添加 MCP 服务（通过 McpServerParameters，内部自动构建 Provider）；同名替换的语义同 {@link #addMcpServer(String, McpClientProvider)}。
     * 配置构建失败时也移除旧服务（不留与新配置不符的旧连接）。
     */
    public McpGatewayTalent addMcpServer(String name, McpServerParameters mcpParameters) {
        if (Utils.isEmpty(name) || mcpParameters == null) {
            return this;
        }

        final McpClientProvider mcpProvider;
        try {
            mcpProvider = McpClientProviders.fromMcpServer(mcpParameters);
            mcpProvider.setEnabled(mcpParameters.isEnabled());
        } catch (Throwable e) {
            LOG.error("Mcp server '{}' create failed", name, e);
            registry.remove(name);
            return this;
        }

        registry.add(name, mcpProvider);
        return this;
    }

    /**
     * 移除 MCP 服务（按配置名），并关闭连接
     *
     * <p>只移除该服务自己的工具：工具按「服务 → 工具」索引，其它服务的同名工具不受影响。
     */
    public McpGatewayTalent removeMcpServer(String name) {
        if (Utils.isEmpty(name) == false) {
            registry.remove(name);
        }
        return this;
    }

    /**
     * 刷新指定 MCP 服务的工具列表（McpClientProvider 的 allowedTools / disallowedTools 或启用状态变更后调用）
     *
     * <p>先建好新工具表再整表替换，调用中的线程不会看到半更新的状态；拉取失败时保留原表。
     */
    public McpGatewayTalent refreshMcpServer(String name) {
        if (Utils.isEmpty(name) == false) {
            registry.refresh(name);
        }
        return this;
    }

    /**
     * 刷新所有 MCP 服务的工具列表
     */
    public McpGatewayTalent refreshMcpServerAll() {
        // 拷贝快照再遍历，避免刷新期间并发修改
        for (String name : new ArrayList<>(registry.names())) {
            registry.refresh(name);
        }
        return this;
    }

    // ========== 查询 ==========

    /**
     * 获取已登记的 MCP 服务名集合（含停用的）
     */
    public Set<String> getMcpServerNames() {
        return registry.names();
    }

    /**
     * 获取指定名称的 MCP Provider（含停用的；名字精确匹配）
     */
    public McpClientProvider getMcpServer(String name) {
        return name == null ? null : registry.provider(name);
    }

    /**
     * 是否包含指定名称的 MCP 服务（含停用的；名字精确匹配）
     */
    public boolean hasMcpServer(String name) {
        return name != null && registry.has(name);
    }

    /**
     * 获取服务描述（服务名大小写不敏感；在全部已登记的服务里找，含停用的）
     *
     * <p>取值顺序：配置的描述 &gt; 服务端握手上报的 description &gt; 服务端 instructions 的第一句
     * （instructions 多是「先调 X 再调 Y」一类的使用说明，整段当描述容易误导选服务；全文另行给出）。
     * 服务端信息只读取已完成的握手结果，不会为此建立连接；连接已断开时用最近一次读到的快照。
     * 都没有或服务不存在时返回 null。
     *
     * @since 4.2.0
     */
    @Nullable
    public String getMcpServerDescription(@Nullable String name) {
        return registry.description(name);
    }

    /**
     * 获取服务下已索引的全部工具（服务名大小写不敏感；顺序同服务端；只读）。
     *
     * <p>只在有工具的服务里找：停用、加载失败或不存在的服务返回空集合。
     *
     * @since 4.2.0
     */
    public Collection<FunctionTool> getMcpServerTools(@Nullable String name) {
        if (Utils.isBlank(name)) {
            return Collections.emptyList();
        }

        String server = registry.canonicalServer(name);
        Map<String, FunctionTool> tools = server == null ? null : registry.toolsOf(server);
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
        List<String> servers = registry.serversOfTool(null, toolName);
        return servers.size() == 1 ? servers.get(0) : null;
    }

    /**
     * 定位工具的归属服务（结果按服务名排序；不考虑请求级作用域）
     *
     * @param serverName 服务名（大小写不敏感）；给出时只在该服务内找，结果至多一个；为空时返回拥有该工具的全部服务
     * @param toolName   工具名（大小写不敏感）
     * @return 归属服务名列表（找不到为空列表）
     * @since 4.2.0
     */
    public List<String> getServersOfTool(@Nullable String serverName, @Nullable String toolName) {
        return registry.serversOfTool(serverName, toolName);
    }

    // ========== Talent 接口实现（按模式路由） ==========

    /** 发现模式（按顺序判定，阈值见字段注释与 modeOf） */
    private enum Mode {
        /** 工具数 &lt;= dynamicThreshold 且没有跨服务同名工具：直接交付原始工具 */
        FULL,
        /** 工具数 &lt;= listThreshold：指令里按服务列出签名与描述 */
        SUMMARY,
        /** 服务数 &lt;= catalogThreshold：指令里只列服务目录，用 list_server_tools 下钻 */
        CATALOG,
        /** 其余：完全折叠，用 search_tools 检索 */
        SEARCH
    }

    // 指令、目录、搜索结果与详情提示里服务描述的最大字符数（单行，超出截断）
    private static final int BRIEF_DESCRIPTION_MAX = 150;
    // list_server_tools 返回里服务描述的最大字符数
    private static final int LIST_DESCRIPTION_MAX = 500;
    // 目录里没有描述的服务列出的工具名个数
    private static final int CATALOG_TOOL_NAMES = 12;
    // 发现结果里工具描述的最大字符数（压成一行；完整描述见 get_tool_detail）
    private static final int TOOL_DESCRIPTION_MAX = 200;
    // list_server_tools 工具过多（超过 listToolsLimit）时每条保留的短描述字符数
    private static final int COMPACT_DESCRIPTION_MAX = 60;
    // search_tools 单次最多列出的工具数
    private static final int SEARCH_LIMIT = 10;
    // list_server_tools 缩短描述时的上限：listToolsLimit 的倍数
    private static final int COMPACT_FACTOR = 5;

    /**
     * 网关描述：按本次请求的可见范围（视野与强制边界）统计服务数与工具数，
     * 只算有工具的服务（停用、加载失败的不算），与指令里的数字一致。
     */
    @Override
    public String description(Prompt prompt) {
        Map<String, Map<String, FunctionTool>> view = viewOf(prompt);
        return McpGatewayI18n.fmt(AiI18n.localeOf(prompt), McpGatewayI18nKeys.GATEWAY_MCP_DESCRIPTION,
                "servers", view.size(), "tools", countTools(view));
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return countTools(viewOf(prompt)) > 0;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        final Locale locale = AiI18n.localeOf(prompt);
        // 按服务名排序，保持指令稳定
        final Map<String, Map<String, FunctionTool>> view = new TreeMap<>(viewOf(prompt));
        final int size = countTools(view);
        StringBuilder sb = new StringBuilder();
        sb.append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_TITLE, "count", size)).append("\n");

        switch (modeOf(view, size)) {
            case FULL:
                // 没有下钻这一步，服务使用说明直接写进指令
                sb.append(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_FULL));
                for (String serverName : view.keySet()) {
                    appendInstructions(sb, locale, serverName);
                }
                break;
            case SUMMARY:
                // 每行是参数签名 + 单行描述（多数调用不必再查详情；描述压成一行，整段函数文档不会打乱列表）。
                // 没有下钻这一步，服务使用说明与 FULL 一样写进指令
                sb.append(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_SUMMARY)).append("\n");
                view.forEach((serverName, tools) -> {
                    sb.append("- **[").append(serverName).append("]**:");
                    String description = briefDescription(serverName, BRIEF_DESCRIPTION_MAX);
                    if (description != null) {
                        sb.append(" ").append(description);
                    }
                    sb.append("\n");
                    tools.values().forEach(t ->
                            sb.append("  - ").append(toolLine(t, TOOL_DESCRIPTION_MAX)).append("\n")
                    );
                });
                for (String serverName : view.keySet()) {
                    // 说明只有一句且已作为服务描述完整列在上面时不重复
                    if (registry.hasDistinctInstructions(serverName, BRIEF_DESCRIPTION_MAX)) {
                        if (sb.charAt(sb.length() - 1) == '\n') {
                            // 工具行自带换行，说明段前只空一行
                            sb.setLength(sb.length() - 1);
                        }
                        appendInstructions(sb, locale, serverName);
                    }
                }
                break;
            case CATALOG:
                sb.append(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_CATALOG)).append("\n");
                view.forEach((serverName, tools) -> {
                    String description = briefDescription(serverName, BRIEF_DESCRIPTION_MAX);
                    if (description == null) {
                        // 没有描述：这一行退化为工具名列表，多花些 token，但目录不失效
                        sb.append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_CATALOG_ITEM_NAMES,
                                "server", serverName, "count", tools.size(), "names", toolNames(tools, CATALOG_TOOL_NAMES))).append("\n");
                    } else {
                        sb.append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_CATALOG_ITEM,
                                "server", serverName, "count", tools.size(), "description", description)).append("\n");
                    }
                });
                break;
            default:
                sb.append(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_SEARCH));
                break;
        }

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        Map<String, Map<String, FunctionTool>> view = viewOf(prompt);
        int size = countTools(view);
        Mode mode = modeOf(view, size);

        if (mode == Mode.FULL) {
            // 直接交付原始工具（已保证没有同名工具）
            List<FunctionTool> tools = new ArrayList<>(size);
            for (Map<String, FunctionTool> group : view.values()) {
                tools.addAll(group.values());
            }
            return tools;
        }

        // 中转工具（描述是固定文本，不随请求语言变化；指令与返回文案按请求语言）
        Collection<FunctionTool> relays = super.getTools(prompt);

        if (mode == Mode.SUMMARY) {
            // 上下文已包含摘要，无需搜索与下钻，只需提供详情与代理工具
            return relays.stream()
                    .filter(f -> !TOOL_SEARCH.equals(f.name()) && !TOOL_LIST_SERVER.equals(f.name()))
                    .collect(Collectors.toList());
        }

        // CATALOG & SEARCH 模式：提供完整的中转工具集（含 search_tools 与 list_server_tools）
        return relays;
    }

    private Mode modeOf(Map<String, Map<String, FunctionTool>> view, int size) {
        if (size <= dynamicThreshold && hasDuplicateName(view) == false) {
            // 有跨服务同名工具时不走 FULL：直接调用只按工具名，无法区分服务
            return Mode.FULL;
        } else if (size <= listThreshold) {
            return Mode.SUMMARY;
        } else if (view.size() <= catalogThreshold) {
            return Mode.CATALOG;
        } else {
            return Mode.SEARCH;
        }
    }

    /**
     * 本次请求可见的服务（视野与强制边界的交集；都未设置时为全量）
     * <p>
     * 空视图<b>不</b>回退全量：选中一个不存在 / 已移除的服务时应当没有工具可用，
     * 回退全量会让「我明明只勾了一个服务」变成「全都开着」，属于静默越权。
     */
    private Map<String, Map<String, FunctionTool>> viewOf(@Nullable Prompt prompt) {
        Map<String, Map<String, FunctionTool>> all = registry.serverTools();
        List<String> selected = effectiveSelection(prompt);
        if (selected == null) {
            return all;
        }

        Map<String, Map<String, FunctionTool>> view = new LinkedHashMap<>();
        for (String serverName : selected) {
            Map<String, FunctionTool> tools = all.get(serverName);
            if (tools != null) {
                view.put(serverName, tools);
            }
        }
        return view;
    }

    /** 工具总数（按「服务 + 工具」计，同名工具分别计数） */
    private static int countTools(Map<String, Map<String, FunctionTool>> view) {
        int size = 0;
        for (Map<String, FunctionTool> tools : view.values()) {
            size += tools.size();
        }
        return size;
    }

    /** 视图里是否有跨服务的同名工具 */
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

    /** 服务是否在强制边界内（allowed 为 null 时不限制） */
    private static boolean isServerAllowed(String server, @Nullable List<String> allowed) {
        return allowed == null || allowed.contains(server);
    }

    /** 服务是否在视野内（未选择时不限制）。只用于发现，点名使用不受它限制 */
    private static boolean isServerSelected(String server, @Nullable List<String> selected) {
        return selected == null || selected.isEmpty() || selected.contains(server);
    }

    // ========== 渲染辅助 ==========

    /** 服务描述的单行短文本（没有描述时返回 null） */
    @Nullable
    private String briefDescription(String serverName, int max) {
        String description = registry.description(serverName);
        if (description == null) {
            return null;
        }

        String text = GatewaySupport.oneLine(description, max);
        return text.isEmpty() ? null : text;
    }

    /** 追加服务使用说明段（没有时不动）；前面空一行 */
    private void appendInstructions(StringBuilder sb, Locale locale, String server) {
        String instructions = registry.instructions(server,
                McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTIONS_TRUNCATED));
        if (instructions == null) {
            return;
        }

        sb.append("\n\n")
                .append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_SERVER_INSTRUCTIONS, "server", server))
                .append("\n").append(instructions);
    }

    /** 工具名列表（原始大小写，顺序同服务端），超过 max 个时以 ", …" 结尾 */
    private static String toolNames(Map<String, FunctionTool> tools, int max) {
        List<String> names = new ArrayList<>();
        for (FunctionTool tool : tools.values()) {
            if (names.size() >= max) {
                break;
            }
            names.add(tool.name());
        }

        String text = String.join(", ", names);
        return tools.size() > names.size() ? text + ", …" : text;
    }

    /** 发现结果里的一个工具：{@code `签名`: 描述}（描述压成一行、最多 descriptionMax 字；描述为空时只有签名） */
    private static String toolLine(FunctionTool tool, int descriptionMax) {
        String line = "`" + ToolSignature.of(tool) + "`";
        String description = GatewaySupport.oneLine(tool.description(), descriptionMax);
        if (description.isEmpty() == false) {
            line = line + ": " + description;
        }
        return line;
    }

    static String ambiguousText(Locale locale, String toolName, List<String> candidates) {
        return McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_AMBIGUOUS,
                "name", toolName, "servers", String.join(", ", candidates));
    }

    /** 工具名与描述是否命中全部关键词 */
    private static boolean matches(FunctionTool tool, String[] keys) {
        return GatewaySupport.containsAll(tool.name() + " " + (tool.description() == null ? "" : tool.description()), keys);
    }

    // ========== 中转工具方法（由 AbsTalent 的 MethodToolProvider 扫描；下划线参数由框架从工具上下文注入） ==========

    /**
     * 搜索 MCP 工具（_mcps_allowed、_mcps_selected、__locale 由工具上下文注入）
     *
     * <p>结果是文本行 {@code - [服务名] `签名`: 描述}（按服务名排序，服务内顺序同服务端，同名工具分别列出），
     * 最多 {@value #SEARCH_LIMIT} 条，超出时注明总数。带签名是为了让模型多数情况下搜完就能 call_tool。
     * 列出的服务里有使用说明的，末尾提示先用 list_server_tools 查看（search_tools 只在目录与搜索模式提供，
     * 这两种模式下全文只在 list_server_tools 里给出）。
     * 结果同时按强制边界与视野收窄（两者叠加即交集）；视野为空表示不限制。
     *
     * @since 4.2
     */
    @ToolMapping(name = TOOL_SEARCH, description = "跨服务搜索MCP工具（结果含服务名、参数签名与描述）。支持多个关键词用空格隔开（如：'杭州 旅游'）")
    public Object searchTools(@Param("keyword") String keyword, List<String> _mcps_allowed, List<String> _mcps_selected, String __locale) {
        Locale locale = AiI18n.toLocale(__locale);
        String[] keys = GatewaySupport.keywordsOf(keyword);
        if (keys.length == 0) return McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_SEARCH_BLANK);

        Map<String, Map<String, FunctionTool>> all = new TreeMap<>(registry.serverTools());
        List<String> lines = new ArrayList<>();
        Set<String> listedServers = new LinkedHashSet<>();
        int total = 0;
        for (Map.Entry<String, Map<String, FunctionTool>> entry : all.entrySet()) {
            String server = entry.getKey();
            if (isServerAllowed(server, _mcps_allowed) == false || isServerSelected(server, _mcps_selected) == false) {
                continue;
            }

            for (FunctionTool t : entry.getValue().values()) {
                if (matches(t, keys)) {
                    total++;
                    if (lines.size() < SEARCH_LIMIT) {
                        lines.add("- [" + server + "] " + toolLine(t, TOOL_DESCRIPTION_MAX));
                        listedServers.add(server);
                    }
                }
            }
        }

        if (lines.isEmpty()) {
            return McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_SEARCH_NONE, "keyword", keyword.trim());
        }

        StringBuilder sb = new StringBuilder();
        sb.append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_SEARCH_FOUND, "count", total, "keyword", keyword.trim()));
        for (String line : lines) {
            sb.append("\n").append(line);
        }
        if (total > lines.size()) {
            sb.append("\n\n").append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_SEARCH_MORE,
                    "limit", lines.size(), "total", total));
        }

        List<String> noted = new ArrayList<>();
        for (String server : listedServers) {
            // 模型只在目录里见过服务描述（截到 BRIEF_DESCRIPTION_MAX）
            if (registry.hasDistinctInstructions(server, BRIEF_DESCRIPTION_MAX)) {
                noted.add(server);
            }
        }
        if (noted.isEmpty() == false) {
            sb.append("\n\n").append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_SEARCH_NOTED,
                    "servers", String.join(", ", noted)));
        }
        return sb.toString();
    }

    /**
     * 获取 MCP 工具详情（_mcps_allowed、__locale 由工具上下文注入）
     *
     * <p>属于点名使用：只按强制边界，不按视野收窄。server_name 可选：为空时，边界内只有一个服务有该工具就自动补上；
     * 有多个时提示指定服务。
     *
     * <p>不带服务使用说明全文（查同一服务的几个工具会重复好几遍），有说明时只给一句指引：
     * 直接调用与摘要模式下全文在指令里，目录与搜索模式下在 list_server_tools 里。
     *
     * @since 4.2
     */
    @ToolMapping(name = TOOL_DETAIL, description = "获取指定MCP工具的完整参数 Schema")
    public String getToolDetail(@Param(value = "server_name", required = false) String serverName, @Param("tool_name") String name,
                                List<String> _mcps_allowed, String __locale) {
        Locale locale = AiI18n.toLocale(__locale);
        if (Utils.isBlank(name)) return McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_NAME_BLANK);

        McpServerRegistry.Target target = registry.resolveTarget(serverName, name, _mcps_allowed);
        if (target == null) {
            return McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_DETAIL_MISSING, "name", name);
        }

        if (target.tool == null) {
            return ambiguousText(locale, name, target.candidates);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_DETAIL_FOUND,
                "server", target.server, "name", target.tool.name(), "description", target.tool.description()))
                .append(" \n```json\n").append(target.tool.inputSchema()).append("\n```");
        if (registry.hasDistinctInstructions(target.server, BRIEF_DESCRIPTION_MAX)) {
            sb.append("\n\n").append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_DETAIL_NOTED,
                    "server", target.server));
        }
        return sb.toString();
    }

    /**
     * 列出服务下的工具（_mcps_allowed、_mcps_selected、__locale 由工具上下文注入）
     *
     * <p>属于发现：与 search_tools 一样按强制边界与视野收窄，越界或不在视野内的服务按「不存在」处理。
     * 服务名大小写不敏感；keyword 可选，在服务内按名称与描述过滤（多个关键词须全部命中）。
     *
     * <p>结果是文本：服务名与工具总数（过滤时另列命中数）、服务描述、服务使用说明（instructions 全文，
     * 与描述相同时不重复），再是工具行。命中不超过 listToolsLimit 时每行 {@code `签名`: 描述}；
     * 超过时描述缩短到 {@value #COMPACT_DESCRIPTION_MAX} 字（一次给全，省掉一轮调用），最多 listToolsLimit 的
     * {@value #COMPACT_FACTOR} 倍，再超出提示用 keyword 缩小范围。这是目录与搜索模式下给出使用说明全文的唯一位置，
     * 每次都给（不按会话去重：上下文压缩后模型要能重新拿到）。
     *
     * @since 4.2.0
     */
    @ToolMapping(name = TOOL_LIST_SERVER, description = "列出指定MCP服务下的工具（名称、参数签名与描述）及服务使用说明，可用 keyword 在服务内过滤")
    public Object listServerTools(@Param("server_name") String serverName,
                                  @Param(value = "keyword", required = false) String keyword,
                                  List<String> _mcps_allowed, List<String> _mcps_selected, String __locale) {
        Locale locale = AiI18n.toLocale(__locale);
        if (Utils.isBlank(serverName)) {
            return McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_LIST_SERVER_BLANK);
        }

        String server = registry.canonicalServer(serverName);
        Map<String, FunctionTool> tools = server == null ? null : registry.toolsOf(server);
        if (tools == null || isServerAllowed(server, _mcps_allowed) == false || isServerSelected(server, _mcps_selected) == false) {
            return McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_LIST_MISSING, "server", serverName);
        }

        String[] keys = GatewaySupport.keywordsOf(keyword);
        List<FunctionTool> matched = new ArrayList<>();
        for (FunctionTool t : tools.values()) {
            if (matches(t, keys)) {
                matched.add(t);
            }
        }

        if (matched.isEmpty()) {
            return McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_LIST_NONE, "server", server, "keyword", keyword.trim());
        }

        StringBuilder sb = new StringBuilder();
        if (keys.length == 0) {
            sb.append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_LIST_HEADER,
                    "server", server, "total", tools.size()));
        } else {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("server", server);
            vars.put("total", tools.size());
            vars.put("keyword", keyword.trim());
            vars.put("matched", matched.size());
            sb.append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_LIST_HEADER_MATCHED, vars));
        }

        String description = briefDescription(server, LIST_DESCRIPTION_MAX);
        if (description != null) {
            sb.append("\n").append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_LIST_DESCRIPTION,
                    "description", description));
        }

        // 使用说明与描述是同一句话时（如 instructions 只有一句）不重复给
        if (registry.hasDistinctInstructions(server, LIST_DESCRIPTION_MAX)) {
            appendInstructions(sb, locale, server);
        }

        sb.append("\n\n").append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_LIST_TOOLS, "server", server));

        boolean compact = matched.size() > listToolsLimit;
        int max = compact ? listToolsLimit * COMPACT_FACTOR : listToolsLimit;
        if (compact) {
            sb.append("\n").append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_LIST_COMPACT,
                    "limit", listToolsLimit));
        }

        int shown = 0;
        for (FunctionTool t : matched) {
            if (shown >= max) {
                break;
            }
            sb.append("\n- ").append(toolLine(t, compact ? COMPACT_DESCRIPTION_MAX : TOOL_DESCRIPTION_MAX));
            shown++;
        }

        if (matched.size() > shown) {
            sb.append("\n\n").append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_LIST_TRUNCATED,
                    "limit", shown, "total", matched.size()));
        }
        return sb.toString();
    }

    /**
     * 经网关调用 MCP 工具：与模型调用 call_tool 走同一路径（定位、参数整理、重试、失败附参数定义），
     * 不带工具上下文（因此不受请求级作用域限制），报错文案用默认语言。
     *
     * @param server_name 服务名（可为空：工具名在全部服务里唯一时自动定位）
     * @param tool_name   工具名
     * @param tool_args   参数（可为 null；不会被修改）
     */
    public ToolResult callTool(String server_name,
                               String tool_name,
                               Map<String, Object> tool_args) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(McpCallTool.ARG_SERVER_NAME, server_name);
        args.put(McpCallTool.ARG_TOOL_NAME, tool_name);
        args.put(McpCallTool.ARG_TOOL_ARGS, tool_args);
        return callTool.handle(args);
    }

    /**
     * 经网关调用 MCP 工具（不带服务名：工具名唯一时自动定位；语义同 {@link #callTool(String, String, Map)}）
     */
    public ToolResult callTool(String tool_name,
                               Map<String, Object> tool_args) {
        return callTool(null, tool_name, tool_args);
    }

    /**
     * 已废弃：4.1.1 误公开的内部中转工具。网关已自带 call_tool，不必再单独创建；
     * 要在代码里经网关调用工具，用 {@link McpGatewayTalent#callTool(String, String, Map)}。
     *
     * <p>保留只为源码兼容：行为委托给网关的 call_tool（4.2.0 语义：只转发 tool_args、失败返回报错结果）。
     *
     * @deprecated 4.2.0
     */
    @Deprecated
    public static class McpToolCallTool extends AbsTool {
        private final McpGatewayTalent gatewayTalent;

        public McpToolCallTool(McpGatewayTalent gatewayTalent) {
            this.gatewayTalent = gatewayTalent;

            addParam(McpCallTool.ARG_SERVER_NAME, String.class, false, "");
            addParam(McpCallTool.ARG_TOOL_NAME, String.class, "");
            addParam(McpCallTool.ARG_TOOL_ARGS, TypeRef.mapOf(String.class, Object.class).getType(), "");
        }

        @Override
        public String name() {
            return McpCallTool.NAME;
        }

        @Override
        public String description() {
            return gatewayTalent.callTool.description();
        }

        @Override
        public Object handle(Map<String, Object> args) {
            return gatewayTalent.callTool.handle(args);
        }
    }
}
