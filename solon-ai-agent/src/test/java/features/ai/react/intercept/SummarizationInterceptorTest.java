package features.ai.react.intercept;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.SummarizationInterceptor;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.prompt.Prompt;

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

        // 阈值设为 6，消息数 > 8 时触发压缩
        interceptor = new SummarizationInterceptor(6);
    }

    @Test
    public void testAtomicAlignment_ByJson() {
        // 1. 系统与初始任务
        messageList.add(ChatMessage.ofSystem("You are a helpful assistant."));
        messageList.add(ChatMessage.ofUser("Search for Solon framework and tell me its version."));

        // 2. 填充历史对话
        messageList.add(ChatMessage.ofAssistant("Thought: I need to search."));
        messageList.add(ChatMessage.ofUser("Observation: No data found."));
        messageList.add(ChatMessage.ofAssistant("Thought: Try again."));

        // 3. 构造关键原子对：Action
        String actionJson = "{" +
                "  \"role\": \"assistant\"," +
                "  \"content\": \"Calling search tool...\"," +
                "  \"toolCalls\": [{" +
                "    \"id\": \"call_001\"," +
                "    \"name\": \"search\"," +
                "    \"arguments\": {\"q\": \"solon framework\"}" +
                "  }]" +
                "}";
        messageList.add(ChatMessage.fromJson(actionJson)); // index 5

        // 4. 结果 Observation
        messageList.add(ChatMessage.ofTool("Solon version is 3.0", "search", "call_001")); // index 6

        // 5. 堆叠消息直到触发压缩 (当前 size=7)
        messageList.add(ChatMessage.ofAssistant("Thought: I found the version.")); // index 7
        messageList.add(ChatMessage.ofAssistant("Thought: Preparing final answer.")); // index 8
        messageList.add(ChatMessage.ofAssistant("Thought: Almost there.")); // index 9
        // 当前 size=10，interceptor 设置的 maxMessages=6，触发点为 6+2=8。10 > 8，触发压缩。

        // 触发压缩
        interceptor.onObservation(trace, "search", "Solon version is 3.0");

        // 验证压缩结果
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(workingMemory).replaceMessages(captor.capture());
        List<ChatMessage> compressed = (List<ChatMessage>) captor.getValue();

        // --- 断言逻辑优化 ---

        // A. 基础结构验证
        assertTrue(compressed.get(0) instanceof SystemMessage, "First message should be SystemMessage");
        assertTrue(compressed.stream().anyMatch(m -> m.getContent().contains("Search for Solon framework")),
                "Initial User task should be preserved");

        // B. 原子性验证（带 NPE 保护）
        // 检查是否存在带 ToolCalls 的 AssistantMessage
        boolean hasAction = compressed.stream()
                .anyMatch(m -> (m instanceof AssistantMessage)
                        && ((AssistantMessage) m).getToolCalls() != null
                        && !((AssistantMessage) m).getToolCalls().isEmpty());

        // 检查是否存在 ToolMessage (Observation)
        boolean hasObservation = compressed.stream().anyMatch(m -> m instanceof ToolMessage);

        // 核心逻辑验证：Action 和 Observation 必须要么全有，要么全无
        assertEquals(hasAction, hasObservation, "Action and Observation must be preserved together or dropped together");

        // C. 深度验证：在本用例的偏移量下，index 5和6应该被保留
        // 因为 targetIdx = 10 - 6 = 4。index 4不是原子对的一部分，不会额外回退。
        // 所以 index 5 (Action) 和 6 (Observation) 应该都在。
        assertTrue(hasAction, "Under this offset, the specific action pair should be preserved");

        System.out.println("Compressed message roles: " +
                compressed.stream().map(m -> m.getRole().name()).reduce((a, b) -> a + " -> " + b).orElse(""));
    }

    @Test
    public void testPreservePlans_ByJson() {
        // 填充大量消息
        for (int i = 0; i < 10; i++) {
            messageList.add(ChatMessage.ofAssistant("Step " + i));
        }

        // 模拟执行计划
        when(trace.hasPlans()).thenReturn(true);
        when(trace.getPlans()).thenReturn(Arrays.asList("Plan A", "Plan B"));

        interceptor.onObservation(trace, "any", "any");

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(workingMemory).replaceMessages(captor.capture());
        List<ChatMessage> compressed = captor.getValue();

        // 验证压缩后的注入信息
        boolean hasPlans = compressed.stream()
                .anyMatch(m -> m instanceof SystemMessage && m.getContent().contains("Plan A"));
        assertTrue(hasPlans, "Compressed context must contain Execution Plans");
    }
}