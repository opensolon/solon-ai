package org.noear.solon.ai.skills.toolgateway;

import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具网关技能：解决工具过多导致的上下文溢出问题。
 *
 * 支持三阶段模式自动切换：
 * 1. FULL: 数量 <= dynamicThreshold，全量平铺。
 * 2. DYNAMIC: 数量 <= searchThreshold，指令内展示清单。
 * 3. SEARCH: 数量 > searchThreshold，强制搜索。
 *
 * 注意：不能有同名工具
 *
 * @author noear
 * @since 3.9.5
 */
public class ToolGatewaySkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(ToolGatewaySkill.class);

    private final Map<String, FunctionTool> dynamicTools = new LinkedHashMap<>();
    private int dynamicThreshold = 10; // 超过此值，不再平铺 Schema，进入清单模式
    private int searchThreshold = 100;  // 超过此值，不再展示清单，进入强制搜索模式

    public ToolGatewaySkill dynamicThreshold(int dynamicThreshold) {
        this.dynamicThreshold = dynamicThreshold;
        return this;
    }

    public ToolGatewaySkill searchThreshold(int searchThreshold) {
        this.searchThreshold = searchThreshold;
        return this;
    }

    /**
     * 添加工具
     */
    public ToolGatewaySkill addTool(ToolProvider toolProvider) {
        if (toolProvider != null) {
            for (FunctionTool tool : toolProvider.getTools()) {
                addTool(tool);
            }
        }
        return this;
    }

    /**
     * 添加工具
     */
    public ToolGatewaySkill addTool(FunctionTool tool) {
        if (tool != null) {
            dynamicTools.put(tool.name().toLowerCase(), tool);
        }
        return this;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        if (dynamicTools.isEmpty()) {
            return "#### 工具网关\n当前暂无业务工具。";
        }

        final int size = dynamicTools.size();
        StringBuilder sb = new StringBuilder();
        sb.append("#### 业务工具发现规范 (共 ").append(size).append(" 个工具)\n");

        if (size <= dynamicThreshold) {
            // FULL 模式：直接交付给 AI
            sb.append("当前已加载全量业务工具定义，请分析需求并直接调用。");
        } else {
            // 引导 AI 走中转流程
            sb.append("由于业务工具库较多，已开启**动态路由**模式。请严格遵循以下步骤：\n");

            if (size > searchThreshold) {
                // SEARCH 模式：瞎子摸象，必须先搜
                sb.append("- **Step 1 (搜索)**: 业务清单已折叠。请先使用 `search_tools` 寻找匹配的工具名。\n");
                sb.append("- **Step 2 (详情)**: 使用 `get_tool_detail` 获取参数定义。\n");
            } else {
                // DYNAMIC 模式：看清单，直接查详情
                sb.append("- **Step 1 (锁定)**: 从下方“可用业务清单”中直接根据描述选定工具名。\n");
                sb.append("- **Step 2 (详情)**: 使用 `get_tool_detail` 获取该工具的参数定义 (JSON Schema)。\n");
            }

            sb.append("- **Step 3 (执行)**: 必须通过 `call_tool` 提交参数并执行。\n\n");

            if (size > searchThreshold) {
                sb.append("> **提示**: 业务工具量大，建议通过关键词搜索，例如：search_tools('天气')。");
            } else {
                // 展示摘要清单
                sb.append("### 可用业务工具清单:\n");
                for (FunctionTool tool : dynamicTools.values()) {
                    sb.append("- **").append(tool.name()).append("**: ").append(tool.description()).append("\n");
                }
            }
        }

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        int size = dynamicTools.size();
        if (size <= dynamicThreshold) {
            return dynamicTools.values();
        } else {
            if (size > searchThreshold) {
                // SEARCH 模式：返回 search_tools, get_tool_detail, call_tool
                return this.tools;
            } else {
                // DYNAMIC 模式：排除 search_tools
                return this.tools.stream()
                        .filter(f -> !"search_tools".equals(f.name()))
                        .collect(Collectors.toList());
            }
        }
    }

    @ToolMapping(name = "search_tools", description = "搜索业务工具。支持多个关键词用空隔隔开（如：'杭州 旅游'）")
    public Object searchTools(@Param("keyword") String keyword) {
        if (Utils.isEmpty(keyword)) return "错误：搜索关键词不能为空。";

        String[] keys = keyword.toLowerCase().split("[\\s,;，；]+");

        List<Map<String, String>> results = dynamicTools.values().stream()
                .filter(t -> {
                    String content = (t.name() + " " + t.description()).toLowerCase();
                    return Arrays.stream(keys).allMatch(content::contains);
                })
                .limit(10)
                .map(this::mapToolBrief)
                .collect(Collectors.toList());

        if (results.isEmpty()) {
            return "提醒：未找到完全匹配关键词 '" + keyword + "' 的业务工具。\n" +
                    "您可以尝试：\n" +
                    "1. 检查关键词是否使用了空格分隔（如：'杭州 旅游'）。\n" +
                    "2. 换用更通用的词汇，或减少关键词数量。\n" +
                    "3. 如果确定无此功能，请告知用户。禁止重复尝试相似搜索。";
        }

        return results;
    }

    @ToolMapping(name = "get_tool_detail", description = "获取指定业务工具的完整参数 Schema")
    public String getToolDetail(@Param("tool_name") String name) {
        if (Utils.isEmpty(name)) return "错误：tool_name 不能为空";

        FunctionTool tool = dynamicTools.get(name.trim().toLowerCase());
        if (tool != null) {
            return "### 工具详情: " + tool.name() + "\n" +
                    "- 功能描述: " + tool.description() + "\n" +
                    "- 参数架构 (JSON Schema): \n```json\n" + tool.inputSchema() + "\n```";
        }

        return "错误：未找到工具 '" + name + "'，请检查名称拼写是否正确。";
    }

    @ToolMapping(name = "call_tool", description = "代理执行特定的业务工具")
    public ToolResult callTool(@Param("tool_name") String name,
                               @Param("tool_args") Map<String, Object> args) {
        if (Utils.isEmpty(name)) {
            return ToolResult.success("错误：tool_name 不能为空");
        }

        FunctionTool tool = dynamicTools.get(name.trim().toLowerCase());

        if (tool == null) {
            return ToolResult.success("错误：未找到工具 '" + name + "'");
        }

        try {
            return tool.call(args);
        } catch (Throwable e) {
            LOG.error("Tool gateway execution failed: {}", name, e);
            return ToolResult.success("执行异常: " +
                    (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private Map<String, String> mapToolBrief(FunctionTool t) {
        Map<String, String> map = new HashMap<>();
        map.put("tool_name", t.name());
        map.put("tool_description", t.description());
        return map;
    }
}