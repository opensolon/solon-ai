/*
 * Copyright 2017-2025 noear.org and authors
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
import org.noear.solon.ai.talents.cli.impl.MountSkillProvider;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.SkillDir;
import org.noear.solon.annotation.Param;

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
    private volatile SkillProvider skillProvider;

    private int listThreshold = 30;
    private int searchThreshold = 100;

    public SkillTalent(MountManager mountManager) {
        this.skillProvider = new MountSkillProvider(mountManager);
    }

    public SkillTalent(SkillProvider skillProvider) {
        this.skillProvider = skillProvider;
    }


    public SkillProvider getSkillProvider() {
        return skillProvider;
    }

    public void setSkillProvider(SkillProvider skillProvider) {
        Objects.requireNonNull(skillProvider, "skillProvider");

        this.skillProvider = skillProvider;
    }

    public SkillTalent listThreshold(int val) {
        this.listThreshold = val;
        return this;
    }

    public SkillTalent searchThreshold(int val) {
        this.searchThreshold = val;
        return this;
    }

    @Override
    public String description() {
        return "技能管理器。支持从本地或挂载点发现并加载技能 (SKILL.md)。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        Collection<SkillDir> skillList = skillProvider.getSkillAll();
        if (skillList.isEmpty()) return null;

        int total = skillList.size();
        StringBuilder sb = new StringBuilder();

        sb.append("优先使用合适的技能解决问题（不确定用什么技能时，可通过 skillsearch 搜索）。**注意：在执行任务中，请务必通过 `skillread` 读取或回顾规约。**\n\n");

        sb.append("## 技能库执行规约 (当前可用技能: " + total + ")\n");

        if (total <= listThreshold) {
            // --- 档位 1: SUMMARY  ---
            sb.append("### 运行模式: 摘要发现\n");
            sb.append("请审阅下方技能清单。若某个技能匹配当前需求，请调用 `skillread` 获取具体执行规约与工具参数：\n");

            sb.append("<skill_list>\n");
            for (SkillDir s : skillList) {
                sb.append("  <skill name=\"").append(s.getName()).append("\">").append(s.getDescription()).append("</skill>\n");
            }
            sb.append("</skill_list>");
        } else if (total <= searchThreshold) {
            // --- 模式 2: LIST ---
            sb.append("### 运行模式: 路径导航\n");
            sb.append("当前技能较多，仅展示路径索引（没有描述）。请推断功能并调用 `skillread`。如果不确定，请使用 `skillsearch` 检索：\n");

            sb.append("<skill_list>\n");
            for (SkillDir s : skillList) {
                sb.append("  <skill name=\"").append(s.getName()).append("\" />\n");
            }
            sb.append("</skill_list>");
        } else {
            // --- 模式 3: SEARCH ---
            sb.append("### 运行模式: 动态发现\n");
            sb.append("由于技能库规模较大。**禁止猜测路径**。你必须先通过 `skillsearch` 检索关键字，确认 name 后再调用 `skillread` 读取。\n");
        }

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        Collection<SkillDir> skillList = skillProvider.getSkillAll();
        if (skillList.isEmpty()) return null;

        int total = skillList.size();

        if (total <= listThreshold) {
            // 少量时，直接 read 即可，不需要 search 和 list 干扰
            return getToolAry().stream().filter(t -> t.name().equals("skillread")
                    || t.name().equals("skillrefresh")).collect(Collectors.toList());
        }

        if (total <= searchThreshold) {
            // 中等规模，保留所有（read, list, refresh），但不强制 search
            return getToolAry().stream().filter(t -> !t.name().equals("skillsearch")).collect(Collectors.toList());
        }

        // 大规模，强制搜索（全量工具开放）
        return getToolAry();
    }

    @ToolMapping(name = "skilllist", description = "列出本地所有挂载点中的可用技能清单。")
    public String skilllist() {
        Collection<SkillDir> skillList = skillProvider.getSkillAll();
        if (skillList.isEmpty()) {
            return "当前没有可用的技能。";
        }

        if (skillList.size() > searchThreshold) {
            return String.format("Error: 当前技能库规模较大 (%d)，禁止全量列出。请使用 `skillsearch` 配合关键字定位。", skillList.size());
        }

        StringBuilder sb = new StringBuilder("可用技能列表：\n");

        sb.append("<skill_list>\n");
        for (SkillDir s : skillList) {
            sb.append("  <skill name=\"").append(s.getName()).append("\">").append(s.getDescription()).append("</skill>\n");
        }
        sb.append("</skill_list>");

        return sb.toString();
    }

    @ToolMapping(name = "skillsearch", description = "在所有挂载点中搜索技能关键字。支持空格分隔多个词。")
    public String skillsearch(@Param("query") String query) {
        Collection<SkillDir> matches = skillProvider.searchSkill(query);

        if (matches.isEmpty()) return "未找到匹配技能。";

        StringBuilder sb = new StringBuilder("<skill_list>\n");
        for (SkillDir s : matches) {
            sb.append("  <skill name=\"").append(s.getName()).append("\">").append(s.getDescription()).append("</skill>\n");
        }
        sb.append("</skill_list>");

        return sb.toString();
    }

    @ToolMapping(name = "skillread", description = "读取本地技能详细说明书。在选定技能后、开始执行具体任务前，必须调用此工具以获取具体的环境要求、参数规约及可用文件别名。")
    public String skillread(@Param(value = "name", description = "技能的唯一路径标识（例如：'deep-research'）") String name, String __cwd) throws IOException {
        // 从内存 Map 查找逻辑路径
        String content = skillProvider.readSkill(name);

        if (content != null) {
            return content;
        }

        return "Error: " + name + " 不是有效的技能目录 (缺少 SKILL.md)";
    }

    @ToolMapping(name = "skillrefresh", description = "重新扫描所有挂载点，更新技能列表。")
    public String skillrefresh() {
        skillProvider.refresh();
        return "技能库已刷新，当前可用技能数：" + skillProvider.getSkillCount();
    }
}