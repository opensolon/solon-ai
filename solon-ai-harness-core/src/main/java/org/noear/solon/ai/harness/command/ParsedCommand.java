/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.command;

import org.noear.solon.ai.util.CmdUtil;
import org.noear.solon.lang.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * 斜杠命令解析结果
 * <p>
 * 只负责把原始输入切成「命令名 + 参数」，不查表、不执行，便于各端复用同一套解析口径。
 * 命令前缀可由引擎配置（默认 {@code "/"}）。
 *
 * @author noear
 * @since 4.2
 */
public final class ParsedCommand {
    /**
     * 默认命令前缀
     */
    public static final String DEFAULT_PREFIX = "/";

    private final String name;
    private final List<String> args;
    private final String rawInput;

    private ParsedCommand(String name, List<String> args, String rawInput) {
        this.name = name;
        this.args = args;
        this.rawInput = rawInput;
    }

    /**
     * 命令名（已转小写，不含前缀）
     */
    public String getName() {
        return name;
    }

    /**
     * 命令参数（不含命令名，可能为空列表）
     */
    public List<String> getArgs() {
        return args;
    }

    /**
     * 原始输入（含命令前缀）
     */
    public String getRawInput() {
        return rawInput;
    }

    public String argAt(int index) {
        return (index >= 0 && index < args.size()) ? args.get(index) : null;
    }

    public int argCount() {
        return args.size();
    }

    public String getArgsJoined() {
        return String.join(" ", args);
    }

    /**
     * 按默认前缀（/）解析
     */
    public static ParsedCommand parse(String input) {
        return parse(input, DEFAULT_PREFIX);
    }

    /**
     * 解析输入
     *
     * @param input  原始输入
     * @param prefix 命令前缀（如 "/"、"::"）；为空时使用默认值
     * @return 不以 prefix 开头、或只有 prefix 而没有命令名时返回 null（按普通输入处理）
     */
    public static ParsedCommand parse(@Nullable String input, @Nullable String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            prefix = DEFAULT_PREFIX;
        }

        if (input == null || !input.startsWith(prefix)) {
            return null;
        }

        List<String> parts = CmdUtil.parseArguments(input.trim().substring(prefix.length()));
        if (parts == null || parts.isEmpty()) {
            //只输入前缀时解析结果为空，不能直接取首元素（历史上的 IndexOutOfBounds 隐患）
            return null;
        }

        String name = parts.get(0).toLowerCase();
        List<String> args = parts.size() > 1
                ? Collections.unmodifiableList(parts.subList(1, parts.size()))
                : Collections.<String>emptyList();

        return new ParsedCommand(name, args, input);
    }

    @Override
    public String toString() {
        return "/" + name + " " + args;
    }
}
