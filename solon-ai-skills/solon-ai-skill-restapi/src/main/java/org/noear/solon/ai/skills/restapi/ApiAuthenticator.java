/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.skills.restapi;

import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpUtils;

/**
 * API 认证策略接口
 * <p>用于在 RestApiSkill 执行调用前，动态为 HttpUtils 注入认证信息（如 Header, Cookie 等）</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
@FunctionalInterface
public interface ApiAuthenticator {
    /**
     * 执行认证应用
     *
     * @param http Http 工具类
     * @param tool 当前调用的工具信息
     */
    void apply(HttpUtils http, ApiTool tool);

    /**
     * 快捷创建 Bearer Token 认证器
     */
    static ApiAuthenticator bearer(String token) {
        return (http, tool) -> http.header("Authorization", "Bearer " + token);
    }

    /**
     * 快捷创建 API Key 认证器（Header 模式）
     */
    static ApiAuthenticator apiKey(String name, String value) {
        return (http, tool) -> http.header(name, value);
    }
}