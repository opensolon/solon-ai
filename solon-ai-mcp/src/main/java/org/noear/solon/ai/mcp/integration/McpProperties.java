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
package org.noear.solon.ai.mcp.integration;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.ai.mcp.client.McpClientProperties;
import org.noear.solon.ai.mcp.server.McpServerProperties;
import org.noear.solon.annotation.BindProps;

import java.util.Map;

/**
 * Mcp 属性（仅用于配置提示）
 *
 * @author noear
 * @since 3.1
 */
@Setter
@Getter
@BindProps(prefix = "solon.ai.mcp")
public class McpProperties {
    private Map<String, McpServerProperties> server;
    private Map<String, McpClientProperties> client;
}
