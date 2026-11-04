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

    /** 队列项快照投影（FlowContext.data 的持久化 key，值为 List&lt;Map&gt;） */
    public static final String QUEUE_ITEMS = "session.queue.items";

    /** 队列在飞项（运行中任务的队列项，drain 暂存） */
    public static final String QUEUE_IN_FLIGHT = "session.queue.inFlight";

    /** 队列代际（cancelPending 递增，用于拒绝旧代际的 requeue） */
    public static final String QUEUE_GENERATION = "session.queue.generation";

    /** 队列领取标记（drainOne 期间的原子占位） */
    public static final String QUEUE_CLAIM = "session.queue.claim";

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
}
