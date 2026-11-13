package features.ai.harness.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.i18n.HarnessI18nKeys;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.hitl.WebToolStrategy;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.ai.harness.spi.InputBacklog;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.FrozenMessages;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * harness-core 低频分支的文案场景（渲染快照走不到的失败、拒绝路径）
 *
 * <p>中文期望值逐字取自改造前（HEAD）的字面量；英文按资源渲染，并断言与中文不同（确实切换了语言）。</p>
 */
public class HarnessI18nScenarioTest {
    @TempDir
    Path workspace;

    static final Locale[] LOCALES = {null, Locale.CHINA, Locale.ENGLISH};

    static boolean isZh(Locale locale) {
        return locale == null || AiI18n.isChinese(locale);
    }

    /**
     * 中文用改造前的字面量；英文用资源值
     */
    static String expect(Locale locale, String zhLiteral, String enValue) {
        return isZh(locale) ? zhLiteral : enValue;
    }

    /**
     * 英文期望值：取自审校过的文案快照（不现读资源，否则资源被改坏时期望值也跟着变）
     */
    static String en(String key, Object... nameValues) {
        return FrozenMessages.of(Locale.ENGLISH).fmt(key, nameValues);
    }

    // ---------------- TaskTalent ----------------

    @Test
    public void testTaskDescriptionFollowsDefaultLocale() {
        TaskTalent talent = HarnessFixtures.engine(workspace).getTaskTalent();
        String zh = talent.description(null);
        assertEquals("多任务调度专家：将复杂任务拆解并委派给专项子代理（如 explore, plan, bash 等），支持并行处理以提高效率。", zh);

        Locale saved = AiI18n.getDefaultLocale();
        AiI18n.setDefaultLocale(Locale.ENGLISH);
        try {
            String text = talent.description(null);
            assertEquals(en(HarnessI18nKeys.TASK_DESCRIPTION), text);
            assertNotEquals(zh, text);
        } finally {
            AiI18n.setDefaultLocale(saved);
        }
    }

    /**
     * 子代理装配时抛错：任务失败文案；同时验证子代理继承了父代理的语言
     */
    @Test
    public void testTaskFailedAndLocaleInherited() throws Throwable {
        for (Locale locale : LOCALES) {
            AtomicReference<Locale> childLocale = new AtomicReference<>();
            HarnessEngine engine = HarnessFixtures.builder(workspace.resolve("tf" + AiI18n.toTag(locale)))
                    .extensionAdd(new HarnessExtension() {
                        @Override
                        public void onRequestAssemble(HarnessEngine engine, String agentName, boolean isDelegated,
                                                      ReActOptionsAmend optionsAmend) {
                            if (isDelegated) {
                                try {
                                    childLocale.set(((ReActOptions) HarnessFixtures.get(optionsAmend, "options")).getLocale());
                                } catch (Exception e) {
                                    throw new IllegalStateException(e);
                                }
                                throw new IllegalStateException("boom");
                            }
                        }
                    })
                    .build();

            AgentSession session = engine.getSession("s1");
            HarnessFixtures.bindTrace(session, HarnessFixtures.trace(locale, false));

            String out = engine.getTaskTalent().multitask(Collections.singletonList(op("explore")),
                    workspace.toString(), "s1", AiI18n.toTag(locale)).getContent();

            String reason = expect(locale, "ERROR: 任务执行失败: boom",
                    "ERROR: " + en(HarnessI18nKeys.TASK_ERROR_FAILED, "error", "boom"));
            assertTrue(out.contains(reason), out);

            Locale expected = locale == null ? AiI18n.getDefaultLocale() : locale;
            assertEquals(expected, childLocale.get(), "子代理应继承父代理的语言");
        }
    }

