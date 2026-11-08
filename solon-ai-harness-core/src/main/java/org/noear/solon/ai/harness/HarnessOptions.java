/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness;

import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.agent.AgentSession;
import java.util.concurrent.ExecutorService;
import java.util.function.BiPredicate;
import org.noear.solon.ai.harness.command.CommandRegistry;
import org.noear.solon.ai.harness.session.SessionOwnership;
import org.noear.solon.ai.harness.scope.InputScopeResolver;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.mount.source.MountSource;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.agent.react.intercept.StopLoopInterceptor;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.agent.ToolName;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.mount.MountManager;
import org.noear.solon.ai.talents.gateway.openapi.ApiSource;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpUtils;

import java.net.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Harness 运行时配置（内部使用）
 *
 * @author noear
 * @since 4.0.0
 */
@Preview("4.0")
class HarnessOptions implements NonSerializable {

    // ========== 基础路径 ==========
    private final String harnessHome;
    private final String workspace;

    // ========== 提示词 ==========
    private volatile String systemPrompt;
    private volatile String userAgent;

    // ========== 引擎级 http 定制（proxy 动态切换） ==========
    private transient volatile Proxy httpProxy; //引擎级动态代理
    private transient volatile Consumer<HttpUtils> httpCustomize; //引擎级通用 http 钩子

    // ========== 主代理工具权限 ==========
    private Set<String> tools = new CopyOnWriteArraySet<>();

    // 禁用工具（全局）
    private Set<String> disallowedTools = new CopyOnWriteArraySet<>();

    // ========== 执行控制 ==========
    private volatile int maxTurns = 20;
    /** 受理占位时限（毫秒）：handle 返回 TASK 后宿主始终不开流时，会话最多被占这么久 */
    private volatile long admissionTimeoutMs = InputGate.DEFAULT_TIMEOUT_MS;
    private volatile boolean autoRethink = true;

    // ========== 会话与压缩 ==========
    private volatile int sessionWindowSize = 12;
    private volatile int compressionMaxMessages = 100;
    private volatile double compressionMaxContextRatio = 0.75D;
    private volatile long compressionDefaultContextLength = 128_000L; //128k

    // ========== 记忆 ==========
    private volatile boolean memoryEnabled = true;
    private volatile int memoryRelevanceCount = 6;
    private volatile int memoryPriorityCount = 5;
    private volatile int memorySummaryLength = 80;

    // ========== 安全与模式 ==========
    private volatile boolean sandboxEnabled = true;
    private volatile boolean sandboxAllowUserHome = false;
    private volatile boolean sandboxSystemRestrict = true;

    private volatile boolean hitlEnabled = false;
    private volatile PermissionContext permissionContext = PermissionContext.create();
    private final Object permissionLock = new Object();
    private volatile boolean subagentEnabled = true;
    private volatile boolean bashAsyncEnabled = false;

    // ========== 多租户 / 服务端 ==========
    private volatile HarnessProfile profile = HarnessProfile.CODING;
    /** 记忆是否按会话隔离（SERVER 缺省 true） */
    private volatile boolean memorySessionIsolation = false;
    /** 是否由引擎自行初始化进程级 OS 沙盒（SERVER 缺省 false，由宿主 SandboxBootstrap.initOnce） */
    private volatile boolean sandboxSelfBootstrap = true;
    /** 运行期是否允许修改引擎级全局配置（SERVER 缺省 false） */
    private volatile boolean runtimeMutable = true;
    /** 会话工作目录的边界策略（null = 不校验） */
    private volatile Predicate<String> cwdPolicy;
    /** 请求级作用域解析器（null = 不解析） */
    private volatile InputScopeResolver scopeResolver;
    /** 是否要求每轮都有作用域（SERVER 缺省 true）：缺失时受理拒绝、守卫禁用全部工具 */
    private volatile boolean scopeRequired = false;
    /** 会话归属（多节点单写者租约；缺省本机恒成功） */
    private volatile SessionOwnership sessionOwnership =
            SessionOwnership.LOCAL;
    /** 受保护的状态子路径（相对 harnessHome）：模型文件工具与 bash 都不得读写 */
    private volatile List<String> protectedStatePaths = DEFAULT_PROTECTED_STATE_PATHS;

