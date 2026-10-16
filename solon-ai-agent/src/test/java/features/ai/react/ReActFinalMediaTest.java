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
 * <p>终态 media 统一通过 trace.finalMediaBlocks 传递；ReActAgent 不从 lastReason 猜测终态内容。</p>
 */
public class ReActFinalMediaTest {

    private AssistantMessage invokeBuild(ReActAgent agent, String result, ReActTrace trace) throws Exception {
        Method method = ReActAgent.class.getDeclaredMethod(
                "buildFinalAssistantMessage", String.class, ReActTrace.class);
        method.setAccessible(true);
        return (AssistantMessage) method.invoke(agent, result, trace);
    }

    private ReActTrace mockTrace(List<ContentBlock> finalMedia) {
        ReActTrace trace = mock(ReActTrace.class);
        when(trace.getFinalMediaBlocks()).thenReturn(finalMedia);
        return trace;
    }

    @Test
    @DisplayName("终态：只保留 finalMediaBlocks 中的媒体")
    public void buildFinalAssistantMessage_preservesOnlyFinalPayload() throws Exception {
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

        AssistantMessage finalMsg = invokeBuild(agent, "final", mockTrace(
                Arrays.<ContentBlock>asList(directImage, newDirectImage)));

        assertEquals("final", finalMsg.getText());
        assertEquals("", finalMsg.getThinking());
        assertNull(finalMsg.getToolCalls());
        assertNull(finalMsg.getSearchResults());
        assertNull(finalMsg.getCitations());
        assertTrue(finalMsg.getMetadata() == null || finalMsg.getMetadata().isEmpty());
        assertFalse(finalMsg.hasProtocolStates());

        assertEquals(3, finalMsg.getBlocks().size());
        assertEquals("final", finalMsg.getBlocks().get(0).getContent());
        assertEquals("https://example.com/same.png", finalMsg.getBlocks().get(1).getContent());
        assertEquals("https://example.com/new.png", finalMsg.getBlocks().get(2).getContent());
    }


    @Test
    @DisplayName("终态：不保留任何方言协议状态")
    public void buildFinalAssistantMessage_preservesOnlyMatchingProtocolState() throws Exception {
        ReActAgent agent = ReActAgent.of(mock(ChatModel.class)).name("protocol-final").build();
        AssistantMessage original = AssistantMessage.snapshot(
                "same", "thinking", null, null, null, null,
                Collections.singletonMap("anthropic.messages", new MessageProtocolState(1,
                        Collections.<String, Object>singletonMap("signature", "sig"))));

        AssistantMessage same = invokeBuild(agent, "same", mockTrace(null));
        assertFalse(same.hasProtocolStates());

        AssistantMessage changed = invokeBuild(agent, "changed", mockTrace(null));
        assertFalse(changed.hasProtocolStates());
    }

    @Test
    @DisplayName("中间 lastReason 的 media 不应泄漏到最终消息")
    public void buildFinalAssistantMessage_ignoresLastReasonMedia() throws Exception {
        ReActAgent agent = ReActAgent.of(mock(ChatModel.class)).name("media-final").build();
        AssistantMessage finalMsg = invokeBuild(agent, "", mockTrace(null));

        assertFalse(finalMsg.hasMedia());
    }

    @Test
    @DisplayName("终态：文本 finalAnswer + 两类 media 同时保留")
    public void buildFinalAssistantMessage_textAndMedia() throws Exception {
        ReActAgent agent = ReActAgent.of(mock(ChatModel.class)).name("media-final").build();

        AssistantMessage finalMsg = invokeBuild(agent, "Final Answer: done", mockTrace(
                Collections.<ContentBlock>singletonList(ImageBlock.ofUrl("https://example.com/final.png"))));

        assertEquals("Final Answer: done", finalMsg.getContent());
        assertTrue(finalMsg.hasMedia());
        assertEquals(2, finalMsg.getBlocks().size());
        assertEquals("https://example.com/final.png",
                ((ImageBlock) finalMsg.getBlocks().get(1)).getUrl());
    }

    @Test
    @DisplayName("终态：无 media 时保持纯文本")
    public void buildFinalAssistantMessage_textOnly() throws Exception {
        ReActAgent agent = ReActAgent.of(mock(ChatModel.class)).name("media-final").build();

        AssistantMessage finalMsg = invokeBuild(agent, "hello world", mockTrace(null));

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

        AssistantMessage finalMsg = invokeBuild(agent, "杭州晴", mockTrace(directMedia));

        assertEquals("杭州晴", finalMsg.getContent());
        assertTrue(finalMsg.hasMedia(), "final message should keep media from trace.finalMediaBlocks");
        assertEquals("https://example.com/direct.png",
                ((ImageBlock) finalMsg.getBlocks().stream()
                        .filter(b -> b instanceof ImageBlock)
                        .findFirst()
                        .get()).getUrl());
    }
}
