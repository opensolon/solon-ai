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
import org.noear.solon.ai.router.strategy.RoundRobinRoutingStrategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.mockito.Mockito.mock;

public class RoundRobinRoutingStrategyTest {
    private final RoutingContext context = new RoutingContext(Prompt.of("question"));

    @Test
    public void shouldSelectRoutesInRegistrationOrder() {
        RoundRobinRoutingStrategy strategy = new RoundRobinRoutingStrategy();
        List<ChatModelRoute> routes = routes();
        List<String> selected = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            selected.add(strategy.select(context, routes).getRouteId());
        }

        Assertions.assertEquals(
                Arrays.asList("a", "b", "c", "a", "b", "c"), selected);
    }

    @Test
    public void shouldRejectEmptyRoutes() {
        RoundRobinRoutingStrategy strategy = new RoundRobinRoutingStrategy();

        Assertions.assertThrows(RoutingException.class,
                () -> strategy.select(context, Collections.<ChatModelRoute>emptyList()));
        Assertions.assertThrows(RoutingException.class,
                () -> strategy.select(context, null));
    }

    @Test
    public void shouldKeepCompleteCyclesUnderConcurrency() throws Exception {
        RoundRobinRoutingStrategy strategy = new RoundRobinRoutingStrategy();
        List<ChatModelRoute> routes = routes();
        ExecutorService executor = Executors.newFixedThreadPool(12);

        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 600; i++) {
                tasks.add(() -> strategy.select(context, routes).getRouteId());
            }

            Map<String, Integer> counts = new HashMap<>();
            for (Future<String> future : executor.invokeAll(tasks)) {
                String routeId = future.get();
                counts.put(routeId, counts.getOrDefault(routeId, 0) + 1);
            }

            Assertions.assertEquals(3, counts.size());
            Assertions.assertEquals(200, counts.get("a").intValue());
            Assertions.assertEquals(200, counts.get("b").intValue());
            Assertions.assertEquals(200, counts.get("c").intValue());
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<ChatModelRoute> routes() {
        return Arrays.asList(
                new ChatModelRoute("a", "model a", 1, mock(ChatModel.class)),
                new ChatModelRoute("b", "model b", 1, mock(ChatModel.class)),
                new ChatModelRoute("c", "model c", 1, mock(ChatModel.class)));
    }
}
