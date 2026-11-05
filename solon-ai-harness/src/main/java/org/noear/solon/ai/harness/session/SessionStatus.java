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
package org.noear.solon.ai.harness.session;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.harness.input.HitlCard;
import org.noear.solon.lang.Nullable;

/**
 * 会话运行态快照（一次读全，避免宿主自己拼装）
 *
 * <p>宿主的「这条会话现在能不能接新输入」判定，实际上要同时看三件事：
 * 有没有活跃流（{@link SessionRuns}）、有没有待审批的挂起卡（{@link HitlCard}）、
 * 积压里还压着几条（{@link InputBacklog}）。漏查任何一个都会导致真实故障：
 * 漏查 HITL 会把审批中的会话判成空闲、新输入直接顶掉挂起任务；漏查积压会重复 drain。</p>
 *
 * <p>本类是这些事实的<b>只读快照</b>，不做缓存、不订阅变更：每次都是现读，
 * 因此不会出现「快照过期」的坑。宿主要判断「现在」能不能接单，调一次即可。</p>
 *
 * <p><b>一律用 {@code engine.status(session)}</b>：积压数由插件贡献，只有引擎知道装了哪些插件。
 * 静态的 {@link #of(AgentSession)} 只读核心事实（运行流 / HITL / 受理窗口），
 * 其 {@link #getQueuedCount()} 恒为 0。</p>
 *
 * <p><b>注意 busy 的口径</b>：{@link #isBusy()} 只表示「有活跃运行流」。
 * 「不能接新输入」通常是 {@link #isOccupied()}（忙碌或待审批），两者刻意分开——
 * HITL 挂起时引擎里并没有运行流，但它显然不能被新输入顶掉。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class SessionStatus {
    private final boolean busy;
    private final int activeRunCount;
    private final int pendingHitl;
    private final int queuedCount;
    private final int cancelGeneration;
    private final boolean admitting;

    private SessionStatus(boolean busy, int activeRunCount, int pendingHitl,
                          int queuedCount, int cancelGeneration, boolean admitting) {
        this.busy = busy;
        this.activeRunCount = activeRunCount;
        this.pendingHitl = pendingHitl;
        this.queuedCount = queuedCount;
        this.cancelGeneration = cancelGeneration;
        this.admitting = admitting;
    }

    /**
     * 读取会话当前的<b>核心</b>运行态（session 为 null 时返回全空闲快照）。
     *
     * <p>不含插件贡献的事实：{@link #getQueuedCount()} 恒为 0。宿主请用 {@code engine.status(session)}。</p>
     */
    public static SessionStatus of(@Nullable AgentSession session) {
        return of(session, 0);
    }

    /**
     * 读取会话当前运行态，并补入插件贡献的积压数（供引擎组装；宿主用 {@code engine.status}）。
     *
     * @param queuedCount 各 {@link InputBacklog#size} 之和
     * @since 4.2.0
     */
    public static SessionStatus of(@Nullable AgentSession session, int queuedCount) {
        if (session == null) {
            return new SessionStatus(false, 0, 0, 0, 0, false);
        }

        int activeRunCount = SessionRuns.activeCount(session);
        int pendingHitl = HitlCard.pendingCount(session);
        int cancelGeneration = SessionRuns.cancelCount(session);

        return new SessionStatus(activeRunCount > 0, activeRunCount, pendingHitl,
                queuedCount, cancelGeneration, InputGate.isAdmitting(session));
    }

    /**
     * 是否有活跃运行流（不含 HITL 挂起）
     */
    public boolean isBusy() {
        return busy;
    }

    /**
     * 活跃运行流数量（多流并发时大于 1）
     */
    public int getActiveRunCount() {
        return activeRunCount;
    }

    /**
     * 仍待用户决策的挂起卡数量
     */
    public int getPendingHitlCount() {
        return pendingHitl;
    }

    /**
     * 是否有待审批的挂起卡
     */
    public boolean hasPendingHitl() {
        return pendingHitl > 0;
    }

    /**
     * 积压中待执行的任务数（不含正在执行的那条；仅 {@code engine.status} 的快照含此事实）
     */
    public int getQueuedCount() {
        return queuedCount;
    }

    /**
     * 是否「占着会话」：有活跃流或有待审批卡
     *
     * <p>这是宿主判断「能不能接新输入」的正确口径：HITL 挂起时没有运行流，
     * 但此时新输入会顶掉挂起任务，必须算作占用。</p>
     */
    public boolean isOccupied() {
        return admitting || busy || pendingHitl > 0;
    }

    /**
     * 是否正处在「输入已受理、运行流尚未登记」的窗口内。
     *
     * @see InputGate
     * @since 4.2.1
     */
    public boolean isAdmitting() {
        return admitting;
    }

    /**
     * 当前取消代数（异步宿主可用于「取消早于订阅」的窗口判定）
     */
    public int getCancelGeneration() {
        return cancelGeneration;
    }

    @Override
    public String toString() {
        return "SessionStatus{busy=" + busy + ", runs=" + activeRunCount
                + ", hitl=" + pendingHitl + ", queued=" + queuedCount
                + ", admitting=" + admitting + "}";
    }
}
