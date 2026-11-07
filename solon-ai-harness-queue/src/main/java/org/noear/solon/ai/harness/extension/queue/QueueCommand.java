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

import java.util.List;

/**
 * /queue 命令 - 排队：把消息挂起，等当前任务结束后自动作为新任务发送。
 *
 * <pre>
 *   /queue &lt;消息&gt;          → 入队
 *   /queue list            → 列出队列（运行中的在首位）
 *   /queue cancel &lt;id|序号&gt; → 取消一条待执行
 *   /queue clear           → 清空全部待执行（不影响正在执行的那条）
 *   /queue -- list         → 「--」之后一律按消息处理（消息恰好是子命令词时用）
 * </pre>
 *
 * <p>子命令只在参数形态完全匹配时生效（{@code list}/{@code clear} 独占整行、{@code cancel} 后恰好一个参数），
 * 其余一律按消息入队——「list all files」这类自然语言不会被误认成子命令。</p>
 *
 * <p>与 /steer（插话，注入当前任务）不同，/queue 不影响进行中的任务，而是排在其后。
 * IM 渠道（微信/飞书/钉钉）没有前端，排队与管理都由此命令承担，续发调度由宿主在任务终态统一 drain。
 * 队列状态存于会话上下文，快照即持久化（无独立文件）。</p>
 *
 * <p>{@link #runnableWhenBusy()} 返回 true，使其可穿透忙碌检查、在任务执行中被受理。
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
        return "排队：当前任务结束后自动作为新任务发送 (<消息>, list, cancel, clear)";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/queue 接着帮我把文档也更新一下",
                "/queue list",
                "/queue cancel <id|序号>",
                "/queue clear"
        };
    }

    @Override
    public boolean runnableWhenBusy() {
        return true;
    }

    @Override
    public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) {
        String text = ctx.getArgsJoined().trim();
        if (Assert.isEmpty(text)) {
            printUsage(ctx);
            return;
        }

        AgentSession session = ctx.getSession();
        if (text.startsWith("--") && (text.length() == 2 || Character.isWhitespace(text.charAt(2)))) {
            enqueue(ctx, session, text.substring(2).trim(), origin);
            return;
        }

        String[] words = text.split("\\s+");
        if (words.length == 1 && "list".equals(words[0])) {
            doList(ctx, session);
        } else if (words.length == 1 && "clear".equals(words[0])) {
            int n = QueueStore.clearPending(session);
            ctx.println(n > 0 ? "已清空 " + n + " 条待执行（正在执行的不受影响）" : "队列里没有待执行的消息");
        } else if (words.length == 2 && "cancel".equals(words[0])) {
            doCancel(ctx, session, words[1]);
        } else {
            enqueue(ctx, session, text, origin);
        }
    }

    private void enqueue(CommandContext ctx, AgentSession session, String text, InputOrigin origin) {
        if (Assert.isEmpty(text)) {
            printUsage(ctx);
            return;
        }
        if (text.length() > limits.maxTextLength) {
            ctx.println("排队内容过长（上限 " + limits.maxTextLength + " 字符）");
            return;
        }

        // 渠道标识由宿主透传的 origin 承载（分发器已把 CLI/Web/IM 各自的渠道身份带进命令）；
        // 此处不再兜底硬编码渠道名——本地无渠道来源（NONE）是合法值，展示层用 channelOr 自行降级。
        InputOrigin resolved = origin == null ? InputOrigin.NONE : origin;

        int n = QueueStore.enqueue(session, text, resolved, null, null, null, null, null,
                limits.maxSize, limits.maxTextLength);
        if (n < 0) {
            ctx.println("排队失败（队列已满，上限 " + limits.maxSize + " 条），请稍后再试");
        } else {
            ctx.println("已加入队列（第 " + n + " 位），将按顺序在任务空闲后自动发送");
        }
    }

    private void doList(CommandContext ctx, AgentSession session) {
        List<QueueItem> items = QueueStore.view(session);
        if (items.isEmpty()) {
            ctx.println("队列为空");
            return;
        }
        ctx.println("队列（共 " + items.size() + " 条）：");
        int i = 1;
        for (QueueItem item : items) {
            ctx.println("  " + i++ + ". [" + item.getId() + "] "
                    + (item.isRunning() ? "(执行中) " : "") + abbreviate(item.getText()));
        }
        ctx.println("取消：/queue cancel <id|序号>；清空：/queue clear");
    }

    private void doCancel(CommandContext ctx, AgentSession session, String idOrIndex) {
        String id = idOrIndex;
        List<QueueItem> items = QueueStore.view(session);
        if (idOrIndex.matches("\\d+")) {
            int index = Integer.parseInt(idOrIndex);
            if (index >= 1 && index <= items.size()) {
                id = items.get(index - 1).getId();
            }
        }
        for (QueueItem item : items) {
            if (item.getId().equals(id) && item.isRunning()) {
                ctx.println("这条正在执行，不能从队列取消（停止当前任务用 /interrupt）");
                return;
            }
        }
        if (QueueStore.cancelItem(session, id)) {
            ctx.println("已取消：" + id);
        } else {
            ctx.println("未找到待执行的消息：" + idOrIndex);
        }
    }

    private void printUsage(CommandContext ctx) {
        ctx.println("用法：/queue <消息>（当前任务结束后自动作为新任务发送）");
        ctx.println("      /queue list | /queue cancel <id|序号> | /queue clear | /queue -- <以子命令词开头的消息>");
    }

    private static String abbreviate(String text) {
        String t = text.replace('\n', ' ');
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }
}
