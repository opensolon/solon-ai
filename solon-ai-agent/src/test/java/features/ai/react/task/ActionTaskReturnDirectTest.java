/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package features.ai.react.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActAgentConfig;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ActionTask;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.prompt.PromptImpl;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.flow.FlowContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ActionTask returnDirect 语义单元测试
 *
 * <p>验证：工具声明 returnDirect=true 且成功时，结果直接作为 FinalAnswer 结束；
 * 混合/失败/未声明时，继续 Observation → Reason 循环。</p>
 */
public class ActionTaskReturnDirectTest {

    private ActionTask actionTask;
    private ReActTrace trace;
    private ReActAgentConfig config;
    private FlowContext context;
    private PromptImpl workingMemory;
    private AgentSession session;
    private ReActOptions options;
    private AtomicInteger toolCallCount;

    @BeforeEach
    public void setUp() {
        config = mock(ReActAgentConfig.class);
        when(config.getName()).thenReturn("ReturnDirectAgent");

        actionTask = new ActionTask(config);

        chatModelSetup();
        workingMemory = new PromptImpl();
        session = mock(AgentSession.class);
        when(session.isPending()).thenReturn(false);

        context = mock(FlowContext.class);
        toolCallCount = new AtomicInteger(0);

        trace = mock(ReActTrace.class);
        when(trace.getOptions()).thenReturn(options);
        when(trace.getWorkingMemory()).thenReturn(workingMemory);
        when(trace.getSession()).thenReturn(session);
        when(trace.getContext()).thenReturn(context);
        when(trace.getAgentName()).thenReturn("ReturnDirectAgent");
        when(trace.hasStreamSink()).thenReturn(false);
        when(trace.getProtocolTool(anyString())).thenReturn(null);
        doAnswer(inv -> {
            toolCallCount.incrementAndGet();
            return null;
        }).when(trace).incrementToolCallCount();

        // route 可变状态
        final String[] routeHolder = {ReActAgent.ID_REASON};
        when(trace.getRoute()).thenAnswer(inv -> routeHolder[0]);
        doAnswer(inv -> {
            routeHolder[0] = inv.getArgument(0);
            return null;
        }).when(trace).setRoute(anyString());

        // finalAnswer 可变状态
        final String[] answerHolder = {null};
        final Boolean[] abnormalHolder = {null};
        when(trace.getFinalAnswer()).thenAnswer(inv -> answerHolder[0]);
        doAnswer(inv -> {
            answerHolder[0] = inv.getArgument(0);
            abnormalHolder[0] = true;
            return null;
        }).when(trace).setFinalAnswer(anyString());
        doAnswer(inv -> {
            answerHolder[0] = inv.getArgument(0);
            abnormalHolder[0] = inv.getArgument(1);
            return null;
        }).when(trace).setFinalAnswer(anyString(), anyBoolean());
    }

    private void chatModelSetup() {
        ChatModel chatModel = mock(ChatModel.class);
        options = new ReActOptions(chatModel);
    }

    private AssistantMessage nativeToolCallMessage(String toolName, Map<String, Object> args) {
        ToolCall call = new ToolCall("0", "call_" + toolName, toolName, null, args);
        return new AssistantMessage("", false, null, null, Collections.singletonList(call), null);
    }

    private AssistantMessage nativeMultiToolCallMessage(List<ToolCall> calls) {
        return new AssistantMessage("", false, null, null, calls, null);
    }

    private AssistantMessage textActionMessage(String toolName, Map<String, Object> args) {
        StringBuilder jsonArgs = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (!first) {
                jsonArgs.append(',');
            }
            first = false;
            jsonArgs.append('"').append(e.getKey()).append("\":");
            if (e.getValue() instanceof Number || e.getValue() instanceof Boolean) {
                jsonArgs.append(e.getValue());
            } else {
                jsonArgs.append('"').append(e.getValue()).append('"');
            }
        }
        jsonArgs.append('}');

