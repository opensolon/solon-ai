package org.noear.solon.ai.harness.extension.steer;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.builtin.Fakes;
import org.noear.solon.ai.harness.command.builtin.HelpCommand;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * steer 对模型 / 用户可见文本的渲染快照
 *
 * <p>中文快照由改造前的代码生成，国际化后必须逐字不变；英文快照经人工审校后生成。</p>
 */
public class SteerRenderGoldenTest {
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
        SteerCommand command = new SteerCommand();

        out.put("steer_command" + suffix, "description: " + command.description(locale)
                + "\nexamples:\n" + String.join("\n", command.examples(locale)));
        out.put("steer_help" + suffix, HelpCommand.formatHelp(Collections.<Command>singletonList(command), "steer", false, locale));

        //用法
        out.put("steer_usage" + suffix, run(command, locale, "", false));
        //已插话
        out.put("steer_applied" + suffix, run(command, locale, "补充要求", true));
        //停用
        SteerExtension disabled = new SteerExtension();
        disabled.setEnabled(false);
        out.put("steer_disabled" + suffix, run(new SteerCommand(disabled), locale, "补充要求", true));
        //邮箱已满
        out.put("steer_box_full" + suffix, runBoxFull(command, locale));
        //没有运行中的任务
        out.put("steer_not_running" + suffix, run(command, locale, "补充要求", false));
        //过长
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < SteerMailbox.MAX_TEXT_LENGTH + 1; i++) {
            longText.append('x');
        }
        out.put("steer_too_long" + suffix, run(command, locale, longText.toString(), true));
        //其它原因（空白内容）
        out.put("steer_failed" + suffix, run(command, locale, "   ", true));

        //注入给模型的消息
        out.put("steer_injected" + suffix, SteerProtocol.toChatMessage(new SteerMessage("s1", "别改测试"), locale).getContent());
        return out;
    }

    static String run(SteerCommand command, Locale locale, String text, boolean running) {
        Fakes.StubContext ctx = Fakes.StubContext.im(text).locale(locale);
        if (running) {
            SteerState.of(ctx.getSession()).open("run-1");
        }
        command.execute(null, ctx.in(Fakes.StubContext.imOrigin()), ctx);
        return ctx.output();
    }

    static String runBoxFull(SteerCommand command, Locale locale) {
        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求").locale(locale);
        AgentSession session = ctx.getSession();
        SteerState.of(session).open("run-1");
        SteerExtension ext = new SteerExtension();
        for (int i = 0; i < SteerMailbox.MAX_BOX_SIZE; i++) {
            ext.steer(session, "seed-" + i);
        }
        command.execute(null, ctx.in(Fakes.StubContext.imOrigin()), ctx);
        return ctx.output();
    }
}
