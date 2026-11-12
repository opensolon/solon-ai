package features.ai.harness.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.InterruptPlan;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.agent.GenerateTalent;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.command.AbsCommand;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.command.CommandOutput;
import org.noear.solon.ai.harness.command.CommandDispatcher;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.command.builtin.ClearCommand;
import org.noear.solon.ai.harness.command.builtin.ContinueCommand;
import org.noear.solon.ai.harness.command.builtin.HelpCommand;
import org.noear.solon.ai.harness.command.builtin.InterruptCommand;
import org.noear.solon.ai.harness.command.builtin.ModelCommand;
import org.noear.solon.ai.harness.command.builtin.RerunCommand;
import org.noear.solon.ai.harness.command.builtin.RewindCommand;
import org.noear.solon.ai.harness.hitl.BashToolStrategy;
import org.noear.solon.ai.harness.hitl.WebToolStrategy;
import org.noear.solon.ai.harness.hitl.WriteToolStrategy;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputAttachments;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.permission.PermissionBehavior;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;
import org.noear.solon.lang.Nullable;
import reactor.core.Disposable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * harness-core 对模型 / 用户可见文本的渲染快照
 *
 * <p>中文快照由改造前的代码生成，国际化后必须逐字不变；英文快照经人工审校后生成。</p>
 */
public class HarnessRenderGoldenTest {
    @TempDir
    Path workspace;

    /**
     * 默认语言（未携带任何语言信息）
     */
    @Test
    public void testZh() throws Throwable {
        GoldenFiles.verify(renderOf(workspace, null, "_zh"));
    }

    /**
     * 请求语言为中文时与默认一致
     */
    @Test
    public void testZhExplicit() throws Throwable {
        GoldenFiles.verify(renderOf(workspace, Locale.CHINA, "_zh"));
    }

    @Test
    public void testEn() throws Throwable {
        GoldenFiles.verify(renderOf(workspace, Locale.ENGLISH, "_en"));
    }

    /**
     * @param locale 请求语言（null 表示不携带，用默认语言）
     */
    static Map<String, String> renderOf(Path ws, Locale locale, String suffix) throws Throwable {
        Map<String, String> out = new LinkedHashMap<>();
        renderTask(out, ws, locale, suffix);
        renderGenerate(out, ws, locale, suffix);
        renderHitl(out, locale, suffix);
        renderCommands(out, ws, locale, suffix);
        renderInput(out, ws, locale, suffix);
        renderAgents(out, ws, locale, suffix);
        return out;
    }

    static String tag(Locale locale) {
        return locale == null ? null : locale.toLanguageTag();
    }

    static Prompt prompt(Locale locale) {
        Prompt prompt = Prompt.of("hi");
        if (locale != null) {
            prompt.locale(locale);
        }
        return prompt;
    }

    // ---------------- TaskTalent ----------------

