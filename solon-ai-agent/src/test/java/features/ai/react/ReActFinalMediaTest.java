package features.ai.react;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.source.Citation;
import org.noear.solon.ai.chat.source.SearchResult;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ReAct 终态多模态保留回归。
 *
 * <p>终态 media 有两个来源，均由 buildFinalAssistantMessage 合并：
 * lastReason 自带 media（生图 / media-only），以及 trace.finalMediaBlocks（returnDirect 工具产出）。</p>
 */
public class ReActFinalMediaTest {

    private AssistantMessage invokeBuild(ReActAgent agent, String result, ReActTrace trace) throws Exception {
        Method method = ReActAgent.class.getDeclaredMethod(
                "buildFinalAssistantMessage", String.class, ReActTrace.class);
        method.setAccessible(true);
        return (AssistantMessage) method.invoke(agent, result, trace);
    }

    private ReActTrace mockTrace(AssistantMessage lastReason, List<ContentBlock> finalMedia) {
        ReActTrace trace = mock(ReActTrace.class);
        when(trace.getLastReasonMessage()).thenReturn(lastReason);
        when(trace.getFinalMediaBlocks()).thenReturn(finalMedia);
        return trace;
    }

    @Test
    @DisplayName("终态：保留通用载荷、块顺序并清理失效 legacy 协议投影")
    public void buildFinalAssistantMessage_preservesBasePayloadAndDeduplicatesMedia() throws Exception {
        ReActAgent agent = ReActAgent.of(mock(ChatModel.class)).name("media-final").build();

        List<org.noear.solon.ai.chat.tool.ToolCall> toolCalls = Collections.singletonList(
                new org.noear.solon.ai.chat.tool.ToolCall("0", "call-1", "search", "{}", Collections.<String, Object>emptyMap()));
        ImageBlock baseImage = ImageBlock.ofUrl("https://example.com/same.png");
        ImageBlock baseImageAgain = ImageBlock.ofUrl("https://example.com/same.png");
        ImageBlock directImage = ImageBlock.ofUrl("https://example.com/same.png");
        ImageBlock newDirectImage = ImageBlock.ofUrl("https://example.com/new.png");
        TextBlock baseText = TextBlock.of("old", "text/markdown");
        baseText.metas().put("format", "markdown");
        List<ContentBlock> baseBlocks = Arrays.asList(baseImage, baseText, baseImageAgain);
        AssistantMessage semantic = AssistantMessage.snapshot(
                "old", "thinking", toolCalls, baseBlocks,
                Collections.singletonList(new SearchResult().title("Solon").url("https://solon.noear.org")),
                Collections.singletonList(new Citation().type("url_citation").url("https://solon.noear.org/docs")),
                null);
        ONode legacyJson = ONode.ofJson(ChatMessage.toJson(semantic));
        legacyJson.getOrNew("contentRaw").asObject().set("provider", "legacy");
        legacyJson.getOrNew("toolCallsRaw").asArray().addNew().set("name", "search");
        legacyJson.getOrNew("searchResultsRaw").asArray().addNew().set("source", "web");
        legacyJson.set("reasoningFieldName", "reasoning_content");
        AssistantMessage lastReason = (AssistantMessage) ChatMessage.fromJson(legacyJson.toJson());
        lastReason.addMetadata("trace", "kept");

        AssistantMessage finalMsg = invokeBuild(agent, "final", mockTrace(lastReason,
                Arrays.<ContentBlock>asList(directImage, newDirectImage)));

        assertEquals("final", finalMsg.getText());
        assertEquals("thinking", finalMsg.getThinking());
        assertNull(finalMsg.getContentRaw(), "正文和媒体发生变化后旧 raw 必须失效");
        assertNull(finalMsg.getToolCallsRaw(), "typed toolCalls 已足够重建，不复制旧 raw");
        assertEquals("call-1", finalMsg.getToolCalls().get(0).getId());
        assertNull(finalMsg.getSearchResultsRaw(), "终态语义投影不再传播旧搜索 raw");
        assertEquals("https://solon.noear.org", finalMsg.getSearchResults().get(0).getUrl());
        assertEquals("https://solon.noear.org/docs", finalMsg.getCitations().get(0).getUrl());
        assertNull(finalMsg.getReasoningFieldName(), "目标方言应在用时决定 reasoning 字段");
        assertEquals("kept", finalMsg.getMetadataAs("trace"));
        assertNotSame(lastReason.getMetadata(), finalMsg.getMetadata());

        assertEquals(4, finalMsg.getBlocks().size());
        assertEquals("https://example.com/same.png", finalMsg.getBlocks().get(0).getContent());
        assertEquals("final", finalMsg.getBlocks().get(1).getContent());
        assertEquals("text/markdown", finalMsg.getBlocks().get(1).getMimeType());
        assertEquals("markdown", finalMsg.getBlocks().get(1).metas().get("format"));
        assertEquals("https://example.com/same.png", finalMsg.getBlocks().get(2).getContent());
        assertSame(newDirectImage, finalMsg.getBlocks().get(3));
    }


