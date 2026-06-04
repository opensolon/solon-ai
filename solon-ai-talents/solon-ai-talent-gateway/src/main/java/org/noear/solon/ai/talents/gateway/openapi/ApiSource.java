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
package org.noear.solon.ai.talents.gateway.openapi;

import org.noear.solon.core.util.Assert;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * API 源
 *
 * @author noear 2026/3/9 created
 * @since 3.9.6
 */
public class ApiSource implements Serializable {
    private String docUrl;
    private String apiBaseUrl;
    private Map<String, String> headers = new HashMap<>();
    // 允许工具（空表示全部）
    private List<String> allowedTools = new ArrayList<>();
    // 禁用工具（空表示不禁）
    private List<String> disallowedTools = new ArrayList<>();
    private Duration timeout;
    private ApiAuthenticator authenticator;

    private boolean enabled = true;

    public ApiSource() {
        //反序列化用
    }

    public ApiSource then(Consumer<ApiSource> build){
        build.accept(this);
        return this;
    }


    public String getDocUrl() {
        return docUrl;
    }

    public void setDocUrl(String docUrl) {
        this.docUrl = docUrl;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public void addHeaderVar(String key, String value) {
        Assert.notNull(key, "The key can not be null");
        Assert.notNull(value, "The value can not be null");

        headers.put(key, value);
    }

    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools;
    }

    public void addAllowedTool(String toolName){
        Assert.notNull(toolName, "The toolName can not be null");

        allowedTools.add(toolName);
    }

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public void setDisallowedTools(List<String> disallowedTools) {
        this.disallowedTools = disallowedTools;
    }

    public void addDisallowedTool(String toolName){
        Assert.notNull(toolName, "The toolName can not be null");

        disallowedTools.add(toolName);
    }

    public List<String> getDisallowedTools() {
        return disallowedTools;
    }


    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public ApiAuthenticator getAuthenticator() {
        return authenticator;
    }

    public void setAuthenticator(ApiAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
