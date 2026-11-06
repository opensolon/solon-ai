/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.MediaBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.agent.*;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.BufferedCommandContextFactory;
import org.noear.solon.ai.harness.command.CommandDispatcher;
import org.noear.solon.ai.harness.command.CommandContextFactory;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.input.AgentMentions;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputAttachments;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.session.SessionSelections;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.mount.*;
import org.noear.solon.ai.talents.cli.*;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
import org.noear.solon.ai.mount.catalog.*;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;
import java.util.*;

/**
 * 输入受理流水线（包内）：{@link HarnessEngine#handle(HarnessInput)} 的实现。
 *
 * <p>一次输入先过第 0 步受理判定（占位 / 入积压 / 拒绝，见 {@link SessionControl#admit}），
 * 再做五段处理：扩展预处理 → HITL 决策 / 续流 → 命令识别与执行 → 子代理解析与附件前缀 →
 * Prompt 构建与请求装配。引擎只保留对外门面与文档，实现集中在这里，便于单独阅读与演进。</p>
 *
 * @author noear
 * @since 4.2.0
 */
final class InputPipeline {
    /**
     * 本线程上已受理（正在装配）的会话：命令内部再次调用 {@code handle} 时继承外层占位，
     * 不再判定（否则会被外层自己的「受理中」拒掉）。
     */
    private static final ThreadLocal<IdentityHashMap<AgentSession, Boolean>> ADMITTED_ON_THREAD =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private final HarnessEngine engine;
    private final HarnessOptions options;
    private final SessionControl sessionControl;

    InputPipeline(HarnessEngine engine, HarnessOptions options, SessionControl sessionControl) {
        this.engine = engine;
        this.options = options;
        this.sessionControl = sessionControl;
    }

    /**
     * 唯一执行入口：第 0 步受理判定（占位 / 入积压 / 拒绝），再装配。
     */
    InputOutcome handle(HarnessInput input) {
        final AgentSession session = input.getSession();
        final Map<AgentSession, Boolean> admittedOnThread = ADMITTED_ON_THREAD.get();

        if (session == null || admittedOnThread.containsKey(session)) {
            // 无会话（如一次性摘要）或命令内部重入：不判定、不占位
            return assemble(input);
        }

        // ========== 0. 受理判定（判定、占位、入积压同一把会话锁）==========
        Admission admission = sessionControl.admit(input);
        if (admission.getOutcome() != null) {
            return admission.getOutcome();
        }

        final InputGate.Ticket ticket = admission.getTicket();
        InputOutcome outcome;
        admittedOnThread.put(session, Boolean.TRUE);
        try {
            outcome = assemble(input);
        } catch (RuntimeException | Error e) {
            ticket.close();
            throw e;
        } finally {
            admittedOnThread.remove(session);
            if (admittedOnThread.isEmpty()) {
                ADMITTED_ON_THREAD.remove();
            }
        }

        // 占位跟着请求走：TASK 由请求在开流 / 调用结束时释放；其余结局当场释放
        Runnable discarder = null;
        if (ticket.isAcquired()) {
            if (outcome.getKind() == InputOutcome.Kind.TASK && outcome.getRequest() instanceof AdmittedRequest) {
                AdmittedRequest request = (AdmittedRequest) outcome.getRequest();
                request.bind(ticket);
                discarder = request::release;
            } else {
                ticket.close();
            }
        }

        return outcome.admitted(admission.isBesideRunning(), admission.getDispatch(), discarder);
    }

