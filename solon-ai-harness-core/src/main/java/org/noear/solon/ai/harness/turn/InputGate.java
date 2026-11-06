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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.session.HarnessAttrs;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.lang.Nullable;

/**
 * 输入受理窗口（宿主启动一个任务前的原子占位）。
 *
 * <p><b>为什么需要它</b>：{@code engine.handle(...)} 返回后，宿主到真正订阅出流之间有一段
 * 窗口——期间「还没有活跃流」，但这条输入事实上已经被受理了。若不占位，两个并发请求
 * 会同时看到空闲、各开一条流，共享同一个 ReActTrace，表现为「刚发出就秒结束」或
 * 「两条流互相覆盖」。</p>
 *
 * <p>{@link SessionStatus} 看不到这个窗口（它只看已登记的运行流与挂起卡），因此窗口态
 * 必须由宿主在受理瞬间自己标记、任务启动后释放。此前每个宿主各写一份 attrs 键 +
 * synchronized 块，键名还不统一（{@code cli.input.admitting}），换端就不通用。本类把它
 * 收归 harness：一个 {@link #tryAcquire} 完成「判定空闲 + 占位」这个不可分割的动作，
 * 释放交给 {@link Ticket#close()}，忘记释放也有 {@link SessionStatus} 兜底读得到。</p>
 *
 * <p><b>典型用法</b>（受理控制的标准骨架）：</p>
 * <pre>{@code
 * try (InputGate.Ticket ticket = InputGate.tryAcquire(session)) {
 *     if (!ticket.isAcquired()) {
 *         // 会话已被占用：按宿主策略排队 / 插话 / 拒绝
 *         return;
 *     }
 *     InputOutcome outcome = engine.handle(input);
 *     // ... 起流
 * }
 * }</pre>
 *
 * <p><b>与队列派发的关系</b>：由队列派发而来的那一轮<b>不应</b>再占位——它本身就是
 * 在会话空闲时被领取的，再占位会让「同步完成的命令」误判成忙态。宿主应跳过占位
 * （{@code tryAcquire} 不会被调用即可）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class InputGate {
    private InputGate() {
    }

    /**
     * 尝试占位（原子：判定与占位在同一临界区）。
     *
     * <p>判定口径是「已有活跃运行流」或「已被其他输入占位」。<b>刻意不包含 HITL 待审批</b>：
     * 审批挂起时引擎里没有运行流，宿主可能仍要接受旁路输入（如新的指令顶掉挂起任务），
     * 那属于受理策略而非开流竞态，由宿主自己用 {@link SessionStatus#isOccupied()} 先判。
     * 本类只保证一件事：两条输入不会同时开流。</p>
     *
     * @return 受理凭据；永不返回 null，用 {@link Ticket#isAcquired()} 判断是否真的占到了
     */
    public static Ticket tryAcquire(@Nullable AgentSession session) {
        if (session == null) {
            return Ticket.NONE;
        }

        synchronized (session.attrs()) {
            if (SessionStatus.of(session).isBusy() || isAdmitting(session)) {
                return Ticket.NONE;
            }
            session.attrs().put(HarnessAttrs.INPUT_ADMITTING, Boolean.TRUE);
            return new Ticket(session);
        }
    }

    /**
     * 是否正处在「输入已受理、流尚未登记」的窗口内。
     */
    public static boolean isAdmitting(@Nullable AgentSession session) {
        return session != null && Boolean.TRUE.equals(session.attrs().get(HarnessAttrs.INPUT_ADMITTING));
    }

    /**
     * 受理凭据：持有期间表示本线程已受理一条输入。
     *
     * <p>幂等释放；未占到的凭据是空操作，因此可以在 {@code try-with-resources} 里
     * 无条件使用，不需要先判空。</p>
     */
    public static final class Ticket implements AutoCloseable {
        /** 未占到窗口的空凭据（可在 try-with-resources 中无条件使用）。 */
        public static final Ticket NONE = new Ticket(null);

        private final AgentSession session;
        private boolean released;

        private Ticket(@Nullable AgentSession session) {
            this.session = session;
        }

        /** 是否真的占到了窗口。 */
        public boolean isAcquired() {
            return session != null;
        }

        /**
         * 释放占位（幂等）。
         *
         * <p>必须在任务启动完成（或受理失败）后调用：否则会话将被永久判为占用，
         * 后续输入全部走排队，且 Stop 也无法恢复。</p>
         */
        @Override
        public void close() {
            if (session == null || released) {
                return;
            }
            released = true;
            synchronized (session.attrs()) {
                session.attrs().remove(HarnessAttrs.INPUT_ADMITTING);
            }
        }
    }
}
