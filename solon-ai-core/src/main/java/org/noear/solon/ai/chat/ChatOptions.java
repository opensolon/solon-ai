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
package org.noear.solon.ai.chat;

import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.tool.*;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpUtils;

import java.lang.reflect.Type;
import java.util.function.Consumer;

/**
 * 聊天选项
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public class ChatOptions extends ModelOptionsAmend<ChatOptions, ChatInterceptor> {
    public static ChatOptions of() {
        return new ChatOptions();
    }


    /// ///////////////////////////////////

    private String agentName;
    private String role;
    private String instruction;
    private String systemPrompt;
    private String outputSchema;
    private Consumer<HttpUtils> httpCustomize;

    /**
     * 代理名字（用于打印或管理）
     */
    public String agentName() {
        return agentName;
    }

    /**
     * 代理名字（用于打印或管理）
     */
    public ChatOptions agentName(String agentName) {
        this.agentName = agentName;
        return this;
    }

    /**
     * 角色
     */
    public String role() {
        return role;
    }

    /**
     * 角色
     */
    public ChatOptions role(String role) {
        this.role = role;
        return this;
    }

    /**
     * 指令
     */
    public String instruction() {
        return instruction;
    }

    /**
     * 指令
     */
    public ChatOptions instruction(String instruction) {
        this.instruction = instruction;
        return this;
    }

    /**
     * 指令
     */
    public String systemPrompt() {
        return systemPrompt;
    }

    /**
     * 指令
     */
    public ChatOptions systemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        return this;
    }

    public String outputSchema() {
        return outputSchema;
    }

    public ChatOptions outputSchema(String val) {
        this.outputSchema = val;
        return this;
    }

    public ChatOptions outputSchema(Type type) {
        this.outputSchema = ToolSchemaUtil.buildOutputSchema(type);
        return this;
    }

    public Consumer<HttpUtils> httpCustomize() {
        return httpCustomize;
    }

    public ChatOptions httpCustomize(Consumer<HttpUtils> httpCustomize) {
        if (this.httpCustomize == null) {
            this.httpCustomize = httpCustomize;
        } else {
            this.httpCustomize.andThen(httpCustomize);
        }

        return this;
    }
}