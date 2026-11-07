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
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.lang.Internal;
import org.noear.solon.lang.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.Objects;
import java.util.function.BinaryOperator;

/**
 * 会话运行态快照（一次读全，避免宿主自己拼装）
 *
 * <p>快照由两部分组成：</p>
 * <ul>
 *     <li><b>核心事实</b>（固定字段）：运行流（{@link SessionRuns}）、待审批挂起卡（{@link HitlCard}）、
 *         输入受理窗口（引擎 {@code handle} 第 0 步的占位）、取消代数。漏查任何一个都会导致真实故障：
 *         漏查 HITL 会把审批中的会话判成空闲、新输入直接顶掉挂起任务。</li>
 *     <li><b>扩展事实</b>（开放槽位）：由插件经 {@code HarnessExtension#contributeSessionStatus} 贡献，按 {@link Key} 读取。
 *         核心不预设插件有哪些事实——新增插件事实不需要修改本类。</li>
 * </ul>
 *
 * <p>本类是这些事实的<b>只读快照</b>，不做缓存、不订阅变更：每次都是现读，
 * 因此不会出现「快照过期」的坑。宿主要判断「现在」能不能接单，调一次即可。</p>
 *
 * <p><b>一律用 {@code engine.status(session)}</b>：扩展事实只有引擎能汇集（只有它知道装了哪些插件）。
 * 静态的 {@link #of(AgentSession)} 只读核心事实，所有 {@link Key} 都返回缺省值。</p>
 *
 * <p><b>核心不定义任何插件事实</b>：本类没有、也不应有「排队数」「插话数」之类的字段或知名键——
 * 内核没有 queue。插件事实一律由插件自己定义 {@link Key} 常量（如 queue 的
 * {@code QueueExtension.STATUS_PENDING}、steer 的 {@code SteerExtension.STATUS_PENDING}），
 * 宿主按键读取。内核自己的判定若依赖插件（如受理的先进先出），走核心定义的能力接口
 * （{@link org.noear.solon.ai.harness.spi.InputBacklog#hasPending}），不走本快照。</p>
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
    private final int cancelGeneration;
    private final boolean admitting;
    private final Map<Key<?>, Object> facts;

    private SessionStatus(boolean busy, int activeRunCount, int pendingHitl,
                          int cancelGeneration, boolean admitting, Map<Key<?>, Object> facts) {
        this.busy = busy;
        this.activeRunCount = activeRunCount;
        this.pendingHitl = pendingHitl;
        this.cancelGeneration = cancelGeneration;
        this.admitting = admitting;
        this.facts = facts;
    }

    /**
     * 读取会话当前的<b>核心</b>运行态（session 为 null 时返回全空闲快照）。
     *
     * <p>不含扩展事实：所有 {@link Key} 返回缺省值。宿主请用 {@code engine.status(session)}。</p>
     */
    public static SessionStatus of(@Nullable AgentSession session) {
        return builder(session).build();
    }

    /**
     * 以会话核心事实为底，开始组装快照（供引擎汇集扩展事实；宿主用 {@code engine.status}）。
     *
     * @since 4.2.0
     */
    public static Builder builder(@Nullable AgentSession session) {
        return new Builder(session);
    }

    /**
     * 读取一项扩展事实（未贡献时返回该键的缺省值）。
     *
     * @since 4.2.0
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) {
        Object value = facts.get(key);
        return value == null ? key.getDefaultValue() : (T) value;
    }

    /**
     * 是否有扩展贡献了该事实。
     *
     * @since 4.2.0
     */
    public boolean has(Key<?> key) {
        return facts.containsKey(key);
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
     * 是否「占着会话」：正在受理（{@link #isAdmitting()}）、有活跃流或有待审批卡
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
     * <p>占位由引擎 {@code handle} 在受理时取得、运行流登记后释放；超过占位时限的占位不算。</p>
     *
     * @since 4.2.0
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
        StringBuilder buf = new StringBuilder();
        buf.append("SessionStatus{busy=").append(busy)
                .append(", runs=").append(activeRunCount)
                .append(", hitl=").append(pendingHitl)
                .append(", admitting=").append(admitting);
        for (Map.Entry<Key<?>, Object> kv : facts.entrySet()) {
            buf.append(", ").append(kv.getKey().getName()).append('=').append(kv.getValue());
        }
        return buf.append('}').toString();
    }

    /**
     * 扩展事实的类型化键。
     *
     * <p>由事实的拥有者（插件或核心）定义为常量，按<b>实例身份</b>区分（同名不同实例互不干扰）。
     * 多个扩展贡献同一个键时，按键的合并规则合成：</p>
     * <ul>
     *     <li>{@link #count(String)}：计数，求和（负数按 0 计）；</li>
     *     <li>{@link #of(String, Object)}：单一拥有者，后写覆盖；</li>
     *     <li>{@link #of(String, Object, BinaryOperator)}：自定义合并。</li>
     * </ul>
     *
     * @since 4.2.0
     */
    public static final class Key<T> {
        private final String name;
        private final T defaultValue;
        private final BinaryOperator<T> merger;

        private Key(String name, T defaultValue, BinaryOperator<T> merger) {
            this.name = Objects.requireNonNull(name, "name");
            this.defaultValue = defaultValue;
            this.merger = Objects.requireNonNull(merger, "merger");
        }

        /** 计数类事实（缺省 0，多提供者求和，负数按 0 计） */
        public static Key<Integer> count(String name) {
            return new Key<>(name, 0, (a, b) -> Math.max(0, a) + Math.max(0, b));
        }

        /** 单一拥有者事实（后写覆盖） */
        public static <T> Key<T> of(String name, @Nullable T defaultValue) {
            return new Key<>(name, defaultValue, (a, b) -> b);
        }

        /** 自定义合并规则的事实（非 null 的缺省值须是合并的单位元：首个贡献也会与它合并） */
        public static <T> Key<T> of(String name, @Nullable T defaultValue, BinaryOperator<T> merger) {
            return new Key<>(name, defaultValue, merger);
        }

        public String getName() {
            return name;
        }

        @Nullable
        public T getDefaultValue() {
            return defaultValue;
        }

        T merge(T existing, T value) {
            return merger.apply(existing, value);
        }

        @Override
        public String toString() {
            return "Key{" + name + "}";
        }
    }

    /**
     * 受理占位登记值是否有效（唯一实现；引擎的受理闸门也委托这里）。
     *
     * <p>登记值是凭据本身（{@link BooleanSupplier}：未释放且未超时为真）；兼容旧的 {@code Boolean.TRUE} 写法。
     * 宿主读占位用 {@code engine.status(session).isAdmitting()}，不要直接调本方法。</p>
     */
    @Internal
    public static boolean isAdmissionMark(@Nullable Object mark) {
        if (mark instanceof BooleanSupplier) {
            return ((BooleanSupplier) mark).getAsBoolean();
        }
        return Boolean.TRUE.equals(mark);
    }

    /**
     * 快照组装器：核心事实在创建时读定，扩展经 {@link #put} 贡献事实。
     *
     * @since 4.2.0
     */
    public static final class Builder {
        private final boolean busy;
        private final int activeRunCount;
        private final int pendingHitl;
        private final int cancelGeneration;
        private final boolean admitting;
        private final Map<Key<?>, Object> facts = new LinkedHashMap<>();

        private Builder(@Nullable AgentSession session) {
            if (session == null) {
                this.busy = false;
                this.activeRunCount = 0;
                this.pendingHitl = 0;
                this.cancelGeneration = 0;
                this.admitting = false;
            } else {
                this.activeRunCount = SessionRuns.activeCount(session);
                this.busy = activeRunCount > 0;
                this.pendingHitl = HitlCard.pendingCount(session);
                this.cancelGeneration = SessionRuns.cancelCount(session);
                this.admitting = isAdmissionMark(session.attrs().get(HarnessAttrs.INPUT_ADMITTING));
            }
        }

        /**
         * 贡献一项事实（null 忽略；已有值时按键的合并规则合成）。
         */
        @SuppressWarnings("unchecked")
        public <T> Builder put(Key<T> key, @Nullable T value) {
            if (key == null || value == null) {
                return this;
            }
            T base = facts.containsKey(key) ? (T) facts.get(key) : key.getDefaultValue();
            facts.put(key, base == null ? value : key.merge(base, value));
            return this;
        }

        public SessionStatus build() {
            Map<Key<?>, Object> snapshot = facts.isEmpty()
                    ? Collections.<Key<?>, Object>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(facts));
            return new SessionStatus(busy, activeRunCount, pendingHitl, cancelGeneration, admitting, snapshot);
        }
    }
}
