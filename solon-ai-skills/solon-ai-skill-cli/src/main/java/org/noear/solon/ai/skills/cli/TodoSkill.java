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
 * 任务进度追踪技能
 *
 * @author noear
 * @since 3.9.5
 */
public class TodoSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(TodoSkill.class);
    private final String relativeDir;

    public TodoSkill() {
       this(null);
    }

    public TodoSkill(String relativeDir) {
        this.relativeDir = relativeDir;
    }


    @Override
    public String description() {
        return "提供复杂任务的拆解、进度跟踪及计划修订能力。适用于需要多步协作的长链路任务。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "## 任务规划指南 (Task Planning Guide)\n" +
                "1. **启动机制**: 超过3步以上的复杂任务，要通过 `todowrite` 创建计划。\n" +
                "2. **状态感知**: 开始新任务或任务中途，应执行 `todoread` 确认当前进度。\n" +
                "3. **进度同步**: 每完成一步，必须调用 `todowrite` 更新计划进度。**状态标记**: `[ ]` 待办；`[/]` 进行中；`[x]` 已完成。\n" +
                "4. **动态修订**: 若任务目标发生重大偏移或原计划失效，要重新执行 `todowrite` 重构完整计划。\n" +
                "5. **适用边界**: 不要为常识性提问、简单计算或单次工具创建计划。";
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

        int lines = todosMarkdown.split("\n").length;

        return "TODO saved (" + lines + " lines).";
    }
}