    /**
     * 子代理定义加载失败
     */
    @Test
    public void testTaskLoadFailed() throws Throwable {
        for (Locale locale : LOCALES) {
            HarnessEngine engine = HarnessFixtures.builder(workspace.resolve("lf" + AiI18n.toTag(locale)))
                    .extensionAdd(new HarnessExtension() {
                        @Override
                        public void onAgentBuild(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder) {
                            if ("explore".equals(agentName)) {
                                throw new IllegalStateException("boom");
                            }
                        }
                    })
                    .build();

            AgentSession session = engine.getSession("s1");
            HarnessFixtures.bindTrace(session, HarnessFixtures.trace(locale, false));

            String out = engine.getTaskTalent().multitask(Collections.singletonList(op("explore")),
                    workspace.toString(), "s1", AiI18n.toTag(locale)).getContent();

            String reason = expect(locale, "ERROR: 子代理 'explore' 定义加载失败: boom",
                    "ERROR: " + en(HarnessI18nKeys.TASK_ERROR_LOAD_FAILED, "name", "explore", "error", "boom"));
            assertTrue(out.contains(reason), out);
        }
    }

    /**
     * 子代理流没有结束事件：父流已取消 / 未取消
     */
    @Test
    public void testTaskRequireEnd() throws Throwable {
        AtomicReference<FluxSink<?>> ref = new AtomicReference<>();
        Disposable d = Flux.create(ref::set).subscribe();
        d.dispose();
        FluxSink<?> cancelled = ref.get();

        Class<?>[] types = {RunEndEvent.class, FluxSink.class, Locale.class};

        for (Locale locale : LOCALES) {
            IllegalStateException paused = assertThrows(IllegalStateException.class,
                    () -> HarnessFixtures.invoke(TaskTalent.class, "requireEnd", types, null, cancelled, locale));
            assertEquals(expect(locale, "主代理已暂停", en(HarnessI18nKeys.TASK_ERROR_PARENT_PAUSED)), paused.getMessage());

            IllegalStateException noResp = assertThrows(IllegalStateException.class,
                    () -> HarnessFixtures.invoke(TaskTalent.class, "requireEnd", types, null, null, locale));
            assertEquals(expect(locale, "子代理未正常完成：未收到正常响应", en(HarnessI18nKeys.TASK_ERROR_NO_RESPONSE)),
                    noResp.getMessage());
        }
    }

    /**
     * 内置子代理按语言分文件：中文用基础文件；英文只替换描述与正文；未知语言落回英文；非内置定义不动。
     * 描述经 {@code getAgentDescription} 取；正文在推理时按本轮语言取，同一个代理实例在各语言的轮次上各自正确。
     */
    @Test
    public void testBuiltinAgentLocalized() throws Exception {
        HarnessEngine engine = HarnessFixtures.engine(workspace);
        AgentDefinition base = engine.resolveAgent(null, "explore");
        String zhDescription = base.getDescription();
        String zhPrompt = base.getSystemPrompt();
        assertTrue(zhPrompt.startsWith("您是一位全域信息探索专家"), zhPrompt);

        //描述
        assertEquals(zhDescription, engine.getAgentDescription(base, null));
        assertEquals(zhDescription, engine.getAgentDescription(base, Locale.CHINA));
        assertEquals(zhDescription, engine.getAgentDescription(base, Locale.TRADITIONAL_CHINESE));
        String enDescription = engine.getAgentDescription(base, Locale.ENGLISH);
        assertTrue(enDescription.startsWith("Exploration expert"), enDescription);
        //未知语言、带国家的英文：落回英文版本
        assertEquals(enDescription, engine.getAgentDescription(base, Locale.JAPANESE));
        assertEquals(enDescription, engine.getAgentDescription(base, Locale.US));
        assertNull(engine.getAgentDescription(null, Locale.ENGLISH));

        //正文：同一个代理实例按每轮的语言渲染
        ReActAgent agent = engine.getAgentBuilder(base).build();
        String enPrompt = HarnessFixtures.renderSystemPrompt(agent, Locale.ENGLISH);
        assertTrue(enPrompt.startsWith("You are an exploration expert"), enPrompt);
        assertEquals(zhPrompt, HarnessFixtures.renderSystemPrompt(agent, Locale.CHINA));
        assertEquals(zhPrompt, HarnessFixtures.renderSystemPrompt(agent, Locale.TRADITIONAL_CHINESE));
        assertEquals(enPrompt, HarnessFixtures.renderSystemPrompt(agent, Locale.JAPANESE));
        assertEquals(enPrompt, HarnessFixtures.renderSystemPrompt(agent, Locale.US));
        //不改基础定义
        assertEquals(zhPrompt, base.getSystemPrompt());
        assertEquals(zhDescription, base.getDescription());

        //非内置（运行时注册 / 工作区挂载的同名定义）不替换
        AgentDefinition custom = base.copy();
        custom.setMountAlias("@workspace-agents");
        assertEquals(zhDescription, engine.getAgentDescription(custom, Locale.ENGLISH));
        assertEquals(zhPrompt, HarnessFixtures.renderSystemPrompt(engine.getAgentBuilder(custom).build(), Locale.ENGLISH));
        AgentDefinition runtime = base.copy();
        assertEquals(zhDescription, engine.getAgentDescription(runtime, Locale.ENGLISH));
        assertEquals(zhPrompt, HarnessFixtures.renderSystemPrompt(engine.getAgentBuilder(runtime).build(), Locale.ENGLISH));

        //隐藏的内置代理同样有语言版本
        AgentDefinition git = engine.resolveAgent(null, "git-summary");
        assertTrue(HarnessFixtures.renderSystemPrompt(engine.getAgentBuilder(git).build(), Locale.ENGLISH)
                .startsWith("You are a professional Git commit message assistant."));
    }

