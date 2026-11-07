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
package org.noear.solon.ai.harness.turn;

import org.noear.solon.ai.harness.spi.TurnDispatch;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.lang.Nullable;

/**
 * 一轮流的生命周期凭据（取消代数基准 + 派发事务身份）。
 *
 * <p><b>为什么需要它</b>：异步宿主的「开一轮流」包含两件必须<b>在订阅之前</b>完成的事——
 * 取取消代数基准（否则调度窗口内的 interrupt 会造出一条取消不掉的幽灵流）、
 * 捕获本轮所属的程序派发事务（终态必须收「这一条流自己的」事务，而不是「会话当前」的）。
 * 这两件事此前由每个宿主各自抄一遍，且必然漏抄：Web 的 async 与 sync 两条路径各写一份
 * （sync 那份连收口都漏了），桌面端再写一份，三处不一致的「取消以 complete 收尾」
 * 不变量只能靠 README 记住。</p>
 *
 * <p>本类把这份「一轮」的身份收归 harness：{@link #begin} 一次捕获，{@link #isCancelled()}
 * 判取消，{@link #settle(boolean)} 收口派发。宿主不再接触 {@link SessionRuns} 与派发实现
 * （派发事务经核心槽位 {@link TurnDispatch} 读取，不认识具体插件）。</p>
 *
 * <p><b>使用规约（必须遵守）</b>：</p>
 * <pre>{@code
 * // 1) 在订阅前（调用线程内）开一轮
 * SessionTurn turn = SessionTurn.begin(session);
 *
 * // 2) 续发积压登记到「运行句柄注销之后」：宿主 doFinally 里会话仍算忙，在那里 drain 会被挡掉
 * outcome.afterRun(() -> drainQueue());
 *
 * // 3) 订阅动作可能被调度到别的线程
 * runAsync(() -> {
 *     if (turn.isCancelled()) {        // 调度窗口内被取消：不开流
 *         outcome.discard();
 *         emitDoneOnce();
 *         return;
 *     }
 *     outcome.getRequest().stream()
 *            .doFinally(s -> {
 *                turn.settle(true);   // 幂等；非程序派发的输入时是空操作
 *                emitDoneOnce();      // 不在这里 drain
 *            })
 *            .subscribe();
 * });
 * }</pre>
 *
 * <p><b>{@link #begin} 必须早于 {@code stream()}/{@code subscribe()}。</b>晚于它调用会失去
 * 存在意义——基准取到的是「已经开始的这一轮」的代数，取消永远判定不到。</p>
 *
 * <p><b>本类不负责</b>：发 done、发 reset、drain 队列（用 {@code InputOutcome#afterRun}）、MDC 作用域。终态去重门是宿主的
 * （Web 用事件发布器的 done 门、桌面端用 {@code terminalSent} CAS），harness 无法也不该
 * 替它决定「谁是终态」。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class SessionTurn {
    private final AgentSession session;
    private final int cancelGeneration;
    private final TurnDispatch dispatch;
    private boolean settled;
    private boolean settleResult;

    private SessionTurn(@Nullable AgentSession session, int cancelGeneration, @Nullable TurnDispatch dispatch) {
        this.session = session;
        this.cancelGeneration = cancelGeneration;
        this.dispatch = dispatch;
    }

    /**
     * 开一轮：捕获取消代数基准与本轮所属的派发事务。
     *
     * <p>会话为 {@code null} 时返回一个空操作凭据（永不取消、收口为空操作），
     * 因此宿主可以在 {@code try/finally} 中无条件使用。</p>
     *
     * @param session 目标会话（可为 null）
     */
    public static SessionTurn begin(@Nullable AgentSession session) {
        if (session == null) {
            return new SessionTurn(null, SessionRuns.NO_GEN, null);
        }
        return new SessionTurn(session,
                SessionRuns.cancelCount(session),
                TurnDispatch.current(session));
    }

    /**
     * 本轮是否已被取消（含 {@link #begin} 之后、订阅之前发生的取消）。
     *
     * <p>会话为空时恒为 {@code false}。</p>
     */
    public boolean isCancelled() {
        if (session == null) return false;
        return SessionRuns.isCancelledSince(session, cancelGeneration);
    }

    /**
     * 取消代数基准（诊断用；宿主一般不需要读）。
     */
    public int cancelGeneration() {
        return cancelGeneration;
    }

    /**
     * 本轮所属的派发事务（非程序派发的一轮为 {@code null}）。
     *
     * <p>宿主用它做<b>输入入口</b>的判定：「我是不是由程序派发而来」，也可以用
     * {@link TurnDispatch#getOrigin()} 取回复路由来源。</p>
     *
     * <p><b>不要</b>用它做「当前线程是否在派发调用栈里」的判定——那要看
     * {@link TurnDispatch#isOnDispatchThread()}（异步线程上恒为 false）。</p>
     */
    @Nullable
    public TurnDispatch dispatch() {
        return dispatch;
    }

    /**
     * 收口本轮（幂等）：确认或回滚派发事务。
     *
     * <p>优先收 {@link #begin} 时捕获的那个事务（有身份保证）；早期路径拿不到时，
     * 退回「收口会话当前的派发」——与宿主历史实现一致，不存在回退风险。</p>
     *
     * @param started true = 本轮已正常跑起来（确认完成）；false = 启动失败（回滚到队首）
     * @return true = 本次调用完成了收口
     */
    public boolean settle(boolean started) {
        if (session == null) return false;
        if (settled) return settleResult;

        settled = true;
        if (dispatch != null) {
            settleResult = dispatch.settle(started);
        } else {
            TurnDispatch current = TurnDispatch.current(session);
            settleResult = current != null && current.settle(started);
        }
        return settleResult;
    }

    /** 是否已收口。 */
    public boolean isSettled() {
        return settled;
    }

    @Override
    public String toString() {
        return "SessionTurn{cancelGen=" + cancelGeneration
                + ", dispatched=" + (dispatch != null)
                + ", cancelled=" + isCancelled()
                + ", settled=" + settled + "}";
    }
}
