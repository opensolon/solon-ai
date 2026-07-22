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
import org.noear.solon.ai.agent.react.*;
import org.noear.solon.ai.agent.react.task.ReasonTask;
import org.noear.solon.ai.chat.ChatChoice;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.prompt.PromptImpl;
import org.noear.solon.flow.FlowContext;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ReasonTask 单元测试
 *
 * <p>覆盖核心的容错处理逻辑：模型响应内容及工具调用均为空时，
 * 引导其重新生成的机制（格式修正 / 自我反思），包括重试计数、边界条件恢复等。</p>
 */
public class ReasonTaskTest {

    private ReasonTask reasonTask;
    private ReActTrace trace;
    private ReActAgentConfig config;
    private ReActAgent agent;
    private FlowContext context;
    private Prompt workingMemory;
    private ChatModel chatModel;
    private ChatRequestDesc reqDesc;
    private AtomicInteger emptyRetryCounter;
    private AgentSession session;

    @BeforeEach
    public void setUp() throws Exception {
        config = mock(ReActAgentConfig.class);
        agent = mock(ReActAgent.class);
        trace = mock(ReActTrace.class);
        context = mock(FlowContext.class);
        workingMemory = new PromptImpl();
        chatModel = mock(ChatModel.class);
        reqDesc = mock(ChatRequestDesc.class);
        emptyRetryCounter = new AtomicInteger(0);
        session = mock(AgentSession.class);

        reasonTask = new ReasonTask(config, agent);

        // === ReActTrace ===
        when(trace.getRoute()).thenReturn("reason");
        when(trace.nextTurn()).thenReturn(1);
        when(trace.getWorkingMemory()).thenReturn(workingMemory);
        when(trace.getEmptyRetryCounter()).thenReturn(emptyRetryCounter);
        when(trace.getAgentName()).thenReturn("TestAgent");
        when(trace.getSession()).thenReturn(session);
        when(session.isPending()).thenReturn(false);
        when(trace.hasPlans()).thenReturn(false);
        when(trace.getProtocol()).thenReturn(null);
        when(trace.getLastReasonMessage()).thenReturn(null);
        when(trace.getConfig()).thenReturn(config);  // ← 关键：之前遗漏了

        // === ReActOptions ===
        ReActOptions options = new ReActOptions(chatModel);
        when(trace.getOptions()).thenReturn(options);

        // === ReActAgentConfig ===
        when(config.getSystemPromptFor(any(), any())).thenReturn("System prompt");
        when(config.getName()).thenReturn("TestAgent");
        when(config.getStyle()).thenReturn(ReActStyle.STRUCTURED_TEXT);
        when(config.getFinishMarker()).thenReturn("[TEST_FINISH]");
        when(config.getLocale()).thenReturn(Locale.CHINESE);

        // === ChatModel chain（使 callWithRetry 可顺利执行） ===
        when(chatModel.prompt(anyList())).thenReturn(reqDesc);
        when(reqDesc.options(any(Consumer.class))).thenReturn(reqDesc);
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造一个 mock ChatResponse（便于控制 message 行为）
     */
    private ChatResponse mockResponse(AssistantMessage message) {
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.isStream()).thenReturn(false);
        when(resp.isEmpty()).thenReturn(false);
        when(resp.getMessage()).thenReturn(message);
        when(resp.getAggregationMessage()).thenReturn(message);
        when(resp.getChoices()).thenReturn(Collections.singletonList(
                new ChatChoice(0, new Date(), "stop", message)));
        when(resp.getUsage()).thenReturn(null);
        return resp;
    }

    /**
     * 通过 JSON 构造 AssistantMessage
     */
    private AssistantMessage msgFromJson(String json) {
        return (AssistantMessage) ChatMessage.fromJson(json);
    }

    // ==================== 正常响应场景 ====================

    @Test
    @DisplayName("正常响应（有结果内容）：重置空响应计数器为 0")
    public void testNormalResponse_withResultContent() throws Throwable {
        // getResultContent() 返回非空内容
        AssistantMessage msg = msgFromJson("{\"role\":\"assistant\",\"content\":\"Here is the final answer.\"}");
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        emptyRetryCounter.set(2);
        reasonTask.run(trace, context);

        assertEquals(0, emptyRetryCounter.get(), "正常响应后计数器应重置为 0");
    }

