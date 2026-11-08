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
package org.noear.solon.ai.harness.agent;

import lombok.Getter;
import lombok.Setter;
import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.permission.PermissionBehavior;
import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.util.Markdown;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
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

    protected Metadata metadata = new Metadata();
    protected String systemPrompt;

    /**
     * 所属挂载别名（用于按挂载批量移除），null 表示内置代理
     */
    private String mountAlias;

    public AgentDefinition() {
        //用于反序列化
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
     * 从已解析的 Markdown 文档构建运行时定义。
     */
    public static AgentDefinition fromParsedMarkdown(Markdown markdown) {
        AgentDefinition definition = new AgentDefinition();
        if (markdown == null) {
            return definition;
        }

        markdown.getMetadata().bindTo(definition.metadata);
        definition.systemPrompt = markdown.getContent();

        // Snack4 无法直接反序列化 PermissionRule，由运行时模型负责转换。
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
        @Preview("4.0.4")
        private List<PermissionRule> permissionRules = new ArrayList<>();

        // ---- 以下为兼容 Claude Code 代理格式的保留字段：只做解析与回写，引擎暂不据此改变行为 ----
        // （skills 由 GenerateTalent 回填；其余字段原样保留，避免读改写时丢失用户配置）

        private List<String> skills;
        private List<String> mcpServers;
        private Object hooks;          // 暂不解析
        private String memory;         // user, project, local
        private Boolean background;
        private String isolation;      // worktree
        private String teamName;       // 所属团队名称（用于团队成员）

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

    /**
     * @since 4.0.4
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @since 4.0.4
     */
    public static class Builder {
        private AgentDefinition definition = new AgentDefinition();

        public Builder systemPrompt(String systemPrompt) {
            definition.setSystemPrompt(systemPrompt);
            return this;
        }

        public Builder primary(boolean primary) {
            definition.getMetadata().setPrimary(primary);
            return this;
        }

        public Builder hidden(boolean hidden) {
            definition.getMetadata().setHidden(hidden);
            return this;
        }

        public Builder name(String name) {
            definition.getMetadata().setName(name);
            return this;
        }

        public Builder description(String description) {
            definition.getMetadata().setDescription(description);
            return this;
        }

        public Builder toolsAdd(Collection<String> toolNames) {
            definition.getMetadata().addTools(toolNames);
            return this;
        }

        public Builder toolsAdd(String... toolNames) {
            definition.getMetadata().addTools(toolNames);
            return this;
        }

        public Builder disallowedToolsAdd(Collection<String> toolNames) {
            definition.getMetadata().getDisallowedTools().addAll(toolNames);
            return this;
        }

        public Builder disallowedToolsAdd(String... toolNames) {
            definition.getMetadata().getDisallowedTools().addAll(Arrays.asList(toolNames));
            return this;
        }

        public Builder metadata(Consumer<Metadata> build) {
            build.accept(definition.getMetadata());
            return this;
        }

        public AgentDefinition build() {
            return definition;
        }
    }
}
