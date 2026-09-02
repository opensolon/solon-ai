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
import org.noear.solon.annotation.Param;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiFunction;

/**
 * 任务进度追踪才能
 *
 * @author noear
 * @since 3.9.5
 */
public class TodoTalent extends AbsTalent {
    public static final String TOOL_TODOREAD = "todoread";
    public static final String TOOL_TODOWRITE = "todowrite";

    public static final String TODO_FILE_NAME = "TODO.md";

    public static final String PARAM_TODOS = "todos";

    private final String relativeDir;

    private BiFunction<String, String, Path> workPathHook;

    public void setWorkPathHook(BiFunction<String, String, Path> workPathHook) {
        this.workPathHook = workPathHook;
    }

    public TodoTalent() {
        this(null);
    }

    public TodoTalent(String relativeDir) {
        this.relativeDir = relativeDir;
    }


    @Override
    public String description() {
        return "提供复杂任务的拆解、进度跟踪及计划修订能力。适用于需要多步协作的长链路任务。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "## 任务规划指南 (Task Planning Guide)\n" +
                "1. **适时启用**: 对需要多个步骤、阶段或工具协作的任务，使用 `todowrite` 建立清单；简单问答、单次查询或计算无需创建计划。任务目标或范围发生明显变化时，及时调整清单。\n" +
                "2. **开始前同步**: 新建计划或不确定当前进度时，先使用 `todoread`/`todowrite` 同步清单，再开展后续工作。每个可跟踪事项使用 `- [ ]` 待办、`- [/]` 进行中、`- [x]` 已完成。\n" +
                "3. **随进度更新**: 开始处理某项时标记为 `[/]`，客观完成后及时标记为 `[x]`，不要等到任务末尾集中补记。清单应反映当前实际进度，不要为了收尾虚假标记。\n" +
                "4. **收尾前确认**: 输出最终结果前，确认清单与实际完成情况一致；只要仍有 `[ ]` 或 `[/]`，就继续推进或说明确实存在的外部阻塞，不要直接总结。以 `todoread`/`todowrite` 返回的进度提示作为收尾参考。\n" +
                "5. **恢复优先**: 任务被打断、用户要求继续，或上下文发生变化后，先读取当前清单，避免凭记忆推断进度。";
    }

    protected Path getWorkPath(String __cwd, String __sessionId) {
        if (workPathHook != null) {
            return workPathHook.apply(__cwd, __sessionId);
        }

        if (relativeDir == null) {
            return Paths.get(__cwd).toAbsolutePath().normalize()
                    .resolve(__sessionId);
        } else {
            return Paths.get(__cwd, relativeDir).toAbsolutePath().normalize()
                    .resolve(__sessionId);
        }
    }

    /**
     * 获取 TODO.md 文件路径（供外部读取，如 Web 接口）
     */
    public Path getTodoPath(String cwd, String sessionId) {
        return getWorkPath(cwd, sessionId).resolve(TODO_FILE_NAME);
    }

    @ToolMapping(name = TOOL_TODOREAD, description = "读取当前任务清单和进度。开始复杂任务、恢复或继续已有任务时，或准备收尾前使用，以确认下一步及清单是否已完成。")
    public String todoRead(String __cwd,
                           String __sessionId) throws IOException {
        Path workPath = getWorkPath(__cwd, __sessionId);

        Path todoFile = workPath.resolve(TODO_FILE_NAME);

        if (!Files.exists(todoFile)) {
            return "[] (当前任务清单为空。若任务复杂，请使用 `todowrite` 初始化计划。)";
        }

        byte[] encoded = Files.readAllBytes(todoFile);
        String content = new String(encoded, StandardCharsets.UTF_8);
        return content + buildProgressFooter(content);
    }

    @ToolMapping(name = TOOL_TODOWRITE, description = "创建或更新完整任务清单（用于同步实际执行进度）。收尾前确保清单与实际结果一致。")
    public String todoWrite(
            @Param(value = "todos", description = "完整 Markdown 任务清单。可使用 `##` 标题分组；所有可跟踪任务必须使用 checkbox 行：`- [ ]` 待办、`- [/]` 进行中、`- [x]` 已完成。不要用无状态普通列表 `- xxx` 表示任务，必须带 checkbox 标记。") String todosMarkdown,
            String __cwd,
            String __sessionId
    ) throws IOException {
        Path workPath = getWorkPath(__cwd, __sessionId);

        if (Files.notExists(workPath)) {
            Files.createDirectories(workPath);
        }

        Path todoFile = workPath.resolve(TODO_FILE_NAME);

        String content = todosMarkdown.trim();
        if (!content.isEmpty() && !content.endsWith("\n")) {
            content = content + "\n";
        }
        Files.write(todoFile, content.getBytes(StandardCharsets.UTF_8));

        return "TODO saved." + buildProgressFooter(content);
    }

    /**
     * 根据清单内容构造进度页脚，并在决策点给出明确的“继续/完成”推力。
     */
    private String buildProgressFooter(String content) {
        int total = 0, done = 0, inProgress = 0, pending = 0;
        String firstUnfinished = null;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            // 仅识别形如 "- [x]" 的 checkbox 行，状态字符大小写均兼容（如 [X] / [ ] / [/]）
            if (trimmed.length() < 5 || !trimmed.startsWith("- [") || trimmed.charAt(4) != ']') {
                continue;
            }
            char mark = Character.toLowerCase(trimmed.charAt(3));
            String text = trimmed.substring(5).trim();
            if (mark == 'x') {
                total++;
                done++;
            } else if (mark == '/') {
                total++;
                inProgress++;
                if (firstUnfinished == null) {
                    firstUnfinished = text;
                }
            } else if (mark == ' ') {
                total++;
                pending++;
                if (firstUnfinished == null) {
                    firstUnfinished = text;
                }
            }
        }

        StringBuilder footer = new StringBuilder();
        footer.append(String.format("%n[进度] total: %d, done: %d, in-progress: %d, pending: %d.",
                total, done, inProgress, pending));

        int unfinished = inProgress + pending;
        if (total == 0) {
            return footer.toString();
        }

        if (unfinished > 0) {
            footer.append(String.format("%n[继续] 还有 %d 项未完成，任务尚未结束。禁止现在收尾或输出总结，请继续推进未完成事项。", unfinished));
            if (firstUnfinished != null && !firstUnfinished.isEmpty()) {
                String next = firstUnfinished.length() > 80
                        ? firstUnfinished.substring(0, 80) + "..."
                        : firstUnfinished;
                footer.append(String.format("%n[待办首项] %s", next));
            }
        } else {
            footer.append(String.format("%n[完成] 所有事项均已 [x]，可以收尾并向用户汇报结果。"));
        }

        return footer.toString();
    }
}