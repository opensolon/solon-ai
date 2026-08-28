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
import org.noear.solon.ai.router.strategy.WeightedRoundRobinRoutingStrategy;

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

public class WeightedRoundRobinRoutingStrategyTest {
    private final RoutingContext context = new RoutingContext(Prompt.of("question"));

    @Test
    public void shouldUseSmoothWeightedSequence() {
        WeightedRoundRobinRoutingStrategy strategy = new WeightedRoundRobinRoutingStrategy();
        List<String> selected = select(strategy, weightedRoutes(), 7);

        Assertions.assertEquals(
                Arrays.asList("a", "a", "b", "a", "c", "a", "a"), selected);
    }

    @Test
    public void shouldMatchConfiguredRatioAcrossCompleteCycles() {
        WeightedRoundRobinRoutingStrategy strategy = new WeightedRoundRobinRoutingStrategy();
        Map<String, Integer> counts = count(select(strategy, weightedRoutes(), 700));

        Assertions.assertEquals(500, counts.get("a").intValue());
        Assertions.assertEquals(100, counts.get("b").intValue());
        Assertions.assertEquals(100, counts.get("c").intValue());
    }

    @Test
    public void shouldMatchConfiguredRatioUnderConcurrency() throws Exception {
        WeightedRoundRobinRoutingStrategy strategy = new WeightedRoundRobinRoutingStrategy();
        List<ChatModelRoute> routes = weightedRoutes();
        ExecutorService executor = Executors.newFixedThreadPool(12);

        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 700; i++) {
                tasks.add(() -> strategy.select(context, routes).getRouteId());
            }

            List<String> selected = new ArrayList<>();
            for (Future<String> future : executor.invokeAll(tasks)) {
                selected.add(future.get());
            }

            Map<String, Integer> counts = count(selected);
            Assertions.assertEquals(3, counts.size());
            Assertions.assertEquals(500, counts.get("a").intValue());
            Assertions.assertEquals(100, counts.get("b").intValue());
            Assertions.assertEquals(100, counts.get("c").intValue());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldRejectEmptyRoutes() {
        WeightedRoundRobinRoutingStrategy strategy = new WeightedRoundRobinRoutingStrategy();

        Assertions.assertThrows(RoutingException.class,
                () -> strategy.select(context, Collections.<ChatModelRoute>emptyList()));
        Assertions.assertThrows(RoutingException.class,
                () -> strategy.select(context, null));
    }

    @Test
    public void shouldRejectChangedRouteTopology() {
        WeightedRoundRobinRoutingStrategy orderStrategy = initializedStrategy();
        Assertions.assertThrows(RoutingException.class,
                () -> orderStrategy.select(context, Arrays.asList(
                        route("b", 1), route("a", 5), route("c", 1))));

        WeightedRoundRobinRoutingStrategy weightStrategy = initializedStrategy();
        Assertions.assertThrows(RoutingException.class,
                () -> weightStrategy.select(context, Arrays.asList(
                        route("a", 4), route("b", 2), route("c", 1))));

        WeightedRoundRobinRoutingStrategy idStrategy = initializedStrategy();
        Assertions.assertThrows(RoutingException.class,
                () -> idStrategy.select(context, Arrays.asList(
                        route("a", 5), route("b", 1), route("d", 1))));
    }

    private WeightedRoundRobinRoutingStrategy initializedStrategy() {
        WeightedRoundRobinRoutingStrategy strategy = new WeightedRoundRobinRoutingStrategy();
        strategy.select(context, weightedRoutes());
        return strategy;
    }

    private List<String> select(WeightedRoundRobinRoutingStrategy strategy,
                                List<ChatModelRoute> routes, int times) {
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            selected.add(strategy.select(context, routes).getRouteId());
        }
        return selected;
    }

    private static Map<String, Integer> count(List<String> selected) {
        Map<String, Integer> counts = new HashMap<>();
        for (String routeId : selected) {
            counts.put(routeId, counts.getOrDefault(routeId, 0) + 1);
        }
        return counts;
    }

    private static List<ChatModelRoute> weightedRoutes() {
        return Arrays.asList(route("a", 5), route("b", 1), route("c", 1));
    }

    private static ChatModelRoute route(String id, int weight) {
        return new ChatModelRoute(id, "model " + id, weight, mock(ChatModel.class));
    }
}
