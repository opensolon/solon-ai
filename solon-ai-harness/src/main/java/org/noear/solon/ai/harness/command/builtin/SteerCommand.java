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
import org.noear.solon.ai.harness.session.steer.SteerInterceptor;
import org.noear.solon.ai.harness.session.steer.SteerInterceptor.SteerResult;
import org.noear.solon.core.util.Assert;

/**
 * /steer 命令 - 向正在执行的任务插入实时补充（插话）。
 *
 * <p>CLI 前端用 Enter 提交插话、Web 前端调 /web/chat/steer，但 IM 渠道（微信/飞书/钉钉）
 * 缺少插话途径。此命令为 IM 场景提供统一的插话能力：把补充消息注入<b>当前</b>任务的工作记忆，
 * 由插话拦截器在下一个采样边界消费，不打断进行中的模型流与工具调用。</p>
 *
 * <p>{@link #runnableWhenBusy()} 返回 true，使其可穿透统一输入入口的忙碌检查、在任务执行中被受理。
 * 本命令<b>只做投递，不做降级</b>：插话邮箱满、任务未运行或轮次已切换时直接告知失败原因，
 * 由用户自行决定是否改用 /queue 排队或直接发送——与 /web/chat/steer 的 409 纯语义对齐
 * （Web 端同样是失败后由前端决策转排队/普通发送，后端不代为降级）。</p>
 *
 * @author noear
 * @since 2026.11
 */
public class SteerCommand implements Command {

    @Override
    public String name() {
        return "steer";
    }

    @Override
    public String description() {
        return "向正在执行的任务插入实时补充（插话）";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/steer 补充一个要求：顺便更新 README",
                "/steer 注意别改动测试代码"
        };
    }

    @Override
    public boolean runnableWhenBusy() {
        return true;
    }

    @Override
    public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) {
        String text = ctx.getArgsJoined();
        if (Assert.isEmpty(text)) {
            ctx.println("用法：/steer <补充内容>（在任务执行中向当前任务插入实时补充）");
            return;
        }

        AgentSession session = ctx.getSession();
        InputOrigin resolved = origin == null ? InputOrigin.NONE : origin;
        if (resolved.getChannel() == null || resolved.getChannel().isEmpty()) {
            resolved = InputOrigin.of("command", resolved.getSenderId(),
                    resolved.getReplyTarget(), resolved.getMessageId());
        }

        SteerResult result = SteerInterceptor.steer(session, null, null, text, resolved, null);

        switch (result.getStatus()) {
            case STEERED:
                ctx.println("已插话，将在下一步注入当前任务（待生效 " + result.getQueued() + " 条）");
                break;
            case BOX_FULL:
                ctx.println("插话邮箱已满（上限 " + SteerInterceptor.MAX_BOX_SIZE + " 条），可用 /queue 排队");
                break;
            case NOT_RUNNING:
            case TURN_CHANGED:
                ctx.println("当前无正在执行的任务可插话，可直接发送或用 /queue 排队");
                break;
            case TEXT_TOO_LONG:
                ctx.println("插话内容过长（上限 " + SteerInterceptor.MAX_TEXT_LENGTH + " 字符）");
                break;
            default:
                ctx.println("插话未生效：" + result.getStatusCode());
        }
    }
}
