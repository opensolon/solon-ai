package org.noear.solon.ai.talents.gateway;

import org.noear.snack4.codec.TypeRef;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.tool.AbsTool;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.talents.gateway.i18n.ToolGatewayI18n;
import org.noear.solon.ai.talents.gateway.i18n.ToolGatewayI18nKeys;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool 网关：解决工具过多导致的上下文溢出（Context Overflow）问题。
 *
 * <p>通过工具数量自动切换四种模式，平衡模型推理精度与 Token 消耗：
 * <ol>
 *   <li>FULL (直接调用): 工具数 &lt;= dynamicThreshold。直接交付原始工具。</li>
 *   <li>SUMMARY (摘要): 工具数 &lt;= listThreshold。在指令内展示"工具名 + 描述"清单。</li>
 *   <li>LIST (名字): 工具数 &lt;= searchThreshold。仅展示"工具名"清单。</li>
 *   <li>SEARCH (搜索): 工具数 &gt; searchThreshold。完全折叠，模型用关键词检索。</li>
 * </ol>
 *
 * <p>中转工具为 {@value #TOOL_SEARCH}、{@value #TOOL_DETAIL}、{@value #TOOL_CALL}（4.2.0 起改名，
 * 原名 search_tools / get_tool_detail / call_tool 与 MCP 网关同名，挂在同一个 Agent 上会互相覆盖）。
 * 同类网关每个 Agent 只挂一个（同名中转工具同样会覆盖），工具多时往同一个网关里加。
 *
 * <p>工具按名字（大小写不敏感）唯一：同名工具后加的替换先加的（也会从原分组移走）。
 * 运行中可以添加、移除工具：索引写时复制，读取总是看到完整的一版。
 *
 * <p>中转调用本地工具时会把工具上下文（会话 id、工作目录、语言等，模型看不到的键）一并传给目标工具：
 * 本地工具需要这些上下文；同名键以上下文为准，模型不能伪造。这一点与 MCP 网关相反（第三方服务不应看到上下文）。
 *
 * <p>中转调用缺省不重试（见 {@link #retryConfig(int, long)}）：本地工具可能包含多步操作，网关判断不了哪一步失败、
 * 前面的步骤是否已经生效，重试应由工具自己负责。
 *
 * @author noear
 * @since 3.9.5
 */
public class ToolGatewayTalent extends AbsTalent {
    private static final Logger LOG = LoggerFactory.getLogger(ToolGatewayTalent.class);

    static final String TOOL_SEARCH = "search_biz_tools";
    static final String TOOL_DETAIL = "get_biz_tool_detail";
    static final String TOOL_CALL = "call_biz_tool";

    private static final String ARG_TOOL_NAME = "tool_name";
    private static final String ARG_TOOL_ARGS = "tool_args";

    // search 单次最多列出的工具数
    private static final int SEARCH_LIMIT = 10;

    /** 工具索引（写时复制：每次修改都换一个新的不可变实例） */
    private static final class Index {
        static final Index EMPTY = new Index(Collections.emptyMap(), Collections.emptyMap());

        // 分组名 -> (工具名小写 -> 工具)
        final Map<String, Map<String, FunctionTool>> categories;
        // 工具名小写 -> 工具
        final Map<String, FunctionTool> all;

        Index(Map<String, Map<String, FunctionTool>> categories, Map<String, FunctionTool> all) {
            this.categories = categories;
            this.all = all;
        }
    }

    private volatile Index index = Index.EMPTY;
    private final Object indexLock = new Object();

    private int dynamicThreshold = 8;   // FULL 模式的工具数上限
    private int listThreshold = 40;     // SUMMARY 模式的工具数上限
    private int searchThreshold = 100;  // LIST 模式的工具数上限（超过则 SEARCH）

    // 缺省只尝试 1 次（不重试），见 retryConfig
    private volatile int maxRetries = 1;
    private volatile long retryDelayMs = 1000L;

    private final BizCallTool callTool;

    public ToolGatewayTalent() {
        this.callTool = new BizCallTool(this);
        internalAddTool(callTool);
    }

    /**
     * 中转调用的重试配置（总尝试次数与初始退避；缺省 1 次，即不重试）。
     *
     * <p>与 MCP、OpenAPI 网关不同，这里缺省不重试：目标是本地工具，一个工具里可能先写库、再调远程服务，
     * 远程服务连不上时异常链里也会出现「连接被拒」，网关据此重跑整个工具会让前面的步骤重复执行。
     * 只有确认目标工具整体幂等时才配置大于 1：此时也只在异常链里有连接被拒、域名解析失败、连接超时等「没送达」类异常时重试，
     * 工具抛出的其它异常不重试。更推荐由工具自己在出错的那一步重试。
     */
    public ToolGatewayTalent retryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(100, retryDelayMs);
        return this;
    }

    /**
     * 中转调用的重试次数（语义与缺省值同 {@link #retryConfig(int, long)}）
     */
    public ToolGatewayTalent retryConfig(int maxRetries) {
        this.maxRetries = Math.max(1, maxRetries);
        return this;
    }

    /**
     * FULL（直接调用）模式的工具数上限（缺省 8）：不超过它时直接交付原始工具
     */
    public ToolGatewayTalent dynamicThreshold(int dynamicThreshold) {
        this.dynamicThreshold = dynamicThreshold;
        return this;
    }

    /**
     * SUMMARY（摘要）模式的工具数上限（缺省 40）：不超过它时在指令里列出"工具名 + 描述"
     */
    public ToolGatewayTalent listThreshold(int listThreshold) {
        this.listThreshold = listThreshold;
        return this;
    }

    /**
     * LIST（只列名字）模式的工具数上限（缺省 100）：不超过它时只列工具名，超过则进入 SEARCH（搜索）模式
     */
    public ToolGatewayTalent searchThreshold(int searchThreshold) {
        this.searchThreshold = searchThreshold;
        return this;
    }

    /**
     * 添加工具包（归入默认分组）
     */
    public ToolGatewayTalent addTool(ToolProvider toolProvider) {
        return addTool(null, toolProvider);
    }

    /**
     * 添加工具包
     *
     * @param category 分组名（为空时归入默认分组）
     */
    public ToolGatewayTalent addTool(String category, ToolProvider toolProvider) {
        if (toolProvider != null) {
            putTools(category, toolProvider.getTools());
        }
        return this;
    }

    /**
     * 添加单个工具（归入默认分组；按名字大小写不敏感去重，同名替换）
     */
    public ToolGatewayTalent addTool(FunctionTool tool) {
        return addTool(null, tool);
    }

    /**
     * 添加单个工具（按名字大小写不敏感去重，同名替换）
     *
     * @param category 分组名（为空时归入默认分组）
     */
    public ToolGatewayTalent addTool(String category, FunctionTool tool) {
        if (tool != null) {
            putTools(category, Collections.singletonList(tool));
        }
        return this;
    }

    /**
     * 移除工具（名字大小写不敏感；不存在时是空操作）
     *
     * @since 4.2.0
     */
    public ToolGatewayTalent removeTool(String toolName) {
        if (Utils.isBlank(toolName)) {
            return this;
        }

        String key = toolName.trim().toLowerCase();
        synchronized (indexLock) {
            Index old = index;
            if (old.all.containsKey(key) == false) {
                return this;
            }

            Map<String, Map<String, FunctionTool>> categories = copyCategories(old.categories);
            Map<String, FunctionTool> all = new LinkedHashMap<>(old.all);
            all.remove(key);
            removeFromCategories(categories, key);
            index = freeze(categories, all);
        }
        return this;
    }

    private void putTools(String category, Collection<FunctionTool> tools) {
        //默认分组按默认语言登记，展示时再按请求语言换成对应文案（见 categoryOf）
        String catName = Utils.isEmpty(category) ? ToolGatewayI18n.get(null, ToolGatewayI18nKeys.GATEWAY_TOOL_DEFAULT_CATEGORY) : category;

        synchronized (indexLock) {
            Index old = index;
            Map<String, Map<String, FunctionTool>> categories = copyCategories(old.categories);
            Map<String, FunctionTool> all = new LinkedHashMap<>(old.all);

            for (FunctionTool tool : tools) {
                if (tool == null) {
                    continue;
                }

                String key = tool.name().toLowerCase();
                // 同名工具换了分组时，从原分组移走（否则会在两个分组里各列一次）
                removeFromCategories(categories, key);
                categories.computeIfAbsent(catName, k -> new LinkedHashMap<>()).put(key, tool);
                all.put(key, tool);
            }

            index = freeze(categories, all);
        }
    }

    private static Map<String, Map<String, FunctionTool>> copyCategories(Map<String, Map<String, FunctionTool>> src) {
        Map<String, Map<String, FunctionTool>> copy = new LinkedHashMap<>();
        src.forEach((cat, tools) -> copy.put(cat, new LinkedHashMap<>(tools)));
        return copy;
    }

    private static void removeFromCategories(Map<String, Map<String, FunctionTool>> categories, String key) {
        Iterator<Map.Entry<String, Map<String, FunctionTool>>> it = categories.entrySet().iterator();
        while (it.hasNext()) {
            Map<String, FunctionTool> tools = it.next().getValue();
            if (tools.remove(key) != null && tools.isEmpty()) {
                it.remove();
            }
        }
    }

    private static Index freeze(Map<String, Map<String, FunctionTool>> categories, Map<String, FunctionTool> all) {
        Map<String, Map<String, FunctionTool>> frozen = new LinkedHashMap<>();
        categories.forEach((cat, tools) -> frozen.put(cat, Collections.unmodifiableMap(tools)));
        return new Index(Collections.unmodifiableMap(frozen), Collections.unmodifiableMap(all));
    }

    @Override
    public String description(Prompt prompt) {
        return ToolGatewayI18n.get(AiI18n.localeOf(prompt), ToolGatewayI18nKeys.GATEWAY_TOOL_DESCRIPTION);
    }

    /**
     * 分组的展示名（默认分组按语言显示）
     */
    private static String categoryOf(String category, Locale locale) {
        if (ToolGatewayI18n.messages().textsOfAllLocales(ToolGatewayI18nKeys.GATEWAY_TOOL_DEFAULT_CATEGORY).contains(category)) {
            return ToolGatewayI18n.get(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_DEFAULT_CATEGORY);
        }
        return category;
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return index.all.size() > 0;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        final Locale locale = AiI18n.localeOf(prompt);
        final Index idx = index;
        final int size = idx.all.size();
        StringBuilder sb = new StringBuilder();
        sb.append(ToolGatewayI18n.fmt(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_INSTRUCTION_TITLE, "count", size)).append("\n");

        if (size <= dynamicThreshold) {
            // --- 逻辑分支 1: FULL 模式 (全铺) ---
            sb.append(ToolGatewayI18n.get(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_INSTRUCTION_FULL));
        } else if (size <= listThreshold) {
            // --- 逻辑分支 2: SUMMARY 模式 (摘要) ---
            sb.append(ToolGatewayI18n.get(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_INSTRUCTION_SUMMARY)).append("\n");
            idx.categories.forEach((cat, tools) -> {
                sb.append("- **[").append(categoryOf(cat, locale)).append("]**:\n");
                tools.values().forEach(t ->
                        sb.append("  - `").append(t.name()).append("`: ").append(t.description()).append("\n")
                );
            });
        } else if (size <= searchThreshold) {
            // --- 逻辑分支 3: LIST 模式 (名字，按原名显示) ---
            sb.append(ToolGatewayI18n.get(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_INSTRUCTION_LIST)).append("\n");
            idx.categories.forEach((cat, tools) -> {
                String names = tools.values().stream().map(FunctionTool::name).collect(Collectors.joining(", "));
                sb.append("- **").append(categoryOf(cat, locale)).append("**: `").append(names).append("`\n");
            });
            sb.append("\n").append(ToolGatewayI18n.get(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_INSTRUCTION_LIST_TIP));
        } else {
            // --- 逻辑分支 4: SEARCH 模式 (搜索) ---
            sb.append(ToolGatewayI18n.get(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_INSTRUCTION_SEARCH));
        }

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        Index idx = index;
        int size = idx.all.size();

        // FULL 模式：直接交付原始工具
        if (size <= dynamicThreshold) {
            return idx.all.values();
        }

        // 中转工具（描述是固定文本，不随请求语言变化；指令与返回文案按请求语言）
        Collection<FunctionTool> relays = super.getTools(prompt);

        // SUMMARY 模式：上下文已包含摘要，无需搜索功能，只需提供详情与代理工具
        if (size <= listThreshold) {
            return relays.stream()
                    .filter(f -> !TOOL_SEARCH.equals(f.name()))
                    .collect(Collectors.toList());
        }

        // LIST & SEARCH 模式：提供完整的中转工具集（包含搜索）
        return relays;
    }

    /**
     * 搜索业务工具（__locale 由工具上下文注入）。结果为列表（每项含 tool_name、tool_description），最多 10 条。
     *
     * @since 4.2
     */
    @ToolMapping(name = TOOL_SEARCH, description = "搜索业务工具。支持多个关键词用空格隔开（如：'杭州 旅游'）")
    public Object searchTools(@Param("keyword") String keyword, String __locale) {
        Locale locale = AiI18n.toLocale(__locale);
        String[] keys = GatewaySupport.keywordsOf(keyword);
        if (keys.length == 0) return ToolGatewayI18n.get(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_SEARCH_BLANK);

        List<Map<String, String>> results = index.all.values().stream()
                .filter(t -> GatewaySupport.containsAll(t.name() + " " + t.description(), keys))
                .limit(SEARCH_LIMIT)
                .map(t -> {
                    Map<String, String> map = new LinkedHashMap<>();
                    map.put("tool_name", t.name());
                    map.put("tool_description", t.description());
                    return map;
                })
                .collect(Collectors.toList());

        if (results.isEmpty()) {
            return ToolGatewayI18n.fmt(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_SEARCH_NONE, "keyword", keyword.trim());
        }

        return results;
    }

    /**
     * 获取业务工具详情（__locale 由工具上下文注入）
     *
     * @since 4.2
     */
    @ToolMapping(name = TOOL_DETAIL, description = "获取指定业务工具的完整参数 Schema")
    public String getToolDetail(@Param("tool_name") String name, String __locale) {
        Locale locale = AiI18n.toLocale(__locale);
        if (Utils.isBlank(name)) return ToolGatewayI18n.get(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_NAME_BLANK);

        FunctionTool tool = index.all.get(name.trim().toLowerCase());
        if (tool != null) {
            return ToolGatewayI18n.fmt(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_DETAIL_FOUND,
                    "name", tool.name(), "description", tool.description()) +
                    " \n```json\n" + tool.inputSchema() + "\n```";
        }

        return ToolGatewayI18n.fmt(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_DETAIL_MISSING, "name", name);
    }

    /**
     * 经网关调用工具：与模型调用 {@value #TOOL_CALL} 走同一路径（定位、参数整理、重试、报错），
     * 不带工具上下文，报错文案用默认语言。
     *
     * @param tool_name 工具名（大小写不敏感）
     * @param tool_args 参数（可为 null；不会被修改）
     */
    public ToolResult callTool(String tool_name,
                               Map<String, Object> tool_args) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(ARG_TOOL_NAME, tool_name);
        args.put(ARG_TOOL_ARGS, tool_args);
        return callTool.handle(args);
    }

    /**
     * 已废弃：4.1.1 误公开的内部中转工具。网关已自带中转工具，不必再单独创建；
     * 要在代码里经网关调用工具，用 {@link ToolGatewayTalent#callTool(String, Map)}。
     *
     * <p>保留只为源码兼容：行为委托给网关的 {@value #TOOL_CALL}（4.2.0 起工具名不再是 call_tool）。
     *
     * @deprecated 4.2.0
     */
    @Deprecated
    public static class ToolCallTool extends AbsTool {
        private final ToolGatewayTalent gatewayTalent;

        public ToolCallTool(ToolGatewayTalent gatewayTalent) {
            this.gatewayTalent = gatewayTalent;

            addParam(ARG_TOOL_NAME, String.class, "");
            addParam(ARG_TOOL_ARGS, TypeRef.mapOf(String.class, Object.class).getType(), "");
        }

        @Override
        public String name() {
            return TOOL_CALL;
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

    /**
     * 中转调用工具（{@value #TOOL_CALL}）
     */
    static final class BizCallTool extends AbsTool {
        private final ToolGatewayTalent gatewayTalent;

        BizCallTool(ToolGatewayTalent gatewayTalent) {
            this.gatewayTalent = gatewayTalent;

            addParam(ARG_TOOL_NAME, String.class, "");
            addParam(ARG_TOOL_ARGS, TypeRef.mapOf(String.class, Object.class).getType(), "");
        }

        @Override
        public String name() {
            return TOOL_CALL;
        }

        @Override
        public String description() {
            return "代理执行特定的业务工具";
        }

        @Override
        public ToolResult handle(Map<String, Object> args) {
            String tool_name = GatewaySupport.textOf(args.get(ARG_TOOL_NAME));
            Locale locale = AiI18n.toLocale(args.get(AiI18n.ATTR_LOCALE));

            // 调用本身的失败一律返回报错结果（与框架对未找到工具、工具异常的处理一致），客户端据此标红
            if (Utils.isBlank(tool_name)) {
                return ToolResult.error(ToolGatewayI18n.get(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_NAME_BLANK));
            }

            FunctionTool tool = gatewayTalent.index.all.get(tool_name.trim().toLowerCase());
            if (tool == null) {
                return ToolResult.error(ToolGatewayI18n.fmt(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_CALL_MISSING, "name", tool_name));
            }

            // 模型给的参数（副本，不改动原始参数；JSON 字符串会解析一次）
            Map<String, Object> toolArgs = GatewaySupport.argsOf(args.get(ARG_TOOL_ARGS));
            if (toolArgs == null) {
                return ToolResult.error(ToolGatewayI18n.fmt(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_CALL_ARGS_INVALID,
                        "name", tool.name()) + " \n```json\n" + tool.inputSchema() + "\n```");
            }

            // 传导工具上下文（本地工具需要会话 id、工作目录等；同名键以上下文为准，模型不能伪造）
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                if (ARG_TOOL_NAME.equals(entry.getKey()) == false && ARG_TOOL_ARGS.equals(entry.getKey()) == false) {
                    toolArgs.put(entry.getKey(), entry.getValue());
                }
            }

            try {
                return GatewaySupport.callWithRetry(gatewayTalent.maxRetries, gatewayTalent.retryDelayMs,
                        () -> tool.call(toolArgs));
            } catch (Throwable e) {
                LOG.error("Tool gateway execution failed: {}", tool_name, e);
                return ToolResult.error(ToolGatewayI18n.fmt(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_CALL_ERROR,
                        "error", GatewaySupport.errorOf(e)));
            }
        }
    }
}
