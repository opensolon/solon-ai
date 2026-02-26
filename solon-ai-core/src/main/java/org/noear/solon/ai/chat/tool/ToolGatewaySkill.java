package org.noear.solon.ai.chat.tool;

import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具网关技能：解决工具过多导致的上下文溢出问题。
 *
 * @author noear
 * @since 3.9.5
 */
public class ToolGatewaySkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(ToolGatewaySkill.class);

    private final Map<String, FunctionTool> dynamicTools = new LinkedHashMap<>();
    private int dynamicThreshold = 15;
    private int maxInstructionListSize = 50; // 新增：Instruction 列表最大容量

    public ToolGatewaySkill dynamicThreshold(int dynamicThreshold) {
        this.dynamicThreshold = dynamicThreshold;
        return this;
    }

    public ToolGatewaySkill maxInstructionListSize(int maxInstructionListSize) {
        this.maxInstructionListSize = maxInstructionListSize;
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

        StringBuilder sb = new StringBuilder();
        sb.append("#### 工具发现规范\n");

        int size = dynamicTools.size();
        if (size <= dynamicThreshold) {
            sb.append("当前已加载全量工具定义，请直接按需调用。");
        } else {
            sb.append("检测到大量工具 (共 ").append(size).append(" 个)，已开启**按需发现**模式。\n");
            sb.append("- **调用链路**: 锁定工具名 -> 执行 `get_tool_detail` 获取参数定义 -> 使用 `call_tool` 执行。\n");
            sb.append("- **物理约束**: 业务工具未直接注册到系统函数列表，必须通过 `call_tool` 进行中转。\n\n");

            if (size > maxInstructionListSize) {
                sb.append("> 提示：工具较多，完整清单请通过 `get_tool_list` 查询。");
            } else {
                for (FunctionTool tool : dynamicTools.values()) {
                    sb.append("- **").append(tool.name()).append("**: ").append(tool.description()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        if (dynamicTools.size() <= dynamicThreshold) {
            return dynamicTools.values();
        } else {
            return this.tools;
        }
    }

    @ToolMapping(name = "call_tool", description = "执行特定的业务工具")
    public ToolResult callTool(@Param("tool_name") String name, @Param("tool_args") Map<String, Object> args) {
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
            LOG.error("Tool execution failed: {}", name, e);
            return ToolResult.success("执行异常: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @ToolMapping(name = "get_tool_list", description = "获取所有业务工具的简要清单")
    public List<Map<String, String>> getToolList() {
        return dynamicTools.values().stream()
                .map(t -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("tool_name", t.name());
                    map.put("tool_description", t.description());
                    return map;
                }).collect(Collectors.toList());
    }

    @ToolMapping(name = "get_tool_detail", description = "获取特定工具的参数 Schema")
    public String getToolDetail(@Param("tool_name") String name) {
        if (Utils.isEmpty(name)) return "错误：tool_name 不能为空";

        FunctionTool tool = dynamicTools.get(name.trim().toLowerCase());
        if (tool != null) {
            return "### 工具详情: " + tool.name() + "\n" +
                    "- 功能描述: " + tool.description() + "\n" +
                    "- 参数定义 (JSON Schema): \n```json\n" + tool.inputSchema() + "\n```\n" +
                    "- 调用约束: 请严格按照 schema 要求的字段类型和必填项构造 tool_args。";
        }

        return "错误：未找到名为 '" + name + "' 的工具。请检查名称拼写或通过 get_tool_list 重新确认。";
    }
}