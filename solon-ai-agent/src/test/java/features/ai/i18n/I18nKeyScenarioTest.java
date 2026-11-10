package features.ai.i18n;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.i18n.AgentI18nKeys;
import org.noear.solon.ai.agent.react.LlmErrorEvent;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.intercept.compress.HierarchicalCompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.VectorStoreCompressionStrategy;
import org.noear.solon.ai.agent.react.task.PlanTalent;
import org.noear.solon.ai.agent.react.task.ReasonTask;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamInterceptor;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.flow.FlowContext;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行时分支文案（错误、拒绝、跳过、兜底等）的中英双语用例
 *
 * <p>这些文案只在异常或边界分支出现，主流程快照覆盖不到。每个用例都经产品代码走到调用点，
 * 并断言输出与该 key 在对应语言下的文案一致（同时校验 key 与调用点的绑定关系）。
 * 与 {@link I18nKeyCoverageTest} 配合，保证每个 key 都有用例覆盖。</p>
 */
public class I18nKeyScenarioTest {
    static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");
    static final Locale[] LOCALES = {Locale.CHINESE, Locale.ENGLISH};

    /**
     * 输出必须等于该语言的文案；英文不得含中文，中文必须含中文（防止走错语言）
     */
    static void assertText(Locale locale, String expected, String actual) {
        Assertions.assertEquals(expected, actual, locale + "");
        Assertions.assertEquals(AgentI18n.isChinese(locale), CJK.matcher(actual).find(), locale + ": " + actual);
    }

    // ==================== ReAct：推理 ====================

    static class RethinkOptions extends ReActOptions {
        private final Locale locale;

        RethinkOptions(ChatModel chatModel, Locale locale) {
            super(chatModel);
            this.locale = locale;
        }

        @Override
        public String getSystemPromptFor(ReActTrace trace, FlowContext ctx) {
            return "System prompt";
        }

        @Override
        public Locale getLocale() {
            return locale;
        }

        @Override
        public boolean isAutoRethink() {
            return true;
        }

        @Override
        public int getMaxTurns() {
            return 1;
        }
    }

    @Test
    public void reason_rethink() throws Throwable {
        for (Locale l : LOCALES) {
            ReActRuntimeI18nTest.ReasonEnv env = new ReActRuntimeI18nTest.ReasonEnv(l);
            ReActOptions options = new RethinkOptions(env.chatModel, l);
            when(env.trace.getOptions()).thenReturn(options);
            env.respond("{\"role\":\"assistant\",\"content\":\"ok\"}");

            String expected = AgentI18n.fmt(l, AgentI18nKeys.REACT_REASON_RETHINK, "turn", 1);
            String actual = null;
            for (ChatMessage m : env.workingMemory.getMessages()) {
                if (expected.equals(m.getContent())) {
                    actual = m.getContent();
                }
            }
            Assertions.assertNotNull(actual, l + ": rethink prompt not found");
            assertText(l, expected, actual);
        }
    }

    @Test
    public void reason_formatFixAndEmptyExhausted() throws Throwable {
        for (Locale l : LOCALES) {
            ReActRuntimeI18nTest.ReasonEnv fix = new ReActRuntimeI18nTest.ReasonEnv(l);
            fix.respond("{\"role\":\"assistant\",\"content\":\"<think>hmm</think>\"}");
            assertText(l, AgentI18n.fmt(l, AgentI18nKeys.REACT_REASON_FORMAT_FIX, "turn", 1, "attempt", 1),
                    fix.workingMemory.getLastMessage().getContent());

            ReActRuntimeI18nTest.ReasonEnv exhausted = new ReActRuntimeI18nTest.ReasonEnv(l);
            exhausted.emptyRetryCounter.set(2);
            exhausted.respond("{\"role\":\"assistant\"}");
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(exhausted.trace).setFinalAnswer(captor.capture());
            assertText(l, AgentI18n.get(l, AgentI18nKeys.REACT_REASON_EMPTY_EXHAUSTED), captor.getValue());
        }
    }

