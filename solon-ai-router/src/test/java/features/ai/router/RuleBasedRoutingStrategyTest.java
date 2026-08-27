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
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.router.ChatModelRoute;
import org.noear.solon.ai.router.RoutingContext;
import org.noear.solon.ai.router.RoutingException;
import org.noear.solon.ai.router.RoutingRule;
import org.noear.solon.ai.router.strategy.RuleBasedRoutingStrategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.mock;

public class RuleBasedRoutingStrategyTest {
    @Test
    public void ruleShouldRejectInvalidDefinition() {
        Assertions.assertThrows(RoutingException.class,
                () -> new RoutingRule(" ", context -> true));
        Assertions.assertThrows(RoutingException.class,
                () -> new RoutingRule("fast", null));
    }

    @Test
    public void strategyShouldRejectInvalidRules() {
        Assertions.assertThrows(RoutingException.class,
                () -> new RuleBasedRoutingStrategy(null));
        Assertions.assertThrows(RoutingException.class,
                () -> new RuleBasedRoutingStrategy(Collections.<RoutingRule>emptyList()));
        Assertions.assertThrows(RoutingException.class,
                () -> new RuleBasedRoutingStrategy(Arrays.asList(
                        new RoutingRule("fast", context -> true), null)));
    }

    @Test
    public void strategyShouldCopyRulesAndExposeReadOnlyOrder() {
        List<RoutingRule> source = new ArrayList<>();
        source.add(new RoutingRule("fast", context -> true));
        source.add(new RoutingRule("reasoning", context -> true));

        RuleBasedRoutingStrategy strategy = new RuleBasedRoutingStrategy(source);
        source.clear();

        Assertions.assertEquals(2, strategy.getRules().size());
        Assertions.assertEquals("fast", strategy.getRules().get(0).getRouteId());
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> strategy.getRules().clear());
    }

    @Test
    public void firstMatchingRuleShouldWin() {
        RuleBasedRoutingStrategy strategy = new RuleBasedRoutingStrategy(Arrays.asList(
                new RoutingRule("fast", context -> true),
                new RoutingRule("reasoning", context -> true)));

        Assertions.assertEquals("fast", strategy.select(
                context(Prompt.of("question")), routes()).getRouteId());
    }

    @Test
    public void ruleShouldMatchLatestUserContent() {
        RuleBasedRoutingStrategy strategy = new RuleBasedRoutingStrategy(Arrays.asList(
                new RoutingRule("code", context -> context.getPrompt()
                        .getUserContent().contains("代码")),
                new RoutingRule("fast", context -> true)));
        Prompt prompt = Prompt.of("普通问题").addMessage("请检查这段代码");

        Assertions.assertEquals("code",
                strategy.select(context(prompt), routes()).getRouteId());
    }

    @Test
    public void ruleShouldMatchPromptAttribute() {
        RuleBasedRoutingStrategy strategy = new RuleBasedRoutingStrategy(Arrays.asList(
                new RoutingRule("reasoning", context -> "premium".equals(
                        context.getPrompt().attr("tier"))),
                new RoutingRule("fast", context -> true)));
        Prompt prompt = Prompt.of("question").attrPut("tier", "premium");

        Assertions.assertEquals("reasoning",
                strategy.select(context(prompt), routes()).getRouteId());
    }

    @Test
    public void noMatchingRuleShouldFail() {
        RuleBasedRoutingStrategy strategy = new RuleBasedRoutingStrategy(
                Collections.singletonList(new RoutingRule("fast", context -> false)));

        Assertions.assertThrows(RoutingException.class,
                () -> strategy.select(context(Prompt.of("question")), routes()));
    }

    @Test
    public void predicateFailureShouldBeWrappedWithCause() {
        IllegalStateException cause = new IllegalStateException("broken predicate");
        RuleBasedRoutingStrategy strategy = new RuleBasedRoutingStrategy(
                Collections.singletonList(new RoutingRule("fast", context -> {
                    throw cause;
                })));

        RoutingException error = Assertions.assertThrows(RoutingException.class,
                () -> strategy.select(context(Prompt.of("question")), routes()));
        Assertions.assertSame(cause, error.getCause());
    }

    private static RoutingContext context(Prompt prompt) {
        return new RoutingContext(prompt);
    }

    private static List<ChatModelRoute> routes() {
        return Arrays.asList(
                route("fast"), route("reasoning"), route("code"));
    }

    private static ChatModelRoute route(String id) {
        return new ChatModelRoute(id, "model " + id, 1, mock(ChatModel.class));
    }
}
