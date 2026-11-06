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
package org.noear.solon.ai.harness.extension.steer;

import org.noear.solon.ai.agent.react.AbsReActEvent;
import org.noear.solon.ai.agent.react.ReActTrace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 有未消费插话被交接（steer_dropped）——残留插话的<b>唯一</b>输出通道。
 *
 * <p>两种时机（见 {@link #getReason()}）：任务正常结束时仍有残留（{@link Reason#RUN_ENDED}）；
 * 上一任务被 interrupt / 异常终止、新任务开始时交接（{@link Reason#RUN_SUPERSEDED}）。
 * 宿主不再需要在开流前手动交接并自行映射结果。</p>
 *
 * <p><b>本事件只是「结果展示」，不是「兜底动作」</b>：转积压（核心能力接口 {@code InputBacklog#offer}）的持久化
 * 已在推出本事件之前完成。因此即使本事件因无 sink 被丢弃，"已接受但永不生效"也不会发生——
 * 这是与「用事件承载业务动作」的关键分界。</p>
 *
 * <p>{@link #getQueued()} 与 {@link #getFailed()} 语义不可混：前者已进队列
 * （客户端应清除待生效气泡），后者未进队列、已暂存待下次开轮重试且永不注入模型
 * （客户端应保留，并提示用户尚未排队）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public class SteerDroppedEvent extends AbsReActEvent {
    /** 交接原因 */
    public enum Reason {
        /** 任务正常结束，仍有未消费插话 */
        RUN_ENDED,
        /** 上一任务被中断或异常退出（onAgentEnd 未触发），新任务开始时交接 */
        RUN_SUPERSEDED
    }

    private final List<SteerMessage> queued;
    private final List<SteerMessage> failed;
    private final Reason reason;

    public SteerDroppedEvent(ReActTrace trace, List<SteerMessage> queued, List<SteerMessage> failed) {
        this(trace, queued, failed, Reason.RUN_ENDED);
    }

    public SteerDroppedEvent(ReActTrace trace, List<SteerMessage> queued, List<SteerMessage> failed, Reason reason) {
        super(trace);
        this.queued = immutable(queued);
        this.failed = immutable(failed);
        this.reason = reason == null ? Reason.RUN_ENDED : reason;
    }

    /** 交接原因 */
    public Reason getReason() {
        return reason;
    }

    private static List<SteerMessage> immutable(List<SteerMessage> source) {
        return Collections.unmodifiableList(
                source == null ? new ArrayList<SteerMessage>() : new ArrayList<>(source));
    }

    /**
     * 已成功转入 session 队列的插话（转为排队任务）
     */
    public List<SteerMessage> getQueued() {
        return queued;
    }

    /**
     * 未能转入队列的插话（已暂存，待下次开轮重试；永不注入模型）
     */
    public List<SteerMessage> getFailed() {
        return failed;
    }

    /**
     * 是否存在「尚未排队」的插话
     */
    public boolean hasFailed() {
        return failed.isEmpty() == false;
    }
}
