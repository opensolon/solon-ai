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

import org.noear.solon.lang.Nullable;

/**
 * 命令输出（端适配）
 * <p>
 * 命令的产出只有两样：回执文本与 Agent 任务意图。不同端（CLI、Web）提供各自的实现，
 * 决定回执怎么落地（即时写终端，或缓冲后一次回传）。
 *
 * <p>命令需要读取的一切（会话、参数、渠道、语言、本轮属性）都在 {@link CommandInput} 上，本接口不承载输入。</p>
 *
 * @author noear
 * @since 4.2
 */
public interface CommandOutput {
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
     * @param model  命令声明的模型（可空，走本轮或会话级回退）
     */
    void requestAgentTask(@Nullable String prompt, @Nullable String model);

    /**
     * 已声明的任务意图（未声明时为 null）
     */
    default @Nullable AgentTaskIntent getAgentTaskIntent() {
        return null;
    }

    /**
     * 已缓冲的回执文本（经 {@code InputOutcome#getCommandOutput()} 交给宿主）
     * <p>
     * 采用即时写终端/直接推送的端（如 CLI）无需实现，返回 null 即可；
     * 缓冲式实现应返回已收集的全部回执行。
     */
    default @Nullable String getText() {
        return null;
    }
}
