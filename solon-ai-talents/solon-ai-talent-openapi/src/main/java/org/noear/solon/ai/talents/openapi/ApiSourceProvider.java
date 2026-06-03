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
package org.noear.solon.ai.talents.openapi;

import org.noear.solon.Utils;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.ResourceUtil;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * API 源提供者（单个 ApiSource 的运行时代理）
 *
 * <p>对标 McpClientProvider 的角色：
 * - 持有 ApiSource 配置
 * - 持有解析后的 ApiTool 集合
 * - 自主加载（从 docUrl 获取定义文档并解析）
 * - 提供 allowed/disallowed 工具过滤
 * - 支持刷新（重新解析 + 过滤）
 *
 * @author noear
 * @since 3.10
 */
public class ApiSourceProvider {
    private static final Logger LOG = LoggerFactory.getLogger(ApiSourceProvider.class);

    private final ApiSource source;
    private final ApiResolver resolver;
    private final ApiAuthenticator defaultAuthenticator;
    private final Duration defaultTimeout;

    // 该源解析出的全量工具（不过滤，用于前端查阅全量列表）
    private final Map<String, ApiTool> rawTools = new LinkedHashMap<>();

    public ApiSourceProvider(ApiSource source, ApiResolver resolver, ApiAuthenticator defaultAuthenticator, Duration defaultTimeout) {
        this.source = source;
        this.resolver = resolver;
        this.defaultAuthenticator = defaultAuthenticator;
        this.defaultTimeout = defaultTimeout;
    }

    // ===== 配置访问 =====

    public ApiSource getSource() {
        return source;
    }

    public String getDocUrl() {
        return source.getDocUrl();
    }

    // ===== 权限控制 =====

    public List<String> getAllowedTools() {
        return source.getAllowedTools();
    }

    public void setAllowedTools(List<String> allowedTools) {
        source.setAllowedTools(allowedTools);
    }

    public void addAllowedTool(String toolName) {
        source.addAllowedTool(toolName);
    }

    public List<String> getDisallowedTools() {
        return source.getDisallowedTools();
    }

    public void setDisallowedTools(List<String> disallowedTools) {
        source.setDisallowedTools(disallowedTools);
    }

    public void addDisallowedTool(String toolName) {
        source.addDisallowedTool(toolName);
    }

    // ===== 工具查询 =====

    /**
     * 获取经过 allowed/disallowed 过滤后的工具列表
     */
    public Collection<ApiTool> getToolsActivated() {
        return rawTools.values().stream()
                .filter(this::isToolAllowed)
                .collect(Collectors.toList());
    }

    /**
     * 获取全量工具（不过滤，供前端展示完整列表用）
     */
    public Collection<ApiTool> getTools() {
        return Collections.unmodifiableCollection(rawTools.values());
    }


    // ===== 加载能力 =====

    /**
     * 从 docUrl 加载 API 定义文档，解析并注册工具
     *
     * <p>对标 McpClientProvider 的初始化流程：
     * - 获取远程/本地 JSON 定义
     * - 通过 resolver 解析为 ApiTool 列表
     * - 补充 baseUrl 和 source 引用
     * - 全量注册到 rawTools
     *
     * <p>调用方可随后通过 {@link #getToolsActivated()} 获取过滤后的工具集
     *
     * @throws IOException 获取或解析失败时抛出
     */
    public void loadApi() throws IOException {
        final String json;

        // 1. 获取定义文档
        if (source.getDocUrl().startsWith("http://") || source.getDocUrl().startsWith("https://")) {
            HttpUtils http = HttpUtils.http(source.getDocUrl());

            if (Assert.isNotEmpty(source.getHeaders())) {
                http.headers(source.getHeaders());
            }

            if (source.getAuthenticator() != null) {
                source.getAuthenticator().apply(http, null);
            } else if (defaultAuthenticator != null) {
                defaultAuthenticator.apply(http, null);
            }

            json = http.get();
        } else {
            json = ResourceUtil.findResourceAsString(source.getDocUrl());
        }

        if (Utils.isEmpty(json)) {
            LOG.warn("ApiSourceProvider: Source empty for {}", source.getDocUrl());
            return;
        }

        // 2. 解析
        List<ApiTool> tools = resolver.resolve(source.getDocUrl(), json);

        // 3. 清空旧工具（刷新场景）
        rawTools.clear();

        // 4. 注册到 rawTools（全量，不过滤）
        for (ApiTool tool : tools) {
            if (!tool.isDeprecated()) {
                tool.setBaseUrl(source.getApiBaseUrl());
                tool.setSource(source);
                rawTools.put(tool.getName().toLowerCase(), tool);
            }
        }

        LOG.info("ApiSourceProvider: Loaded {} tools from {} (filtered: {})",
                rawTools.size(), source.getDocUrl(), getToolsActivated().size());
    }

    // ===== 内部方法 =====

    /**
     * 过滤判断
     */
    private boolean isToolAllowed(ApiTool tool) {
        String name = tool.getName();

        // 白名单非空时，仅保留白名单中的工具
        if (!source.getAllowedTools().isEmpty()
                && !source.getAllowedTools().contains(name)) {
            return false;
        }

        // 黑名单非空时，剔除黑名单中的工具
        if (!source.getDisallowedTools().isEmpty()
                && !source.getDisallowedTools().contains(name)) {
            return false;
        }

        return true;
    }
}
