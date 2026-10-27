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
import org.noear.solon.ai.harness.HarnessEngine;

/**
 * 排队端口：命令层与具体排队实现解耦的中立接口。
 *
 * <p>harness 的内置命令（如 /queue）只面向本接口编程；
 * 宿主（如 soloncode-cli）提供实现并注入，能力本体（队列存储、持久化、容量）
 * 仍留在宿主侧。排队域与插话域（SteerPort）完全隔离，互不降级。</p>
 *
 * <p><b>存储定位同源约束</b>：{@link #bindStorage(HarnessEngine, AgentSession)} 的实现
 * 必须以 {@link HarnessEngine#getWorkspace()} 为唯一 workspace 事实来源来解析会话
 * 数据目录，禁止从 cwd、user.dir 等环境变量另行取值，保证各入口（命令、CLI 回调、
 * Web 网关）写入同一份队列持久化文件。</p>
 *
 * @author noear
 * @since 2026.11
 */
public interface QueuePort {
    /**
     * 为会话绑定队列持久化目录（幂等，多次调用只刷新待执行视图）。
     */
    void bindStorage(HarnessEngine engine, AgentSession session);

    /**
     * 入队一条待发消息。
     *
     * @return 新的队列位次（1 起）；失败（队列满、文本超长、持久化失败）返回 -1
     */
    int enqueue(AgentSession session, String text, String source,
                String sourceUserId, String replyTarget, String messageId);

    /**
     * 队列容量上限（用于提示文案）。
     */
    int maxQueueSize();

    /**
     * 单条文本长度上限（用于提示文案）。
     */
    int maxTextLength();

    /**
     * 取消所有排队中的消息（保留运行中任务），供 /interrupt 等命令复用。
     *
     * @return 是否全部持久化成功
     */
    boolean cancelPending(AgentSession session);
}
