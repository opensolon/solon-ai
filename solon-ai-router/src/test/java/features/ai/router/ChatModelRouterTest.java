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
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.router.ChatModelRoute;
import org.noear.solon.ai.router.ChatModelRouter;
import org.noear.solon.ai.router.RoutingDecision;
import org.noear.solon.ai.router.RoutingException;
import org.noear.solon.ai.router.RoutingStrategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ChatModelRouterTest {
    @Test
    public void constructorShouldRejectInvalidTopology() {
        ChatModel model = mock(ChatModel.class);
        ChatModelRoute route = route("fast", model);
        RoutingStrategy strategy = select("fast");

        Assertions.assertThrows(RoutingException.class,
                () -> new ChatModelRouter(null, strategy));
        Assertions.assertThrows(RoutingException.class,
                () -> new ChatModelRouter(Collections.<ChatModelRoute>emptyList(), strategy));
        Assertions.assertThrows(RoutingException.class,
                () -> new ChatModelRouter(Collections.singletonList(route), null));
        Assertions.assertThrows(RoutingException.class,
                () -> new ChatModelRouter(Arrays.asList(route, null), strategy));
        Assertions.assertThrows(RoutingException.class,
                () -> new ChatModelRouter(Arrays.asList(route, route("fast", model)), strategy));
    }

    @Test
    public void constructorShouldCopyRoutesAndExposeReadOnlyOrder() {
        List<ChatModelRoute> source = new ArrayList<>();
        source.add(route("fast", mock(ChatModel.class)));
        source.add(route("reasoning", mock(ChatModel.class)));

        ChatModelRouter router = new ChatModelRouter(source, select("fast"));
        source.clear();

        Assertions.assertEquals(2, router.getRoutes().size());
        Assertions.assertEquals("fast", router.getRoutes().get(0).getId());
        Assertions.assertEquals("reasoning", router.getRoutes().get(1).getId());
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> router.getRoutes().add(route("other", mock(ChatModel.class))));
    }

    @Test
    public void promptShouldReturnSelectedModelsNativeRequest() {
        ChatModel model = mock(ChatModel.class);
        ChatRequestDesc request = mock(ChatRequestDesc.class);
        Prompt prompt = Prompt.of("hello");
        when(model.prompt(prompt)).thenReturn(request);

        ChatModelRouter router = new ChatModelRouter(
                Collections.singletonList(route("fast", model)), select("fast"));

        Assertions.assertSame(request, router.prompt(prompt));
        verifyNoInteractions(request);
    }

    @Test
    public void listPromptShouldPreserveMessages() {
        ChatModel model = mock(ChatModel.class);
        ChatRequestDesc request = mock(ChatRequestDesc.class);
        AtomicReference<Prompt> captured = capturePrompt(model, request);
        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.ofSystem("system"), ChatMessage.ofUser("question"));

        ChatModelRouter router = new ChatModelRouter(
                Collections.singletonList(route("fast", model)), select("fast"));

        Assertions.assertSame(request, router.prompt(messages));
        Assertions.assertEquals(messages, captured.get().getMessages());
    }

    @Test
    public void varargPromptShouldPreserveMessages() {
        ChatModel model = mock(ChatModel.class);
        ChatRequestDesc request = mock(ChatRequestDesc.class);
        AtomicReference<Prompt> captured = capturePrompt(model, request);
        ChatMessage system = ChatMessage.ofSystem("system");
        ChatMessage user = ChatMessage.ofUser("question");

        ChatModelRouter router = new ChatModelRouter(
                Collections.singletonList(route("fast", model)), select("fast"));

        Assertions.assertSame(request, router.prompt(system, user));
        Assertions.assertEquals(Arrays.asList(system, user), captured.get().getMessages());
    }

    @Test
    public void stringPromptShouldBecomeUserMessage() {
        ChatModel model = mock(ChatModel.class);
        ChatRequestDesc request = mock(ChatRequestDesc.class);
        AtomicReference<Prompt> captured = capturePrompt(model, request);

        ChatModelRouter router = new ChatModelRouter(
                Collections.singletonList(route("fast", model)), select("fast"));

        Assertions.assertSame(request, router.prompt("question"));
        Assertions.assertEquals(1, captured.get().size());
        Assertions.assertEquals("question", captured.get().getUserContent());
    }

    @Test
    public void explicitRouteShouldBypassStrategy() {
        ChatModel fastModel = mock(ChatModel.class);
        ChatModel reasoningModel = mock(ChatModel.class);
        ChatRequestDesc reasoningRequest = mock(ChatRequestDesc.class);
        RoutingStrategy strategy = mock(RoutingStrategy.class);
        Prompt prompt = Prompt.of("question")
                .attrPut(ChatModelRouter.ATTR_ROUTE_ID, "reasoning");
        when(reasoningModel.prompt(prompt)).thenReturn(reasoningRequest);

        ChatModelRouter router = new ChatModelRouter(Arrays.asList(
                route("fast", fastModel), route("reasoning", reasoningModel)), strategy);

        Assertions.assertSame(reasoningRequest, router.prompt(prompt));
        verifyNoInteractions(strategy);
        verify(fastModel, never()).prompt(any(Prompt.class));
    }

    @Test
    public void invalidExplicitRouteShouldFailWithoutCallingStrategy() {
        ChatModel model = mock(ChatModel.class);
        RoutingStrategy strategy = mock(RoutingStrategy.class);
        ChatModelRouter router = new ChatModelRouter(
                Collections.singletonList(route("fast", model)), strategy);

        Assertions.assertThrows(RoutingException.class,
                () -> router.prompt(Prompt.of("question")
                        .attrPut(ChatModelRouter.ATTR_ROUTE_ID, "missing")));
        Assertions.assertThrows(RoutingException.class,
                () -> router.prompt(Prompt.of("question")
                        .attrPut(ChatModelRouter.ATTR_ROUTE_ID, " ")));
        Assertions.assertThrows(RoutingException.class,
                () -> router.prompt(Prompt.of("question")
                        .attrPut(ChatModelRouter.ATTR_ROUTE_ID, 1)));

        verifyNoInteractions(strategy);
        verify(model, never()).prompt(any(Prompt.class));
    }

    @Test
    public void invalidStrategyDecisionShouldNotCallCandidates() {
        ChatModel model = mock(ChatModel.class);

        assertInvalidDecision(model, context -> null);
        assertInvalidDecision(model, context -> new RoutingDecision(" ", null));
        assertInvalidDecision(model, context -> new RoutingDecision("missing", null));
    }

    @Test
    public void strategyMutationShouldNotChangeForwardedPrompt() {
        ChatModel model = mock(ChatModel.class);
        ChatRequestDesc request = mock(ChatRequestDesc.class);
        AtomicReference<Prompt> captured = capturePrompt(model, request);
        Prompt original = Prompt.of("question").attrPut("tenant", "a");

        RoutingStrategy mutatingStrategy = (context, routes) -> {
            context.getPrompt().clear();
            context.getPrompt().attrPut("tenant", "b");
            return new RoutingDecision("fast", "selected");
        };
        ChatModelRouter router = new ChatModelRouter(
                Collections.singletonList(route("fast", model)), mutatingStrategy);

        Assertions.assertSame(request, router.prompt(original));
        Assertions.assertSame(original, captured.get());
        Assertions.assertEquals(1, captured.get().size());
        Assertions.assertEquals("a", captured.get().attr("tenant"));
    }

    @Test
    public void nullPromptShouldFailBeforeStrategy() {
        RoutingStrategy strategy = mock(RoutingStrategy.class);
        ChatModelRouter router = new ChatModelRouter(
                Collections.singletonList(route("fast", mock(ChatModel.class))), strategy);

        Assertions.assertThrows(RoutingException.class, () -> router.prompt((Prompt) null));
        verifyNoInteractions(strategy);
    }

    private static ChatModelRoute route(String id, ChatModel model) {
        return new ChatModelRoute(id, id + " model", 1, model);
    }

    private static RoutingStrategy select(String routeId) {
        return (context, routes) -> new RoutingDecision(routeId, "selected");
    }

    private static AtomicReference<Prompt> capturePrompt(ChatModel model, ChatRequestDesc request) {
        AtomicReference<Prompt> captured = new AtomicReference<>();
        when(model.prompt(any(Prompt.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return request;
        });
        return captured;
    }

    private static void assertInvalidDecision(ChatModel model, DecisionFactory factory) {
        RoutingStrategy strategy = (context, routes) -> factory.create(context);
        ChatModelRouter router = new ChatModelRouter(
                Collections.singletonList(route("fast", model)), strategy);

        Assertions.assertThrows(RoutingException.class, () -> router.prompt("question"));
        verify(model, never()).prompt(any(Prompt.class));
    }

    private interface DecisionFactory {
        RoutingDecision create(org.noear.solon.ai.router.RoutingContext context);
    }
}
