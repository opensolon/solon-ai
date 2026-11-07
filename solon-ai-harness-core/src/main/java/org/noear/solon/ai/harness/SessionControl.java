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

import org.noear.solon.ai.harness.session.OwnerCheck;
import org.noear.solon.ai.harness.spi.SessionOwnership;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.harness.agent.*;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandRegistry;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.spi.InputBacklog;
import org.noear.solon.ai.harness.spi.RunDispatch;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.ai.mount.*;
import org.noear.solon.ai.harness.agent.ToolName;
import org.noear.solon.ai.talents.cli.*;
import org.noear.solon.ai.mount.catalog.*;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话控制面（包内）：受理判定、运行态快照、空闲判定与中断第一段的实现。
 *
 * <p>对应 {@code handle} 第 0 步的受理判定与引擎门面 {@code status / isIdle / isRunnableWhenBusy / resolveCommand / prepareInterrupt}。
 * 这些判定共用同一把会话锁（{@code session.attrs()}）与同一组扩展回调，集中在一处才能保证口径一致。</p>
 *
 * @author noear
 * @since 4.2.0
 */
final class SessionControl {
    private static final Logger LOG = LoggerFactory.getLogger(SessionControl.class);

    private final HarnessEngine engine;
    private final HarnessOptions options;
    private final CommandRegistry commandRegistry;
    /** 本引擎认领过的会话（shutdown 时释放；LOCAL 不登记） */
    private final Set<String> ownedSessions = ConcurrentHashMap.newKeySet();

    SessionControl(HarnessEngine engine, HarnessOptions options, CommandRegistry commandRegistry) {
        this.engine = engine;
        this.options = options;
        this.commandRegistry = commandRegistry;
    }

    /**
     * 会话归属检查（多节点单写者）：本节点持有或首次认领成功时为 owned，并登记以便 shutdown 时释放。
     * 锁外调用——租约实现可能有网络 IO，不得持会话锁等待。
     */
    OwnerCheck checkOwner(String sessionId) {
        SessionOwnership ownership = options.getSessionOwnership();
        if (ownership == SessionOwnership.LOCAL || sessionId == null) {
            return OwnerCheck.owned();
        }
        if (ownership.tryAcquire(sessionId) == false) {
            return OwnerCheck.notOwned(ownership.ownerOf(sessionId));
        }
        ownedSessions.add(sessionId);
        return OwnerCheck.owned();
    }

    InterruptPlan prepareInterrupt(AgentSession session) {
        if (session == null) {
            return null;
        }

        // ⓪ 非持有节点：运行态不在本进程，什么都不做（不清积压、不推进代数），宿主按 owner 转发
        OwnerCheck owner = checkOwner(session.getSessionId());
        if (owner.isOwned() == false) {
            return InterruptPlan.notOwner(owner.getOwner());
        }

        // ① 扩展中断参与（先于停流）：逐个隔离异常，任何扩展都不能阻止中断生效
        for (HarnessExtension extension : options.getExtensions()) {
            try {
                extension.onSessionInterrupt(engine, session);
            } catch (Throwable e) {
                LOG.warn("Extension onSessionInterrupt failed: {}", extension.getClass().getName(), e);
            }
        }

        // ② 兜底清除受理占位：宿主看到本轮已取消就不再订阅，占位不能一直留着
        InputGate.clear(session);

        // ③ 核心：原子认领运行句柄（停流与补收尾在 plan.commit）
        return HarnessInterrupts.claim(session);
    }

    SessionStatus status(AgentSession session) {
        SessionStatus.Builder builder = SessionStatus.builder(session);
        if (session != null) {
            for (HarnessExtension extension : options.getExtensions()) {
                try {
                    extension.contributeSessionStatus(engine, session, builder);
                } catch (Throwable e) {
                    LOG.warn("Extension contributeSessionStatus failed: {}", extension.getClass().getName(), e);
                }
            }
        }
        return builder.build();
    }

