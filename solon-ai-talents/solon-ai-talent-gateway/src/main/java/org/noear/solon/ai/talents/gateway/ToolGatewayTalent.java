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
import org.noear.solon.ai.util.RetryUtil;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool 网关：解决工具过多导致的上下文溢出（Context Overflow）问题。
 *
 * 通过工具数量自动切换四阶段模式，平衡模型推理精度与 Token 消耗：
 * 1. FULL (全量): 数量 <= dynamicThreshold。平铺所有工具的完整 Schema。
 * 2. SUMMARY (摘要): 数量 <= listThreshold。在指令内展示“工具名 + 描述”清单。
 * 3. LIST (名字): 数量 <= searchThreshold。仅展示“工具名”清单，利用模型语义理解能力进行初选。
 * 4. SEARCH (搜索): 数量 > searchThreshold。完全折叠，强制 LLM 使用关键词检索。
 *
 * 注意：必须确保工具名称唯一。
 *
 * @author noear
 * @since 3.9.5
 */
public class ToolGatewayTalent extends AbsTalent {
    private static final Logger LOG = LoggerFactory.getLogger(ToolGatewayTalent.class);

    private final Map<String, Map<String, FunctionTool>> categoryTools = new LinkedHashMap<>();
    private final Map<String, FunctionTool> allTools = new LinkedHashMap<>();

    private int dynamicThreshold = 8;   // 超过此值，进入摘要模式（SUMMARY）
    private int listThreshold = 40;     // 超过此值，进入仅名字模式（LIST）
    private int searchThreshold = 100;  // 超过此值，进入强制搜索模式（SEARCH）

    private int maxRetries = 3;

    private final ToolCallTool callTool;

    public ToolGatewayTalent() {
        this.callTool = new ToolCallTool(this);
        internalAddTool(callTool);
    }

