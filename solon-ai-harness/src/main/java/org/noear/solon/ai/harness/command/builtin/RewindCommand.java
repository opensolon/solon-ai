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

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.core.util.Assert;

/**
 * /rewind 命令，用于回退最近 N 条对话记录
 *
 * @author noear
 * @since 2026.5.8
 */
public class RewindCommand implements Command {
    private static final String DIM = "\033[2m";
    private static final String RESET = "\033[0m";

    @Override
    public String name() {
        return "rewind";
    }

    @Override
    public String description() {
        return "回退对话记录 (<n>, 默认1)";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/rewind",
                "/rewind <n>"
        };
    }

    @Override
    public void execute(HarnessEngine engine, CommandContext ctx) throws Exception {
        String flag = ctx.argAt(0);
        int count = 1;

        if (Assert.isInteger(flag)) {
            count = Integer.parseInt(flag);
            if (count <= 0) {
                ctx.println(ctx.color(DIM + "回退数量必须为正整数" + RESET));
                return;
            }
        }

        ctx.getSession().removeLatestMessage(count);
        ctx.println(ctx.color(DIM + "已回退 " + count + " 条记录" + RESET));
    }
}