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

import org.noear.solon.ai.AiSwitchable;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.Locale;

/**
 * 通用命令接口（适用于 CLI、Web 等所有端）
 *
 * @author noear
 * @since 4.0
 */
@Preview("4.0")
public interface Command extends AiSwitchable {
    /**
     * 命令名（不含 / 前缀）
     */
    String name();

    /**
     * 命令描述
     *
     * @param locale 语言（null 为默认语言）
     * @since 4.2
     */
     String description(@Nullable Locale locale) ;

    /**
     * 示例
     *
     * @param locale 语言（null 为默认语言）
     * @since 4.2
     */
    String[] examples(@Nullable Locale locale);

    /**
     * 使用模型
     */
    default String model(){
        return null;
    }

    /**
     * 是否要求宿主具备终端交互能力（默认 false）
     * <p>
     * 返回 true 时，无终端的渠道（Web/IM/桌面）既不展示也不执行该命令。
     * 与 {@link org.noear.solon.ai.harness.input.HarnessInput#isTerminal()} 配套：
     * 能力由每次输入携带，而不是由宿主实现类型推断。
     */
    default boolean terminalOnly() {
        return false;
    }

    /**
     * 是否允许在会话正在执行任务时运行。
     * <p>
     * 默认不允许。中断、插话、排队等只修改当前运行态的命令可以覆写为 true；
     * 会启动新任务或修改会话结构的命令应保持默认值，避免忙碌会话发生重入。
     */
    default boolean runnableWhenBusy() {
        return false;
    }

    /**
     * 是否需要会话（默认 true）
     * <p>
     * 为 true 时，无会话的输入（{@code HarnessInput.of(null, "/xxx")}）不会进入命令，
     * 由分发器直接回执统一提示；命令执行时 {@link CommandInput#getSession()} 因此一定不为 null。
     * 不碰会话的命令（如 {@code /help}、Markdown 自定义命令）覆写为 false。
     *
     * @since 4.2
     */
    default boolean requiresSession() {
        return true;
    }

    /**
     * 执行命令
     * <p>
     * 读取一律走 {@code input}（会话、参数、渠道、生效语言、本轮属性），产出一律走 {@code output}
     * （回执文本、Agent 任务意图）。
     *
     * @param input  本次命令的只读输入
     * @param output 本次命令的输出（端适配）
     * @since 4.2
     */
    void execute(HarnessEngine engine, CommandInput input, CommandOutput output) throws Exception;
}