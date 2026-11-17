package org.noear.solon.ai.talents.gateway;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.snack4.ONode;
import org.noear.snack4.codec.TypeRef;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.TextBlock;
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
import org.noear.solon.ai.util.RetryTask;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MCP 网关：按配置名管理 MCP 服务，支持动态添加与移除。
 *
 * <p>通过工具数量（与服务数量）自动切换四种模式，平衡模型推理精度与 Token 消耗：
 * 1. FULL (全量): 数量 &lt;= dynamicThreshold（且没有跨服务同名工具）。平铺所有工具的完整 Schema。
 * 2. SUMMARY (摘要): 数量 &lt;= listThreshold。在指令内展示"参数签名 + 单行描述"清单，模型多数情况下可以直接 call_tool。
 * 3. CATALOG (服务目录): 数量 &gt; listThreshold 且服务数 &lt;= catalogThreshold。只展示"服务名 + 工具数 + 描述"，
 *    模型用 list_server_tools 下钻到服务内的工具（带参数签名），再 call_tool。
 * 4. SEARCH (搜索): 数量 &gt; listThreshold 且服务数 &gt; catalogThreshold。完全折叠，强制 LLM 使用关键词检索。
 *
 * <p>4.2.0 起去掉了只列工具名的 LIST 模式（服务目录加「没有描述就列工具名」已覆盖它），
 * {@link #searchThreshold(int)} 不再起作用。
 *
 * <p>服务描述取值：配置的描述（{@link McpClientProvider#getDescription()}）&gt; 服务端握手上报的 description &gt;
 * 服务端 instructions 的第一句；都没有时，服务目录这一行改为列出工具名。握手信息在添加、刷新与每次读取时留快照，
 * 连接断开（读不到握手信息）时用快照，目录与指令不随连接状态跳变。
 * instructions 全文是写给模型的使用说明（注明由服务提供、不能改变用户或系统的要求），只在一处给出全文：
 * FULL 与 SUMMARY 模式写进指令；CATALOG 与 SEARCH 模式由 list_server_tools 给出，search_tools 与 get_tool_detail
 * 只给一句指引，不重复全文。
 *
 * <p>发现结果（search_tools / list_server_tools）是文本行，每个工具带参数签名（{@link ToolSignature}），
 * 模型多数情况下可以直接 call_tool，签名表达不了的细节再用 get_tool_detail 查。
 * call_tool 失败（工具返回 isError，或服务端报参数非法）时，结果后附上完整参数定义，便于模型一次改对。
 * call_tool 自身的失败（未找到、重名、参数不是 JSON 对象、执行异常）一律返回报错结果（isError），与框架对工具异常的处理一致。
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

    // 服务端握手信息快照：服务名 -> 最近一次读到的握手描述与使用说明。
    // MCP 客户端出错后会清掉连接，到下次重连之前读不到握手信息；工具表却还在，没有快照时目录与指令会跟着跳变
    private final Map<String, ServerMeta> serverMetas = new ConcurrentHashMap<>();

    /** 握手信息快照（记下来源 provider：同名服务重新添加后，旧快照不再作数） */
    private static final class ServerMeta {
        final McpClientProvider provider;
        final String description;
        final String instructions;

        ServerMeta(McpClientProvider provider, String description, String instructions) {
            this.provider = provider;
            this.description = description;
            this.instructions = instructions;
        }
    }

    // 模式阈值
    private int dynamicThreshold = 8;
    private int listThreshold = 40;
    // 服务目录模式的服务数上限（超过则目录本身太长，退回搜索模式）
    private int catalogThreshold = 30;
    // list_server_tools 带完整描述列出的工具数上限（超出描述缩短，最多其 COMPACT_FACTOR 倍）
    private int listToolsLimit = 30;

    private int maxRetries = 3;
    private long retryDelayMs = 1000L; // 默认初始延迟

    private final McpToolCallTool callTool;

    public McpGatewayTalent() {
        this.callTool = new McpToolCallTool(this);
        internalAddTool(callTool);
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

    public McpGatewayTalent dynamicThreshold(int dynamicThreshold) {
        this.dynamicThreshold = dynamicThreshold;
        return this;
    }

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
     * 服务目录模式的服务数上限（工具数超过 listThreshold 时，服务数不超过它就展示服务目录，否则退回搜索模式）
     *
     * @since 4.2.0
     */
    public McpGatewayTalent catalogThreshold(int catalogThreshold) {
        this.catalogThreshold = catalogThreshold;
        return this;
    }

    /**
     * list_server_tools 带描述列出的工具数上限（缺省 30）。
     *
     * <p>服务内工具（或 keyword 过滤后的结果）不超过它时每条带签名与描述；超过时每条只留签名与缩短的描述，
     * 一次给全，不让模型多走一轮；只列签名也有硬上限（它的 5 倍），再超出提示用 keyword 缩小范围。
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
            // 拉取工具通常已完成握手，顺手留下握手信息快照
            serverMeta(name);

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

        // 1. 从工具索引中移除（连同握手信息快照）
        Map<String, FunctionTool> tools = categoryTools.remove(name);
        serverMetas.remove(name);

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
        serverMeta(name);

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
     * <p>取值顺序：配置的描述 &gt; 服务端握手上报的 description &gt; 服务端 instructions 的第一句
     * （instructions 多是「先调 X 再调 Y」一类的使用说明，整段当描述容易误导选服务；全文另在下钻时给出）。
     * 服务端信息只读取已完成的握手结果，不会为此建立连接；连接已断开时用最近一次读到的快照。
     * 都没有或服务不存在时返回 null。
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

        ServerMeta meta = serverMeta(server);
        if (meta == null) {
            return null;
        }

        if (Utils.isNotBlank(meta.description)) {
            return meta.description.trim();
        }

        if (Utils.isNotBlank(meta.instructions)) {
            String sentence = firstSentence(meta.instructions);
            if (sentence.isEmpty() == false) {
                return sentence;
            }
        }

        return null;
    }

    /**
     * 服务端握手信息：已连接时读实时值并更新快照；读不到（尚未连接，或出错后连接已被清掉）时用同一 provider 的快照。
     *
     * <p>已完成握手的服务一定上报 serverInfo（MCP 规范必填），所以 serverInfo 与 instructions 都为 null 就是「读不到」，
     * 不是「服务没有描述」。不会为此建立连接。
     *
     * @param server 规范服务名
     */
    @Nullable
    private ServerMeta serverMeta(String server) {
        McpClientProvider provider = providerMap.get(server);
        if (provider == null) {
            return null;
        }

        try {
            McpSchema.Implementation info = provider.getServerInfo();
            String instructions = provider.getServerInstructions();
            if (info != null || instructions != null) {
                ServerMeta meta = new ServerMeta(provider, info == null ? null : info.description(), instructions);
                serverMetas.put(server, meta);
                return meta;
            }
        } catch (Throwable e) {
            LOG.debug("Mcp server {}: read server info failed", server, e);
        }

        ServerMeta meta = serverMetas.get(server);
        return meta != null && meta.provider == provider ? meta : null;
    }

    /**
     * 取 instructions 的第一句当描述：只看第一段（空行之前），空白压成一行、去掉开头的 Markdown 标题符；
     * 遇到中文句末标点就切，英文只在「句末标点 + 空格 + 大写字母」处切，且前 {@value #FIRST_SENTENCE_MIN} 个字符内不切
     * （避开 e.g. / Inc. 这类缩写）。找不到切点时返回整段（指令里另有长度截断）。
     */
    private static String firstSentence(String text) {
        String paragraph = text.trim().split("\\n[ \\t]*\\n", 2)[0];
        String line = paragraph.replaceAll("\\s+", " ").trim().replaceFirst("^#+\\s*", "");
        int len = line.length();
        for (int i = 0; i < len; i++) {
            char c = line.charAt(i);
            if (c == '\u3002' || c == '\uff01' || c == '\uff1f') {
                return line.substring(0, i + 1);
            }

            if ((c == '.' || c == '!' || c == '?') && i + 1 >= FIRST_SENTENCE_MIN && i + 2 < len
                    && line.charAt(i + 1) == ' ' && Character.isUpperCase(line.charAt(i + 2))) {
                return line.substring(0, i + 1);
            }
        }
        return line;
    }

    /**
     * 服务端 instructions 全文（保留换行结构，去掉行尾空白与多余空行；超过 {@value #INSTRUCTIONS_MAX} 字截断并注明）。
     * 只读已完成的握手结果（断开时用快照）；没有时返回 null。
     *
     * @param server 规范服务名
     */
    @Nullable
    private String serverInstructions(String server, Locale locale) {
        ServerMeta meta = serverMeta(server);
        String text = meta == null ? null : meta.instructions;
        if (Utils.isBlank(text)) {
            return null;
        }

        String block = text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (block.length() > INSTRUCTIONS_MAX) {
            block = cut(block, INSTRUCTIONS_MAX).trim() + "…\n"
                    + McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTIONS_TRUNCATED);
        }
        return block;
    }

    /** 追加服务使用说明段（没有时不动）；前面空一行 */
    private void appendInstructions(StringBuilder sb, Locale locale, String server, @Nullable String instructions) {
        if (instructions == null) {
            return;
        }

        sb.append("\n\n")
                .append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_SERVER_INSTRUCTIONS, "server", server))
                .append("\n").append(instructions);
    }

    /** 按字符数截取，不拆开代理对（emoji 等） */
    private static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }

        int end = max;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /** 压成一行并截断（超出以 … 结尾）；null 视为空串 */
    private static String oneLine(@Nullable String text, int max) {
        if (text == null) {
            return "";
        }

        String line = text.replaceAll("\\s+", " ").trim();
        return line.length() > max ? cut(line, max) + "…" : line;
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

    // ========== Talent 接口实现（按模式路由） ==========

    /**
     * 网关描述：按本次请求的可见范围（视野与强制边界）统计服务数与工具数，
     * 只算有工具的服务（停用、加载失败的不算），与指令里的数字一致。
     */
    @Override
    public String description(Prompt prompt) {
        Map<String, Map<String, FunctionTool>> view = viewCategories(prompt);
        return McpGatewayI18n.fmt(AiI18n.localeOf(prompt), McpGatewayI18nKeys.GATEWAY_MCP_DESCRIPTION,
                "servers", view.size(), "tools", countTools(view));
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
                // --- FULL 模式 (全铺)：没有下钻这一步，服务使用说明直接写进指令（按服务名排序，保持指令稳定） ---
                sb.append(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_FULL));
                for (String serverName : new TreeSet<>(view.keySet())) {
                    appendInstructions(sb, locale, serverName, serverInstructions(serverName, locale));
                }
                break;
            case SUMMARY:
                // --- SUMMARY 模式 (摘要：按服务名排序，保持指令稳定) ---
                // 每行是参数签名 + 单行描述（多数调用不必再查详情；描述压成一行，整段函数文档不会打乱列表）。
                // 没有下钻这一步，服务使用说明与 FULL 一样写进指令
                sb.append(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_SUMMARY)).append("\n");
                Map<String, Map<String, FunctionTool>> sorted = new TreeMap<>(view);
                sorted.forEach((serverName, tools) -> {
                    sb.append("- **[").append(serverName).append("]**:");
                    String description = briefDescription(serverName, CATALOG_DESCRIPTION_MAX);
                    if (description != null) {
                        sb.append(" ").append(description);
                    }
                    sb.append("\n");
                    tools.values().forEach(t ->
                            sb.append("  - ").append(toolLine(t, TOOL_DESCRIPTION_MAX)).append("\n")
                    );
                });
                for (String serverName : sorted.keySet()) {
                    // 说明只有一句且已作为服务描述完整列在上面时不重复
                    if (hasDistinctInstructions(serverName, CATALOG_DESCRIPTION_MAX)) {
                        if (sb.charAt(sb.length() - 1) == '\n') {
                            // 工具行自带换行，说明段前只空一行
                            sb.setLength(sb.length() - 1);
                        }
                        appendInstructions(sb, locale, serverName, serverInstructions(serverName, locale));
                    }
                }
                break;
            case CATALOG:
                // --- CATALOG 模式 (服务目录：按服务名排序，保持指令稳定) ---
                sb.append(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_CATALOG)).append("\n");
                new TreeMap<>(view).forEach((serverName, tools) -> {
                    String description = briefDescription(serverName, CATALOG_DESCRIPTION_MAX);
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
                // --- SEARCH 模式 (搜索) ---
                sb.append(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_INSTRUCTION_SEARCH));
                break;
        }

        return sb.toString();
    }

    /** 发现模式 */
    private enum Mode {
        FULL, SUMMARY, CATALOG, SEARCH
    }

    private Mode modeOf(Map<String, Map<String, FunctionTool>> view, int size) {
        if (isFullMode(view, size)) {
            return Mode.FULL;
        } else if (size <= listThreshold) {
            return Mode.SUMMARY;
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
    // 目录里没有描述的服务列出的工具名个数
    private static final int CATALOG_TOOL_NAMES = 12;
    // 发现结果里工具描述的最大字符数（压成一行；完整描述见 get_tool_detail）
    private static final int TOOL_DESCRIPTION_MAX = 200;
    // list_server_tools 工具过多（超过 listToolsLimit）时每条保留的短描述字符数
    private static final int COMPACT_DESCRIPTION_MAX = 60;
    // 服务使用说明（instructions）的最大字符数
    private static final int INSTRUCTIONS_MAX = 2000;
    // instructions 取首句时，英文句点至少要在这么多字符之后才切（避开开头的缩写）
    private static final int FIRST_SENTENCE_MIN = 20;
    // search_tools 单次最多列出的工具数
    private static final int SEARCH_LIMIT = 10;
    // list_server_tools 只列签名时的上限：listToolsLimit 的倍数
    private static final int COMPACT_FACTOR = 5;

    /** 服务描述的单行短文本（没有描述时返回 null） */
    @Nullable
    private String briefDescription(String serverName, int max) {
        String description = getMcpServerDescription(serverName);
        if (description == null) {
            return null;
        }

        String text = oneLine(description, max);
        return text.isEmpty() ? null : text;
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
        String description = oneLine(tool.description(), descriptionMax);
        if (description.isEmpty() == false) {
            line = line + ": " + description;
        }
        return line;
    }

    /**
     * 服务是否有需要单独给出的使用说明。说明只有一句、就是服务描述，而且描述是完整给出的（不超过 shownMax，没被截断）时不算；
     * 描述被截断时（如整段没有句末标点的长说明）仍要给全文。
     *
     * @param shownMax 服务描述在模型已看到的位置里的截断长度
     */
    private boolean hasDistinctInstructions(String server, int shownMax) {
        ServerMeta meta = serverMeta(server);
        if (meta == null || Utils.isBlank(meta.instructions)) {
            return false;
        }

        String description = getMcpServerDescription(server);
        if (description == null) {
            return true;
        }

        String text = oneLine(meta.instructions, Integer.MAX_VALUE);
        return text.length() > shownMax || text.equals(oneLine(description, Integer.MAX_VALUE)) == false;
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
     * <p>结果是文本行 {@code - [服务名] `签名`: 描述}（按服务名排序，服务内顺序同服务端，同名工具分别列出），
     * 最多 {@value #SEARCH_LIMIT} 条，超出时注明总数。带签名是为了让模型多数情况下搜完就能 call_tool。
     * 列出的服务里有使用说明的，末尾提示先用 list_server_tools 查看（search_tools 只在目录与搜索模式提供，
     * 这两种模式下全文只在 list_server_tools 里给出）。
     * 结果同时按强制边界与视野收窄（两者叠加即交集）；视野为空表示不限制。
     * get_tool_detail / call_tool 属于点名使用，不按视野收窄。
     *
     * @since 4.2
     */
    @ToolMapping(name = TOOL_SEARCH, description = "跨服务搜索MCP工具（结果含服务名、参数签名与描述）。支持多个关键词用空格隔开（如：'杭州 旅游'）")
    public Object searchTools(@Param("keyword") String keyword, List<String> _mcps_allowed, List<String> _mcps_selected, String __locale) {
        Locale locale = AiI18n.toLocale(__locale);
        String[] keys = keywordsOf(keyword);
        if (keys.length == 0) return McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_SEARCH_BLANK);

        List<String> lines = new ArrayList<>();
        Set<String> listedServers = new LinkedHashSet<>();
        int total = 0;
        for (String server : new TreeSet<>(categoryTools.keySet())) {
            Map<String, FunctionTool> tools = categoryTools.get(server);
            if (tools == null || isServerAllowed(server, _mcps_allowed) == false || isServerSelected(server, _mcps_selected) == false) {
                continue;
            }

            for (FunctionTool t : tools.values()) {
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
            // 模型只在目录里见过服务描述（截到 CATALOG_DESCRIPTION_MAX）
            if (hasDistinctInstructions(server, CATALOG_DESCRIPTION_MAX)) {
                noted.add(server);
            }
        }
        if (noted.isEmpty() == false) {
            sb.append("\n\n").append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_SEARCH_NOTED,
                    "servers", String.join(", ", noted)));
        }
        return sb.toString();
    }

    /** 关键词切分（空白与中英文逗号、分号都算分隔，忽略空项）；没有关键词时返回空数组 */
    private static String[] keywordsOf(@Nullable String keyword) {
        if (Utils.isBlank(keyword)) {
            return new String[0];
        }

        List<String> keys = new ArrayList<>();
        for (String key : keyword.trim().toLowerCase().split("[\\s,;，；]+")) {
            if (key.isEmpty() == false) {
                keys.add(key);
            }
        }
        return keys.toArray(new String[0]);
    }

    /** 工具名与描述是否命中全部关键词 */
    private static boolean matches(FunctionTool tool, String[] keys) {
        String content = (tool.name() + " " + (tool.description() == null ? "" : tool.description())).toLowerCase();
        for (String key : keys) {
            if (content.contains(key) == false) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取 MCP 工具详情（_mcps_allowed、__locale 由工具上下文注入）
     *
     * <p>server_name 可选（schema 不设必填，框架会对必填参数做运行期校验）：为空时，边界内只有一个服务有该工具就自动补上；
     * 有多个时提示指定服务。
     *
     * <p>不带服务使用说明全文（查同一服务的几个工具会重复好几遍），有说明时只给一句指引：
     * 直接调用与摘要模式下全文在指令里，目录与搜索模式下在 list_server_tools 里。
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

        StringBuilder sb = new StringBuilder();
        sb.append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_DETAIL_FOUND,
                "server", target.server, "name", target.tool.name(), "description", target.tool.description()))
                .append(" \n```json\n").append(target.tool.inputSchema()).append("\n```");
        if (hasDistinctInstructions(target.server, CATALOG_DESCRIPTION_MAX)) {
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

        String server = canonicalServer(serverName);
        Map<String, FunctionTool> tools = server == null ? null : categoryTools.get(server);
        if (tools == null || isServerAllowed(server, _mcps_allowed) == false || isServerSelected(server, _mcps_selected) == false) {
            return McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_LIST_MISSING, "server", serverName);
        }

        String[] keys = keywordsOf(keyword);
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

        String description = briefDescription(server, DETAIL_DESCRIPTION_MAX);
        if (description != null) {
            sb.append("\n").append(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_LIST_DESCRIPTION,
                    "description", description));
        }

        // 使用说明与描述是同一句话时（如 instructions 只有一句）不重复给
        if (hasDistinctInstructions(server, DETAIL_DESCRIPTION_MAX)) {
            appendInstructions(sb, locale, server, serverInstructions(server, locale));
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

            //-------
            Locale locale = AiI18n.toLocale(args.get(AiI18n.ATTR_LOCALE));

            // 调用本身的失败一律返回报错结果（与框架对未找到工具、工具异常的处理一致），客户端据此标红
            if (Utils.isEmpty(tool_name)) {
                return ToolResult.error(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_NAME_BLANK));
            }

            // selected 是视野不是权限：call_tool 是点名调用，不按视野硬控；
            // 但 _mcps_allowed 是强制边界，越界按「不存在」处理（不泄露其它租户服务是否存在）。
            Target target = gatewayTalent.resolveTarget(server_name, tool_name, getAllowed(args));
            if (target == null) {
                return ToolResult.error(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_CALL_MISSING, "name", tool_name));
            }

            if (target.tool == null) {
                return ToolResult.error(ambiguousText(locale, tool_name, target.candidates));
            }

            final FunctionTool tool = target.tool;

            // 只转发模型给的参数（复制一份，避免修改原始参数）。args 里其余的键是工具上下文
            // （会话 id、工作目录、边界与视野、语言等），第三方 MCP 服务不认识也不应看到；
            // 严格校验参数的服务还会因多出的键直接报参数非法
            final Map<String, Object> finalToolArgs = toolArgsOf(args.get("tool_args"));
            if (finalToolArgs == null) {
                return ToolResult.error(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_CALL_ARGS_INVALID)
                        + "\n\n" + schemaHint(locale, target.server, tool));
            }

            try {
                ToolResult result = new RetryTask()
                        .maxRetries(gatewayTalent.maxRetries)
                        .initialDelayMs(gatewayTalent.retryDelayMs)
                        .maxDelayMs(gatewayTalent.retryDelayMs * 4)
                        .retryIf(McpToolCallTool::isNotDelivered)
                        .callWithRetry(() -> tool.call(finalToolArgs));
                if (result != null && result.isError()) {
                    // 工具报错（多半是参数不对）：附上完整参数定义，模型对照一次就能改对，不必再查详情
                    return withSchemaHint(result, schemaHint(locale, target.server, tool));
                }
                return result;
            } catch (Throwable e) {
                LOG.error("McpTool gateway execution failed: {}/{}", target.server, tool_name, e);
                String text = McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_CALL_ERROR,
                        "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                if (isInvalidParams(e)) {
                    // 只在服务端明确报「参数非法」时附定义；超时、断连等与参数无关，不加干扰信息
                    text = text + "\n\n" + schemaHint(locale, target.server, tool);
                }
                return ToolResult.error(text);
            }
        }

        /**
         * 模型给的 tool_args 转为参数表（副本）。缺省或空串为空表；有的模型会把对象序列化成 JSON 字符串传来，
         * 这里解析一次；解析不出 JSON 对象（数组、数字、写坏的 JSON 等）返回 null。
         */
        @Nullable
        @SuppressWarnings("unchecked")
        static Map<String, Object> toolArgsOf(@Nullable Object raw) {
            if (raw == null) {
                return new LinkedHashMap<>();
            }

            if (raw instanceof Map) {
                return new LinkedHashMap<>((Map<String, Object>) raw);
            }

            if (raw instanceof String) {
                String json = ((String) raw).trim();
                if (json.isEmpty()) {
                    return new LinkedHashMap<>();
                }

                try {
                    ONode node = ONode.ofJson(json);
                    if (node.isObject()) {
                        return new LinkedHashMap<>((Map<String, Object>) node.toBean(Map.class));
                    }
                } catch (Throwable e) {
                    LOG.debug("McpTool gateway: tool_args is not a JSON object: {}", json, e);
                }
            }

            return null;
        }

        /**
         * 请求是否确定没有送达服务端（连接被拒、域名解析失败、无路由），只有这类失败重试才安全。
         *
         * <p>服务端已应答的错误（{@link McpError}）重试结果不变；超时、传输中断、5xx 时请求可能已经执行，
         * 对创建、转账一类非幂等工具重发会重复执行（MCP 客户端内层对传输错误已有一次重连重发），不再叠加。
         */
        static boolean isNotDelivered(Throwable e) {
            int depth = 0;
            for (Throwable t = e; t != null && depth < 10; t = t.getCause(), depth++) {
                if (t instanceof McpError) {
                    return false;
                }

                if (t instanceof ConnectException || t instanceof UnknownHostException || t instanceof NoRouteToHostException) {
                    return true;
                }

                String message = t.getMessage();
                if (message != null && message.contains("Connection refused")) {
                    return true;
                }
            }
            return false;
        }

        /** 异常链里是否有 JSON-RPC「参数非法」（-32602） */
        private static boolean isInvalidParams(Throwable e) {
            int depth = 0;
            for (Throwable t = e; t != null && depth < 10; t = t.getCause(), depth++) {
                if (t instanceof McpError) {
                    McpSchema.JSONRPCResponse.JSONRPCError error = ((McpError) t).getJsonRpcError();
                    return error != null && error.code() == McpSchema.ErrorCodes.INVALID_PARAMS;
                }
            }
            return false;
        }

        /** 参数定义提示：说明 + 完整 inputSchema */
        private static String schemaHint(Locale locale, String server, FunctionTool tool) {
            return McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_CALL_SCHEMA_HINT, "server", server, "name", tool.name())
                    + "\n```json\n" + tool.inputSchema() + "\n```";
        }

        /**
         * 在报错结果后附上提示。重建结果而不是 addText：部分适配只取第一个文本块（getContent），
         * 追加的块会被丢掉。文本块合并成一段，媒体块、报错标记与 metas 原样保留。
         */
        private static ToolResult withSchemaHint(ToolResult result, String hint) {
            StringBuilder text = new StringBuilder();
            List<ContentBlock> others = new ArrayList<>();
            for (ContentBlock block : result.getBlocks()) {
                if (block instanceof TextBlock) {
                    if (text.length() > 0) {
                        text.append("\n");
                    }
                    text.append(block.getContent());
                } else {
                    others.add(block);
                }
            }

            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(hint);

            ToolResult out = new ToolResult(text.toString());
            out.addBlocks(others);
            out.setError(true);
            out.metas().putAll(result.metas());
            return out;
        }

        @Nullable
        private static String textOf(@Nullable Object value) {
            return value == null ? null : value.toString();
        }
    }
}
