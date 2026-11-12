package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.builtin.Fakes;
import org.noear.solon.ai.harness.command.builtin.HelpCommand;
import org.noear.solon.ai.harness.extension.goal.validate.BuildPassValidator;
import org.noear.solon.ai.harness.extension.goal.validate.CommandRunner;
import org.noear.solon.ai.harness.extension.goal.validate.GoalValidator;
import org.noear.solon.ai.harness.extension.goal.validate.TestsPassValidator;
import org.noear.solon.ai.harness.extension.goal.validate.ValidationResult;
import org.noear.solon.ai.harness.extension.goal.validate.ValidatorPolicy;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;
import org.noear.solon.ai.talents.cli.CommandResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * goal 对模型 / 用户可见文本的渲染快照
 *
 * <p>中文快照由改造前的代码生成，国际化后必须逐字不变；英文快照经人工审校后生成。</p>
 *
 * <p>语言来源：轮次提示词、轮次结果记录、门面结果说明跟随引擎语言（{@code HarnessEngine.Builder.locale}）；
 * 工具描述跟随请求，工具返回值跟随工具参数 {@code __locale}；命令输出跟随命令上下文；验证说明跟随调用方传入的语言。</p>
 */
public class GoalRenderGoldenTest {
    @TempDir
    Path tmp;

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

    Map<String, String> renderOf(Locale locale, String suffix) throws Throwable {
        Map<String, String> out = new LinkedHashMap<>();
        Path ws = Files.createDirectories(tmp.resolve("ws" + suffix + (locale == null ? "_default" : "_explicit")));

        renderPrompts(out, ws.resolve("prompts"), locale, suffix);
        renderRoundRecords(out, ws.resolve("records"), locale, suffix);
        renderTools(out, ws.resolve("tools"), locale, suffix);
        renderValidators(out, ws.resolve("validators"), locale, suffix);
        renderFacadeAndCommand(out, ws.resolve("facade"), locale, suffix);
        return out;
    }

    // ==================== 轮次提示词（引擎语言） ====================

    private void renderPrompts(Map<String, String> out, Path ws, Locale locale, String suffix) throws Throwable {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        HarnessEngine engine = engine(ws, sessions, locale, null);
        List<String> prompts = new ArrayList<>();
        GoalRuntime rt = new GoalRuntime(engine, BudgetThresholds.defaults(), 3, 3, false);
        rt.addExecutor("t", null, (sid, p) -> {
            prompts.add(p);
            return GoalOutcome.done("ok", true, 0);
        });

        out.put("prompt_full_first" + suffix, round(rt, sessions, prompts, "p1", "修复登录缺陷", gs -> {
        }));

        out.put("prompt_full_rich" + suffix, round(rt, sessions, prompts, "p2", "修复登录缺陷", gs -> {
            gs.setMaxTokens(100_000);
            gs.addTokens(70_000);
            gs.setMaxIterations(10);
            gs.setCurrentIteration(2);
            gs.updateLastExecution(longText(400));
            gs.recordStagnation();
            gs.recordStagnation();
            gs.recordStagnation();
            gs.recordError("SSL", "PKIX path building failed");
        }));

        String[] errorTypes = {"HTTP_4XX", "NETWORK", "TOOL_EXECUTION", "OTHER"};
        for (int i = 0; i < errorTypes.length; i++) {
            final String type = errorTypes[i];
            out.put("prompt_error_" + type.toLowerCase() + suffix, round(rt, sessions, prompts, "pe" + i, "修复登录缺陷", gs -> {
                gs.recordError(type, "OTHER".equals(type) ? null : "boom");
            }));
        }

        out.put("prompt_full_critical" + suffix, round(rt, sessions, prompts, "p3", "修复登录缺陷", gs -> {
            gs.setMaxTokens(100_000);
            gs.addTokens(85_000);
        }));

        // 完整模式下的「紧急」提示：只有紧急阈值低于 70% 时才会出现（默认 85% 时已进入精简模式）
        GoalRuntime urgentRt = new GoalRuntime(engine, new BudgetThresholds(50, 60), 3, 3, false);
        urgentRt.addExecutor("t", null, (sid, p) -> {
            prompts.add(p);
            return GoalOutcome.done("ok", true, 0);
        });
        out.put("prompt_full_urgent" + suffix, round(urgentRt, sessions, prompts, "p3u", "修复登录缺陷", gs -> {
            gs.setMaxTokens(100_000);
            gs.addTokens(65_000);
        }));

        out.put("prompt_compact" + suffix, round(rt, sessions, prompts, "p4", "修复登录缺陷", gs -> {
            gs.setMaxTokens(100_000);
            gs.addTokens(75_000);
            gs.setCurrentIteration(1);
            gs.updateLastExecution(longText(300));
        }));

        out.put("prompt_compact_first" + suffix, round(rt, sessions, prompts, "p5", "修复登录缺陷", gs -> {
            gs.setMaxTokens(100_000);
            gs.addTokens(80_000);
        }));

        out.put("prompt_minimal" + suffix, round(rt, sessions, prompts, "p6", "修复登录缺陷", gs -> {
            gs.setMaxTokens(100_000);
            gs.addTokens(90_000);
            gs.setMaxIterations(5);
        }));

        out.put("prompt_tokens_unlimited" + suffix, round(rt, sessions, prompts, "p7", "修复登录缺陷", gs -> {
            gs.addTokens(1_500);
        }));

        out.put("prompt_budget_limit" + suffix, round(rt, sessions, prompts, "p8", "修复登录缺陷", gs -> {
            gs.setMaxTokens(1_000);
            gs.addTokens(1_000);
        }));

        out.put("prompt_duration_limit" + suffix, round(rt, sessions, prompts, "p9", "修复登录缺陷", gs -> {
            gs.setMaxDurationMs(1);
        }));
    }

