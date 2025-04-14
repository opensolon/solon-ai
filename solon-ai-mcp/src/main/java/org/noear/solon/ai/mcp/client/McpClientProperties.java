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
package org.noear.solon.ai.mcp.client;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.net.http.HttpTimeout;

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
public class McpClientProperties {
    /**
     * 客户端名称
     */
    private String name = "Solon-Ai-Mcp-Client";
    /**
     * 客户端版本号
     */
    private String version = "1.0.0";

    /**
     * 接口完整地址
     */
    private String apiUrl;
    /**
     * 接口密钥
     */
    private String apiKey;
    /**
     * 请求头信息
     */
    private final Map<String, String> headers = new LinkedHashMap<>();
    /**
     * http 超时
     */
    private HttpTimeout httpTimeout = HttpTimeout.of(10);
    /**
     * 请求超时
     */
    private Duration requestTimeout = Duration.ofSeconds(20); // Default timeout
    /**
     * 初始化超时
     */
    private Duration initializationTimeout = Duration.ofSeconds(20);


    public McpClientProperties() {
        //用于序列化
    }

    /**
     * @param apiUrl 接口地址
     */
    public McpClientProperties(String apiUrl) {
        this.apiUrl = apiUrl;
    }
}