    /**
     * 装配（受理之后的五段处理）。
     */
    private InputOutcome assemble(HarnessInput input) {
        for(HarnessExtension extension: options.getExtensions()) {
            if (extension.isEnabled()) {
                extension.onInput(engine, input);
            }
        }

        final AgentSession session = input.getSession();
        final InputAttachments attachments = input.getAttachments();

        // ========== 1. HITL 决策（优先于空输入判空：审批请求通常不带文本）==========
        if (input.isHitlDecision()) {
            //审批请求通常不带 selectedAgent：回退到会话级选择，避免宿主续流时误回主代理
            //（trace 按代理名分 key，取错代理会取不到挂起的 tool_calls）。
            String agentName = resolveHitlAgent(session, input.getSelectedAgent());
            boolean hitlApplied = applyHitlDecision(session, input.getHitlAction(), input.getHitlCallId(), input.getHitlOutput());
            int pendingHitl = HitlCard.pendingCount(session);

            if (input.isResume() && hitlApplied && pendingHitl == 0) {
                //决策写入后同一次调用完成续流装配，宿主无须再发一次 handle。
                //三个前置条件缺一不可：
                //1) hitlApplied —— 无匹配挂起任务时续流会把上一轮问题重跑一遍（空提示词回落 originalPrompt）；
                //2) pendingHitl == 0 —— 批内仍有未决卡时续流会把它们一并带走；
                //3) 宿主显式传 resume —— 只写决策的批量场景不自作主张。
                rememberEffectiveAgent(session, agentName);
                return InputOutcome.task(null, agentName, resolveSessionModelName(session, input.getSelectedModel()),
                        assembleRequest(session, null, input.getSelectedModel(), agentName, input.getSelectedSkills(), input.getSelectedMcps()), true);
            }

            //未续流的两种情形都归入 HITL，由宿主按 isHitlApplied / getPendingHitlCount 决定后续动作。
            return InputOutcome.hitl(agentName, resolveSessionModelName(session, input.getSelectedModel()),
                    hitlApplied, pendingHitl, HitlCard.list(session));
        }

        // ========== 2. 续流（无新输入，按挂起的 tool_calls 继续执行）==========
        if (input.isResume()) {
            //生效代理的会话级记忆：续流未显式指定时回填上一轮生效值（无状态宿主跨请求续流的关键）
            String agentName = Assert.isNotEmpty(input.getSelectedAgent())
                    ? input.getSelectedAgent() : Assert.isNotEmpty(input.getExplicitAgent())
                    ? input.getExplicitAgent() : effectiveAgentOf(session);
            rememberEffectiveAgent(session, agentName);
            return InputOutcome.task(null, agentName, resolveSessionModelName(session, input.getSelectedModel()),
                    assembleRequest(session, null, input.getSelectedModel(), agentName, input.getSelectedSkills(), input.getSelectedMcps()));
        }

        if (Assert.isEmpty(input.getText()) && !attachments.hasAny()) {
            return InputOutcome.ignored();
        }

        // ========== 3. 命令识别与执行（有附件或声明非命令语境时不当作命令，与既有口径一致）==========
        if (input.isCommandAware() && !attachments.hasAny()) {
            InputOutcome commandOutcome = dispatchCommand(session, input);
            if (commandOutcome != null) {
                return commandOutcome;
            }
        }

        // ========== 4. 子代理解析 + 附件前缀 ==========
        //附件由宿主落盘并装配（各端校验规则不同：桌面端有符号链接与总量上限，Web 端只需路径穿越校验），
        //引擎不再触碰文件系统，只负责提示词装配。
        String text = input.getText();

        AgentMentions.Mention mention = input.parseMention(engine);
        String agentName = mention.getAgentName();
        text = attachments.withFilePrefix(mention.getText());

        if (Assert.isEmpty(text) && !attachments.hasMedia()) {
            return InputOutcome.ignored();
        }

        if (Assert.isEmpty(text)) {
            if (attachments.isAllImages()) {
                text = attachments.getMediaBlocks().size() > 1 ? "请描述这些图片" : "请描述这张图片";
            } else {
                text = "请分析这些媒体文件";
            }
        }

        // ========== 5. Prompt 构建 ==========
        Prompt prompt = buildInputPrompt(text, attachments, input.getOrigin().getChannel(), agentName, input);

        // ========== 6. 请求装配 ==========
        String modelName = resolveSessionModelName(session, input.getSelectedModel());
        rememberEffectiveAgent(session, agentName);
        return InputOutcome.task(prompt, agentName, modelName,
                assembleRequest(session, prompt, input.getSelectedModel(), agentName, input.getSelectedSkills(), input.getSelectedMcps()));
    }