    @Test
    @DisplayName("正常响应（有工具调用）：重置空响应计数器为 0")
    public void testNormalResponse_withToolCalls() throws Throwable {
        // 工具调用优先级高于 resultContent
        AssistantMessage msg = msgFromJson("{" +
                "  \"role\": \"assistant\"," +
                "  \"toolCalls\": [{\"id\":\"1\",\"name\":\"search\",\"arguments\":{}}]" +
                "}");
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        emptyRetryCounter.set(5);
        reasonTask.run(trace, context);

        assertEquals(0, emptyRetryCounter.get(), "有工具调用时计数器应重置");
    }

    @Test
    @DisplayName("正常响应之后 route 不被设为 ID_REASON")
    public void testNormalResponse_routeNotSetToReason() throws Throwable {
        AssistantMessage msg = msgFromJson("{\"role\":\"assistant\",\"content\":\"Final answer.\"}");
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        reasonTask.run(trace, context);

        verify(trace, never()).setRoute(ReActAgent.ID_REASON);
    }

    // ==================== 空响应 + 有思考内容（格式修正） ====================
    //
    // 「有思考内容」= content 包含 <think> 标签，使 getResultContent() 返回空，
    //   但 getContent() 返回原始内容（非空）→ 走格式修正分支

    @Test
    @DisplayName("空响应但有思考内容（第 1 次）：注入格式修正提示，设置 route=reason")
    public void testEmptyResponse_withContent_retry1() throws Throwable {
        // content 有 <think> 且 </think> 后无内容 → getResultContent()="", getContent() 非空
        AssistantMessage msg = msgFromJson("{\"role\":\"assistant\",\"content\":\"<think>I need to analyze</think>\"}");
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        reasonTask.run(trace, context);

        assertEquals(1, emptyRetryCounter.get());

        List<ChatMessage> msgs = workingMemory.getMessages();
        assertEquals(2, msgs.size(), "应注入 2 条消息（原始思考 + 格式修正提示）");
        assertTrue(msgs.get(0) instanceof AssistantMessage, "第 1 条应为 AssistantMessage");
        assertTrue(msgs.get(1).getContent().contains("输出格式修正 (Format Correction)"),
                "应为格式修正提示");
        assertTrue(msgs.get(1).getContent().contains("第 1 次尝试"),
                "应包含第 1 次尝试的计数");

        verify(trace).setRoute(ReActAgent.ID_REASON);
    }

    @Test
    @DisplayName("空响应但有思考内容（第 2 次）：再次注入格式修正提示")
    public void testEmptyResponse_withContent_retry2() throws Throwable {
        AssistantMessage msg = msgFromJson("{\"role\":\"assistant\",\"content\":\"<think>More analysis</think>\"}");
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        emptyRetryCounter.set(1);
        reasonTask.run(trace, context);

        assertEquals(2, emptyRetryCounter.get());

        List<ChatMessage> msgs = workingMemory.getMessages();
        assertEquals(2, msgs.size());
        assertTrue(msgs.get(1).getContent().contains("输出格式修正 (Format Correction)"));
        assertTrue(msgs.get(1).getContent().contains("第 2 次尝试"));

        verify(trace).setRoute(ReActAgent.ID_REASON);
    }

    @Test
    @DisplayName("空响应只有 <think> 无 </think>：同样 getResultContent 为空，走格式修正")
    public void testEmptyResponse_withUnclosedThink() throws Throwable {
        // <think> 未闭合 → getResultContent() 返回 ""，getContent() 非空
        AssistantMessage msg = msgFromJson("{\"role\":\"assistant\",\"content\":\"<think>incomplete\"}");
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        reasonTask.run(trace, context);

        assertEquals(1, emptyRetryCounter.get());
        List<ChatMessage> msgs = workingMemory.getMessages();
        assertEquals(2, msgs.size());
        assertTrue(msgs.get(1).getContent().contains("输出格式修正 (Format Correction)"));
    }

    // ==================== 空响应 + 无任何内容（自我反思） ====================
    //
    // 「无任何内容」= content 为 null（JSON 中无 content 字段）
    //   → getContent()=null, getResultContent()="" → 走自我反思分支

    @Test
    @DisplayName("空响应且无任何内容（第 1 次）：注入自我反思提示")
    public void testEmptyResponse_withoutContent_retry1() throws Throwable {
        AssistantMessage msg = msgFromJson("{\"role\":\"assistant\"}");
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        reasonTask.run(trace, context);

        assertEquals(1, emptyRetryCounter.get());

        List<ChatMessage> msgs = workingMemory.getMessages();
        assertEquals(1, msgs.size(), "应注入 1 条自我反思提示");
        assertTrue(msgs.get(0).getContent().contains("自我反思 (Self-Reflection)"));
        assertTrue(msgs.get(0).getContent().contains("第 1 次尝试"));

        verify(trace).setRoute(ReActAgent.ID_REASON);
    }

