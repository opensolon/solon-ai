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
package org.noear.solon.ai.talents.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.noear.solon.ai.talents.lsp.exception.LspCommandNotFoundException;
import org.noear.solon.ai.talents.lsp.exception.LspEnvironmentException;
import org.noear.solon.ai.talents.lsp.exception.LspNoMatchException;
import org.noear.solon.ai.talents.lsp.exception.LspStartException;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

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

    private final Map<String, LspServerParameters> serverConfigs = new ConcurrentHashMap<>();
    private final Map<String, LspClient> activeClients = new ConcurrentHashMap<>();
    /**
     * 启动失败的服务器：name -> 失败原因。
     *
     * <p>没有这层记忆，未安装语言服务器的机器会在每次写文件时反复 fork 失败进程并刷错误栈。
     */
    private final Map<String, RuntimeException> brokenServers = new ConcurrentHashMap<>();
    private final String workspace;
    private final ReentrantLock clientLock = new ReentrantLock();
    private BiConsumer<String, List<Diagnostic>> diagnosticsCallback;

    /**
     * @param workspace 工作区根目录
     */
    public LspManager(String workspace) {
        this.workspace = workspace;
    }

    public boolean isEmpty() {
        return serverConfigs.isEmpty();
    }

    /**
     * 设置诊断信息回调（结构化诊断列表）
     */
    public void setDiagnosticsCallback(BiConsumer<String, List<Diagnostic>> callback) {
        this.diagnosticsCallback = callback;
    }

    /**
     * 注册一个 LSP 服务器配置
     *
     * <p>禁用的配置也会登记（路由时跳过）：注册与启动本就是分离的（首次用到才拉起进程），
     * 在注册阶段丢弃配置会让「已配置的服务器清单」失真，设置页也无法呈现完整列表。
     */
    public void registerServer(String name, LspServerParameters params) {
        Objects.requireNonNull(name, "Server name cannot be null");
        Objects.requireNonNull(params, "Server params cannot be null");

        if (Assert.isEmpty(params.getCommand())) {
            LOG.warn("LSP server '{}' has no command configured, skipping", name);
            return;
        }

        if (Assert.isEmpty(params.getExtensions())) {
            LOG.warn("LSP server '{}' has no extensions configured, skipping", name);
            return;
        }

        serverConfigs.put(name, params);
        //配置变更后清掉旧的失败记忆，让用户改完命令能立刻重试
        brokenServers.remove(name);

        if (params.isEnabled()) {
            LOG.info("Registered LSP server '{}': command={}, extensions={}",
                    name, params.getCommand(), params.getExtensions());
        } else {
            LOG.debug("Registered LSP server '{}' (disabled): command={}", name, params.getCommand());
        }
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
     * @return 匹配的 LspClient
     * @throws LspNoMatchException 文件扩展名没有匹配的 LSP 服务器配置
     * @throws LspCommandNotFoundException 命令不存在或不可执行
     * @throws LspEnvironmentException 运行环境不满足（如 Java 版本过低）
     * @throws LspStartException  匹配到服务器但启动失败（初始化超时等其他原因）
     */
    public LspClient getClientForFile(String filePath) throws LspNoMatchException, LspCommandNotFoundException, LspEnvironmentException, LspStartException {
        for (Map.Entry<String, LspServerParameters> entry : serverConfigs.entrySet()) {
            if (entry.getValue().isEnabled() == false) {
                continue;
            }
            if (entry.getValue().matchesExtension(filePath)) {
                return getOrCreateClient(entry.getKey(), entry.getValue());
            }
        }
        throw new LspNoMatchException(filePath, getSupportedExtensionsSummary());
    }

    /**
     * 文件类型是否有可用（已启用且未标记失败）的 LSP 服务器。
     *
     * <p>供写入/读取钩子做零成本短路：不涉及任何进程启动。
     */
    public boolean hasClientFor(String filePath) {
        for (Map.Entry<String, LspServerParameters> entry : serverConfigs.entrySet()) {
            if (entry.getValue().isEnabled() == false) {
                continue;
            }
            if (entry.getValue().matchesExtension(filePath)) {
                return brokenServers.containsKey(entry.getKey()) == false;
            }
        }
        return false;
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
     * 是否有可用（已启用）的服务器配置
     */
    public boolean hasServers() {
        for (LspServerParameters params : serverConfigs.values()) {
            if (params.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 服务器是否已被标记为启动失败
     */
    public boolean isBroken(String name) {
        return brokenServers.containsKey(name);
    }

    /**
     * 清除启动失败记忆（用户装完语言服务器后可手动重试）
     */
    public void clearBroken() {
        brokenServers.clear();
    }

    /**
     * 命令是否在 PATH 中可用。
     *
     * <p>直接扫 PATH 目录而不是 fork {@code which}：该判定会在工作区初始化与设置页
     * 渲染时对十几个服务器批量执行，进程成本不可接受。结果进程内缓存。
     */
    public static boolean isCommandAvailable(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }

        Boolean cached = COMMAND_AVAILABLE_CACHE.get(command);
        if (cached != null) {
            return cached;
        }

        boolean available = probeCommand(command);
        COMMAND_AVAILABLE_CACHE.put(command, available);
        return available;
    }

    private static final Map<String, Boolean> COMMAND_AVAILABLE_CACHE = new ConcurrentHashMap<>();

    private static boolean probeCommand(String command) {
        try {
            File direct = new File(command);
            if (direct.isAbsolute()) {
                return direct.isFile() && direct.canExecute();
            }

            String pathEnv = System.getenv("PATH");
            if (pathEnv == null || pathEnv.isEmpty()) {
                return false;
            }

            //Windows 下命令常以 .cmd/.exe/.bat 落盘，配置里则写裸名
            List<String> suffixes = new ArrayList<>();
            suffixes.add("");
            String pathExt = System.getenv("PATHEXT");
            if (pathExt != null && !pathExt.isEmpty()) {
                //PATHEXT 仅 Windows 存在，固定以 ; 分隔
                for (String ext : pathExt.split(";")) {
                    if (!ext.isEmpty()) {
                        suffixes.add(ext.toLowerCase());
                    }
                }
            }

            for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
                if (dir == null || dir.isEmpty()) {
                    continue;
                }
                for (String suffix : suffixes) {
                    File candidate = new File(dir, command + suffix);
                    if (candidate.isFile() && candidate.canExecute()) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Failed to probe command '{}': {}", command, e.getMessage());
        }
        return false;
    }

    /**
     * 获取已启用服务器支持的扩展名摘要（用于异常提示）
     */
    private String getSupportedExtensionsSummary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, LspServerParameters> entry : serverConfigs.entrySet()) {
            if (entry.getValue().isEnabled() == false) {
                continue;
            }
            if (sb.length() > 0) sb.append("; ");
            sb.append(entry.getKey()).append(": ");
            sb.append(String.join(", ", entry.getValue().getExtensions()));
        }
        return sb.toString();
    }

    private LspClient getOrCreateClient(String name, LspServerParameters params) throws LspStartException {
        LspClient existing = activeClients.get(name);
        if (existing != null) {
            return existing;
        }

        //已知启动失败：直接重抛缓存原因，不再反复 fork 进程
        RuntimeException broken = brokenServers.get(name);
        if (broken != null) {
            throw broken;
        }

        clientLock.lock();
        try {
            // double-check
            existing = activeClients.get(name);
            if (existing != null) {
                return existing;
            }

            broken = brokenServers.get(name);
            if (broken != null) {
                throw broken;
            }

            LOG.info("Starting LSP server '{}': {}", name, params.getCommand());
            LspClientImpl client = new LspClientImpl(
                    name,
                    params.getCommandArray(),
                    workspace,
                    params.getInitialization(),
                    params.getEnv()
            );

            // 设置诊断信息回调
            client.setDiagnosticsConsumer((uri, items) -> {
                if (diagnosticsCallback != null) {
                    diagnosticsCallback.accept(uri, items);
                }
            });

            activeClients.put(name, client);
            LOG.info("LSP server '{}' started successfully", name);
            return client;
        } catch (LspCommandNotFoundException | LspEnvironmentException | LspStartException e) {
            brokenServers.put(name, e);
            LOG.error("Failed to start LSP server '{}' (will not retry until config changes): {}", name, e.getMessage());
            throw e; // 透传具体异常类型
        } catch (Exception e) {
            LspStartException se = new LspStartException(name, params.getCommandArray(), e);
            brokenServers.put(name, se);
            LOG.error("Failed to start LSP server '{}' (will not retry until config changes): {}", name, e.getMessage());
            throw se;
        } finally {
            clientLock.unlock();
        }
    }

    /**
     * 注销并关闭一个 LSP 服务器
     */
    public void unregisterServer(String name) {
        Objects.requireNonNull(name, "Server name cannot be null");

        serverConfigs.remove(name);
        brokenServers.remove(name);

        LspClient client = activeClients.remove(name);
        if (client != null) {
            try {
                client.shutdown();
                LOG.info("LSP server '{}' shut down", name);
            } catch (Exception e) {
                LOG.warn("Error shutting down LSP server '{}': {}", name, e.getMessage());
            }
        }
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

    public static Map<String, LspServerParameters> buildLspServers() {
        Map<String, LspServerParameters> lspServers = new LinkedHashMap<>();

        //检测，有没有 ava

        //lspServers.put("java", new LspServerParameters(Arrays.asList("jdtls", "--java-executable", "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java"), Arrays.asList(".java")));
        lspServers.put("java", new LspServerParameters(Arrays.asList("jdtls"), Arrays.asList(".java")));
        lspServers.put("typescript", new LspServerParameters(Arrays.asList("typescript-language-server", "--stdio"), Arrays.asList(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".mts", ".cts")));
        lspServers.put("go", new LspServerParameters(Arrays.asList("gopls"), Arrays.asList(".go")));
        lspServers.put("python", new LspServerParameters(Arrays.asList("pyright-langserver", "--stdio"), Arrays.asList(".py", ".pyi")));
        lspServers.put("rust", new LspServerParameters(Arrays.asList("rust-analyzer"), Arrays.asList(".rs")));
        lspServers.put("c-cpp", new LspServerParameters(Arrays.asList("clangd", "--background-index", "--clang-tidy"), Arrays.asList(".c", ".h", ".cpp", ".hpp", ".cc", ".cxx", ".hxx", ".c++", ".h++", ".hh")));
        lspServers.put("csharp", new LspServerParameters(Arrays.asList("roslyn-language-server", "--stdio", "--autoLoadProjects"), Arrays.asList(".cs", ".csx")));
        lspServers.put("ruby", new LspServerParameters(Arrays.asList("solargraph", "stdio"), Arrays.asList(".rb", ".rake", ".gemspec", ".ru")));
        lspServers.put("php", new LspServerParameters(Arrays.asList("intelephense", "--stdio"), Arrays.asList(".php")));
        lspServers.put("bash", new LspServerParameters(Arrays.asList("bash-language-server", "start"), Arrays.asList(".sh", ".bash", ".zsh", ".ksh")));
        lspServers.put("lua", new LspServerParameters(Arrays.asList("lua-language-server"), Arrays.asList(".lua")));
        lspServers.put("dart", new LspServerParameters(Arrays.asList("dart", "language-server", "--lsp"), Arrays.asList(".dart")));
        lspServers.put("swift", new LspServerParameters(Arrays.asList("sourcekit-lsp"), Arrays.asList(".swift", ".objc", ".objcpp")));
        lspServers.put("kotlin", new LspServerParameters(Arrays.asList("kotlin-language-server"), Arrays.asList(".kt", ".kts")));
        lspServers.put("yaml", new LspServerParameters(Arrays.asList("yaml-language-server", "--stdio"), Arrays.asList(".yaml", ".yml")));

        return lspServers;
    }
}
