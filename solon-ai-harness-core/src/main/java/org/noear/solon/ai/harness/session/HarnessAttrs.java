/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.session;


/**
 * 会话属性的<b>契约键</b>登记（引擎与宿主共享的唯一清单）。
 *
 * <p><b>命名空间规约</b>（宿主与引擎都必须遵守）：</p>
 * <ul>
 *   <li>{@code session.*} —— 归引擎。宿主可以读，但<b>不得写入自有键</b>；
 *       引擎新增/变更契约键必须登记在本类，并在发布说明中标注。</li>
 *   <li>{@code cli.*} / {@code im.*} / {@code web.*} / {@code desktop.*} —— 归宿主私有。
 *       宿主自有状态必须使用自有前缀，严禁占用 {@code session.*}（否则引擎升级时
 *       会与真实契约键撞名，且读侧无法区分「契约」与「宿主私货」）。</li>
 * </ul>
 *
 * <p>本类的常量只做<b>登记与文档化</b>：引擎内部实现仍可与既有私有常量同值共存，
 * 但对外发布/宿主读取一律以本类为准。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class HarnessAttrs {
    private HarnessAttrs() {
    }

    // ========== 引擎契约键（session.*）==========

    /*
     * 插件自有键登记在各插件模块内（queue 的 QueueAttrs：session.queue.*；goal 的 GoalAttrs：session.goal.*；
     * steer 的 SteerAttrs：session.steer），
     * 核心只登记引擎与「核心持有的会话事实槽位」。
     */

    /**
     * 程序派发的一轮（值为 {@link org.noear.solon.ai.harness.turn.TurnDispatch}，transient，未持久化）。
     *
     * <p>随异步任务一直保留到流终态收口，是「本轮是否程序派发」「该用哪个事务收口」的唯一来源。
     * 宿主与插件不要直接读写本键：读用 {@link org.noear.solon.ai.harness.turn.TurnDispatch#current}，登记/注销用
     * {@link org.noear.solon.ai.harness.turn.TurnDispatch#bind} / {@link org.noear.solon.ai.harness.turn.TurnDispatch#unbind}。</p>
     *
     * @since 4.2.0
     */
    public static final String TURN_DISPATCH = "session.turn.dispatch";

    /**
     * 本轮实际生效的子代理名（含 {@code @子代理} 解析结果），主代理归一为 {@code "main"}。
     *
     * <p>由 {@code HarnessEngine#handle} 在返回 TASK 时自动写入；HITL 续流与事件侧
     * 「源代理判定」均以它为准。宿主不要自建同类记忆键。</p>
     */
    public static final String AGENT_EFFECTIVE = "session.agent.effective";

    /**
     * 输入受理窗口标记（{@code InputGate} 占位期间为 {@code TRUE}）。
     *
     * <p>表示「已受理一条输入、它的运行流尚未登记」。{@link SessionStatus} 会把它计入
     * 占用态，否则并发请求会各自开流。宿主不应直接读写本键，用 {@code InputGate}。</p>
     *
     * @since 4.2.1
     */
    public static final String INPUT_ADMITTING = "session.input.admitting";

    // ========== 历史例外键（早于 session.* 规约，已落盘/已发布，键值不可改）==========

    /**
     * 会话工作目录（{@code attrs()} 与工具上下文共用）。
     *
     * <p>与已发布常量 {@code HarnessEngine.ATTR_CWD} 同值。宿主读写一律走
     * {@code HarnessEngine#getSessionCwd} / {@code setSessionCwd}，不要直接访问本键。</p>
     *
     * @since 4.2.0
     */
    public static final String LEGACY_CWD = "__cwd";

    /**
     * 会话选中的模型（{@code FlowContext} 持久化键）。读写走 {@link SessionSelections}。
     *
     * @since 4.2.0
     */
    public static final String LEGACY_MODEL_SELECTED = "_model_selected";

    /**
     * 会话选中的代理（{@code FlowContext} 持久化键）。读写走 {@link SessionSelections}。
     *
     * @since 4.2.0
     */
    public static final String LEGACY_AGENT_SELECTED = "_agent_selected";
}
