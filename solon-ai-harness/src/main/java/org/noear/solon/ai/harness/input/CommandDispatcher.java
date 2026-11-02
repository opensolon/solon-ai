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
package org.noear.solon.ai.harness.input;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.AgentTaskIntent;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.lang.Nullable;

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
     * 命令在当前渠道不可用时的统一提示
     */
    public static final String COMMAND_UNAVAILABLE_HINT = " 仅支持 CLI，Web/IM 端不可用。发送 /help 查看可用命令。";

    /**
     * 命令已停用（组件被 setEnabled(false) 关闭）时的统一提示
     */
    public static final String COMMAND_DISABLED_HINT = " 已停用。发送 /help 查看可用命令。";

    private CommandDispatcher() {
    }

    /**
     * 分发已解析的命令
     *
     * @param command  解析结果
     * @param factory  命令上下文工厂
     * @param origin   本次输入的渠道来源身份（可空，空同 {@link InputOrigin#NONE}）
     * @param terminal 本次输入所在渠道是否具备终端能力（决定 {@link Command#terminalOnly()} 是否放行）
     * @return 命令处理结果；不是命令（解析为空或命令未注册）时返回 null
     */
    public static @Nullable InputOutcome dispatch(HarnessEngine engine,
                                                  AgentSession session,
                                                  @Nullable ParsedCommand command,
                                                  CommandContextFactory factory,
                                                  @Nullable InputOrigin origin,
                                                  boolean terminal) throws Exception {
        return dispatch(engine, session, command, factory, origin, terminal, null);
    }

    /**
     * @param input 原输入上下文（可为空）；非空时把它的技能 / MCP 视野透传给命令二段任务
     */
    public static @Nullable InputOutcome dispatch(HarnessEngine engine,
                                                  AgentSession session,
                                                  @Nullable ParsedCommand command,
                                                  CommandContextFactory factory,
                                                  @Nullable InputOrigin origin,
                                                  boolean terminal,
                                                  @Nullable HarnessInput input) throws Exception {
        if (command == null) {
            return null;
        }

        Command target = engine.getCommandRegistry().get(command.getName());
        if (target == null) {
            return null;
        }

        //已停用的命令：不能落成普通输入（否则 "/clear" 原文会被当作用户消息发给模型）。
        //结局同为 REPLY，与渠道受限一致；isCommand() 仍为 true，宿主不必写额外分支。
        if (target.isEnabled() == false) {
            String hint = "/" + target.name() + COMMAND_DISABLED_HINT;
            return InputOutcome.unavailable(target.name(), hint, command);
        }

        //渠道受限命令：必须在执行前拦截。终端专属命令（如 /exit）会终止进程或依赖终端。
        //结局是 REPLY（渲染提示后收尾），不是另一种 Kind —— 渠道限制属于「命令属性 + 本次结局」的组合，
        //由 isCommand() 仍为 true 表达，宿主不必为此写额外分支。
        if (target.terminalOnly() && !terminal) {
            String hint = "/" + target.name() + COMMAND_UNAVAILABLE_HINT;
            return InputOutcome.unavailable(target.name(), hint, command);
        }

        CommandContext ctx = factory.create(engine, session, command, terminal);
        target.execute(engine, ctx, origin == null ? InputOrigin.NONE : origin);

        //二段：命令只声明意图（预处理），装配仍走唯一的 handle 入口（不开嵌式回调）
        InputOutcome nested = null;
        AgentTaskIntent intent = ctx.getAgentTaskIntent();
        if (intent != null) {
            HarnessInput nestedInput = HarnessInput.of(session, intent.getPrompt())
                    .commandAware(false)  //命令产物不是用户手输，不再识别命令
                    .resume(intent.isResume())  //正文为空即续流，先于空输入判空生效（/continue）
                    .selectedAgent(selectedAgentOf(session))  //保留会话级子代理选择
                    .selectedModel(intent.getModel());

            //视野透传：命令触发的任务与手输消息必须看得见同一批资源，否则同一个 /xxx 在主路径与
            //命令路径上行为不一致（这类差异极难定位）。
            if (input != null) {
                if (input.hasSkillSelection()) {
                    nestedInput.selectedSkill(input.getSelectedSkills().toArray(new String[0]));
                }

                if (input.hasMcpSelection()) {
                    nestedInput.selectedMcp(input.getSelectedMcps().toArray(new String[0]));
                }
            }

            nested = engine.handle(nestedInput);
        }

        //任务标记与回执由上下文自己报告，避免分发器 instanceof 具体实现（CLI 的实时上下文不是缓冲式）
        return InputOutcome.command(target.name(), ctx.getCommandOutput(), command, nested);
    }

    /**
     * 会话级子代理选择（命令任务的子代理口径须与普通消息一致）
     */
    private static @Nullable String selectedAgentOf(@Nullable AgentSession session) {
        return session == null ? null : session.getContext().getAs(HarnessEngine.CTX_AGENT_SELECTED);
    }
}
