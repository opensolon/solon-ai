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
package org.noear.solon.ai.harness.session.steer;

import org.noear.solon.ai.agent.AgentSession;

import java.util.List;
import java.util.Queue;

/**
 * 插话事件输出端口。
 *
 * <p>session.steer 领域只负责插话邮箱、注入守卫与任务级清理；「注入已生效」「残留转排队」
 * 如何呈现给客户端（Web 事件、IM 通知或静默）属于入口适配器职责。</p>
 *
 * <p><b>路由身份即 session</b>：两个方法都以 {@link AgentSession} 定位投递目标，
 * harness 不再接受任何宿主类型的不透明令牌。实现方若需工作区、连接池等上下文，
 * 自行从 {@link AgentSession#attrs()} 取（宿主在轮次边界写入），harness 不解释。</p>
 *
 * @author noear
 * @since 2026
 */
public interface SteerOutputPort {
    /**
     * 插话已注入当前任务工作记忆（steer_applied）。
     *
     * <p>这是「注入已生效」的唯一信号，实现方必须尽力送达，客户端据此清除待生效态。</p>
     */
    void emitSteerApplied(AgentSession session, String runId, List<SteerMessage> items);

    /**
     * 任务结束仍有未消费插话的兜底处理：转入 session 队列并广播 dropped。
     *
     * <p>「转排队」只依赖 session，「广播」才需要入口上下文——实现方应把两者拆开，
     * 使无法定位到入口（如无前端连接的自动任务）时仍能完成排队而不丢失插话。</p>
     *
     * @param box 待兜底的插话队列（方法调用后视为已消费，由实现方排空）
     */
    void handleDroppedSteers(AgentSession session, Queue<SteerMessage> box, String runId);
}
