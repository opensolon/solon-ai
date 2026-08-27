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
package features.ai.router;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.router.ChatModelRoute;
import org.noear.solon.ai.router.ChatModelRouter;
import org.noear.solon.ai.router.RoutingContext;
import org.noear.solon.ai.router.RoutingDecision;
import org.noear.solon.ai.router.RoutingException;
import org.noear.solon.ai.router.strategy.SmartRoutingStrategy;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SmartRoutingStrategyTest {
    @Test
    public void constructorShouldRejectNullClassifier() {
        Assertions.assertThrows(RoutingException.class,
                () -> new SmartRoutingStrategy(null));
    }

    @Test
    public void shouldBuildClassifierPromptAndParseStructuredDecision() throws Exception {
        ClassifierFixture fixture = new ClassifierFixture(message(
                "{\"routeId\":\"reasoning\",\"reasoning\":\"complex analysis\"}"));
        SmartRoutingStrategy strategy = new SmartRoutingStrategy(fixture.classifier);
        Prompt original = Prompt.of(
                ChatMessage.ofSystem("You are a coding assistant"),
                ChatMessage.ofUser("Analyze this concurrent algorithm"));

        RoutingDecision decision = strategy.select(new RoutingContext(original), routes());

        Assertions.assertEquals("reasoning", decision.getRouteId());
        Assertions.assertEquals("complex analysis", decision.getReasoning());

        String classifierContent = fixture.prompt.get().getUserContent();
        Assertions.assertTrue(classifierContent.contains("fast"));
        Assertions.assertTrue(classifierContent.contains("Fast general questions"));
        Assertions.assertTrue(classifierContent.contains("reasoning"));
        Assertions.assertTrue(classifierContent.contains("Complex multi-step analysis"));
        Assertions.assertTrue(classifierContent.contains("You are a coding assistant"));
        Assertions.assertTrue(classifierContent.contains("Analyze this concurrent algorithm"));
        Assertions.assertTrue(classifierContent.contains("only one candidate route id"));

        String outputSchema = fixture.options.get().outputSchema();
        Assertions.assertNotNull(outputSchema);
        Assertions.assertTrue(outputSchema.contains("routeId"));
        Assertions.assertTrue(outputSchema.contains("reasoning"));

        Assertions.assertEquals(2, original.size());
    }

    @Test
    public void shouldRejectMissingContextOrRoutesBeforeClassifierCall() throws Exception {
        ClassifierFixture fixture = new ClassifierFixture(message(validJson()));
        SmartRoutingStrategy strategy = new SmartRoutingStrategy(fixture.classifier);

        Assertions.assertThrows(RoutingException.class,
                () -> strategy.select(null, routes()));
        Assertions.assertThrows(RoutingException.class,
                () -> strategy.select(new RoutingContext(Prompt.of("question")), null));
        Assertions.assertThrows(RoutingException.class,
                () -> strategy.select(new RoutingContext(Prompt.of("question")),
                        Collections.<ChatModelRoute>emptyList()));

        verify(fixture.classifier, never()).prompt(any(Prompt.class));
    }

    @Test
    public void shouldRejectEmptyResponseAndMessage() throws Exception {
        ClassifierFixture nullResponse = new ClassifierFixture(message(validJson()));
        when(nullResponse.request.call()).thenReturn(null);
        Assertions.assertThrows(RoutingException.class,
                () -> select(nullResponse, routes()));

        ClassifierFixture nullMessage = new ClassifierFixture(null);
        Assertions.assertThrows(RoutingException.class,
                () -> select(nullMessage, routes()));
    }

    @Test
    public void shouldRejectMalformedOrIncompleteDecision() throws Exception {
        assertInvalidDecision("not json");
        assertInvalidDecision("{\"routeId\":\"\",\"reasoning\":\"none\"}");
        assertInvalidDecision("{\"routeId\":\"fast\",\"reasoning\":\"\"}");
        assertInvalidDecision("{\"routeId\":\"missing\",\"reasoning\":\"unknown\"}");
    }

    @Test
    public void classifierFailureShouldBeWrappedAndSkipBusinessModel() throws Exception {
        IOException cause = new IOException("classifier unavailable");
        ClassifierFixture fixture = new ClassifierFixture(message(validJson()));
        when(fixture.request.call()).thenThrow(cause);
        SmartRoutingStrategy strategy = new SmartRoutingStrategy(fixture.classifier);
        ChatModel businessModel = mock(ChatModel.class);
        ChatModelRouter router = new ChatModelRouter(
                Collections.singletonList(new ChatModelRoute(
                        "fast", "Fast general questions", 1, businessModel)), strategy);

        RoutingException error = Assertions.assertThrows(RoutingException.class,
                () -> router.prompt("question"));

        Assertions.assertSame(cause, error.getCause());
        verify(businessModel, never()).prompt(any(Prompt.class));
    }

    private static RoutingDecision select(ClassifierFixture fixture,
                                          List<ChatModelRoute> routes) {
        return new SmartRoutingStrategy(fixture.classifier).select(
                new RoutingContext(Prompt.of("question")), routes);
    }

    private static void assertInvalidDecision(String content) throws Exception {
        ClassifierFixture fixture = new ClassifierFixture(message(content));
        Assertions.assertThrows(RoutingException.class,
                () -> select(fixture, routes()));
    }

    private static AssistantMessage message(String content) {
        return content == null ? null : ChatMessage.ofAssistant(content);
    }

    private static String validJson() {
        return "{\"routeId\":\"fast\",\"reasoning\":\"simple request\"}";
    }

    private static List<ChatModelRoute> routes() {
        return Arrays.asList(
                new ChatModelRoute("fast", "Fast general questions", 3,
                        mock(ChatModel.class)),
                new ChatModelRoute("reasoning", "Complex multi-step analysis", 1,
                        mock(ChatModel.class)));
    }

    private static class ClassifierFixture {
        private final ChatModel classifier = mock(ChatModel.class);
        private final ChatRequestDesc request = mock(ChatRequestDesc.class);
        private final ChatResponse response = mock(ChatResponse.class);
        private final AtomicReference<Prompt> prompt = new AtomicReference<>();
        private final AtomicReference<ChatOptions> options = new AtomicReference<>();

        private ClassifierFixture(AssistantMessage assistantMessage) throws IOException {
            when(classifier.prompt(any(Prompt.class))).thenAnswer(invocation -> {
                prompt.set(invocation.getArgument(0));
                return request;
            });
            when(request.options(ArgumentMatchers.<Consumer<ChatOptions>>any()))
                    .thenAnswer(invocation -> {
                        ChatOptions chatOptions = ChatOptions.of();
                        Consumer<ChatOptions> customizer = invocation.getArgument(0);
                        customizer.accept(chatOptions);
                        options.set(chatOptions);
                        return request;
                    });
            when(request.call()).thenReturn(response);
            when(response.getMessage()).thenReturn(assistantMessage);
        }
    }
}
