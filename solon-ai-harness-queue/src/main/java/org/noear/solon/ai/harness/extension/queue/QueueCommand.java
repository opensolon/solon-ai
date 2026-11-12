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
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.command.CommandOutput;
import org.noear.solon.ai.harness.extension.queue.i18n.QueueI18n;
import org.noear.solon.ai.harness.extension.queue.i18n.QueueI18nKeys;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

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
 * <p>写队列（入队、取消、清空）后回调所属扩展同步积压索引，与门面 {@code enqueue} / {@code cancelItem} /
 * {@code clearPending} 同一口径：经命令入队、尚未执行就重启的会话，L0 骨架也能按索引续发。</p>
 *
 * @author noear
 * @since 4.2.0
 */
class QueueCommand extends AbsCommand {
    /** 实例上限（由所属 QueueExtension 注入） */
    final QueueExtension.Limits limits;
    /** 队列变更回调（所属扩展同步积压索引；null = 不维护索引，仅单测用） */
    private final Consumer<AgentSession> onChanged;

    QueueCommand() {
        this(QueueExtension.Limits.DEFAULT, null);
    }

    QueueCommand(QueueExtension.Limits limits, Consumer<AgentSession> onChanged) {
        this.limits = limits;
        this.onChanged = onChanged;
    }

    private void changed(AgentSession session) {
        if (onChanged != null) {
            onChanged.accept(session);
        }
    }


    @Override
    public String name() {
        return "queue";
    }


    @Override
    public String description(@Nullable Locale locale) {
        return QueueI18n.get(locale, QueueI18nKeys.COMMAND_QUEUE_DESCRIPTION);
    }

    @Override
    public String[] examples(@Nullable Locale locale) {
        return new String[]{
                QueueI18n.get(locale, QueueI18nKeys.COMMAND_QUEUE_EXAMPLE_MESSAGE),
                "/queue list",
                QueueI18n.get(locale, QueueI18nKeys.COMMAND_QUEUE_EXAMPLE_CANCEL),
                "/queue clear"
        };
    }

    @Override
    public boolean runnableWhenBusy() {
        return true;
    }

    @Override
    public void execute(HarnessEngine engine, CommandInput input, CommandOutput output) {
        String text = input.getArgsJoined().trim();
        if (Assert.isEmpty(text)) {
            printUsage(input, output);
            return;
        }

        AgentSession session = input.getSession();
        if (text.startsWith("--") && (text.length() == 2 || Character.isWhitespace(text.charAt(2)))) {
            enqueue(input, output, session, text.substring(2).trim());
            return;
        }

        String[] words = text.split("\\s+");
        if (words.length == 1 && "list".equals(words[0])) {
            doList(input, output, session);
        } else if (words.length == 1 && "clear".equals(words[0])) {
            int n = QueueStore.clearPending(session);
            changed(session);
            output.println(n > 0
                    ? QueueI18n.fmt(input.getLocale(), QueueI18nKeys.COMMAND_QUEUE_CLEARED, "count", n)
                    : QueueI18n.get(input.getLocale(), QueueI18nKeys.COMMAND_QUEUE_CLEAR_EMPTY));
        } else if (words.length == 2 && "cancel".equals(words[0])) {
            doCancel(input, output, session, words[1]);
        } else {
            enqueue(input, output, session, text);
        }
    }

    private void enqueue(CommandInput input, CommandOutput output, AgentSession session, String text) {
        if (Assert.isEmpty(text)) {
            printUsage(input, output);
            return;
        }
        if (text.length() > limits.maxTextLength) {
            output.println(QueueI18n.fmt(input.getLocale(), QueueI18nKeys.COMMAND_QUEUE_TOO_LONG, "max", limits.maxTextLength));
            return;
        }

        // 与扩展入队（QueueExtension#offer）同一口径：派生「同一轮」的输入，来源、模型、子代理、宿主域属性
        // 与快照（视野、提示词、语言、显式作用域）一并保存，续发时原样还原——命令入队不能比接口入队丢得更多。
        HarnessInput next = input.derive(text);
        if (next.isScopeExplicit() && next.getScope().isDeferrable() == false) {
            // 不能悄悄丢掉不可保存的维度、按更宽的权限续发（与引擎忙时积压同一规则）
            output.println(QueueI18n.get(input.getLocale(), QueueI18nKeys.COMMAND_QUEUE_SCOPE_NOT_DEFERRABLE));
            return;
        }

        int n = QueueStore.enqueue(session, next.getText(), next.getOrigin(), null, next.getSelectedModel(),
                next.getSelectedAgent(), next.getAttrs(), QueueItem.snapshotOf(next),
                limits.maxSize, limits.maxTextLength);
        if (n < 0) {
            output.println(QueueI18n.fmt(input.getLocale(), QueueI18nKeys.COMMAND_QUEUE_FULL, "max", limits.maxSize));
        } else {
            changed(session);
            output.println(QueueI18n.fmt(input.getLocale(), QueueI18nKeys.COMMAND_QUEUE_ENQUEUED, "position", n));
        }
    }

    private void doList(CommandInput input, CommandOutput output, AgentSession session) {
        List<QueueItem> items = QueueStore.view(session);
        if (items.isEmpty()) {
            output.println(QueueI18n.get(input.getLocale(), QueueI18nKeys.COMMAND_QUEUE_LIST_EMPTY));
            return;
        }
        Locale locale = input.getLocale();
        output.println(QueueI18n.fmt(locale, QueueI18nKeys.COMMAND_QUEUE_LIST_TITLE, "count", items.size()));
        int i = 1;
        for (QueueItem item : items) {
            output.println("  " + i++ + ". [" + item.getId() + "] "
                    + (item.isRunning() ? QueueI18n.get(locale, QueueI18nKeys.COMMAND_QUEUE_LIST_RUNNING) + " " : "")
                    + abbreviate(item.getText()));
        }
        output.println(QueueI18n.get(locale, QueueI18nKeys.COMMAND_QUEUE_LIST_FOOTER));
    }

    private void doCancel(CommandInput input, CommandOutput output, AgentSession session, String idOrIndex) {
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
                output.println(QueueI18n.get(input.getLocale(), QueueI18nKeys.COMMAND_QUEUE_CANCEL_RUNNING));
                return;
            }
        }
        if (QueueStore.cancelItem(session, id)) {
            changed(session);
            output.println(QueueI18n.fmt(input.getLocale(), QueueI18nKeys.COMMAND_QUEUE_CANCELLED, "id", id));
        } else {
            output.println(QueueI18n.fmt(input.getLocale(), QueueI18nKeys.COMMAND_QUEUE_CANCEL_NOT_FOUND, "target", idOrIndex));
        }
    }

    private void printUsage(CommandInput input, CommandOutput output) {
        //多行用法：每行单独输出（各语言自行对齐续行）
        for (String line : QueueI18n.get(input.getLocale(), QueueI18nKeys.COMMAND_QUEUE_USAGE).split("\n", -1)) {
            output.println(line);
        }
    }

    private static String abbreviate(String text) {
        String t = text.replace('\n', ' ');
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }
}
