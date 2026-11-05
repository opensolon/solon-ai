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
import org.noear.solon.lang.Nullable;

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
     * 本次命令执行所在渠道是否具备终端交互能力
     * <p>
     * 供 {@code /help} 等命令裁剪可见命令集；取值与
     * {@link org.noear.solon.ai.harness.input.HarnessInput#isTerminal()} 同源（由分发器统一传入），
     * 与 {@link org.noear.solon.ai.harness.input.InputOrigin} 无关（CLI 有渠道标识且具备终端能力，
     * 桌面是本地进程却无终端能力）。
     */
    default boolean isTerminal(){
        return false;
    }

    /**
     * 获取当前会话
     */
    AgentSession getSession();

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
     * 声明 Agent 任务意图（纯预处理，不在此开流）
     * <p>
     * 命令只负责「改会话状态 + 声明意图」；真正的请求装配由分发器交给引擎完成，
     * 宿主统一经 {@code InputOutcome} 消费，因此三端不必再各自实现回调。
     *
     * @param prompt 任务正文；<b>为空表示续流</b>（如 {@code /continue}）
     * @param model  命令声明的模型（可空，走会话级回退）
     */
    void requestAgentTask(@Nullable String prompt, @Nullable String model);

    /**
     * 已声明的任务意图（未声明时为 null）
     */
    default @Nullable AgentTaskIntent getAgentTaskIntent() {
        return null;
    }

    /**
     * 命令回执文本
     * <p>
     * 采用即时写终端/直接推送的端（如 CLI）无需实现，返回 null 即可；
     * 缓冲式实现应返回已收集的全部回执行。
     */
    default String getCommandOutput() {
        return null;
    }
}