    static TaskTalent.MultiTaskOp op(String agent) {
        TaskTalent.MultiTaskOp op = new TaskTalent.MultiTaskOp();
        op.agent_name = agent;
        op.description = "d1";
        op.prompt = "p1";
        op.index = 1;
        return op;
    }

    // ---------------- GenerateTalent ----------------

    @Test
    public void testGenerateFailed() {
        for (Locale locale : LOCALES) {
            HarnessEngine engine = HarnessFixtures.builder(workspace.resolve("gf" + AiI18n.toTag(locale)))
                    .agentPersistPolicy((s, d) -> {
                        throw new IllegalStateException("boom");
                    })
                    .build();
            engine.getSession("s1");

            String out = engine.getGenerateTalent().generate("g1", "dg", "sp", null, null, true,
                    workspace.toString(), "s1", AiI18n.toTag(locale)).getContent();
            assertEquals(expect(locale, "ERROR: 创建子代理失败: boom",
                    "ERROR: " + en(HarnessI18nKeys.GENERATE_ERROR_FAILED, "error", "boom")), out);
        }
    }

    // ---------------- HITL ----------------

    /**
     * 内置拒绝规则在没有 url 时的兜底域名（公开入口下规则只在有 url 时命中，这里直接驱动转换方法）
     */
    @Test
    public void testWebUnknownHost() throws Throwable {
        WebToolStrategy web = new WebToolStrategy("webfetch");
        PermissionRule rule = ((List<PermissionRule>) HarnessFixtures.get(web, "internalRules")).get(0);

        for (Locale locale : LOCALES) {
            String out = (String) HarnessFixtures.invoke(web, "toResult",
                    new Class<?>[]{PermissionRule.class, Map.class, Locale.class},
                    rule, new HashMap<String, Object>(), locale);
            assertEquals(expect(locale, "检测到高风险域名 [未知]，拒绝访问。",
                    en(HarnessI18nKeys.HITL_WEB_RISKY, "host", en(HarnessI18nKeys.HITL_WEB_UNKNOWN_HOST))), out);
        }
    }

    // ---------------- 输入 ----------------

