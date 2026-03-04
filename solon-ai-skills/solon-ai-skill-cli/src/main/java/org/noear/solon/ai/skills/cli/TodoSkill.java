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
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 任务进度追踪技能 (对齐 OpenCode/Claude Code 规范)
 *
 * @author noear
 * @since 3.9.5
 */
public class TodoSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(TodoSkill.class);

    @Override
    public String description() {
        return "任务进度追踪专家。通过维护 TODO.md 状态机，确保复杂任务的原子性与执行进度透明。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        // 返回 null 是正确的，让模型完全依赖工具描述，减少冗余上下文
        return null;
    }

    @ToolMapping(name = "todoread", description =
            "读取当前会话的任务清单。你应该主动且频繁地使用此工具以确保知晓任务状态。建议场景：\n" +
                    "- 开始新对话时查看历史遗留进度；\n" +
                    "- 在执行多个复杂步骤中途，确认下一步优先级；\n" +
                    "- 完成代码修改后，确认验证步骤是否已执行。\n" +
                    "如果文件不存在，将返回空提示。")
    public String todoRead(String __workDir) throws IOException {
        Path rootPath = Paths.get(__workDir).toAbsolutePath().normalize();
        Path todoFile = rootPath.resolve("TODO.md");

        if (!Files.exists(todoFile)) {
            return "[] (当前任务清单为空。若任务复杂，请使用 `todowrite` 初始化计划。)";
        }

        byte[] encoded = Files.readAllBytes(todoFile);
        return new String(encoded, StandardCharsets.UTF_8);
    }

    @ToolMapping(name = "todowrite", description =
            "管理任务列表。必须在处理 3 步以上复杂任务时使用。\n\n" +
                    "## 强制要求：\n" +
                    "1. 收到新指令时立即捕捉为待办项，并根据新信息随时调整清单；\n" +
                    "2. 严禁批量标记完成。每一步完成后，必须立即更新为 completed；\n" +
                    "3. 在处理当前任务时，必须将该项标记为 in_progress，且全局唯一。\n\n" +
                    "## 推理示例：\n" +
                    "<example>\n" +
                    "用户：帮我把项目里的 getCwd 改成 getCurrentWorkingDirectory。\n" +
                    "助手：我先搜索全局出现的次数。发现涉及 8 个文件。我将建立清单以防遗漏。\n" +
                    "调用 `todowrite` (todos: \"- [ ] 备份源码\\n- [/] 修改 src/main.java (in_progress)\\n- [ ] 修改 lib/util.java (pending)...\")\n" +
                    "</example>")
    public String todoWrite(
            @Param(value = "todos", description = "更新后的完整 Markdown 列表。") String todosMarkdown,
            String __workDir
    ) throws IOException {

        Path rootPath = Paths.get(__workDir).toAbsolutePath().normalize();
        Path todoFile = rootPath.resolve("TODO.md");

        StringBuilder sb = new StringBuilder();
        sb.append("# TODO\n\n");
        sb.append("\n\n");
        sb.append(todosMarkdown.trim());

        Files.write(todoFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        ensureInGitignore(rootPath, "TODO.md");

        return "TODO.md 已物理更新。请保持专注，继续执行标记为 in_progress 的任务。";
    }

    private void ensureInGitignore(Path rootPath, String fileName) {
        try {
            Path gitignore = rootPath.resolve(".gitignore");
            if (Files.exists(gitignore)) {
                List<String> lines = Files.readAllLines(gitignore, StandardCharsets.UTF_8);
                boolean exists = false;
                for (String line : lines) {
                    if (line.trim().equals(fileName)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    String entry = "\n# AI Task Tracker\n" + fileName + "\n";
                    Files.write(gitignore, entry.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
                }
            }
        } catch (Exception ignored) {
            LOG.warn("Failed to update .gitignore", ignored);
        }
    }
}