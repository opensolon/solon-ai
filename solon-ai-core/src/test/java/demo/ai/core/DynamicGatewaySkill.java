package demo.ai.core;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.skill.SkillMetadata;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.annotation.Param;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

public class DynamicGatewaySkill implements Skill {
    // 隐藏在后台的巨大工具库，不占用初始上下文
    private final Map<String, FunctionTool> lazyLibrary = new HashMap<>();

    private ToolProvider toolProvider = new MethodToolProvider(this);
    private SkillMetadata metadata = new SkillMetadata("dynamic_gateway", "动态工具网关");

    private void register(FunctionTool tool) {
        lazyLibrary.put(tool.name(), tool);
    }

    @Override
    public String name() {
        return metadata.getName();
    }

    @Override
    public String description() {
        return metadata.getDescription();
    }

    @Override
    public SkillMetadata metadata() {
        return metadata;
    }

    @Override
    public Collection<FunctionTool> getTools() {
        return toolProvider.getTools();
    }

    @Override
    public String getInstruction(Object ctx) {
        return "你是一个具备无限扩展能力的 Agent。\n" +
                "1. 当你发现现有工具无法解决问题时，请先调用 'search_tools' 搜索工具。\n" +
                "2. 搜索结果会返回工具的详细使用说明（Schema）。\n" +
                "3. 请根据返回的 Schema，使用 'call_tool' 发起真正的请求。";
    }

    @ToolMapping(name = "search_tools", description = "搜索工具库。当你不知道如何操作时，请务必先搜一下。")
    public String searchTools(@Param("keywords") String keywords) {
        // 模拟模糊搜索
        List<FunctionTool> results = lazyLibrary.values().stream()
                .filter(t -> t.name().contains(keywords) || t.description().contains(keywords))
                .collect(Collectors.toList());

        if (results.isEmpty()) return "未找到匹配的工具。";

        StringBuilder sb = new StringBuilder("发现以下工具，请根据其 Schema 进行调用：\n");
        for (FunctionTool tool : results) {
            sb.append("--- 工具名: ").append(tool.name()).append(" ---\n");
            sb.append("描述: ").append(tool.descriptionAndMeta()).append("\n");
            sb.append("输入Schema: ").append(tool.inputSchema()).append("\n");
        }
        return sb.toString();
    }

    @ToolMapping(name = "call_tool", description = "通用工具执行网关")
    public Object callTool(
            @Param("name") String name,
            @Param("args") Map<String, Object> args) throws Throwable {

        FunctionTool tool = lazyLibrary.get(name);
        if (tool == null) return "错误：工具不存在";

        // 真正的执行逻辑
        return tool.handle(args);
    }
}