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
     * SSE 端点
     */
    String sseEndpoint() default "/sse";

    /**
     * 是否启用服务器SSE心跳
     */
    boolean enabledSseHeartbeat() default false;

    /**
     * 服务器SSE心跳间隔（启用后才有效）
     */
    String sseHeartbeatInterval() default "30s";
}