    @Test
    public void testCommandScopeDenied() {
        for (Locale locale : LOCALES) {
            HarnessEngine engine = HarnessFixtures.builder(workspace.resolve("sd" + AiI18n.toTag(locale)))
                    .commandAdd(HarnessRenderGoldenTest.named("x1", "d", false))
                    .build();
            AgentSession s = engine.getSession("s1");
            InputOutcome outcome = engine.handle(HarnessInput.of(s, "/x1").locale(locale).commandAware(true)
                    .scope(InputScope.builder("u1").commands("clear").build()));
            assertEquals(expect(locale, "命令不可用：/x1",
                    en(HarnessI18nKeys.COMMAND_SCOPE_DENIED, "command", "/x1")), outcome.getCommandOutput(),
                    () -> outcome.getKind() + " / " + outcome.getRejectReason() + " / " + outcome.getRejectDetail());
        }
    }

    @Test
    public void testAnalyzeMedia() {
        for (Locale locale : LOCALES) {
            HarnessEngine engine = HarnessFixtures.engine(workspace.resolve("am" + AiI18n.toTag(locale)));
            AgentSession s = engine.getSession("s1");
            AudioBlock audio = AudioBlock.ofBase64(Base64.getEncoder().encodeToString(new byte[]{1}), "audio/mp3");
            InputOutcome outcome = engine.handle(HarnessInput.of(s, "").locale(locale).media(audio));
            assertEquals(expect(locale, "请分析这些媒体文件", en(HarnessI18nKeys.INPUT_ANALYZE_MEDIA)),
                    outcome.getPrompt().getUserContent());
        }
    }

    @Test
    public void testHitlPendingWithoutToolName() {
        for (Locale locale : LOCALES) {
            HarnessEngine engine = HarnessFixtures.engine(workspace.resolve("hp" + AiI18n.toTag(locale)));
            AgentSession s = engine.getSession("s1");
            s.getContext().put(HITL.LAST_INTERVENED, new HITLTask("c1", null, new HashMap<>(), null));
            InputOutcome outcome = engine.handle(HarnessInput.of(s, "hi").locale(locale));
            assertEquals(expect(locale, "请先处理待审批操作", en(HarnessI18nKeys.INPUT_HITL_PENDING)),
                    outcome.getRejectDetail());
        }
    }

    /**
     * 忙时排队的三种拒绝：带附件、作用域不可保存、积压不可用
     */
    @Test
    public void testBusyRejects() {
        for (Locale locale : LOCALES) {
            HarnessEngine engine = HarnessFixtures.builder(workspace.resolve("br" + AiI18n.toTag(locale)))
                    .extensionAdd(new FullBacklog())
                    .build();
            AgentSession s = engine.getSession("s1");
            SessionRuns.attach(s, new HarnessRenderGoldenTest.NoopDisposable());

            ImageBlock image = ImageBlock.ofBase64(Base64.getEncoder().encodeToString(new byte[]{1}), "image/png");
            InputOutcome attachments = engine.handle(HarnessInput.of(s, "hi").locale(locale).media(image));
            assertEquals(expect(locale, "当前任务正在执行，带附件的消息不能排队，请稍后再发",
                    en(HarnessI18nKeys.INPUT_BUSY_ATTACHMENTS)), attachments.getRejectDetail());

            InputOutcome scope = engine.handle(HarnessInput.of(s, "hi").locale(locale)
                    .scope(InputScope.builder("u1").attr("k", "v").build()));
            assertEquals(expect(locale, "当前任务正在执行，该消息带有不可保存的权限范围，请稍后再发",
                    en(HarnessI18nKeys.INPUT_BUSY_SCOPE)), scope.getRejectDetail());

            InputOutcome backlog = engine.handle(HarnessInput.of(s, "hi").locale(locale));
            assertEquals(expect(locale, "当前任务正在执行，且排队已满或不可用",
                    en(HarnessI18nKeys.INPUT_BUSY_BACKLOG)), backlog.getRejectDetail());
        }
    }

    /**
     * 永远满的积压
     */
    static final class FullBacklog implements HarnessExtension, InputBacklog {
        @Override
        public int offer(AgentSession session, HarnessInput input) {
            return -1;
        }

        @Override
        public boolean hasPending(AgentSession session) {
            return false;
        }
    }
}
