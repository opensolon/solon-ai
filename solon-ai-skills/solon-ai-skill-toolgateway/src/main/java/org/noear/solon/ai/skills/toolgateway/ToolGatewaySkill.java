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
 * 工具网关技能：解决工具过多导致的上下文溢出（Context Overflow）问题。
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
public class ToolGatewaySkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(ToolGatewaySkill.class);

    private final Map<String, FunctionTool> dynamicTools = new LinkedHashMap<>();
    private int dynamicThreshold = 8;   // 超过此值，进入摘要模式（SUMMARY）
    private int listThreshold = 30;     // 超过此值，进入仅名字模式（LIST）
    private int searchThreshold = 100;  // 超过此值，进入强制搜索模式（SEARCH）

    /**
     * 设置全量平铺的阈值（默认为 8）
     */
    public ToolGatewaySkill dynamicThreshold(int dynamicThreshold) {
        this.dynamicThreshold = dynamicThreshold;
        return this;
    }

    /**
     * 设置摘要模式的阈值（默认为 30）
     */
    public ToolGatewaySkill listThreshold(int listThreshold) {
        this.listThreshold = listThreshold;
        return this;
    }

    /**
     * 设置仅名字列表模式的阈值（默认为 100）
     */
    public ToolGatewaySkill searchThreshold(int searchThreshold) {
        this.searchThreshold = searchThreshold;
        return this;
    }

    /**
     * 添加工具包
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
     * 添加单个工具（内部强制转换为小写键值以防同名冲突）
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
            return "## 工具网关\n当前暂无业务工具。";
        }

        final int size = dynamicTools.size();
        StringBuilder sb = new StringBuilder();
        sb.append("## 业务工具发现规范 (共 ").append(size).append(" 个工具)\n");

        if (size <= dynamicThreshold) {
            // --- 逻辑分支 1: FULL 模式 (全铺) ---
            sb.append("当前已加载全量业务工具定义，请分析需求并直接调用相关工具。");
        } else if (size <= listThreshold) {
            // --- 逻辑分支 2: SUMMARY 模式 (摘要) ---
            sb.append("由于业务工具较多，已开启**动态发现**模式。请严格遵循以下操作流程：\n");

            sb.append("- **Step 1 (锁定)**: 从下方“可用工具清单”中根据描述选定工具名。\n");
            sb.append("- **Step 2 (详情)**: 调用 `get_tool_detail` 获取参数定义。\n");
            sb.append("- **Step 3 (执行)**: 通过 `call_tool` 执行。\n\n");

            sb.append("### 可用业务工具清单 (摘要):\n");
            for (FunctionTool tool : dynamicTools.values()) {
                sb.append("- **").append(tool.name()).append("**: ").append(tool.description()).append("\n");
            }
        } else if (size <= searchThreshold) {
            // --- 逻辑分支 3: LIST 模式 (名字) ---
            sb.append("由于业务工具较多，已开启**动态发现**模式。请严格遵循以下操作流程：\n");

            sb.append("- **Step 1 (预选)**: 从下方“工具名列表”中推断目标，或用 `search_tools` 检索。\n");
            sb.append("- **Step 2 (详情)**: 调用 `get_tool_detail` 获取参数定义。\n");
            sb.append("- **Step 3 (执行)**: 通过 `call_tool` 执行。\n\n");

            sb.append("### 可用工具名列表 (仅名称):\n");
            sb.append("> 提示：请根据工具名推断功能，锁定后必查详情。\n");
            String names = dynamicTools.values().stream()
                    .map(FunctionTool::name)
                    .collect(Collectors.joining(", "));
            sb.append("`").append(names).append("`\n");
        } else {
            // --- 逻辑分支 4: SEARCH 模式 (搜索) ---
            sb.append("由于业务工具较多，已开启**动态发现**模式。请严格遵循以下操作流程：\n");

            sb.append("- **Step 1 (搜索)**: 清单已折叠。请务必先用 `search_tools` 寻找匹配的工具名。\n");
            sb.append("- **Step 2 (详情)**: 调用 `get_tool_detail` 获取参数定义。\n");
            sb.append("- **Step 3 (执行)**: 通过 `call_tool` 执行。\n\n");

            sb.append("> **注意**: 当前工具规模较大，请通过搜索发现功能。例如：search_tools('天气')。");
        }

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        int size = dynamicTools.size();

        // FULL 模式：直接交付原始工具
        if (size <= dynamicThreshold) {
            return dynamicTools.values();
        }

        // SUMMARY 模式：上下文已包含摘要，无需搜索功能，只需提供详情与代理工具
        if (size <= listThreshold) {
            return this.tools.stream()
                    .filter(f -> !"search_tools".equals(f.name()))
                    .collect(Collectors.toList());
        }

        // LIST & SEARCH 模式：提供完整的中转工具集（包含 search_tools）
        return this.tools;
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