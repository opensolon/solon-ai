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

import org.noear.solon.ai.harness.session.goal.GoalExtension;
import org.noear.solon.ai.harness.session.queue.QueueExtension;
import org.noear.solon.ai.harness.session.steer.SteerExtension;

/**
 * harness 内置扩展的<b>装配聚合入口</b> —— 一次调用装齐 steer / queue / goal。
 *
 * <p>用途是消除宿主组合根里那段「必须按某序、且必须一个不漏」的手写装配序列。
 * 典型宿主（soloncode 的 {@code WorkspaceManager}）此前写成：</p>
 *
 * <pre>{@code
 * // 改前：三处分散在 60 行里，中间还夹着别的命令注册
 * engine.addExtension(new SteerExtension());
 * engine.addExtension(new QueueExtension());
 * ... 其它装配 ...
 * GoalExtension goal = GoalExtension.builder(id).budget(...).build();
 * goal.setEnabled(goalsEnabled);
 * engine.addExtension(goal);
 * }</pre>
 *
 * <pre>{@code
 * // 改后：一句装齐，句柄可继续取门面
 * HarnessExtensions.Handles handles = HarnessExtensions.installDefaults(engine,
 *         GoalExtension.builder(id).budget(70, 85).workspace(path).build());
 * handles.goal().setEnabled(goalsEnabled);
 * }</pre>
 *
 * <h3>为什么是「聚合入口」而不是给每个扩展加 builder</h3>
 * <p>steer 有真旋钮（{@link org.noear.solon.ai.harness.session.steer.SteerLimits}）、
 * goal 有真旋钮（预算/停滞/熔断/验证器），二者各自已有 builder；
 * queue 零旋钮、零状态、纯静态中转，加 builder 只会得到一个空壳
 * （判据见 README「常见坑 15：不要给零旋钮的能力套 builder」）。
 * 真正缺的能力不是「每个类怎么建」，而是「三件套怎么一次装对」——那是组合根的职责，
 * 所以承载点在这里。</p>
 *
 * <h3>顺序与幂等</h3>
 * <ul>
 *   <li>顺序固定为 steer → queue → goal（与宿主历史装配顺序一致）。
 *       三者 onAttach 都只向 {@code CommandRegistry} 注册命令，而 register 是
 *       {@code putIfAbsent} 且命令名互不冲突，因此顺序本身不承载语义；
 *       固定它只是为了让装配结果可复现。</li>
 *   <li><b>幂等</b>：引擎里已挂载同类型扩展时不再重复添加。{@code addExtension}
 *       不查重、重复会二次回调 {@code onAttach}（见 {@link HarnessExtension} 的说明），
 *       聚合入口替宿主挡掉这个坑：重复调用返回的是<b>已挂载</b>的那个实例。</li>
 * </ul>
 *
 * <h3>不做的事</h3>
 * <p>不接管宿主自有扩展（如 soloncode 的 loop / manager 扩展）——
 * 它们属宿主域能力，仍走 {@code engine.addExtension(...)} 追加。
 * 本类只承诺「harness 内置三件套一次性装好」。</p>
 *
 * @author noear
 * @since 4.2.1
 */
public final class HarnessExtensions {

    private HarnessExtensions() {
    }

    /**
     * 用 goal 的缺省配置装齐三件套（workspaceId 兼作验证器执行目录）。
     *
     * <p><b>边界</b>：本重载会按缺省建 {@code GoalScheduler}，而 {@code JobManager} 是 Solon 容器单例。
     * 无容器宿主（纯 JUnit、嵌入式 headless）调用会因 {@code JobManager} 不可用而失败——
     * 那不是本类的缺陷，而是「goal 调度需要容器」这条既有语义。这类宿主请改用
     * {@link #installDefaults(HarnessEngine, GoalExtension)}，并以
     * {@code GoalExtension.builder(id).scheduler(null).build()} 显式关闭调度
     * （仅保留 /goal 命令与 Talent）。</p>
     *
     * <p>需要调 goal 预算/停滞/熔断阈值时，同样改用带 {@code GoalExtension} 的重载。</p>
     *
     * @param engine      目标引擎
     * @param workspaceId 工作区标识（goal job 名前缀，跨工作区防撞）；可空（仅单工作区进程安全）
     */
    public static Handles installDefaults(HarnessEngine engine, String workspaceId) {
        return installDefaults(engine, GoalExtension.builder(workspaceId).build());
    }

    /**
     * 装齐三件套，goal 用宿主已配置好的实例。
     *
     * <p>{@code goal} 传 null 时只装 steer + queue（纯受控环境或宿主自行装配 goal 的场景）。</p>
     *
     * @param engine 目标引擎
     * @param goal   已带配置的 goal 扩展；可空
     */
    public static Handles installDefaults(HarnessEngine engine, GoalExtension goal) {
        if (engine == null) {
            return new Handles(null, null, goal);
        }

        SteerExtension steer = existing(engine, SteerExtension.class);
        if (steer == null) {
            steer = new SteerExtension();
            engine.addExtension(steer);
        }

        QueueExtension queue = existing(engine, QueueExtension.class);
        if (queue == null) {
            queue = new QueueExtension();
            engine.addExtension(queue);
        }

        if (goal != null) {
            GoalExtension mounted = existing(engine, GoalExtension.class);
            if (mounted != null) {
                goal = mounted;   // 幂等：已挂载者优先，避免二次 onAttach 重复建调度器
            } else {
                engine.addExtension(goal);
            }
        }

        return new Handles(steer, queue, goal);
    }

    private static <T extends HarnessExtension> T existing(HarnessEngine engine, Class<T> type) {
        return engine.getExtension(type).orElse(null);
    }

    /**
     * 装配结果句柄 —— 三个门面一次取回，省去宿主再写三行 {@code getExtension(...)}。
     *
     * <p>取回的都是<b>实际挂载在引擎上</b>的实例（幂等分支下可能是宿主的旧实例），
     * 因此对句柄做的任何配置都会作用到生效对象上。</p>
     */
    public static final class Handles {
        private final SteerExtension steer;
        private final QueueExtension queue;
        private final GoalExtension goal;

        Handles(SteerExtension steer, QueueExtension queue, GoalExtension goal) {
            this.steer = steer;
            this.queue = queue;
            this.goal = goal;
        }

        /** steer 门面（插话投递 / 取消 / 上限查询）。 */
        public SteerExtension steer() {
            return steer;
        }

        /** queue 门面（入队 / 领取 / 续发）。 */
        public QueueExtension queue() {
            return queue;
        }

        /** goal 门面（状态 / 暂停 / 恢复 / 执行器注册）；未装配时为 null。 */
        public GoalExtension goal() {
            return goal;
        }
    }
}
