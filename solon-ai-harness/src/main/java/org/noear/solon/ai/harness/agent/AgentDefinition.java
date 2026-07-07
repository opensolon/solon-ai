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
import org.noear.solon.ai.harness.permission.PermissionBehavior;
import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.harness.permission.ToolPermission;
import org.noear.solon.ai.util.Markdown;
import org.noear.solon.ai.util.MarkdownUtil;
import org.noear.solon.core.util.Assert;
import org.yaml.snakeyaml.Yaml;

import java.util.*;
import java.util.function.Consumer;

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
    public final static String ATTR_PERMISSION_CONTEXT = "__permissionContext";

    protected Metadata metadata = new Metadata();
    protected String systemPrompt;

    /**
     * 所属挂载别名（用于按挂载批量移除），null 表示内置代理
     */
    private String mountAlias;

    public AgentDefinition() {
        //用于反序列化
    }

    public AgentDefinition(String name) {
        getMetadata().setName(name);
    }

    public AgentDefinition systemPrompt(String systemPrompt){
        setSystemPrompt(systemPrompt);
        return this;
    }

    public AgentDefinition metadata(Consumer<Metadata> build){
        build.accept(getMetadata());
        return this;
    }


    /**
     * 复制
     */
    public AgentDefinition copy() {
        AgentDefinition definition = new AgentDefinition();

        // 暂存规则，避免 Snack4 无法处理 PermissionRule
        List<PermissionRule> savedRules = metadata.permissionRules;
        metadata.permissionRules = null;

        definition.metadata = ONode.ofBean(metadata).toBean(Metadata.class);

        metadata.permissionRules = savedRules;
        definition.metadata.permissionRules = savedRules != null ? new ArrayList<>(savedRules) : null;

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

    public String getMountAlias() {
        return mountAlias;
    }

    public void setMountAlias(String mountAlias) {
        this.mountAlias = mountAlias;
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

        // 手动解析 permissionRules
        ONode rulesNode = markdown.getMetadata().get("permissionRules");
        if (rulesNode != null && rulesNode.isArray()) {
            List<PermissionRule> rules = new ArrayList<>();
            for (ONode ruleNode : rulesNode.getArray()) {
                String toolName = ruleNode.get("toolName").getString();
                if (toolName == null) continue;

                String behaviorStr = ruleNode.get("behavior").getString();
                if (behaviorStr == null) continue;

                String pattern = ruleNode.get("pattern").getString();
                int priority = ruleNode.get("priority").getInt();
                PermissionBehavior behavior = PermissionBehavior.valueOf(behaviorStr.toUpperCase());

                if (pattern != null && !pattern.isEmpty()) {
                    rules.add(PermissionRule.withPattern(toolName, behavior, pattern, priority));
                } else {
                    rules.add(PermissionRule.of(toolName, behavior, priority));
                }
            }
            definition.metadata.setPermissionRules(rules);
        }

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

        // 手动解析 permissionRules（Snack4 无法直接反序列化 PermissionRule）
        ONode rulesNode = markdown.getMetadata().get("permissionRules");
        if (rulesNode != null && rulesNode.isArray()) {
            List<PermissionRule> rules = new ArrayList<>();
            for (ONode ruleNode : rulesNode.getArray()) {
                String toolName = ruleNode.get("toolName").getString();
                if (toolName == null) continue;

                String behaviorStr = ruleNode.get("behavior").getString();
                if (behaviorStr == null) continue;

                String pattern = ruleNode.get("pattern").getString();
                int priority = ruleNode.get("priority").getInt();
                PermissionBehavior behavior = PermissionBehavior.valueOf(behaviorStr.toUpperCase());

                if (pattern != null && !pattern.isEmpty()) {
                    rules.add(PermissionRule.withPattern(toolName, behavior, pattern, priority));
                } else {
                    rules.add(PermissionRule.of(toolName, behavior, priority));
                }
            }
            definition.metadata.setPermissionRules(rules);
        }

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

    public ReActAgent.Builder builder(HarnessEngine agentRuntime, String sessionModel) {
        return AgentFactory.create(agentRuntime, this, sessionModel);
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

        // 允许工具
        private List<String> tools = new ArrayList<>();

        // 禁用工具
        private List<String> disallowedTools = new ArrayList<>();

        // 权限配置
        private List<PermissionRule> permissionRules = new ArrayList<>();

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
            // 暂存 permissionRules，避免 Snack4 序列化不识别的类型
            List<PermissionRule> savedRules = this.permissionRules;
            this.permissionRules = null;

            String yaml = new Yaml().dump(ONode.ofBean(this).toBean(Map.class));

            this.permissionRules = savedRules;

            if (Assert.isNotEmpty(yaml)) {
                buf.append("---\n");
                buf.append(yaml);

                // 手动追加 permissionRules（Snack4 无法直接序列化 PermissionRule）
                if (hasPermissionRules()) {
                    buf.append("permissionRules:\n");
                    for (PermissionRule rule : permissionRules) {
                        buf.append("  - toolName: \"").append(rule.toolName()).append("\"\n");
                        buf.append("    behavior: ").append(rule.behavior().name().toLowerCase()).append("\n");
                        rule.pattern().ifPresent(p ->
                                buf.append("    pattern: \"").append(p).append("\"\n"));
                        if (rule.priority() != 0) {
                            buf.append("    priority: ").append(rule.priority()).append("\n");
                        }
                    }
                }

                buf.append("---\n\n");
            }
        }

        public void addPermissionRule(PermissionRule rule) {
            permissionRules.add(rule);
        }

        public void addPermissionRules(List<PermissionRule> rules) {
            if (rules != null) {
                permissionRules.addAll(rules);
            }
        }

        public void addTools(Collection<String> toolNames) {
            tools.addAll(toolNames);
        }

        public void addTools(String... toolNames) {
            tools.addAll(Arrays.asList(toolNames));
        }

        public void addTools(ToolName... toolNames) {
            for (ToolName p1 : toolNames) {
                tools.add(p1.getName());
            }
        }

        /**
         * @deprecated 4.0.4
         *
         */
        @Deprecated
        public void addTools(ToolPermission... toolNames) {
            for (ToolPermission p1 : toolNames) {
                tools.add(p1.getName());
            }
        }

        //------------

        public boolean hasModel() {
            return model != null && !model.isEmpty();
        }

        public boolean hasPermissionRules() {
            return permissionRules != null && !permissionRules.isEmpty();
        }

        public boolean hasSkills() {
            return skills != null && !skills.isEmpty();
        }

        public boolean hasMcpServers() {
            return mcpServers != null && !mcpServers.isEmpty();
        }

        public boolean hasDisallowedTools() {
            return disallowedTools != null && !disallowedTools.isEmpty();
        }

        public boolean hasMemory() {
            return memory != null && !memory.isEmpty();
        }

        public boolean isBackground() {
            return background != null && background;
        }

        public boolean hasIsolation() {
            return isolation != null && !isolation.isEmpty();
        }

        public boolean hasTeamName() {
            return teamName != null && !teamName.isEmpty();
        }
    }
}