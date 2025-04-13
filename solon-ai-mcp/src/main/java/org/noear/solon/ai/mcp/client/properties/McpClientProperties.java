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
package org.noear.solon.ai.mcp.client.properties;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.annotation.BindProps;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mcp 客户端属性
 *
 * @author noear
 * @since 3.1
 */
@Setter
@Getter
@BindProps(prefix = "solon.ai.mcp.client")
public class McpClientProperties {
    /**
     * 是否启用
     */
    private boolean enabled = false;
    /**
     * 客户端名称
     */
    private String name = "Solon-Ai-Mcp-Client";
    /**
     * 客户端版本号
     */
    private String version = "1.0.0";

    /**
     * 公共超时
     */
    private Duration timeout;
    /**
     * 公共头
     */
    private final Map<String, String> headers = new LinkedHashMap<>();

    /**
     * 连接配置
     */
    private Map<String, McpConnectionProperties> connections = new LinkedHashMap<>();
}