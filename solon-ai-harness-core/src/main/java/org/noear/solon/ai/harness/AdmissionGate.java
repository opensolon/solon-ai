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
package org.noear.solon.ai.harness;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.session.HarnessAttrs;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.lang.Nullable;

import java.util.function.BooleanSupplier;

/**
 * 输入受理窗口（包内）：{@code handle} 受理一条输入到它的运行流登记之间的原子占位。
 *
 * <p><b>为什么需要它</b>：{@code handle} 返回 TASK 后，宿主到真正订阅出流之间有一段窗口
 * （宿主常把订阅调度到别的线程）——期间「还没有活跃流」，但这条输入事实上已经被受理了。
 * 不占位，两条并发输入会同时看到空闲、各开一条流，共享同一个 ReActTrace。</p>
 *
 * <p><b>占位由引擎持有，宿主不接触</b>：判定与占位在 {@code handle} 第 0 步同一把会话锁内完成；
 * 结局不是 TASK 时 {@code handle} 返回前释放；是 TASK 时凭据挂在请求上
 * （{@link AdmittedRequest}），流订阅（运行流已登记）或同步调用结束时释放。
 * 兜底两道：用户中断时清除（{@link #clear}），以及占位时限（超时的占位不再算占用）。</p>
 *
 * <p>登记值是凭据本身（{@link BooleanSupplier}：仍有效才为真），释放时只清自己：
 * 迟到的旧凭据释放不会误清新占位。{@link SessionStatus} 按同一口径读取。</p>
 *
 * @author noear
 * @since 4.2.0
 */
final class AdmissionGate {
    /** 缺省占位时限（毫秒）：宿主拿到 TASK 却始终不订阅时，会话最多被占这么久。 */
    static final long DEFAULT_TIMEOUT_MS = 60_000L;

    private AdmissionGate() {
    }

    /** 尝试占位（缺省时限）。 */
    static Hold tryAcquire(@Nullable AgentSession session) {
        return tryAcquire(session, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 尝试占位（原子：判定与占位在同一临界区）。
     *
     * <p>判定口径是「已有活跃运行流」或「已被其他输入占位」。<b>不包含 HITL 待审批与积压</b>：
     * 那两项属于受理策略，由 {@code SessionControl#admit} 另判。本方法只保证两条输入不会同时开流。</p>
     *
     * @param timeoutMs 占位时限（毫秒，&lt;=0 取缺省）
     * @return 受理凭据；永不返回 null，用 {@link Hold#isAcquired()} 判断是否真的占到了
     */
    static Hold tryAcquire(@Nullable AgentSession session, long timeoutMs) {
        if (session == null) {
            return Hold.NONE;
        }

        long timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        synchronized (session.attrs()) {
            if (SessionStatus.of(session).isBusy() || isAdmitting(session)) {
                return Hold.NONE;
            }
            Hold hold = new Hold(session, System.currentTimeMillis() + timeout);
            session.attrs().put(HarnessAttrs.INPUT_ADMITTING, hold);
            return hold;
        }
    }

    /**
     * 是否正处在「输入已受理、流尚未登记」的窗口内（超时的占位不算）。
     */
    static boolean isAdmitting(@Nullable AgentSession session) {
        return session != null && isValidMark(session.attrs().get(HarnessAttrs.INPUT_ADMITTING));
    }

    /**
     * 清除占位（用户中断兜底：宿主看到本轮已取消就不再订阅，占位不能一直留着）。
     */
    static void clear(@Nullable AgentSession session) {
        if (session == null) {
            return;
        }
        synchronized (session.attrs()) {
            Object value = session.attrs().remove(HarnessAttrs.INPUT_ADMITTING);
            if (value instanceof Hold) {
                ((Hold) value).released = true;
            }
        }
    }

    /** 登记值是否表示有效占位（委托 {@link SessionStatus#isAdmissionMark}：只有一份实现）。 */
    static boolean isValidMark(@Nullable Object value) {
        return SessionStatus.isAdmissionMark(value);
    }

    /**
     * 受理凭据：幂等释放，只清自己的登记；未占到的凭据是空操作。
     */
    static final class Hold implements AutoCloseable, BooleanSupplier {
        /** 未占到窗口的空凭据。 */
        static final Hold NONE = new Hold(null, 0L);

        private final AgentSession session;
        private final long deadline;
        private volatile boolean released;

        private Hold(@Nullable AgentSession session, long deadline) {
            this.session = session;
            this.deadline = deadline;
        }

        /** 是否真的占到了窗口。 */
        boolean isAcquired() {
            return session != null;
        }

        /** 占位是否仍有效（未释放且未超时）。 */
        @Override
        public boolean getAsBoolean() {
            return session != null && released == false && System.currentTimeMillis() < deadline;
        }

        /** 释放占位（幂等；只在登记值仍是自己时清除）。 */
        @Override
        public void close() {
            if (session == null || released) {
                return;
            }
            released = true;
            synchronized (session.attrs()) {
                if (session.attrs().get(HarnessAttrs.INPUT_ADMITTING) == this) {
                    session.attrs().remove(HarnessAttrs.INPUT_ADMITTING);
                }
            }
        }

        @Override
        public String toString() {
            return "AdmissionGate.Hold{acquired=" + isAcquired() + ", valid=" + getAsBoolean() + "}";
        }
    }
}
