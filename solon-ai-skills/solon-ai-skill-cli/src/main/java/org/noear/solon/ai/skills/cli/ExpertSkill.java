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
package org.noear.solon.ai.skills.cli;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.annotation.Param;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 专家技能管理器
 *
 * 支持三阶段模式自动切换：
 * 1. FULL: 数量 <= dynamicThreshold，全量平铺。
 * 2. DYNAMIC: 数量 <= searchThreshold，指令内展示清单。
 * 3. SEARCH: 数量 > searchThreshold，强制搜索。
 */
public class ExpertSkill extends AbsSkill {
    private final PoolManager skillManager;
    private int dynamicThreshold = 8; // 超过此值，不再平铺注入所有 SKILL.md
    private int searchThreshold = 80;  // 超过此值，不再展示摘要清单，进入强制搜索

    public ExpertSkill(PoolManager skillManager) {
        this.skillManager = skillManager;
    }

    public PoolManager getSkillManager() {
        return skillManager;
    }

    @Override
    public String description() {
        return "专家技能管理器。支持从本地或资源池发现并加载专家技能 (SKILL.md)。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        Map<String, PoolManager.SkillDir> skillMap = skillManager.getSkillMap();
        if (skillMap.isEmpty()) return null;

        int total = skillMap.size();
        StringBuilder sb = new StringBuilder();

        sb.append("优先使用合适的技能解决问题（不确定用什么技能时，可通过 skillsearch 搜索）\n\n");

        sb.append("## 专家技能库执行规约 (当前可用技能: " + total + ")\n");

        if (total <= dynamicThreshold && total > 0) {
            sb.append("已加载以下专家技能，其内容即为执行标准，请严格遵循：\n");
            for (PoolManager.SkillDir skill : skillMap.values()) {
                sb.append(renderSkillXml(skill, false));
            }
        } else if (total <= searchThreshold) {
            sb.append("检测到多个专家技能。在执行相关领域动作前，必须先调用 `skillread` 加载具体技能规约：\n");
            sb.append("<available_skills>\n");
            for (PoolManager.SkillDir skill : skillMap.values()) {
                sb.append("  <skill name=\"").append(skill.aliasPath).append("\">")
                        .append(skill.description).append("</skill>\n");
            }
            sb.append("</available_skills>");
        } else {
            sb.append("专家技能库规模较大。为了确保工程质量，请执行以下检索流程：\n");
            sb.append("1. **技能检索**：处理特定技术栈前，可以通过 `skillsearch` 检索对应的专家技能。\n");
            sb.append("2. **规约读取**：通过 `skillread` 获取技能对应的 SKILL.md 完整规约。\n");
        }

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        Map<String, PoolManager.SkillDir> skillMap = skillManager.getSkillMap();
        if (skillMap.isEmpty()) return null;

        int total = skillMap.size();
        if (total <= dynamicThreshold) {
            return tools.stream().filter(t -> t.name().equals("skillread")).collect(Collectors.toList());
        } else if (total <= searchThreshold) {
            return tools.stream().filter(t -> t.name().equals("skillread") || t.name().equals("skilllist")).collect(Collectors.toList());
        } else {
            return this.tools;
        }
    }

    @ToolMapping(name = "skilllist", description = "列出所有已挂载专家技能池中的可用清单。")
    public String skilllist() {
        Map<String, PoolManager.SkillDir> skillMap = skillManager.getSkillMap();
        if (skillMap.isEmpty()) return "当前没有可用的专家技能。";

        StringBuilder sb = new StringBuilder("可用专家技能列表：\n");
        for (PoolManager.SkillDir s : skillMap.values()) {
            sb.append("- ").append(s.aliasPath).append(": ").append(s.description).append("\n");
        }
        return sb.toString();
    }

    @ToolMapping(name = "skillsearch", description = "在所有专家技能池中搜索关键字。支持空格分隔多个词。")
    public String skillsearch(@Param("query") String query) {
        Map<String, PoolManager.SkillDir> skillMap = skillManager.getSkillMap();
        String[] keys = query.toLowerCase().split("\\s+");

        List<PoolManager.SkillDir> matches = skillMap.values().stream()
                .filter(s -> Arrays.stream(keys).anyMatch(k ->
                        s.aliasPath.toLowerCase().contains(k) ||
                                s.description.toLowerCase().contains(k)))
                .limit(15)
                .collect(Collectors.toList());

        if (matches.isEmpty()) return "未找到匹配专家技能。";

        StringBuilder sb = new StringBuilder("<search_results>\n");
        for (PoolManager.SkillDir s : matches) {
            sb.append("  <skill path=\"").append(s.aliasPath).append("\">")
                    .append(s.description).append("</skill>\n");
        }
        sb.append("</search_results>");
        return sb.toString();
    }

    @ToolMapping(name = "skillread", description = "加载特定专家技能的完整 SKILL.md 规约及其文件参考。")
    public String skillread(@Param("path") String path, String __workDir) throws IOException {
        Map<String, PoolManager.SkillDir> skillMap = skillManager.getSkillMap();
        // 1. 优先从内存 Map 查找逻辑路径
        PoolManager.SkillDir cachedSkill = skillMap.get(path);
        if (cachedSkill != null) {
            return renderSkillXml(cachedSkill, true);
        }

        // 2. 回退到物理路径解析
        Path target = resolvePathExtended(__workDir, path);
        if (!isSkillDir(target)) return "Error: 路径 " + path + " 不是有效的专家技能目录 (缺少 SKILL.md)";

        return renderSkillXml(new PoolManager.SkillDir(path, target, null), true);
    }

    @ToolMapping(name = "skillrefresh", description = "重新扫描所有专家技能池，更新专家技能列表。")
    public String skillrefresh() {
        skillManager.refresh();
        return "专家技能库已刷新，当前可用专家技能数：" + skillManager.getSkillMap().size();
    }

    // --- 核心渲染与辅助逻辑 ---

    private String renderSkillXml(PoolManager.SkillDir skill, boolean includeFiles) {
        Path md = skill.realPath.resolve("SKILL.md");
        if (!Files.exists(md)) md = skill.realPath.resolve("skill.md");

        try {
            String content = Files.exists(md) ? new String(Files.readAllBytes(md), StandardCharsets.UTF_8) : "";
            StringBuilder sb = new StringBuilder("\n<skill_content name=\"" + skill.aliasPath + "\">\n");
            sb.append(content.trim()).append("\n\n");
            sb.append("Base Directory: ").append(skill.aliasPath).append("\n");

            if (includeFiles) {
                sb.append("<skill_files>\n").append(sampleFiles(skill.realPath)).append("</skill_files>\n");
            }
            sb.append("</skill_content>\n");
            return sb.toString();
        } catch (IOException e) {
            return "Load skill " + skill.aliasPath + " failed.";
        }
    }

    private String sampleFiles(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir, 2)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equalsIgnoreCase("SKILL.md"))
                    .limit(10) // 增加到 10 个展示
                    .map(p -> "  <file>" + dir.relativize(p).toString().replace("\\", "/") + "</file>")
                    .collect(Collectors.joining("\n"));
        }
    }

    private boolean isSkillDir(Path p) {
        return Files.exists(p.resolve("SKILL.md")) || Files.exists(p.resolve("skill.md"));
    }

    private Path resolvePathExtended(String workDir, String pStr) {
        return skillManager.resolve(Paths.get(workDir), pStr);
    }
}