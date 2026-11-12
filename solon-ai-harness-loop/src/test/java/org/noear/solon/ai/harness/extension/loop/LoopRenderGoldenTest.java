package org.noear.solon.ai.harness.extension.loop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.builtin.Fakes;
import org.noear.solon.ai.harness.command.builtin.HelpCommand;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;
import org.noear.solon.ai.mount.source.FileMountSource;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * loop 对模型 / 用户可见文本的渲染快照
 *
 * <p>中文快照由改造前的代码生成，国际化后必须逐字不变；英文快照经人工审校后生成。
 * 时间（serverNow、nextFireTime、错误里的服务器当前时间）、时区与随机 taskId 归一化后再比对。</p>
 */
public class LoopRenderGoldenTest {
    @TempDir
    Path dir;

    @Test
    public void testZh() throws Throwable {
        GoldenFiles.verify(renderOf(null, "_zh", dir));
    }

    @Test
    public void testZhExplicit() throws Throwable {
        GoldenFiles.verify(renderOf(Locale.CHINA, "_zh", dir));
    }

    @Test
    public void testEn() throws Throwable {
        GoldenFiles.verify(renderOf(Locale.ENGLISH, "_en", dir));
    }

    static Map<String, String> renderOf(Locale locale, String suffix, Path dir) throws Throwable {
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

        out.put("loop_command_receipts" + suffix, commandReceipts(locale, dir));
        out.put("loop_command_list" + suffix, listView(locale, dir));
        return out;
    }

    /**
     * /loop 的回执：创建（间隔 / cron / 立即执行 / 已持久化）、列表（等待中 / 执行中 / 已暂停）、
     * 暂停、恢复、删除、清空，以及用法与参数错误。任务 id 归一化。
     */
    static String commandReceipts(Locale locale, Path dir) throws Throwable {
        LoopScheduler scheduler = LoopExtension.builder().jobManager(null).defaultExecutor(false)
                .store(FileMountSource.of(dir, true), "loop")
                .build().scheduler();
        LoopCommand command = new LoopCommand(scheduler);
        AgentSession session = Fakes.newSession();
        String sid = session.getSessionId();
        StringBuilder buf = new StringBuilder();

        run(buf, command, session, locale);                                          // 空列表
        run(buf, command, session, locale, "--every", "2h", "--now", "check", "ci"); // 间隔 + 立即执行
        run(buf, command, session, locale, "--cron", "0 0 0 1 1 ? 2099", "yearly", "report");
        run(buf, command, session, locale, "--every", "abc", "x");
        run(buf, command, session, locale, "--foo", "hello");                         // 未知参数：告警后照常创建
        run(buf, command, session, locale, "--cron");                                 // 缺值：打印用法

        List<LoopTask> tasks = scheduler.list(sid);
        String paused = tasks.get(1).getId();
        run(buf, command, session, locale, "pause", paused);
        LoopTask running = tasks.get(2);
        running.tryStart();
        run(buf, command, session, locale, "list");
        running.finish();

        run(buf, command, session, locale, "resume", paused);
        run(buf, command, session, locale, "pause", "nope");
        run(buf, command, session, locale, "resume", "");
        run(buf, command, session, locale, "remove", paused);
        run(buf, command, session, locale, "remove", "nope");
        run(buf, command, session, locale, "clear");
        return buf.toString();
    }

    private static void run(StringBuilder buf, LoopCommand command, AgentSession session, Locale locale, String... args)
            throws Exception {
        Fakes.StubContext ctx = new Fakes.StubContext(session, Arrays.asList(args)).locale(locale);
        command.execute(null, ctx.in(null), ctx);
        String transcript = "$ /loop " + String.join(" ", args) + "\n" + normalize(ctx.output());
        buf.append(transcript.replaceAll("[0-9a-f]{32}", "<id>")).append("\n\n");
    }

    /**
     * 列表视图：间隔 / cron / 状态 / 上次执行距今。
     *
     * <p>任务按存储格式落盘后重新加载（加载走的就是真实路径），再断言 {@code /loop list} 的实际输出；
     * 渲染逻辑本身不在这里复刻。任务 id 归一化。</p>
     */
    static String listView(Locale locale, Path dir) throws Throwable {
        Instant now = Instant.now();
        Map<String, List<LoopTask>> seeded = new LinkedHashMap<>();
        seeded.put("s9", Arrays.asList(
                executedTask(locale, "check ci", 5, null, now.minusSeconds(30)),
                executedTask(locale, "check deploy", 120, null, now.minusSeconds(300)),
                executedTask(locale, "yearly report", 1, "0 0 0 1 1 ? 2099", now.minusSeconds(7200))));
        new LoopStore(FileMountSource.of(dir, true), "loop", null).save(seeded);

        LoopScheduler scheduler = LoopExtension.builder().jobManager(null).defaultExecutor(false)
                .store(FileMountSource.of(dir, true), "loop")
                .build().scheduler();
        scheduler.load();

        LoopCommand command = new LoopCommand(scheduler);
        Fakes.StubContext ctx = new Fakes.StubContext(InMemoryAgentSession.of("s9"), Arrays.asList("list")).locale(locale);
        command.execute(null, ctx.in(null), ctx);
        return ctx.output().replaceAll("[0-9a-f]{32}", "<id>");
    }

    /** 已执行过一轮的任务（上次执行时刻固定在相对现在的偏移上） */
    private static LoopTask executedTask(Locale locale, String prompt, int intervalMinutes, String cron,
                                          Instant lastExecutedAt) {
        ONode node = new LoopTask(prompt, intervalMinutes, cron, false, locale).toONode();
        node.set("lastExecutedAt", lastExecutedAt.toString());
        node.set("lastResult", "ok");
        return LoopTask.fromONode(node);
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