    /**
     * 永久删除会话的引擎侧善后：逐个回调 {@code onSessionRemove}（不判 isEnabled，逐个隔离异常）。
     * 不取会话实例（取了可能把已删会话重建出来）。
     */
    void removeSession(String sessionId) {
        for (HarnessExtension extension : options.getExtensions()) {
            try {
                extension.onSessionRemove(engine, sessionId);
            } catch (Throwable e) {
                LOG.warn("Extension onSessionRemove failed: {}", extension.getClass().getName(), e);
            }
        }

        ownedSessions.remove(sessionId);
        releaseOwnership(sessionId);
    }

    /** 引擎关闭：释放认领过的全部会话租约，让其它节点可以接管 */
    void releaseAllOwnership() {
        for (String sessionId : new ArrayList<>(ownedSessions)) {
            ownedSessions.remove(sessionId);
            releaseOwnership(sessionId);
        }
    }

    private void releaseOwnership(String sessionId) {
        try {
            options.getSessionOwnership().release(sessionId);
        } catch (Throwable e) {
            LOG.warn("[Harness] release session ownership failed: {}", sessionId, e);
        }
    }

    boolean isIdle(AgentSession session) {
        if (session == null) {
            return false;
        }
        synchronized (session.attrs()) {
            return SessionStatus.of(session).isOccupied() == false && hasPendingBacklog(session) == false;
        }
    }

    /** 是否有任一积压能力报告待执行输入（先进先出与空闲判定共用；逐个隔离异常）。 */
    private boolean hasPendingBacklog(AgentSession session) {
        for (HarnessExtension extension : options.getExtensions()) {
            if (extension instanceof InputBacklog) {
                try {
                    if (((InputBacklog) extension).hasPending(session)) {
                        return true;
                    }
                } catch (Throwable e) {
                    LOG.warn("InputBacklog hasPending failed: {}", extension.getClass().getName(), e);
                }
            }
        }
        return false;
    }

    boolean isRunnableWhenBusy(String input) {
        Command command = resolveCommand(input);
        return command != null && !command.terminalOnly() && command.runnableWhenBusy();
    }

    /**
     * 受理判定（{@code handle} 第 0 步；原子：判定、占位、入积压同一把会话锁）。
     *
     * <p>放行条件全部从输入本身推导，宿主不再传额外谓词：审批决策与续流豁免待审批拦截；
     * 程序派发的那一轮（当前线程在派发调用栈里）天然空闲；声明 {@code runnableWhenBusy} 的命令
     * 忙时也可执行（{@code terminalOnly} 的命令仅在终端输入时算）。</p>
     */
    Admission admit(HarnessInput input) {
        final AgentSession session = input.getSession();
        if (session == null) {
            return Admission.free();
        }

        // 会话归属与作用域预检已在锁外完成（见 InputPipeline#handle 的 0a / 0b 步）
        synchronized (session.attrs()) {
            RunDispatch dispatch = RunDispatch.current(session);
            boolean onDispatchThread = dispatch != null && dispatch.isOnDispatchThread();
            boolean busyCommand = isBusyRunnable(input);
            boolean hitlDecision = input.isHitlDecision();

            // 1) HITL 挂起：审批未决策前开新回合会与挂起 trace 并存，
            //    用户点「同意」时续流被运行态认领拒绝，或两条流交叉写快照。
            SessionStatus status = status(session);
            if (status.hasPendingHitl()) {
                boolean exempt = hitlDecision || input.isResume() || onDispatchThread || busyCommand;
                if (exempt == false) {
                    HitlCard next = HitlCard.next(session);
                    String toolName = next == null ? null : next.getToolName();
                    return Admission.outcome(InputOutcome.rejected(InputOutcome.REJECT_HITL_PENDING,
                            toolName == null ? "请先处理待审批操作" : "请先处理待审批操作：" + toolName));
                }
            }

            // 2) 程序派发的一轮（如积压续发）：派发方已判过空闲，直接受理。
            //    仍要占位：派发方常以「返回时会话是否忙」判断本轮是同步完成还是已异步开流，
            //    TASK 到开流之间没有占位会被误判成同步完成、提前收口并续发下一条（两条流并发）。
            //    非 TASK 结局在 handle 返回前即释放，同步完成的命令不会被误判成忙。
            if (onDispatchThread) {
                return Admission.admitted(InputGate.tryAcquire(session, options.getAdmissionTimeoutMs()), false, dispatch);
            }

            boolean busyNow = status.isBusy() || InputGate.isAdmitting(session) || hasPendingBacklog(session);

            // 3) 忙时可执行命令 / 审批决策：在运行中的任务旁边执行，不占位、不改写回复路由
            //    （审批决策不可排队；三端历史上都是直接写入决策）。
            if ((busyCommand || hitlDecision) && busyNow) {
                InputGate.Ticket ticket = InputGate.tryAcquire(session, options.getAdmissionTimeoutMs());
                return Admission.admitted(ticket, status.isBusy() && ticket.isAcquired() == false, null);
            }

            // 4) 忙态（有活跃流 / 已在受理窗口）或积压里还有待执行输入：延后（保证先进先出）或拒绝。
            if (busyNow) {
                return Admission.outcome(deferOrReject(session, input));
            }

            // 5) 正常受理：判定与占位同临界区
            return Admission.admitted(InputGate.tryAcquire(session, options.getAdmissionTimeoutMs()), false, null);
        }
    }

