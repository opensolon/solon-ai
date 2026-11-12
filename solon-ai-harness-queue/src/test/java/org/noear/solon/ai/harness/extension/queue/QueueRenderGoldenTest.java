package org.noear.solon.ai.harness.extension.queue;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.builtin.Fakes;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.command.builtin.HelpCommand;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * queue 对用户可见文本的渲染快照
 *
 * <p>中文快照由改造前的代码生成，国际化后必须逐字不变；英文快照经人工审校后生成。</p>
 */
public class QueueRenderGoldenTest {
    @Test
    public void testZh() throws Throwable {
        GoldenFiles.verify(renderOf(null, "_zh"));
    }

    @Test
    public void testZhExplicit() throws Throwable {
        GoldenFiles.verify(renderOf(Locale.CHINA, "_zh"));
    }

    @Test
    public void testEn() throws Throwable {
        GoldenFiles.verify(renderOf(Locale.ENGLISH, "_en"));
    }

    static Map<String, String> renderOf(Locale locale, String suffix) {
        Map<String, String> out = new LinkedHashMap<>();
        QueueCommand command = new QueueCommand();

        out.put("queue_command" + suffix, "description: " + command.description(locale)
                + "\nexamples:\n" + String.join("\n", command.examples(locale)));
        out.put("queue_help" + suffix, HelpCommand.formatHelp(Collections.<Command>singletonList(command), "queue", false, locale));

        //用法（空参数、只有 "--"）
        out.put("queue_usage" + suffix, run(command, locale, Fakes.newSession(), ""));
        out.put("queue_usage_dashdash" + suffix, run(command, locale, Fakes.newSession(), "--"));

        //过长
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < QueueStore.MAX_TEXT_LENGTH + 1; i++) {
            longText.append('x');
        }
        out.put("queue_too_long" + suffix, run(command, locale, Fakes.newSession(), longText.toString()));

        //入队成功、队列已满
        out.put("queue_enqueued" + suffix, run(command, locale, Fakes.newSession(), "接着更新文档"));
        AgentSession full = Fakes.newSession();
        for (int i = 0; i < QueueStore.MAX_QUEUE_SIZE; i++) {
            QueueStore.enqueue(full, "seed-" + i, InputOrigin.NONE);
        }
        out.put("queue_full" + suffix, run(command, locale, full, "再来一条"));

        //本轮作用域含不可保存的维度：拒绝排队
        HarnessInput scoped = HarnessInput.of(Fakes.newSession(), "/queue 再来一条").locale(locale)
                .scope(InputScope.builder("alice").attr("quota", 1).build());
        Fakes.StubContext scopedCtx = new Fakes.StubContext(scoped.getSession(), Arrays.asList("再来一条"));
        command.execute(null, CommandInput.of(scoped, ParsedCommand.parse("/queue 再来一条"), locale), scopedCtx);
        out.put("queue_scope_not_deferrable" + suffix, scopedCtx.output());

        //列表：空、有执行中的与待执行的
        out.put("queue_list_empty" + suffix, run(command, locale, Fakes.newSession(), "list"));
        AgentSession listed = Fakes.newSession();
        QueueStore.enqueue(listed, "第一条", InputOrigin.NONE, "q1", null, null, null, null,
                QueueStore.MAX_QUEUE_SIZE, QueueStore.MAX_TEXT_LENGTH);
        QueueStore.enqueue(listed, "第二条", InputOrigin.NONE, "q2", null, null, null, null,
                QueueStore.MAX_QUEUE_SIZE, QueueStore.MAX_TEXT_LENGTH);
        QueueStore.poll(listed);
        out.put("queue_list" + suffix, run(command, locale, listed, "list"));

        //取消：执行中、成功、找不到
        out.put("queue_cancel_running" + suffix, run(command, locale, listed, "cancel 1"));
        out.put("queue_cancel_ok" + suffix, run(command, locale, listed, "cancel q2"));
        out.put("queue_cancel_missing" + suffix, run(command, locale, listed, "cancel 9"));

        //清空：有、无
        AgentSession cleared = Fakes.newSession();
        QueueStore.enqueue(cleared, "a", InputOrigin.NONE);
        QueueStore.enqueue(cleared, "b", InputOrigin.NONE);
        out.put("queue_clear" + suffix, run(command, locale, cleared, "clear"));
        out.put("queue_clear_empty" + suffix, run(command, locale, cleared, "clear"));

        //门面入队：队列已满的说明
        QueueExtension ext = QueueExtension.builder().maxSize(1).build();
        AgentSession facade = Fakes.newSession();
        ext.enqueue(HarnessInput.of(facade, "a").locale(locale), null);
        out.put("queue_facade_full" + suffix, ext.enqueue(HarnessInput.of(facade, "b").locale(locale), null).getDetail());
        return out;
    }

    static String run(QueueCommand command, Locale locale, AgentSession session, String args) {
        Fakes.StubContext ctx = new Fakes.StubContext(session,
                args.isEmpty() ? Collections.<String>emptyList() : Arrays.asList(args.split(" "))).locale(locale);
        command.execute(null, ctx.in(InputOrigin.NONE), ctx);
        return ctx.output();
    }
}