    static void renderTask(Map<String, String> out, Path ws, Locale locale, String suffix) throws Throwable {
        HarnessEngine engine = HarnessFixtures.engine(ws.resolve("task"));
        TaskTalent talent = engine.getTaskTalent();
        String cwd = ws.toString();
        String tag = tag(locale);

        if (locale == null) {
            //description(null) 没有请求上下文：默认语言
            out.put("task_description" + suffix, talent.description(null));
        }
        out.put("task_instruction" + suffix, talent.getInstruction(prompt(locale)));
        out.put("task_tools", GoldenFiles.dumpTools(talent.getTools(prompt(locale))));

        //会话级子代理清单
        AgentSession s1 = engine.getSession("s1");
        engine.getGenerateTalent().generate("helper", "helper desc", "sp", null, null, false, cwd, "s1",null);
        Prompt prompt = prompt(locale);
        prompt.attrs().put(ChatSession.ATTR_SESSIONID, "s1");
        out.put("task_instruction_session" + suffix, talent.getInstruction(prompt));

        //嵌套委派
        ThreadLocal<Boolean> depth = (ThreadLocal<Boolean>) HarnessFixtures.getStatic(TaskTalent.class, "TASK_DEPTH");
        depth.set(Boolean.TRUE);
        try {
            out.put("task_nested" + suffix, talent.task(HarnessFixtures.taskOp("explore", 1), cwd, "s1", tag).getContent());
            out.put("multitask_nested" + suffix, talent.multitask(Collections.singletonList(HarnessFixtures.taskOp("explore", 1)), cwd, "s1", tag).getContent());
        } finally {
            depth.remove();
        }

        out.put("multitask_empty" + suffix, talent.multitask(Collections.emptyList(), cwd, "s1", tag).getContent());

        //无效任务：未知子代理 + null 条目
        HarnessFixtures.bindTrace(s1, HarnessFixtures.trace(locale, false));
        List<TaskTalent.MultiTaskOp> bad = new ArrayList<>();
        bad.add(HarnessFixtures.taskOp("no-such-agent", 1));
        bad.add(null);
        out.put("multitask_invalid" + suffix, talent.multitask(bad, cwd, "s1", tag).getContent());

        //聚合：失败 + 三种建议
        ReActTrace trace = HarnessFixtures.trace(locale, false);
        String[] advices = {"rate", "upstream", "timeout"};
        for (String advice : advices) {
            AtomicBoolean rate = new AtomicBoolean("rate".equals(advice));
            AtomicBoolean timeout = new AtomicBoolean("timeout".equals(advice));
            AtomicBoolean upstream = new AtomicBoolean("upstream".equals(advice));
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("boom"));
            List<CompletableFuture<String>> futures = new ArrayList<>();
            futures.add(CompletableFuture.completedFuture("ok"));
            futures.add(failed);
            out.put("aggregate_" + advice + suffix, HarnessFixtures.aggregate(talent, futures,
                    HarnessFixtures.multitaskContext(trace, 2, rate, timeout, upstream)).getContent());
        }

        //聚合：主流程已取消
        ReActTrace cancelled = HarnessFixtures.trace(locale, true);
        List<CompletableFuture<String>> pending = new ArrayList<>();
        pending.add(new CompletableFuture<>());
        pending.add(new CompletableFuture<>());
        out.put("aggregate_cancelled" + suffix, HarnessFixtures.aggregate(talent, pending,
                HarnessFixtures.multitaskContext(cancelled, 2, new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean())).getContent());

        //聚合：中断
        Thread.currentThread().interrupt();
        try {
            out.put("aggregate_interrupted" + suffix, HarnessFixtures.aggregate(talent, pending,
                    HarnessFixtures.multitaskContext(trace, 2, new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean())).getContent());
        } finally {
            Thread.interrupted();
        }

        //未启动
        Class<?>[] opType = {TaskTalent.MultiTaskOp.class, Locale.class};
        out.put("task_not_started" + suffix, (String) HarnessFixtures.invoke(talent, "notStarted", opType, HarnessFixtures.taskOp("explore", 3), locale));
        Thread.currentThread().interrupt();
        try {
            out.put("task_not_started_interrupted" + suffix, (String) HarnessFixtures.invoke(talent, "notStarted", opType, HarnessFixtures.taskOp("explore", 3), locale));
        } finally {
            Thread.interrupted();
        }

