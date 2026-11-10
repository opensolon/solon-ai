package features.ai.i18n;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.react.*;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.StopLoopInterceptor;
import org.noear.solon.ai.agent.react.task.PlanTalent;
import org.noear.solon.ai.agent.react.task.ReasonTask;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.util.FeedbackTool;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.prompt.PromptImpl;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.flow.FlowContext;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ReAct 运行时文案（推理修正、计划看板、计划工具、StopLoop、HITL、Feedback）国际化测试
 */
public class ReActRuntimeI18nTest {
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");

    private static void assertNoChinese(String text) {
        assertNotNull(text);
        assertFalse(CJK.matcher(text).find(), "Should not contain Chinese: " + text);
    }

    private static class TestOptions extends ReActOptions {
        private final Locale locale;
        private final boolean planning;

        TestOptions(ChatModel chatModel, Locale locale, boolean planning) {
            super(chatModel);
            this.locale = locale;
            this.planning = planning;
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
        public boolean isPlanningMode() {
            return planning;
        }
    }

    static ReActOptions options(ChatModel chatModel, Locale locale, boolean planning) {
        return new TestOptions(chatModel, locale, planning);
    }

    // ==================== ReasonTask ====================

    static class ReasonEnv {
        ReActTrace trace = mock(ReActTrace.class);
        ReActAgentConfig config = mock(ReActAgentConfig.class);
        ChatModel chatModel = mock(ChatModel.class);
        ChatRequestDesc reqDesc = mock(ChatRequestDesc.class);
        Prompt workingMemory = new PromptImpl();
        AtomicInteger emptyRetryCounter = new AtomicInteger(0);
        ReasonTask task;

        @SuppressWarnings("unchecked")
        ReasonEnv(Locale locale) {
            AgentSession session = mock(AgentSession.class);
            when(trace.getRoute()).thenReturn("reason");
            when(trace.nextTurn()).thenReturn(1);
            when(trace.getWorkingMemory()).thenReturn(workingMemory);
            when(trace.getEmptyRetryCounter()).thenReturn(emptyRetryCounter);
            when(trace.getAgentName()).thenReturn("TestAgent");
            when(trace.getSession()).thenReturn(session);
            when(trace.hasPlans()).thenReturn(false);
            when(trace.getConfig()).thenReturn(config);
            ReActOptions options = options(chatModel, locale, false);
            when(trace.getOptions()).thenReturn(options);
            when(config.getName()).thenReturn("TestAgent");
            when(config.getStyle()).thenReturn(ReActStyle.STRUCTURED_TEXT);
            when(config.getFinishMarker()).thenReturn("[TEST_FINISH]");
            when(chatModel.prompt(anyList())).thenReturn(reqDesc);
            when(reqDesc.options(any(Consumer.class))).thenReturn(reqDesc);
            task = new ReasonTask(config, mock(ReActAgent.class));
        }

        void respond(String json) throws Throwable {
            AssistantMessage message = (AssistantMessage) ChatMessage.fromJson(json);
            ChatResponse resp = mock(ChatResponse.class);
            when(resp.isEmpty()).thenReturn(false);
            when(resp.getMessage()).thenReturn(message);
            when(reqDesc.call()).thenReturn(resp);
            task.run(trace, mock(FlowContext.class));
        }
    }

    @Test
    public void reason_formatFix_en() throws Throwable {
        ReasonEnv env = new ReasonEnv(Locale.ENGLISH);
        env.respond("{\"role\":\"assistant\",\"content\":\"<think>hmm</think>\"}");
        String prompt = env.workingMemory.getLastMessage().getContent();
        assertTrue(prompt.contains("Format Correction"));
        assertTrue(prompt.contains("turn 1") && prompt.contains("attempt 1"));
        assertNoChinese(prompt);
    }

    @Test
    public void reason_reflect_en_and_zh() throws Throwable {
        ReasonEnv en = new ReasonEnv(Locale.ENGLISH);
        en.respond("{\"role\":\"assistant\"}");
        String enPrompt = en.workingMemory.getLastMessage().getContent();
        assertTrue(enPrompt.contains("Self-Reflection"));
        assertNoChinese(enPrompt);

        ReasonEnv zh = new ReasonEnv(Locale.CHINESE);
        zh.respond("{\"role\":\"assistant\"}");
        assertEquals("【系统指令：自我反思 (Self-Reflection)】\n" +
                        "您在第 1 轮推理中返回了空响应（第 1 次尝试），请立即启动自我反思：\n\n" +
                        "1. **回溯任务目标**：重新审视您最初被分配的任务核心目标，检查是否偏离了主线。\n" +
                        "2. **审视历史轨迹**：回顾最近的 Observation 和之前的思考链，定位导致空响应的原因。\n" +
                        "3. **策略修正**：若当前路径受阻，请果断切换思路或拆分为更小的子任务推进。\n",
                zh.workingMemory.getLastMessage().getContent());
    }

