/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.llm.dialect.gemini;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Gemini thought signature 协议状态边界测试。 */
public class GeminiMessageStateSupportTest {
    private static final String FOREIGN_PROTOCOL_ID = "foreign.protocol";

    @Test
    public void otherGeminiProtocolState_suppressesLegacyFallback() {
        AssistantMessage interactionsOnly = legacyMessage();
        ToolCall interactionsCall = interactionsOnly.getToolCalls().get(0);
        interactionsOnly = withBoundState(interactionsOnly,
                GeminiMessageStateSupport.INTERACTIONS_PROTOCOL_ID,
                GeminiMessageStateSupport.createSignatureState(
                        interactionsCall, 0, "sig_interactions"));

        assertNull(GeminiMessageStateSupport.resolveSignature(interactionsOnly,
                GeminiMessageStateSupport.GENERATE_CONTENT_PROTOCOL_ID, interactionsCall, 0));

        AssistantMessage generateOnly = legacyMessage();
        ToolCall generateCall = generateOnly.getToolCalls().get(0);
        generateOnly = withBoundState(generateOnly,
                GeminiMessageStateSupport.GENERATE_CONTENT_PROTOCOL_ID,
                GeminiMessageStateSupport.createSignatureState(
                        generateCall, 0, "sig_generate"));

        assertNull(GeminiMessageStateSupport.resolveSignature(generateOnly,
                GeminiMessageStateSupport.INTERACTIONS_PROTOCOL_ID, generateCall, 0));
    }

    @Test
    public void bothGeminiProtocolStates_eachResolvesOwnSignature() {
        AssistantMessage message = message(null);
        ToolCall call = message.getToolCalls().get(0);
        Map<String, MessageProtocolState> states = new LinkedHashMap<>();
        states.put(GeminiMessageStateSupport.GENERATE_CONTENT_PROTOCOL_ID,
                GeminiMessageStateSupport.createSignatureState(call, 0, "sig_generate"));
        states.put(GeminiMessageStateSupport.INTERACTIONS_PROTOCOL_ID,
                GeminiMessageStateSupport.createSignatureState(call, 0, "sig_interactions"));
        message = AssistantMessage.snapshot("", "", message.getToolCalls(), null,
                null, null, states);

        assertEquals("sig_generate", GeminiMessageStateSupport.resolveSignature(message,
                GeminiMessageStateSupport.GENERATE_CONTENT_PROTOCOL_ID, call, 0));
        assertEquals("sig_interactions", GeminiMessageStateSupport.resolveSignature(message,
                GeminiMessageStateSupport.INTERACTIONS_PROTOCOL_ID, call, 0));
    }

    @Test
    public void foreignProtocolState_doesNotSuppressLegacyFallback() {
        AssistantMessage base = legacyMessage();
        AssistantMessage message = withBoundState(base, FOREIGN_PROTOCOL_ID,
                new MessageProtocolState(7,
                        Collections.<String, Object>singletonMap("opaque", "foreign")));
        ToolCall call = message.getToolCalls().get(0);

        assertEquals("sig_legacy", GeminiMessageStateSupport.resolveSignature(message,
                GeminiMessageStateSupport.GENERATE_CONTENT_PROTOCOL_ID, call, 0));
        assertEquals("sig_legacy", GeminiMessageStateSupport.resolveSignature(message,
                GeminiMessageStateSupport.INTERACTIONS_PROTOCOL_ID, call, 0));
    }

