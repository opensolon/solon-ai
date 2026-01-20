package demo.ai.core;

import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.ChatPrompt;
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

    private ToolProvider toolProvider = new MethodToolProvider(this);
    private SkillMetadata metadata = new SkillMetadata("dynamic_gateway", "动态工具网关");

    private List<FunctionTool> findRelevant(String query, int size){
        //查找相关工具
        return null;
    }

    private FunctionTool loadTool(String name){
        //加载具体工具
        return null;
    }

    //-----------------


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
    public String getInstruction(ChatPrompt prompt) {
        return "你是一个具备无限扩展能力的 Agent。\n" +
                "1. 当你发现现有工具无法解决问题时，请先调用 'search_tools' 搜索工具。\n" +
                "2. 搜索结果会返回工具的详细使用说明（Schema）。\n" +
                "3. 请根据返回的 Schema，使用 'call_tool' 发起真正的请求。";
    }

    @ToolMapping(name = "search_tools", description = "从海量库中发现工具。支持语义搜索和功能描述。")
    public String searchTools(@Param("query") String query) {
        // 模拟模糊搜索
        List<FunctionTool> tools = findRelevant(query, 5);

        if (tools.isEmpty()) return "未发现匹配工具，请换个关键词试试（如：财务、报表、推送）。";

        return ONode.serialize(tools.stream().map(t -> {
            Map<String, Object> info = new HashMap<>();
            info.put("tool_name", t.name());
            info.put("description", t.descriptionAndMeta());
            info.put("input_schema", t.inputSchema()); // 让模型直接读 Schema
            return info;
        }).collect(Collectors.toList()));
    }

    @ToolMapping(name = "call_tool", description = "执行已搜到的工具。")
    public Object callTool(@Param("tool_name") String name,
                           @Param("arguments") Map<String, Object> args) throws Throwable {

        // 动态加载工具实例
        FunctionTool tool = loadTool(name);
        if (tool == null) {
            return "Execution failed: Tool [" + name + "] is no longer available.";
        }

        return tool.handle(args);
    }
}