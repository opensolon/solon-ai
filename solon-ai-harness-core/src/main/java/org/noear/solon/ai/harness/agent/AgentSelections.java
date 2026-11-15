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

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mount.catalog.SkillCatalog;
import org.noear.solon.ai.mount.catalog.SkillDescriptor;
import org.noear.solon.ai.talents.cli.SkillTalent;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 智能体声明的技能 / MCP（定义里的 {@code skills} / {@code mcpServers}，只记名字）。
 *
 * <p>用到该智能体时转成本轮的选择，与 {@code HarnessInput#selectedSkill} / {@code HarnessInput#selectedMcp}
 * 同一语义：收窄可见范围，并预加载选中技能的正文。三种用法都生效：选择器或 {@code @提及} 选中它对话
 * （含 goal / loop 等程序轮次）、被 {@code task} / {@code multitask} 委派。输入里的显式选择优先于声明。</p>
 *
 * <p>生效条件：声明非空；{@code tools} 分组展开后含 {@code skill} / {@code mcp}，且智能体与引擎都没有禁用它
 * （与 {@link AgentFactory} 挂载工具同口径）。不存在的名字（MCP 还有已停用的服务）直接剔除；
 * 全部失效时视为未声明——否则视野会被收窄成空，一个技能都看不到。</p>
 *
 * <p>选择是「视野」不是权限：模型仍可经 {@code skillread} / {@code call_tool} 点名访问范围外的项；
 * 强制边界是请求级作用域（{@code InputScope}），最后叠加、与这里的结果取交集。</p>
 *
 * <p>只对注册在案的智能体生效：主代理（{@code main}）不在智能体注册表里，也不支持声明；
 * 往 main.md 里写 {@code skills} 不会有任何效果。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class AgentSelections {
    private static final Logger LOG = LoggerFactory.getLogger(AgentSelections.class);

    private AgentSelections() {
    }

    /**
     * 智能体声明的技能（已剔除失效名，保持声明顺序）
     *
     * @param definition 智能体定义（null 返回 null）
     * @return 不满足生效条件时为 null
     */
    @Nullable
    public static List<String> skillsOf(HarnessEngine engine, @Nullable AgentDefinition definition) {
        if (definition == null || definition.getMetadata().hasSkills() == false
                || hasTool(engine, definition.getMetadata(), ToolName.TOOL_SKILL.getName()) == false) {
            return null;
        }

        final List<String> valid = filterSkills(engine, definition.getMetadata().getSkills());
        return validOrNull(definition, "skills", definition.getMetadata().getSkills(), valid);
    }

    /**
     * 智能体声明的 MCP 服务（已剔除不存在与已停用的，保持声明顺序）
     *
     * @param definition 智能体定义（null 返回 null）
     * @return 不满足生效条件时为 null
     */
    @Nullable
    public static List<String> mcpsOf(HarnessEngine engine, @Nullable AgentDefinition definition) {
        if (definition == null || definition.getMetadata().hasMcpServers() == false
                || hasTool(engine, definition.getMetadata(), ToolName.TOOL_MCP.getName()) == false) {
            return null;
        }

        final List<String> valid = filterMcps(engine, definition.getMetadata().getMcpServers());
        return validOrNull(definition, "mcpServers", definition.getMetadata().getMcpServers(), valid);
    }

    /**
     * 剔除不可访问的技能标识（与技能清单收窄同口径，见 {@link SkillTalent#matchesSelected}），保持原顺序。
     *
     * <p>用于会话级选择：可能是早先存的、现在已卸载或不可访问的标识。返回空表示全部失效。</p>
     *
     * @since 4.2.0
     */
    public static List<String> filterSkills(HarnessEngine engine, @Nullable Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptyList();
        }

        final SkillCatalog catalog = engine.getSkillCatalog();
        final Collection<SkillDescriptor> descriptors = catalog.getDescriptors();
        final List<String> valid = new ArrayList<>();
        for (String name : normalize(names)) {
            if (isSkillAvailable(catalog, descriptors, name)) {
                valid.add(name);
            }
        }

        return Collections.unmodifiableList(valid);
    }

    /**
     * 剔除不存在与已停用的 MCP 服务名，保持原顺序。返回空表示全部失效。
     *
     * @since 4.2.0
     */
    public static List<String> filterMcps(HarnessEngine engine, @Nullable Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptyList();
        }

        final List<String> valid = new ArrayList<>();
        for (String name : normalize(names)) {
            McpClientProvider provider = engine.getMcpServer(name);
            if (provider != null && provider.isEnabled()) {
                valid.add(name);
            }
        }

        return Collections.unmodifiableList(valid);
    }

    /**
     * 智能体是否挂载指定工具（与 {@link AgentFactory} 装配同口径）：{@code tools} 经 {@link ToolName#expand}
     * 展开后含它，且智能体与引擎的禁用列表都不含它。
     *
     * @param toolName 逻辑工具名（如 {@code skill}、{@code mcp}）
     */
    public static boolean hasTool(HarnessEngine engine, AgentDefinition.Metadata metadata, String toolName) {
        if (metadata.getTools() == null) {
            return false;
        }

        if (metadata.getDisallowedTools() != null && metadata.getDisallowedTools().contains(toolName)) {
            return false;
        }

        if (engine.getDisallowedTools().contains(toolName)) {
            return false;
        }

        for (String name : metadata.getTools()) {
            if (name != null && ToolName.expand(name).contains(toolName)) {
                return true;
            }
        }

        return false;
    }

    /** 名字至少对得上一个可访问的技能（与技能清单收窄同口径，见 {@link SkillTalent#matchesSelected}） */
    private static boolean isSkillAvailable(SkillCatalog catalog, Collection<SkillDescriptor> descriptors, String name) {
        final List<String> one = Collections.singletonList(name);
        for (SkillDescriptor descriptor : descriptors) {
            if (catalog.isAllowed(descriptor) && SkillTalent.matchesSelected(descriptor, one)) {
                return true;
            }
        }
        return false;
    }

    /** trim + 丢空 + 去重保序（与 HarnessInput 的选择归一同口径） */
    private static Set<String> normalize(Collection<String> names) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && name.trim().isEmpty() == false) {
                normalized.add(name.trim());
            }
        }
        return normalized;
    }

    @Nullable
    private static List<String> validOrNull(AgentDefinition definition, String field, List<String> declared,
                                            List<String> valid) {
        if (valid.isEmpty()) {
            // 全部失效：不收窄（收窄成空等于把这一类能力全关掉），但要留下配置问题的痕迹
            LOG.debug("Agent '{}' declares {} {}, but none of them is available; the declaration is ignored",
                    definition.getName(), field, declared);
            return null;
        }

        if (valid.size() < declared.size() && LOG.isDebugEnabled()) {
            LOG.debug("Agent '{}' declares {} {}, available: {}", definition.getName(), field, declared, valid);
        }

        return Collections.unmodifiableList(valid);
    }
}