    @Test
    public void reason_errorMessages() throws Throwable {
        Method http = ReasonTask.class.getDeclaredMethod("buildHttpErrorMessage", Locale.class, int.class, LlmErrorEvent.Type.class);
        Method other = ReasonTask.class.getDeclaredMethod("buildExceptionErrorMessage", Locale.class, LlmErrorEvent.Type.class);
        http.setAccessible(true);
        other.setAccessible(true);

        Map<LlmErrorEvent.Type, String> httpKeys = new HashMap<>();
        httpKeys.put(LlmErrorEvent.Type.RATE_LIMIT, AgentI18nKeys.REACT_REASON_HTTP_RATE_LIMIT);
        httpKeys.put(LlmErrorEvent.Type.UPSTREAM_UNAVAILABLE, AgentI18nKeys.REACT_REASON_HTTP_UPSTREAM_UNAVAILABLE);
        httpKeys.put(LlmErrorEvent.Type.SERVER_ERROR, AgentI18nKeys.REACT_REASON_HTTP_SERVER_ERROR);
        httpKeys.put(LlmErrorEvent.Type.TIMEOUT, AgentI18nKeys.REACT_REASON_HTTP_TIMEOUT);
        httpKeys.put(LlmErrorEvent.Type.SSL, AgentI18nKeys.REACT_REASON_HTTP_OTHER);

        Map<LlmErrorEvent.Type, String> errorKeys = new HashMap<>();
        errorKeys.put(LlmErrorEvent.Type.NO_RETURN, AgentI18nKeys.REACT_REASON_ERROR_NO_RETURN);
        errorKeys.put(LlmErrorEvent.Type.SSL, AgentI18nKeys.REACT_REASON_ERROR_SSL);
        errorKeys.put(LlmErrorEvent.Type.DNS, AgentI18nKeys.REACT_REASON_ERROR_DNS);
        errorKeys.put(LlmErrorEvent.Type.TIMEOUT, AgentI18nKeys.REACT_REASON_ERROR_TIMEOUT);
        errorKeys.put(LlmErrorEvent.Type.CONNECTION_REFUSED, AgentI18nKeys.REACT_REASON_ERROR_CONNECTION_REFUSED);
        errorKeys.put(LlmErrorEvent.Type.CONNECTION, AgentI18nKeys.REACT_REASON_ERROR_CONNECTION);
        errorKeys.put(LlmErrorEvent.Type.NETWORK, AgentI18nKeys.REACT_REASON_ERROR_NETWORK);
        errorKeys.put(LlmErrorEvent.Type.RATE_LIMIT, AgentI18nKeys.REACT_REASON_ERROR_OTHER);

        for (Locale l : LOCALES) {
            for (Map.Entry<LlmErrorEvent.Type, String> kv : httpKeys.entrySet()) {
                assertText(l, AgentI18n.fmt(l, kv.getValue(), "code", 503), (String) http.invoke(null, l, 503, kv.getKey()));
            }
            for (Map.Entry<LlmErrorEvent.Type, String> kv : errorKeys.entrySet()) {
                assertText(l, AgentI18n.get(l, kv.getValue()), (String) other.invoke(null, l, kv.getKey()));
            }
        }
    }

    // ==================== ReAct：计划工具的边界返回 ====================

    @Test
    public void planTalent_edgeResults() {
        for (Locale l : LOCALES) {
            PlanTalent talent = ReActRuntimeI18nTest.planTalent(l);
            assertText(l, AgentI18n.get(l, AgentI18nKeys.REACT_PLAN_RESULT_PROGRESS_NOT_PLANNING), talent.update_plan_progress(1));
            assertText(l, AgentI18n.get(l, AgentI18nKeys.REACT_PLAN_RESULT_REVISE_NOT_INIT), talent.revise_plan(Collections.singletonList("x"), 1));
            assertText(l, AgentI18n.get(l, AgentI18nKeys.REACT_PLAN_RESULT_CREATE_IGNORED), talent.create_plan(Collections.singletonList("only")));

            talent.create_plan(Arrays.asList("a", "b"));
            String done = talent.update_plan_progress(3);
            Assertions.assertTrue(done.contains(AgentI18n.get(l, AgentI18nKeys.REACT_PLAN_RESULT_PROGRESS_DONE)), done);
            assertText(l, AgentI18n.get(l, AgentI18nKeys.REACT_PLAN_RESULT_REVISE_EMPTY), talent.revise_plan(Collections.emptyList(), 1));
        }
    }

