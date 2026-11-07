/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness.agent.task;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.agent.SessionAgents;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * @author noear 2026/3/21 created
 *
 */
public class GenerateTalent extends AbsTalent {
    private static final Logger LOG = LoggerFactory.getLogger(GenerateTalent.class);

    private HarnessEngine engine;

    public GenerateTalent(HarnessEngine engine) {
        this.engine = engine;
    }


    @ToolMapping(name = "generate",
            description = "动态构建一个具备特定专家知识和工具权限的子代理。用于将复杂大任务拆解给垂直领域的‘虚拟专家’执行。\n" +
                    "- 只有当‘当前可用代理列表’中无匹配项时，才允许调用此工具。\n" +
                    "- 创建前应查阅相关知识库和技能，以确保 systemPrompt 的专业性。")
    public String generate(
            @Param(name = "name", description = "子代理的唯一英文标识符（如 code_reviewer）") String name,
            @Param(name = "description", description = "对该代理职能的精炼描述，便于主代理后续识别和调用") String description,
            @Param(name = "systemPrompt", description = "核心指令集。需包含角色身份、技能范畴、输出格式规范及负面约束（避免废话）") String systemPrompt,
            @Param(name = "tools", required = false, description = "赋予子代理的工具权限（严禁全选，仅勾选任务相关项）。从给定列表中选择：\n" +
                    "- `read`，读取文件完整内容\n" +
                    "- `write`，写入文件完整内容\n" +
                    "- `edit`，修改文件内容（包括：read,write,edit）\n" +
                    "- `glob`，使用模式匹配\n" +
                    "- `grep`，基于正则表达式的全文检索\n" +
                    "- `list`，列出目录内容\n" +
                    "- `bash`，运行 Shell 命令\n" +
                    "- `skill`，调用预定义的技能模块\n" +
                    "- `mcp`，MCP 网关工具集\n" +
                    "- `code`，编码指导模块\n" +
                    "- `todo`，任务清单管理\n" +
                    "- `webfetch`，直接抓取特定网页内容\n" +
                    "- `websearch`，互联网通用搜索\n" +
                    "- `codesearch`，互联网代码仓库搜索\n" +
                    "- `*`，全量授权") List<String> tools,
            @Param(name = "skills", description = "子代理具备的特定专家能力标识列表", required = false) List<String> skills,
            @Param(name = "saveToFile", description = "是否持久化。true：写入工作区代理目录，所有会话可用（通用、可复用的专家角色）；false：仅当前会话可用（临时任务助手）。", defaultValue = "false", required = false) Boolean saveToFile,
            String __cwd,
            String __sessionId
    ) {
        if (name == null || !name.matches("^[a-zA-Z0-9_-]+$")) {
            return "ERROR: name 标识符不合法，仅允许使用英文字符、数字、下划线或中划线。";
        }

        // 保留名（main / general / 内置代理）不可创建：同名会把所有会话的内置代理换成模型生成的提示词
        if (engine.isReservedAgentName(name)) {
            return "ERROR: '" + name + "' 是保留名，请换一个名字。";
        }

        try {
            AgentDefinition definition = engine.getAgentManager()
                    .getAgent(AgentDefinition.AGENT_GENERAL)
                    .copy();

            definition.setSystemPrompt(systemPrompt);

            definition.getMetadata().setName(name);
            definition.getMetadata().setDescription(description);
            definition.getMetadata().setEnabled(true);

            definition.getMetadata().setTools(tools);
            definition.getMetadata().setSkills(skills);

            boolean shouldSave = saveToFile != null && saveToFile;
            AgentSession session = engine.getSessionOrNull(__sessionId);

            if (shouldSave) {
                // 全局：同名不覆盖（含挂载目录与已有文件），且受持久化策略约束（SERVER 缺省拒绝）
                if (engine.getAgentManager().hasAgent(name)) {
                    return "ERROR: 已存在同名的全局子代理，不覆盖：" + name;
                }
                if (engine.getAgentPersistPolicy().test(session, definition) == false) {
                    return "ERROR: 当前环境不允许创建全局子代理。请改用 saveToFile=false（仅本会话可用）。";
                }
                // 经工作区来源写入 <harnessAgents>/<name>.md（与工作区 agents 挂载同口径，不再硬编码 .soloncode、不随 cwd 漂移）
                String agentPath = engine.getSettings().getHarnessAgents() + name + ".md";
                if (engine.getWorkspaceSource().exists(agentPath)) {
                    return "ERROR: 代理定义文件已存在，不覆盖：" + agentPath;
                }
                if (engine.getAgentManager().addAgentIfAbsent(definition) == false) {
                    return "ERROR: 已存在同名的全局子代理，不覆盖：" + name;
                }
                engine.getWorkspaceSource().writeString(agentPath, definition.toMarkdown());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Agent 定义已保存到: {}", agentPath);
                }
            } else {
                // 会话级：只对本会话可见（随会话快照保存，不进引擎级注册表）
                if (session == null) {
                    return "ERROR: 缺少会话上下文，无法创建仅本会话可用的子代理。";
                }
                if (engine.getAgentManager().hasAgent(name)) {
                    return "ERROR: 与全局子代理同名：" + name + "，请换一个名字。";
                }
                if (SessionAgents.putIfAbsent(session, definition, SessionAgents.MAX_PER_SESSION) == false) {
                    return "ERROR: 本会话已有同名子代理，或会话级子代理数量已达上限（"
                            + SessionAgents.MAX_PER_SESSION + "）。";
                }
                session.updateSnapshot();
            }

            return "[OK] 子代理创建成功（" + (shouldSave ? "全局" : "仅本会话") + "）！\n\n" +
                    String.format("**标识**: %s\n", name) +
                    String.format("**描述**: %s\n", description) +
                    String.format("\n现在可以使用 `task(name=\"%s\", prompt=\"...\")` 来调用。", name);

        } catch (Throwable e) {
            LOG.error("创建子代理失败: name={}, error={}", name, e.getMessage(), e);
            return "ERROR: 创建子代理失败: " + e.getMessage();
        }
    }
}