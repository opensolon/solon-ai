package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.builtin.Fakes;
import org.noear.solon.ai.harness.extension.goal.i18n.GoalI18n;
import org.noear.solon.ai.harness.extension.goal.i18n.GoalI18nKeys;
import org.noear.solon.ai.harness.input.HarnessInput;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目标的语言：创建时记下（/goal 取命令上下文的语言），随快照持久化；
 * 每轮提示词、写入 lastResult 的系统说明、交给执行器（进而写进本轮输入）的语言都取它，没有时用引擎语言。
 *
 * <p>回归：Web 界面选英文后提交 {@code /goal ...}，引擎未配置语言（缺省中文），后台轮次却全是中文。</p>
 */
public class GoalLocaleTest {
    @TempDir
    Path workspace;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    private HarnessEngine engine(Locale engineLocale, AbsHarnessExtension... extensions) {
        HarnessEngine.Builder b = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .locale(engineLocale);
        for (AbsHarnessExtension e : extensions) {
            b.extensionAdd(e);
        }
        return b.build();
    }

    private static GoalExtension goalExt() {
        return GoalExtension.builder().jobManager(null).defaultValidators(false).build();
    }

    private AgentSession session(String id) {
        return sessions.computeIfAbsent(id, InMemoryAgentSession::of);
    }

    @Test
    public void commandRecordsTheContextLocale() throws Exception {
        GoalExtension ext = goalExt();
        HarnessEngine engine = engine(null, ext);

        Fakes.StubContext en = new Fakes.StubContext(session("s1"),
                Arrays.asList("Prepare", "a", "business", "plan")).locale(Locale.ENGLISH);
        new GoalCommand(ext).execute(engine, en.in(null), en);
        assertEquals(Locale.ENGLISH, ext.get("s1").getLocale(), en.output());

        Fakes.StubContext zh = new Fakes.StubContext(session("s2"),
                Arrays.asList("修复登录缺陷")).locale(Locale.CHINA);
        new GoalCommand(ext).execute(engine, zh.in(null), zh);
        assertEquals(Locale.CHINA, ext.get("s2").getLocale(), zh.output());
    }

    @Test
    public void roundPromptAndExecutorFollowTheGoalLocale() {
        GoalExtension ext = goalExt();
        engine(null, ext); // 引擎未配置语言：缺省中文
        List<String> prompts = new CopyOnWriteArrayList<>();
        List<Locale> locales = new CopyOnWriteArrayList<>();
        ext.addExecutor("t", null, (sid, prompt, locale) -> {
            prompts.add(prompt);
            locales.add(locale);
            return GoalOutcome.done("ok", true, 0);
        });

        AgentSession s = session("s1");
        assertTrue(ext.create("s1", GoalSpec.of("Prepare a business plan").locale(Locale.ENGLISH).runNow(false)).isOk());
        ext.getGoalRuntime().runRound(s);

        assertEquals(Arrays.asList(Locale.ENGLISH), locales, "执行器拿到目标的语言");
        String prompt = prompts.get(0);
        assertTrue(prompt.contains(GoalI18n.fmt(Locale.ENGLISH, GoalI18nKeys.GOAL_PROMPT_FULL_CONTINUATION,
                "objective", "Prepare a business plan")), prompt);
        assertFalse(prompt.contains("目标延续"), prompt);
    }

    @Test
    public void goalWithoutLocaleFollowsTheEngine() {
        GoalExtension ext = goalExt();
        engine(Locale.ENGLISH, ext);
        List<Locale> locales = new CopyOnWriteArrayList<>();
        ext.addExecutor("t", null, (sid, prompt, locale) -> {
            locales.add(locale);
            return GoalOutcome.done("ok", true, 0);
        });

        AgentSession s = session("s1");
        assertTrue(ext.create("s1", GoalSpec.of("Explain the architecture").runNow(false)).isOk());
        assertNull(ext.get("s1").getLocale(), "未指定：不冻结引擎语言");
        ext.getGoalRuntime().runRound(s);
        assertEquals(Arrays.asList(Locale.ENGLISH), locales);
    }

