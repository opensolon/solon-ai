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
package org.noear.solon.ai.harness.command;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.ai.harness.i18n.HarnessI18nKeys;
import org.noear.solon.lang.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * 基于 Markdown 模板的自定义命令（兼容 Claude Code 的 Custom Commands 规范）
 * <p>
 * 支持的 Markdown 格式：
 * <pre>
 * ---
 * description: Create a git commit
 * argument-hint: [message]
 * allowed-tools: Bash(git add:*), Bash(git status:*)
 * ---
 * 命令的 prompt 正文，支持 $ARGUMENTS 和 $1, $2 等位置变量
 * </pre>
 *
 * @author noear
 * @since 4.2.0
 */
public class MarkdownCommand extends AbsCommand implements Command {
    private final String name;
    private final String template;
    /** md 里写的描述（可空：空时按语言给兜底描述） */
    private final String description;
    private final String model;

    public MarkdownCommand(String name, String template, String description, String model) {
        this.name = name;
        this.template = template;
        this.description = description;
        this.model = model;
    }

    @Override
    public String name() {
        return name;
    }

    /** md 里写的描述原样返回（用户内容，不翻译）；没写时按语言给兜底描述 */
    @Override
    public String description(@Nullable Locale locale) {
        return description != null ? description
                : HarnessI18n.fmt(locale, HarnessI18nKeys.COMMAND_CUSTOM_DESCRIPTION, "name", name);
    }

    @Override
    public String[] examples(@Nullable Locale locale) {
        return new String[0];
    }

    @Override
    public String model() {
        return model;
    }

    /**
     * 只声明任务意图、不碰会话：无会话时二段任务走临时会话（与普通消息一致）
     */
    @Override
    public boolean requiresSession() {
        return false;
    }

    /**
     * 获取替换变量后的 prompt 文本
     * <p>
     * 替换规则（兼容 Claude Code）：
     * - $ARGUMENTS → 所有参数拼接为单个字符串
     * - $1, $2, $3 ... → 按位置取单个参数
     */
    public String getResolvedPrompt(List<String> args) {
        String result = template;

        // 1. 替换位置参数 $1, $2, $3 ... （先替换位置参数，再替换 $ARGUMENTS）
        if (args != null) {
            for (int i = 0; i < args.size(); i++) {
                // $1 对应 args[0]，$2 对应 args[1]...
                result = result.replace("$" + (i + 1), args.get(i) != null ? args.get(i) : "");
            }
        }

        // 2. 替换 $ARGUMENTS（所有参数拼接为单个字符串）
        String allArgs = (args == null || args.isEmpty()) ? "" : joinArgs(args);
        result = result.replace("$ARGUMENTS", allArgs);

        return result;
    }

    private String joinArgs(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(" ");
            }
            sb.append(args.get(i));
        }
        return sb.toString();
    }

    @Override
    public void execute(HarnessEngine engine, CommandInput input, CommandOutput output) throws Exception {
        // 替换变量得到 prompt
        String prompt = getResolvedPrompt(input.getArgs());

        // 作为 Agent 任务执行（只声明意图，装配与开流由引擎/宿主完成）
        output.requestAgentTask(prompt, model());
    }
}
