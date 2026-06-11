package features.ai.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.AssistantMessage;

public class AssistantMessageTest {
    @Test
    public void getReasoningShouldRemoveThinkTagsWhenThinking() {
        AssistantMessage message = new AssistantMessage("<think>analysis</think>", true);

        Assertions.assertEquals("analysis", message.getReasoning());
    }
}