    static final List<String> DEFAULT_PROTECTED_STATE_PATHS = Collections.unmodifiableList(
            Arrays.asList("sessions", "memory", "channels", "tasks.json", "loop", "goal"));

    // ========== 重试配置 ==========
    private volatile int apiRetries = 3;
    private volatile int mcpRetries = 3;
    private volatile int modelRetries = 3;

    // ========== 并行任务控制 ==========

    /**
     * 子代理任务默认并发上限。
     */
    private volatile int subagentDefaultConcurrency = 5;

    // ========== 缓存控制 ==========
    private volatile CacheControl cacheControl;

    // ========== 输入前缀 ==========
    /**
     * 命令前缀（默认 "/"）。支持多字符（如 "::"）。
     */
    private volatile String commandPrefix = "/";

    /**
     * 子代理提及前缀（默认 "@"）。支持多字符（如 "#"）。
     */
    private volatile String agentPrefix = "@";

    // ========== 集合类配置 ==========
    protected final MountManager mountManager;
    protected final CommandRegistry commandRegistry;
    private volatile String defaultModel;
    private final Map<String, ChatConfig> models = new ConcurrentHashMap<>();
    private final Map<String, McpServerParameters> mcpServers = new ConcurrentHashMap<>();
    private final Map<String, ApiSource> apiServers = new ConcurrentHashMap<>();
    private final Map<String, LspServerParameters> lspServers = new ConcurrentHashMap<>();
    private final List<HarnessExtension> extensions = new CopyOnWriteArrayList<>();

    // ========== 服务注入 ==========
    private AgentSessionProvider sessionProvider;
    private ContextCompressionInterceptor compressionInterceptor;
    private StopLoopInterceptor stopLoopInterceptor;
    private HITLInterceptor hitlInterceptor;
    private MemorySolutionProvider memoryProvider;
    /** harness 状态数据来源（TODO 清单等会话级状态）；null = 工作区下的 harnessHome 目录。 */
    private volatile MountSource stateSource;

    HarnessOptions(String workspace, String harnessHome) {
        if (Assert.isEmpty(harnessHome)) {
            harnessHome = ".solon/";
        } else if (!harnessHome.endsWith("/")) {
            harnessHome = harnessHome + "/";
        }

        this.workspace = workspace;
        this.harnessHome = harnessHome;
        this.mountManager = new MountManager(workspace);
        this.commandRegistry = new CommandRegistry();
    }

    // ========== 派生路径属性 ==========

    String getHarnessHome() {
        return harnessHome;
    }

    String getHarnessSessions() {
        return harnessHome + "sessions/";
    }

    String getHarnessSkills() {
        return harnessHome + "skills/";
    }

    String getHarnessAgents() {
        return harnessHome + "agents/";
    }

    String getHarnessCommands() {
        return harnessHome + "commands/";
    }

    String getHarnessMemory() {
        return harnessHome + "memory/";
    }

    String getHarnessChannels() {
        return harnessHome + "channels/";
    }

    /**
     * harness 状态数据来源：未设置时为 {@code <workspace>/<harnessHome>} 上的本地来源。
     *
     * <p>与工作区来源分开：工作区是模型可见的代码与产物；数据来源是 harness 自身状态，不注册进挂载表，
     * 模型的文件工具无法访问。</p>
     */
    MountSource getStateSource() {
        MountSource tmp = stateSource;
        if (tmp == null) {
            synchronized (this) {
                tmp = stateSource;
                if (tmp == null) {
                    Path root = Paths.get(workspace == null ? "." : workspace).resolve(harnessHome);
                    tmp = stateSource = FileMountSource.of(root.toAbsolutePath().normalize());
                }
            }
        }
        return tmp;
    }

    void setStateSource(MountSource stateSource) {
        this.stateSource = stateSource;
    }

    // ========== getter / setter ==========

