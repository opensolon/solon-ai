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
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.harness.agent.*;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandRegistry;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.turn.AdmitDecision;
import org.noear.solon.ai.harness.turn.InputGate;
import org.noear.solon.ai.harness.spi.InputBacklog;
import org.noear.solon.ai.harness.spi.TurnDispatch;
import org.noear.solon.ai.harness.turn.InterruptPlan;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.ai.mount.*;
import org.noear.solon.ai.harness.agent.ToolName;
import org.noear.solon.ai.talents.cli.*;
import org.noear.solon.ai.mount.catalog.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

/**
 * 会话控制面（包内）：受理判定、运行态快照、空闲判定与中断第一段的实现。
 *
 * <p>对应引擎门面 {@code admit / status / isIdle / isBusyBypassCommand / resolveCommand / prepareInterrupt}。
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

    SessionControl(HarnessEngine engine, HarnessOptions options, CommandRegistry commandRegistry) {
        this.engine = engine;
        this.options = options;
        this.commandRegistry = commandRegistry;
    }

    InterruptPlan prepareInterrupt(AgentSession session) {
        if (session == null) {
            return null;
        }

        // ① 扩展中断参与（先于停流）：逐个隔离异常，任何扩展都不能阻止中断生效
        for (HarnessExtension extension : options.getExtensions()) {
            try {
                extension.onSessionInterrupt(engine, session);
            } catch (Throwable e) {
                LOG.warn("Extension onSessionInterrupt failed: {}", extension.getClass().getName(), e);
            }
        }

        // ② 核心：原子认领运行句柄（停流与补收尾在 plan.commit）
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
    }

    boolean isIdle(AgentSession session) {
        if (session == null) {
            return false;
        }
        synchronized (session.attrs()) {
            return SessionStatus.of(session).isOccupied() == false && hasPendingBacklog(session) == false;
        }
    }

    /** 引擎上是否装有积压能力（决定忙态受理给 DEFER 还是 REJECT）。 */
    private boolean hasBacklog() {
        return engine.getExtension(InputBacklog.class).isPresent();
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

    boolean isBusyBypassCommand(String input) {
        Command command = resolveCommand(input);
        return command != null && !command.terminalOnly() && command.runnableWhenBusy();
    }

    AdmitDecision admit(AgentSession session, String input, boolean hitlDecision,
                        java.util.function.Predicate<String> hitlExempt) {
        if (session == null) {
            return AdmitDecision.admit(InputGate.Ticket.NONE);
        }

        synchronized (session.attrs()) {
            TurnDispatch dispatch = TurnDispatch.current(session);
            boolean onDispatchThread = dispatch != null && dispatch.isOnDispatchThread();
            boolean busyCommand = isBusyBypassCommand(input);

            // 1) HITL 挂起：审批未决策前开新回合会与挂起 trace 并存，
            //    用户点「同意」时续流被运行态认领拒绝，或两条流交叉写快照。
            SessionStatus status = status(session);
            if (status.hasPendingHitl()) {
                boolean exempt = hitlDecision || onDispatchThread
                        || (hitlExempt != null && hitlExempt.test(input));
                if (exempt == false) {
                    HitlCard next = HitlCard.next(session);
                    String toolName = next == null ? null : next.getToolName();
                    return AdmitDecision.reject("hitl_pending",
                            toolName == null ? "请先处理待审批操作" : "请先处理待审批操作：" + toolName);
                }
            }

            // 2) 忙态（有活跃流 / 已在受理窗口）或积压里还有待执行输入：应延后（保证先进先出）。
            //    程序派发本身与忙态旁路命令除外（后者的语义是在运行中的任务旁插一脚）。
            //    没装积压能力时无处可延：拒绝（busy）。
            if (onDispatchThread == false && busyCommand == false
                    && (status.isBusy() || status.isAdmitting() || hasPendingBacklog(session))) {
                return hasBacklog() ? AdmitDecision.defer()
                        : AdmitDecision.reject("busy", "当前任务正在执行，请稍后再试");
            }

            // 3) 程序派发的一轮：天然空闲、不占位（再占位会让同步完成的命令误判成忙态）
            if (onDispatchThread) {
                return AdmitDecision.bypassDispatch(dispatch);
            }

            // 4) 已在真实任务上运行的旁路命令：不占启动闩、不改写回复路由
            boolean sideCommand = busyCommand && status.isBusy();
            if (sideCommand) {
                return AdmitDecision.bypassSideCommand();
            }

            // 5) 正常受理：判定与占位同临界区
            return AdmitDecision.admit(InputGate.tryAcquire(session));
        }
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
