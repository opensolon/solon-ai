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
                "1. **启动机制**: 超过3步以上的复杂任务，要通过 `todowrite` 创建计划；任务边界或目标发生重大变化时，用 `todowrite` 重构完整计划。\n" +
                "2. **状态感知（恢复优先）**: 凡是你对当前进度没有十足把握时，必须 **先** `todoread` 再行动，禁止凭记忆推断。以下场景一律强制先读：上下文被压缩/截断后、用户说“继续/接着做/还有吗”、开启新一轮对话、或任务中途被打断后恢复。\n" +
                "3. **实时推进**: 状态变更要随做随记，禁止攒着批量补记。开始做某项就置 `[/]`，做完就立即 `todowrite` 置 `[x]`。串行任务通常同一时刻只有一项 `[/]`；并行场景允许多项同时 `[/]`，但每项一旦完成都要第一时间单独更新，避免进度失真。**状态标记**: `[ ]` 待办；`[/]` 进行中；`[x]` 已完成。\n" +
                "4. **闭环校验（禁止半途收尾）**: 只要清单中还存在 `[ ]` 或 `[/]`，即视为任务未完成。默认行为是 **继续把它做完**，不得停下、不得输出总结。仅当遇到真正的外部阻塞（缺少权限、缺少必要信息、需用户拍板决策）时，才允许停下并明确说明阻塞点求助；不得把“可自行完成的剩余工作”当作追问理由。\n" +
                "5. **以返回为准**: `todoread`/`todowrite` 的返回值会附带 [进度] 与 [继续]/[完成] 提示，请以该提示作为“是否可以收尾”的判定依据。\n" +
                "6. **适用边界**: 不要为常识性提问、简单计算或单次工具创建计划。";
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

    /**
     * 获取 TODO.md 文件路径（供外部读取，如 Web 接口）
     */
    public Path getTodoPath(String cwd, String sessionId) {
        return getWorkPath(cwd, sessionId).resolve(TODO_FILE_NAME);
    }

    @ToolMapping(name = TOOL_TODOREAD, description = "读取任务清单。用于同步执行进度，确认下一步操作。")
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

    @ToolMapping(name = TOOL_TODOWRITE, description = "写入任务列表（新建、更新或重构）。接收完整的 Markdown 格式清单。")
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