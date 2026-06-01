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
package org.noear.solon.ai.chat.talent;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.core.util.Assert;
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
public class TalentUtil {
    private static final Logger LOG = LoggerFactory.getLogger(TalentUtil.class);

    /**
     * 激活工具包
     */
    public static StringBuilder activeTalents(ModelOptionsAmend<?, ?> modelOptions, Prompt prompt, StringBuilder builder) {
        for (RankEntity<Talent> item : modelOptions.talents()) {
            Talent talent = item.target;

            try {
                if (talent.isSupported(prompt) == false) {
                    //不支持？跳过
                    continue;
                }
            } catch (Throwable e) {
                //出错？跳过
                LOG.error("Talent support check failed: {}", talent.getClass().getName(), e);
                continue;
            }

            try {
                // 开始挂载（可以做些初始化）
                talent.onAttach(prompt);
            } catch (Throwable e) {
                LOG.error("Talent active failed: {}", talent.getClass().getName(), e);
                throw e;
            }

            //聚合提示词
            injectTalentInstruction(talent, prompt, builder);

            //部署工具
            modelOptions.toolAdd(talent.getTools(prompt));
        }

        return builder;
    }


    /**
     * 注入指令并对工具进行“染色”
     */
    private static void injectTalentInstruction(Talent talent, Prompt prompt, StringBuilder combinedInstruction) {
        String ins = talent.getInstruction(prompt);

        if (Assert.isEmpty(ins)) {
            //如果指令为 null，不展示（只作工具集用）
            return;
        }

        Collection<FunctionTool> tools = talent.getTools(prompt);

        // 1. 如果有工具，进行元信息染色（借鉴 MCP 思想）
        if (Assert.isNotEmpty(tools)) {
            for (FunctionTool tool : tools) {
                // 将所属 Talent 的名字注入工具的 meta
                tool.metaPut("talent", talent.name());
            }
        }

        // 2. 构建 System Prompt 指令块
        if (Assert.isNotEmpty(ins) || Assert.isNotEmpty(tools)) {
            combinedInstruction.append("\n---\n"); // 使用分割线开启独立空间

            // 工具包标题行：# [Talent: Name] Description
            combinedInstruction.append("# [Talent: ").append(talent.name()).append("]");
            if (Utils.isNotEmpty(talent.description())) {
                combinedInstruction.append(" - ").append(talent.description());
            }
            combinedInstruction.append("\n<Talent:").append(talent.name()).append(">\n\n");

            // 注入工具包特有的指令（如数据库结构、API 限制等）
            if (Assert.isNotEmpty(ins)) {
                combinedInstruction.append(ins.trim()).append("\n");
            }

            // 显式声明该工具包控制的工具范围
            if (Assert.isNotEmpty(tools)) {
                String toolNames = tools.stream()
                        .map(t -> "`" + t.name() + "`") // 给工具名加反引号，增强识别度
                        .collect(Collectors.joining(", "));
                combinedInstruction.append("\n> **工具作用域**: 此工具包指令适用于以下工具的调用: ").append(toolNames).append("\n");
            }

            combinedInstruction.append("\n\n</Talent:").append(talent.name()).append(">\n");
            combinedInstruction.append("---\n"); // 闭合分割线
        }
    }
}