    @Test
    public void invalidOwnedState_failsClosedForBothProtocols() {
        for (String protocol : Arrays.asList(
                GeminiMessageStateSupport.GENERATE_CONTENT_PROTOCOL_ID,
                GeminiMessageStateSupport.INTERACTIONS_PROTOCOL_ID)) {
            AssistantMessage wrongVersion = legacyMessage();
            ToolCall wrongVersionCall = wrongVersion.getToolCalls().get(0);
            wrongVersion = withBoundState(wrongVersion, protocol, signatureState(
                    GeminiMessageStateSupport.VERSION + 1, wrongVersionCall, "sig_wrong_version"));
            assertNull(GeminiMessageStateSupport.resolveSignature(
                    wrongVersion, protocol, wrongVersionCall, 0), protocol + " wrong version");

            AssistantMessage unbound = legacyMessage();
            ToolCall unboundCall = unbound.getToolCalls().get(0);
            unbound = withRestoredState(unbound, protocol,
                    GeminiMessageStateSupport.createSignatureState(
                            unboundCall, 0, "sig_unbound"));
            assertNull(GeminiMessageStateSupport.resolveSignature(
                    unbound, protocol, unboundCall, 0), protocol + " unbound");

            AssistantMessage wrongStructure = legacyMessage();
            ToolCall wrongStructureCall = wrongStructure.getToolCalls().get(0);
            wrongStructure = withBoundState(wrongStructure, protocol,
                    new MessageProtocolState(
                            GeminiMessageStateSupport.VERSION,
                            Collections.<String, Object>singletonMap("thoughtSignatures",
                                    Collections.singletonList("sig_wrong_structure"))));
            assertNull(GeminiMessageStateSupport.resolveSignature(
                    wrongStructure, protocol, wrongStructureCall, 0), protocol + " wrong structure");
        }
    }

    @Test
    public void protocolSignature_acceptsOnlyNonEmptyString() {
        for (String protocol : Arrays.asList(
                GeminiMessageStateSupport.GENERATE_CONTENT_PROTOCOL_ID,
                GeminiMessageStateSupport.INTERACTIONS_PROTOCOL_ID)) {
            for (Object invalid : Arrays.<Object>asList(
                    123, true, Collections.singletonMap("signature", "sig_map"),
                    Collections.singletonList("sig_list"), "")) {
                AssistantMessage message = legacyMessage();
                ToolCall call = message.getToolCalls().get(0);
                message = withBoundState(message, protocol, signatureState(
                        GeminiMessageStateSupport.VERSION, call, invalid));

                assertNull(GeminiMessageStateSupport.resolveSignature(message, protocol, call, 0),
                        protocol + " must reject " + invalid.getClass().getSimpleName());
            }
        }
    }

    private AssistantMessage legacyMessage() {
        return message("sig_legacy");
    }

    private AssistantMessage message(String legacySignature) {
        ToolCall call = new ToolCall("0", "call-1", "getWeather", "{}",
                new LinkedHashMap<String, Object>());
        call.setThoughtSignature(legacySignature);
        return new AssistantMessage("", "", Collections.singletonList(call), null);
    }

    private AssistantMessage withBoundState(AssistantMessage source, String protocol,
                                            MessageProtocolState state) {
        return AssistantMessage.snapshot(source.getTextRaw(), source.getThinkingRaw(),
                source.getToolCalls(), source.getBlocks(), source.getSearchResults(),
                source.getCitations(), Collections.singletonMap(protocol, state),
                source.hasMetadata() ? source.getMetadata() : null);
    }

    private AssistantMessage withRestoredState(AssistantMessage source, String protocol,
                                               MessageProtocolState state) {
        org.noear.snack4.ONode node = org.noear.snack4.ONode.ofJson(
                org.noear.solon.ai.chat.message.ChatMessage.toJson(source));
        org.noear.snack4.ONode stateNode = node.getOrNew("protocolStates").getOrNew(protocol);
        stateNode.set("version", state.getVersion());
        if (state.getSemanticHash() != null) {
            stateNode.set("semanticHash", state.getSemanticHash());
        }
        org.noear.snack4.ONode data = stateNode.getOrNew("data");
        for (Map.Entry<String, Object> entry : state.getData().entrySet()) {
            data.set(entry.getKey(), plain(entry.getValue()));
        }
        return (AssistantMessage) org.noear.solon.ai.chat.message.ChatMessage.fromJson(node);
    }

    private static Object plain(Object value) {
        if (value instanceof Map) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                map.put(String.valueOf(entry.getKey()), plain(entry.getValue()));
            }
            return map;
        }
        if (value instanceof java.util.List) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (Object item : (java.util.List<?>) value) {
                list.add(plain(item));
            }
            return list;
        }
        return value;
    }

    private MessageProtocolState signatureState(int version, ToolCall call, Object signature) {
        Map<String, Object> signatures = new LinkedHashMap<>();
        signatures.put("id:" + call.getId(), signature);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("thoughtSignatures", signatures);
        return new MessageProtocolState(version, data);
    }
}
