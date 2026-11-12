package features.ai.harness.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.ai.harness.i18n.HarnessI18nKeys;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.flow.FlowContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 引擎实例的默认语言：输入指定的优先，没有时用引擎的，并下传到本轮的整条链路
 */
public class HarnessEngineLocaleTest {
    @TempDir
    Path workspace;

    /**
     * 请求装配后实际生效的 options（与执行时同一条初始化路径）
     */
    static ReActOptions optionsOf(InputOutcome outcome) throws Exception {
        return optionsOf((ReActRequest) outcome.getRequest());
    }

    @Test
    public void testLocaleOf() {
        HarnessEngine plain = HarnessFixtures.engine(workspace.resolve("plain"));
        assertNull(plain.getLocale());
        assertNull(plain.localeOf((HarnessInput) null));
        assertNull(plain.localeOf(HarnessInput.of("hi")));
        assertSame(Locale.ENGLISH, plain.localeOf(HarnessInput.of("hi").locale(Locale.ENGLISH)));

        HarnessEngine en = HarnessFixtures.builder(workspace.resolve("en")).locale(Locale.ENGLISH).build();
        assertSame(Locale.ENGLISH, en.getLocale());
        assertSame(Locale.ENGLISH, en.localeOf((HarnessInput) null));
        assertSame(Locale.ENGLISH, en.localeOf(HarnessInput.of("hi")));
        assertSame(Locale.CHINA, en.localeOf(HarnessInput.of("hi").locale(Locale.CHINA)), "输入指定的优先");
    }

    /**
     * 本轮 options 的语言：输入优先，引擎兜底；都没有时不设置（沿用智能体配置 / 全局默认）
     */
    @Test
    public void testRequestLocale() throws Exception {
        HarnessEngine en = HarnessFixtures.builder(workspace.resolve("en")).locale(Locale.ENGLISH).build();

        InputOutcome byEngine = en.handle(HarnessInput.of(en.getSession("s1"), "hi"));
        try {
            assertEquals(Locale.ENGLISH, optionsOf(byEngine).getLocale());
        } finally {
            byEngine.discard();
        }

        InputOutcome byInput = en.handle(HarnessInput.of(en.getSession("s2"), "hi").locale(Locale.CHINA));
        try {
            assertEquals(Locale.CHINA, optionsOf(byInput).getLocale());
        } finally {
            byInput.discard();
        }

        //程序轮次（goal / loop）不带语言：用引擎的
        InputOutcome program = en.handle(HarnessInput.ofProgram(en.getSession("s3"), "round", "goal"));
        try {
            assertEquals(Locale.ENGLISH, optionsOf(program).getLocale());
        } finally {
            program.discard();
        }

        //输入没有指定语言时不回写到输入（进积压只保存输入自己指定的）
        HarnessInput raw = HarnessInput.of(en.getSession("s4"), "hi");
        en.handle(raw).discard();
        assertNull(raw.getLocale());

        HarnessEngine plain = HarnessFixtures.engine(workspace.resolve("plain"));
        InputOutcome none = plain.handle(HarnessInput.of(plain.getSession("s1"), "hi"));
        try {
            assertEquals(AiI18n.getDefaultLocale(), optionsOf(none).getLocale(), "都没有时沿用默认");
        } finally {
            none.discard();
        }
    }

