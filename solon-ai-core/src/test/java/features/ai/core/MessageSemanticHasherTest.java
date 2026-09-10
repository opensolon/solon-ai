package features.ai.core;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.message.MessageSemanticHasher;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MessageSemanticHasherTest {
    @Test
    public void legacyRawToolCallNameIdAndArgumentsChangesInvalidateHash() {
        AssistantMessage payload = rawOnly("call-1", "lookup", "{\"a\":1,\"b\":2}");
        MessageProtocolState protocolState = new MessageProtocolState(1);
        protocolState.setSemanticHash(MessageSemanticHasher.hash(payload));
        ONode historical = ONode.ofJson(ChatMessage.toJson(payload));
        historical.set("protocolStates", Collections.singletonMap("vendor.protocol", protocolState));
        AssistantMessage original = (AssistantMessage) ChatMessage.fromJson(historical.toJson());
        MessageProtocolState state = original.getProtocolState("vendor.protocol");
        assertTrue(MessageSemanticHasher.matches(original, state));

        assertFalse(MessageSemanticHasher.matches(
                rawOnly("call-1", "search", "{\"a\":1,\"b\":2}"), state));
        assertFalse(MessageSemanticHasher.matches(
                rawOnly("call-2", "lookup", "{\"a\":1,\"b\":2}"), state));
        assertFalse(MessageSemanticHasher.matches(
                rawOnly("call-1", "lookup", "{\"a\":1,\"b\":3}"), state));
    }

    @Test
    public void legacyRawToolCallArgumentsObjectKeyOrderIsStable() {
        AssistantMessage first = rawOnly("call-1", "lookup", "{\"a\":1,\"b\":2}");
        AssistantMessage reordered = rawOnly("call-1", "lookup", "{\"b\":2,\"a\":1}");

        assertEquals(MessageSemanticHasher.hash(first), MessageSemanticHasher.hash(reordered));
    }

    private AssistantMessage rawOnly(String id, String name, String arguments) {
        ONode legacy = new ONode().set("role", "assistant").set("text", "").set("thinking", "");
        legacy.getOrNew("toolCallsRaw").asArray().addNew()
                .set("id", id)
                .set("type", "function")
                .getOrNew("function")
                .set("name", name)
                .set("arguments", arguments);
        return (AssistantMessage) ChatMessage.fromJson(legacy.toJson());
    }
}
