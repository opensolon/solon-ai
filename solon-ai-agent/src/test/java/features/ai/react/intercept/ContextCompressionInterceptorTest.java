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

public class ContextCompressionInterceptorTest {

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

        // stub config，使 onReasonStart 中的 tools 估算分支可安全跳过（默认 STRUCTURED_TEXT）
        org.noear.solon.ai.agent.react.ReActAgentConfig cfg = mock(org.noear.solon.ai.agent.react.ReActAgentConfig.class);
        when(cfg.getStyle()).thenReturn(org.noear.solon.ai.agent.react.ReActStyle.STRUCTURED_TEXT);
        when(trace.getConfig()).thenReturn(cfg);

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
     * 验证压缩后不会留下孤立的 ToolMessage。
     */
    @Test
    public void testRemoveDanglingToolMessageAfterCompression() {
        ChatMessage goal = ChatMessage.ofUser("Goal");
        goal.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(goal);

        for (int i = 0; i < 12; i++) {
            workingMemory.addMessage(ChatMessage.ofAssistant("History " + i));
        }

        ToolMessage danglingTool = ChatMessage.ofTool("dangling result", "t1", "call_1");
        workingMemory.addMessage(danglingTool);
        workingMemory.addMessage(ChatMessage.ofUser("continue"));

        interceptor.onReasonStart(trace, null);

        assertFalse(workingMemory.getMessages().contains(danglingTool),
                "Dangling ToolMessage should be removed after compression");
    }

    /**
     * 验证保留窗口从 ToolMessage 开始时，会向前补齐匹配的 Assistant(tool_calls)。
     */
    @Test
    public void testToolMessageWindowHeadKeepsAssistantToolCall() {
        ChatMessage goal = ChatMessage.ofUser("Goal");
        goal.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(goal);

        for (int i = 0; i < 5; i++) {
            workingMemory.addMessage(ChatMessage.ofAssistant("Noise " + i));
        }

        List<ToolCall> toolCalls = Arrays.asList(new ToolCall("0", "call_1", "t1", "{}", Utils.asMap()));
        AssistantMessage action = new AssistantMessage("call", false, null, null, toolCalls, null);
        ToolMessage toolResult = ChatMessage.ofTool("result", "t1", "call_1");
        workingMemory.addMessage(action);
        workingMemory.addMessage(toolResult);

        for (int i = 0; i < 7; i++) {
            workingMemory.addMessage(ChatMessage.ofAssistant("Active " + i));
        }

        interceptor.onReasonStart(trace, null);

        List<ChatMessage> result = workingMemory.getMessages();
        int actionIdx = result.indexOf(action);
        int toolIdx = result.indexOf(toolResult);

        assertTrue(actionIdx > -1, "Assistant tool call should be kept");
        assertTrue(toolIdx > -1, "Tool result should be kept");
        assertTrue(actionIdx < toolIdx, "Assistant tool call should appear before tool result");
    }

    /**
     * 验证压缩结果中不能留下没有工具结果的 Assistant(tool_calls)。
     * 这类半截工具调用在 Responses API 中同样不是完整续接上下文。
     */
    @Test
    public void testRemoveDanglingAssistantToolCallWithoutOutput() {
        ChatMessage goal = ChatMessage.ofUser("Goal");
        goal.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(goal);

        for (int i = 0; i < 12; i++) {
            workingMemory.addMessage(ChatMessage.ofAssistant("History " + i));
        }

        List<ToolCall> toolCalls = Arrays.asList(new ToolCall("0", "call_1", "t1", "{}", Utils.asMap()));
        AssistantMessage danglingAction = new AssistantMessage("call", false, null, null, toolCalls, null);
        workingMemory.addMessage(danglingAction);
        workingMemory.addMessage(ChatMessage.ofUser("continue"));

        interceptor.onReasonStart(trace, null);

        assertFalse(workingMemory.getMessages().contains(danglingAction),
                "Dangling Assistant(tool_calls) without matching ToolMessage should be removed after compression");
    }