    /**
     * 给用户看的文案（附件默认提问、命令回执、待审批、忙碌）同样按生效语言输出
     */
    @Test
    public void testUserFacingTextFollowsEngineLocale() {
        HarnessEngine en = HarnessFixtures.builder(workspace.resolve("en")).locale(Locale.ENGLISH).build();
        String enHint = HarnessI18n.get(Locale.ENGLISH, HarnessI18nKeys.INPUT_HITL_PENDING);
        String zhHint = HarnessI18n.get(Locale.CHINA, HarnessI18nKeys.INPUT_HITL_PENDING);

        //待审批
        AgentSession hitl = en.getSession("h1");
        hitl.getContext().put(HITL.LAST_INTERVENED, new HITLTask("c1", null, new HashMap<>(), null));
        assertEquals(enHint, en.handle(HarnessInput.of(hitl, "hi")).getRejectDetail());
        assertEquals(zhHint, en.handle(HarnessInput.of(hitl, "hi").locale(Locale.CHINA)).getRejectDetail());

        //命令回执
        HarnessEngine withCommand = HarnessFixtures.builder(workspace.resolve("cmd")).locale(Locale.ENGLISH)
                .commandAdd(HarnessRenderGoldenTest.named("x1", "d", false))
                .build();
        InputOutcome denied = withCommand.handle(HarnessInput.of(withCommand.getSession("c1"), "/x1")
                .scope(InputScope.builder("u1").commands("clear").build()));
        assertEquals(AiI18n.format(HarnessI18n.get(Locale.ENGLISH, HarnessI18nKeys.COMMAND_SCOPE_DENIED),
                Collections.singletonMap("command", "/x1")), denied.getCommandOutput());

        //忙碌（无积压能力：拒绝说明）
        AgentSession busy = en.getSession("b1");
        SessionRuns.attach(busy, new HarnessRenderGoldenTest.NoopDisposable());
        assertEquals(HarnessI18n.get(Locale.ENGLISH, HarnessI18nKeys.INPUT_BUSY),
                en.handle(HarnessInput.of(busy, "hi")).getRejectDetail());
    }

    // ==================== 续流：语言属于被中断的那一轮 ====================

    /**
     * 引擎中文、那一轮是英文（输入当时指定的）：不带语言续流，沿用英文，不被引擎语言覆盖
     */
    @Test
    public void testResumeKeepsRunLocale() throws Exception {
        HarnessEngine zh = HarnessFixtures.builder(workspace.resolve("zh")).locale(Locale.CHINA).build();
        AgentSession session = zh.getSession("r1");
        putMainTrace(zh, session, ranIn(Locale.ENGLISH, true));

        assertResumeLocale(zh, HarnessInput.of(session, null).resume(true), Locale.ENGLISH);
    }

    /**
     * 会话从存储重新加载后（运行选项不随快照保存）：仍沿用那一轮的英文
     */
    @Test
    public void testResumeKeepsRunLocaleAfterReload() throws Exception {
        HarnessEngine zh = HarnessFixtures.builder(workspace.resolve("zh")).locale(Locale.CHINA).build();
        AgentSession origin = zh.getSession("r2");
        putMainTrace(zh, origin, ranIn(Locale.ENGLISH, true));

        AgentSession reloaded = InMemoryAgentSession.of(FlowContext.fromJson(origin.getContext().toJson()));
        ReActTrace trace = zh.getMainAgent().getTrace(reloaded);
        assertNull(trace.getOptions(), "options should not survive the snapshot");
        assertEquals("en", trace.getRunLocale().getLanguage());

        assertResumeLocale(zh, HarnessInput.of(reloaded, null).resume(true), Locale.ENGLISH);
    }

    /**
     * 审批决定（与 HarnessHost.decide 同一条路径）：不带语言，续流沿用那一轮的英文；重新加载后一样
     */
    @Test
    public void testHitlDecisionKeepsRunLocale() throws Exception {
        HarnessEngine zh = HarnessFixtures.builder(workspace.resolve("zh")).locale(Locale.CHINA).build();

        AgentSession session = zh.getSession("d1");
        putMainTrace(zh, session, ranIn(Locale.ENGLISH, true));
        session.getContext().put(HITL.LAST_INTERVENED, new HITLTask("c1", "bash", new HashMap<>(), null));
        assertDecisionLocale(zh, session, Locale.ENGLISH);

        AgentSession origin = zh.getSession("d2");
        putMainTrace(zh, origin, ranIn(Locale.ENGLISH, true));
        origin.getContext().put(HITL.LAST_INTERVENED, new HITLTask("c1", "bash", new HashMap<>(), null));
        AgentSession reloaded = InMemoryAgentSession.of(FlowContext.fromJson(origin.getContext().toJson()));
        assertDecisionLocale(zh, reloaded, Locale.ENGLISH);
    }

