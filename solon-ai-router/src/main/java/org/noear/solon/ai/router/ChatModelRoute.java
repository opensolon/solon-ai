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

import org.noear.solon.ai.chat.ChatModel;

/**
 * 聊天模型路由候选
 *
 * @author bai
 * @since 4.1
 */
public final class ChatModelRoute {
    private final String id;
    private final String description;
    private final int weight;
    private final ChatModel chatModel;

    /**
     * @param id          路由标识
     * @param description 路由描述
     * @param weight      路由权重
     * @param chatModel   聊天模型
     */
    public ChatModelRoute(String id, String description, int weight, ChatModel chatModel) {
        if (id == null || id.trim().isEmpty()) {
            throw new RoutingException("The route id is required");
        }
        if (description == null || description.trim().isEmpty()) {
            throw new RoutingException("The route description is required");
        }
        if (weight <= 0) {
            throw new RoutingException("The route weight must be greater than zero");
        }
        if (chatModel == null) {
            throw new RoutingException("The route chatModel is required");
        }

        this.id = id;
        this.description = description;
        this.weight = weight;
        this.chatModel = chatModel;
    }

    /**
     * 获取路由标识
     */
    public String getId() {
        return id;
    }

    /**
     * 获取路由描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取路由权重
     */
    public int getWeight() {
        return weight;
    }

    /**
     * 获取聊天模型
     */
    public ChatModel getChatModel() {
        return chatModel;
    }
}
