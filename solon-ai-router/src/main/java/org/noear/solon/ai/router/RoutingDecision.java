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

/**
 * 路由决策
 *
 * @author bai
 * @since 4.1
 */
public class RoutingDecision {
    private String routeId;
    private String reasoning;

    /**
     * 用于反序列化
     */
    public RoutingDecision() {
    }

    /**
     * @param routeId   路由标识
     * @param reasoning 决策说明
     */
    public RoutingDecision(String routeId, String reasoning) {
        this.routeId = routeId;
        this.reasoning = reasoning;
    }

    /**
     * 获取路由标识
     */
    public String getRouteId() {
        return routeId;
    }

    /**
     * 设置路由标识
     */
    public void setRouteId(String routeId) {
        this.routeId = routeId;
    }

    /**
     * 获取决策说明
     */
    public String getReasoning() {
        return reasoning;
    }

    /**
     * 设置决策说明
     */
    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }
}
