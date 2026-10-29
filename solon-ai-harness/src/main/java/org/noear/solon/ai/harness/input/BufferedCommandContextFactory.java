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
 * @author noear
 * @since 4.2
 */
public class BufferedCommandContextFactory implements CommandContextFactory {
    private final HarnessInput input;
    private final boolean remote;

    public BufferedCommandContextFactory(HarnessInput input) {
        this(input, true);
    }

    public BufferedCommandContextFactory(HarnessInput input, boolean remote) {
        this.input = input;
        this.remote = remote;
    }

    @Override
    public CommandContext create(HarnessEngine engine, AgentSession session, ParsedCommand command) {
        return new BufferedCommandContext(session,
                command.getRawInput(), command.getName(), command.getArgs()) {
            @Override
            public boolean isRemote() {
                return remote;
            }

            @Override
            public String getSource() {
                return input.getSource();
            }

            @Override
            public String getSourceUserId() {
                return input.getSourceUserId();
            }

            @Override
            public String getReplyTarget() {
                return input.getReplyTarget();
            }

            @Override
            public String getMessageId() {
                return input.getMessageId();
            }
        };
    }

    @Override
    public boolean isRemote() {
        return remote;
    }
}
