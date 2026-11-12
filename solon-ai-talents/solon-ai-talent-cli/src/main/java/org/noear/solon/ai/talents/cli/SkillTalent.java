/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.mount.catalog.SkillCatalog;
import org.noear.solon.ai.mount.catalog.SkillContent;
import org.noear.solon.ai.mount.catalog.SkillDescriptor;
import org.noear.solon.ai.talents.cli.i18n.CliI18n;
import org.noear.solon.ai.talents.cli.i18n.CliI18nKeys;
import org.noear.solon.annotation.Param;
import org.noear.solon.lang.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 技能管理器
 *
 * 支持三阶段模式自动切换：
 * 1. SUMMARY: 数量 <= listThreshold。展示带描述的技能清单。
 * 2. LIST: 数量 <= searchThreshold。仅展示技能路径索引。
 * 3. SEARCH: 数量 > searchThreshold。强制搜索模式。
 */
public class SkillTalent extends AbsTalent {
    public final static String CTX_SKILLS_SELECTED = "_skills_selected";
    public static List<String> getSelected(Prompt prompt) {
        if (prompt == null) {
            return Collections.emptyList();
        }

        return getSelected(prompt.attrs());
    }

    public static List<String> getSelected(Map<String,Object> toolContext) {
        return toSelected(toolContext, CTX_SKILLS_SELECTED);
    }

    /**
     * 弱类型读取视野：toolContext 本质是 {@code Map<String,Object>}，
     * 宿主可能写 Collection / 数组 / 逗号分隔字符串。强转会在类型不符时
     * 抛 ClassCastException 打死整轮对话，所以统一归一化。
     */
    public static List<String> toSelected(Map<String,Object> map, String key) {
        if (map == null) {
            return Collections.emptyList();
        }

        Object tmp = map.get(key);
        if (tmp == null) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        if (tmp instanceof Collection) {
            for (Object item : (Collection<?>) tmp) {
                if (item != null && item.toString().trim().length() > 0) {
                    result.add(item.toString().trim());
                }
            }
        } else if (tmp instanceof String[]) {
            for (String item : (String[]) tmp) {
                if (item != null && item.trim().length() > 0) {
                    result.add(item.trim());
                }
            }
        } else {
            // 单值（含逗号分隔）兑底
            for (String item : tmp.toString().split(",")) {
                if (item.trim().length() > 0) {
                    result.add(item.trim());
                }
            }
        }

        return result;
    }

    public static void putSelected(Prompt prompt, List<String> skillNames){
         prompt.attrPut(CTX_SKILLS_SELECTED, skillNames);
    }

    /**
     * 技能工具的可用名单（值为 {@code List<String>}，与「视野」不同）：键存在时，
     * skilllist / skillsearch / skillread 及系统提示里的清单与预加载只认名单内的技能；
     * 空名单表示全部禁止，越界按「不存在」处理。由宿主的请求级作用域写入。
     *
     * <p>只约束技能工具：技能挂载点仍可经文件工具或 bash 访问。需要文件级隔离时，
     * 不要把名单外的技能挂进该引擎。</p>
     *
     * @since 4.2.0
     */
    public final static String CTX_SKILLS_ALLOWED = "_skills_allowed";

    /**
     * 读取可用名单（未设置返回 null = 不限制）
     *
     * @since 4.2.0
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static List<String> getAllowed(@Nullable Map<String, Object> map) {
        return map == null ? null : (List<String>) map.get(CTX_SKILLS_ALLOWED);
    }


    private volatile SkillCatalog skillCatalog;

    private int listThreshold = 30;
    private int searchThreshold = 100;
    /**
     * 选中技能的预加载字符预算（默认 12000，0 表示不预加载）
     * <p>
     * 显式选中的技能通常意味着「本次就要用它」，预加载可以省掉模型的一个 skillread 来回；
     * 但技能正文可能很大，因此按字符做预算：超预算的技能降级为「仅列名」，
     * 由模型自行 skillread。
     */
    private int preloadCharLimit = 12000;

    public SkillTalent(SkillCatalog skillCatalog) {
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog");
    }

    public SkillCatalog getSkillCatalog() {
        return skillCatalog;
    }

    public void setSkillCatalog(SkillCatalog skillCatalog) {
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog");
    }

    public SkillTalent listThreshold(int val) {
        this.listThreshold = val;
        return this;
    }

    public SkillTalent searchThreshold(int val) {
        this.searchThreshold = val;
        return this;
    }

    /**
     * 选中技能的预加载字符预算（默认 12000，0 表示只收窄清单、不预加载）
     */
    public SkillTalent preloadCharLimit(int val) {
        this.preloadCharLimit = Math.max(0, val);
        return this;
    }

    @Override
    public String description(Prompt prompt) {
        return CliI18n.get(AiI18n.localeOf(prompt), CliI18nKeys.SKILL_DESCRIPTION);
    }

