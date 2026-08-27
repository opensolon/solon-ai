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
package org.noear.solon.ai.router;

import java.util.function.Predicate;

/**
 * 路由规则
 *
 * @author bai
 * @since 4.1
 */
public final class RoutingRule {
    private final String routeId;
    private final Predicate<RoutingContext> predicate;

    /**
     * @param routeId   路由标识
     * @param predicate 匹配条件
     */
    public RoutingRule(String routeId, Predicate<RoutingContext> predicate) {
        if (routeId == null || routeId.trim().length() == 0) {
            throw new RoutingException("The rule routeId is required");
        }
        if (predicate == null) {
            throw new RoutingException("The rule predicate is required");
        }

        this.routeId = routeId;
        this.predicate = predicate;
    }

    /**
     * 获取路由标识
     */
    public String getRouteId() {
        return routeId;
    }

    /**
     * 获取匹配条件
     */
    public Predicate<RoutingContext> getPredicate() {
        return predicate;
    }
}