    // ==================== ReAct：HITL ====================

    static ReActTrace hitlTrace(Locale locale) {
        ReActTrace trace = mock(ReActTrace.class);
        ReActOptions options = ReActRuntimeI18nTest.options(mock(ChatModel.class), locale, false);
        when(trace.getOptions()).thenReturn(options);
        when(trace.getContext()).thenReturn(FlowContext.of());
        when(trace.getRoute()).thenReturn("reason");
        return trace;
    }

    @Test
    public void hitl_batchSkipReject() throws Throwable {
        HITLInterceptor hitl = new HITLInterceptor();
        Method suspend = HITLInterceptor.class.getDeclaredMethod("suspendBatch", ReActTrace.class, List.class);
        Method apply = HITLInterceptor.class.getDeclaredMethod("applyDecision", ReActTrace.class, ToolExchanger.class, HITLDecision.class, int.class);
        suspend.setAccessible(true);
        apply.setAccessible(true);

        for (Locale l : LOCALES) {
            ReActTrace trace = hitlTrace(l);
            suspend.invoke(hitl, trace, Arrays.asList(
                    new HITLTask("c1", "transfer", new HashMap<>(), "need"),
                    new HITLTask("c2", "delete", new HashMap<>(), "need")));
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(trace).setFinalAnswer(captor.capture());
            assertText(l, AgentI18n.fmt(l, AgentI18nKeys.REACT_HITL_PENDING_BATCH, "count", 2), captor.getValue());

            ToolExchanger skipped = new ToolExchanger("c1", "transfer", new HashMap<>());
            apply.invoke(hitl, hitlTrace(l), skipped, HITLDecision.skip(null), 2);
            assertText(l, AgentI18n.get(l, AgentI18nKeys.REACT_HITL_SKIPPED), skipped.getResult());

            ToolExchanger rejected = new ToolExchanger("c2", "delete", new HashMap<>());
            apply.invoke(hitl, hitlTrace(l), rejected, HITLDecision.reject(null), 2);
            assertText(l, AgentI18n.get(l, AgentI18nKeys.REACT_HITL_REJECTED), rejected.getResult());
        }
    }

    // ==================== Team：拦截跳过、无内容、指派动词 ====================

    public static class DenyInterceptor implements TeamInterceptor {
        @Override
        public boolean shouldSupervisorContinue(TeamTrace trace) {
            return false;
        }

        @Override
        public boolean shouldAgentContinue(TeamTrace trace, Agent agent) {
            return false;
        }
    }

