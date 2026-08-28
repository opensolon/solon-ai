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
package org.noear.solon.ai.router.strategy;

import org.noear.solon.ai.router.ChatModelRoute;
import org.noear.solon.ai.router.RoutingContext;
import org.noear.solon.ai.router.RoutingDecision;
import org.noear.solon.ai.router.RoutingException;
import org.noear.solon.ai.router.RoutingStrategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平滑加权轮询路由策略
 *
 * @author bai
 * @since 4.1
 */
public final class WeightedRoundRobinRoutingStrategy implements RoutingStrategy {
    private List<String> routeIds;
    private List<Integer> routeWeights;
    private Map<String, Long> currentWeights;

    /**
     * 按平滑权重选择路由
     */
    @Override
    public synchronized RoutingDecision select(RoutingContext context, List<ChatModelRoute> routes) {
        if (routes == null || routes.isEmpty()) {
            throw new RoutingException("The routes are required");
        }

        if (routeIds == null) {
            initialize(routes);
        } else {
            verifyTopology(routes);
        }

        ChatModelRoute selected = null;
        long selectedWeight = Long.MIN_VALUE;
        long totalWeight = 0L;

        for (ChatModelRoute route : routes) {
            long currentWeight = currentWeights.get(route.getId()) + route.getWeight();
            currentWeights.put(route.getId(), currentWeight);
            totalWeight += route.getWeight();

            if (selected == null || currentWeight > selectedWeight) {
                selected = route;
                selectedWeight = currentWeight;
            }
        }

        currentWeights.put(selected.getId(), selectedWeight - totalWeight);
        return new RoutingDecision(selected.getId(), "smooth weighted round-robin");
    }

    private void initialize(List<ChatModelRoute> routes) {
        List<String> ids = new ArrayList<>(routes.size());
        List<Integer> weights = new ArrayList<>(routes.size());
        Map<String, Long> current = new LinkedHashMap<>();

        for (ChatModelRoute route : routes) {
            ids.add(route.getId());
            weights.add(route.getWeight());
            current.put(route.getId(), 0L);
        }

        routeIds = Collections.unmodifiableList(ids);
        routeWeights = Collections.unmodifiableList(weights);
        currentWeights = current;
    }

    private void verifyTopology(List<ChatModelRoute> routes) {
        if (routes.size() != routeIds.size()) {
            throw new RoutingException("The weighted routes cannot change after first use");
        }

        for (int i = 0; i < routes.size(); i++) {
            ChatModelRoute route = routes.get(i);
            if (!routeIds.get(i).equals(route.getId())
                    || routeWeights.get(i).intValue() != route.getWeight()) {
                throw new RoutingException("The weighted routes cannot change after first use");
            }
        }
    }
}
