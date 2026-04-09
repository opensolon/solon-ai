package org.noear.solon.ai.harness.agent;

import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * @author noear 2026/3/21 created
 *
 */
public class GenerateTool extends AbsToolProvider {
    private static final Logger LOG = LoggerFactory.getLogger(GenerateTool.class);

    private HarnessEngine engine;

    public GenerateTool(HarnessEngine engine) {
        super(createBinding(engine));
        this.engine = engine;
    }

    private static Map<String, Object> createBinding(HarnessEngine engine) {
        Map<String, Object> binding = new LinkedHashMap<>();

        if (Assert.isNotEmpty(engine.getProps().getModels())) {
            //有模型配置
            StringBuilder buf = new StringBuilder("从给定列表中选择：\n");
            for (Map.Entry<String, ChatConfig> entry : engine.getProps().getModels().entrySet()) {
                buf.append("- `").append(entry.getValue()).append("`，").append(Utils.annoAlias(entry.getValue().getDescription(), entry.getKey())).append("\n");
            }

            binding.put("aiModels", buf.toString());
        } else {
            //没有
            binding.put("aiModels", "暂无模型可选");
        }

        return binding;
    }


    @ToolMapping(name = "generate_agent",
            description = "动态创建一个具有特定能力和系统提示词的子代理\n" +
                    "- 优先使用 general 子代理（如果它不适合，才考虑创建新的子代理）\n" +
                    "- 创建前，可以先查阅任务相关的专家技能（如果有，可以更好的为子代理设计系统提示词）")
    public String generateAgent(
            @Param(name = "name", description = "子代理的唯一英文标识符（如 code_reviewer）") String name,
            @Param(name = "description", description = "简要描述该代理的职责") String description,
            @Param(name = "systemPrompt", description = "详细的角色设定和工作准则") String systemPrompt,
            @Param(name = "model", description = "指定使用的模型名称。#{aiModels}\n", required = false) String model,
            @Param(name = "tools", required = false, description = "指定可以使用的工具。从给定列表中选择：\n" +
                    "- `read`，读取文件完整内容\n" +
                    "- `write`，写入文件完整内容\n" +
                    "- `edit`，修改文件内容（包括：read,write,edit）\n" +
                    "- `glob`，使用模式匹配\n" +
                    "- `grep`，基于正则表达式的全文检索\n" +
                    "- `list`，列出目录内容\n" +
                    "- `bash`，运行 Shell 命令\n" +
                    "- `skill`，调用预定义的专家技能模块\n" +
                    "- `code`，编码指导模块\n" +
                    "- `todo`，任务清单管理\n" +
                    "- `webfetch`，直接抓取特定网页内容\n" +
                    "- `websearch`，互联网通用搜索\n" +
                    "- `codesearch`，互联网代码仓库搜索\n" +
                    "- `pi`，核心操作能力（包括：read,write,edit,bash）\n" +
                    "- `task`，调度子代理干活\n" +
                    "- `*`，代表全选") List<String> tools,
            @Param(name = "skills", description = "子代理具备的特定专家能力标识列表", required = false) List<String> skills,
            @Param(name = "maxTurns", description = "单次任务的最大思考/对话轮数，通常建议 5-10", required = false) Integer maxTurns,
            @Param(name = "saveToFile", description = "是否将代理定义保存为 .md 文件（如果存为文件，进程重启后可复用）", defaultValue = "false", required = false) Boolean saveToFile,
            String __cwd
    ) {
        if (name == null || !name.matches("^[a-zA-Z0-9_-]+$")) {
            return "ERROR: name 标识符不合法，仅允许使用英文字符、数字、下划线或中划线。";
        }

        try {
            AgentDefinition definition = engine.getAgentManager()
                    .getAgent(AgentDefinition.AGENT_GENERAL)
                    .copy();

            definition.getMetadata().setName(name);
            definition.getMetadata().setDescription(description);
            definition.getMetadata().setEnabled(true);

            if (model != null && !model.isEmpty()) {
                definition.getMetadata().setModel(model);
            }
            if (tools != null && !tools.isEmpty()) {
                definition.getMetadata().setTools(tools);
            }
            if (skills != null && !skills.isEmpty()) {
                definition.getMetadata().setSkills(skills);
            }
            if (maxTurns != null && maxTurns > 0) {
                definition.getMetadata().setMaxTurns(maxTurns);
            }

            definition.setSystemPrompt(systemPrompt);

            boolean shouldSave = saveToFile != null && saveToFile;

            if (shouldSave) {
                Path agentsDir = Paths.get(__cwd, ".soloncode", "agents");
                if (!Files.exists(agentsDir)) {
                    Files.createDirectories(agentsDir);
                }
                Path agentFile = agentsDir.resolve(name + ".md");

                try (BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(
                                Files.newOutputStream(agentFile.toFile().toPath()),
                                StandardCharsets.UTF_8))) {
                    writer.write(definition.toMarkdown());
                }

                LOG.info("Agent 定义已保存到: {}", agentFile);
            }

            engine.getAgentManager().addAgent(definition);

            return "[OK] 子代理创建成功！\n\n" +
                    String.format("**标识**: %s\n", name) +
                    String.format("**描述**: %s\n", description) +
                    String.format("\n现在可以使用 `task(name=\"%s\", prompt=\"...\")` 来调用。", name);

        } catch (Throwable e) {
            LOG.error("创建子代理失败: name={}, error={}", name, e.getMessage(), e);
            return "ERROR: 创建子代理失败: " + e.getMessage();
        }
    }
}