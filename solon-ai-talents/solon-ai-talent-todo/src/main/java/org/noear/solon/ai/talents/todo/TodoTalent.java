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
package org.noear.solon.ai.talents.todo;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.talents.todo.i18n.TodoI18n;
import org.noear.solon.ai.talents.todo.i18n.TodoI18nKeys;
import org.noear.solon.annotation.Param;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
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
 * <p>自 4.2.0 起从 solon-ai-talent-cli 独立为 solon-ai-talent-todo（只依赖 core 与 mount）；
 * 旧类 {@code org.noear.solon.ai.talents.cli.TodoTalent} 保留为废弃过渡子类。</p>
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
    private final MountSource stateSource;
    private final String sessionsDir;

    public TodoTalent() {
        this((String) null);
    }

    public TodoTalent(String relativeDir) {
        this.relativeDir = relativeDir;
        this.stateSource = null;
        this.sessionsDir = null;
    }

    /**
     * 基于挂载来源存储清单。
     *
     * @param stateSource  状态来源（不可为 null）
     * @param sessionsDir 来源内的会话目录前缀（如 {@code "sessions/"}；可空表示来源根）
     * @since 4.2.0
     */
    public TodoTalent(MountSource stateSource, String sessionsDir) {
        if (stateSource == null) {
            throw new IllegalArgumentException("stateSource must not be null");
        }
        this.relativeDir = null;
        this.stateSource = stateSource;
        String dir = sessionsDir == null ? "" : sessionsDir.replace('\\', '/');
        this.sessionsDir = dir.isEmpty() || dir.endsWith("/") ? dir : dir + "/";
    }


    @Override
    public String description(Prompt prompt) {
        return TodoI18n.get(AiI18n.localeOf(prompt), TodoI18nKeys.TODO_DESCRIPTION);
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return TodoI18n.get(AiI18n.localeOf(prompt), TodoI18nKeys.TODO_INSTRUCTION);
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
        if (stateSource != null) {
            return new Location(stateSource, sessionsDir + sessionId + "/" + TODO_FILE_NAME);
        }
        Path root = relativeDir == null ? Paths.get(cwd) : Paths.get(cwd, relativeDir);
        return new Location(FileMountSource.of(root.toAbsolutePath().normalize()), sessionId + "/" + TODO_FILE_NAME);
    }

    /** sessionId 必须是单个路径段：拼进存储路径前拒绝分隔符、上跳与控制字符。 */
    protected static void requireSafeSessionId(String sessionId) {
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

    /** 写入前的内容规整：去首尾空白、非空时补末尾换行。 */
    protected static String normalizeContent(String markdown) {
        String content = markdown == null ? "" : markdown.trim();
        if (!content.isEmpty() && !content.endsWith("\n")) {
            content = content + "\n";
        }
        return content;
    }

    /**
     * 读取清单（__locale 由工具上下文注入）
     *
     * @since 4.2
     */
    @ToolMapping(name = TOOL_TODOREAD, description = "读取当前任务清单和执行进度。用于恢复被打断的任务、确认下一步工作，或在收尾前核对是否全完成。")
    public String todoRead(String __cwd,
                           String __sessionId,
                           String __locale) throws IOException {
        Locale locale = AiI18n.toLocale(__locale);
        String content = readTodo(__cwd, __sessionId);
        if (content == null) {
            return TodoI18n.get(locale, TodoI18nKeys.TODO_READ_EMPTY);
        }
        return content + buildProgressFooter(locale, content);
    }

    /**
     * 写入清单（__locale 由工具上下文注入）
     *
     * @since 4.2
     */
    @ToolMapping(name = TOOL_TODOWRITE, description = "创建或全量覆盖更新任务清单。更新状态时必须传入包含所有任务（已完成/进行中/待办）的完整 Markdown 内容。")
    public String todoWrite(
            @Param(value = "todos", description = "完整 Markdown 任务清单。只能使用 `- [ ]` 待办、`- [/]` 进行中、`- [x]` 已完成，不要使用数字序号或无状态列表。更新时必须包含全量任务，不能只传部分。") String todosMarkdown,
            String __cwd,
            String __sessionId,
            String __locale
    ) throws IOException {
        writeTodo(__cwd, __sessionId, todosMarkdown);
        //"TODO saved." 是原本就为英文的协议性回执，各语言一致
        return "TODO saved." + buildProgressFooter(AiI18n.toLocale(__locale), normalizeContent(todosMarkdown));
    }

    // 在 ^ 和 [*-] 之间增加 \s*，匹配可选的缩进
    private static final Pattern TODO_LINE_PATTERN = Pattern.compile("^\\s*[*-]\\s*\\[([ x/X])\\]\\s*(.*)");

    /**
     * 根据清单内容构造进度页脚，并在决策点给出明确的“继续/完成”推力。
     */
    private String buildProgressFooter(Locale locale, String content) {
        int total = 0, done = 0, inProgress = 0, pending = 0;
        String firstUnfinished = null;

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            Matcher matcher = TODO_LINE_PATTERN.matcher(trimmed);

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

        //行分隔符沿用原实现的 %n（跟随系统），由代码补；文案只写正文
        String nl = System.lineSeparator();

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("total", total);
        vars.put("done", done);
        vars.put("inProgress", inProgress);
        vars.put("pending", pending);

        StringBuilder footer = new StringBuilder();
        footer.append(nl).append(TodoI18n.fmt(locale, TodoI18nKeys.TODO_FOOTER_PROGRESS, vars));

        int unfinished = inProgress + pending;
        if (total == 0) {
            return footer.toString();
        }

        if (unfinished > 0) {
            footer.append(nl).append(TodoI18n.fmt(locale, TodoI18nKeys.TODO_FOOTER_CONTINUE, "count", unfinished));
            if (firstUnfinished != null && !firstUnfinished.isEmpty()) {
                String next = firstUnfinished.length() > 80
                        ? firstUnfinished.substring(0, 80) + "..."
                        : firstUnfinished;
                footer.append(nl).append(TodoI18n.fmt(locale, TodoI18nKeys.TODO_FOOTER_NEXT, "item", next));
            }
        } else {
            footer.append(nl).append(TodoI18n.get(locale, TodoI18nKeys.TODO_FOOTER_DONE));
        }

        return footer.toString();
    }
}