/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness;

import org.noear.solon.ai.AbsSwitchable;
import org.noear.solon.lang.Nullable;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Harness 扩展接口基类（提供启用控制与引擎绑定）
 *
 * <p><b>引擎绑定</b>：持有引擎状态的扩展（命令回调、调度器、索引等绑着某个引擎）在 {@link #onAttach} 开头调用
 * {@link #bindEngine}，保证一个实例同一时刻只服务一个未关闭的引擎；引擎回调的整体清理钩子（如 {@link #onDetach}）
 * 用 {@link #isBoundTo} 忽略别的引擎发来的回调。无状态扩展不必调用，可以挂到多个引擎上共用。</p>
 *
 * @author noear
 * @since 4.1.1
 */
public abstract class AbsHarnessExtension extends AbsSwitchable implements HarnessExtension {
    private final AtomicReference<HarnessEngine> boundEngine = new AtomicReference<>();

    /**
     * 绑定到引擎（持有引擎状态的扩展在 {@link #onAttach} 开头调用）。
     *
     * <ul>
     *   <li>首次绑定：返回 null；</li>
     *   <li>同一引擎上再次挂接（{@code removeExtension} 之后再 {@code addExtension}）：返回该引擎；</li>
     *   <li>前一个引擎已关闭：换绑，返回前一个引擎——子类据此丢掉前一个引擎的状态、按新引擎重建；</li>
     *   <li>前一个引擎未关闭：抛出，不换绑。从运行中的引擎上 {@code removeExtension} 不算解绑：那个引擎的命令表里
     *       仍留着本实例注册的命令（命令表不能注销），换绑后它们会改按新引擎维护状态。</li>
     * </ul>
     *
     * <p>构建半途失败的引擎会自行关闭（见 {@code HarnessEngine.Builder#build}），在它上面已绑定的实例随之可以换绑。</p>
     *
     * @return 之前绑定的引擎（首次为 null）
     * @throws IllegalStateException 本实例已绑在另一个未关闭的引擎上（共用会把两个引擎的会话、命令与调度混在一起）
     * @since 4.2.0
     */
    @Nullable
    protected final HarnessEngine bindEngine(HarnessEngine engine) {
        Objects.requireNonNull(engine, "engine");
        while (true) {
            HarnessEngine previous = boundEngine.get();
            if (previous == engine) {
                return previous;
            }
            if (previous != null && previous.isClosed() == false) {
                throw new IllegalStateException(getClass().getSimpleName()
                        + " is already attached to another engine that is not shut down; create one instance per engine");
            }
            if (boundEngine.compareAndSet(previous, engine)) {
                return previous;
            }
        }
    }

    /**
     * 是否绑在这个引擎上（按引用比较；从未绑定时为 false）。
     *
     * <p>{@link #onDetach} 这类清掉整个扩展状态的钩子据此忽略别的引擎发来的回调：换绑之后，旧引擎上迟到的
     * {@code removeExtension} 不该卸掉新引擎上的调度。</p>
     *
     * @since 4.2.0
     */
    protected final boolean isBoundTo(@Nullable HarnessEngine engine) {
        return engine != null && boundEngine.get() == engine;
    }
}