    private static void assertDecisionLocale(HarnessEngine engine, AgentSession session, Locale expected) throws Exception {
        InputOutcome outcome = engine.handle(HarnessInput.of(session, null).hitl(HITLAction.APPROVE, "c1").resume(true));
        try {
            assertNotNull(outcome.getRequest(), "decision should resume: " + outcome);
            assertEquals(expected, optionsOf(outcome).getLocale(), "decision resume locale");
        } finally {
            outcome.discard();
        }
    }

    /**
     * 续流输入显式指定了语言：以输入为准
     */
    @Test
    public void testResumeWithInputLocale() throws Exception {
        HarnessEngine zh = HarnessFixtures.builder(workspace.resolve("zh")).locale(Locale.CHINA).build();
        AgentSession session = zh.getSession("r3");
        putMainTrace(zh, session, ranIn(Locale.ENGLISH, true));

        assertResumeLocale(zh, HarnessInput.of(session, null).resume(true).locale(Locale.JAPANESE), Locale.JAPANESE);
    }

    /**
     * 那一轮没留下语言（升级前保存的旧会话）或从未跑过：用引擎语言
     */
    @Test
    public void testResumeWithoutRunLocaleUsesEngineLocale() throws Exception {
        HarnessEngine en = HarnessFixtures.builder(workspace.resolve("en")).locale(Locale.ENGLISH).build();

        AgentSession legacy = en.getSession("r4");
        ReActTrace trace = ranIn(Locale.CHINA, false);
        trace.getOriginalPrompt().locale(null);
        putMainTrace(en, legacy, trace);
        assertResumeLocale(en, HarnessInput.of(legacy, null).resume(true), Locale.ENGLISH);

        assertResumeLocale(en, HarnessInput.of(en.getSession("r5"), null).resume(true), Locale.ENGLISH);
    }

    /**
     * 不经过 handle、直接用引擎的 resume 续流：同样沿用那一轮的语言
     */
    @Test
    @SuppressWarnings("deprecation")
    public void testEngineResumeKeepsRunLocale() throws Exception {
        HarnessEngine zh = HarnessFixtures.builder(workspace.resolve("zh")).locale(Locale.CHINA).build();
        AgentSession session = zh.getSession("r6");
        putMainTrace(zh, session, ranIn(Locale.ENGLISH, false));

        ReActRequest request = zh.resume(zh.getMainAgent()).session(session);
        assertEquals(Locale.ENGLISH, optionsOf(request).getLocale());
    }

    /**
     * 一轮跑过的轨迹：原始提示词上记着这一轮的语言（与运行时写入方式一致）
     *
     * @param withOptions 是否带上内存中的运行选项（同一进程内续流时有，快照重新加载后没有）
     */
    private static ReActTrace ranIn(Locale locale, boolean withOptions) throws Exception {
        ReActTrace trace = withOptions ? HarnessFixtures.trace(locale, false) : new ReActTrace();
        Prompt prompt = Prompt.of("hi");
        prompt.locale(locale);
        HarnessFixtures.set(trace, "originalPrompt", prompt);
        return trace;
    }

    private static void putMainTrace(HarnessEngine engine, AgentSession session, ReActTrace trace) {
        session.getContext().put(engine.getMainAgent().getConfig().getTraceKey(), trace);
    }

    private static void assertResumeLocale(HarnessEngine engine, HarnessInput input, Locale expected) throws Exception {
        InputOutcome outcome = engine.handle(input);
        try {
            assertEquals(expected, optionsOf(outcome).getLocale(), "resume locale");
        } finally {
            outcome.discard();
        }
    }

    static ReActOptions optionsOf(ReActRequest request) throws Exception {
        Method init = ReActRequest.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(request);
        Field f = ReActRequest.class.getDeclaredField("options");
        f.setAccessible(true);
        return (ReActOptions) f.get(request);
    }
}
