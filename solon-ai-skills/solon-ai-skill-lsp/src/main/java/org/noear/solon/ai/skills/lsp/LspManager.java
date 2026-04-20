/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.skills.lsp;

import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * LSP 服务器管理器，负责多语言服务器的生命周期管理与路由
 *
 * <p>参考 OpenCode 的 LSP 配置模型：
 * <ul>
 *   <li>按文件扩展名路由到对应的 LSP 服务器</li>
 *   <li>延迟启动（首次使用时才创建连接）</li>
 *   <li>统一关闭与资源清理</li>
 * </ul>
 *
 * @author noear
 * @since 3.10.0
 */
public class LspManager {
    private static final Logger LOG = LoggerFactory.getLogger(LspManager.class);

    private final Map<String, LspServerParameters> serverConfigs = new LinkedHashMap<>();
    private final Map<String, LspClient> activeClients = new ConcurrentHashMap<>();
    private final String workspace;
    private BiConsumer<String, String> diagnosticsCallback;

    /**
     * @param workspace 工作区根目录
     */
    public LspManager(String workspace) {
        this.workspace = workspace;
    }

    /**
     * 设置诊断信息回调
     */
    public void setDiagnosticsCallback(BiConsumer<String, String> callback) {
        this.diagnosticsCallback = callback;
    }

    /**
     * 注册一个 LSP 服务器配置
     */
    public void registerServer(String name, LspServerParameters params) {
        Objects.requireNonNull(name, "Server name cannot be null");
        Objects.requireNonNull(params, "Server params cannot be null");

        if (params.isDisabled()) {
            LOG.info("LSP server '{}' is disabled, skipping registration", name);
            return;
        }

        if (Assert.isEmpty(params.getCommand())) {
            LOG.warn("LSP server '{}' has no command configured, skipping", name);
            return;
        }

        if (Assert.isEmpty(params.getExtensions())) {
            LOG.warn("LSP server '{}' has no extensions configured, skipping", name);
            return;
        }

        serverConfigs.put(name, params);
        LOG.info("Registered LSP server '{}': command={}, extensions={}",
                name, params.getCommand(), params.getExtensions());
    }

    /**
     * 获取服务器配置
     */
    public LspServerParameters getServerConfig(String name) {
        return serverConfigs.get(name);
    }

    /**
     * 获取所有服务器配置
     */
    public Map<String, LspServerParameters> getServerConfigs() {
        return Collections.unmodifiableMap(serverConfigs);
    }

    /**
     * 根据文件路径获取对应的 LSP 客户端（延迟启动）
     *
     * @param filePath 文件相对路径或绝对路径
     * @return 匹配的 LspClientImpl，如果无匹配则返回 null
     */
    public LspClient getClientForFile(String filePath) {
        for (Map.Entry<String, LspServerParameters> entry : serverConfigs.entrySet()) {
            if (entry.getValue().matchesExtension(filePath)) {
                return getOrCreateClient(entry.getKey(), entry.getValue());
            }
        }
        return null;
    }

    /**
     * 根据服务器名获取 LSP 客户端（延迟启动）
     */
    public LspClient getClient(String name) {
        LspServerParameters config = serverConfigs.get(name);
        if (config == null) {
            return null;
        }
        return getOrCreateClient(name, config);
    }

    /**
     * 获取活跃的客户端数量
     */
    public int getActiveClientCount() {
        return activeClients.size();
    }

    /**
     * 是否有可用的服务器配置
     */
    public boolean hasServers() {
        return !serverConfigs.isEmpty();
    }

    private LspClient getOrCreateClient(String name, LspServerParameters params) {
        return activeClients.computeIfAbsent(name, k -> {
            try {
                LOG.info("Starting LSP server '{}': {}", name, params.getCommand());
                LspClientImpl client = new LspClientImpl(
                        params.getCommandArray(),
                        workspace
                );

                // 设置诊断信息回调
                client.setDiagnosticsConsumer((uri, text) -> {
                    if (diagnosticsCallback != null) {
                        diagnosticsCallback.accept(uri, text);
                    }
                });

                LOG.info("LSP server '{}' started successfully", name);
                return client;
            } catch (Exception e) {
                LOG.error("Failed to start LSP server '{}': {}", name, e.getMessage(), e);
                return null;
            }
        });
    }

    /**
     * 关闭所有 LSP 服务器
     */
    public void shutdownAll() {
        LOG.info("Shutting down {} LSP servers...", activeClients.size());
        for (Map.Entry<String, LspClient> entry : activeClients.entrySet()) {
            try {
                entry.getValue().shutdown();
                LOG.info("LSP server '{}' shut down", entry.getKey());
            } catch (Exception e) {
                LOG.warn("Error shutting down LSP server '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        activeClients.clear();
    }
}
