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
package org.noear.solon.ai.harness.session.steer;

import org.noear.solon.ai.agent.react.AbsReActEvent;
import org.noear.solon.ai.agent.react.ReActTrace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 任务结束时仍有未消费插话（steer_dropped）。
 *
 * <p><b>本事件只是「结果展示」，不是「兜底动作」</b>：转排队（QueueHelper）的持久化
 * 已由 {@link SteerHelper} 在调用本事件之前完成（见
 * {@link SteerHelper#enqueueIntoSessionQueue}）。因此即使本事件因无 sink
 * 被丢弃，"已接受但永不生效"也不会发生——这是与「用事件承载业务动作」的关键分界。</p>
 *
 * <p>{@link #getQueued()} 与 {@link #getFailed()} 语义不可混：前者已进队列
 * （客户端应清除待生效气泡），后者未进队列且已回填易失邮箱待下次开流重试
 * （客户端应保留，并提示用户尚未排队）。</p>
 *
 * @author noear
 * @since 2026
 */
public class SteerDroppedEvent extends AbsReActEvent {
    private final List<SteerMessage> queued;
    private final List<SteerMessage> failed;

    public SteerDroppedEvent(ReActTrace trace, List<SteerMessage> queued, List<SteerMessage> failed) {
        super(trace);
        this.queued = immutable(queued);
        this.failed = immutable(failed);
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
     * 未能转入队列的插话（已回填易失邮箱，待下次开流重试）
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