    @Test
    @DisplayName("空响应且无任何内容（第 2 次）：再次注入自我反思提示")
    public void testEmptyResponse_withoutContent_retry2() throws Throwable {
        AssistantMessage msg = msgFromJson("{\"role\":\"assistant\"}");
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        emptyRetryCounter.set(1);
        reasonTask.run(trace, context);

        assertEquals(2, emptyRetryCounter.get());

        List<ChatMessage> msgs = workingMemory.getMessages();
        assertEquals(1, msgs.size());
        assertTrue(msgs.get(0).getContent().contains("自我反思 (Self-Reflection)"));

        verify(trace).setRoute(ReActAgent.ID_REASON);
    }

    // ==================== 空响应 + 重试耗尽 ====================

    @Test
    @DisplayName("空响应（有内容）达到重试上限：不再注入提示，静默返回")
    public void testEmptyResponse_withContent_retriesExhausted() throws Throwable {
        AssistantMessage msg = msgFromJson("{\"role\":\"assistant\",\"content\":\"<think>Still thinking</think>\"}");
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        emptyRetryCounter.set(2);
        reasonTask.run(trace, context);

        assertEquals(3, emptyRetryCounter.get());

        // 不应注入任何新消息
        assertTrue(workingMemory.getMessages().isEmpty(),
                "重试耗尽后不应注入任何消息");
        verify(trace, never()).setRoute(anyString());
    }

    @Test
    @DisplayName("空响应（无内容）达到重试上限：静默返回")
    public void testEmptyResponse_withoutContent_retriesExhausted() throws Throwable {
        AssistantMessage msg = msgFromJson("{\"role\":\"assistant\"}");
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        emptyRetryCounter.set(2);
        reasonTask.run(trace, context);

        assertEquals(3, emptyRetryCounter.get());
        assertTrue(workingMemory.getMessages().isEmpty(),
                "重试耗尽后不应注入任何消息");
        verify(trace, never()).setRoute(anyString());
    }

    // ==================== 计数器恢复 ====================

    @Test
    @DisplayName("先空后正常：计数器从累加状态正确恢复为 0")
    public void testCounterResetAfterSuccessfulResponse() throws Throwable {
        // 第 1 次：空响应
        AssistantMessage emptyMsg = msgFromJson("{\"role\":\"assistant\",\"content\":\"<think>thinking</think>\"}");
        ChatResponse emptyResp = mockResponse(emptyMsg);
        when(reqDesc.call()).thenReturn(emptyResp);
        reasonTask.run(trace, context);
        assertEquals(1, emptyRetryCounter.get());

        // 第 2 次：空响应
        reasonTask.run(trace, context);
        assertEquals(2, emptyRetryCounter.get());

        // 第 3 次：正常响应 → 计数器重置
        AssistantMessage normalMsg = msgFromJson("{\"role\":\"assistant\",\"content\":\"Final answer.\"}");
        ChatResponse normalResp = mockResponse(normalMsg);
        when(reqDesc.call()).thenReturn(normalResp);

        reasonTask.run(trace, context);

        assertEquals(0, emptyRetryCounter.get(),
                "正常响应后空响应计数器应恢复为 0");
    }

    // ==================== 边界场景 ====================

    @Test
    @DisplayName("有工具调用（无 resultContent）：不算空响应")
    public void testToolCallsOnly_notEmpty() throws Throwable {
        AssistantMessage msg = msgFromJson("{" +
                "  \"role\": \"assistant\"," +
                "  \"toolCalls\": [{\"id\":\"1\",\"name\":\"search\",\"arguments\":{}}]" +
                "}");
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        emptyRetryCounter.set(3);
        reasonTask.run(trace, context);

        assertEquals(0, emptyRetryCounter.get(),
                "有工具调用时计数器应重置");
    }

    @Test
    @DisplayName("有结果内容（无 toolCalls）：不算空响应")
    public void testResultContentNotEmpty_notEmpty() throws Throwable {
        AssistantMessage msg = msgFromJson("{\"role\":\"assistant\",\"content\":\"Final answer.\"}");
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        emptyRetryCounter.set(1);
        reasonTask.run(trace, context);

        assertEquals(0, emptyRetryCounter.get(),
                "有结果内容时计数器应重置");
    }

