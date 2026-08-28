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

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轮询路由策略
 *
 * @author bai
 * @since 4.1
 */
public final class RoundRobinRoutingStrategy implements RoutingStrategy {
    private final AtomicLong cursor = new AtomicLong();

    /**
     * 按候选注册顺序选择路由
     */
    @Override
    public RoutingDecision select(RoutingContext context, List<ChatModelRoute> routes) {
        if (routes == null || routes.isEmpty()) {
            throw new RoutingException("The routes are required");
        }

        int index = (int) Math.floorMod(cursor.getAndIncrement(), routes.size());
        return new RoutingDecision(routes.get(index).getId(), "round-robin");
    }
}
