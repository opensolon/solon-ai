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
package org.noear.solon.ai.mcp.server;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.annotation.BindProps;

/**
 * Mcp 服务端属性
 *
 * @author noear
 * @since 3.1
 */
@Setter
@Getter
public class McpServerProperties {
    /**
     * 是否启用
     */
    private boolean enabled = false;
    /**
     * 服务名称
     */
    private String name = "Solon-Ai-Mcp-Server";
    /**
     * 服务端版本号
     */
    private String version = "1.0.0";
    /**
     * 消息端端点（路径）
     */
    private String messageEndpoint = "/mcp/message";
    /**
     * 服务器派发事件端点（路径）
     */
    private String sseEndpoint = "/mcp/sse";
}