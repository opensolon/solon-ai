package features.ai.react.intercept;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.SummarizationInterceptor;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.core.util.Assert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SummarizationInterceptorTest {

    private ReActTrace trace;
    private Prompt workingMemory;
    private List<ChatMessage> messageList;
    private SummarizationInterceptor interceptor;

    @BeforeEach
    public void setUp() {
        trace = mock(ReActTrace.class);
        workingMemory = mock(Prompt.class);
        messageList = new ArrayList<>();

        when(trace.getWorkingMemory()).thenReturn(workingMemory);
        when(workingMemory.getMessages()).thenReturn(messageList);
        when(trace.getAgentName()).thenReturn("TestAgent");

        // 设置较小的阈值以便触发压缩逻辑
        interceptor = new SummarizationInterceptor(6);
    }

    /**
     * 测试 1：验证原子对齐（Action 和 Observation 必须同生共死）
     */
    @Test
    public void testAtomicAlignment_WithActionPair() {
        // 1. 基础消息
        messageList.add(ChatMessage.ofSystem("Base System"));
        messageList.add(ChatMessage.ofUser("Initial Task"));

        // 2. 干扰消息
        messageList.add(ChatMessage.ofAssistant("Thought 1"));
        messageList.add(ChatMessage.ofUser("Observation: Noise 1"));
        messageList.add(ChatMessage.ofAssistant("Thought 1.5")); // 增加这一条
        messageList.add(ChatMessage.ofUser("Observation: Noise 1.5")); // 增加这一条

        // 3. 关键原子对 (使用 AssistantMessage 的标准实现而非 Mock)
        // 这样 getContent() 默认返回空字符串而非 null，规避 NPE
        List<ToolCall> toolCalls = Arrays.asList(new ToolCall("call_1","call_1", "tool_1", "{}", Utils.asMap()));
        // 参数含义：content, isThinking, contentRaw, toolCallsRaw, toolCalls, searchResultsRaw
        AssistantMessage actionMsg = new AssistantMessage(
                "Calling tool...",
                false,
                null,
                null,
                toolCalls,
                null
        );

        messageList.add(actionMsg); // Index 4
        messageList.add(ChatMessage.ofTool("Result", "tool_1", "call_1")); // Index 5

        // 4. 后续堆叠
        messageList.add(ChatMessage.ofAssistant("Thought 2"));
        messageList.add(ChatMessage.ofAssistant("Thought 3"));
        messageList.add(ChatMessage.ofAssistant("Thought 4"));
        messageList.add(ChatMessage.ofAssistant("Thought 5"));

        interceptor.onObservation(trace, "tool_1", "Result");

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(workingMemory).replaceMessages(captor.capture());
        List<ChatMessage> compressed = (List<ChatMessage>) captor.getValue();

        // 5. 验证结果
        // 检查是否包含 ToolMessage
        assertTrue(compressed.stream().anyMatch(m -> m instanceof ToolMessage), "Observation must be preserved");

        // 检查是否包含带 ToolCalls 的 AssistantMessage
        assertTrue(compressed.stream().anyMatch(m ->
                (m instanceof AssistantMessage) && Assert.isNotEmpty(((AssistantMessage) m).getToolCalls())
        ), "Action must be preserved");

        // 验证 TRIM_MARKER
        // 确保检查 getContent() 前不为 null
        boolean hasMarker = compressed.stream()
                .filter(m -> m.getContent() != null)
                .anyMatch(m -> m.getContent().contains("trimmed"));
        assertTrue(hasMarker, "Should contain trim marker");
    }

    /**
     * 测试 2：验证系统提示词去重逻辑
     * (WorkingMemory 中即便有多个 SystemMessage，也只应保留第一个)
     */
    @Test
    public void testSystemMessageDeduplication() {
        messageList.add(ChatMessage.ofSystem("System 1"));
        messageList.add(ChatMessage.ofUser("User 1"));
        messageList.add(ChatMessage.ofSystem("System 2 (Old Plan)")); // 模拟被错误存入的消息
        for (int i = 0; i < 10; i++) messageList.add(ChatMessage.ofAssistant("History " + i));

        interceptor.onObservation(trace, "any", "any");

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(workingMemory).replaceMessages(captor.capture());
        List<ChatMessage> compressed = captor.getValue();

        long systemCount = compressed.stream()
                .filter(m -> m instanceof SystemMessage && !m.getContent().contains("trimmed"))
                .count();

        assertEquals(1, systemCount, "Should only keep the first foundational SystemMessage");
        assertEquals("System 1", compressed.get(0).getContent(), "The preserved system message must be the first one");
    }

    /**
     * 测试 3：验证“初心”保护 (First User Message 不会被裁)
     */
    @Test
    public void testFirstUserMessagePreservation() {
        messageList.add(ChatMessage.ofSystem("System"));
        messageList.add(ChatMessage.ofUser("KEEP THIS GOAL"));
        for (int i = 0; i < 15; i++) {
            messageList.add(ChatMessage.ofAssistant("Irrelevant Step " + i));
        }

        interceptor.onObservation(trace, "any", "any");

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(workingMemory).replaceMessages(captor.capture());
        List<ChatMessage> compressed = captor.getValue();

        boolean goalPreserved = compressed.stream()
                .anyMatch(m -> m instanceof UserMessage && m.getContent().equals("KEEP THIS GOAL"));

        assertTrue(goalPreserved, "The very first User message (goal) must never be trimmed");
    }

    /**
     * 测试 4：验证无须截断时的行为
     */
    @Test
    public void testNoCompressionWhenUnderThreshold() {
        messageList.add(ChatMessage.ofSystem("System"));
        messageList.add(ChatMessage.ofUser("Task"));
        messageList.add(ChatMessage.ofAssistant("Response"));

        interceptor.onObservation(trace, "any", "any");

        // 不应调用 replaceMessages
        verify(workingMemory, never()).replaceMessages(any());
    }

    /**
     * 测试 5：验证在极端回溯下，依然能触发 TRIM_MARKER
     * 场景：消息非常多，即使对齐回退了几步，前面依然有大量被丢弃的历史
     */
    @Test
    public void testTrimMarkerWithHeavyHistory() {
        messageList.add(ChatMessage.ofSystem("System"));
        messageList.add(ChatMessage.ofUser("Initial Goal")); // Index 1

        // 模拟大量历史 (Index 2 to 21)
        for (int i = 0; i < 20; i++) {
            messageList.add(ChatMessage.ofAssistant("Old Step " + i));
        }

        // 此时 size = 22, maxMessages = 6, targetIdx = 16
        // 即使没有原子对齐，16 也远大于 (1 + 1)

        interceptor.onObservation(trace, "any", "any");

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(workingMemory).replaceMessages(captor.capture());
        List<ChatMessage> compressed = captor.getValue();

        assertTrue(compressed.stream().anyMatch(m -> m.getContent().contains("trimmed")),
                "With heavy history, trim marker must be present");
    }
}