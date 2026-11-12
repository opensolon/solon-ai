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


import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.ai.harness.i18n.HarnessI18nKeys;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.AbsCommand;
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.command.CommandOutput;
import org.noear.solon.lang.Nullable;

import java.util.Locale;

/**
 * /clear 命令
 *
 * @author noear
 * @since 4.2.0
 */
public class ClearCommand extends AbsCommand {
    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description(@Nullable Locale locale) {
        return HarnessI18n.get(locale, HarnessI18nKeys.COMMAND_CLEAR_DESCRIPTION);
    }

    @Override
    public String[] examples(@Nullable Locale locale) {
        return new String[]{
                "/clear",
        };
    }

    @Override
    public void execute(HarnessEngine engine, CommandInput input, CommandOutput output) {
        AgentSession session = input.getSession();

        if (session == null) {
            return;
        }

        session.clear();
    }
}