    private static String round(GoalRuntime rt, Map<String, AgentSession> sessions, List<String> prompts,
                                String sessionId, String objective, Consumer<Goal> setup) throws Exception {
        AgentSession s = sessions.computeIfAbsent(sessionId, InMemoryAgentSession::of);
        rt.attach(s, objective, 0, 0, 0);
        Goal gs = rt.state(s);
        setup.accept(gs);
        setStartEpochMs(gs, System.currentTimeMillis() - 2 * 3_600_000L);
        rt.commit(s, gs);
        prompts.clear();
        rt.runRound(s);
        return String.join("\n<<<next-turn>>>\n", prompts);
    }

    // ==================== 轮次结果记录（引擎语言） ====================

    private void renderRoundRecords(Map<String, String> out, Path ws, Locale locale, String suffix) throws Throwable {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        HarnessEngine engine = engine(ws, sessions, locale, null);

        // 有执行器但都不认领
        GoalRuntime unclaimed = new GoalRuntime(engine, BudgetThresholds.defaults(), 3, 3, false);
        unclaimed.addExecutor("t", sid -> false, (sid, p) -> GoalOutcome.done("ok", true, 0));
        AgentSession s1 = sessions.computeIfAbsent("r1", InMemoryAgentSession::of);
        unclaimed.attach(s1, "修复登录缺陷", 0, 0, 0);
        unclaimed.runRound(s1);
        out.put("record_unclaimed" + suffix, unclaimed.state(s1).getLastResult());

        // 声明完成但本轮没有工具证据
        GoalRuntime[] holder = new GoalRuntime[1];
        AgentSession s2 = sessions.computeIfAbsent("r2", InMemoryAgentSession::of);
        holder[0] = new GoalRuntime(engine, BudgetThresholds.defaults(), 3, 3, false);
        holder[0].addExecutor("t", null, (sid, p) -> {
            Goal gs = holder[0].state(s2);
            gs.achieve();
            holder[0].commit(s2, gs);
            return GoalOutcome.done("done", false, 0);
        });
        holder[0].attach(s2, "创建 hello.txt 文件", 0, 0, 0);
        holder[0].runRound(s2);
        out.put("record_completion_rejected" + suffix, holder[0].state(s2).getLastResult());
    }

    // ==================== 工具（请求语言 / __locale） ====================