    String getWorkspace() {
        return workspace;
    }

    String getSystemPrompt() {
        return systemPrompt;
    }

    void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    String getUserAgent() {
        return userAgent;
    }

    void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    Proxy getHttpProxy() {
        return httpProxy;
    }

    void setHttpProxy(Proxy httpProxy) {
        this.httpProxy = httpProxy;
    }

    Consumer<HttpUtils> getHttpCustomize() {
        return httpCustomize;
    }

    void addHttpCustomize(Consumer<HttpUtils> consumer) {
        if (consumer != null) {
            if (this.httpCustomize == null) {
                this.httpCustomize = consumer;
            } else {
                this.httpCustomize = this.httpCustomize.andThen(consumer);
            }
        }
    }

    void setHttpCustomize(Consumer<HttpUtils> consumer) {
        this.httpCustomize = consumer;
    }

    Set<String> getTools() {
        return tools;
    }

    Set<String> getDisallowedTools() {
        return disallowedTools;
    }

    int getMaxTurns() {
        return maxTurns;
    }

    long getAdmissionTimeoutMs() {
        return admissionTimeoutMs;
    }

    void setAdmissionTimeoutMs(Long admissionTimeoutMs) {
        if (admissionTimeoutMs != null) {
            if (admissionTimeoutMs <= 0) {
                throw new IllegalArgumentException("admissionTimeoutMs must be > 0: " + admissionTimeoutMs);
            }
            this.admissionTimeoutMs = admissionTimeoutMs;
        }
    }

    void setMaxTurns(Integer maxTurns) {
        if (maxTurns != null) {
            this.maxTurns = maxTurns;
        }
    }

    boolean isAutoRethink() {
        return autoRethink;
    }

    void setAutoRethink(Boolean autoRethink) {
        if (autoRethink != null) {
            this.autoRethink = autoRethink;
        }
    }

    int getSessionWindowSize() {
        return sessionWindowSize;
    }

    void setSessionWindowSize(Integer sessionWindowSize) {
        if (sessionWindowSize != null) {
            this.sessionWindowSize = sessionWindowSize;
        }
    }

    int getCompressionMaxMessages() {
        return compressionMaxMessages;
    }

    void setCompressionMaxMessages(Integer compressionMaxMessages) {
        if (compressionMaxMessages != null) {
            this.compressionMaxMessages = compressionMaxMessages;
        }
    }

    double getCompressionMaxContextRatio() {
        return compressionMaxContextRatio;
    }

    void setCompressionMaxContextRatio(Double compressionMaxContextRatio) {
        if (compressionMaxContextRatio != null) {
            this.compressionMaxContextRatio = compressionMaxContextRatio;
        }
    }

    long getCompressionDefaultContextLength() {
        return compressionDefaultContextLength;
    }

    void setCompressionDefaultContextLength(Long compressionDefaultContextLength) {
        if (compressionDefaultContextLength != null) {
            this.compressionDefaultContextLength = compressionDefaultContextLength;
        }
    }

    boolean isMemoryEnabled() {
        return memoryEnabled;
    }

    void setMemoryEnabled(Boolean memoryEnabled) {
        if (memoryEnabled != null) {
            this.memoryEnabled = memoryEnabled;
        }
    }

    int getMemoryRelevanceCount() {
        return memoryRelevanceCount;
    }

    void setMemoryRelevanceCount(int n) {
        this.memoryRelevanceCount = n;
    }

    int getMemoryPriorityCount() {
        return memoryPriorityCount;
    }

    void setMemoryPriorityCount(int n) {
        this.memoryPriorityCount = n;
    }

    int getMemorySummaryLength() {
        return memorySummaryLength;
    }

    void setMemorySummaryLength(int n) {
        this.memorySummaryLength = n;
    }

    boolean isSandboxEnabled() {
        return sandboxEnabled;
    }

    void setSandboxEnabled(Boolean sandboxEnabled) {
        if (sandboxEnabled != null) {
            this.sandboxEnabled = sandboxEnabled;
        }
    }

    boolean isSandboxAllowUserHome() {
        return sandboxAllowUserHome;
    }