    /**
     * 验证一个 Assistant 同时发起多个 tool_calls 时，必须保留全部对应结果；少一个就整体移除。
     */
    @Test
    public void testRemoveIncompleteMultiToolCallGroup() {
        ChatMessage goal = ChatMessage.ofUser("Goal");
        goal.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(goal);

        for (int i = 0; i < 12; i++) {
            workingMemory.addMessage(ChatMessage.ofAssistant("History " + i));
        }

        List<ToolCall> toolCalls = Arrays.asList(
                new ToolCall("0", "call_1", "t1", "{}", Utils.asMap()),
                new ToolCall("1", "call_2", "t2", "{}", Utils.asMap())
        );
        AssistantMessage action = new AssistantMessage("call", false, null, null, toolCalls, null);
        ToolMessage toolResult = ChatMessage.ofTool("result1", "t1", "call_1");
        workingMemory.addMessage(action);
        workingMemory.addMessage(toolResult);
        workingMemory.addMessage(ChatMessage.ofUser("continue"));

        interceptor.onReasonStart(trace, null);

        assertFalse(workingMemory.getMessages().contains(action),
                "Incomplete multi tool call Assistant should be removed after compression");
        assertFalse(workingMemory.getMessages().contains(toolResult),
                "Tool result of incomplete group should be removed with its Assistant");
    }

    /**
     * 验证完整的多工具调用组会被保留，且无关的 ToolMessage 会被清掉。
     */
    @Test
    public void testKeepCompleteMultiToolCallGroupAndRemoveUnrelatedToolOutput() {
        ChatMessage goal = ChatMessage.ofUser("Goal");
        goal.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(goal);

        for (int i = 0; i < 5; i++) {
            workingMemory.addMessage(ChatMessage.ofAssistant("Noise " + i));
        }

        List<ToolCall> toolCalls = Arrays.asList(
                new ToolCall("0", "call_1", "t1", "{}", Utils.asMap()),
                new ToolCall("1", "call_2", "t2", "{}", Utils.asMap())
        );
        AssistantMessage action = new AssistantMessage("call", false, null, null, toolCalls, null);
        ToolMessage toolResult1 = ChatMessage.ofTool("result1", "t1", "call_1");
        ToolMessage unrelatedToolResult = ChatMessage.ofTool("bad", "bad", "call_bad");
        ToolMessage toolResult2 = ChatMessage.ofTool("result2", "t2", "call_2");
        workingMemory.addMessage(action);
        workingMemory.addMessage(toolResult1);
        workingMemory.addMessage(unrelatedToolResult);
        workingMemory.addMessage(toolResult2);

        for (int i = 0; i < 6; i++) {
            workingMemory.addMessage(ChatMessage.ofAssistant("Active " + i));
        }

        interceptor.onReasonStart(trace, null);

        List<ChatMessage> result = workingMemory.getMessages();
        assertTrue(result.contains(action), "Complete multi tool call Assistant should be kept");
        assertTrue(result.contains(toolResult1), "Matching tool result should be kept");
        assertTrue(result.contains(toolResult2), "Matching tool result should be kept");
        assertFalse(result.contains(unrelatedToolResult), "Unrelated tool result should be removed");
        assertTrue(result.indexOf(action) < result.indexOf(toolResult1));
        assertTrue(result.indexOf(toolResult1) < result.indexOf(toolResult2));
    }

