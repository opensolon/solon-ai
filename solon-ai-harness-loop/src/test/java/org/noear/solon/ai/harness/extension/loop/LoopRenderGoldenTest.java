package org.noear.solon.ai.harness.extension.loop;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.builtin.Fakes;
import org.noear.solon.ai.harness.command.builtin.HelpCommand;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * loop 对模型 / 用户可见文本的渲染快照
 *
 * <p>中文快照由改造前的代码生成，国际化后必须逐字不变；英文快照经人工审校后生成。
 * 时间（serverNow、nextFireTime、错误里的服务器当前时间）、时区与随机 taskId 归一化后再比对。</p>
 */
public class LoopRenderGoldenTest {
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

    static Map<String, String> renderOf(Locale locale, String suffix) throws Throwable {
        Map<String, String> out = new LinkedHashMap<>();
        LoopScheduler scheduler = LoopExtension.builder().jobManager(null).defaultExecutor(false).build().scheduler();
        LoopTalent talent = new LoopTalent(scheduler);

        Prompt prompt = Prompt.of("hi");
        if (locale != null) {
            prompt.locale(locale);
        }
        Collection<FunctionTool> toolList = talent.getTools(prompt);
        Map<String, FunctionTool> tools = new HashMap<>();
        for (FunctionTool t : toolList) {
            tools.put(t.name(), t);
        }
        out.put("loop_tools", GoldenFiles.dumpTools(toolList));

        // ---- loop_add ----
        out.put("loop_add_no_session" + suffix, call(tools, locale, "loop_add", null, "prompt", "x"));
        out.put("loop_add_prompt_empty" + suffix, call(tools, locale, "loop_add", "s1", "prompt", ""));
        out.put("loop_add_at_format" + suffix, call(tools, locale, "loop_add", "s1", "prompt", "x", "at", "tomorrow"));
        out.put("loop_add_at_past" + suffix, call(tools, locale, "loop_add", "s1", "prompt", "x", "at", "2000-01-01T00:00"));
        out.put("loop_add_one_shot_no_schedule" + suffix, call(tools, locale, "loop_add", "s1", "prompt", "x", "oneShot", true));
        out.put("loop_add_one_shot_run_now" + suffix, call(tools, locale, "loop_add", "s1", "prompt", "x",
                "cron", "0 0 0 1 1 ? 2099", "oneShot", true, "runNow", true));
        out.put("loop_add_cron_bad" + suffix, call(tools, locale, "loop_add", "s1", "prompt", "x", "cron", "bad"));
        out.put("loop_add_cron_expired" + suffix, call(tools, locale, "loop_add", "s1", "prompt", "x", "cron", "0 0 0 1 1 ? 2000"));
        out.put("loop_add_interval_range" + suffix, call(tools, locale, "loop_add", "s1", "prompt", "x", "intervalMinutes", 0));
        out.put("loop_add_ok" + suffix, call(tools, locale, "loop_add", "s1", "prompt", "x", "intervalMinutes", 5));
        out.put("loop_add_one_shot_ok" + suffix, call(tools, locale, "loop_add", "s1", "prompt", "y", "at", "2099-01-01T08:00"));

        // ---- loop_list ----
        out.put("loop_list_no_session" + suffix, call(tools, locale, "loop_list", null));

        // ---- loop_control ----
        String id = scheduler.list("s1").get(0).getId();
        out.put("loop_control_bad_action" + suffix, call(tools, locale, "loop_control", "s1", "action", "kill", "taskId", id));
        for (String action : new String[]{"stop", "pause", "resume", "trigger"}) {
            out.put("loop_control_" + action + "_no_session" + suffix, call(tools, locale, "loop_control", null, "action", action, "taskId", id));
            out.put("loop_control_" + action + "_no_id" + suffix, call(tools, locale, "loop_control", "s1", "action", action, "taskId", ""));
            out.put("loop_control_" + action + "_missing" + suffix, call(tools, locale, "loop_control", "s1", "action", action, "taskId", "nope"));
        }
        out.put("loop_control_pause" + suffix, call(tools, locale, "loop_control", "s1", "action", "pause", "taskId", id));
        out.put("loop_control_trigger_paused" + suffix, call(tools, locale, "loop_control", "s1", "action", "trigger", "taskId", id));
        out.put("loop_control_resume" + suffix, call(tools, locale, "loop_control", "s1", "action", "resume", "taskId", id));
        out.put("loop_control_trigger" + suffix, call(tools, locale, "loop_control", "s1", "action", "trigger", "taskId", id));
        out.put("loop_control_stop" + suffix, call(tools, locale, "loop_control", "s1", "action", "stop", "taskId", id));

        // ---- 规格校验（宿主门面的结果说明） ----
        out.put("loop_spec_prompt_empty" + suffix, specError(locale, LoopSpec.of(" ")));
        out.put("loop_spec_at_past" + suffix, specError(locale, LoopSpec.of("x").at(LocalDateTime.of(2000, 1, 1, 0, 0))));
        out.put("loop_spec_one_shot_no_schedule" + suffix, specError(locale, LoopSpec.of("x").oneShot(true)));
        out.put("loop_spec_one_shot_run_now" + suffix, specError(locale, LoopSpec.of("x").cron("0 0 0 1 1 ? 2099").oneShot(true).runNow(true)));
        out.put("loop_spec_interval_range" + suffix, specError(locale, LoopSpec.of("x").everyMinutes(99999)));
        try {
            scheduler.schedule("", LoopSpec.of("x"));
            out.put("loop_scheduler_session_empty" + suffix, "<no error>");
        } catch (IllegalArgumentException e) {
            out.put("loop_scheduler_session_empty" + suffix, messageOf(e, locale));
        }

        // ---- /loop 命令 ----
        LoopCommand command = new LoopCommand(scheduler);
        out.put("loop_command" + suffix, "description: " + command.description(locale)
                + "\nexamples:\n" + String.join("\n", command.examples(locale)));
        out.put("loop_help" + suffix, HelpCommand.formatHelp(Collections.<Command>singletonList(command), "loop", false, locale));
        Fakes.StubContext ctx = new Fakes.StubContext(Fakes.newSession(), Arrays.asList("--cron", "bad", "hello")).locale(locale);
        command.execute(null, ctx.in(null), ctx);
        out.put("loop_command_failed" + suffix, normalize(ctx.output()));
        return out;
    }