    @Test
    @DisplayName("终态：语义未变化时保留协议状态，变化时失效")
    public void buildFinalAssistantMessage_preservesOnlyMatchingProtocolState() throws Exception {
        ReActAgent agent = ReActAgent.of(mock(ChatModel.class)).name("protocol-final").build();
        AssistantMessage original = AssistantMessage.snapshot(
                "same", "thinking", null, null, null, null,
                Collections.singletonMap("anthropic.messages", new MessageProtocolState(1,
                        Collections.<String, Object>singletonMap("signature", "sig"))));

        AssistantMessage same = invokeBuild(agent, "same", mockTrace(original, null));
        assertNotNull(same.getProtocolState("anthropic.messages"));

        AssistantMessage changed = invokeBuild(agent, "changed", mockTrace(original, null));
        assertFalse(changed.hasProtocolStates());
    }

    @Test
    @DisplayName("终态：lastReason 有 media 时返回消息保留 blocks")
    public void buildFinalAssistantMessage_keepsMedia() throws Exception {
        ReActAgent agent = ReActAgent.of(mock(ChatModel.class)).name("media-final").build();

        AssistantMessage lastReason = ChatMessage.ofAssistant(
                "",
                ImageBlock.ofUrl("https://example.com/final.png"));

        AssistantMessage finalMsg = invokeBuild(agent, "", mockTrace(lastReason, null));

        assertTrue(finalMsg.hasMedia(), "final message should keep media from lastReason");
        assertEquals(1, finalMsg.getBlocks().stream()
                .filter(b -> b instanceof ImageBlock)
                .count());
        assertEquals("https://example.com/final.png",
                ((ImageBlock) finalMsg.getBlocks().stream()
                        .filter(b -> b instanceof ImageBlock)
                        .findFirst()
                        .get()).getUrl());
    }

    @Test
    @DisplayName("终态：文本 finalAnswer + media 同时保留")
    public void buildFinalAssistantMessage_textAndMedia() throws Exception {
        ReActAgent agent = ReActAgent.of(mock(ChatModel.class)).name("media-final").build();

        AssistantMessage lastReason = ChatMessage.ofAssistant(
                "raw think",
                ImageBlock.ofUrl("https://example.com/a.png"));

        AssistantMessage finalMsg = invokeBuild(agent, "Final Answer: done", mockTrace(lastReason, null));

        assertEquals("Final Answer: done", finalMsg.getContent());
        assertTrue(finalMsg.hasMedia());
    }

    @Test
    @DisplayName("终态：无 media 时保持纯文本")
    public void buildFinalAssistantMessage_textOnly() throws Exception {
        ReActAgent agent = ReActAgent.of(mock(ChatModel.class)).name("media-final").build();

        AssistantMessage lastReason = ChatMessage.ofAssistant("hello");

        AssistantMessage finalMsg = invokeBuild(agent, "hello world", mockTrace(lastReason, null));

        assertEquals("hello world", finalMsg.getContent());
        assertFalse(finalMsg.hasMedia());
    }

    @Test
    @DisplayName("终态：returnDirect 工具 media 走 trace.finalMediaBlocks 也被保留")
    public void buildFinalAssistantMessage_directMedia() throws Exception {
        ReActAgent agent = ReActAgent.of(mock(ChatModel.class)).name("media-final").build();

        // lastReason 是带 tool_call 的推理消息，本身无 media
        AssistantMessage lastReason = ChatMessage.ofAssistant("");
        List<ContentBlock> directMedia = Collections.singletonList(
                ImageBlock.ofUrl("https://example.com/direct.png"));

        AssistantMessage finalMsg = invokeBuild(agent, "杭州晴", mockTrace(lastReason, directMedia));

        assertEquals("杭州晴", finalMsg.getContent());
        assertTrue(finalMsg.hasMedia(), "final message should keep media from trace.finalMediaBlocks");
        assertEquals("https://example.com/direct.png",
                ((ImageBlock) finalMsg.getBlocks().stream()
                        .filter(b -> b instanceof ImageBlock)
                        .findFirst()
                        .get()).getUrl());
    }
}
