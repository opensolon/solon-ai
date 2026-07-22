package features.ai.react;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * ReAct 终态多模态保留回归。
 */
public class ReActFinalMediaTest {

    @Test
    @DisplayName("终态：lastReason 有 media 时返回消息保留 blocks")
    public void buildFinalAssistantMessage_keepsMedia() throws Exception {
        ReActAgent agent = ReActAgent.of(mock(ChatModel.class)).name("media-final").build();

        AssistantMessage lastReason = ChatMessage.ofAssistant(
                "",
                ImageBlock.ofUrl("https://example.com/final.png"));

        Method method = ReActAgent.class.getDeclaredMethod(
                "buildFinalAssistantMessage", String.class, AssistantMessage.class);
        method.setAccessible(true);

        AssistantMessage finalMsg = (AssistantMessage) method.invoke(agent, "", lastReason);

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

        Method method = ReActAgent.class.getDeclaredMethod(
                "buildFinalAssistantMessage", String.class, AssistantMessage.class);
        method.setAccessible(true);

        AssistantMessage finalMsg = (AssistantMessage) method.invoke(
                agent, "Final Answer: done", lastReason);

        assertEquals("Final Answer: done", finalMsg.getContent());
        assertTrue(finalMsg.hasMedia());
    }

    @Test
    @DisplayName("终态：无 media 时保持纯文本")
    public void buildFinalAssistantMessage_textOnly() throws Exception {
        ReActAgent agent = ReActAgent.of(mock(ChatModel.class)).name("media-final").build();

        AssistantMessage lastReason = ChatMessage.ofAssistant("hello");

        Method method = ReActAgent.class.getDeclaredMethod(
                "buildFinalAssistantMessage", String.class, AssistantMessage.class);
        method.setAccessible(true);

        AssistantMessage finalMsg = (AssistantMessage) method.invoke(
                agent, "hello world", lastReason);

        assertEquals("hello world", finalMsg.getContent());
        assertFalse(finalMsg.hasMedia());
    }
}