    @Test
    @DisplayName("仅有媒体（无文本/无 toolCalls）：不算空响应，不注入自我反思")
    public void testMediaOnly_notEmpty() throws Throwable {
        AssistantMessage msg = ChatMessage.ofAssistant(
                "",
                org.noear.solon.ai.chat.content.ImageBlock.ofUrl("https://example.com/gen.png"));
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        emptyRetryCounter.set(2);
        reasonTask.run(trace, context);

        assertEquals(0, emptyRetryCounter.get(), "media-only 响应应重置空响应计数器");
        assertTrue(workingMemory.getMessages().isEmpty(), "不应注入格式修正/自我反思提示");
        verify(trace, never()).setRoute(ReActAgent.ID_REASON);
    }

    @Test
    @DisplayName("NATIVE_TOOL 风格 media-only：路由到 END")
    public void testMediaOnly_nativeTool_routesToEnd() throws Throwable {
        when(config.getStyle()).thenReturn(ReActStyle.NATIVE_TOOL);

        AssistantMessage msg = ChatMessage.ofAssistant(
                "",
                org.noear.solon.ai.chat.content.ImageBlock.ofUrl("https://example.com/gen2.png"));
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        reasonTask.run(trace, context);

        assertEquals(0, emptyRetryCounter.get());
        verify(trace).setRoute(Agent.ID_END);
        verify(trace).setFinalAnswer(anyString(), eq(false));
    }

    @Test
    @DisplayName("响应为 null 提前返回，不触发空响应逻辑")
    public void testNullResponse_earlyReturn() throws Throwable {
        when(reqDesc.call()).thenThrow(new RuntimeException("模拟失败"));

        emptyRetryCounter.set(1);
        reasonTask.run(trace, context);

        // callWithRetry 异常 → handleLastException 返回 null → setRoute(Agent.ID_END) 在 handleLastException 中调用一次
        assertEquals(1, emptyRetryCounter.get(),
                "null 响应应提前返回，不修改计数器");
        verify(trace, atLeastOnce()).setRoute(Agent.ID_END);
    }

    @Test
    @DisplayName("Session 挂起时提前返回，不触发空响应逻辑")
    public void testSessionPending_earlyReturn() throws Throwable {
        AssistantMessage msg = msgFromJson("{\"role\":\"assistant\"}");
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);
        when(session.isPending()).thenReturn(true);

        emptyRetryCounter.set(1);
        reasonTask.run(trace, context);

        assertEquals(1, emptyRetryCounter.get(),
                "session 挂起应提前返回，不修改计数器");
    }

    // ==================== 提示内容验证 ====================

    @Test
    @DisplayName("格式修正提示包含正确的轮次和重试序号")
    public void testFormatFixPrompt_content() throws Throwable {
        AssistantMessage msg = msgFromJson("{\"role\":\"assistant\",\"content\":\"<think>Let me think</think>\"}");
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        when(trace.nextTurn()).thenReturn(5);

        reasonTask.run(trace, context);

        List<ChatMessage> msgs = workingMemory.getMessages();
        assertEquals(2, msgs.size());

        String promptContent = msgs.get(1).getContent();
        assertAll("格式修正提示内容验证",
                () -> assertTrue(promptContent.contains("第 5 轮"), "应包含轮次"),
                () -> assertTrue(promptContent.contains("第 1 次尝试"), "应包含重试次数"),
                () -> assertTrue(promptContent.contains("未包含有效的行动"), "应指出缺少行动"),
                () -> assertTrue(promptContent.contains("Final Answer"), "应提及 Final Answer")
        );
    }

    @Test
    @DisplayName("自我反思提示包含正确的轮次和重试序号")
    public void testReflectPrompt_content() throws Throwable {
        AssistantMessage msg = msgFromJson("{\"role\":\"assistant\"}");
        ChatResponse resp = mockResponse(msg);
        when(reqDesc.call()).thenReturn(resp);

        when(trace.nextTurn()).thenReturn(3);
        emptyRetryCounter.set(1);

        reasonTask.run(trace, context);

        List<ChatMessage> msgs = workingMemory.getMessages();
        assertEquals(1, msgs.size());

        String promptContent = msgs.get(0).getContent();
        assertAll("自我反思提示内容验证",
                () -> assertTrue(promptContent.contains("第 3 轮"), "应包含轮次"),
                () -> assertTrue(promptContent.contains("第 2 次尝试"), "应包含重试次数"),
                () -> assertTrue(promptContent.contains("回溯任务目标"), "应包含回溯任务目标"),
                () -> assertTrue(promptContent.contains("审视历史轨迹"), "应包含审视历史轨迹"),
                () -> assertTrue(promptContent.contains("策略修正"), "应包含策略修正")
        );
    }
}
