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

/**
 * 函数工具
 *
 * @author noear
 * @since 3.1
 */
public interface FunctionTool extends ChatTool {
    /**
     * 工具类型
     */
    default String type() {
        return "function";
    }

    /**
     * 名字
     */
    String name();

    /**
     * 描述
     */
    String description();

    /**
     * 是否直接返回给调用者
     */
    boolean returnDirect();

    /**
     * 输入架构
     *
     * <pre>{@code
     * JsonSchema {
     *     String type;
     *     Map<String, Object> properties;
     *     List<String> required;
     *     Boolean additionalProperties;
     * }
     * }</pre>
     */
    String inputSchema();

    /**
     * 输入架构
     *
     * <pre>{@code
     * JsonSchema {
     *     String type;
     *     Map<String, Object> properties;
     *     List<String> required;
     *     Boolean additionalProperties;
     * }
     * }</pre>
     */
    default String outputSchema() {
        return null;
    }

    /**
     * 处理
     */
    String handle(Map<String, Object> args) throws Throwable;
}