    @Test
    public void reason_emptyExhausted_en() throws Throwable {
        ReasonEnv env = new ReasonEnv(Locale.ENGLISH);
        env.emptyRetryCounter.set(2);
        env.respond("{\"role\":\"assistant\"}");
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(env.trace).setFinalAnswer(captor.capture());
        assertNoChinese(captor.getValue());
    }

    @Test
    public void reason_modelError_en_and_zh() throws Throwable {
        ReasonEnv en = new ReasonEnv(Locale.ENGLISH);
        when(en.reqDesc.call()).thenThrow(new IllegalStateException("boom"));
        en.task.run(en.trace, mock(FlowContext.class));
        ArgumentCaptor<String> enCaptor = ArgumentCaptor.forClass(String.class);
        verify(en.trace).setFinalAnswer(enCaptor.capture());
        assertTrue(enCaptor.getValue().startsWith("Sorry"));
        assertNoChinese(enCaptor.getValue());

        ReasonEnv zh = new ReasonEnv(Locale.CHINESE);
        when(zh.reqDesc.call()).thenThrow(new IllegalStateException("boom"));
        zh.task.run(zh.trace, mock(FlowContext.class));
        ArgumentCaptor<String> zhCaptor = ArgumentCaptor.forClass(String.class);
        verify(zh.trace).setFinalAnswer(zhCaptor.capture());
        assertTrue(zhCaptor.getValue().startsWith("抱歉，"));
    }

    @Test
    public void reason_errorMessages_allKeys() {
        String[] keys = {"react.reason.http.rate_limit", "react.reason.http.upstream_unavailable",
                "react.reason.http.server_error", "react.reason.http.timeout", "react.reason.http.other"};
        for (String key : keys) {
            String en = AgentI18n.fmt(Locale.ENGLISH, key, "code", 429);
            assertTrue(en.contains("(CODE: 429)"), en);
            assertNoChinese(en);
            assertTrue(AgentI18n.fmt(Locale.CHINESE, key, "code", 429).contains("（CODE: 429）"));
        }
        assertEquals("抱歉，模型服务请求过于频繁或触发了上游限流（CODE: 429）。请稍后重试，或降低并发数量。",
                AgentI18n.fmt(Locale.CHINESE, "react.reason.http.rate_limit", "code", 429));
    }

    // ==================== PlanContextRenderer ====================

    static ReActTrace planTrace(Locale locale, int planIndex) {
        ReActTrace trace = mock(ReActTrace.class);
        ReActOptions options = options(mock(ChatModel.class), locale, true);
        when(trace.getOptions()).thenReturn(options);
        when(trace.hasPlans()).thenReturn(true);
        when(trace.getPlans()).thenReturn(Arrays.asList("collect", "conclude"));
        when(trace.getPlanIndex()).thenReturn(planIndex);
        return trace;
    }

    @Test
    public void planBoard_zh_unchanged() {
        assertEquals("[执行计划进度看板]\n" +
                        "1. [●] collect\n" +
                        "2. [ ] conclude\n" +
                        "\n**计划进度同步协议 (Plan Sync Protocol)：**\n" +
                        "- **当前状态**: 你正在执行步骤 [1]。\n" +
                        "- **正常推进**: 步骤完成后，若结果符合预期，必须调用 `update_plan_progress` 并将 `next_plan_index` 设为 `2` " +
                        "(切换至下一环节)。\n" +
                        "- **动态调整**: 若观察结果（Observation）显示原计划已不可行，必须优先调用 `revise_plan` 修正后续步骤，严禁强行进入错误环节。\n" +
                        "- **禁止跳步**: 在更新进度前，禁止直接提供最终回答。",
                PlanContextRenderer.render(planTrace(Locale.CHINESE, 0)));

        assertTrue(PlanContextRenderer.render(planTrace(Locale.CHINESE, 1)).contains("(标志所有计划已达成)。\n"));
        assertTrue(PlanContextRenderer.render(planTrace(Locale.CHINESE, 2)).endsWith("直接给出最终的详细回答。"));
    }

