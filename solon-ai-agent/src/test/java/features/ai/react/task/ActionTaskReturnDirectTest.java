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
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.prompt.PromptImpl;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.flow.FlowContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ActionTask returnDirect 语义单元测试
 *
 * <p>验证：工具声明 returnDirect=true 且成功时，结果直接作为 FinalAnswer 结束；
 * 混合/失败/未声明时，继续 Observation → Reason 循环。
 * 富结果（media / isError）在 observation 与终态路径上不被压扁。</p>
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
    /**
     * null=未调用 setFinalAnswer；true/false=abnormal 标记
     */
    private AtomicReference<Boolean> finalAnswerAbnormal;
    private AtomicReference<AssistantMessage> lastReasonHolder;
    private AtomicReference<List<ContentBlock>> finalMediaHolder;

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
        finalAnswerAbnormal = new AtomicReference<>(null);
        lastReasonHolder = new AtomicReference<>(null);
        finalMediaHolder = new AtomicReference<>(null);

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

        // finalAnswer 可变状态（对齐 ReActTrace：单参→abnormal=true；双参→显式 abnormal）
        final String[] answerHolder = {null};
        when(trace.getFinalAnswer()).thenAnswer(inv -> answerHolder[0]);
        doAnswer(inv -> {
            answerHolder[0] = inv.getArgument(0);
            finalAnswerAbnormal.set(true);
            return null;
        }).when(trace).setFinalAnswer(anyString());
        doAnswer(inv -> {
            answerHolder[0] = inv.getArgument(0);
            finalAnswerAbnormal.set(inv.getArgument(1));
            return null;
        }).when(trace).setFinalAnswer(anyString(), anyBoolean());

        // lastReason 可变
        when(trace.getLastReasonMessage()).thenAnswer(inv -> lastReasonHolder.get());
        doAnswer(inv -> {
            lastReasonHolder.set(inv.getArgument(0));
            return null;
        }).when(trace).setLastReasonMessage(any());

        // finalMediaBlocks 可变：returnDirect 工具 media 写到这里（不再挂 lastReason）
        when(trace.getFinalMediaBlocks()).thenAnswer(inv -> finalMediaHolder.get());
        doAnswer(inv -> {
            finalMediaHolder.set(inv.getArgument(0));
            return null;
        }).when(trace).setFinalMediaBlocks(any());
    }

    private void chatModelSetup() {
        ChatModel chatModel = mock(ChatModel.class);
        options = new ReActOptions(chatModel);
    }

    private AssistantMessage nativeToolCallMessage(String toolName, Map<String, Object> args) {
        ToolCall call = new ToolCall("0", "call_" + toolName, toolName, null, args);
        return new AssistantMessage("", "", false, null, null, Collections.singletonList(call), null);
    }

    private AssistantMessage nativeMultiToolCallMessage(List<ToolCall> calls) {
        return new AssistantMessage("", "", false, null, null, calls, null);
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

    private void setLastReason(AssistantMessage reason) {
        lastReasonHolder.set(reason);
    }

    @Test
    @DisplayName("单工具 returnDirect=true 成功：直接 FinalAnswer 并 END，且 abnormal=false")
    public void testSingleReturnDirect_native() throws Throwable {
        options.getModelOptions().toolAdd(new FunctionToolDesc("getWeather")
                .returnDirect(true)
                .description("查询天气")
                .stringParamAdd("city", "城市")
                .doHandle(args -> "晴天 25℃"));

        Map<String, Object> args = new HashMap<>();
        args.put("city", "杭州");
        AssistantMessage reason = nativeToolCallMessage("getWeather", args);
        setLastReason(reason);

        actionTask.run(trace, context);

        assertEquals(Agent.ID_END, trace.getRoute());
        assertEquals("晴天 25℃", trace.getFinalAnswer());
        // 业务直返必须走双参 setFinalAnswer(content, false)，不能误标 abnormal
        assertEquals(Boolean.TRUE, finalAnswerAbnormal.get(), "业务 returnDirect 成功应为 abnormal=false");
        verify(trace, times(1)).setFinalAnswer(eq("晴天 25℃"), eq(false));
        verify(trace, never()).setFinalAnswer(anyString()); // 单参会强制 abnormal=true
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
        setLastReason(nativeToolCallMessage("getWeather", args));

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
        setLastReason(textActionMessage("getWeather", args));

        actionTask.run(trace, context);

        assertEquals(Agent.ID_END, trace.getRoute());
        assertEquals("多云 20℃", trace.getFinalAnswer());
        assertEquals(1, toolCallCount.get());
    }

    @Test
    @DisplayName("多工具全部 returnDirect=true：拼接结果并 END，abnormal=false")
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
        setLastReason(nativeMultiToolCallMessage(calls));

        actionTask.run(trace, context);

        assertEquals(Agent.ID_END, trace.getRoute());
        assertEquals("晴\n25℃", trace.getFinalAnswer());
        assertEquals(Boolean.TRUE, finalAnswerAbnormal.get());
        verify(trace).setFinalAnswer(eq("晴\n25℃"), eq(false));
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
        setLastReason(nativeMultiToolCallMessage(calls));

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

        setLastReason(nativeToolCallMessage("getWeather", Collections.emptyMap()));

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
        setLastReason(nativeToolCallMessage("missing_tool", Collections.emptyMap()));

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
        setLastReason(nativeToolCallMessage(org.noear.solon.ai.agent.util.FeedbackTool.TOOL_NAME, args));

        actionTask.run(trace, context);

        assertEquals(Agent.ID_END, trace.getRoute());
        assertEquals("缺少用户手机号", trace.getFinalAnswer());
        // Feedback 走单参 setFinalAnswer → abnormal=true
        assertEquals(Boolean.TRUE, finalAnswerAbnormal.get(), "Feedback 应为 abnormal=true");
        verify(trace, times(1)).setFinalAnswer(eq("缺少用户手机号"));
        verify(context, times(1)).interrupt();
    }

    @Test
    @DisplayName("returnDirect=true 但 handler 返回 ToolResult.error：不直返，ToolMessage 不 mark")
    public void testReturnDirect_toolResultError_notEnd() throws Throwable {
        options.getModelOptions().toolAdd(new FunctionToolDesc("getWeather")
                .returnDirect(true)
                .description("天气")
                .doHandle(args -> ToolResult.error("upstream unavailable")));

        setLastReason(nativeToolCallMessage("getWeather", Collections.emptyMap()));

        actionTask.run(trace, context);

        assertEquals(ReActAgent.ID_REASON, trace.getRoute());
        assertNull(trace.getFinalAnswer());
        assertNull(finalAnswerAbnormal.get());
        assertEquals(1, toolCallCount.get()); // call 成功返回，只是 isError
        // 错误结果仍应进入 WM 作为 observation，供模型自愈
        assertTrue(workingMemory.getMessages().stream()
                .anyMatch(m -> m.getContent() != null && m.getContent().contains("upstream unavailable")));
        // ToolMessage.returnDirect 不应为 true（未 mark）
        workingMemory.getMessages().stream()
                .filter(m -> m instanceof ToolMessage)
                .map(m -> (ToolMessage) m)
                .forEach(tm -> assertFalse(tm.isReturnDirect(), "error 结果不应标记 returnDirect"));
    }

    @Test
    @DisplayName("returnDirect=true 但结果为空串：不 mark、不直返")
    public void testReturnDirect_emptyResult_notEnd() throws Throwable {
        options.getModelOptions().toolAdd(new FunctionToolDesc("getWeather")
                .returnDirect(true)
                .description("天气")
                .doHandle(args -> ""));

        setLastReason(nativeToolCallMessage("getWeather", Collections.emptyMap()));

        actionTask.run(trace, context);

        assertEquals(ReActAgent.ID_REASON, trace.getRoute());
        assertNull(trace.getFinalAnswer());
        assertEquals(1, toolCallCount.get());
        ToolMessage tm = (ToolMessage) workingMemory.getMessages().stream()
                .filter(m -> m instanceof ToolMessage)
                .findFirst()
                .orElse(null);
        assertNotNull(tm);
        // 与 apply 对齐：空串不可交付，执行成功时也不 mark
        assertFalse(tm.isReturnDirect(), "空串结果不应标记 returnDirect");
    }

    @Test
    @DisplayName("returnDirect=true 且结果含 media：ToolMessage 保留 blocks，media 上浮 lastReason")
    public void testReturnDirect_withMedia_preservesBlocks() throws Throwable {
        ImageBlock image = ImageBlock.ofUrl("https://example.com/weather.png", "image/png");
        options.getModelOptions().toolAdd(new FunctionToolDesc("drawWeather")
                .returnDirect(true)
                .description("画天气图")
                .doHandle(args -> new ToolResult()
                        .addText("杭州晴")
                        .addBlock(image)));

        setLastReason(nativeToolCallMessage("drawWeather", Collections.emptyMap()));

        actionTask.run(trace, context);

        assertEquals(Agent.ID_END, trace.getRoute());
        assertEquals("杭州晴", trace.getFinalAnswer());
        assertEquals(Boolean.TRUE, finalAnswerAbnormal.get());

        ToolMessage tm = (ToolMessage) workingMemory.getMessages().stream()
                .filter(m -> m instanceof ToolMessage)
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertTrue(tm.isReturnDirect());
        assertEquals("杭州晴", tm.getContent());
        assertNotNull(tm.getBlocks());
        assertTrue(tm.getBlocks().stream().anyMatch(b -> b instanceof ImageBlock),
                "ToolMessage 应保留 ImageBlock，不能被 String 压扁");
        assertTrue(tm.getBlocks().stream().anyMatch(b -> b instanceof TextBlock));

        // A1：media 写到 trace.finalMediaBlocks，供 ReActAgent 终态收口；不挂回 lastReason
        List<ContentBlock> finalMedia = finalMediaHolder.get();
        assertNotNull(finalMedia, "returnDirect media 应写到 trace.finalMediaBlocks");
        assertTrue(finalMedia.stream().anyMatch(b -> b instanceof ImageBlock));
        // lastReason 保持原样（带 tool_call 的推理消息），不被 media 污染
        AssistantMessage last = lastReasonHolder.get();
        assertNotNull(last);
        assertFalse(last.hasMedia(), "lastReason 不应被挂上 tool media");
        assertNotNull(last.getToolCalls(), "lastReason.toolCalls 不应丢失");
        assertEquals(1, last.getToolCalls().size());
        assertEquals("drawWeather", last.getToolCalls().get(0).getName());
    }

    @Test
    @DisplayName("returnDirect=true 纯 media（无文本）：仍 END，FinalAnswer 可为空，media 上浮")
    public void testReturnDirect_mediaOnly() throws Throwable {
        ImageBlock image = ImageBlock.ofUrl("https://example.com/art.png");
        options.getModelOptions().toolAdd(new FunctionToolDesc("generateImage")
                .returnDirect(true)
                .description("生图")
                .doHandle(args -> new ToolResult().addBlock(image)));

        setLastReason(nativeToolCallMessage("generateImage", Collections.emptyMap()));

        actionTask.run(trace, context);

        assertEquals(Agent.ID_END, trace.getRoute());
        assertEquals(Boolean.TRUE, finalAnswerAbnormal.get());
        // 纯 media：finalAnswer 文本为空串
        assertEquals("", trace.getFinalAnswer());
        verify(trace).setFinalAnswer(eq(""), eq(false));

        ToolMessage tm = (ToolMessage) workingMemory.getMessages().stream()
                .filter(m -> m instanceof ToolMessage)
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertTrue(tm.isReturnDirect());
        assertTrue(tm.getBlocks().stream().anyMatch(b -> b instanceof ImageBlock));

        // 纯 media 写到 trace.finalMediaBlocks
        List<ContentBlock> finalMedia = finalMediaHolder.get();
        assertNotNull(finalMedia);
        assertTrue(finalMedia.stream().anyMatch(b -> b instanceof ImageBlock));
    }

    @Test
    @DisplayName("拦截器在 onToolCallEnd 改写结果（block 数量不变）：observation 按最终结果重建")
    public void testInterceptorRewritesResult_observationRebuilt() throws Throwable {
        options.getModelOptions().toolAdd(new FunctionToolDesc("getWeather")
                .returnDirect(false)
                .description("天气")
                .doHandle(args -> "原始天气"));

        // 拦截器：文本变了但 block 数量同为 1（旧 sameBlockSize 会误判为无需重建）
        options.getModelOptions().interceptorAdd(new org.noear.solon.ai.agent.react.ReActInterceptor() {
            @Override
            public void onToolCallEnd(ReActTrace t, org.noear.solon.ai.agent.react.task.ToolExchanger ex,
                                      ChatMessage obs, Throwable err, long ms) {
                ex.setToolResult(ToolResult.success("审批后的天气"));
            }
        });

        setLastReason(nativeToolCallMessage("getWeather", Collections.emptyMap()));

        actionTask.run(trace, context);

        ToolMessage tm = (ToolMessage) workingMemory.getMessages().stream()
                .filter(m -> m instanceof ToolMessage)
                .findFirst()
                .orElseThrow(AssertionError::new);
        // observation 应反映拦截器改写后的内容，而非旧值
        assertEquals("审批后的天气", tm.getContent());
    }
}
