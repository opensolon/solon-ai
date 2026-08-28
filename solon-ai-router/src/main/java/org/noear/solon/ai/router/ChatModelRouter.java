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

import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天模型路由器
 *
 * @author bai
 * @since 4.1
 */
public final class ChatModelRouter {
    /**
     * Prompt 显式路由标识属性
     */
    public static final String ATTR_ROUTE_ID = "solon.ai.router.routeId";

    private final List<ChatModelRoute> routes;
    private final Map<String, ChatModelRoute> routeIndex;
    private final RoutingStrategy strategy;

    /**
     * @param routes   候选路由
     * @param strategy 路由策略
     */
    public ChatModelRouter(Collection<ChatModelRoute> routes, RoutingStrategy strategy) {
        if (routes == null || routes.isEmpty()) {
            throw new RoutingException("The routes are required");
        }
        if (strategy == null) {
            throw new RoutingException("The routing strategy is required");
        }

        List<ChatModelRoute> routeList = new ArrayList<>(routes.size());
        Map<String, ChatModelRoute> routeMap = new LinkedHashMap<>();
        for (ChatModelRoute route : routes) {
            if (route == null) {
                throw new RoutingException("The route is required");
            }
            if (routeMap.put(route.getId(), route) != null) {
                throw new RoutingException("Duplicate route id: " + route.getId());
            }
            routeList.add(route);
        }

        this.routes = Collections.unmodifiableList(routeList);
        this.routeIndex = Collections.unmodifiableMap(routeMap);
        this.strategy = strategy;
    }

    /**
     * 获取只读候选路由
     */
    public List<ChatModelRoute> getRoutes() {
        return routes;
    }

    /**
     * 创建聊天请求描述
     *
     * @param prompt 提示语
     */
    public ChatRequestDesc prompt(Prompt prompt) {
        if (prompt == null) {
            throw new RoutingException("The prompt is required");
        }

        RoutingDecision decision = resolveDecision(prompt);
        if (decision == null) {
            throw new RoutingException("The routing decision is required");
        }

        String routeId = decision.getRouteId();
        if (routeId == null || routeId.trim().length() == 0) {
            throw new RoutingException("The routing decision routeId is required");
        }

        ChatModelRoute route = routeIndex.get(routeId);
        if (route == null) {
            throw new RoutingException("Unknown route id: " + routeId);
        }

        return route.getChatModel().prompt(prompt);
    }

    /**
     * 创建聊天请求描述
     *
     * @param messages 消息列表
     */
    public ChatRequestDesc prompt(List<ChatMessage> messages) {
        return prompt(Prompt.of(messages));
    }

    /**
     * 创建聊天请求描述
     *
     * @param messages 消息数组
     */
    public ChatRequestDesc prompt(ChatMessage... messages) {
        return prompt(Arrays.asList(messages));
    }

    /**
     * 创建聊天请求描述
     *
     * @param content 用户消息内容
     */
    public ChatRequestDesc prompt(String content) {
        return prompt(ChatMessage.ofUser(content));
    }

    private RoutingDecision resolveDecision(Prompt prompt) {
        Object explicitRouteId = prompt.attr(ATTR_ROUTE_ID);
        if (explicitRouteId != null) {
            if (!(explicitRouteId instanceof String)
                    || ((String) explicitRouteId).trim().length() == 0) {
                throw new RoutingException("The explicit route id must be a non-empty string");
            }

            return new RoutingDecision((String) explicitRouteId, "explicit route");
        }

        return strategy.select(new RoutingContext(prompt), routes);
    }
}