    @Test
    public void planBoard_en() {
        for (int i = 0; i <= 2; i++) {
            String board = PlanContextRenderer.render(planTrace(Locale.ENGLISH, i));
            assertTrue(board.startsWith("[Execution Plan Progress Board]\n"));
            assertNoChinese(board);
        }
        assertTrue(PlanContextRenderer.render(planTrace(Locale.ENGLISH, 0)).contains("`next_plan_index` set to `2` (moving"));
    }

    // ==================== PlanTalent ====================

    static PlanTalent planTalent(Locale locale) {
        ReActTrace trace = mock(ReActTrace.class);
        ReActOptions options = options(mock(ChatModel.class), locale, true);
        when(trace.getOptions()).thenReturn(options);
        List<String>[] plans = new List[1];
        doAnswer(inv -> {
            plans[0] = inv.getArgument(0);
            return null;
        }).when(trace).setPlans(any());
        when(trace.getPlans()).thenAnswer(inv -> plans[0]);
        return new PlanTalent(trace);
    }

    @Test
    public void planTalent_zh_toolsUnchanged() throws Throwable {
        PlanTalent talent = planTalent(Locale.CHINESE);
        Map<String, FunctionTool> tools = talent.getToolMap();
        assertEquals(Arrays.asList("create_plan", "update_plan_progress", "revise_plan"), new ArrayList<>(tools.keySet()));

        assertEquals("初始化执行计划。这是处理复杂任务的第一步，用于拆解后续所有行动步骤。", tools.get("create_plan").description());
        assertEquals("{\"type\":\"object\",\"properties\":{\"steps\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"结构化的步骤列表（每步应为一个独立的动作描述）。\"}},\"required\":[\"steps\"]}",
                tools.get("create_plan").inputSchema());
        assertEquals("{\"type\":\"object\",\"properties\":{\"next_plan_index\":{\"type\":\"integer\",\"description\":\"下一个步骤索引（从 1 开始计数的数字）。\"}},\"required\":[\"next_plan_index\"]}",
                tools.get("update_plan_progress").inputSchema());
        assertEquals("{\"type\":\"object\",\"properties\":{\"from_index\":{\"type\":\"integer\",\"description\":\"从哪一步开始替换（从 1 开始）。\"},\"new_steps\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"新的后续计划步骤列表。\"}},\"required\":[\"new_steps\",\"from_index\"]}",
                tools.get("revise_plan").inputSchema());
        assertEquals("", tools.get("revise_plan").outputSchema());
        assertFalse(tools.get("revise_plan").returnDirect());

        Map<String, Object> args = new HashMap<>();
        args.put("steps", Arrays.asList("1. a", "2. b"));
        assertEquals("成功：计划已初始化，共 2 步。请开始执行第一步。", tools.get("create_plan").handle(args));

        args = new HashMap<>();
        args.put("next_plan_index", 2);
        assertEquals("成功：计划进度已更新。下一步是：第 2 步 - b", tools.get("update_plan_progress").handle(args));

        args = new HashMap<>();
        args.put("new_steps", Collections.singletonList("c"));
        args.put("from_index", "2");
        assertEquals("成功：计划已从第 2 步开始重构。请按照新计划继续执行。", tools.get("revise_plan").handle(args));

        assertEquals("提供复杂任务的拆解、进度跟踪及计划修订能力。适用于需要多步协作的长链路任务。", talent.description());
        assertTrue(talent.getInstruction(null).startsWith("## 任务规划指南 (Task Planning Guide)\n"));
    }

    @Test
    public void planTalent_en() throws Throwable {
        PlanTalent talent = planTalent(Locale.ENGLISH);
        for (FunctionTool tool : talent.getToolAry()) {
            assertNoChinese(tool.description());
            assertNoChinese(tool.inputSchema());
        }
        assertNoChinese(talent.description());
        assertNoChinese(talent.getInstruction(null));

        assertNoChinese(talent.update_plan_progress(1));
        assertNoChinese(talent.revise_plan(Collections.singletonList("x"), 1));
        assertNoChinese(talent.create_plan(Collections.singletonList("only")));
        assertNoChinese(talent.create_plan(Arrays.asList("a", "b")));
        assertNoChinese(talent.update_plan_progress(2));
        assertNoChinese(talent.update_plan_progress(3));
        assertNoChinese(talent.revise_plan(Collections.emptyList(), 1));
        assertNoChinese(talent.revise_plan(Collections.singletonList("c"), 2));
    }

    @Test
    public void planTalent_invalidIndexIsIllegalArgument() throws Throwable {
        // 非法索引在参数转换时抛 IllegalArgumentException，ActionTask 会把它转为带 schema 的纠正提示（不会中断推理）
        PlanTalent talent = planTalent(Locale.CHINESE);
        Map<String, Object> args = new HashMap<>();
        args.put("next_plan_index", "第2步");
        assertThrows(IllegalArgumentException.class, () -> talent.getToolMap().get("update_plan_progress").handle(args));
    }

