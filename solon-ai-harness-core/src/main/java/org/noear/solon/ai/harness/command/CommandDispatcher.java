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

import org.noear.solon.ai.harness.session.SessionSelections;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.ai.harness.i18n.HarnessI18nKeys;
import org.noear.solon.lang.Nullable;

import java.util.Locale;

/**
 * 命令分发器
 * <p>
 * 统一三端的「解析 → 查找 → 渠道限制拦截 → 执行 → 回执收集」，消除各端实现漂移
 * （其中桌面端曾完全缺失终端能力拦截，导致 /exit 会终止桌面进程）。
 *
 * @author noear
 * @since 4.2
 */
public final class CommandDispatcher {
    /**
     * 渠道受限命令的统一提示（默认语言；形如 {@code /exit 仅支持 CLI，...}）
     *
     * @param commandName 命令名（不含 / 前缀）
     * @since 4.2
     */
    public static String unavailableHint(String commandName) {
        return unavailableHint(commandName, null);
    }

    /**
     * 渠道受限命令的统一提示（形如 {@code /exit 仅支持 CLI，...}）
     *
     * @param commandName 命令名（不含 / 前缀）
     * @param locale      语言（null 为默认语言）
     * @since 4.2
     */
    public static String unavailableHint(String commandName, @Nullable Locale locale) {
        return "/" + commandName + " " + HarnessI18n.get(locale, HarnessI18nKeys.COMMAND_UNAVAILABLE);
    }

    /**
     * 命令已停用（组件被 setEnabled(false) 关闭）时的统一提示（默认语言；形如 {@code /clear 已停用。...}）
     *
     * @param commandName 命令名（不含 / 前缀）
     * @since 4.2
     */
    public static String disabledHint(String commandName) {
        return disabledHint(commandName, null);
    }

    /**
     * 命令已停用（组件被 setEnabled(false) 关闭）时的统一提示（形如 {@code /clear 已停用。...}）
     *
     * @param commandName 命令名（不含 / 前缀）
     * @param locale      语言（null 为默认语言）
     * @since 4.2
     */
    public static String disabledHint(String commandName, @Nullable Locale locale) {
        return "/" + commandName + " " + HarnessI18n.get(locale, HarnessI18nKeys.COMMAND_DISABLED);
    }

    /**
     * 需要会话的命令在无会话输入上执行时的统一提示（形如 {@code /model 需要会话才能执行，...}）
     *
     * @param commandName 命令名（不含 / 前缀）
     * @param locale      语言（null 为默认语言）
     * @see Command#requiresSession()
     * @since 4.2
     */
    public static String sessionRequiredHint(String commandName, @Nullable Locale locale) {
        return "/" + commandName + " " + HarnessI18n.get(locale, HarnessI18nKeys.COMMAND_SESSION_REQUIRED);
    }

    private CommandDispatcher() {
    }

    /**
     * 分发已解析的命令
     * <p>
     * 会话、渠道、终端能力、语言与输出工厂一律从 {@code input} 取：命令拿到的是它的只读包装 {@link CommandInput}，
     * 输出由 {@link HarnessInput#getCommandOutputFactory()} 创建（为空时用缓冲式默认实现）。
     *
     * @param input   本次输入（不为 null）
     * @param command 解析结果
     * @return 命令处理结果；不是命令（解析为空或命令未注册）时返回 null
     */
    public static @Nullable InputOutcome dispatch(HarnessEngine engine,
                                                  HarnessInput input,
                                                  @Nullable ParsedCommand command) throws Exception {
        if (command == null) {
            return null;
        }

        Command target = engine.getCommandRegistry().get(command.getName());
        if (target == null) {
            return null;
        }

        final CommandInput cin = CommandInput.of(engine, input, command);
        final Locale locale = cin.getLocale();

        //已停用的命令：不能落成普通输入（否则 "/clear" 原文会被当作用户消息发给模型）。
        //结局同为 REPLY，与渠道受限一致；isCommand() 仍为 true，宿主不必写额外分支。
        if (target.isEnabled() == false) {
            String hint = disabledHint(target.name(), locale);
            return InputOutcome.unavailable(target.name(), hint, command);
        }

        //渠道受限命令：必须在执行前拦截。终端专属命令（如 /exit）会终止进程或依赖终端。
        //结局是 REPLY（渲染提示后收尾），不是另一种 Kind —— 渠道限制属于「命令属性 + 本次结局」的组合，
        //由 isCommand() 仍为 true 表达，宿主不必为此写额外分支。
        if (target.terminalOnly() && !cin.isTerminal()) {
            String hint = unavailableHint(target.name(), locale);
            return InputOutcome.unavailable(target.name(), hint, command);
        }

        //无会话的输入（engine.handle 的临时会话分支）上执行需要会话的命令：统一回执，而不是由各命令自行判空（或 NPE）
        if (target.requiresSession() && cin.getSession() == null) {
            String hint = sessionRequiredHint(target.name(), locale);
            return InputOutcome.unavailable(target.name(), hint, command);
        }

        CommandOutputFactory factory = input.getCommandOutputFactory();
        CommandOutput output = (factory != null ? factory : BufferedCommandOutputFactory.INSTANCE).create(cin);
        target.execute(engine, cin, output);

        //二段：命令只声明意图（预处理），装配仍走唯一的 handle 入口（不开嵌式回调）
        InputOutcome nested = null;
        AgentTaskIntent intent = output.getAgentTaskIntent();
        if (intent != null) {
            //命令触发的任务是「这一轮」的一部分：提示词、语言、视野、显式作用域、来源、宿主域属性一并带上
            //（见 HarnessInput#derive），否则同一个 /xxx 在主路径与命令路径上行为不一致，宿主收窄的作用域还会被重新解析。
            HarnessInput nestedInput = cin.derive(intent.getPrompt())
                    .resume(intent.isResume())  //正文为空即续流，先于空输入判空生效（/continue）
                    .selectedAgent(selectedAgentOf(cin.getSession()));  //保留会话级子代理选择

            //命令声明了模型就用它；未声明时沿用本轮的模型选择（再回退会话级），与普通消息一致
            if (intent.getModel() != null) {
                nestedInput.selectedModel(intent.getModel());
            }

            nested = engine.handle(nestedInput);
        }

        //任务意图与回执由输出自己报告，避免分发器 instanceof 具体实现（CLI 的实时输出不是缓冲式）
        return InputOutcome.command(target.name(), output.getText(), command, nested);
    }

    /**
     * 会话级子代理选择（命令任务的子代理口径须与普通消息一致）
     */
    private static @Nullable String selectedAgentOf(@Nullable AgentSession session) {
        return SessionSelections.agentOf(session);
    }
}
