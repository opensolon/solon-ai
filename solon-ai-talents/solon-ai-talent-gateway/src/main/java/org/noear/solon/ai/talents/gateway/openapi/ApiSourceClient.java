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

import org.noear.solon.Utils;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.http.HttpTimeout;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * API 源客户端（单个 ApiSource 的运行时代理）
 *
 * <p>持有独立的 allowedTools / disallowedTools 副本，初始化时从 ApiSource 复制，
 * 后续操作均在副本上进行，不会修改 ApiSource 原始配置。
 *
 * @author noear
 * @since 3.10
 */
public class ApiSourceClient {
    private static final Logger LOG = LoggerFactory.getLogger(ApiSourceClient.class);

    private final ApiSource source;
    private final ApiResolver resolver;
    private final ApiAuthenticator defaultAuthenticator;
    private final Duration defaultTimeout;

    // 该源解析出的全量工具（不过滤，用于前端查阅全量列表；首次访问或刷新时加载）
    private volatile Map<String, ApiTool> rawTools;
    private final ReentrantLock lock = new ReentrantLock();

    // 运行时权限副本（从 source 初始化，之后独立维护）
    private Set<String> allowedTools = new HashSet<>();
    private Set<String> disallowedTools = new HashSet<>();

    public ApiSourceClient(ApiSource source, ApiResolver resolver, ApiAuthenticator defaultAuthenticator, Duration defaultTimeout) {
        this.source = source;
        this.resolver = resolver;
        this.defaultAuthenticator = defaultAuthenticator;
        this.defaultTimeout = defaultTimeout;

        // 从 source 复制初始权限配置
        if (source.getAllowedTools() != null) {
            this.allowedTools = new HashSet<>(source.getAllowedTools());
        }
        if (source.getDisallowedTools() != null) {
            this.disallowedTools = new HashSet<>(source.getDisallowedTools());
        }
    }

    // ===== 配置访问 =====

    public ApiSource getSource() {
        return source;
    }

    public String getDocUrl() {
        return source.getDocUrl();
    }

    // ===== 权限控制（操作自身副本） =====

    public Set<String> getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(List<String> allowedTools) {
        if(allowedTools != null) {
            this.allowedTools = new HashSet<>(allowedTools);
        }
    }

    public Set<String> getDisallowedTools() {
        return disallowedTools;
    }

    public void setDisallowedTools(List<String> disallowedTools) {
        if(disallowedTools != null) {
            this.disallowedTools = new HashSet<>(disallowedTools);
        }
    }

    // ===== 工具查询 =====

    /**
     * 获取经过 allowed/disallowed 过滤后的工具列表
     */
    public Collection<ApiTool> getToolsActivated() {
        return getOrLoadApi().values().stream()
                .filter(this::isToolAllowed)
                .collect(Collectors.toList());
    }

    /**
     * 获取全量工具（不过滤，供前端展示完整列表用）
     */
    public Collection<ApiTool> getTools() {
        return Collections.unmodifiableCollection(getOrLoadApi().values());
    }


    // ===== 加载能力 =====

    /**
     * 获取已缓存的工具，如果尚未加载则首次触发加载
     */
    private Map<String, ApiTool> getOrLoadApi() {
        if (rawTools == null) {
            lock.lock();
            try {
                if (rawTools == null) {
                    try {
                        rawTools = doLoadApi();
                    } catch (IOException e) {
                        LOG.error("ApiSourceClient: Failed to load API from {}", source.getDocUrl(), e);
                        rawTools = Collections.emptyMap();
                    }
                }
            } finally {
                lock.unlock();
            }
        }
        return rawTools;
    }

    /**
     * 强制重新从 docUrl 加载 API 定义文档（刷新场景使用）
     *
     * <p>对标 McpClientProvider 的刷新流程：
     * - 重新获取远程/本地 JSON 定义
     * - 通过 resolver 解析为 ApiTool 列表
     * - 补充 baseUrl 和 source 引用
     * - 替换 rawTools 缓存
     */
    public void reloadApi() throws IOException {
        lock.lock();
        try {
            rawTools = doLoadApi();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从 docUrl 加载 API 定义文档，解析并注册工具
     *
     * <p>对标 McpClientProvider 的初始化流程：
     * - 获取远程/本地 JSON 定义
     * - 通过 resolver 解析为 ApiTool 列表
     * - 补充 baseUrl 和 source 引用
     * - 全量注册到 rawTools
     */
    private Map<String, ApiTool> doLoadApi() throws IOException {
        Map<String, ApiTool> newTools = new LinkedHashMap<>();

        final String json;

        // 1. 获取定义文档
        HttpUtils http = HttpUtils.http(source.getDocUrl());

        if (Assert.isNotEmpty(source.getHeaders())) {
            http.headers(source.getHeaders());
        }

        if(source.getTimeout() != null){
            http.timeout(HttpTimeout.of(source.getTimeout()));
        } else {
            http.timeout(HttpTimeout.of(defaultTimeout));
        }

        if (source.getAuthenticator() != null) {
            source.getAuthenticator().apply(http, null);
        } else if (defaultAuthenticator != null) {
            defaultAuthenticator.apply(http, null);
        }

        json = http.get();

        if (Utils.isEmpty(json)) {
            LOG.warn("ApiSourceClient: Source empty for {}", source.getDocUrl());
            return newTools;
        }

        // 2. 解析
        List<ApiTool> tools = resolver.resolve(source.getDocUrl(), json);

        // 3. 注册到 newTools（全量，不过滤）
        for (ApiTool tool : tools) {
            if (!tool.isDeprecated()) {
                tool.setBaseUrl(source.getApiBaseUrl());
                tool.setSource(source);
                newTools.put(tool.getName().toLowerCase(), tool);
            }
        }

        LOG.info("ApiSourceClient: Loaded {} tools from {}",
                newTools.size(), source.getDocUrl());

        return newTools;
    }

    // ===== 内部方法 =====

    /**
     * 过滤判断（基于自身副本）
     */
    private boolean isToolAllowed(ApiTool tool) {
        String name = tool.getName();

        // 白名单非空时，仅保留白名单中的工具
        if (!allowedTools.isEmpty()
                && !allowedTools.contains(name)) {
            return false;
        }

        // 黑名单非空时，剔除黑名单中的工具
        if (!disallowedTools.isEmpty()
                && disallowedTools.contains(name)) {
            return false;
        }

        return true;
    }
}