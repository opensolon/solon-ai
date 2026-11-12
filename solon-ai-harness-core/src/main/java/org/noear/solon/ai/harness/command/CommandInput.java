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
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.lang.Internal;
import org.noear.solon.lang.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 命令输入（只读）
 * <p>
 * 命令能看到的全部输入：会话、命令参数、渠道、生效语言与本轮属性。它是 {@link HarnessInput} 的只读包装——
 * {@code HarnessInput} 要留给扩展在 {@code onInput} 里修改，命令则只读不写，因此不直接交给命令。
 *
 * <p>直接委托、不做拷贝：命令分发时 {@code onInput} 已经跑完，输入不会再变。</p>
 *
 * @author noear
 * @since 4.2
 */
public final class CommandInput {
    private final HarnessInput input;
    private final ParsedCommand command;
    private final Locale locale;

    private CommandInput(HarnessInput input, ParsedCommand command, @Nullable Locale locale) {
        this.input = input;
        this.command = command;
        this.locale = locale;
    }

    /**
     * 由分发器构造（生效语言在此只算一次）
     *
     * @param input   本次输入
     * @param command 解析结果
     */
    @Internal
    public static CommandInput of(HarnessEngine engine, HarnessInput input, ParsedCommand command) {
        return new CommandInput(input, command, engine.localeOf(input));
    }

    /**
     * 按已知的生效语言构造（不经引擎的场景：单测、宿主自行调用命令）
     *
     * @param input   本次输入
     * @param command 解析结果
     * @param locale  生效语言（null 为默认语言）
     */
    @Internal
    public static CommandInput of(HarnessInput input, ParsedCommand command, @Nullable Locale locale) {
        return new CommandInput(input, command, locale);
    }

    // ========== 会话 ==========

    /**
     * 目标会话
     * <p>
     * {@link Command#requiresSession()} 为 true 的命令执行时一定不为 null（分发器已拦截）；否则可能为 null。
     */
    public @Nullable AgentSession getSession() {
        return input.getSession();
    }

    // ========== 命令 ==========

    /**
     * 命令解析结果
     */
    public ParsedCommand getCommand() {
        return command;
    }

    /**
     * 命令名（已转小写，不含前缀）
     */
    public String getCommandName() {
        return command.getName();
    }

    /**
     * 用户原始输入（含命令前缀）
     */
    public String getRawInput() {
        return command.getRawInput();
    }

    /**
     * 命令参数（不含命令名，不可变，可能为空列表）
     */
    public List<String> getArgs() {
        return command.getArgs();
    }

    /**
     * 按索引获取参数，越界（含负数）返回 null
     */
    public @Nullable String argAt(int index) {
        return command.argAt(index);
    }

    /**
     * 参数数量
     */
    public int argCount() {
        return command.argCount();
    }

    /**
     * 全部参数以空格拼接
     */
    public String getArgsJoined() {
        return command.getArgsJoined();
    }

    // ========== 渠道 ==========

    /**
     * 本次输入在渠道侧的身份（永不为 null，无来源时为 {@link InputOrigin#NONE}）
     */
    public InputOrigin getOrigin() {
        return input.getOrigin();
    }

    /**
     * 本次输入所在渠道是否具备终端交互能力（供 {@code /help} 等命令裁剪可见命令集）
     * <p>
     * 与 {@link InputOrigin} 无关：CLI 有渠道标识且具备终端能力，桌面是本地进程却无终端能力。
     */
    public boolean isTerminal() {
        return input.isTerminal();
    }

    // ========== 语言 ==========

    /**
     * 本次命令的生效语言（回执文案按它输出；null 为默认语言）
     * <p>
     * 取值为 {@link HarnessEngine#localeOf(HarnessInput)}：输入优先，引擎实例兜底。
     * 与 {@link HarnessInput#getLocale()}（输入上的原始值）含义不同。
     */
    public @Nullable Locale getLocale() {
        return locale;
    }

    // ========== 本轮属性（只读） ==========

    /**
     * 本轮系统提示词（未设置时为 null）
     */
    public @Nullable String getSystemPrompt() {
        return input.getSystemPrompt();
    }

    /**
     * 本轮显式指定的模型名（未设置时为 null）
     */
    public @Nullable String getSelectedModel() {
        return input.getSelectedModel();
    }

    /**
     * 本轮选择器传入的子代理名（未设置时为 null）
     */
    public @Nullable String getSelectedAgent() {
        return input.getSelectedAgent();
    }

    /**
     * 提交者主体（未设置时为 null）
     */
    public @Nullable String getPrincipal() {
        return input.getPrincipal();
    }

    /**
     * 本轮选中的技能（不可变；未指定时为空列表）
     */
    public List<String> getSelectedSkills() {
        return input.getSelectedSkills();
    }

    /**
     * 本轮是否显式指定了技能视野
     */
    public boolean hasSkillSelection() {
        return input.hasSkillSelection();
    }

    /**
     * 本轮选中的 MCP 服务名（不可变；未指定时为空列表）
     */
    public List<String> getSelectedMcps() {
        return input.getSelectedMcps();
    }

    /**
     * 本轮是否显式指定了 MCP 视野
     */
    public boolean hasMcpSelection() {
        return input.hasMcpSelection();
    }

    /**
     * 本轮生效的作用域（显式携带的，或受理时解析回写的；未设置时为 null）
     */
    public @Nullable InputScope getScope() {
        return input.getScope();
    }

    /**
     * 作用域是否由调用方显式携带
     */
    public boolean isScopeExplicit() {
        return input.isScopeExplicit();
    }

    /**
     * 宿主域属性（未设置时为 null）
     */
    public @Nullable String getAttr(String key) {
        return input.getAttr(key);
    }

    /**
     * 全部宿主域属性（只读视图，永不为 null）
     */
    public Map<String, String> getAttrs() {
        return input.getAttrs();
    }

    // ========== 派生 ==========

    /**
     * 派生「同一轮」的下一条输入（返回全新的 {@link HarnessInput}，原输入不受影响）
     * <p>
     * 命令把消息排进队列、或分发器装配命令二段任务时使用：本轮属性（提示词、语言、视野、显式作用域、
     * 来源、宿主域属性等）一并带上，口径见 {@link HarnessInput#derive(String)}。
     *
     * @param text 派生输入的文本
     */
    public HarnessInput derive(@Nullable String text) {
        return input.derive(text);
    }

    @Override
    public String toString() {
        return "CommandInput{" + command + '}';
    }
}