    static Agent muteAgent(String name) {
        return new Agent() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String role() {
                return name + "-role";
            }

            @Override
            public AgentProfile profile() {
                return new AgentProfile();
            }

            @Override
            public AssistantMessage call(Prompt prompt, AgentSession session) {
                return new AssistantMessage("");
            }
        };
    }

    @Test
    public void team_skippedByInterceptor() throws Throwable {
        for (Locale l : LOCALES) {
            TeamProtocolI18nTest.Ctx c = RuntimeRenderGoldenTest.build(TeamProtocols.SEQUENTIAL, l,
                    b -> b.defaultInterceptorAdd(new DenyInterceptor()));
            String interceptor = DenyInterceptor.class.getSimpleName();

            c.coder.run(c.flow, null);
            assertText(l, AgentI18n.fmt(l, AgentI18nKeys.TEAM_RUN_SKIPPED, "interceptor", interceptor), TeamBaseI18nTest.lastRecord(c.trace));

            new SupervisorTask(c.team.getConfig()).run(c.flow, null);
            assertText(l, AgentI18n.fmt(l, AgentI18nKeys.TEAM_SUPERVISOR_SKIPPED, "interceptor", interceptor), TeamBaseI18nTest.lastRecord(c.trace));
        }
    }

    @Test
    public void team_agentNoContent() throws Throwable {
        for (Locale l : LOCALES) {
            TeamAgent team = TeamAgent.of(null).name("mute_team")
                    .protocol(TeamProtocols.SEQUENTIAL)
                    .agentAdd(muteAgent("mute"))
                    .locale(l)
                    .build();
            TeamTrace trace = PromptGoldenTest.prepareTeam(team, Prompt.of("say something"));
            FlowContext flow = FlowContext.of();
            flow.put(team.getConfig().getTraceKey(), trace);
            flow.put(Agent.KEY_CURRENT_TEAM_TRACE_KEY, team.getConfig().getTraceKey());

            team.getConfig().getAgentMap().get("mute").run(flow, null);
            assertText(l, AgentI18n.fmt(l, AgentI18nKeys.TEAM_RUN_NO_CONTENT, "agent", "mute"), TeamBaseI18nTest.lastRecord(trace));
        }
    }

    static class AssignVerbsProbe extends SupervisorTask {
        AssignVerbsProbe(TeamAgentConfig config) {
            super(config);
        }

        String verbs() {
            return assignVerbsRegex();
        }
    }

    @Test
    public void supervisor_assignVerbsOfAllLocales() throws Throwable {
        TeamProtocolI18nTest.Ctx c = TeamProtocolI18nTest.build(TeamProtocols.SEQUENTIAL, Locale.ENGLISH);
        String regex = new AssignVerbsProbe(c.team.getConfig()).verbs();

        //与团队语言无关：所有已知语言的动词都参与识别
        for (Locale l : LOCALES) {
            for (String item : AgentI18n.get(l, AgentI18nKeys.TEAM_SUPERVISOR_ASSIGN_VERBS).split("\\|")) {
                Assertions.assertTrue(("|" + regex + "|").contains("|" + item.trim() + "|"), l + ": " + item + " not in " + regex);
            }
        }
        Assertions.assertTrue(Pattern.compile("(?i)(?:" + regex + ")").matcher("指派 coder").find());
        Assertions.assertTrue(Pattern.compile("(?i)(?:" + regex + ")").matcher("next agent: coder").find());
    }

    // ==================== 协议：中文运行时反馈（英文见 TeamProtocolI18nTest）====================

    @Test
    public void blackboard_toolAndSnapshot() throws Throwable {
        for (Locale l : LOCALES) {
            TeamProtocolI18nTest.Ctx c = TeamProtocolI18nTest.build(TeamProtocols.BLACKBOARD, l);
            Map<String, Object> args = new HashMap<>();
            args.put("result", "api drafted");
            args.put("todo", "write docs");
            Object ret = TeamProtocolI18nTest.agentTools(c, c.reviewer).get(0).handle(args);
            assertText(l, AgentI18n.get(l, AgentI18nKeys.PROTOCOL_BLACKBOARD_TOOL_UPDATED), String.valueOf(ret));

            TeamProtocolI18nTest.agentRound(c, c.reviewer, "review ok");
            String prompt = TeamProtocolI18nTest.agentPrompt(c, c.reviewer, Prompt.of(ChatMessage.ofUser("continue")), l);
            Assertions.assertTrue(prompt.contains(AgentI18n.get(l, AgentI18nKeys.PROTOCOL_BLACKBOARD_PROMPT_SNAPSHOT)), prompt);
        }
    }

    @Test
    public void contractNet_rejectsAndGuards() throws Throwable {
        for (Locale l : LOCALES) {
            TeamProtocolI18nTest.Ctx c = TeamProtocolI18nTest.build(TeamProtocols.CONTRACT_NET, l);
            List<FunctionTool> supTools = TeamProtocolI18nTest.supervisorTools(c);
            Map<String, Object> req = new HashMap<>();
            req.put("requirement", "java api");
            supTools.get(0).handle(req);

            Map<String, Object> bid = new HashMap<>();
            bid.put("score", 80);
            bid.put("plan", "");
            assertText(l, AgentI18n.get(l, AgentI18nKeys.PROTOCOL_CONTRACTNET_BID_TOOL_PLAN_REQUIRED),
                    String.valueOf(TeamProtocolI18nTest.agentTools(c, c.coder).get(0).handle(bid)));

            //第 2 轮招标后次数耗尽，且无人投标
            c.trace.setRoute(TeamAgent.ID_SUPERVISOR);
            supTools.get(0).handle(req);
            c.trace.setRoute(TeamAgent.ID_SUPERVISOR);
            assertText(l, AgentI18n.get(l, AgentI18nKeys.PROTOCOL_CONTRACTNET_CALL_TOOL_EXHAUSTED), String.valueOf(supTools.get(0).handle(req)));

            c.trace.setRoute("reviewer");
            c.protocol.onSupervisorRouting(c.flow, c.trace, "reviewer");
            Assertions.assertEquals(Agent.ID_END, c.trace.getRoute());
            assertText(l, AgentI18n.get(l, AgentI18nKeys.PROTOCOL_CONTRACTNET_ROUTE_CANNOT_AWARD), c.trace.getFinalAnswer());
        }
    }

    // ==================== 压缩 ====================

    @Test
    public void compress_hierarchicalUserData() throws Throwable {
        for (Locale l : LOCALES) {
            String userData = RuntimeRenderGoldenTest.compressUserData(new HierarchicalCompressionStrategy()::compress, l);
            String noSummary = AgentI18n.get(l, AgentI18nKeys.COMPRESS_HIERARCHICAL_NO_SUMMARY);
            String[] parts = AgentI18n.fmt(l, AgentI18nKeys.COMPRESS_HIERARCHICAL_USER_DATA, "summary", noSummary, "history", "\u0000").split("\u0000");
            Assertions.assertTrue(userData.startsWith(parts[0]) && userData.endsWith(parts[1]), l + ":\n" + userData);
            Assertions.assertEquals(AgentI18n.isChinese(l), CJK.matcher(userData).find(), userData);
        }
    }

    @Test
    public void compress_truncatedShortMarker() throws Throwable {
        Method m = ContextCompressionInterceptor.class.getDeclaredMethod("truncateTextToTokens", String.class, int.class, Locale.class);
        m.setAccessible(true);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            text.append("line ").append(i).append('\n');
        }

        for (Locale l : LOCALES) {
            String out = (String) m.invoke(new ContextCompressionInterceptor(), text.toString(), 3, l);
            Assertions.assertFalse(out.isEmpty(), l + "");
            Assertions.assertTrue(AgentI18n.get(l, AgentI18nKeys.COMPRESS_INTERCEPTOR_TRUNCATED_SHORT).startsWith(out), l + ": " + out);
        }
    }

    @Test
    public void compress_vectorRecallNotFoundAndFailed() throws Throwable {
        RepositoryStorable empty = mock(RepositoryStorable.class);
        when(empty.search(any(QueryCondition.class))).thenReturn(Collections.emptyList());
        RepositoryStorable broken = mock(RepositoryStorable.class);
        when(broken.search(any(QueryCondition.class))).thenThrow(new IOException("down"));

        for (Locale l : LOCALES) {
            assertText(l, AgentI18n.fmt(l, AgentI18nKeys.COMPRESS_VECTOR_RECALL_NOT_FOUND, "query", "q"),
                    new VectorStoreCompressionStrategy(empty).recall(l, "q", 3, "s1"));
            assertText(l, AgentI18n.get(l, AgentI18nKeys.COMPRESS_VECTOR_RECALL_FAILED),
                    new VectorStoreCompressionStrategy(broken).recall(l, "q", 3, "s1"));
        }
    }
}