    void setSandboxAllowUserHome(Boolean sandboxAllowUserHome) {
        if (sandboxAllowUserHome != null) {
            this.sandboxAllowUserHome = sandboxAllowUserHome;
        }
    }

    boolean isSandboxSystemRestrict() {
        return sandboxSystemRestrict;
    }

    void setSandboxSystemRestrict(Boolean sandboxSystemRestrict) {
        if (sandboxSystemRestrict != null) {
            this.sandboxSystemRestrict = sandboxSystemRestrict;
        }
    }

    boolean isHitlEnabled() {
        return hitlEnabled;
    }

    void setHitlEnabled(Boolean hitlEnabled) {
        if (hitlEnabled != null) {
            this.hitlEnabled = hitlEnabled;
        }
    }

    PermissionContext getPermissionContext() {
        return permissionContext;
    }

    void setPermissionContext(PermissionContext permissionContext) {
        if (permissionContext != null) {
            synchronized (permissionLock) {
                this.permissionContext = permissionContext;
            }
        }
    }

    /**
     * 原子地读-改-写权限上下文（并发审批 / 宿主加规则时不丢规则）
     */
    void updatePermissionContext(UnaryOperator<PermissionContext> updater) {
        synchronized (permissionLock) {
            PermissionContext next = updater.apply(this.permissionContext);
            if (next != null) {
                this.permissionContext = next;
            }
        }
    }

    boolean isSubagentEnabled() {
        return subagentEnabled;
    }

    void setSubagentEnabled(Boolean subagentEnabled) {
        if (subagentEnabled != null) {
            this.subagentEnabled = subagentEnabled;
        }
    }

    /**
     * 子代理任务默认并发上限（仅在模型未配置 {@code AiConfig#concurrency} 时生效）
     *
     * <p>恒为有限正整数：0 被解释为「未设置」（与 {@code contextLength} 同理），
     * 本项不提供「不限制」。</p>
     */
    int getSubagentDefaultConcurrency() {
        return subagentDefaultConcurrency;
    }

    /**
     * @param subagentDefaultConcurrency 子代理任务默认并发上限；{@code 0} = 未设（取默认 5），
     *                                   上界 {@link TaskTalent#MAX_CONCURRENCY}，<b>不存在「0 = 不限制」</b>
     * @throws IllegalArgumentException 传入负数
     */
    void setSubagentDefaultConcurrency(Integer subagentDefaultConcurrency) {
        if (subagentDefaultConcurrency != null) {
            this.subagentDefaultConcurrency = TaskTalent.normalizeDefaultConcurrency(subagentDefaultConcurrency);
        }
    }

    boolean isBashAsyncEnabled() {
        return bashAsyncEnabled;
    }

    void setBashAsyncEnabled(Boolean bashAsyncEnabled) {
        if (bashAsyncEnabled != null) {
            this.bashAsyncEnabled = bashAsyncEnabled;
        }
    }

    int getApiRetries() {
        return apiRetries;
    }

    void setApiRetries(Integer apiRetries) {
        if (apiRetries != null) {
            this.apiRetries = apiRetries;
        }
    }

    int getMcpRetries() {
        return mcpRetries;
    }

    void setMcpRetries(Integer mcpRetries) {
        if (mcpRetries != null) {
            this.mcpRetries = mcpRetries;
        }
    }

    int getModelRetries() {
        return modelRetries;
    }

    void setModelRetries(Integer modelRetries) {
        if (modelRetries != null) {
            this.modelRetries = modelRetries;
        }
    }

    // ========== 多租户 / 服务端 ==========

    HarnessProfile getProfile() {
        return profile;
    }

