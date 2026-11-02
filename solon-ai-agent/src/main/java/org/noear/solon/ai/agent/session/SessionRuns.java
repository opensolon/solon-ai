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
package org.noear.solon.ai.agent.session;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.lang.Preview;
import reactor.core.Disposable;
import reactor.core.Disposables;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话运行句柄登记表（框架侧所有权的唯一实现）。
 *
 * <p>此前「取消一个正在跑的任务」要求宿主在自己的订阅代码里把句柄写进
 * {@code session.attrs().put("disposable", ...)}，框架才知道去停谁。这个约定是字符串级的、
 * 无编译期约束、写错即静默失效。本类把登记权收归框架：{@code ReActRequest#stream()} 订阅时
 * 自动登记，结束时自动按值注销，宿主不再需要触碰任何 attrs key。</p>
 *
 * <p><b>多流语义</b>：一个会话上允许并存多条流（HITL 续流、命令触发的任务、连发输入）。
 * 因此这里持有的是句柄<b>集合</b>（{@link Disposable.Composite}），注销一律按值（身份）删除
 * —— 无条件清槽位会让先结束的那条流把槽位摘空，此后仍在跑的流再也无法取消。</p>
 *
 * <p><b>取消的时序</b>：取消总发生在两处窗口之一，本类两者都覆盖：</p>
 * <ul>
 *   <li><b>已订阅</b>：句柄就在当前 composite 里，{@code dispose()} 直接生效。</li>
 *   <li><b>已创建、未订阅</b>：宿主持有请求、把订阅调度到了别的线程。此时先把当前 composite
 *       置为「已取消」并换上新 composite，随后到来的 {@code add} 会被 Composite 立即 dispose
 *       —— 这是 {@link Disposable.Composite} 自带的能力，不需要额外判空。</li>
 * </ul>
 * <p>另配一个取消代数（{@link #cancelCount}），让 {@link #attach(AgentSession, Disposable, int)}
 * 能明确回答「本次运行是否已在调度期间被取消」，调用方据此放弃订阅而不是开一条幽灵流。</p>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class SessionRuns {
    /** 运行句柄集合的会话属性 key（本类私有，不构成对外契约） */
    private static final String ATTR_RUNS = "_session_runs_";

    /** 不校验取消代数 */
    public static final int NO_GEN = -1;

    private SessionRuns() {
    }

    /**
     * 登记一个运行句柄（不校验取消代数）。
     *
     * <p>要求 {@code run.isDisposed()} 可信：句柄若被登记到已取消的槽位会立即 dispose，
     * 本方法据此判定「未真正生效」。 {@code Subscription::cancel} 这类方法引用不满足该前提，
     * 需包一层带状态的 {@link Disposable}。</p>
     *
     * @return true = 已登记；false = 参数为空，或该次运行此前已被取消（句柄已 dispose）
     */
    public static boolean attach(AgentSession session, Disposable run) {
        return attach(session, run, NO_GEN);
    }

    /**
     * 登记一个运行句柄，并校验取消代数。
     *
     * @param gen 流创建时取的 {@link #cancelCount(AgentSession)}；{@link #NO_GEN} 表示不校验
     * @return true = 已登记；false = 该次运行已被取消（句柄已 dispose）
     */
    public static boolean attach(AgentSession session, Disposable run, int gen) {
        if (session == null || run == null) {
            return false;
        }

        Runs runs = runsOf(session, true);

        // 整个「校验 + 入槽」必须在同一临界区：
        // 否则取消可能恰好插在 running.add 与 actives.add 之间 —— 句柄已被换槽 dispose，
        // 却仍被记进 actives，activeCount 永久 > 0，会话从此误判为忙碌（Stop 再也取消不掉）。
        synchronized (runs) {
            if (gen != NO_GEN && runs.cancels.get() > gen) {
                // 调度窗口内发生过取消：本流不该再跑
                disposeQuietly(run);
                return false;
            }

            // 若当前 composite 已被取消，add 会立即 dispose 该句柄（Reactor 内置语义）
            runs.running.add(run);

            if (run.isDisposed()) {
                // 落到已取消的槽位：句柄已就地释放，不得计入活跃
                return false;
            }

            runs.actives.add(run);
            return true;
        }
    }

    /**
     * 按值注销一个运行句柄：只摘自己那一条，不影响同一会话上仍在跑的其他流。
     */
    public static void detach(AgentSession session, Disposable run) {
        Runs runs = runsOf(session, false);
        if (runs == null || run == null) {
            return;
        }

        synchronized (runs) {
            runs.actives.remove(run);
            runs.running.remove(run);
        }
    }

    /**
     * 当前会话上未结束的运行流数量。
     *
     * <p>以登记表为准（attach 入、detach 出），不探测句柄自身的 isDisposed：
     * 句柄可能是 {@code Subscription::cancel} 这类方法引用，其 isDisposed 语义不可靠。</p>
     */
    public static int activeCount(AgentSession session) {
        Runs runs = runsOf(session, false);
        return runs == null ? 0 : runs.actives.size();
    }

    /**
     * 会话当前是否有正在执行的流。
     */
    public static boolean isBusy(AgentSession session) {
        return activeCount(session) > 0;
    }

    /**
     * 当前取消代数。
     *
     * <p>宿主把订阅调度到别的线程时，用它在调度前取基准值，订阅前传给
     * {@link #attach(AgentSession, Disposable, int)} 即可覆盖「取消早于订阅」的窗口。</p>
     */
    public static int cancelCount(AgentSession session) {
        Runs runs = runsOf(session, false);
        return runs == null ? 0 : runs.cancels.get();
    }

    /**
     * 自给定代数以来是否发生过取消。
     */
    public static boolean isCancelledSince(AgentSession session, int gen) {
        return cancelCount(session) > gen;
    }

    /**
     * 取消会话上全部运行流（推进取消代数）。
     *
     * <p>实现是「换槽 + 停旧槽」：先原子换成新的空 composite，再 dispose 旧的。
     * 换槽与取消之间不存在「取消了却仍能被 add 进来」的空隙，正因如此，
     * 「已创建、未订阅」的流在随后 attach 时会直接落到已取消的槽上。</p>
     *
     * <p><b>返回 false 不代表什么都没发生</b>：取消代数依然推进。宿主可能刚把订阅
     * 调度到别的线程、句柄尚未登记，此刻的取消必须能拦住那条「随后才到」的流，
     * 否则会出现一条取消不掉的幽灵流。因此不要把 false 当作「用户可以重试」的依据。</p>
     *
     * @return true = 确有活跃流被停掉；false = 当时没有活跃流（但取消代数已推进）
     */
    public static boolean cancelAll(AgentSession session) {
        Disposable.Composite claimed = claim(session);
        if (claimed == null) {
            return false;
        }
        disposeQuietly(claimed);
        return true;
    }

    /**
     * 原子认领会话上全部运行流（推进取消代数），返回被摘下的句柄集合。
     *
     * <p>「判定忙碌」与「摘下句柄」必须是同一个原子动作，否则两个并发的 Stop 会都看到忙碌、
     * 都走到后续的持久化与回执上（重复写取消消息、重复领 IM 终态）。
     * 本方法返回非空只对第一个调用者成立，语义等价于原实现的 {@code attrs().remove(...)}。</p>
     *
     * <p>调用者必须负责 dispose 返回值（可直接 {@link Disposable#dispose()}）。</p>
     *
     * @return 被认领的句柄集合；null = 当时没有活跃流（但取消代数已推进）
     */
    public static Disposable.Composite claim(AgentSession session) {
        return cancel(session, true);
    }

    /**
     * 只停掉当前运行流，不推进取消代数。
     *
     * <p>用于「新输入顶掉旧轮次」这类场景：旧流要停，但正要开始的新流不能被当成被取消的那一轮。</p>
     *
     * @return true = 确有活跃流被停掉
     */
    public static boolean cancelActive(AgentSession session) {
        Disposable.Composite claimed = cancel(session, false);
        if (claimed == null) {
            return false;
        }
        disposeQuietly(claimed);
        return true;
    }

    private static Disposable.Composite cancel(AgentSession session, boolean advanceGen) {
        Runs runs = runsOf(session, true);
        if (runs == null) {
            return null;
        }

        Disposable.Composite old;
        synchronized (runs) {
            if (advanceGen) {
                runs.cancels.incrementAndGet();
            }

            if (runs.running.size() == 0) {
                // 没有活跃流：不换槽（换掉会让随后到来的 attach 落到已取消槽位）
                old = null;
            } else {
                old = runs.running;
                runs.running = Disposables.composite();
            }

            // 与 attach 共用同一把锁，此处清空不会漏掉并发登记进来的句柄
            runs.actives.clear();
        }

        return old;
    }

    private static void disposeQuietly(Disposable run) {
        try {
            run.dispose();
        } catch (Throwable ignored) {
            // 取消是尽力而为：单个句柄的取消失败不得影响其余句柄
        }
    }

    /**
     * 取得（或创建）会话的运行登记槽。
     *
     * <p>槽位一旦建立就不移除：它同时承载取消代数，每会话一个对象，无累积风险。</p>
     */
    private static Runs runsOf(AgentSession session, boolean create) {
        if (session == null) {
            return null;
        }

        Object value = session.attrs().get(ATTR_RUNS);
        if (value instanceof Runs) {
            return (Runs) value;
        }

        if (create == false) {
            return null;
        }

        Runs created = new Runs();
        Object old = session.attrs().putIfAbsent(ATTR_RUNS, created);
        return old instanceof Runs ? (Runs) old : created;
    }

    /**
     * 单个会话的运行登记槽。
     */
    private static final class Runs {
        /** 活跃运行句柄；取消时整体替换，替换后的旧槽处于已 dispose 状态 */
        volatile Disposable.Composite running = Disposables.composite();
        /** 同集合的镜像，用于「排除已自行结束的句柄」的精确计数（Composite 不提供遍历） */
        final CopyOnWriteArrayList<Disposable> actives = new CopyOnWriteArrayList<>();
        /** 取消代数：每次 cancelAll 自增 */
        final AtomicInteger cancels = new AtomicInteger();
    }
}