    private void renderTools(Map<String, String> out, Path ws, Locale locale, String suffix) throws Throwable {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        GoalExtension ext = GoalExtension.builder().jobManager(null)
                .validatorEnabled(true)
                .defaultValidators(false)
                .validatorPolicy(ValidatorPolicy.FAIL_CLOSED)
                .build();
        HarnessEngine engine = engine(ws, sessions, null, ext);
        ext.registerValidator(c -> c.contains("alpha"), (c, dir) -> ValidationResult.failed("custom failure"));
        ext.registerValidator(c -> c.contains("beta"), (c, dir) -> {
            throw new IllegalStateException("boom");
        });
        ext.registerValidator(c -> c.contains("gamma"), (c, dir) -> null);
        ext.registerValidator(c -> c.contains("delta"), (c, dir) -> ValidationResult.inconclusive("probe unavailable"));
        GoalTalent talent = ext.getGoalTalent();
        String cwd = ws.toString();

        Prompt prompt = Prompt.of("hi");
        prompt.attrPut("__sessionId", "t0");
        if (locale != null) {
            prompt.locale(locale);
        }
        sid(sessions, "t0");
        ext.create("t0", GoalSpec.of("keep going").runNow(false));
        Collection<FunctionTool> toolList = talent.getTools(prompt);
        Map<String, FunctionTool> tools = new HashMap<>();
        for (FunctionTool t : toolList) {
            tools.put(t.name(), t);
        }
        out.put("goal_tools", GoldenFiles.dumpTools(toolList));

        sid(sessions, "t1");
        out.put("goal_get_no_goal" + suffix, call(tools, locale, "goal_get", "t1", cwd));
        out.put("goal_update_status_missing" + suffix, updateDirect(talent, locale, null, "t1", cwd));
        out.put("goal_update_no_session" + suffix, call(tools, locale, "goal_update", null, cwd, "status", "complete"));
        out.put("goal_update_no_goal" + suffix, call(tools, locale, "goal_update", "t1", cwd, "status", "complete"));

        createGoal(ext, sessions, "t2", "keep going");
        ext.pause("t2");
        out.put("goal_update_not_active" + suffix, call(tools, locale, "goal_update", "t2", cwd, "status", "complete"));

        createGoal(ext, sessions, "t3", "keep going");
        out.put("goal_update_unknown_status" + suffix, call(tools, locale, "goal_update", "t3", cwd, "status", "done"));

        createGoal(ext, sessions, "t4", "create a report file");
        Goal g4 = ext.getGoalRuntime().state(sessions.get("t4"));
        g4.setLastHasToolCalls(false);
        ext.getGoalRuntime().commit(sessions.get("t4"), g4);
        out.put("goal_update_action_evidence" + suffix, call(tools, locale, "goal_update", "t4", cwd, "status", "complete"));

        createGoal(ext, sessions, "t5", "keep going");
        engine.getTodoTalent().writeTodo(cwd, "t5", "- [x] a\n- [ ] b\n- [/] c");
        out.put("goal_update_todo_unfinished" + suffix, call(tools, locale, "goal_update", "t5", cwd, "status", "complete"));

        createGoal(ext, sessions, "t6", "alpha");
        out.put("goal_update_validation_failed" + suffix, call(tools, locale, "goal_update", "t6", cwd, "status", "complete"));
        createGoal(ext, sessions, "t7", "beta");
        out.put("goal_update_validator_error" + suffix, call(tools, locale, "goal_update", "t7", cwd, "status", "complete"));
        createGoal(ext, sessions, "t8", "gamma");
        out.put("goal_update_validator_no_result" + suffix, call(tools, locale, "goal_update", "t8", cwd, "status", "complete"));
        createGoal(ext, sessions, "t9", "delta");
        out.put("goal_update_validator_inconclusive" + suffix, call(tools, locale, "goal_update", "t9", cwd, "status", "complete"));
    }

    private static void createGoal(GoalExtension ext, Map<String, AgentSession> sessions, String sessionId, String objective) {
        sid(sessions, sessionId);
        ext.create(sessionId, GoalSpec.of(objective).runNow(false));
    }

