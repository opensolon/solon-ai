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
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.AbsCommand;
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.command.CommandOutput;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;

import java.util.Locale;

/**
 * /rewind 命令，用于回退最近 N 条对话记录
 *
 * @author noear
 * @since 4.2.0
 */
public class RewindCommand extends AbsCommand {
    private static final String DIM = "\033[2m";
    private static final String RESET = "\033[0m";

    @Override
    public String name() {
        return "rewind";
    }

    @Override
    public String description(@Nullable Locale locale) {
        return HarnessI18n.get(locale, HarnessI18nKeys.COMMAND_REWIND_DESCRIPTION);
    }

    @Override
    public String[] examples(@Nullable Locale locale) {
        return new String[]{
                "/rewind",
                "/rewind <n>"
        };
    }

    @Override
    public void execute(HarnessEngine engine, CommandInput input, CommandOutput output) throws Exception {
        String flag = input.argAt(0);
        int count = 1;

        if (Assert.isInteger(flag)) {
            count = Integer.parseInt(flag);
            if (count <= 0) {
                output.println(output.color(DIM + HarnessI18n.get(input.getLocale(), HarnessI18nKeys.COMMAND_REWIND_INVALID) + RESET));
                return;
            }
        }

        input.getSession().removeLatestMessage(count);
        output.println(output.color(DIM + HarnessI18n.fmt(input.getLocale(), HarnessI18nKeys.COMMAND_REWIND_DONE, "count", count) + RESET));
    }
}