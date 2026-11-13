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
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.ai.harness.i18n.HarnessI18nKeys;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.ai.harness.session.OwnerCheck;
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
import org.noear.solon.ai.harness.command.CommandDispatcher;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.input.AgentMentions;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputAttachments;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.input.RejectReason;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.session.SessionSelections;
import org.noear.solon.ai.harness.spi.RunDispatch;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.talents.cli.*;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
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
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(InputPipeline.class);

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

        if (engine.isClosed()) {
            // 宿主持有过期引用：明确失败，而不是悄悄重新拉起 LSP / MCP 等已释放的资源
            return InputOutcome.rejected(RejectReason.ENGINE_CLOSED, null);
        }

        if (session == null || admittedOnThread.containsKey(session)) {
            // 无会话（如一次性摘要）或命令内部重入：不判定、不占位（afterRun 仍可用）
            InputOutcome scopeReject = precheckScope(input);
            if (scopeReject != null) {
                return scopeReject;
            }
            InputOutcome outcome = assemble(input);
            AdmittedRequest admitted = admittedOf(outcome);
            return admitted == null ? outcome : outcome.admitted(false, null, null, admitted::addAfterRun);
        }

        // ========== 0a. 会话归属（锁外：租约实现可能有网络 IO）==========
        //    HITL 决策同样要求持有：挂起的 trace 与运行态都在持有节点内存里。
        OwnerCheck owner = sessionControl.checkOwner(session.getSessionId());
        if (owner.isOwned() == false) {
            return InputOutcome.rejected(RejectReason.NOT_OWNER, owner.getOwner());
        }

        // ========== 0b. 主体与作用域预检（锁外；归属之后，非持有节点不调解析器）==========
        //    放在受理之前：主体不符、解析不到作用域的输入不占位、不进积压（不消耗积压配额）。
        String principal = input.getPrincipal();
        if (principal != null && principal.isEmpty() == false
                && principal.equals(engine.getSessionPrincipal(session)) == false) {
            // sessionId 来自客户端：提交者不是会话主体时拒绝，防止用别人的会话提交
            return InputOutcome.rejected(RejectReason.SCOPE_UNAVAILABLE, "principal mismatch");
        }
        InputOutcome scopeReject = precheckScope(input);
        if (scopeReject != null) {
            return scopeReject;
        }

        // ========== 0c. 受理判定（判定、占位、入积压同一把会话锁）==========
        AdmissionDecision admission = sessionControl.admit(input);
        if (admission.getOutcome() != null) {
            return admission.getOutcome();
        }

        final AdmissionGate.Hold hold = admission.getHold();
        InputOutcome outcome;
        admittedOnThread.put(session, Boolean.TRUE);
        try {
            outcome = assemble(input);
        } catch (RuntimeException | Error e) {
            hold.close();
            throw e;
        } finally {
            admittedOnThread.remove(session);
            if (admittedOnThread.isEmpty()) {
                ADMITTED_ON_THREAD.remove();
            }
        }

        // 占位跟着请求走：TASK 由请求在开流 / 调用结束时释放；其余结局当场释放
        final AdmittedRequest admitted = admittedOf(outcome);
        Runnable discarder = null;
        if (hold.isAcquired()) {
            if (admitted != null) {
                admitted.bind(hold);
                discarder = admitted::release;
            } else {
                hold.close();
            }
        }

        return outcome.admitted(admission.isBesideRunning(), dispatchOriginOf(admission.getDispatch()), discarder,
                admitted == null ? null : admitted::addAfterRun);
    }

    /** 派发事务 → 回复路由来源（非派发为 null；派发但无来源为 NONE）。 */
    private static InputOrigin dispatchOriginOf(RunDispatch dispatch) {
        if (dispatch == null) {
            return null;
        }
        InputOrigin origin = dispatch.getOrigin();
        return origin == null ? InputOrigin.NONE : origin;
    }

    /** TASK 且请求由本引擎装配时取出请求，否则 null。 */
    private static AdmittedRequest admittedOf(InputOutcome outcome) {
        return (outcome.getKind() == InputOutcome.Kind.TASK && outcome.getRequest() instanceof AdmittedRequest)
                ? (AdmittedRequest) outcome.getRequest() : null;
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

        // 请求级作用域：受理前已预检（显式携带的原样、解析器的结果回写到输入）；
        // 扩展可在 onInput 里就地覆盖（input.scope(...)），以输入上的最终值为准。
        final InputScope scope = input.getScope();
        if (scope == null && options.isScopeRequired()) {
            return InputOutcome.rejected(RejectReason.SCOPE_UNAVAILABLE, null);
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
                        assembleRequest(session, null, input, agentName, scope), true);
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
                    assembleRequest(session, null, input, agentName, scope));
        }

        if (Assert.isEmpty(input.getText()) && !attachments.hasAny()) {
            return InputOutcome.ignored();
        }

        // ========== 3. 命令识别与执行（有附件或声明非命令语境时不当作命令，与既有口径一致）==========
        if (input.isCommandAware() && !attachments.hasAny()) {
            InputOutcome commandOutcome = dispatchCommand(input, scope);
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
        text = attachments.withFilePrefix(mention.getText(), engine.localeOf(input));

        if (Assert.isEmpty(text) && !attachments.hasMedia()) {
            return InputOutcome.ignored();
        }

        if (Assert.isEmpty(text)) {
            if (attachments.isAllImages()) {
                text = attachments.getMediaBlocks().size() > 1
                        ? HarnessI18n.get(engine.localeOf(input), HarnessI18nKeys.INPUT_DESCRIBE_IMAGES)
                        : HarnessI18n.get(engine.localeOf(input), HarnessI18nKeys.INPUT_DESCRIBE_IMAGE);
            } else {
                text = HarnessI18n.get(engine.localeOf(input), HarnessI18nKeys.INPUT_ANALYZE_MEDIA);
            }
        }

        // ========== 5. Prompt 构建 ==========
        Prompt prompt = buildInputPrompt(text, attachments, input.getOrigin().getChannel(), agentName, input);

        // ========== 6. 请求装配 ==========
        String modelName = resolveSessionModelName(session, input.getSelectedModel());
        rememberEffectiveAgent(session, agentName);
        return InputOutcome.task(prompt, agentName, modelName,
                assembleRequest(session, prompt, input, agentName, scope));
    }

    /**
     * 装配一轮请求（会话、技能/连接选择、会话工作目录、生效模型）。
     */
    private ReActRequest assembleRequest(@Nullable AgentSession session, @Nullable Prompt prompt,
                                  HarnessInput input,
                                  @Nullable String agentName,
                                  @Nullable InputScope scope) {
        if(Assert.isEmpty(agentName)){
            agentName = input.getSelectedAgent();
        }

        final ReActAgent targetAgent = engine.getAgentOrMain(session, agentName);
        ReActRequest request = engine.promptDo(prompt, targetAgent);

        if (session != null) {
            // 会话级子代理的清单与委派解析都由 TaskTalent 按会话完成，这里不再转递
            request.session(session);
        }

        // 宿主 systemPrompt 为“整体替换”语义（与 AgentFactory 的主 agent 定义一致），需自带完整协议提示。
        // 语言由 locale 决定（模板不自带语言），所以提示词与协议指令不会中英混排。
        final String inputSystemPrompt = input.getSystemPrompt();

        // 本轮语言：只写输入自带的。没带时由智能体定（ReActAgent#runLocaleOf）：新任务用代理的默认语言
        // （即引擎语言，见 AgentFactory），续流（HITL 审批后继续、resume、/continue）沿用被中断那一轮的语言。
        // 语言写进本轮 options 后随轨迹下传：代理提示词、协议指令、才能指令与工具描述、工具参数 __locale、
        // HITL 理由、子代理，都在推理时按它取，所以不会中英混排。
        final Locale inputLocale = input.getLocale();
        if (inputLocale != null) {
            request.options(o -> o.locale(inputLocale));
        }

        if (Assert.isNotEmpty(inputSystemPrompt)) {
            // 只换文本：语言仍由本轮 locale 决定（模板不自带语言）
            request.options(o -> o.systemPrompt(trace -> inputSystemPrompt));
        }

        if (Assert.isNotEmpty(input.getSelectedSkills())) {
            request.options(o -> o.toolContextPut(SkillTalent.CTX_SKILLS_SELECTED, input.getSelectedSkills()));
        }

        if (Assert.isNotEmpty(input.getSelectedMcps())) {
            request.options(o -> o.toolContextPut(McpGatewayTalent.CTX_MCPS_SELECTED, input.getSelectedMcps()));
        }


        // request 级 cwd：会话工作目录是引擎已知的事实，装配时统一注入，宿主不必各写一遍。
        // 四个入口曾各自 o.toolContextPut(HarnessEngine.ATTR_CWD, ...)（ACP 传的是协议请求级 cwd，
        // 需按请求覆盖，属于真实差异，故它保留自己的那次写入）。
        // options() 是 andThen 叠加语义，宿主后写的值覆盖这里的缺省值。
        if (session != null) {
            final String sessionCwdForRun = engine.getSessionCwd(session);
            if (Assert.isNotEmpty(sessionCwdForRun)) {
                request.options(o -> o.toolContextPut(HarnessEngine.ATTR_CWD, sessionCwdForRun));
            }
        }

        final String realModelName = resolveSessionModelName(session, input.getSelectedModel());
        if (realModelName != null) {
            final ChatModel chatModel = engine.getModelOrDefInstance(realModelName);
            if (chatModel != null) {
                request.options(o -> o.chatModel(chatModel));
            }
        }

        // 作用域放在最后（andThen 叠加：后写覆盖），模型覆盖也必须晚于上面的会话模型
        if (scope != null) {
            request.options(scope::applyTo);
        }

        return request;
    }

    /**
     * 作用域预检（受理之前、锁外）：确定本轮作用域并写回输入；不通过时返回拒绝结局（不占位、不入积压）。
     *
     * <ul>
     *     <li>显式携带（非积压还原）：原样使用，不调解析器；</li>
     *     <li>积压还原的显式作用域：与解析器最新结果取交集（只会变窄；主体不一致拒绝）；</li>
     *     <li>未携带：调解析器，结果以「解析所得」写回输入（积压不保存它，续发时重新解析）。</li>
     * </ul>
     *
     * <p>解析器只应依赖会话与输入本身（调用时扩展的 {@code onInput} 尚未执行）。
     * 要求作用域时解析不到就拒绝（缺失不得等于不限制）；未要求时解析器异常照旧上抛。</p>
     *
     * @return 拒绝结局；null 表示通过
     */
    @Nullable
    private InputOutcome precheckScope(HarnessInput input) {
        final InputScopeResolver resolver = options.getScopeResolver();
        final boolean required = options.isScopeRequired();
        final InputScope explicit = input.isScopeExplicit() ? input.getScope() : null;

        if (explicit != null && input.isScopeRestored() == false) {
            return null;
        }

        if (resolver == null) {
            if (explicit == null) {
                input.resolvedScope(null);
                if (required) {
                    return InputOutcome.rejected(RejectReason.SCOPE_UNAVAILABLE, null);
                }
            }
            return null;
        }

        InputScope resolved;
        try {
            resolved = resolver.resolve(input.getSession(), input);
        } catch (RuntimeException e) {
            if (required == false) {
                throw e;
            }
            LOG.warn("[Harness] scope resolve failed, input rejected: {}", e.toString());
            return InputOutcome.rejected(RejectReason.SCOPE_UNAVAILABLE, e.getMessage());
        }

        if (explicit != null) {
            // 积压续发：保存的显式作用域 ∩ 最新解析（撤销的权限立即生效，且不会被放宽）
            if (resolved == null) {
                return required ? InputOutcome.rejected(RejectReason.SCOPE_UNAVAILABLE, null) : null;
            }
            InputScope narrowed = InputScope.intersect(explicit, resolved);
            if (narrowed == null) {
                return InputOutcome.rejected(RejectReason.SCOPE_UNAVAILABLE, "principal mismatch");
            }
            input.scope(narrowed);
            return null;
        }

        if (resolved == null && required) {
            return InputOutcome.rejected(RejectReason.SCOPE_UNAVAILABLE, null);
        }
        input.resolvedScope(resolved);
        return null;
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
    private @Nullable InputOutcome dispatchCommand(HarnessInput input, @Nullable InputScope scope) {
        if (input.getText() == null || input.hasAttachments()) {
            return null;
        }

        ParsedCommand command = ParsedCommand.parse(input.getText(), options.getCommandPrefix());
        if (command == null) {
            return null;
        }

        // 作用域的命令名单：名单外的已注册命令不执行（与「本渠道不可用」同一结局，宿主无须新分支）
        if (scope != null && scope.allowsCommand(command.getName()) == false
                && options.commandRegistry.get(command.getName()) != null) {
            return InputOutcome.unavailable(command.getName(),
                    HarnessI18n.fmt(engine.localeOf(input), HarnessI18nKeys.COMMAND_SCOPE_DENIED,
                            "command", options.getCommandPrefix() + command.getName()), command);
        }

        try {
            return CommandDispatcher.dispatch(engine, input, command);
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
     * 已由宿主写入会话级子代理选择（{@link HarnessEngine#setSessionAgent}）。HITL 续流必须沿用它：trace 按代理名分 key，
     * 取错代理会取不到挂起的 tool_calls，表现为续跑空转。
     */
    private String resolveHitlAgent(@Nullable AgentSession session, @Nullable String selectedAgent) {
        if (Assert.isNotEmpty(selectedAgent)) {
            return selectedAgent;
        }

        return SessionSelections.agentOf(session);
    }
}