    // ==================== StopLoopInterceptor ====================

    private static String stopLoopPrompt(Locale locale) {
        ReActTrace trace = mock(ReActTrace.class);
        Map<String, Object> extras = new HashMap<>();
        Prompt workingMemory = new PromptImpl();
        when(trace.getExtraAs(anyString())).thenAnswer(inv -> extras.get(inv.getArgument(0)));
        when(trace.getExtras()).thenReturn(extras);
        doAnswer(inv -> {
            extras.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(trace).setExtra(anyString(), any());
        when(trace.getAgentName()).thenReturn("TestAgent");
        when(trace.getSession()).thenReturn(InMemoryAgentSession.of());
        when(trace.getWorkingMemory()).thenReturn(workingMemory);
        ReActOptions options = options(mock(ChatModel.class), locale, false);
        when(trace.getOptions()).thenReturn(options);

        StopLoopInterceptor interceptor = new StopLoopInterceptor();
        for (int i = 0; i < 3; i++) {
            ToolCall call = new ToolCall("0", "call-" + i, "get_weather", "{}", Collections.emptyMap());
            AssistantMessage message = new AssistantMessage("call", "", Collections.singletonList(call), null);
            interceptor.onReasonEnd(trace, null, message, 0L);
            interceptor.onActionEnd(trace, Collections.emptyList());
        }
        return workingMemory.getLastMessage().getContent();
    }

    @Test
    public void stopLoop_en_and_zh() {
        String en = stopLoopPrompt(Locale.ENGLISH);
        assertTrue(en.contains("Loop Detected"));
        assertNoChinese(en);

        String zh = stopLoopPrompt(Locale.CHINESE);
        assertTrue(zh.startsWith("【系统提示：检测到潜在循环 (Loop Detected)】\n系统检测到最近 "));
        assertTrue(zh.contains("可能存在\"原地打转\"的情况。\n\n"));
    }

    // ==================== HITL / Feedback ====================

    @Test
    public void hitl_en_and_zh() {
        ReActTrace en = mock(ReActTrace.class);
        ReActOptions enOptions = options(mock(ChatModel.class), Locale.ENGLISH, false);
        when(en.getOptions()).thenReturn(enOptions);
        HITLInterceptor.HITLSensitiveStrategy strategy = new HITLInterceptor.HITLSensitiveStrategy();
        assertNoChinese(strategy.evaluate(en, Collections.emptyMap()));

        ReActTrace zh = mock(ReActTrace.class);
        ReActOptions zhOptions = options(mock(ChatModel.class), Locale.CHINESE, false);
        when(zh.getOptions()).thenReturn(zhOptions);
        assertEquals("敏感操作，需要人工介入确认", strategy.evaluate(zh, Collections.emptyMap()));
        assertEquals("敏感操作，需要人工介入确认", strategy.evaluate(mock(ReActTrace.class), Collections.emptyMap()));

        assertNoChinese(AgentI18n.fmt(Locale.ENGLISH, "react.hitl.pending_batch", "count", 2));
        assertNoChinese(AgentI18n.get(Locale.ENGLISH, "react.hitl.skipped"));
        assertNoChinese(AgentI18n.get(Locale.ENGLISH, "react.hitl.rejected"));
        assertEquals("有 2 项操作待人工确认", AgentI18n.fmt(Locale.CHINESE, "react.hitl.pending_batch", "count", 2));
        assertEquals("操作跳过：请继续下一步。", AgentI18n.get(Locale.CHINESE, "react.hitl.skipped"));
        assertEquals("操作拒绝：人工审批未通过。", AgentI18n.get(Locale.CHINESE, "react.hitl.rejected"));
    }

    @Test
    public void feedback_en_and_zh() {
        FunctionTool zh = FeedbackTool.getTool(null, null);
        assertEquals(FeedbackTool.TOOL_DESCRIPTION_DEF, zh.description());
        assertTrue(zh.inputSchema().contains(FeedbackTool.TOOL_REASON_DESCRIPTION_DEF));

        FunctionTool en = FeedbackTool.getTool(null, null, Locale.ENGLISH);
        assertEquals(FeedbackTool.TOOL_NAME, en.name());
        assertNoChinese(en.description());
        assertNoChinese(en.inputSchema());

        assertEquals("custom", FeedbackTool.getTool("custom", null, Locale.ENGLISH).description());
    }
}
