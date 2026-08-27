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

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.router.ChatModelRoute;
import org.noear.solon.ai.router.RoutingContext;
import org.noear.solon.ai.router.RoutingDecision;
import org.noear.solon.ai.router.RoutingException;
import org.noear.solon.ai.router.RoutingStrategy;

import java.util.List;

/**
 * 基于聊天模型分类的智能路由策略
 *
 * @author bai
 * @since 4.1
 */
public final class SmartRoutingStrategy implements RoutingStrategy {
    private final ChatModel classifierModel;

    /**
     * @param classifierModel 分类聊天模型
     */
    public SmartRoutingStrategy(ChatModel classifierModel) {
        if (classifierModel == null) {
            throw new RoutingException("The classifier model is required");
        }

        this.classifierModel = classifierModel;
    }

    /**
     * 使用分类模型选择路由
     */
    @Override
    public RoutingDecision select(RoutingContext context, List<ChatModelRoute> routes) {
        if (context == null) {
            throw new RoutingException("The routing context is required");
        }
        if (routes == null || routes.isEmpty()) {
            throw new RoutingException("The routes are required");
        }

        try {
            ChatResponse response = classifierModel
                    .prompt(buildClassifierPrompt(context, routes))
                    .options(options -> options.outputSchema(RoutingDecision.class))
                    .call();
            if (response == null) {
                throw new RoutingException("The classifier response is required");
            }

            AssistantMessage message = response.getMessage();
            if (message == null) {
                throw new RoutingException("The classifier response message is required");
            }

            RoutingDecision decision = message.toBean(RoutingDecision.class);
            validateDecision(decision, routes);
            return decision;
        } catch (RoutingException e) {
            throw e;
        } catch (Exception e) {
            throw new RoutingException("Smart routing classification failed", e);
        }
    }

    private Prompt buildClassifierPrompt(RoutingContext context, List<ChatModelRoute> routes) {
        StringBuilder buf = new StringBuilder();
        buf.append("You are a routing classifier. Select only one candidate route id ")
                .append("and explain the reason.\n\n")
                .append("Candidate routes:\n");

        for (ChatModelRoute route : routes) {
            buf.append("- ")
                    .append(route.getId())
                    .append(": ")
                    .append(route.getDescription())
                    .append('\n');
        }

        buf.append("\nReturn a structured decision whose routeId is exactly one candidate id ")
                .append("and whose reasoning is non-empty.\n\n")
                .append("Original messages:\n");

        Prompt prompt = context.getPrompt();
        for (ChatMessage message : prompt.getMessages()) {
            buf.append(message.getRole()).append(": ");
            if (message.getContent() != null) {
                buf.append(message.getContent());
            }
            buf.append('\n');
        }

        return Prompt.of(buf.toString());
    }

    private void validateDecision(RoutingDecision decision, List<ChatModelRoute> routes) {
        if (decision == null) {
            throw new RoutingException("The classifier routing decision is required");
        }
        if (decision.getRouteId() == null
                || decision.getRouteId().trim().length() == 0) {
            throw new RoutingException("The classifier routeId is required");
        }
        if (decision.getReasoning() == null
                || decision.getReasoning().trim().length() == 0) {
            throw new RoutingException("The classifier reasoning is required");
        }

        for (ChatModelRoute route : routes) {
            if (route.getId().equals(decision.getRouteId())) {
                return;
            }
        }

        throw new RoutingException("Unknown classifier route id: " + decision.getRouteId());
    }
}