    /**
     * 在 {@code build()} 时合成预设：显式值 &gt; 预设值 &gt; 默认值，与调用顺序无关。
     *
     * @param explicit 用户显式设过的字段名（这些字段不被预设覆盖）
     */
    void applyProfile(HarnessProfile profile, Set<String> explicit) {
        if (profile == null) {
            return;
        }
        this.profile = profile;
        if (profile == HarnessProfile.SERVER) {
            if (!explicit.contains("sandboxEnabled")) this.sandboxEnabled = true;
            if (!explicit.contains("sandboxAllowUserHome")) this.sandboxAllowUserHome = false;
            if (!explicit.contains("sandboxSystemRestrict")) this.sandboxSystemRestrict = true;
            if (!explicit.contains("sandboxSelfBootstrap")) this.sandboxSelfBootstrap = false;
            if (!explicit.contains("bashAsyncEnabled")) this.bashAsyncEnabled = false;
            if (!explicit.contains("memorySessionIsolation")) this.memorySessionIsolation = true;
            if (!explicit.contains("runtimeMutable")) this.runtimeMutable = false;
            if (!explicit.contains("scopeRequired")) this.scopeRequired = true;
        }
    }

    boolean isScopeRequired() {
        return scopeRequired;
    }

    void setScopeRequired(boolean scopeRequired) {
        this.scopeRequired = scopeRequired;
    }

    boolean isMemorySessionIsolation() {
        return memorySessionIsolation;
    }

    void setMemorySessionIsolation(boolean memorySessionIsolation) {
        this.memorySessionIsolation = memorySessionIsolation;
    }

    boolean isSandboxSelfBootstrap() {
        return sandboxSelfBootstrap;
    }

    void setSandboxSelfBootstrap(boolean sandboxSelfBootstrap) {
        this.sandboxSelfBootstrap = sandboxSelfBootstrap;
    }

    boolean isRuntimeMutable() {
        return runtimeMutable;
    }

    void setRuntimeMutable(boolean runtimeMutable) {
        this.runtimeMutable = runtimeMutable;
    }

    Predicate<String> getCwdPolicy() {
        return cwdPolicy;
    }

    /** 全局子代理持久化策略（null = 按 runtimeMutable） */
    private volatile BiPredicate<AgentSession, AgentDefinition> agentPersistPolicy;

    /** 子代理执行器（null = 引擎私有） */
    private volatile ExecutorService taskExecutor;

    ExecutorService getTaskExecutor() {
        return taskExecutor;
    }

    void setTaskExecutor(ExecutorService taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    BiPredicate<AgentSession, AgentDefinition> getAgentPersistPolicy() {
        return agentPersistPolicy;
    }

    void setAgentPersistPolicy(BiPredicate<AgentSession, AgentDefinition> agentPersistPolicy) {
        this.agentPersistPolicy = agentPersistPolicy;
    }

    void setCwdPolicy(Predicate<String> cwdPolicy) {
        this.cwdPolicy = cwdPolicy;
    }

    InputScopeResolver getScopeResolver() {
        return scopeResolver;
    }

    void setScopeResolver(InputScopeResolver scopeResolver) {
        this.scopeResolver = scopeResolver;
    }

    SessionOwnership getSessionOwnership() {
        return sessionOwnership;
    }

    void setSessionOwnership(SessionOwnership sessionOwnership) {
        this.sessionOwnership = sessionOwnership == null
                ? SessionOwnership.LOCAL : sessionOwnership;
    }

    List<String> getProtectedStatePaths() {
        return protectedStatePaths;
    }

    void setProtectedStatePaths(List<String> protectedStatePaths) {
        this.protectedStatePaths = protectedStatePaths == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(protectedStatePaths));
    }

    /** 显式设置的状态来源（不触发缺省值） */
    MountSource getStateSourceOrNull() {
        return stateSource;
    }

    // ========== 缓存控制 ==========

    CacheControl getCacheControl() {
        return cacheControl;
    }

    void setCacheControl(CacheControl cacheControl) {
        this.cacheControl = cacheControl;
    }

    // ========== 输入前缀 ==========

    String getCommandPrefix() {
        return commandPrefix;
    }

    void setCommandPrefix(String commandPrefix) {
        if (Assert.isEmpty(commandPrefix)) {
            throw new IllegalArgumentException("commandPrefix cannot be empty");
        }
        this.commandPrefix = commandPrefix;
    }

