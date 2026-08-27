package features.ai.react.intercept;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.compress.CompressionUtil;
import org.noear.solon.ai.agent.react.intercept.compress.HierarchicalCompressionStrategy;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class HierarchicalCompressionStrategyTest {
    private static final String SUMMARY_KEY = "agent:summary:hierarchical";

    @Test
    public void normalPathCallsModelOnceAndCommits() throws Exception {
        ReActTrace trace = mock(ReActTrace.class);
        when(trace.getExtraAs(SUMMARY_KEY)).thenReturn("S0");

        ChatModel model = mockModel(response("S1"));
        ChatMessage result = new HierarchicalCompressionStrategy().compress(
                model, 1, trace, Arrays.asList(ChatMessage.ofAssistant("A")));

        assertNotNull(result);
        assertTrue(result.getContent().contains("S1"));
        verify(model, times(1)).prompt(anyString());
        verify(trace, times(1)).setExtra(SUMMARY_KEY, "S1");
    }

    @Test
    public void ptlSplitsInOrderWithoutDroppingHistory() throws Exception {
        ReActTrace trace = mock(ReActTrace.class);
        when(trace.getExtraAs(SUMMARY_KEY)).thenReturn("S0");

        ChatRequestDesc request = mock(ChatRequestDesc.class);
        when(request.options(any(java.util.function.Consumer.class))).thenReturn(request);
        when(request.call())
                .thenThrow(new RuntimeException("context_length_exceeded"))
                .thenReturn(response("S1"))
                .thenReturn(response("S2"));
        ChatModel model = mock(ChatModel.class);
        when(model.prompt(anyString())).thenReturn(request);

        ChatMessage result = new HierarchicalCompressionStrategy().compress(model, 3, trace,
                Arrays.asList(ChatMessage.ofAssistant("oldest-A"), ChatMessage.ofAssistant("newest-B")));

        assertNotNull(result);
        assertTrue(result.getContent().contains("S2"));
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).prompt(prompts.capture());
        assertTrue(prompts.getAllValues().get(0).contains("oldest-A"));
        assertTrue(prompts.getAllValues().get(0).contains("newest-B"));
        assertTrue(prompts.getAllValues().get(1).contains("oldest-A"));
        assertFalse(prompts.getAllValues().get(1).contains("newest-B"));
        assertTrue(prompts.getAllValues().get(2).contains("S1"));
        assertTrue(prompts.getAllValues().get(2).contains("newest-B"));
        verify(trace, times(1)).setExtra(SUMMARY_KEY, "S2");
    }

    @Test
    public void failedLaterChunkDoesNotCommitIntermediateSummary() throws Exception {
        ReActTrace trace = mock(ReActTrace.class);
        when(trace.getExtraAs(SUMMARY_KEY)).thenReturn("S0");

        ChatRequestDesc request = mock(ChatRequestDesc.class);
        when(request.options(any(java.util.function.Consumer.class))).thenReturn(request);
        when(request.call())
                .thenThrow(new RuntimeException("context_length_exceeded"))
                .thenReturn(response("S1"))
                .thenThrow(new RuntimeException("network failed"));
        ChatModel model = mock(ChatModel.class);
        when(model.prompt(anyString())).thenReturn(request);

        ChatMessage result = new HierarchicalCompressionStrategy().compress(model, 1, trace,
                Arrays.asList(ChatMessage.ofAssistant("A"), ChatMessage.ofAssistant("B")));

        assertNull(result);
        verify(model, times(3)).prompt(anyString());
        verify(trace, never()).setExtra(eq(SUMMARY_KEY), anyString());
    }

    @Test
    public void failedFirstChunkIsNotRetriedAfterPtl() throws Exception {
        ReActTrace trace = mock(ReActTrace.class);
        when(trace.getExtraAs(SUMMARY_KEY)).thenReturn("S0");

        ChatRequestDesc request = mock(ChatRequestDesc.class);
        when(request.options(any(java.util.function.Consumer.class))).thenReturn(request);
        when(request.call())
                .thenThrow(new RuntimeException("context_length_exceeded"))
                .thenThrow(new RuntimeException("network failed"));
        ChatModel model = mock(ChatModel.class);
        when(model.prompt(anyString())).thenReturn(request);

        ChatMessage result = new HierarchicalCompressionStrategy().compress(model, 100, trace,
                Arrays.asList(ChatMessage.ofAssistant("A"), ChatMessage.ofAssistant("B")));

        assertNull(result);
        verify(model, times(2)).prompt(anyString());
        verify(request, times(2)).call();
        verify(trace, never()).setExtra(eq(SUMMARY_KEY), anyString());
    }

    @Test
    public void ordinaryFailureIsNotRetriedEvenWithHighRetryConfiguration() throws Exception {
        ReActTrace trace = mock(ReActTrace.class);
        when(trace.getExtraAs(SUMMARY_KEY)).thenReturn("S0");

        ChatRequestDesc request = mock(ChatRequestDesc.class);
        when(request.options(any(java.util.function.Consumer.class))).thenReturn(request);
        when(request.call()).thenThrow(new RuntimeException("network failed"));
        ChatModel model = mock(ChatModel.class);
        when(model.prompt(anyString())).thenReturn(request);

        ChatMessage result = new HierarchicalCompressionStrategy().compress(model, 100, trace,
                Arrays.asList(ChatMessage.ofAssistant("A"), ChatMessage.ofAssistant("B")));

        assertNull(result);
        verify(model, times(1)).prompt(anyString());
        verify(request, times(1)).call();
        verify(trace, never()).setExtra(eq(SUMMARY_KEY), anyString());
    }

    @Test
    public void ptlFallbackNeverExceedsThreeModelCalls() throws Exception {
        ReActTrace trace = mock(ReActTrace.class);
        when(trace.getExtraAs(SUMMARY_KEY)).thenReturn("S0");

        ChatRequestDesc request = mock(ChatRequestDesc.class);
        when(request.options(any(java.util.function.Consumer.class))).thenReturn(request);
        when(request.call())
                .thenThrow(new RuntimeException("context_length_exceeded"))
                .thenReturn(response("S1"))
                .thenThrow(new RuntimeException("context_length_exceeded"));
        ChatModel model = mock(ChatModel.class);
        when(model.prompt(anyString())).thenReturn(request);

        ChatMessage result = new HierarchicalCompressionStrategy().compress(model, 3, trace,
                Arrays.asList(ChatMessage.ofAssistant("A"), ChatMessage.ofAssistant("B")));

        assertNull(result);
        verify(model, times(3)).prompt(anyString());
        verify(request, times(3)).call();
        verifyNoMoreInteractions(model);
        verify(trace, never()).setExtra(eq(SUMMARY_KEY), anyString());
    }

    @Test
    public void ptlSplitKeepsToolCallAndObservationTogether() throws Exception {
        ReActTrace trace = mock(ReActTrace.class);
        when(trace.getExtraAs(SUMMARY_KEY)).thenReturn("S0");

        ChatRequestDesc request = mock(ChatRequestDesc.class);
        when(request.options(any(java.util.function.Consumer.class))).thenReturn(request);
        when(request.call())
                .thenThrow(new RuntimeException("context_length_exceeded"))
                .thenReturn(response("S1"))
                .thenReturn(response("S2"));
        ChatModel model = mock(ChatModel.class);
        when(model.prompt(anyString())).thenReturn(request);

        AssistantMessage action = new AssistantMessage("call", "", false, null, null,
                Arrays.asList(new ToolCall("0", "call_1", "read", "{}", Utils.asMap())), null);
        ToolMessage observation = ChatMessage.ofTool("tool-result", "read", "call_1");
        ChatMessage recent = ChatMessage.ofAssistant("recent-step");

        new HierarchicalCompressionStrategy().compress(model, 1, trace,
                Arrays.asList(action, observation, recent));

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).prompt(prompts.capture());
        String firstChunk = prompts.getAllValues().get(1);
        assertTrue(firstChunk.contains("调用工具 read"));
        assertTrue(firstChunk.contains("tool-result"));
        assertFalse(firstChunk.contains("recent-step"));
    }

    @Test
    public void ptlTextResponseIsNotCommittedAsSummary() throws Exception {
        ReActTrace trace = mock(ReActTrace.class);
        when(trace.getExtraAs(SUMMARY_KEY)).thenReturn("S0");
        ChatModel model = mockModel(response("prompt is too long"));

        ChatMessage result = new HierarchicalCompressionStrategy().compress(
                model, 1, trace, Arrays.asList(ChatMessage.ofAssistant("indivisible")));

        assertNull(result);
        verify(trace, never()).setExtra(eq(SUMMARY_KEY), anyString());
    }

    @Test
    public void ptlSplitKeepsTextActionAndObservationTogether() throws Exception {
        ReActTrace trace = mock(ReActTrace.class);
        when(trace.getExtraAs(SUMMARY_KEY)).thenReturn("S0");

        ChatRequestDesc request = mock(ChatRequestDesc.class);
        when(request.options(any(java.util.function.Consumer.class))).thenReturn(request);
        when(request.call())
                .thenThrow(new RuntimeException("context_length_exceeded"))
                .thenReturn(response("S1"))
                .thenReturn(response("S2"));
        ChatModel model = mock(ChatModel.class);
        when(model.prompt(anyString())).thenReturn(request);

        ChatMessage action = ChatMessage.ofAssistant("Thought: inspect\nAction: read");
        ChatMessage observation = ChatMessage.ofUser("Observation: text-result");
        ChatMessage recent = ChatMessage.ofAssistant("recent-step");

        new HierarchicalCompressionStrategy().compress(model, 1, trace,
                Arrays.asList(action, observation, recent));

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).prompt(prompts.capture());
        String firstChunk = prompts.getAllValues().get(1);
        assertTrue(firstChunk.contains("Action: read"));
        assertTrue(firstChunk.contains("text-result"));
        assertFalse(firstChunk.contains("recent-step"));
    }

    @Test
    public void maxSummaryLengthIsValidatedAndEnforcedAsHardLimit() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new HierarchicalCompressionStrategy().maxSummaryLength(0));

        ReActTrace trace = mock(ReActTrace.class);
        // 使用足够长的中文文本确保 Token 数超过 maxSummaryLength
        ChatModel model = mockModel(response("这是一段需要被压缩的长文本内容包含了大量的信息需要被总结和精简以确保摘要长度限制能够被正确触发和执行从而验证硬性限制的功能"));
        ChatMessage result = new HierarchicalCompressionStrategy()
                .maxSummaryLength(12)
                .compress(model, 1, trace, Arrays.asList(ChatMessage.ofAssistant("A")));

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(trace).setExtra(eq(SUMMARY_KEY), summary.capture());
        // 验证 Token 数不超过硬性限制（maxSummaryLength 按 Token 计量）
        assertTrue(CompressionUtil.countTokens(summary.getValue()) <= 12);
        assertTrue(result.getContent().contains(summary.getValue()));
    }

    private ChatModel mockModel(ChatResponse response) throws Exception {
        ChatRequestDesc request = mock(ChatRequestDesc.class);
        when(request.options(any(java.util.function.Consumer.class))).thenReturn(request);
        when(request.call()).thenReturn(response);
        ChatModel model = mock(ChatModel.class);
        when(model.prompt(anyString())).thenReturn(request);
        return model;
    }

    private ChatResponse response(String content) {
        ChatResponse response = mock(ChatResponse.class);
        when(response.hasContent()).thenReturn(true);
        when(response.getContent()).thenReturn(content);
        return response;
    }
}
