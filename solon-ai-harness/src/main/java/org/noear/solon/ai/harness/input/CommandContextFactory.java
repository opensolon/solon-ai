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
package org.noear.solon.ai.harness.input;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.CommandContext;

/**
 * 命令上下文工厂（由宿主实现）
 * <p>
 * 命令的分发与执行由 harness 统一处理，但「回执怎么落地、是否需要读终端」属于端特性，
 * 因此 CommandContext 的构造交由宿主决定。
 *
 * @author noear
 * @since 4.2
 */
public interface CommandContextFactory {
    /**
     * 创建命令上下文
     *
     * @param engine   引擎
     * @param session  当前会话
     * @param command  已解析的命令
     * @param terminal 本次输入所在渠道是否具备终端能力（决定
     *                 {@link org.noear.solon.ai.harness.command.Command#terminalOnly()} 是否放行，
     *                 并作为 {@link CommandContext#isTerminal()} 的取值来源）
     */
    CommandContext create(HarnessEngine engine, AgentSession session, ParsedCommand command, boolean terminal);
}