    /**
     * 装配一轮请求（会话、技能/连接选择、会话工作目录、生效模型）。
     */
    private ReActRequest assembleRequest(@Nullable AgentSession session, @Nullable Prompt prompt,
                                  @Nullable String selectedModel,
                                  @Nullable String selectedAgent,
                                  @Nullable List<String> selectedSkills,
                                  @Nullable List<String> selectedMcps) {
        ReActRequest request = engine.promptDo(prompt, engine.getAgentOrMain(selectedAgent));

        if (session != null) {
            request.session(session);
        }


        if (Assert.isNotEmpty(selectedSkills)) {
            request.options(o -> o.toolContextPut(SkillTalent.CTX_SKILLS_SELECTED, selectedSkills));
        }

        if (Assert.isNotEmpty(selectedMcps)) {
            request.options(o -> o.toolContextPut(McpGatewayTalent.CTX_MCPS_SELECTED, selectedMcps));
        }


        // request 级 cwd：会话工作目录是引擎已知的事实，装配时统一注入，宿主不必各写一遍。
        // 四个入口曾各自 o.toolContextPut(HarnessEngine.ATTR_CWD, ...)（ACP 传的是协议请求级 cwd，
        // 需按请求覆盖，属于真实差异，故它保留自己的那次写入）。
        // options() 是 andThen 叠加语义，宿主后写的值覆盖这里的缺省值。
        if (session != null) {
            final String sessionCwdForTurn = engine.getSessionCwd(session);
            if (Assert.isNotEmpty(sessionCwdForTurn)) {
                request.options(o -> o.toolContextPut(HarnessEngine.ATTR_CWD, sessionCwdForTurn));
            }
        }

        final String realModelName = resolveSessionModelName(session, selectedModel);
        if (realModelName != null) {
            final ChatModel chatModel = engine.getModelOrDefInstance(realModelName);
            if (chatModel != null) {
                request.options(o -> o.chatModel(chatModel));
            }
        }

        return request;
    }

    /**
     * 记录本轮生效代理到会话属性（主代理归一为 "main"）。
     *
     * <p>写入时机与流启动同步：发生在 handle 返回 TASK 后、宿主订阅前，与 Web 端原先
     * 「StreamBuilder 每轮写入」等价。HITL 挂起期间不覆盖（决策分支仅在真正续流时写入）。</p>
     */
    private static void rememberEffectiveAgent(@Nullable AgentSession session, @Nullable String agentName) {
        if (session == null) {
            return;
        }
        session.attrs().put(HarnessEngine.ATTR_AGENT_EFFECTIVE,
                Assert.isNotEmpty(agentName) ? agentName : AgentDefinition.AGENT_MAIN);
    }

    /**
     * 读取生效代理的会话级记忆（未记忆过时为 null）。
     */
    @Nullable
    private static String effectiveAgentOf(@Nullable AgentSession session) {
        if (session == null) {
            return null;
        }
        return session.attrs().get(HarnessEngine.ATTR_AGENT_EFFECTIVE) instanceof String
                ? (String) session.attrs().get(HarnessEngine.ATTR_AGENT_EFFECTIVE) : null;
    }

    /**
     * 构建输入提示词（有多媒体时组装多模态内容，并写入来源 / 附件 / 子代理 / 视野元数据）
     */
    private Prompt buildInputPrompt(String text, InputAttachments attachments, @Nullable String channel,
                                    @Nullable String agentName, @Nullable HarnessInput input) {
        UserMessage userMsg;

        if (attachments.hasMedia()) {
            Contents contents = new Contents();
            contents.addBlock(TextBlock.of(text));
            for (MediaBlock block : attachments.getMediaBlocks()) {
                contents.addBlock(block);
            }
            userMsg = new UserMessage(contents);

            String attachMeta = attachments.buildAttachmentMeta();
            if (attachMeta != null) {
                userMsg.addMetadata("attachments", attachMeta);
            }
        } else {
            userMsg = ChatMessage.ofUser(text);
        }

        if (channel != null) {
            userMsg.addMetadata("source", channel);
        }
        if (Assert.isNotEmpty(agentName)) {
            userMsg.addMetadata("agent", agentName);
        }

        Prompt prompt = Prompt.of(userMsg);

        if (input != null) {
            // 双写：attrs 是「本次输入选了什么」的事实（供回执 / 日志 / 宿主回读），
            // toolContext 是「本次执行生效什么」（promptDo 写入）。两者冲突时 toolContext 优先。
            if (input.hasSkillSelection()) {
                SkillTalent.putSelected(prompt, input.getSelectedSkills());
            }

            if (input.hasMcpSelection()) {
                McpGatewayTalent.putSelected(prompt, input.getSelectedMcps());
            }
        }

        return prompt;
    }

