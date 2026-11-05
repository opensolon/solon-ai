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
 * 默认命令上下文工厂（缓冲式）
 * <p>
 * 用于宿主未提供工厂的场景：回执写入内存缓冲，由 {@link InputOutcome#getCommandOutput()} 一次取回。
 * 命令内部声明的 Agent 意图由分发器交给引擎装配，因此未提供工厂的宿主（IM 等）同样能触发任务。
 *
 * <p>本工厂不持有任何渠道元数据：来源身份由分发器经
 * {@link org.noear.solon.ai.harness.command.Command#execute(HarnessEngine, CommandContext, InputOrigin)}
 * 直接交给命令，终端能力则由分发器按
 * {@link org.noear.solon.ai.harness.input.HarnessInput#isTerminal()} 传入，
 * 避免「同一条信息既从 input 嗅探、又从工厂注入」的双通路。</p>
 *
 * @author noear
 * @since 4.2
 */
public class BufferedCommandContextFactory implements CommandContextFactory {
    @Override
    public CommandContext create(HarnessEngine engine, AgentSession session, ParsedCommand command, boolean terminal) {
        return new BufferedCommandContext(session,
                command.getRawInput(), command.getName(), command.getArgs(), terminal);
    }
}
