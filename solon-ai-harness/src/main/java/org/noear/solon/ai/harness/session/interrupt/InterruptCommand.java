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
package org.noear.solon.ai.harness.session.interrupt;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.AbsCommand;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.input.InputOrigin;

/**
 * /interrupt 命令 - 中断当前正在执行的任务。
 *
 * <p>在 CLI 中用户可通过 ESC 键中断，在 Web 前端可通过停止按钮中断，
 * 但在 IM 渠道（微信/飞书/钉钉）中缺少中断途径。此命令为 IM 场景提供
 * 统一的中断能力，也适用于所有终端。</p>
 *
 * <p>实现退化为 {@code engine.interrupt(session)} 的薄壳：中断语义只有一处实现，
 * 插件参与（如清队列）也由引擎统一回调。</p>
 *
 * @author noear
 * @since 2026.5.15
 */
public class InterruptCommand extends AbsCommand {
    @Override
    public String name() {
        return "interrupt";
    }

    @Override
    public String description() {
        return "中断当前正在执行的任务";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/interrupt"
        };
    }

    @Override
    public boolean runnableWhenBusy() {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) throws Exception {
        AgentSession session = ctx.getSession();

        if (session == null) {
            return;
        }

        // 必须经引擎：插件的中断参与（先清积压再停流）只在引擎入口回调。
        // 无引擎（纯单测手工执行）时退回核心停流。
        boolean interrupted = engine != null ? engine.interrupt(session) : InterruptHelper.interrupt(session);
        if (interrupted) {
            ctx.println("用户已取消任务（或中断）");
        } else {
            ctx.println("当前没有正在执行的任务");
        }
    }
}