        //超长结果：落盘 / 纯截断
        String longText = HarnessFixtures.repeat('x', 20005);
        Class<?>[] respType = {TaskTalent.MultiTaskOp.class, String.class, boolean.class, String.class, boolean.class,
                String.class, Locale.class};
        out.put("task_result_spilled" + suffix, (String) HarnessFixtures.invoke(talent, "formatTaskResp", respType,
                HarnessFixtures.taskOp("explore", 1), "t1", true, longText, false, cwd, locale));
        out.put("task_result_truncated" + suffix, (String) HarnessFixtures.invoke(new TaskTalent(null), "formatTaskResp", respType,
                HarnessFixtures.taskOp("explore", 1), "t1", true, longText, false, cwd, locale));
    }

    // ---------------- GenerateTalent ----------------

    static void renderGenerate(Map<String, String> out, Path ws, Locale locale, String suffix) throws Throwable {
        HarnessEngine engine = HarnessFixtures.engine(ws.resolve("gen"));
        GenerateTalent talent = engine.getGenerateTalent();
        String cwd = ws.toString();
        String tag = tag(locale);
        engine.getSession("s1");

        out.put("generate_tools", GoldenFiles.dumpTools(talent.getTools(prompt(locale))));
        out.put("generate_bad_name" + suffix, talent.generate("bad name", "d", "sp", null, null, false, cwd, "s1", tag).getContent());
        out.put("generate_reserved" + suffix, talent.generate(AgentDefinition.AGENT_GENERAL, "d", "sp", null, null, false, cwd, "s1", tag).getContent());
        out.put("generate_no_session" + suffix, talent.generate("a1", "d", "sp", null, null, false, cwd, "no-such-session", tag).getContent());
        out.put("generate_session_ok" + suffix, talent.generate("a1", "d1", "sp", null, null, false, cwd, "s1", tag).getContent());
        out.put("generate_session_dup" + suffix, talent.generate("a1", "d1", "sp", null, null, false, cwd, "s1", tag).getContent());
        out.put("generate_global_ok" + suffix, talent.generate("g1", "dg", "sp", null, null, true, cwd, "s1", tag).getContent());
        out.put("generate_global_dup" + suffix, talent.generate("g1", "dg", "sp", null, null, true, cwd, "s1", tag).getContent());
        out.put("generate_session_vs_global" + suffix, talent.generate("g1", "dg", "sp", null, null, false, cwd, "s1", tag).getContent());

        String agentPath = engine.getHarnessAgents() + "g2.md";
        engine.getWorkspaceSource().writeString(agentPath, "x");
        out.put("generate_file_exists" + suffix, talent.generate("g2", "dg", "sp", null, null, true, cwd, "s1", tag).getContent()
                .replace(agentPath, "<agentPath>"));

        HarnessEngine denied = HarnessFixtures.builder(ws.resolve("gen2"))
                .agentPersistPolicy((s, d) -> false)
                .build();
        out.put("generate_global_denied" + suffix, denied.getGenerateTalent().generate("g3", "dg", "sp", null, null, true, cwd, "s1", tag).getContent());
    }

    // ---------------- HITL ----------------

    static Map<String, Object> args(String key, String value) {
        Map<String, Object> args = new HashMap<>();
        args.put(key, value);
        return args;
    }

    static void renderHitl(Map<String, String> out, Locale locale, String suffix) throws Throwable {
        ReActTrace trace = HarnessFixtures.trace(locale, false);
        BashToolStrategy bash = new BashToolStrategy();
        out.put("hitl_bash_injection" + suffix, bash.evaluate(trace, args("command", "echo `id`")));
        out.put("hitl_bash_privileged" + suffix, bash.evaluate(trace, args("command", " sudo ls ")));
        out.put("hitl_bash_traversal" + suffix, bash.evaluate(trace, args("command", "cat ../a.txt")));
        out.put("hitl_bash_sensitive" + suffix, bash.evaluate(trace, args("command", "cat /etc/hosts")));
        out.put("hitl_bash_incomplete" + suffix, bash.evaluate(trace, args("command", "rm -rf build &&")));
        out.put("hitl_bash_deny" + suffix, new BashToolStrategy().defaultDecision(PermissionBehavior.DENY)
                .evaluate(trace, args("command", "rm -rf build")));
        out.put("hitl_bash_ask" + suffix, new BashToolStrategy().defaultDecision(PermissionBehavior.ASK)
                .evaluate(trace, args("command", "rm -rf build")));

        WriteToolStrategy write = new WriteToolStrategy("write");
        out.put("hitl_write_traversal" + suffix, write.evaluate(trace, args("file_path", "../a.txt")));
        out.put("hitl_write_sensitive" + suffix, write.evaluate(trace, args("file_path", "/etc/hosts")));
        out.put("hitl_write_deny" + suffix, new WriteToolStrategy("write").defaultDecision(PermissionBehavior.DENY)
                .evaluate(trace, args("file_path", "a.txt")));
        out.put("hitl_write_ask" + suffix, new WriteToolStrategy("write").defaultDecision(PermissionBehavior.ASK)
                .evaluate(trace, args("file_path", "a.txt")));

        WebToolStrategy web = new WebToolStrategy("webfetch").riskyHostname("evil.example.com");
        out.put("hitl_web_risky" + suffix, web.evaluate(trace, args("url", "https://evil.example.com/x")));
        out.put("hitl_web_deny" + suffix, new WebToolStrategy("webfetch").defaultDecision(PermissionBehavior.DENY)
                .evaluate(trace, args("url", "https://a.example.com/x")));
        out.put("hitl_web_ask" + suffix, new WebToolStrategy("webfetch").defaultDecision(PermissionBehavior.ASK)
                .evaluate(trace, args("url", "https://a.example.com/x")));
    }

    // ---------------- 命令 ----------------

    /**
     * 自定义命令（只有不分语言的 description()）
     */
    static Command named(String name, String description, boolean terminalOnly) {
        return new AbsCommand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description(@Nullable Locale locale) {
                return description;
            }

            @Override
            public String[] examples(@Nullable Locale locale) {
                return new String[0];
            }

            @Override
            public boolean terminalOnly() {
                return terminalOnly;
            }

            @Override
            public void execute(HarnessEngine engine, CommandInput input, CommandOutput output) {
            }
        };
    }

    static void renderCommands(Map<String, String> out, Path ws, Locale locale, String suffix) throws Throwable {
        List<Command> builtins = Arrays.asList(new HelpCommand(), new ClearCommand(), new ContinueCommand(),
                new InterruptCommand(), new ModelCommand(), new RerunCommand(), new RewindCommand());

        StringBuilder desc = new StringBuilder();
        for (Command c : builtins) {
            desc.append(c.name()).append(": ").append(c.description(locale)).append("\n");
        }
        out.put("command_descriptions" + suffix, desc.toString());

        List<Command> list = new ArrayList<>(builtins);
        list.add(named("exit", "退出", true));
        list.add(named("nodesc", null, false));
        out.put("help_list" + suffix, HelpCommand.formatHelp(list, null, false, locale));
        out.put("help_topic" + suffix, HelpCommand.formatHelp(list, "rewind", false, locale));
        out.put("help_topic_terminal_only" + suffix, HelpCommand.formatHelp(list, "exit", false, locale));
        out.put("help_topic_missing" + suffix, HelpCommand.formatHelp(list, "nope", false, locale));

        HarnessEngine engine = HarnessFixtures.engine(ws.resolve("cmd"));
        AgentSession session = engine.getSession("c1");
        //取消消息按会话当前轨迹的语言写入
        HarnessFixtures.bindTrace(session, HarnessFixtures.trace(locale, false));

        HarnessFixtures.Cmd ctx = HarnessFixtures.ctx(locale, session, "/help", "help");
        new HelpCommand().execute(engine, ctx.in, ctx.out);
        out.put("help_execute" + suffix, ctx.text());

        ctx = HarnessFixtures.ctx(locale, session, "/interrupt", "interrupt");
        new InterruptCommand().execute(engine, ctx.in, ctx.out);
        out.put("interrupt_idle" + suffix, ctx.text());

        SessionRuns.attach(session, new NoopDisposable());
        ctx = HarnessFixtures.ctx(locale, session, "/interrupt", "interrupt");
        new InterruptCommand().execute(engine, ctx.in, ctx.out);
        out.put("interrupt_running" + suffix, ctx.text());
        out.put("cancel_message" + suffix, session.getMessages().get(session.getMessages().size() - 1).getContent());
        out.put("cancel_text" + suffix, InterruptPlan.cancelText(locale));

        ctx = HarnessFixtures.ctx(locale, engine.getSession("c2"), "/continue", "continue");
        new ContinueCommand().execute(engine, ctx.in, ctx.out);
        out.put("continue_none" + suffix, ctx.text());

        ctx = HarnessFixtures.ctx(locale, engine.getSession("c3"), "/rewind 0", "rewind", "0");
        new RewindCommand().execute(engine, ctx.in, ctx.out);
        out.put("rewind_invalid" + suffix, ctx.text());

        ctx = HarnessFixtures.ctx(locale, engine.getSession("c3"), "/rewind 2", "rewind", "2");
        new RewindCommand().execute(engine, ctx.in, ctx.out);
        out.put("rewind_ok" + suffix, ctx.text());

        //分发器：渠道不可用 / 已停用
        HarnessEngine engine2 = HarnessFixtures.builder(ws.resolve("cmd2"))
                .commandAdd(named("exit", "退出", true))
                .commandAdd(named("off", "停用的命令", false))
                .build();
        AgentSession s = engine2.getSession("d1");
        InputOutcome unavailable = CommandDispatcher.dispatch(engine2,
                HarnessInput.of(s, "/exit").locale(locale), ParsedCommand.parse("/exit", "/"));
        out.put("dispatch_unavailable" + suffix, unavailable.getCommandOutput());

        engine2.getCommandRegistry().get("off").setEnabled(false);
        InputOutcome disabled = CommandDispatcher.dispatch(engine2,
                HarnessInput.of(s, "/off").locale(locale), ParsedCommand.parse("/off", "/"));
        out.put("dispatch_disabled" + suffix, disabled.getCommandOutput());

        //分发器：需要会话的命令在无会话输入上
        engine2.getCommandRegistry().get("off").setEnabled(true);
        InputOutcome noSession = CommandDispatcher.dispatch(engine2,
                HarnessInput.of(null, "/off").locale(locale), ParsedCommand.parse("/off", "/"));
        out.put("dispatch_session_required" + suffix, noSession.getCommandOutput());
    }

    static final class NoopDisposable implements Disposable {
        @Override
        public void dispose() {
        }

        @Override
        public boolean isDisposed() {
            return false;
        }
    }

    // ---------------- 输入 ----------------

    static ImageBlock image(String path) {
        return ImageBlock.ofBase64(Base64.getEncoder().encodeToString(new byte[]{1}), "image/png").filePath(path);
    }

    static void renderInput(Map<String, String> out, Path ws, Locale locale, String suffix) throws Throwable {
        out.put("attachment_prefix" + suffix, InputAttachments.of().reference("a.txt").reference("b.pdf").withFilePrefix("看下", locale));
        out.put("attachment_prefix_empty" + suffix, InputAttachments.of().reference("a.txt").withFilePrefix("", locale));

        HarnessEngine engine = HarnessFixtures.engine(ws.resolve("in"));

        AgentSession s1 = engine.getSession("i1");
        out.put("input_one_image" + suffix, engine.handle(HarnessInput.of(s1, "").locale(locale).media(image("a.png")))
                .getPrompt().getUserContent());
        AgentSession s2 = engine.getSession("i2");
        out.put("input_images" + suffix, engine.handle(HarnessInput.of(s2, "").locale(locale).media(image("a.png")).media(image("b.png")))
                .getPrompt().getUserContent());

        //忙碌
        AgentSession busy = engine.getSession("i3");
        SessionRuns.attach(busy, new NoopDisposable());
        out.put("input_busy" + suffix, engine.handle(HarnessInput.of(busy, "hi").locale(locale)).getRejectDetail());

        //审批未决
        AgentSession hitl = engine.getSession("i4");
        hitl.getContext().put(HITL.LAST_INTERVENED, new HITLTask("c1", "bash", new HashMap<>(), null));
        out.put("input_hitl_pending" + suffix, engine.handle(HarnessInput.of(hitl, "hi").locale(locale)).getRejectDetail());
    }

    // ---------------- 内置子代理 ----------------

    static void renderAgents(Map<String, String> out, Path ws, Locale locale, String suffix) throws Throwable {
        HarnessEngine engine = HarnessFixtures.engine(ws.resolve("ag"));
        List<AgentDefinition> agents = new ArrayList<>(engine.getAgentManager().getAgents());
        //隐藏的内置代理不在列表里，单独取
        agents.add(engine.getAgentManager().getAgent("git-summary"));
        for (AgentDefinition agent0 : agents) {
            //委派时按父代理语言取内置子代理的语言版本
            AgentDefinition agent = engine.localizeAgent(agent0, locale);
            out.put("agent_" + agent.getName().replace('-', '_') + suffix,
                    "description: " + agent.getDescription() + "\n--- systemPrompt ---\n" + agent.getSystemPrompt());
        }
    }
}
