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
import org.noear.solon.ai.router.RoutingDecision;
import org.noear.solon.ai.router.RoutingException;

import static org.mockito.Mockito.mock;

public class RoutingContractTest {
    private final ChatModel chatModel = mock(ChatModel.class);

    @Test
    public void routeShouldRejectBlankId() {
        Assertions.assertThrows(RoutingException.class,
                () -> new ChatModelRoute(" ", "general", 1, chatModel));
    }

    @Test
    public void routeShouldRejectBlankDescription() {
        Assertions.assertThrows(RoutingException.class,
                () -> new ChatModelRoute("general", " ", 1, chatModel));
    }

    @Test
    public void routeShouldRejectNonPositiveWeight() {
        Assertions.assertThrows(RoutingException.class,
                () -> new ChatModelRoute("general", "general model", 0, chatModel));
        Assertions.assertThrows(RoutingException.class,
                () -> new ChatModelRoute("general", "general model", -1, chatModel));
    }

    @Test
    public void routeShouldRejectNullModel() {
        Assertions.assertThrows(RoutingException.class,
                () -> new ChatModelRoute("general", "general model", 1, null));
    }

    @Test
    public void routeShouldExposeValidatedValues() {
        ChatModelRoute route = new ChatModelRoute("general", "general model", 3, chatModel);

        Assertions.assertEquals("general", route.getId());
        Assertions.assertEquals("general model", route.getDescription());
        Assertions.assertEquals(3, route.getWeight());
        Assertions.assertSame(chatModel, route.getChatModel());
    }

    @Test
    public void contextShouldRejectNullPrompt() {
        Assertions.assertThrows(RoutingException.class, () -> new RoutingContext(null));
    }

    @Test
    public void contextShouldProtectOriginalPromptFromMutation() {
        Prompt original = Prompt.of("original").attrPut("tenant", "a");
        RoutingContext context = new RoutingContext(original);

        Prompt exposed = context.getPrompt();
        exposed.addMessage("changed").attrPut("tenant", "b");

        Assertions.assertEquals(1, original.size());
        Assertions.assertEquals("a", original.attr("tenant"));
        Assertions.assertEquals(1, context.getPrompt().size());
        Assertions.assertEquals("a", context.getPrompt().attr("tenant"));
    }

    @Test
    public void decisionShouldSupportBeanAccess() {
        RoutingDecision decision = new RoutingDecision();
        decision.setRouteId("reasoning");
        decision.setReasoning("complex request");

        Assertions.assertEquals("reasoning", decision.getRouteId());
        Assertions.assertEquals("complex request", decision.getReasoning());

        RoutingDecision constructed = new RoutingDecision("fast", "simple request");
        Assertions.assertEquals("fast", constructed.getRouteId());
        Assertions.assertEquals("simple request", constructed.getReasoning());
    }
}