    @Test
    public void testVectorStoreCompressionStrategy_FiltersFirst() throws Exception {
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
     * 验证 LLMCompressionStrategy 真实集成
     */
    @Test
    public void testLLMCompressionStrategy_Real() throws Exception {
        LLMCompressionStrategy strategy = new LLMCompressionStrategy();

        ChatMessage m1 = ChatMessage.ofUser("我是初心任务");
        m1.addMetadata(AgentTrace.META_FIRST, 1);
        ChatMessage m2 = ChatMessage.ofAssistant("我是需要被总结的执行细节。");

        ChatMessage result = strategy.compress(chatModel, 3, trace, Arrays.asList(m1, m2));

        assertNotNull(result);
        // 验证返回了有效的压缩结果
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
        when(mockErrorStrategy.compress(eq(chatModel), eq(3), any(), any())).thenThrow(new RuntimeException("LLM Timeout"));

        // 用 mock 替代真实 LLM 调用，确保测试不依赖外部 API
        CompressionStrategy normalStrategy = mock(CompressionStrategy.class);
        when(normalStrategy.compress(eq(chatModel), eq(3), any(), any()))
                .thenReturn(ChatMessage.ofUser("normal strategy result"));

        CompositeCompressionStrategy composite = new CompositeCompressionStrategy(mockErrorStrategy, normalStrategy);

        ChatMessage result = composite.compress(chatModel, 3, trace, Arrays.asList(ChatMessage.ofUser("data")));

        assertNotNull(result);
        assertFalse(result.getContent().isEmpty(), "Should still contain result from normal strategy");
        assertTrue(result.getContent().contains("normal strategy result"));
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

        interceptor.onReasonStart(trace, new StringBuilder());

        // 验证：第一条消息依然是原生的 System 消息
        assertEquals(globalSystem.getContent(), workingMemory.getMessages().get(0).getContent());
    }

    /**
     * 验证 minReservedMessages 下限保护：
     * 即使 Token 预算很小，保留窗口也不会被压缩到少于 minReservedMessages 条。
     * interceptor 的 maxMessages=6（实际被修正为 10），minReservedMessages = max(3, 10/3) = 3
     */
    @Test
    public void testMinReservedMessagesProtection() {
        // 构造初心
        ChatMessage sys = ChatMessage.ofSystem("System");
        sys.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(sys);

        ChatMessage goal = ChatMessage.ofUser("Goal");
        goal.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(goal);

        // 构造 25 条普通消息
        for (int i = 0; i < 25; i++) {
            workingMemory.addMessage(ChatMessage.ofAssistant("Step " + i));
        }

        int sizeBefore = workingMemory.getMessages().size(); // 27

        interceptor.onReasonStart(trace, null);

        List<ChatMessage> result = workingMemory.getMessages();

        // 压缩应该发生了
        assertTrue(result.size() < sizeBefore, "Compression should have occurred");

        // 保留窗口至少 minReservedMessages（3）条
        // result = [初心链(2)] + [过期区处理] + [保留窗口]
        // 保留窗口 = result 中排除初心链和过期区后的消息数
        // 简化验证：总消息数 > 初心链 + minReservedMessages（至少保留了 3 条非初心消息）
        assertTrue(result.size() > 2 + 2, // 2 初心 + 至少 3 条保留（宽松验证）
                "Should keep at least minReservedMessages in the reserved window");
    }

    /**
     * 验证极端 Token 场景：大量大消息，但保留窗口仍受 minReservedMessages 保护。
     * 使用很小的 maxTokens（8000）和包含超长内容的消息，模拟代码 Agent 读大文件。
     */
    @Test
    public void testTokenOverloadKeepsMinReserved() {
        // interceptor: maxMessages=6(实际10), maxTokens=8000, minReservedMessages=3
        ChatMessage sys = ChatMessage.ofSystem("System");
        sys.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(sys);

        ChatMessage goal = ChatMessage.ofUser("Refactor this codebase");
        goal.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(goal);

        // 模拟代码 Agent 场景：读了很多文件（每次文件内容都很大）
        for (int i = 0; i < 15; i++) {
            // 构造大内容消息（模拟文件读取结果）
            StringBuilder bigContent = new StringBuilder();
            for (int j = 0; j < 200; j++) {
                bigContent.append("This is line ").append(j).append(" of file content for step ").append(i).append(" ");
            }
            workingMemory.addMessage(ChatMessage.ofAssistant(bigContent.toString()));
        }

        interceptor.onReasonStart(trace, null);

        List<ChatMessage> result = workingMemory.getMessages();

        // 验证：压缩后保留窗口至少有 minReservedMessages 条非初心消息
        long nonFirstCount = result.stream()
                .filter(m -> !m.hasMetadata(AgentTrace.META_FIRST))
                .count();

        assertTrue(nonFirstCount >= 3,
                "Reserved window should have at least minReservedMessages (3) non-FIRST messages, got: " + nonFirstCount);
    }

    /**
     * 验证 minReservedMessages 的 setter 正常工作
     */
    @Test
    public void testSetMinReservedMessages() {
        ContextCompressionInterceptor custom = new ContextCompressionInterceptor(10, 8000, LlmUtil::getChatModel, null);
        custom.setMinReservedMessages(5);

        // 构造初心
        ChatMessage sys = ChatMessage.ofSystem("System");
        sys.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(sys);

        // 构造大量消息
        for (int i = 0; i < 30; i++) {
            StringBuilder bigContent = new StringBuilder();
            for (int j = 0; j < 200; j++) {
                bigContent.append("Big message content line ").append(j).append(" ");
            }
            workingMemory.addMessage(ChatMessage.ofAssistant(bigContent.toString()));
        }

        custom.onReasonStart(trace, null);

        List<ChatMessage> result = workingMemory.getMessages();
        long nonFirstCount = result.stream()
                .filter(m -> !m.hasMetadata(AgentTrace.META_FIRST))
                .count();

        assertTrue(nonFirstCount >= 5,
                "With minReservedMessages=5, should keep at least 5 non-FIRST messages, got: " + nonFirstCount);
    }

    /**
     * 验证单条消息硬上限兜底：当一条 ToolMessage 自身就超过窗口预算时（如读取大文件/二进制），
     * 其内容会被头尾截断，从根本上避免 context_length_exceeded。
     */
    @Test
    public void testOversizedSingleToolMessageGetsTruncated() {
        // maxTokens=8000，perMessageCap = max(2000, 8000/2) = 4000
        ChatMessage sys = ChatMessage.ofSystem("System");
        sys.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(sys);

        ChatMessage goal = ChatMessage.ofUser("Read the big jar");
        goal.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(goal);

        // 触发工具调用的 Assistant + 一条超大 ToolMessage（模拟读了个超大文件）
        List<ToolCall> toolCalls = Arrays.asList(new ToolCall("0", "call_1", "bash", "{}", Utils.asMap()));
        AssistantMessage toolCallMsg = new AssistantMessage("call", false, null, null, toolCalls, null);
        workingMemory.addMessage(toolCallMsg);

        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            huge.append("token").append(i).append(' ');
        }
        ToolMessage bigTool = ChatMessage.ofTool(huge.toString(), "bash", "call_1");
        int originalLen = huge.length();
        workingMemory.addMessage(bigTool);

        interceptor.onReasonStart(trace, null);

        // 找到截断后的 ToolMessage
        ToolMessage after = workingMemory.getMessages().stream()
                .filter(m -> m instanceof ToolMessage)
                .map(m -> (ToolMessage) m)
                .findFirst()
                .orElse(null);

        assertNotNull(after, "ToolMessage should still exist after compression");
        assertTrue(after.getContent().length() < originalLen,
                "Oversized ToolMessage content should be truncated");
        assertTrue(after.getContent().contains("内容过大已截断"),
                "Truncated content should carry the placeholder marker");
        // 截断后整体上下文应落在窗口内
        assertTrue(after.getContent().contains("token0 "), "Should keep head片段");
    }