    static String call(Map<String, FunctionTool> tools, Locale locale, String tool, String sessionId, Object... kv) throws Throwable {
        Map<String, Object> args = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            args.put((String) kv[i], kv[i + 1]);
        }
        if (sessionId != null) {
            args.put("__sessionId", sessionId);
        }
        if (locale != null) {
            args.put(AiI18n.ATTR_LOCALE, locale.toLanguageTag());
        }
        return normalize(String.valueOf(tools.get(tool).handle(args)));
    }

    static String specError(Locale locale, LoopSpec spec) throws Exception {
        try {
            spec.toTask();
            return "<no error>";
        } catch (IllegalArgumentException e) {
            return messageOf(e, locale);
        }
    }

    /**
     * 异常说明：支持按语言渲染的用 getMessage(Locale)，否则用 getMessage()
     */
    static String messageOf(Exception e, Locale locale) throws Exception {
        try {
            Method m = e.getClass().getMethod("getMessage", Locale.class);
            return normalize(String.valueOf(m.invoke(e, locale)));
        } catch (NoSuchMethodException ignored) {
            return normalize(e.getMessage());
        }
    }

    /** 文案里的示例时刻（不归一化） */
    static final String EXAMPLE_AT = "2026-09-05T18:00";

    static String normalize(String text) {
        return text.replace(EXAMPLE_AT, "<example-at>")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?", "<time>")
                .replace("<example-at>", EXAMPLE_AT)
                .replaceAll("(\"timezone\"\\s*:\\s*)\"[^\"]*\"", "$1\"<tz>\"")
                .replaceAll("(\"taskId\"\\s*:\\s*)\"[^\"]*\"", "$1\"<id>\"");
    }
}