    /**
     * 应用 HITL 审批决策
     *
     * @param output 工具返回值改写文本（可空），非空时作为决策备注写入，由 HITLInterceptor 覆写工具返回
     * @return 决策是否真的写入；无匹配的挂起任务（过期审批卡）时返回 false
     */
    private boolean applyHitlDecision(@Nullable AgentSession session, HITLAction action, @Nullable String callId,
                                     @Nullable String output) {
        if (session == null) {
            return false;
        }

        HITLTask task = Assert.isNotEmpty(callId)
                ? HITL.getPendingTaskByCallUuid(session, callId)
                : HITL.getPendingTask(session);

        if (task == null) {
            return false;
        }

        // 与三端既有的「批准/拒绝并改写工具返回」口径一致（桌面端 HITL 三段重载）
        if (Assert.isNotEmpty(output)) {
            switch (action) {
                case APPROVE:
                    HITL.approve(session, task, output);
                    break;
                case REJECT:
                    HITL.reject(session, task, output);
                    break;
                case SKIP:
                    HITL.skip(session, task, output);
                    break;
            }
        } else {
            switch (action) {
                case APPROVE:
                    HITL.approve(session, task);
                    break;
                case REJECT:
                    HITL.reject(session, task);
                    break;
                case SKIP:
                    HITL.skip(session, task);
                    break;
            }
        }

        return true;
    }


    /**
     * 命令识别与执行（非命令时返回 null）
     * <p>
     * 供 {@link #handle} 与宿主的「是不是命令」快速判断共用：后者在普通输入上不应触发
     * 附件落盘、提及解析与请求装配 —— 那些结果会被宿主丢弃，白跑一遍。
     */
    private @Nullable InputOutcome dispatchCommand(AgentSession session, HarnessInput input) {
        if (input.getText() == null || input.hasAttachments()) {
            return null;
        }

        ParsedCommand command = ParsedCommand.parse(input.getText(), options.getCommandPrefix());
        if (command == null) {
            return null;
        }

        CommandContextFactory factory = input.getCommandContextFactory();
        CommandContextFactory realFactory = factory != null ? factory : new BufferedCommandContextFactory();
        try {
            return CommandDispatcher.dispatch(engine, session, command, realFactory, input.getOrigin(), input.isTerminal(), input);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Command execute failed: " + command.getName(), e);
        }
    }

    /**
     * 解析生效的模型名：显式入参优先，其次会话级选择
     */
    private String resolveSessionModelName(@Nullable AgentSession session, @Nullable String modelName) {
        if (modelName != null) {
            return modelName;
        }

        return SessionSelections.modelOf(session);
    }

    /**
     * 解析 HITL 请求的生效子代理名：审批入参优先，其次会话级选择
     * <p>
     * Web 端审批请求通常不携带 selectedAgent，而首轮生效代理（含 {@code @子代理} 解析结果）
     * 已由宿主写入会话级子代理选择（{@link #setSessionAgent}）。HITL 续流必须沿用它：trace 按代理名分 key，
     * 取错代理会取不到挂起的 tool_calls，表现为续跑空转。
     */
    private String resolveHitlAgent(@Nullable AgentSession session, @Nullable String selectedAgent) {
        if (Assert.isNotEmpty(selectedAgent)) {
            return selectedAgent;
        }

        return SessionSelections.agentOf(session);
    }
}
