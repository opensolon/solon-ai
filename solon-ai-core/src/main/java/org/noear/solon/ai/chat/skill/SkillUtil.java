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
package org.noear.solon.ai.chat.skill;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.core.util.RankEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 *
 * @author noear
 * @since 3.8.4
 */
public class SkillUtil {
    private static final Logger LOG = LoggerFactory.getLogger(SkillUtil.class);

    /**
     * 激活技能
     */
    public static StringBuilder activeSkills(ModelOptionsAmend<?, ?> modelOptions, Prompt prompt) {
        StringBuilder combinedInstruction = new StringBuilder();

        for (RankEntity<Skill> item : modelOptions.skills()) {
            Skill skill = item.target;

            try {
                if (skill.isSupported(prompt) == false) {
                    //不支持？跳过
                    continue;
                }
            } catch (Throwable e) {
                //出错？跳过
                LOG.error("Skill support check failed: {}", skill.getClass().getName(), e);
                continue;
            }

            try {
                // 挂载
                skill.onAttach(prompt);
            } catch (Throwable e) {
                LOG.error("Skill active failed: {}", skill.getClass().getName(), e);
                throw e;
            }

            //聚合提示词
            injectSkillInstruction(skill, prompt, combinedInstruction);

            //部署工具
            modelOptions.toolAdd(skill.getTools(prompt));
        }

        return combinedInstruction;
    }


    /**
     * 注入指令并对工具进行“染色”
     */
    private static void injectSkillInstruction(Skill skill, Prompt prompt, StringBuilder combinedInstruction) {
        String ins = skill.getInstruction(prompt);
        Collection<FunctionTool> tools = skill.getTools(prompt);

        // 1. 如果有工具，进行元信息染色（借鉴 MCP 思想）
        if (tools != null && !tools.isEmpty()) {
            for (FunctionTool tool : tools) {
                // 将所属 Skill 的名字注入工具的 meta
                tool.metaPut("skill", skill.name());
                // 如果需要，也可以把 Skill 的描述或其它元数据注入
                if (Utils.isNotEmpty(skill.description())) {
                    tool.metaPut("skill_desc", skill.description());
                }
            }
        }

        // 2. 构建 System Prompt 指令块
        if (Utils.isNotEmpty(ins) || (tools != null && !tools.isEmpty())) {
            if (combinedInstruction.length() > 0) {
                combinedInstruction.append("\n");
            }

            // 统一头部
            combinedInstruction.append("**Skill**: ").append(skill.name());

            // 补充 Skill 描述（如果有）
            if (Utils.isNotEmpty(skill.metadata().getDescription()) && !skill.name().equals(skill.metadata().getDescription())) {
                combinedInstruction.append(" (").append(skill.metadata().getDescription()).append(")");
            }
            combinedInstruction.append("\n");

            // 注入具体指令
            if (Utils.isNotEmpty(ins)) {
                combinedInstruction.append(ins).append("\n");
            }

            // 注入工具关联说明（告知模型这些工具受此 Skill 指令约束）
            if (tools != null && !tools.isEmpty()) {
                String toolNames = tools.stream().map(t -> t.name()).collect(Collectors.joining(", "));
                combinedInstruction.append("- **Supported Tools**: ").append(toolNames).append("\n");
            }
        }
    }
}