/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.agent;

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
            for (ChatConfig entry : engine.getProps().getModels()) {
                buf.append("- `").append(entry.getNameOrModel()).append("`，").append(entry.getDescriptionOrModel()).append("\n");
            }

            binding.put("models", buf.toString());
        } else {
            //没有
            binding.put("models", "暂无模型可选");
        }

        return binding;
    }


    @ToolMapping(name = "generate",
            description = "动态构建一个具备特定专家知识和工具权限的子代理。用于将复杂大任务拆解给垂直领域的‘虚拟专家’执行。\n" +
                    "- 只有当‘当前可用代理列表’中无匹配项时，才允许调用此工具。\n" +
                    "- 创建前应查阅相关知识库和专家技能，以确保 systemPrompt 的专业性。")
    public String generate(
            @Param(name = "name", description = "子代理的唯一英文标识符（如 code_reviewer）") String name,
            @Param(name = "description", description = "对该代理职能的精炼描述，便于主代理后续识别和调用") String description,
            @Param(name = "systemPrompt", description = "核心指令集。需包含角色身份、技能范畴、输出格式规范及负面约束（避免废话）") String systemPrompt,
            @Param(name = "model", description = "使用的模型标识。建议复杂逻辑用高级模型，简单任务用基础模型。#{models}", required = false) String model,
            @Param(name = "tools", required = false, description = "赋予子代理的工具权限（严禁全选，仅勾选任务相关项）。从给定列表中选择：\n" +
                    "- `read`，读取文件完整内容\n" +
                    "- `write`，写入文件完整内容\n" +
                    "- `edit`，修改文件内容（包括：read,write,edit）\n" +
                    "- `glob`，使用模式匹配\n" +
                    "- `grep`，基于正则表达式的全文检索\n" +
                    "- `list`，列出目录内容\n" +
                    "- `bash`，运行 Shell 命令\n" +
                    "- `skill`，调用预定义的专家技能模块\n" +
                    "- `lsp`，深度代码理解\n" +
                    "- `code`，编码指导模块\n" +
                    "- `todo`，任务清单管理\n" +
                    "- `webfetch`，直接抓取特定网页内容\n" +
                    "- `websearch`，互联网通用搜索\n" +
                    "- `codesearch`，互联网代码仓库搜索\n" +
                    "- `pi`，核心操作能力包（包括：read,write,edit,bash）\n" +
                    "- `task`，允许其进一步开启下级代理(递归分发)\n" +
                    "- `*`，全量授权") List<String> tools,
            @Param(name = "skills", description = "子代理具备的特定专家能力标识列表", required = false) List<String> skills,
            @Param(name = "maxTurns", description = "单次任务的最大思考/对话轮数，通常建议 10-30", required = false) Integer maxTurns,
            @Param(name = "saveToFile", description = "是否持久化。如果是通用的、可复用的专家角色，建议设为 true；如果是临时任务助手，设为 false。", defaultValue = "false", required = false) Boolean saveToFile,
            String __cwd
    ) {
        if (name == null || !name.matches("^[a-zA-Z0-9_-]+$")) {
            return "ERROR: name 标识符不合法，仅允许使用英文字符、数字、下划线或中划线。";
        }

        try {
            AgentDefinition definition = engine.getAgentManager()
                    .getAgent(AgentDefinition.AGENT_GENERAL)
                    .copy();

            definition.setSystemPrompt(systemPrompt);

            definition.getMetadata().setName(name);
            definition.getMetadata().setDescription(description);
            definition.getMetadata().setEnabled(true);

            definition.getMetadata().setModel(model);
            definition.getMetadata().setTools(tools);
            definition.getMetadata().setSkills(skills);
            definition.getMetadata().setMaxTurns(maxTurns);

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

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Agent 定义已保存到: {}", agentFile);
                }
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