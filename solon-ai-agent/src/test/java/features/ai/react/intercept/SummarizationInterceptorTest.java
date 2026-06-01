package features.ai.react.intercept;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.agent.react.intercept.CompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.*;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryStorable;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SummarizationInterceptorTest {

    private ReActTrace trace;
    private Prompt workingMemory;
    private ContextCompressionInterceptor interceptor;
    private ChatModel chatModel;

    @BeforeEach
    public void setUp() {
        trace = mock(ReActTrace.class);
        workingMemory = Prompt.of();

        when(trace.getWorkingMemory()).thenReturn(workingMemory);
        when(trace.getAgentName()).thenReturn("TestAgent");

        chatModel = LlmUtil.getChatModel();

        // 阈值设为 6，消息超过 6 条时触发压缩
        interceptor = new ContextCompressionInterceptor(6,8000, LlmUtil::getChatModel, null);
    }

    /**
     * 验证“初心链”物理保留
     */
    @Test
    public void testFirstChainPreservationAndMetadata() {
        // 模拟 ReActAgent 行为：为 System 和第一个 User 注入 META_FIRST
        ChatMessage systemMsg = ChatMessage.ofSystem("Base System");
        systemMsg.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(systemMsg);

        ChatMessage userGoal = ChatMessage.ofUser("Initial Goal");
        userGoal.addMetadata(AgentTrace.META_FIRST, 1); // 手动模拟框架注入标记
        workingMemory.addMessage(userGoal);

        // 注入大量中间历史 (Total: 2 + 15 = 17 > 6)
        for (int i = 0; i < 15; i++) {
            workingMemory.addMessage(ChatMessage.ofAssistant("Step " + i));
        }

        // 执行拦截
        interceptor.onReasonStart(trace, null);

        // 验证截断发生
        assertTrue(workingMemory.getMessages().size() < 17);

        // 验证带有标记的初心是否被保留
        ChatMessage firstUser = workingMemory.getMessages().stream()
                .filter(m -> m instanceof UserMessage)
                .findFirst()
                .orElse(null);

        assertNotNull(firstUser, "First User goal should be preserved");
        assertTrue(firstUser.hasMetadata(AgentTrace.META_FIRST), "Should carry _first metadata");
        assertEquals("Initial Goal", firstUser.getContent());
    }

    /**
     * 验证原子对齐不会误伤初心链
     */
    @Test
    public void testAtomicAlignment_RespectsFirstChain() {
        ChatMessage sys = ChatMessage.ofSystem("System");
        sys.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(sys);

        ChatMessage goal = ChatMessage.ofUser("Target Goal");
        goal.addMetadata(AgentTrace.META_FIRST, 1); // 加上标记
        workingMemory.addMessage(goal);

        // 噪音消息
        for (int i = 0; i < 5; i++) {
            workingMemory.addMessage(ChatMessage.ofAssistant("Noise " + i));
        }

        // 构造原子对 (Action + Observation)
        List<ToolCall> toolCalls = Arrays.asList(new ToolCall("c1", "c1", "t1", "{}", Utils.asMap()));
        AssistantMessage action = new AssistantMessage("call", false, null, null, toolCalls, null);
        workingMemory.addMessage(action);
        workingMemory.addMessage(ChatMessage.ofTool("res", "t1", "c1"));

        // 堆叠活跃消息，迫使对齐逻辑在截断时进行回退判断
        for (int i = 0; i < 3; i++) {
            workingMemory.addMessage(ChatMessage.ofAssistant("Active " + i));
        }

        interceptor.onReasonStart(trace, null);

        List<ChatMessage> result = workingMemory.getMessages();

        // 验证初心完好
        boolean hasFirstGoal = result.stream()
                .anyMatch(m -> m instanceof UserMessage && m.hasMetadata(AgentTrace.META_FIRST));
        assertTrue(hasFirstGoal, "First chain must be protected even with atomic alignment");

        // 验证 Tool 消息由于对齐机制也被保留了
        assertTrue(result.stream().anyMatch(m -> m instanceof ToolMessage));
    }

    /**
     * 验证 VectorStore 策略过滤逻辑
     */
    @Test
    public void testVectorStoreSummarizationStrategy_FiltersFirst() throws Exception {
        RepositoryStorable vectorRepository = mock(RepositoryStorable.class);
        AgentSession session = mock(AgentSession.class);
        when(trace.getSession()).thenReturn(session);
        when(session.getSessionId()).thenReturn("sess_001");

        VectorStoreCompressionStrategy strategy = new VectorStoreCompressionStrategy(vectorRepository);

        ChatMessage m1 = ChatMessage.ofUser("初心内容");
        m1.addMetadata(AgentTrace.META_FIRST, 1); // 标记为初心
        ChatMessage m2 = ChatMessage.ofAssistant("执行过程内容");

        strategy.compress(chatModel, 3, trace, Arrays.asList(m1, m2));

        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(vectorRepository).save(docCaptor.capture());

        String savedContent = docCaptor.getValue().getContent();
        // 核心验证：策略类内部过滤掉了带有 _first 的消息，不存入向量库
        assertFalse(savedContent.contains("初心内容"), "Vector store should filter out messages with _first metadata");
        assertTrue(savedContent.contains("执行过程内容"));
    }

    /**
     * 验证 LLMSummarizationStrategy 真实集成
     */
    @Test
    public void testLLMSummarizationStrategy_Real() throws Exception {
        LLMCompressionStrategy strategy = new LLMCompressionStrategy();

        ChatMessage m1 = ChatMessage.ofUser("我是初心任务");
        m1.addMetadata(AgentTrace.META_FIRST, 1);
        ChatMessage m2 = ChatMessage.ofAssistant("我是需要被总结的执行细节。");

        ChatMessage result = strategy.compress(chatModel, 3, trace, Arrays.asList(m1, m2));

        assertNotNull(result);
        // 验证返回了有效的摘要内容
        assertNotNull(result.getContent());
        assertFalse(result.getContent().isEmpty());
    }

    @Test
    public void testSemanticCompletion_IncludesThought() {
        // 1. 初心
        workingMemory.addMessage(ChatMessage.ofUser("Goal").addMetadata(AgentTrace.META_FIRST, 1));

        // 2. 构造一个 Thought (Assistant 消息且无 ToolCalls)
        AssistantMessage thought = new AssistantMessage("I should check the weather first.");
        workingMemory.addMessage(thought); // 假设这是 targetIdx - 1 的位置

        // 3. 构造活跃窗口消息
        for (int i = 0; i < 10; i++) {
            workingMemory.addMessage(ChatMessage.ofAssistant("Step " + i));
        }

        interceptor.onReasonStart(trace, null);

        // 验证：thought 消息虽然在 maxMessages 范围外，但由于语义补齐逻辑，它应该被保留
        assertTrue(workingMemory.getMessages().contains(thought), "Thought message should be preserved for semantic continuity");
    }

    @Test
    public void testCompositeStrategy_Isolation() {
        CompressionStrategy mockErrorStrategy = mock(CompressionStrategy.class);
        when(mockErrorStrategy.compress(chatModel, 3, any(), any())).thenThrow(new RuntimeException("LLM Timeout"));

        CompressionStrategy normalStrategy = new LLMCompressionStrategy();
        CompositeCompressionStrategy composite = new CompositeCompressionStrategy(mockErrorStrategy, normalStrategy);

        ChatMessage result = composite.compress(chatModel, 3,trace, Arrays.asList(ChatMessage.ofUser("data")));

        assertNotNull(result);
        assertFalse(result.getContent().isEmpty(), "Should still contain result from normal strategy");
    }

    @Test
    public void testHierarchicalStrategy_StateRolling() {
        HierarchicalCompressionStrategy strategy = new HierarchicalCompressionStrategy();
        String lastSummaryKey = "agent:summary:hierarchical";

        // 模拟之前已经存在的旧摘要
        String oldSummary = "Previous summary content.";
        when(trace.getExtraAs(lastSummaryKey)).thenReturn(oldSummary);

        // 触发新的总结
        strategy.compress(chatModel,3, trace, Arrays.asList(ChatMessage.ofAssistant("New event.")));

        // 验证：trace.setExtra 被调用了，且存入了新的摘要（由于 mock 模型，这里主要看调用）
        verify(trace).setExtra(eq(lastSummaryKey), anyString());
    }

    @Test
    public void testGlobalSystemMessagePreservation() {
        // 构造全局 System 指令（框架会为其注入 META_FIRST 标记，以在压缩时保护）
        ChatMessage globalSystem = ChatMessage.ofSystem("You are a helpful assistant.");
        globalSystem.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(globalSystem);

        for (int i = 0; i < 20; i++) {
            workingMemory.addMessage(ChatMessage.ofUser("msg " + i));
        }

        interceptor.onReasonStart(trace, null);

        // 验证：第一条消息依然是原生的 System 消息
        assertEquals(globalSystem.getContent(), workingMemory.getMessages().get(0).getContent());
    }
}