    public ToolGatewayTalent retryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        return this;
    }

    public ToolGatewayTalent retryConfig(int maxRetries) {
        this.maxRetries = Math.max(1, maxRetries);
        return this;
    }

    /**
     * 设置全量平铺的阈值（默认为 8）
     */
    public ToolGatewayTalent dynamicThreshold(int dynamicThreshold) {
        this.dynamicThreshold = dynamicThreshold;
        return this;
    }

    /**
     * 设置摘要模式的阈值（默认为 30）
     */
    public ToolGatewayTalent listThreshold(int listThreshold) {
        this.listThreshold = listThreshold;
        return this;
    }

    /**
     * 设置仅名字列表模式的阈值（默认为 100）
     */
    public ToolGatewayTalent searchThreshold(int searchThreshold) {
        this.searchThreshold = searchThreshold;
        return this;
    }

    /**
     * 添加工具包
     */
    public ToolGatewayTalent addTool(ToolProvider toolProvider) {
        return addTool(null, toolProvider);
    }

    /**
     * 添加工具包
     */
    public ToolGatewayTalent addTool(String category, ToolProvider toolProvider) {
        if (toolProvider != null) {
            toolProvider.getTools().forEach(t -> addTool(category, t));
        }
        return this;
    }

    /**
     * 添加单个工具（内部强制转换为小写键值以防同名冲突）
     */
    public ToolGatewayTalent addTool(FunctionTool tool) {
        return addTool(null, tool);
    }

    /**
     * 添加单个工具（内部强制转换为小写键值以防同名冲突）
     */
    public ToolGatewayTalent addTool(String category, FunctionTool tool) {
        if (tool == null) {
            return this;
        }

        //默认分组按默认语言登记，展示时再按请求语言换成对应文案（见 categoryOf）
        String catName = Utils.isEmpty(category) ? ToolGatewayI18n.get(null, ToolGatewayI18nKeys.GATEWAY_TOOL_DEFAULT_CATEGORY) : category;

        categoryTools.computeIfAbsent(catName, k -> new LinkedHashMap<>())
                .put(tool.name().toLowerCase(), tool);

        allTools.put(tool.name().toLowerCase(), tool);

        return this;
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
        return allTools.size() > 0;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        final Locale locale = AiI18n.localeOf(prompt);
        final int size = allTools.size();
        StringBuilder sb = new StringBuilder();
        sb.append(ToolGatewayI18n.fmt(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_INSTRUCTION_TITLE, "count", size)).append("\n");

        if (size <= dynamicThreshold) {
            // --- 逻辑分支 1: FULL 模式 (全铺) ---
            sb.append(ToolGatewayI18n.get(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_INSTRUCTION_FULL));
        } else if (size <= listThreshold) {
            // --- 逻辑分支 2: SUMMARY 模式 (摘要) ---
            sb.append(ToolGatewayI18n.get(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_INSTRUCTION_SUMMARY)).append("\n");
            categoryTools.forEach((cat, tools) -> {
                sb.append("- **[").append(categoryOf(cat, locale)).append("]**:\n");
                tools.values().forEach(t ->
                        sb.append("  - `").append(t.name()).append("`: ").append(t.description()).append("\n")
                );
            });
        } else if (size <= searchThreshold) {
            // --- 逻辑分支 3: LIST 模式 (名字) ---
            sb.append(ToolGatewayI18n.get(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_INSTRUCTION_LIST)).append("\n");
            categoryTools.forEach((cat, tools) -> {
                String names = String.join(", ", tools.keySet());
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
        int size = allTools.size();

        // FULL 模式：直接交付原始工具
        if (size <= dynamicThreshold) {
            return allTools.values();
        }

        // SUMMARY 模式：上下文已包含摘要，无需搜索功能，只需提供详情与代理工具（中转工具按请求语言本地化）
        if (size <= listThreshold) {
            return super.getTools(prompt).stream()
                    .filter(f -> !"search_tools".equals(f.name()))
                    .collect(Collectors.toList());
        }

        // LIST & SEARCH 模式：提供完整的中转工具集（包含 search_tools）
        return super.getTools(prompt);
    }

    public Object searchTools(String keyword) {
        return searchTools(keyword, null);
    }

    /**
     * 搜索业务工具（__locale 由工具上下文注入）
     *
     * @since 4.2
     */
    @ToolMapping(name = "search_tools", description = "搜索业务工具。支持多个关键词用空隔隔开（如：'杭州 旅游'）")
    public Object searchTools(@Param("keyword") String keyword, String __locale) {
        Locale locale = AiI18n.toLocale(__locale);
        if (Utils.isEmpty(keyword)) return ToolGatewayI18n.get(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_SEARCH_BLANK);

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
            return ToolGatewayI18n.fmt(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_SEARCH_NONE, "keyword", keyword);
        }

        return results;
    }

    public String getToolDetail(String name) {
        return getToolDetail(name, null);
    }

    /**
     * 获取业务工具详情（__locale 由工具上下文注入）
     *
     * @since 4.2
     */
    @ToolMapping(name = "get_tool_detail", description = "获取指定业务工具的完整参数 Schema")
    public String getToolDetail(@Param("tool_name") String name, String __locale) {
        Locale locale = AiI18n.toLocale(__locale);
        if (Utils.isEmpty(name)) return ToolGatewayI18n.get(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_NAME_BLANK);

        FunctionTool tool = allTools.get(name.trim().toLowerCase());
        if (tool != null) {
            return ToolGatewayI18n.fmt(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_DETAIL_FOUND,
                    "name", tool.name(), "description", tool.description()) +
                    " \n```json\n" + tool.inputSchema() + "\n```";
        }

        return ToolGatewayI18n.fmt(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_DETAIL_MISSING, "name", name);
    }

    //测试用
    public ToolResult callTool(String tool_name,
                               Map<String, Object> tool_args) {
        try {
            return callTool.call(Utils.asMap(
                    "tool_name", tool_name,
                    "tool_args", tool_args));
        }catch (Throwable e){
            throw  new RuntimeException(e);
        }
    }

    public static class ToolCallTool extends AbsTool {
        private final ToolGatewayTalent gatewayTalent;

        public ToolCallTool(ToolGatewayTalent gatewayTalent) {
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
            return "代理执行特定的业务工具";
        }

        @Override
        public Object handle(Map<String, Object> args) {
            String tool_name = (String) args.get("tool_name");
            Map<String, Object> tool_args = (Map) args.get("tool_args");

            //传导工具上下文
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                if ("tool_name".equals(entry.getKey()) == false && "tool_args".equals(entry.getKey()) == false) {
                    tool_args.put(entry.getKey(), entry.getValue());
                }
            }

            //-------
            Locale locale = AiI18n.toLocale(args.get(AiI18n.ATTR_LOCALE));

            if (Utils.isEmpty(tool_name)) {
                return ToolResult.success(ToolGatewayI18n.get(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_NAME_BLANK));
            }

            FunctionTool tool = gatewayTalent.allTools.get(tool_name.trim().toLowerCase());

            if (tool == null) {
                return ToolResult.success(ToolGatewayI18n.fmt(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_CALL_MISSING, "name", tool_name));
            }

            try {
                return RetryUtil.callWithRetry(gatewayTalent.maxRetries,  () -> tool.call(tool_args));
            } catch (Throwable e) {
                LOG.error("Tool gateway execution failed: {}", tool_name, e);
                return ToolResult.success(ToolGatewayI18n.fmt(locale, ToolGatewayI18nKeys.GATEWAY_TOOL_CALL_ERROR,
                        "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }
    }
}