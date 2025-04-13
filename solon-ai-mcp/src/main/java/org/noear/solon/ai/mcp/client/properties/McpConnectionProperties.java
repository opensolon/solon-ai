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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mcp 连接属性
 *
 * @author noear
 * @since 3.1
 */
@Setter
@Getter
public class McpConnectionProperties {
    /**
     * 接口完整地址
     */
    private String apiUrl;
    /**
     * 接口密钥
     */
    private String apiKey;
    /**
     * 连接头信息
     */
    private final Map<String, String> headers = new LinkedHashMap<>();
    /**
     * 连接超时
     */
    private Duration timeout;

    public McpConnectionProperties() {
        // 用于序列化
    }

    /**
     * @param apiUrl 接口地址
     */
    public McpConnectionProperties(String apiUrl) {
        this.apiUrl = apiUrl;
    }
}