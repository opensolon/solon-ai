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
package org.noear.solon.ai.harness.command.builtin;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.session.steer.SteerInterceptor;
import org.noear.solon.ai.harness.session.steer.SteerInterceptor.SteerResult;

/**
 * 插话端口：向运行中任务的插话邮箱投递一条消息（offer），<b>只做投递，不做降级编排</b>。
 *
 * <p>投递失败（邮箱满、任务未运行、轮次已切换）时如实返回状态码，由调用方（命令/前端）
 * 决定后续动作（改用 /queue、转普通发送等）；本端口与排队域（QueuePort）完全隔离。
 * 宿主实现通常是到 {@link SteerInterceptor#steer} 的薄委托。</p>
 *
 * @author noear
 * @since 2026.11
 */
public interface SteerPort {
    /**
     * 投递插话（IM/命令场景 runId、steerId 传空，由后端绑定当前活跃任务并生成 ID）。
     *
     * @return 投递结果（见 {@link SteerResult}，status 与 /web/chat/steer 应答码同名）
     */
    SteerResult steer(AgentSession session, String text, String source,
                      String sourceUserId, String replyTarget, String messageId);
}