    String getAgentPrefix() {
        return agentPrefix;
    }

    void setAgentPrefix(String agentPrefix) {
        if (Assert.isEmpty(agentPrefix)) {
            throw new IllegalArgumentException("agentPrefix cannot be empty");
        }
        this.agentPrefix = agentPrefix;
    }

    List<HarnessExtension> getExtensions() {
        return extensions;
    }

    Map<String, ChatConfig> getModels() {
        return models;
    }

    String getDefaultModel() {
        return defaultModel;
    }

    void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    Map<String, McpServerParameters> getMcpServers() {
        return mcpServers;
    }

    Map<String, ApiSource> getApiServers() {
        return apiServers;
    }

    Map<String, LspServerParameters> getLspServers() {
        return lspServers;
    }

    // ========== 集合操作方法 ==========

    void addTools(ToolName... toolNames) {
        for (ToolName p1 : toolNames) {
            tools.add(p1.getName());
        }
    }

    void addDisallowedTools(ToolName... toolNames) {
        for (ToolName p1 : toolNames) {
            disallowedTools.add(p1.getName());
        }
    }

    void addModel(ChatConfig chatConfig) {
        models.put(chatConfig.getNameOrModel(), chatConfig);
    }

    void removeModel(String modelName) {
        models.remove(modelName);
    }

    boolean hasModel(String modelName) {
        return models.containsKey(modelName);
    }

    ChatConfig getModelOrNil(String modelName) {
        if (models.isEmpty()) {
            return null;
        }

        if (Assert.isEmpty(modelName)) {
            return null;
        }

        ChatConfig c = models.get(modelName);
        if (c != null && c.isEnabled()) {
            return c;
        }

        return null;
    }

    ChatConfig getModelOrDef(String modelName) {
        if (models.isEmpty()) {
            return null;
        }

        if (Assert.isEmpty(modelName)) {
            return getDefaultModelConfig();
        }

        ChatConfig c = models.get(modelName);
        if (c != null && c.isEnabled()) {
            return c;
        }

        return getDefaultModelConfig();
    }

    /**
     * 获取默认模型配置：优先 defaultModel 指定的，否则取 Map 中第一个启用项
     * <p>
     * 与显式名路径（{@link #getModelOrNil}）对齐，都跳过 {@code enabled=false} 的配置；
     * 全部禁用或全为 null 时返回 null，由调用方给出明确错误（勿静默构建禁用模型）。
     */
    private ChatConfig getDefaultModelConfig() {
        if (Assert.isNotEmpty(defaultModel)) {
            ChatConfig c = models.get(defaultModel);
            if (c != null && c.isEnabled()) {
                return c;
            }
        }

        // fallback 到第一个启用项
        for (ChatConfig c : models.values()) {
            if (c != null && c.isEnabled()) {
                return c;
            }
        }

        return null;
    }

    // ========== 服务注入 getter / setter ==========

    AgentSessionProvider getSessionProvider() {
        return sessionProvider;
    }

    void setSessionProvider(AgentSessionProvider sessionProvider) {
        this.sessionProvider = sessionProvider;
    }

    ContextCompressionInterceptor getCompressionInterceptor() {
        return compressionInterceptor;
    }

    void setCompressionInterceptor(ContextCompressionInterceptor compressionInterceptor) {
        this.compressionInterceptor = compressionInterceptor;
    }

    StopLoopInterceptor getStopLoopInterceptor() {
        return stopLoopInterceptor;
    }

    void setStopLoopInterceptor(StopLoopInterceptor stopLoopInterceptor) {
        this.stopLoopInterceptor = stopLoopInterceptor;
    }

    HITLInterceptor getHitlInterceptor() {
        return hitlInterceptor;
    }

    void setHitlInterceptor(HITLInterceptor hitlInterceptor) {
        this.hitlInterceptor = hitlInterceptor;
    }

    MemorySolutionProvider getMemoryProvider() {
        return memoryProvider;
    }

    void setMemoryProvider(MemorySolutionProvider memoryProvider) {
        this.memoryProvider = memoryProvider;
    }
}