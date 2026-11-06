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
import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.ai.talents.mount.source.MountSource;
import org.noear.solon.annotation.Param;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * 任务进度追踪才能
 *
 * <p><b>存储</b>：清单是会话级状态，经 {@link MountSource} 读写（本地磁盘、db、http 均可）：</p>
 * <ul>
 *   <li>数据源模式（推荐，{@link #TodoTalent(MountSource, String)}）：{@code <sessionsDir><sessionId>/TODO.md}，
 *       与会话的 cwd 无关——清单属于会话，不随工作目录漂移；</li>
 *   <li>兼容模式（无参 / {@link #TodoTalent(String)}）：以会话 cwd（或 cwd 下的 relativeDir）为根的本地目录。</li>
 * </ul>
 * <p>sessionId 只能是单个路径段（不含分隔符、不是 {@code .}/{@code ..}），否则拒绝，防止目录穿越。</p>
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
    private final MountSource dataSource;
    private final String sessionsDir;

    private BiFunction<String, String, Path> workPathHook;

    /**
     * 设置本地工作目录钩子（cwd, sessionId → 目录）。
     *
     * @deprecated 4.2.0 改用 {@link #TodoTalent(MountSource, String)}（经挂载来源读写，可接 db / http）
     */
    @Deprecated
    public void setWorkPathHook(BiFunction<String, String, Path> workPathHook) {
        this.workPathHook = workPathHook;
    }

    public TodoTalent() {
        this((String) null);
    }

    public TodoTalent(String relativeDir) {
        this.relativeDir = relativeDir;
        this.dataSource = null;
        this.sessionsDir = null;
    }

    /**
     * 基于挂载来源存储清单。
     *
     * @param dataSource  数据来源（不可为 null）
     * @param sessionsDir 来源内的会话目录前缀（如 {@code "sessions/"}；可空表示来源根）
     * @since 4.2.0
     */
    public TodoTalent(MountSource dataSource, String sessionsDir) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        this.relativeDir = null;
        this.dataSource = dataSource;
        String dir = sessionsDir == null ? "" : sessionsDir.replace('\\', '/');
        this.sessionsDir = dir.isEmpty() || dir.endsWith("/") ? dir : dir + "/";
    }


    @Override
    public String description() {
        return "提供复杂任务的拆解、进度跟踪及计划修订能力。适用于需要多步协作的长链路任务。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "## 任务规划指南 (Task Planning Guide)\n" +
                "1. **适时启用**: 对需要多个步骤、阶段或工具协作的任务，必须使用 `todowrite` 建立清单；简单问答、单次查询无需创建计划。任务目标变化时及时更新。\n" +
                "2. **全量更新**: `todowrite` 会覆盖原文件，更新进度时**必须提供包含所有历史任务（含已完成）的完整 Markdown**，切勿仅发送新增或修改的单行。\n" +
                "3. **同步与状态标记**: 每个可跟踪事项统一使用 `- [ ]` 待办、`- [/]` 进行中、`- [x]` 已完成。开始处理某项标记为 `[/]`，完成后立即更新为 `[x]`。\n" +
                "4. **严禁虚假收尾**: 输出最终回复前，确认清单全为 `[x]`；若存在 `[ ]` 或 `[/]`，严禁直接总结或假装结束，必须继续调用工具推进，或显式向用户说明阻塞原因。\n" +
                "5. **上下文恢复**: 任务打断、继续或提示词很长时，优先调用 `todoread` 读取进度，避免凭记忆推断。";
    }

    /** 清单存储位置：来源 + 来源内路径。 */
    private static final class Location {
        final MountSource source;
        final String path;

        Location(MountSource source, String path) {
            this.source = source;
            this.path = path;
        }
    }

    private Location locate(String cwd, String sessionId) {
        requireSafeSessionId(sessionId);
        if (workPathHook != null) {
            return new Location(FileMountSource.of(workPathHook.apply(cwd, sessionId)), TODO_FILE_NAME);
        }
        if (dataSource != null) {
            return new Location(dataSource, sessionsDir + sessionId + "/" + TODO_FILE_NAME);
        }
        Path root = relativeDir == null ? Paths.get(cwd) : Paths.get(cwd, relativeDir);
        return new Location(FileMountSource.of(root.toAbsolutePath().normalize()), sessionId + "/" + TODO_FILE_NAME);
    }

    /** sessionId 必须是单个路径段：拼进存储路径前拒绝分隔符、上跳与控制字符。 */
    static void requireSafeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || sessionId.length() > 128
                || ".".equals(sessionId) || "..".equals(sessionId)) {
            throw new IllegalArgumentException("Invalid sessionId: " + sessionId);
        }
        for (int i = 0; i < sessionId.length(); i++) {
            char c = sessionId.charAt(i);
            if (c == '/' || c == '\\' || c < 0x20) {
                throw new IllegalArgumentException("Invalid sessionId: " + sessionId);
            }
        }
    }

    /**
     * 本地工作目录（兼容口径；数据源为非本地来源时无意义）。
     */
    protected Path getWorkPath(String __cwd, String __sessionId) {
        Location loc = locate(__cwd, __sessionId);
        int slash = loc.path.lastIndexOf('/');
        String dir = slash < 0 ? "" : loc.path.substring(0, slash);
        return loc.source.getLocalPath(dir).orElse(null);
    }

    /**
     * 获取 TODO.md 本地文件路径；数据源不是本地来源时返回 null。
     *
     * @deprecated 4.2.0 改用 {@link #readTodo(String, String)}（与存储介质无关）
     */
    @Deprecated
    public Path getTodoPath(String cwd, String sessionId) {
        Location loc = locate(cwd, sessionId);
        return loc.source.getLocalPath(loc.path).orElse(null);
    }

    /**
     * 读取会话清单原文。
     *
     * @param cwd       会话工作目录（仅兼容模式使用；数据源模式下忽略，可为 null）
     * @param sessionId 会话 ID
     * @return 清单原文；不存在时为 null
     * @throws IOException 读取失败
     * @throws IllegalArgumentException sessionId 非法
     * @since 4.2.0
     */
    public String readTodo(String cwd, String sessionId) throws IOException {
        Location loc = locate(cwd, sessionId);
        return loc.source.readStringOrNull(loc.path);
    }

    /**
     * 覆盖写入会话清单（自动补末尾换行；空内容照常写入，表示清空）。
     *
     * @since 4.2.0
     */
    public void writeTodo(String cwd, String sessionId, String markdown) throws IOException {
        Location loc = locate(cwd, sessionId);
        loc.source.writeString(loc.path, normalizeContent(markdown));
    }

    private static String normalizeContent(String markdown) {
        String content = markdown == null ? "" : markdown.trim();
        if (!content.isEmpty() && !content.endsWith("\n")) {
            content = content + "\n";
        }
        return content;
    }

    @ToolMapping(name = TOOL_TODOREAD, description = "读取当前任务清单和执行进度。用于恢复被打断的任务、确认下一步工作，或在收尾前核对是否全完成。")
    public String todoRead(String __cwd,
                           String __sessionId) throws IOException {
        String content = readTodo(__cwd, __sessionId);
        if (content == null) {
            return "[] (当前任务清单为空。若任务复杂，请使用 `todowrite` 初始化计划。)";
        }
        return content + buildProgressFooter(content);
    }

    @ToolMapping(name = TOOL_TODOWRITE, description = "创建或全量覆盖更新任务清单。更新状态时必须传入包含所有任务（已完成/进行中/待办）的完整 Markdown 内容。")
    public String todoWrite(
            @Param(value = "todos", description = "完整 Markdown 任务清单。只能使用 `- [ ]` 待办、`- [/]` 进行中、`- [x]` 已完成，不要使用数字序号或无状态列表。更新时必须包含全量任务，不能只传部分。") String todosMarkdown,
            String __cwd,
            String __sessionId
    ) throws IOException {
        writeTodo(__cwd, __sessionId, todosMarkdown);
        return "TODO saved." + buildProgressFooter(normalizeContent(todosMarkdown));
    }

    // 在 ^ 和 [*-] 之间增加 \s*，匹配可选的缩进
    private static final Pattern TODO_LINE_PATTERN = Pattern.compile("^\\s*[*-]\\s*\\[([ x/X])\\]\\s*(.*)");

    /**
     * 根据清单内容构造进度页脚，并在决策点给出明确的“继续/完成”推力。
     */
    private String buildProgressFooter(String content) {
        int total = 0, done = 0, inProgress = 0, pending = 0;
        String firstUnfinished = null;

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            java.util.regex.Matcher matcher = TODO_LINE_PATTERN.matcher(trimmed);

            if (!matcher.matches()) {
                continue;
            }

            char mark = Character.toLowerCase(matcher.group(1).charAt(0));
            String text = matcher.group(2).trim();

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