    static String call(Map<String, FunctionTool> tools, Locale locale, String tool, String sessionId, String cwd, Object... kv) throws Throwable {
        Map<String, Object> args = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            args.put((String) kv[i], kv[i + 1]);
        }
        if (sessionId != null) {
            args.put("__sessionId", sessionId);
        }
        args.put("__cwd", cwd);
        if (locale != null) {
            args.put(AiI18n.ATTR_LOCALE, locale.toLanguageTag());
        }
        return normalize(String.valueOf(tools.get(tool).handle(args)));
    }

    /**
     * status 缺省（工具框架会先拦下必填参数，这里直接调方法）：有带 __locale 的重载就用它
     */
    static String updateDirect(GoalTalent talent, Locale locale, String status, String sessionId, String cwd) throws Exception {
        for (Method m : GoalTalent.class.getMethods()) {
            if ("goal_update".equals(m.getName()) && m.getParameterCount() == 4) {
                return normalize(String.valueOf(m.invoke(talent, status, sessionId, cwd,
                        locale == null ? null : locale.toLanguageTag())));
            }
        }
        return normalize(talent.goal_update(status, sessionId, cwd, null).getContent());
    }

    // ==================== 内置验证器（调用方传入的语言） ====================

    private void renderValidators(Map<String, String> out, Path ws, Locale locale, String suffix) throws Throwable {
        Path maven = Files.createDirectories(ws.resolve("maven"));
        Files.write(maven.resolve("pom.xml"), "<project/>".getBytes());
        Path empty = Files.createDirectories(ws.resolve("empty"));

        CommandRunner throwing = (cmd, dir) -> {
            throw new IllegalStateException("boom");
        };
        CommandRunner nothing = (cmd, dir) -> null;
        CommandRunner timedOut = (cmd, dir) -> CommandResult.timedOut("slow");
        CommandRunner exit1 = (cmd, dir) -> CommandResult.completed(1, "[ERROR] something broke");

        out.put("validator_no_workdir" + suffix, validate(new TestsPassValidator(exit1), null, locale));
        out.put("validator_bad_workdir" + suffix, validate(new TestsPassValidator(exit1), "bad\0dir", locale));
        out.put("validator_run_failed" + suffix, validate(new TestsPassValidator(throwing), maven.toString(), locale));
        out.put("validator_no_result" + suffix, validate(new TestsPassValidator(nothing), maven.toString(), locale));
        out.put("validator_timed_out" + suffix, validate(new TestsPassValidator(timedOut), maven.toString(), locale));
        out.put("validator_tests_failed" + suffix, validate(new TestsPassValidator(exit1), maven.toString(), locale));
        out.put("validator_build_failed" + suffix, validate(new BuildPassValidator(exit1), maven.toString(), locale));
        out.put("validator_unknown_project" + suffix, validate(new TestsPassValidator(exit1), empty.toString(), locale));
    }

    /**
     * 有带语言的 validate 重载就用它，否则用两参版本
     */
    static String validate(GoalValidator v, String workDir, Locale locale) throws Exception {
        ValidationResult vr;
        try {
            Method m = v.getClass().getMethod("validate", String.class, String.class, Locale.class);
            vr = (ValidationResult) m.invoke(v, "all tests pass", workDir, locale);
        } catch (NoSuchMethodException e) {
            vr = v.validate("all tests pass", workDir);
        }
        return vr.getKind() + ": " + normalize(vr.getDetail());
    }

    // ==================== 门面（引擎语言）与 /goal 命令（命令上下文语言） ====================

    private void renderFacadeAndCommand(Map<String, String> out, Path ws, Locale locale, String suffix) throws Throwable {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        GoalExtension ext = GoalExtension.builder().jobManager(null).defaultValidators(false).build();
        HarnessEngine engine = engine(ws, sessions, locale, ext);

        createGoal(ext, sessions, "f1", "first goal");
        out.put("facade_create_conflict" + suffix, ext.create("f1", GoalSpec.of("second goal").runNow(false)).getDetail());

        createGoal(ext, sessions, "f2", "finished goal");
        AgentSession f2 = sessions.get("f2");
        Goal g2 = ext.getGoalRuntime().state(f2);
        g2.achieve();
        ext.getGoalRuntime().commit(f2, g2);
        out.put("facade_update_terminal" + suffix, ext.update("f2", GoalPatch.create().maxTokens(5L), null).getDetail());

        GoalCommand command = new GoalCommand(ext);
        out.put("goal_command" + suffix, "description: " + command.description(locale)
                + "\nexamples:\n" + String.join("\n", command.examples(locale)));
        out.put("goal_help" + suffix, HelpCommand.formatHelp(Collections.<Command>singletonList(command), "goal", false, locale));

        Fakes.StubContext bad = new Fakes.StubContext(sessions.get("f1"), Arrays.asList("--max-tokens", "abc", "x")).locale(locale);
        command.execute(engine, bad.in(null), bad);
        out.put("goal_command_bad_flag" + suffix, bad.output());

        Fakes.StubContext conflict = new Fakes.StubContext(sessions.get("f1"), Arrays.asList("another", "goal")).locale(locale);
        command.execute(engine, conflict.in(null), conflict);
        out.put("goal_command_conflict" + suffix, conflict.output());
    }

    // ==================== 辅助 ====================

    private static HarnessEngine engine(Path ws, Map<String, AgentSession> sessions, Locale locale, GoalExtension ext) throws Exception {
        Files.createDirectories(ws);
        HarnessEngine.Builder b = HarnessEngine.of(ws.toString(), ws.resolve("home").toString())
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id));
        if (locale != null) {
            b.locale(locale);
        }
        if (ext != null) {
            b.extensionAdd(ext);
        }
        return b.build();
    }

    private static void sid(Map<String, AgentSession> sessions, String id) {
        sessions.computeIfAbsent(id, InMemoryAgentSession::of);
    }

    private static void setStartEpochMs(Goal gs, long value) throws Exception {
        Field f = Goal.class.getDeclaredField("startEpochMs");
        f.setAccessible(true);
        f.setLong(gs, value);
    }

    private static String longText(int len) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (sb.length() < len) {
            sb.append("step").append(i++).append(' ');
        }
        return sb.substring(0, len);
    }

    static String normalize(String text) {
        if (text == null) {
            return "<null>";
        }
        return text.replace("\0", "<NUL>")
                .replaceAll("(\"taskId\"\\s*:\\s*)\"[^\"]*\"", "$1\"<id>\"");
    }
}
