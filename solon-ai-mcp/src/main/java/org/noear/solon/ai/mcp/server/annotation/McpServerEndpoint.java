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
package org.noear.solon.ai.mcp.server.annotation;

import org.noear.solon.ai.mcp.McpChannel;

import java.lang.annotation.*;

/**
 * Mcp 服务端点注解
 *
 * @author noear
 * @since 3.1
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface McpServerEndpoint {
    /**
     * 名字（没有时类名即为名）
     */
    String name() default "";

    /**
     * 版本
     */
    String version() default "1.0.0";

    /**
     * 通道
     */
    String channel() default McpChannel.STREAMABLE;

    /**
     * MCP 端点
     */
    String mcpEndpoint() default "";

    /**
     * SSE 端点
     *
     * @deprecated 3.5
     */
    @Deprecated
    String sseEndpoint() default "";

    /**
     * Message 端点（默认根据 sse 端点自动构建）
     *
     * @deprecated 3.5
     */
    @Deprecated
    String messageEndpoint() default "";

    /**
     * 服务器心跳间隔（空表示不启用）
     */
    String heartbeatInterval() default "30s";

    /**
     * 启用输出架构
     */
    boolean enableOutputSchema() default false;
}