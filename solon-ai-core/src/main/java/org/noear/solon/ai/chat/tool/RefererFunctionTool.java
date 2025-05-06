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
package org.noear.solon.ai.chat.tool;

import java.util.Map;
import java.util.function.Function;

/**
 * 引用的函数工具（用于 mcp 场景）
 *
 * @author noear
 * @since 3.1
 */
public class RefererFunctionTool implements FunctionTool {
    private final String name;
    private final String description;
    private final String inputSchema;
    private final boolean returnDirect;
    private final Function<Map<String, Object>, String> handler;

    public RefererFunctionTool(String name, String description, Boolean returnDirect, String inputSchema, Function<Map<String, Object>, String> handler) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.handler = handler;
        this.returnDirect = (returnDirect == null ? false : returnDirect);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean returnDirect() {
        return returnDirect;
    }

    @Override
    public String inputSchema() {
        return inputSchema;
    }

    @Override
    public String handle(Map<String, Object> args) throws Throwable {
        return handler.apply(args);
    }

    @Override
    public String toString() {
        return "RefererFunctionTool{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", returnDirect=" + returnDirect +
                ", inputSchema=" + inputSchema +
                '}';
    }
}