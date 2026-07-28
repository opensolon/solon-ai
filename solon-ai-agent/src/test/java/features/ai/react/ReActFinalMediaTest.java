package features.ai.react;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

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
