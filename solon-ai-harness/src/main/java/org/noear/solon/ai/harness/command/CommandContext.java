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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;

import java.util.List;

/**
 * 通用命令上下文接口
 * <p>
 * 不同端（CLI、Web）提供各自的实现，使命令逻辑与具体环境解耦。
 *
 * @author noear
 * @since 2026.4.28
 */
public interface CommandContext {

    /**
     * 获取当前会话
     */
    AgentSession getSession();

    /**
     * 获取 Agent 运行时
     */
    HarnessEngine getEngine();

    /**
     * 获取用户原始输入
     */
    String getRawInput();

    /**
     * 获取命令名（不含 / 前缀）
     */
    String getCommandName();

    /**
     * 获取命令参数列表
     */
    List<String> getArgs();

    /**
     * 按索引获取参数，越界返回 null
     */
    default String argAt(int index) {
        List<String> args = getArgs();
        return (args != null && index < args.size()) ? args.get(index) : null;
    }

    /**
     * 参数数量
     */
    default int argCount() {
        List<String> args = getArgs();
        return args != null ? args.size() : 0;
    }

    /**
     * 全部参数拼接为单个字符串
     */
    default String getArgsJoined() {
        List<String> args = getArgs();
        return args != null ? String.join(" ", args) : "";
    }

    /**
     * 输出一行文本（不同端有不同的实现）
     */
    void println(String text);

    /**
     * 是否支持 ANSI 颜色（CLI 返回 true，Web 返回 false）
     */
    default boolean supportsAnsi() {
        return false;
    }

    /**
     * 如果支持 ANSI 则原样返回，否则去除 ANSI 转义序列
     */
    default String color(String text) {
        return supportsAnsi() ? text : text.replaceAll("\033\\[[0-9;]*m", "");
    }

    /**
     * 运行 Agent 任务（异步回调）
     * <p>
     * 由 MarkdownCommand 等需要触发 Agent 执行的命令调用。
     * CLI 端实现为阻塞等待；Web 端实现为返回 Flux 流。
     */
    void runAgentTask(String prompt, String model);
}
