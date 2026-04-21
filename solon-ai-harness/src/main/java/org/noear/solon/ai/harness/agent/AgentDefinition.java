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

import lombok.Getter;
import lombok.Setter;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.permission.ToolPermission;
import org.noear.solon.ai.util.Markdown;
import org.noear.solon.ai.util.MarkdownUtil;
import org.noear.solon.core.util.Assert;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 代理定义
 *
 * @author bai
 * @author noear
 * @since 3.9.5
 */
public class AgentDefinition {
    public static final String AGENT_MAIN = "main";
    public static final String AGENT_GENERAL = "general";

    protected Metadata metadata = new Metadata();
    protected String systemPrompt;

    /**
     * 复制
     */
    public AgentDefinition copy() {
        AgentDefinition definition = new AgentDefinition();

        definition.metadata = ONode.ofBean(metadata).toBean(Metadata.class);
        definition.systemPrompt = systemPrompt;

        return definition;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public boolean isHidden() {
        return metadata.isHidden();
    }

    public String getName() {
        return metadata.getName();
    }

    public String getDescription() {
        return metadata.getDescription();
    }

    public String getModel() {
        return metadata.getModel();
    }

    public void setMetadata(Metadata metadata) {
        if (metadata == null) {
            this.metadata = new Metadata();
        } else {
            this.metadata = metadata;
        }
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    /**
     * 从系统提示词中解析元数据
     *
     * @param markdownStr 系统提示词
     * @return 解析出的元数据对象
     */
    public static AgentDefinition fromMarkdown(String markdownStr) {
        AgentDefinition definition = new AgentDefinition();

        if (markdownStr == null || markdownStr.isEmpty()) {
            return definition;
        }

        Markdown markdown = MarkdownUtil.resolve(Arrays.asList(markdownStr.split("\n")));

        markdown.getMetadata().bindTo(definition.metadata);
        definition.systemPrompt = markdown.getContent();

        return definition;
    }

    /**
     * 从文件行列表解析子代理元数据和提示词
     *
     * @param lines 文件内容行列表
     * @return 包含元数据和提示词的对象
     */
    public static AgentDefinition fromMarkdown(List<String> lines) {
        AgentDefinition definition = new AgentDefinition();

        if (lines == null || lines.isEmpty()) {
            return definition;
        }

        Markdown markdown = MarkdownUtil.resolve(lines);

        definition.metadata = markdown.getMetadata().toBean(Metadata.class);
        definition.systemPrompt = markdown.getContent();

        return definition;
    }


    public String toMarkdown() {
        StringBuilder buf = new StringBuilder();
        metadata.injectYamlFrontmatter(buf);

        if (Assert.isNotEmpty(systemPrompt)) {
            buf.append(systemPrompt);
        }

        return buf.toString();
    }

    public ReActAgent.Builder builder(HarnessEngine agentRuntime) {
        return AgentFactory.create(agentRuntime, this);
    }


    //-------------------------


    /**
     * 代理元数据
     *
     * @author bai
     * @author noear
     * @since 3.9.5
     */
    @Setter
    @Getter
    public static class Metadata {
        private boolean enabled = true;
        private boolean hidden = false;
        private boolean primary = false;

        // 必需字段
        private String name;
        private String description;

        // 模型配置
        private String model;

        // 最大步数（执行限制）
        private Integer maxSteps;

        // 最大步数自动扩展（新增）
        private Boolean maxStepsAutoExtensible;

        private Integer sessionWindowSize;
        private Integer summaryWindowSize;
        private Integer summaryWindowToken;

        // 工具配置
        private List<String> tools = new ArrayList<>();

        // 禁用工具
        private List<String> disallowedTools = new ArrayList<>();

        // 权限配置
        private String permissionMode;

        // 执行限制（最大回合）
        private Integer maxTurns;

        // Skills 配置
        private List<String> skills;

        // MCP Servers 配置
        private List<String> mcpServers;

        // Hooks 配置（暂不解析，保留字段）
        private Object hooks;

        // 记忆配置
        private String memory;  // user, project, local

        // 后台任务
        private Boolean background;

        // 隔离配置
        private String isolation;  // worktree

        // 团队配置
        private String teamName;  // 所属团队名称（用于团队成员）

        protected void injectYamlFrontmatter(StringBuilder buf) {
            String yaml = new Yaml().dump(ONode.ofBean(this).toBean(Map.class));

            if (Assert.isNotEmpty(yaml)) {
                buf.append("---\n");
                buf.append(yaml);
                buf.append("---\n\n");
            }
        }

        public void addTools(String... toolNames) {
            tools.addAll(Arrays.asList(toolNames));
        }

        public void addTools(ToolPermission... toolPermissions) {
            for (ToolPermission p1 : toolPermissions) {
                tools.add(p1.getName());
            }
        }
    }
}