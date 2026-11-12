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
package org.noear.solon.ai.harness.command.builtin;

import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.ai.harness.i18n.HarnessI18nKeys;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.harness.command.AbsCommand;
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.command.CommandOutput;
import org.noear.solon.lang.Nullable;

import java.util.Locale;

/**
 * /interrupt 命令 - 中断当前正在执行的任务。
 *
 * <p>在 CLI 中用户可通过 ESC 键中断，在 Web 前端可通过停止按钮中断，
 * 但在 IM 渠道（微信/飞书/钉钉）中缺少中断途径。此命令为 IM 场景提供
 * 统一的中断能力，也适用于所有终端。</p>
 *
 * <p>实现退化为 {@code engine.interrupt(session, InterruptMode.INTERRUPT_ALL)} 的薄壳：中断语义只有一处实现，
 * 插件参与（如清队列）也由引擎统一回调。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public class InterruptCommand extends AbsCommand {
    @Override
    public String name() {
        return "interrupt";
    }

    @Override
    public String description(@Nullable Locale locale) {
        return HarnessI18n.get(locale, HarnessI18nKeys.COMMAND_INTERRUPT_DESCRIPTION);
    }

    @Override
    public String[] examples(@Nullable Locale locale) {
        return new String[]{
                "/interrupt"
        };
    }

    @Override
    public boolean runnableWhenBusy() {
        return true;
    }

    @Override
    public void execute(HarnessEngine engine, CommandInput input, CommandOutput output) throws Exception {
        AgentSession session = input.getSession();

        if (session == null || engine == null) {
            return;
        }

        // 必须经引擎：插件的中断参与（先清积压再停流）只在引擎入口回调，不存在绕开引擎的第二套中断。
        if (Boolean.TRUE.equals(engine.interrupt(session, InterruptMode.INTERRUPT_ALL).getValue())) {
            output.println(HarnessI18n.get(input.getLocale(), HarnessI18nKeys.COMMAND_INTERRUPT_DONE));
        } else {
            output.println(HarnessI18n.get(input.getLocale(), HarnessI18nKeys.COMMAND_INTERRUPT_IDLE));
        }
    }
}
