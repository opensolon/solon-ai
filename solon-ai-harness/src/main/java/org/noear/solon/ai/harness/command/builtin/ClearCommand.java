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
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.core.util.Assert;

/**
 * /clear 命令
 *
 * @author noear
 * @since 2026.4.28
 */
public class ClearCommand implements Command {
    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description() {
        return "清空会话记录";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/clear",
        };
    }

    @Override
    public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) {
        AgentSession session = ctx.getSession();

        if (session == null) {
            return;
        }

        session.clear();
    }
}