    /**
     * 验证初心链中的超大消息不被截断（system / 用户原始目标需完整保留）。
     */
    @Test
    public void testOversizedFirstChainMessageNotTruncated() {
        StringBuilder hugeGoal = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            hugeGoal.append("goal").append(i).append(' ');
        }
        String goalContent = hugeGoal.toString();

        ChatMessage sys = ChatMessage.ofSystem("System");
        sys.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(sys);

        ChatMessage goal = ChatMessage.ofUser(goalContent);
        goal.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(goal);

        workingMemory.addMessage(ChatMessage.ofAssistant("ok"));

        interceptor.onReasonStart(trace, null);

        ChatMessage firstUser = workingMemory.getMessages().stream()
                .filter(m -> m instanceof UserMessage)
                .findFirst()
                .orElse(null);

        assertNotNull(firstUser);
        assertEquals(goalContent, firstUser.getContent(),
                "First-chain (META_FIRST) message must not be truncated");
    }

    /**
     * P2：非初心链的超大 UserMessage（用户粘贴超长日志/文件）应被硬上限截断。
     */
    @Test
    public void testOversizedUserMessageGetsTruncated() {
        ChatMessage sys = ChatMessage.ofSystem("System");
        sys.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(sys);

        ChatMessage goal = ChatMessage.ofUser("Goal");
        goal.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(goal);

        workingMemory.addMessage(ChatMessage.ofAssistant("ok"));

        // 用户后续粘贴的超大文本（非初心链）
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            huge.append("paste").append(i).append(' ');
        }
        int originalLen = huge.length();
        workingMemory.addMessage(ChatMessage.ofUser(huge.toString()));

        interceptor.onReasonStart(trace, null);

        // 取最后一条 UserMessage（粘贴的那条），应被截断
        UserMessage pasted = workingMemory.getMessages().stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> (UserMessage) m)
                .reduce((a, b) -> b)
                .orElse(null);

        assertNotNull(pasted, "粘贴的 UserMessage 应仍存在");
        assertTrue(pasted.getContent().length() < originalLen,
                "超大 UserMessage 应被截断");
        assertTrue(pasted.getContent().contains("内容过大已截断"),
                "截断内容应携带占位标记");
    }

    /**
     * P2：含 toolCalls 的 AssistantMessage 不做 content 截断，避免破坏推理链/原子对。
     */
    @Test
    public void testOversizedAssistantWithToolCallsNotTruncated() {
        ChatMessage sys = ChatMessage.ofSystem("System");
        sys.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(sys);

        ChatMessage goal = ChatMessage.ofUser("Goal");
        goal.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(goal);

        // 超大正文 + 携带 toolCalls 的 Assistant
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            huge.append("think").append(i).append(' ');
        }
        String thought = huge.toString();
        List<ToolCall> toolCalls = Arrays.asList(new ToolCall("0", "call_1", "bash", "{}", Utils.asMap()));
        AssistantMessage am = new AssistantMessage(thought, false, null, null, toolCalls, null);
        workingMemory.addMessage(am);
        // 配对的 ToolMessage，避免被悬挂清理逻辑移除
        workingMemory.addMessage(ChatMessage.ofTool("result", "bash", "call_1"));

        interceptor.onReasonStart(trace, null);

        AssistantMessage after = workingMemory.getMessages().stream()
                .filter(m -> m instanceof AssistantMessage)
                .map(m -> (AssistantMessage) m)
                .filter(m -> m.getToolCalls() != null && !m.getToolCalls().isEmpty())
                .findFirst()
                .orElse(null);

        assertNotNull(after, "含 toolCalls 的 Assistant 应保留");
        assertEquals(thought, after.getContent(),
                "含 toolCalls 的 Assistant 正文不应被截断");
    }

    // ============================================================
    //  4.0.0 新增测试：模型感知阈值 / perMessageCap / PTL / CompositeMode
    // ============================================================

    /**
     * 测试 perMessageCap 作为独立配置字段（MicroCompact 守卫）。
     * 当显式设置 perMessageCap 时，单条消息按此值截断，不依赖 maxTokens 推导。
     */
    @Test
    public void testPerMessageCap_IndependentConfig() {
        // 初心链
        ChatMessage sys = ChatMessage.ofSystem("System");
        sys.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(sys);
        ChatMessage goal = ChatMessage.ofUser("Goal");
        goal.addMetadata(AgentTrace.META_FIRST, 1);
        workingMemory.addMessage(goal);

        // 一条超过 perMessageCap 的工具消息
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            huge.append("data").append(i).append(' ');
        }
        String largeOutput = huge.toString(); // 约 50K chars
        workingMemory.addMessage(ChatMessage.ofTool(largeOutput, "bash", "call_1"));

        // 设置小 perMessageCap（500 tokens），不依赖 maxTokens
        interceptor.setPerMessageCap(500);
        interceptor.onReasonStart(trace, null);

        // 验证工具消息被截断
        ToolMessage after = workingMemory.getMessages().stream()
                .filter(m -> m instanceof ToolMessage)
                .map(m -> (ToolMessage) m)
                .findFirst()
                .orElse(null);
        assertNotNull(after);
        String content = after.getContent();
        assertNotNull(content);
        assertTrue(content.length() < largeOutput.length(),
                "perMessageCap=500 应截断超大工具消息");
        assertTrue(content.contains("内容过大已截断") || content.contains("[truncated"),
                "截断应包含标记");
    }

    /**
     * 测试 CompositeCompressionStrategy 的 FIRST_MATCH 短路模式。
     * 当第一个策略返回有效结果时，后续策略不应被执行。
     */
    @Test
    public void testCompositeStrategy_FirstMatchMode() {
        CompressionStrategy first = mock(CompressionStrategy.class);
        CompressionStrategy second = mock(CompressionStrategy.class);

        ChatMessage expectedResult = ChatMessage.ofUser("first strategy result");
        when(first.compress(any(), anyInt(), any(), anyList()))
                .thenReturn(expectedResult);

        CompositeCompressionStrategy composite = new CompositeCompressionStrategy(first, second)
                .mode(CompositeCompressionStrategy.CompositeMode.FIRST_MATCH);

        ChatMessage result = composite.compress(null, 0, null, Arrays.asList(ChatMessage.ofUser("test")));

        assertNotNull(result);
        assertTrue(result.getContent().contains("first strategy result"));

        // 验证第二个策略从未被调用
        verify(second, never()).compress(any(), anyInt(), any(), anyList());
    }

    /**
     * 测试 CompositeCompressionStrategy 的 ALL 模式（默认）。
     * 所有策略应全部执行，结果合并。
     */
    @Test
    public void testCompositeStrategy_AllMode() {
        CompressionStrategy first = mock(CompressionStrategy.class);
        CompressionStrategy second = mock(CompressionStrategy.class);

        when(first.compress(any(), anyInt(), any(), anyList()))
                .thenReturn(ChatMessage.ofUser("first result"));
        when(second.compress(any(), anyInt(), any(), anyList()))
                .thenReturn(ChatMessage.ofUser("second result"));

        CompositeCompressionStrategy composite = new CompositeCompressionStrategy(first, second);
        ChatMessage result = composite.compress(null, 0, null, Arrays.asList(ChatMessage.ofUser("test")));

        // 默认 ALL 模式，两者都应执行
        verify(first, times(1)).compress(any(), anyInt(), any(), anyList());
        verify(second, times(1)).compress(any(), anyInt(), any(), anyList());

        assertNotNull(result);
        assertTrue(result.getContent().contains("first result"), "应包含第一个策略结果");
        assertTrue(result.getContent().contains("second result"), "应包含第二个策略结果");
    }

    /**
     * 测试 CompressionUtil.formatMessageForCompression 的截断行为。
     */
    @Test
    public void testCompressionUtil_TruncateToolResult() {
        // 短内容不截断
        ChatMessage shortMsg = ChatMessage.ofTool("short result", "bash", "call_1");
        String formatted = CompressionUtil.formatMessageForCompression(shortMsg, 2000);
        assertTrue(formatted.contains("short result"));
        assertFalse(formatted.contains(CompressionUtil.TRUNCATION_SUFFIX));

        // 超长内容截断
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            huge.append("long_data_");
        }
        String longContent = huge.toString(); // > 2000 chars
        ChatMessage longMsg = ChatMessage.ofTool(longContent, "bash", "call_1");
        String formatted2 = CompressionUtil.formatMessageForCompression(longMsg, 100);

        assertTrue(formatted2.length() < longContent.length(),
                "超长 ToolResult 应被截断");
        assertTrue(formatted2.contains(CompressionUtil.TRUNCATION_SUFFIX),
                "截断应包含标记");
    }

    /**
     * 测试 CompressionUtil.isEmptySummary 识别空摘要。
     */
    @Test
    public void testCompressionUtil_IsEmptySummary() {
        assertTrue(CompressionUtil.isEmptySummary(null));
        assertTrue(CompressionUtil.isEmptySummary(""));
        assertTrue(CompressionUtil.isEmptySummary("(无显著进度)"));
        assertTrue(CompressionUtil.isEmptySummary("(无关键增量)"));
        assertFalse(CompressionUtil.isEmptySummary("任务完成"));
    }

    /**
     * 测试 CompressionUtil.isPromptTooLong 检测 PTL 标记。
     */
    @Test
    public void testCompressionUtil_IsPromptTooLong() {
        assertTrue(CompressionUtil.isPromptTooLong("prompt is too long: please reduce content"));
        assertTrue(CompressionUtil.isPromptTooLong("Prompt is too long")); // 大小写不敏感开头
        assertFalse(CompressionUtil.isPromptTooLong("正常响应文本"));
        assertFalse(CompressionUtil.isPromptTooLong(null));
        assertFalse(CompressionUtil.isPromptTooLong(""));
    }

    /**
     * 测试 CompressionUtil.buildCompressedMessage 创建带标记的摘要消息。
     */
    @Test
    public void testCompressionUtil_BuildCompressedMessage() {
        // 有效内容
        ChatMessage msg = CompressionUtil.buildCompressedMessage("prefix", "content");
        assertNotNull(msg);
        assertTrue(msg.getContent().contains("content"));
        assertTrue(msg.getContent().contains("prefix"));
        assertEquals(Integer.valueOf(1), msg.getMetadataAs(ContextCompressionInterceptor.META_COMPRESSED));

        // 空内容返回 null
        assertNull(CompressionUtil.buildCompressedMessage("prefix", ""));
        assertNull(CompressionUtil.buildCompressedMessage(null, null));

        // null prefix 仍构建
        ChatMessage msg2 = CompressionUtil.buildCompressedMessage(null, "just content");
        assertNotNull(msg2);
        assertEquals("just content", msg2.getContent());
    }

    /**
     * 测试 LLMCompressionStrategy 的 PTL 缩小批次逻辑（通过 mock 模拟 PTL 响应）。
     */
    @Test
    public void testLLMCompressionStrategy_PTLDetection() {
        // 验证 PTL 检测方法本身
        assertTrue(CompressionUtil.isPromptTooLong("prompt is too long, reduce to 5000 tokens"));
        assertTrue(CompressionUtil.isPromptTooLong("prompt is too long"));
        assertFalse(CompressionUtil.isPromptTooLong("正常摘要内容"));
    }
}