        String content = "Thought: use tool\nAction: {\"name\":\"" + toolName + "\",\"arguments\":" + jsonArgs + "}";
        return new AssistantMessage(content);
    }

    @Test
    @DisplayName("单工具 returnDirect=true 成功：直接 FinalAnswer 并 END")
    public void testSingleReturnDirect_native() throws Throwable {
        options.getModelOptions().toolAdd(new FunctionToolDesc("getWeather")
                .returnDirect(true)
                .description("查询天气")
                .stringParamAdd("city", "城市")
                .doHandle(args -> "晴天 25℃"));

        Map<String, Object> args = new HashMap<>();
        args.put("city", "杭州");
        AssistantMessage reason = nativeToolCallMessage("getWeather", args);
        when(trace.getLastReasonMessage()).thenReturn(reason);

        actionTask.run(trace, context);

        assertEquals(Agent.ID_END, trace.getRoute());
        assertEquals("晴天 25℃", trace.getFinalAnswer());
        assertEquals(1, toolCallCount.get());
        assertTrue(workingMemory.getMessages().size() >= 2, "应写入 assistant + tool 成套消息");
        assertTrue(workingMemory.getMessages().stream().anyMatch(m -> m instanceof ToolMessage));
        ToolMessage tm = (ToolMessage) workingMemory.getMessages().stream()
                .filter(m -> m instanceof ToolMessage)
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertTrue(tm.isReturnDirect());
        assertEquals("晴天 25℃", tm.getContent());
    }

    @Test
    @DisplayName("单工具 returnDirect=false：不结束，路由保持 Reason")
    public void testReturnDirectFalse_staysOnReason() throws Throwable {
        options.getModelOptions().toolAdd(new FunctionToolDesc("getWeather")
                .returnDirect(false)
                .description("查询天气")
                .stringParamAdd("city", "城市")
                .doHandle(args -> "晴天 25℃"));

        Map<String, Object> args = new HashMap<>();
        args.put("city", "杭州");
        when(trace.getLastReasonMessage()).thenReturn(nativeToolCallMessage("getWeather", args));

        actionTask.run(trace, context);

        assertEquals(ReActAgent.ID_REASON, trace.getRoute());
        assertNull(trace.getFinalAnswer());
        assertEquals(1, toolCallCount.get());
        assertFalse(workingMemory.getMessages().isEmpty());
    }

    @Test
    @DisplayName("文本模式 returnDirect=true：同样直接结束")
    public void testSingleReturnDirect_textMode() throws Throwable {
        options.getModelOptions().toolAdd(new FunctionToolDesc("getWeather")
                .returnDirect(true)
                .description("查询天气")
                .stringParamAdd("city", "城市")
                .doHandle(args -> "多云 20℃"));

        Map<String, Object> args = new HashMap<>();
        args.put("city", "上海");
        when(trace.getLastReasonMessage()).thenReturn(textActionMessage("getWeather", args));

        actionTask.run(trace, context);

        assertEquals(Agent.ID_END, trace.getRoute());
        assertEquals("多云 20℃", trace.getFinalAnswer());
        assertEquals(1, toolCallCount.get());
    }

    @Test
    @DisplayName("多工具全部 returnDirect=true：拼接结果并 END")
    public void testMultiAllReturnDirect() throws Throwable {
        options.getModelOptions().toolAdd(new FunctionToolDesc("getWeather")
                .returnDirect(true)
                .description("天气")
                .doHandle(args -> "晴"));
        options.getModelOptions().toolAdd(new FunctionToolDesc("getTemp")
                .returnDirect(true)
                .description("温度")
                .doHandle(args -> "25℃"));

        List<ToolCall> calls = java.util.Arrays.asList(
                new ToolCall("0", "c1", "getWeather", null, Collections.emptyMap()),
                new ToolCall("1", "c2", "getTemp", null, Collections.emptyMap())
        );
        when(trace.getLastReasonMessage()).thenReturn(nativeMultiToolCallMessage(calls));

        actionTask.run(trace, context);

        assertEquals(Agent.ID_END, trace.getRoute());
        assertEquals("晴\n25℃", trace.getFinalAnswer());
        assertEquals(2, toolCallCount.get());
    }

    @Test
    @DisplayName("多工具混合 returnDirect：有一个 false 则不直返")
    public void testMultiMixedReturnDirect_notEnd() throws Throwable {
        options.getModelOptions().toolAdd(new FunctionToolDesc("getWeather")
                .returnDirect(true)
                .description("天气")
                .doHandle(args -> "晴"));
        options.getModelOptions().toolAdd(new FunctionToolDesc("analyze")
                .returnDirect(false)
                .description("分析")
                .doHandle(args -> "需要再推理"));

        List<ToolCall> calls = java.util.Arrays.asList(
                new ToolCall("0", "c1", "getWeather", null, Collections.emptyMap()),
                new ToolCall("1", "c2", "analyze", null, Collections.emptyMap())
        );
        when(trace.getLastReasonMessage()).thenReturn(nativeMultiToolCallMessage(calls));

        actionTask.run(trace, context);

        assertEquals(ReActAgent.ID_REASON, trace.getRoute());
        assertNull(trace.getFinalAnswer());
        assertEquals(2, toolCallCount.get());
    }

    @Test
    @DisplayName("returnDirect=true 但工具执行失败（异常吞为 observation）：不直返")
    public void testReturnDirect_toolExecutionError_notEnd() throws Throwable {
        options.getModelOptions().toolAdd(new FunctionToolDesc("getWeather")
                .returnDirect(true)
                .description("天气")
                .doHandle(args -> {
                    throw new RuntimeException("upstream timeout");
                }));

        when(trace.getLastReasonMessage()).thenReturn(
                nativeToolCallMessage("getWeather", Collections.emptyMap()));

        actionTask.run(trace, context);

        assertEquals(ReActAgent.ID_REASON, trace.getRoute());
        assertNull(trace.getFinalAnswer());
        // 异常在 incrementToolCallCount 之前被 catch，不计入成功调用次数
        assertEquals(0, toolCallCount.get());
        assertTrue(workingMemory.getMessages().stream()
                .anyMatch(m -> m.getContent() != null && m.getContent().contains("Execution error")));
    }

    @Test
    @DisplayName("工具不存在：不直返")
    public void testToolNotFound_notEnd() throws Throwable {
        when(trace.getLastReasonMessage()).thenReturn(
                nativeToolCallMessage("missing_tool", Collections.emptyMap()));

        actionTask.run(trace, context);

        assertEquals(ReActAgent.ID_REASON, trace.getRoute());
        assertNull(trace.getFinalAnswer());
        assertEquals(0, toolCallCount.get());
    }

    @Test
    @DisplayName("Feedback 工具：仍走原有结束语义，且不依赖通用 returnDirect 汇总")
    public void testFeedbackTool_stillEnds() throws Throwable {
        Map<String, Object> args = new HashMap<>();
        args.put("reason", "缺少用户手机号");
        when(trace.getLastReasonMessage()).thenReturn(
                nativeToolCallMessage(org.noear.solon.ai.agent.util.FeedbackTool.TOOL_NAME, args));

        actionTask.run(trace, context);

        assertEquals(Agent.ID_END, trace.getRoute());
        assertEquals("缺少用户手机号", trace.getFinalAnswer());
        verify(context, times(1)).interrupt();
    }
}