    @Test
    public void roundRecordsFollowTheGoalLocale() {
        HarnessEngine engine = engine(null);
        GoalRuntime rt = new GoalRuntime(engine, BudgetThresholds.defaults(), 3, 3, false);
        rt.addExecutor("t", sid -> false, (sid, p, locale) -> GoalOutcome.done("ok", true, 0));

        AgentSession s = session("r1");
        rt.attach(s, "Fix the login bug", null, 0, 0, 0, Locale.ENGLISH);
        rt.runRound(s);
        assertEquals("error [no-executor]: " + GoalI18n.get(Locale.ENGLISH, GoalI18nKeys.GOAL_ROUND_UNCLAIMED),
                rt.state(s).getLastResult());
    }

    @Test
    public void defaultExecutorPutsTheLocaleOnTheInput() {
        List<Locale> seen = new CopyOnWriteArrayList<>();
        AbsHarnessExtension probe = new AbsHarnessExtension() {
            @Override
            public void onInput(HarnessEngine engine, HarnessInput input) {
                if (input.isProgram()) {
                    seen.add(input.getLocale());
                    throw new IllegalStateException("probe: stop before the model call");
                }
            }
        };
        GoalExtension ext = goalExt();
        engine(null, probe, ext);

        AgentSession s = session("s1");
        assertTrue(ext.create("s1", GoalSpec.of("Prepare a business plan").locale(Locale.ENGLISH).runNow(false)).isOk());
        ext.getGoalRuntime().runRound(s);
        assertEquals(Arrays.asList(Locale.ENGLISH), seen, "缺省执行器把语言写进本轮输入");
    }

    @Test
    public void facadeConflictUsesTheRequesterLocale() {
        GoalExtension ext = goalExt();
        engine(null, ext);
        session("s1");
        assertTrue(ext.create("s1", GoalSpec.of("first").runNow(false)).isOk());
        assertEquals(GoalI18n.get(Locale.ENGLISH, GoalI18nKeys.GOAL_ERROR_ACTIVE_EXISTS),
                ext.create("s1", GoalSpec.of("second").locale(Locale.ENGLISH).runNow(false)).getDetail());
    }

    @Test
    public void attachOverloadRecordsTheLocale() {
        HarnessEngine engine = engine(null);
        GoalRuntime rt = new GoalRuntime(engine, BudgetThresholds.defaults(), 3, 3, false);
        AgentSession s = session("a1");

        rt.attach(s, "Prepare a business plan", "Prepare a business plan", 0, 0, 0, Locale.ENGLISH);
        assertEquals(Locale.ENGLISH, rt.state(s).getLocale());
    }

    @Test
    public void attachConflictUsesTheGivenLocale() {
        HarnessEngine engine = engine(null);
        GoalRuntime rt = new GoalRuntime(engine, BudgetThresholds.defaults(), 3, 3, false);
        AgentSession s = session("a2");
        rt.attach(s, "first", null, 0, 0, 0, null);

        GoalActiveException ex = assertThrows(GoalActiveException.class,
                () -> rt.attach(s, "second", null, 0, 0, 0, Locale.ENGLISH));
        assertEquals(GoalI18n.get(Locale.ENGLISH, GoalI18nKeys.GOAL_ERROR_ACTIVE_EXISTS), ex.getMessage());
    }

    @Test
    public void patchChangesTheGoalLocale() {
        HarnessEngine engine = engine(null);
        GoalRuntime rt = new GoalRuntime(engine, BudgetThresholds.defaults(), 3, 3, false);
        List<Locale> locales = new CopyOnWriteArrayList<>();
        List<String> prompts = new CopyOnWriteArrayList<>();
        rt.addExecutor("t", null, (sid, prompt, locale) -> {
            prompts.add(prompt);
            locales.add(locale);
            return GoalOutcome.done("ok", true, 0);
        });

        AgentSession s = session("p1");
        rt.attach(s, "Fix the login bug", null, 0, 0, 0, Locale.CHINA);
        rt.runRound(s);

        // 补丁指定语言：改成它，下一轮的提示词与执行器语言都跟着变
        rt.apply(s, rt.state(s), GoalPatch.create().locale(Locale.ENGLISH));
        assertEquals(Locale.ENGLISH, rt.state(s).getLocale());
        rt.runRound(s);

        assertEquals(Arrays.asList(Locale.CHINA, Locale.ENGLISH), locales);
        assertTrue(prompts.get(1).contains(GoalI18n.fmt(Locale.ENGLISH, GoalI18nKeys.GOAL_PROMPT_FULL_CONTINUATION,
                "objective", "Fix the login bug")), prompts.get(1));
    }

