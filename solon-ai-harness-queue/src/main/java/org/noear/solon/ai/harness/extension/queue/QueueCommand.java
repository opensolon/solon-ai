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
package org.noear.solon.ai.harness.extension.queue;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.AbsCommand;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.core.util.Assert;

/**
 * /queue 命令 - 排队：把消息挂起，等当前任务结束后自动作为新任务发送。
 *
 * <p>与 /steer（插话，注入当前任务）不同，/queue 不影响进行中的任务，而是排在其后。
 * Web 前端的排队编排在浏览器完成，IM 渠道（微信/飞书/钉钉）没有前端，故排队入队由此命令
 * 承担，续发调度由宿主的会话网关在任务终态统一 drain。队列状态存于会话上下文，
 * 快照即持久化（无独立文件）。</p>
 *
 * <p>{@link #runnableWhenBusy()} 返回 true，使其可穿透忙碌检查、在任务执行中被受理并入队。
 * 会话空闲时入队后由命令收尾的 drain 立即发起，效果等同于普通发送。</p>
 *
 * @author noear
 * @since 4.2.0
 */
class QueueCommand extends AbsCommand {
    /** 实例上限（由所属 QueueExtension 注入） */
    final QueueExtension.Limits limits;

    QueueCommand() {
        this(QueueExtension.Limits.DEFAULT);
    }

    QueueCommand(QueueExtension.Limits limits) {
        this.limits = limits;
    }


    @Override
    public String name() {
        return "queue";
    }

    @Override
    public String description() {
        return "排队：当前任务结束后自动作为新任务发送";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/queue 接着帮我把文档也更新一下",
                "/queue 跑一遍测试"
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
            ctx.println("用法：/queue <消息>（当前任务结束后自动作为新任务发送）");
            return;
        }

        if (text.length() > limits.maxTextLength) {
            ctx.println("排队内容过长（上限 " + limits.maxTextLength + " 字符）");
            return;
        }

        AgentSession session = ctx.getSession();
        // 渠道标识由宿主透传的 origin 承载（分发器已把 CLI/Web/IM 各自的渠道身份带进命令）；
        // 此处不再兜底硬编码渠道名——本地无渠道来源（NONE）是合法值，展示层用 channelOr 自行降级。
        InputOrigin resolved = origin == null ? InputOrigin.NONE : origin;

        int n = QueueStore.enqueue(session, text, resolved, null, null, null, null, null, false, null,
                limits.maxSize, limits.maxTextLength);
        if (n < 0) {
            ctx.println("排队失败（队列已满，上限 " + limits.maxSize + " 条），请稍后再试");
        } else {
            ctx.println("已加入队列（第 " + n + " 位），将按顺序在任务空闲后自动发送");
        }
    }
}
