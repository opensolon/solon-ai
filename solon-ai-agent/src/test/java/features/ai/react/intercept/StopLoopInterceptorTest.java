package features.ai.react.intercept;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.StopLoopInterceptor;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.prompt.PromptImpl;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * StopLoopInterceptor 单元测试。
 */
public class StopLoopInterceptorTest {
    private ReActTrace trace;
    private Map<String, Object> extras;
    private Prompt workingMemory;
    private StopLoopInterceptor interceptor;

    @BeforeEach
    public void setUp() {
        trace = mock(ReActTrace.class);
        extras = new HashMap<>();
        workingMemory = new PromptImpl();
        AgentSession session = InMemoryAgentSession.of();

        when(trace.getExtraAs(anyString())).thenAnswer(inv -> extras.get(inv.getArgument(0)));
        when(trace.getExtras()).thenReturn(extras);
        doAnswer(inv -> {
            extras.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(trace).setExtra(anyString(), any());
        when(trace.getAgentName()).thenReturn("TestAgent");
        when(trace.getSession()).thenReturn(session);
        when(trace.getWorkingMemory()).thenReturn(workingMemory);

        interceptor = new StopLoopInterceptor(3, 6);
    }

    @Test
    @DisplayName("默认配置在第三次相同动作后提醒")
    public void testDefaultThreshold() {
        StopLoopInterceptor target = new StopLoopInterceptor();
        AssistantMessage message = toolCall("get_weather", "{}");

        record(target, message);
        record(target, message);
        assertTrue(workingMemory.isEmpty());

        record(target, message);
        assertTrue(workingMemory.getLastMessage().getContent().contains("Loop Detected"));
    }

    @Test
    @DisplayName("构造参数具有下限，窗口会扩展到重复阈值")
    public void testConstructorBoundaries() {
        StopLoopInterceptor minimum = new StopLoopInterceptor(1, 2);
        AssistantMessage message = toolCall("search", "{}");
        record(minimum, message);
        assertTrue(workingMemory.isEmpty());
        record(minimum, message);
        assertFalse(workingMemory.isEmpty());

        extras.clear();
        workingMemory = new PromptImpl();
        when(trace.getWorkingMemory()).thenReturn(workingMemory);
        StopLoopInterceptor expanded = new StopLoopInterceptor(10, 4);
        for (int i = 0; i < 9; i++) {
            record(expanded, message);
        }
        assertTrue(workingMemory.isEmpty(), "不应把阈值 10 静默降低为 4");
        record(expanded, message);
        assertFalse(workingMemory.isEmpty());
    }

    @Test
    @DisplayName("空消息、普通终态文本和禁用状态不参与检测")
    public void testIgnoredInputs() {
        assertDoesNotThrow(() -> record(interceptor, null));
        record(interceptor, new AssistantMessage("Final answer"));
        record(interceptor, new AssistantMessage("Final answer"));
        record(interceptor, new AssistantMessage("Final answer"));
        assertFalse(extras.containsKey("stoploop_history"));

        interceptor.setEnabled(false);
        AssistantMessage message = toolCall("read", "{\"file\":\"A\"}");
        record(interceptor, message);
        record(interceptor, message);
        record(interceptor, message);
        assertFalse(extras.containsKey("stoploop_history"));
        assertTrue(workingMemory.isEmpty());
    }

    @Test
    @DisplayName("空工具项异常时放行主执行链")
    public void testNullToolCallFailsOpen() {
        AssistantMessage message = new AssistantMessage("call", "",
                Arrays.asList((ToolCall) null), null);

        assertDoesNotThrow(() -> record(interceptor, message));
        assertFalse(extras.containsKey("stoploop_history"));
    }

    @Test
    @DisplayName("工具名和参数完全相同时触发")
    public void testNativeToolLoop() {
        AssistantMessage message = toolCall("read", "{\"file\":\"A\"}");
        record(interceptor, message);
        record(interceptor, message);
        record(interceptor, message);

        assertEquals(1, workingMemory.getMessages().size());
        assertTrue(workingMemory.getLastMessage().getContent().contains("Loop Detected"));
    }

    @Test
    @DisplayName("相同工具的不同参数不误报")
    public void testDifferentNativeArguments() {
        record(interceptor, toolCall("read", "{\"file\":\"A\"}"));
        record(interceptor, toolCall("read", "{\"file\":\"B\"}"));
        record(interceptor, toolCall("read", "{\"file\":\"A\"}"));

        assertTrue(workingMemory.isEmpty());
    }

    @Test
    @DisplayName("参数对象键顺序不影响指纹")
    public void testNativeArgumentsAreCanonicalized() {
        AssistantMessage first = toolCall("read", "{\"file\":\"A\",\"offset\":1}");
        AssistantMessage reordered = toolCall("read", "{\"offset\":1,\"file\":\"A\"}");

        record(interceptor, first);
        record(interceptor, reordered);
        record(interceptor, first);

        assertFalse(workingMemory.isEmpty());
    }

    @Test
    @DisplayName("非法参数和多个 JSON 根按原文区分")
    public void testMalformedNativeArgumentsRemainDistinct() {
        AssistantMessage malformedA = rawToolCall("read", "{\"file\":\"A");
        AssistantMessage malformedB = rawToolCall("read", "{\"file\":\"B");
        record(interceptor, malformedA);
        record(interceptor, malformedB);
        record(interceptor, malformedA);
        assertTrue(workingMemory.isEmpty());

        extras.clear();
        AssistantMessage rootsA = rawToolCall("read", "{\"file\":\"A\"}{\"same\":1}");
        AssistantMessage rootsB = rawToolCall("read", "{\"file\":\"B\"}{\"same\":1}");
        record(interceptor, rootsA);
        record(interceptor, rootsB);
        record(interceptor, rootsA);
        assertTrue(workingMemory.isEmpty());
    }

    @Test
    @DisplayName("复合工具调用的完整有序序列参与指纹")
    public void testMultipleNativeToolsUseWholeSequence() {
        AssistantMessage first = multipleToolCalls("A", "B");
        AssistantMessage secondArgumentChanged = multipleToolCalls("A", "C");

        record(interceptor, first);
        record(interceptor, secondArgumentChanged);
        record(interceptor, first);
        assertTrue(workingMemory.isEmpty());

        record(interceptor, first);
        assertFalse(workingMemory.isEmpty());
    }

    @Test
    @DisplayName("文本 Action 的 name 和 arguments 定义动作身份")
    public void testTextActionIdentity() {
        AssistantMessage id1 = textAction("read", "{\"id\":1}", "");
        AssistantMessage id2 = textAction("read", "{\"id\":2}", "");

        record(interceptor, id1);
        record(interceptor, id2);
        record(interceptor, id1);
        assertTrue(workingMemory.isEmpty());

        record(interceptor, id1);
        assertFalse(workingMemory.isEmpty());
    }

    @Test
    @DisplayName("文本 Action 忽略附加字段并规范化空参数")
    public void testTextActionMatchesExecutionSemantics() {
        AssistantMessage extra1 = textAction("read", "{\"file\":\"A\"}", ",\"requestId\":\"1\"");
        AssistantMessage extra2 = textAction("read", "{\"file\":\"A\"}", ",\"requestId\":\"2\"");
        record(interceptor, extra1);
        record(interceptor, extra2);
        record(interceptor, extra1);
        assertFalse(workingMemory.isEmpty(), "执行器不消费的字段不应改变指纹");

        extras.clear();
        workingMemory = new PromptImpl();
        when(trace.getWorkingMemory()).thenReturn(workingMemory);
        record(interceptor, new AssistantMessage("Action: {\"name\":\"read\"}"));
        record(interceptor, new AssistantMessage("Action: {\"name\":\"read\",\"arguments\":null}"));
        record(interceptor, new AssistantMessage("Action: {\"name\":\"read\",\"arguments\":1}"));
        assertFalse(workingMemory.isEmpty(), "非对象 arguments 都会被执行器解释为空参数");
    }

    @Test
    @DisplayName("文本模式的多个 Action 整体参与指纹")
    public void testMultipleTextActionsUseWholeSequence() {
        AssistantMessage first = new AssistantMessage("Action: "
                + actionJson("read", "{\"file\":\"A\"}", "")
                + actionJson("search", "{\"q\":\"same\"}", ""));
        AssistantMessage changed = new AssistantMessage("Action: "
                + actionJson("write", "{\"file\":\"B\"}", "")
                + actionJson("search", "{\"q\":\"same\"}", ""));

        record(interceptor, first);
        record(interceptor, changed);
        record(interceptor, first);
        assertTrue(workingMemory.isEmpty());
    }

    @Test
    @DisplayName("循环提醒在本轮 Observation 之后注入且不截断动作")
    public void testPromptIsAppendedAfterObservation() {
        AssistantMessage message = toolCall("get_weather", "{}");
        record(interceptor, message);
        record(interceptor, message);
        interceptor.onReasonEnd(trace, null, message, 0L);

        assertTrue(workingMemory.isEmpty());
        workingMemory.addMessage(message);
        workingMemory.addMessage(ChatMessage.ofUser("Observation: sunny"));
        interceptor.onActionEnd(trace, Collections.emptyList());

        assertEquals("Observation: sunny", workingMemory.getMessages().get(1).getContent());
        assertTrue(workingMemory.getLastMessage().getContent().contains("Loop Detected"));
        verify(trace, never()).setFinalAnswer(anyString());
        verify(trace, never()).setRoute(anyString());
        assertFalse(trace.getSession().isPending());
    }

    @Test
    @DisplayName("END 路由和后续轮次丢弃待提醒")
    public void testPendingPromptIsDiscarded() {
        AssistantMessage message = toolCall("get_weather", "{}");
        triggerPending(message, 1);
        when(trace.getRoute()).thenReturn(Agent.ID_END);
        interceptor.onActionEnd(trace, Collections.emptyList());
        assertPendingCleared();
        assertTrue(workingMemory.isEmpty());

        resetLoopState();
        triggerPending(message, 1);
        when(trace.getTurnCount()).thenReturn(2);
        when(trace.getRoute()).thenReturn(null);
        interceptor.onActionEnd(trace, Collections.emptyList());
        assertPendingCleared();
        assertTrue(workingMemory.isEmpty());
    }

    @Test
    @DisplayName("Agent 结束时清理未消费的提醒")
    public void testAgentEndClearsPendingPrompt() {
        triggerPending(toolCall("get_weather", "{}"), 1);
        interceptor.onAgentEnd(trace);
        assertPendingCleared();
    }

    @Test
    @DisplayName("滑动窗口只统计最近动作")
    public void testSlidingWindow() {
        StopLoopInterceptor target = new StopLoopInterceptor(3, 4);
        record(target, toolCall("a", "{}"));
        record(target, toolCall("b", "{}"));
        record(target, toolCall("c", "{}"));
        record(target, toolCall("a", "{}"));
        record(target, toolCall("a", "{}"));
        assertTrue(workingMemory.isEmpty(), "最早的 a 已被窗口淘汰");

        record(target, toolCall("a", "{}"));
        assertFalse(workingMemory.isEmpty());
    }

    private void triggerPending(AssistantMessage message, int turn) {
        when(trace.getTurnCount()).thenReturn(turn);
        record(interceptor, message);
        record(interceptor, message);
        interceptor.onReasonEnd(trace, null, message, 0L);
    }

    private void resetLoopState() {
        extras.clear();
        workingMemory = new PromptImpl();
        when(trace.getWorkingMemory()).thenReturn(workingMemory);
    }

    private void assertPendingCleared() {
        assertFalse(extras.containsKey("stoploop_pending_prompt"));
        assertFalse(extras.containsKey("stoploop_pending_turn"));
    }

    private void record(StopLoopInterceptor target, AssistantMessage message) {
        target.onReasonEnd(trace, null, message, 0L);
        target.onActionEnd(trace, Collections.emptyList());
    }

    private AssistantMessage toolCall(String toolName, String arguments) {
        return rawToolCall(toolName, arguments);
    }

    private AssistantMessage rawToolCall(String toolName, String arguments) {
        ToolCall call = new ToolCall("0", "call-" + System.nanoTime(), toolName,
                arguments, Collections.emptyMap());
        return new AssistantMessage("call", "", Collections.singletonList(call), null);
    }

    private AssistantMessage multipleToolCalls(String firstFile, String secondFile) {
        ToolCall first = new ToolCall("0", "call-1", "read",
                "{\"file\":\"" + firstFile + "\"}", Collections.emptyMap());
        ToolCall second = new ToolCall("1", "call-2", "read",
                "{\"file\":\"" + secondFile + "\"}", Collections.emptyMap());
        return new AssistantMessage("call", "", Arrays.asList(first, second), null);
    }

    private AssistantMessage textAction(String name, String arguments, String extraFields) {
        return new AssistantMessage("Thought: work\nAction: " + actionJson(name, arguments, extraFields));
    }

    private String actionJson(String name, String arguments, String extraFields) {
        return "{\"name\":\"" + name + "\",\"arguments\":" + arguments + extraFields + "}";
    }
}
