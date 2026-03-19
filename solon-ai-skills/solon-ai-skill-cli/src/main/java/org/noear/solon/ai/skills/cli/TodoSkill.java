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
    private final String relativeDir;

    public TodoSkill(String relativeDir) {
        this.relativeDir = relativeDir;
    }


    @Override
    public String description() {
        return "任务进度追踪专家。通过维护 TODO.md 状态机，确保复杂任务的原子性与执行进度透明。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "## 任务执行 SOP (Task Lifecycle)\n" +
                "1. **规划优先**: 超过 3 个步骤的复杂任务，先使用 `todowrite` 初始化计划。\n" +
                "2. **状态感知**: 开始新任务或任务中途，应执行 `todoread` 确认当前进度。\n" +
                "3. **状态标记**: `[ ]` 待办；`[/]` 进行中（全局唯一）；`[x]` 已完成。\n" +
                "4. **重构机制**: 若任务目标发生重大偏移或原计划失效，要重新执行 `todowrite` 重构完整计划。";
    }

    protected Path getWorkPath(String __cwd, String __sessionId) {
        if (relativeDir == null) {
            return Paths.get(__cwd).toAbsolutePath().normalize()
                    .resolve(__sessionId);
        } else {
            return Paths.get(__cwd, relativeDir).toAbsolutePath().normalize()
                    .resolve(__sessionId);
        }
    }

    @ToolMapping(name = "todoread", description = "读取任务清单。用于同步执行进度，确认下一步操作。")
    public String todoRead(String __cwd,
                           String __sessionId) throws IOException {
        Path workPath = getWorkPath(__cwd, __sessionId);

        Path todoFile = workPath.resolve("TODO.md");

        if (!Files.exists(todoFile)) {
            return "[] (当前任务清单为空。若任务复杂，请使用 `todowrite` 初始化计划。)";
        }

        byte[] encoded = Files.readAllBytes(todoFile);
        return new String(encoded, StandardCharsets.UTF_8);
    }

    @ToolMapping(name = "todowrite", description = "写入任务列表（新建、更新或重构）。接收完整的 Markdown 格式清单。")
    public String todoWrite(
            @Param(value = "todos", description = "完整 Markdown 列表。") String todosMarkdown,
            String __cwd,
            String __sessionId
    ) throws IOException {
        Path workPath = getWorkPath(__cwd, __sessionId);

        if (Files.notExists(workPath)) {
            Files.createDirectories(workPath);
        }

        Path todoFile = workPath.resolve("TODO.md");

        Files.write(todoFile, todosMarkdown.trim().getBytes(StandardCharsets.UTF_8));
        ensureInGitignore(workPath, "TODO.md");

        int lines = todosMarkdown.split("\n").length;
        return "TODO.md saved (" + lines + " lines).";
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
        } catch (Throwable ignored) {
            LOG.warn("Failed to update .gitignore", ignored);
        }
    }
}