    @Override
    public String getInstruction(Prompt prompt) {
        Collection<SkillDescriptor> skillList = availableSkills(prompt);
        if (skillList.isEmpty()) return null;

        Locale locale = AiI18n.localeOf(prompt);
        int total = skillList.size();
        StringBuilder sb = new StringBuilder();

        sb.append(CliI18n.get(locale, CliI18nKeys.SKILL_INSTRUCTION_HEAD)).append("\n\n");

        // 显式选中的技能：先给正文，再给清单（让模型不必为一个已选技能多跑一次 skillread）
        appendPreloadedSkills(prompt, sb, locale);

        sb.append(CliI18n.fmt(locale, CliI18nKeys.SKILL_INSTRUCTION_TITLE, "total", total)).append("\n");

        if (total <= listThreshold) {
            // --- 档位 1: SUMMARY  ---
            sb.append(CliI18n.get(locale, CliI18nKeys.SKILL_INSTRUCTION_SUMMARY)).append("\n");

            sb.append("<skill_list>\n");
            for (SkillDescriptor s : skillList) {
                sb.append("  <skill name=\"").append(s.getId()).append("\">").append(s.getDescription()).append("</skill>\n");
            }
            sb.append("</skill_list>");
        } else if (total <= searchThreshold) {
            // --- 模式 2: LIST ---
            sb.append(CliI18n.get(locale, CliI18nKeys.SKILL_INSTRUCTION_LIST)).append("\n");

            sb.append("<skill_list>\n");
            for (SkillDescriptor s : skillList) {
                sb.append("  <skill name=\"").append(s.getId()).append("\" />\n");
            }
            sb.append("</skill_list>");
        } else {
            // --- 模式 3: SEARCH ---
            sb.append(CliI18n.get(locale, CliI18nKeys.SKILL_INSTRUCTION_SEARCH)).append("\n");
        }

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        Collection<SkillDescriptor> skillList = availableSkills(prompt);
        if (skillList.isEmpty()) return null;

        int total = skillList.size();
        // 按请求语言本地化的工具描述
        Collection<FunctionTool> tools = super.getTools(prompt);

        if (total <= listThreshold) {
            // 少量时，直接 read 即可，不需要 search 和 list 干扰
            return tools.stream().filter(t -> t.name().equals("skillread")
                    || t.name().equals("skillrefresh")).collect(Collectors.toList());
        }

        if (total <= searchThreshold) {
            // 中等规模，保留所有（read, list, refresh），但不强制 search
            return tools.stream().filter(t -> !t.name().equals("skillsearch")).collect(Collectors.toList());
        }

        // 大规模，强制搜索（全量工具开放）
        return tools;
    }

    @ToolMapping(name = "skilllist", description = "列出本地所有挂载点中的可用技能清单。")
    public String skilllist(List<String> _skills_selected, List<String> _skills_allowed, String __locale) {
        // _skills_selected / _skills_allowed 由 toolContext 隐式注入（未设置时为 null），模型不可见；
        // 与指令清单同口径：只列视野内的技能
        Locale locale = AiI18n.toLocale(__locale);
        Collection<SkillDescriptor> skillList = availableSkills(_skills_selected, _skills_allowed);
        if (skillList.isEmpty()) {
            return CliI18n.get(locale, CliI18nKeys.SKILL_LIST_EMPTY);
        }

        if (skillList.size() > searchThreshold) {
            return CliI18n.fmt(locale, CliI18nKeys.SKILL_LIST_TOO_MANY, "count", skillList.size());
        }

        StringBuilder sb = new StringBuilder(CliI18n.get(locale, CliI18nKeys.SKILL_LIST_TITLE)).append("\n");

        sb.append("<skill_list>\n");
        for (SkillDescriptor s : skillList) {
            sb.append("  <skill name=\"").append(s.getId()).append("\">").append(s.getDescription()).append("</skill>\n");
        }
        sb.append("</skill_list>");

        return sb.toString();
    }

    @ToolMapping(name = "skillsearch", description = "在所有挂载点中搜索技能关键字。支持空格分隔多个词。")
    public String skillsearch(@Param("query") String query, List<String> _skills_selected, List<String> _skills_allowed,
                              String __locale) {
        Collection<SkillDescriptor> matches = skillCatalog.searchDescriptors(query);

        if (matches.isEmpty()) return CliI18n.get(AiI18n.toLocale(__locale), CliI18nKeys.SKILL_SEARCH_NONE);

        StringBuilder sb = new StringBuilder("<skill_list>\n");
        for (SkillDescriptor s : matches) {
            if (isAvailable(s, _skills_selected, _skills_allowed)) {
                sb.append("  <skill name=\"").append(s.getId()).append("\">").append(s.getDescription()).append("</skill>\n");
            }
        }
        sb.append("</skill_list>");

        return sb.toString();
    }

