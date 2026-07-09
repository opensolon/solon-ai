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
package org.noear.solon.ai.talents.cli.impl;

import org.noear.solon.ai.talents.cli.SkillProvider;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.SkillDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author noear 2026/6/15 created
 *
 */
public class MountSkillProvider implements SkillProvider {
    private final MountManager mountManager;

    public MountSkillProvider(MountManager mountManager){
        this.mountManager = mountManager;
    }
    @Override
    public void refresh() {
        mountManager.refresh();
    }

    @Override
    public void refreshByGroup(String groupName) {
        mountManager.refresh(groupName);
    }

    @Override
    public int getSkillCount() {
        return mountManager.getSkillCount();
    }

    @Override
    public Collection<SkillDir> getSkillAll() {
        return mountManager.getSkills();
    }

    @Override
    public Collection<SkillDir> searchSkill(String query) {
        Collection<SkillDir> skillList = mountManager.getSkills();
        String[] keys = query.toLowerCase().split("\\s+");

        List<SkillDir> matches = skillList.stream()
                .filter(s -> Arrays.stream(keys).anyMatch(k ->
                        s.getName().toLowerCase().contains(k) ||
                                s.getDescription().toLowerCase().contains(k)))
                .limit(15)
                .collect(Collectors.toList());

        return matches;
    }

    @Override
    public SkillDir getSkill(String name) {
        return mountManager.getSkill(name);
    }

    @Override
    public String readSkill(String name) {
        // 从内存 Map 查找逻辑路径
        SkillDir cachedSkill = mountManager.getSkill(name);
        if (cachedSkill != null) {
            return renderSkillXml(cachedSkill, true);
        }

        return null;
    }

    // --- 核心渲染与辅助逻辑 ---

    private String renderSkillXml(SkillDir skill, boolean includeFiles) {
        Path md = skill.getRealPath().resolve("SKILL.md");
        if (!Files.exists(md)) md = skill.getRealPath().resolve("skill.md");

        try {
            String content = Files.exists(md) ? new String(Files.readAllBytes(md), StandardCharsets.UTF_8) : "";

            StringBuilder sb = new StringBuilder("\n<skill_content name=\"" + skill.getName() + "\">\n");
            sb.append("[SYSTEM NOTE: Access granted. Use the <alias> paths in 'bash' tool for execution.]\n");
            sb.append("[If this task takes many steps, remember to re-read this skill if you feel uncertain about details.]\n");

            sb.append(content.trim()).append("\n\n");

            if (includeFiles) {
                sb.append("<skill_files>\n").append(sampleFiles(skill)).append("</skill_files>\n");
            }
            sb.append("</skill_content>\n");
            return sb.toString();
        } catch (IOException e) {
            return "Load skill " + skill.getName() + " failed.";
        }
    }

    private String sampleFiles(SkillDir skill) throws IOException {
        Path dir = skill.getRealPath();
        String aliasBase = skill.getAliasPath();

        // 定义忽略列表，过滤掉干扰项
        Set<String> ignorePatterns = new HashSet<>(Arrays.asList(
                ".DS_Store", "__pycache__", ".git", ".idea", ".vscode", "node_modules", "venv"
        ));

        try (Stream<Path> stream = Files.walk(dir, 3)) { // 深度增至 3，以便看到 scripts/ 下的内容
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        // 过滤 SKILL.md 本身和隐藏文件/杂质
                        return !name.equalsIgnoreCase("SKILL.md") &&
                                !ignorePatterns.contains(name) &&
                                !name.startsWith(".");
                    })
                    .map(p -> {
                        String relative = dir.relativize(p).toString().replace("\\", "/");
                        String logicalPath = (aliasBase + "/" + relative).replace("//", "/");

                        // 返回结构化标签
                        return String.format(
                                "  <file>\n" +
                                        "    <rel>%s</rel>\n" +
                                        "    <logicalPath>%s</logicalPath>\n" +
                                        "  </file>",
                                relative, logicalPath
                        );
                    })
                    .collect(Collectors.joining("\n"));
        }
    }
}
