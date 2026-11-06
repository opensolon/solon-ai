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
 * 插话已注入当前任务工作记忆（steer_applied）。
 *
 * <p>这是「注入已生效」的唯一信号：客户端据此清除待生效态并把气泡落位。
 * 不提供超时猜测终态——那样会把已执行过的插话再次入队。</p>
 *
 * <p><b>刻意不覆写 {@link #getText()}</b>：本事件不属于文本流。ACP 的兜底分支
 * （{@code AcpLink}）、Desktop 的逐类判定都按类型 / 文本处理事件，
 * 一旦给出文本就会被未识别的宿主顺手打印到对话里。需要展示的宿主必须显式按类型分支。</p>
 *
 * <p>投递由 {@link ReActTrace#pushAgentEvent} 统一完成：非流式（无 sink）或订阅已取消时
 * 静默丢弃——本事件无持久化副作用，可以丢。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public class SteerAppliedEvent extends AbsReActEvent {
    private final List<SteerMessage> items;

    public SteerAppliedEvent(ReActTrace trace, List<SteerMessage> items) {
        super(trace);
        this.items = Collections.unmodifiableList(
                items == null ? new ArrayList<SteerMessage>() : new ArrayList<>(items));
    }

    /**
     * 已注入的插话（按投递顺序，不可变副本）
     */
    public List<SteerMessage> getItems() {
        return items;
    }
}