    @ToolMapping(name = "skillread", description = "读取本地技能详细说明书。在选定技能后、开始执行具体任务前，必须调用此工具以获取具体的环境要求、参数规约及可用文件别名。")
    public String skillread(@Param(value = "name", description = "技能的唯一路径标识（例如：'deep-research'）") String name,
                            List<String> _skills_allowed,
                            String __locale) throws IOException {
        // 从技能目录按逻辑标识查找（与会话工作目录无关，故不接收 __cwd）
        SkillContent content = skillCatalog.readContent(name);

        // 可用名单：越界与不存在同一口径（不泄露名单外的技能是否存在）
        if (content != null && isAllowedBy(content.getDescriptor(), _skills_allowed)) {
            return content.getRenderedText();
        }

        return CliI18n.fmt(AiI18n.toLocale(__locale), CliI18nKeys.SKILL_READ_INVALID, "name", name);
    }

    /**
     * 当前视野内的技能：未指定选择时返回全部允许项；指定时按标识收窄
     *
     * <p>注意这是「视野」而不是「权限」：{@code skillread} 仍按 catalog 的 isAllowed 口径放行，
     * 否则被选技能的 SKILL.md 里引用另一个技能时会断链。</p>
     */
    private Collection<SkillDescriptor> availableSkills(@Nullable Prompt prompt) {
        return availableSkills(getSelected(prompt), prompt == null ? null : getAllowed(prompt.attrs()));
    }

    private Collection<SkillDescriptor> availableSkills(@Nullable List<String> selected, @Nullable List<String> allowed) {
        return skillCatalog.getDescriptors().stream()
                .filter(s -> isAvailable(s, selected, allowed))
                .collect(Collectors.toList());
    }

    /** 列表/搜索口径：目录放行 + 在可用名单内 + 在视野内（视野为空表示不收窄） */
    private boolean isAvailable(SkillDescriptor s, @Nullable List<String> selected, @Nullable List<String> allowed) {
        return skillCatalog.isAllowed(s)
                && isAllowedBy(s, allowed)
                && (selected == null || selected.isEmpty() || matchesSelected(s, selected));
    }

    /** 可用名单为 null 表示不限制 */
    private static boolean isAllowedBy(SkillDescriptor s, @Nullable List<String> allowed) {
        return allowed == null || matchesSelected(s, allowed);
    }

    /**
     * 与 {@code DefaultSkillCatalog#find} 同口径的匹配：完整 id / 短名 / 无前缀写入时的 @id
     */
    private static boolean matchesSelected(SkillDescriptor descriptor, List<String> selected) {
        String id = descriptor.getId();
        String name = descriptor.getName();

        for (String item : selected) {
            if (item.equals(id) || item.equals(name)) {
                return true;
            }
            
            if (item.startsWith("@") == false && ("@" + item).equals(id)) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 预加载选中技能的正文（受 {@link #preloadCharLimit} 约束）
     */
    private void appendPreloadedSkills(@Nullable Prompt prompt, StringBuilder sb, Locale locale) {
        List<String> selected = getSelected(prompt);
        if (selected.isEmpty() || preloadCharLimit <= 0) {
            return;
        }
        List<String> allowed = prompt == null ? null : getAllowed(prompt.attrs());

        int remaining = preloadCharLimit;
        StringBuilder block = new StringBuilder();

        for (String name : selected) {
            SkillContent content = skillCatalog.readContent(name);
            if (content == null || isAllowedBy(content.getDescriptor(), allowed) == false) {
                // 不存在（或越界）的选择名忽略，不报错：一个过期技能名不应打死整轮对话
                continue;
            }

            if (content.getRenderedText().length() > remaining) {
                block.append("<preloaded_skill name=\"").append(content.getDescriptor().getId())
                        .append("\" skipped=\"over_budget\" />\n");
                continue;
            }

            remaining -= content.getRenderedText().length();
            block.append("<preloaded_skill name=\"").append(content.getDescriptor().getId()).append("\">\n")
                    .append(content.getRenderedText().trim()).append("\n</preloaded_skill>\n");
        }

        if (block.length() > 0) {
            sb.append(CliI18n.get(locale, CliI18nKeys.SKILL_INSTRUCTION_PRELOADED_TITLE)).append("\n");
            sb.append(block).append("\n");
        }
    }

    @ToolMapping(name = "skillrefresh", description = "重新扫描所有挂载点，更新技能列表。")
    public String skillrefresh(String __locale) {
        skillCatalog.refresh();
        return CliI18n.fmt(AiI18n.toLocale(__locale), CliI18nKeys.SKILL_REFRESH_OK, "count", skillCatalog.getSkillCount());
    }
}