    @Test
    public void patchWithoutLocaleKeepsTheGoalLocale() {
        HarnessEngine engine = engine(null);
        GoalRuntime rt = new GoalRuntime(engine, BudgetThresholds.defaults(), 3, 3, false);
        AgentSession s = session("p2");
        rt.attach(s, "Fix the login bug", null, 0, 0, 0, Locale.CHINA);

        rt.apply(s, rt.state(s), GoalPatch.create().objective("Fix the tests"));
        assertEquals(Locale.CHINA, rt.state(s).getLocale(), "补丁没指定语言：保持目标的语言");
    }

    @Test
    public void patchWithNullLocaleKeepsTheGoalLocale() {
        HarnessEngine engine = engine(Locale.ENGLISH);
        GoalRuntime rt = new GoalRuntime(engine, BudgetThresholds.defaults(), 3, 3, false);
        List<Locale> locales = new CopyOnWriteArrayList<>();
        rt.addExecutor("t", null, (sid, prompt, locale) -> {
            locales.add(locale);
            return GoalOutcome.done("ok", true, 0);
        });

        AgentSession s = session("p3");
        rt.attach(s, "Fix the login bug", null, 0, 0, 0, Locale.CHINA);
        // locale(null) 与「不调」同义：保持，不清除
        rt.apply(s, rt.state(s), GoalPatch.create().locale(null));

        assertEquals(Locale.CHINA, rt.state(s).getLocale(), "传 null 也是保持目标的语言");
        rt.runRound(s);
        assertEquals(Arrays.asList(Locale.CHINA), locales, "目标语言优先于引擎语言");
    }

    @Test
    public void localeIsPersistedWithTheSnapshot() {
        Goal g = new Goal("Prepare a business plan", 0);
        g.setLocale(Locale.CHINA);
        Map<String, Object> map = g.toMap();
        assertEquals("zh-CN", map.get(GoalProjection.K_LOCALE));
        assertEquals(Locale.CHINA, Goal.fromMap(map).getLocale());
        assertEquals(Locale.CHINA, GoalProjection.from((Object) map).getLocale());

        ONode node = g.toONode();
        assertEquals(Locale.CHINA, Goal.fromONode(node).getLocale());

        // 升级前保存的投影没有这个字段：跟随引擎
        Goal legacy = new Goal("x", 0);
        Map<String, Object> legacyMap = legacy.toMap();
        assertFalse(legacyMap.containsKey(GoalProjection.K_LOCALE), "未指定语言不写键");
        assertNull(Goal.fromMap(legacyMap).getLocale());

        // 下划线写法也认
        Map<String, Object> underscore = new HashMap<>(legacyMap);
        underscore.put(GoalProjection.K_LOCALE, "en_US");
        assertEquals(Locale.US, Goal.fromMap(underscore).getLocale());
    }

    @Test
    public void localeSurvivesHydrate() {
        HarnessEngine engine = engine(null);
        AgentSession s = session("h1");
        new GoalRuntime(engine, BudgetThresholds.defaults(), 3, 3, false)
                .attach(s, "Prepare a business plan", null, 0, 0, 0, Locale.ENGLISH);

        s.attrs().remove(GoalAttrs.GOAL_STATE); // 模拟会话重新加载：只剩快照里的投影
        Goal hydrated = new GoalRuntime(engine, BudgetThresholds.defaults(), 3, 3, false).state(s);
        assertEquals(Locale.ENGLISH, hydrated.getLocale());
    }
}