    /** 忙态结局：能排队就交积压（DEFERRED），否则拒绝（REJECTED）。调用方持有会话锁。 */
    private InputOutcome deferOrReject(AgentSession session, HarnessInput input) {
        final String busyText = "当前任务正在执行，请稍后再试";

        // 程序轮次、续流不可排队：程序轮次有自己的重试节奏；续流没有正文可存
        if (input.isProgram() || input.isResume()) {
            return InputOutcome.rejected(InputOutcome.REJECT_BUSY, busyText);
        }

        InputBacklog backlog = engine.getExtension(InputBacklog.class).orElse(null);
        if (backlog == null) {
            return InputOutcome.rejected(InputOutcome.REJECT_BUSY, busyText);
        }

        if (input.hasAttachments()) {
            return InputOutcome.rejected(InputOutcome.REJECT_ATTACHMENTS_NOT_DEFERRABLE,
                    "当前任务正在执行，带附件的消息不能排队，请稍后再发");
        }

        if (Assert.isEmpty(input.getText())) {
            return InputOutcome.ignored();
        }

        // 显式作用域随积压保存（解析所得的不存，续发时重新解析）；含不可保存维度时拒绝，
        // 不能悄悄丢掉模型 / 属性后按更宽的权限执行
        if (input.isScopeExplicit() && input.getScope().isDeferrable() == false) {
            return InputOutcome.rejected(InputOutcome.REJECT_SCOPE_NOT_DEFERRABLE,
                    "当前任务正在执行，该消息带有不可保存的权限范围，请稍后再发");
        }

        int position;
        try {
            position = backlog.offer(session, input);
        } catch (Throwable e) {
            LOG.warn("InputBacklog offer failed: {}", backlog.getClass().getName(), e);
            position = -1;
        }

        if (position < 0) {
            return InputOutcome.rejected(InputOutcome.REJECT_BACKLOG_UNAVAILABLE, "当前任务正在执行，且排队已满或不可用");
        }

        return InputOutcome.deferred(position);
    }

    /** 本输入是否为「忙时可执行」的命令（terminalOnly 命令仅在终端输入时算）。 */
    private boolean isBusyRunnable(HarnessInput input) {
        if (input.isCommandAware() == false || input.hasAttachments() || input.isHitlDecision() || input.isResume()) {
            return false;
        }
        Command command = resolveCommand(input.getText());
        return command != null && command.runnableWhenBusy() && (command.terminalOnly() == false || input.isTerminal());
    }

    Command resolveCommand(String input) {
        if (input == null || commandRegistry == null) {
            return null;
        }
        String trimmed = input.trim();
        ParsedCommand parsed = ParsedCommand.parse(trimmed, options.getCommandPrefix());
        if (parsed == null) {
            return null;
        }
        Command command = commandRegistry.find(parsed.getName());
        if (command == null || command.isEnabled() == false) {
            return null;
        }
        return command;
    }
}
