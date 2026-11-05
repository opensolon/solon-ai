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

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.agent.react.intercept.CompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.StopLoopInterceptor;
import org.noear.solon.ai.agent.react.intercept.compress.CompositeCompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.HierarchicalCompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.KeyInfoExtractionStrategy;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.MediaBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.agent.*;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandRegistry;
import org.noear.solon.ai.harness.input.AgentMentions;
import org.noear.solon.ai.harness.input.BufferedCommandContextFactory;
import org.noear.solon.ai.harness.input.CommandDispatcher;
import org.noear.solon.ai.harness.input.CommandContextFactory;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.HitlCard;
import org.noear.solon.ai.harness.input.InputAttachments;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.input.ParsedCommand;
import org.noear.solon.ai.harness.hitl.BashToolStrategy;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.input.AdmitDecision;
import org.noear.solon.ai.harness.session.InputGate;
import org.noear.solon.ai.harness.session.InputBacklog;
import org.noear.solon.ai.harness.session.TurnDispatch;
import org.noear.solon.ai.harness.session.HarnessAttrs;
import org.noear.solon.ai.harness.session.SessionSelections;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.session.interrupt.InterruptHelper;
import org.noear.solon.ai.harness.session.interrupt.InterruptPlan;

import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.harness.permission.ToolPermission;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.talents.mount.*;
import org.noear.solon.ai.talents.mount.source.ClasspathMountSource;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.harness.agent.ToolName;
import org.noear.solon.ai.talents.cli.*;
import org.noear.solon.ai.talents.code.CodeTalent;
import org.noear.solon.ai.talents.lsp.LspManager;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.ai.talents.lsp.LspTalent;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import java.util.stream.Collectors;
import org.noear.solon.ai.talents.gateway.openapi.ApiSource;
import org.noear.solon.ai.talents.gateway.openapi.ApiSourceClient;
import org.noear.solon.ai.talents.gateway.OpenApiGatewayTalent;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
import org.noear.solon.ai.talents.mount.catalog.*;
import org.noear.solon.ai.talents.web.CodeSearchTalent;
import org.noear.solon.ai.talents.web.WebfetchTalent;
import org.noear.solon.ai.talents.web.WebsearchTalent;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Harness 引擎
 *
 * @author noear
 * @since 4.0
 */
@Preview("3.10")
public class HarnessEngine {
    private static final Logger LOG = LoggerFactory.getLogger(HarnessEngine.class);

    public final static String ATTR_CWD = "__cwd";
    private static final String BUILTIN_AGENT_MOUNT = "@harness-agents";
    private static final String BUILTIN_AGENT_PATH = "META-INF/solon/ai/harness/agents/";

    /**
     * 会话属性：本轮实际生效的子代理名（含 {@code @子代理} 解析结果）。
     *
     * <p>由 {@code handle} 在返回 {@link InputOutcome.Kind#TASK TASK} 时自动写入，
     * 主代理归一为 {@code "main"}。HITL 续流（审批请求通常不带 selectedAgent）
     * 与事件侧「源代理判定」均以它为准：trace 按代理名分 key，取错代理会取不到挂起的
     * tool_calls，表现为续跑空转。无状态宿主（如 Web 的跨请求审批）不必再自建记忆。</p>
     *
     * @since 4.2.0
     */
    public final static String ATTR_AGENT_EFFECTIVE = HarnessAttrs.AGENT_EFFECTIVE;

    private final ReentrantLock agentLock = new ReentrantLock();

    private final HarnessOptions options;

    private final SkillCatalog skillCatalog;
    private final AgentCatalog agentCatalog;

    private final CodeTalent codeTalent;
    private final TodoTalent todoTalent;
    private final TaskTalent taskTalent;
    private final GenerateTalent generateTalent;

    private final ClockTalent clockTalent;

    private final WebfetchTalent webfetchTalent;
    private final WebsearchTalent websearchTalent;
    private final CodeSearchTalent codeSearchTalent;

    private final LspManager lspManager;
    private final LspTalent lspTalent;

    private final McpGatewayTalent mcpGatewayTalent;
    private final OpenApiGatewayTalent openApiGatewayTalent;

    private final MemoryTalent memoryTalent;

    private final TerminalTalent terminalTalent;
    private final SkillTalent skillTalent;

    private final CommandRegistry commandRegistry = new CommandRegistry();

    private final AgentManager agentManager;

    private volatile ReActAgent mainAgent; //允许运行时切换

    public String getName() {
        return getMainAgent().name();
    }

    public CommandRegistry getCommandRegistry() {
        return commandRegistry;
    }

    public AgentManager getAgentManager() {
        return agentManager;
    }

    public ContextCompressionInterceptor getCompressionInterceptor() {
        return options.getCompressionInterceptor();
    }

    public StopLoopInterceptor getStopLoopInterceptor() {
        return options.getStopLoopInterceptor();
    }

    public CacheControl getCacheControl() {
        return options.getCacheControl();
    }

    // ========== 权限系统 ==========

    /**
     * 获取当前权限上下文
     */
    public PermissionContext getPermissionContext() {
        return options.getPermissionContext();
    }

    /**
     * 添加权限规则
     */
    public void addPermissionRule(PermissionRule rule) {
        if (rule != null) {
            options.setPermissionContext(options.getPermissionContext().addRule(rule));
        }
    }

    /**
     * 批量添加权限规则
     */
    public void addPermissionRules(List<PermissionRule> rules) {
        if (Utils.isNotEmpty(rules)) {
            options.setPermissionContext(options.getPermissionContext().addRules(rules));
        }
    }


    public HITLInterceptor getHitlInterceptor() {
        return options.getHitlInterceptor();
    }

    public TerminalTalent getTerminalTalent() {
        return terminalTalent;
    }

    public SkillTalent getSkillTalent() {
        return skillTalent;
    }

    public TodoTalent getTodoTalent() {
        return todoTalent;
    }

    public TaskTalent getTaskTalent() {
        return taskTalent;
    }

    /**
     * 释放引擎持有的资源
     *
     * <p>两件事：① 通知全部扩展 {@link HarnessExtension#onDetach}（让扩展回收自己在
     * onAttach 里建到<b>引擎之外</b>的资源，如 JVM 单例调度器上的 job、独立线程池、长连接——
     * 引擎本身不知道这些资源的存在）；② 关闭子代理委派的私有执行器。</p>
     *
     * <p>顺序：先扩展后执行器——扩展的清理动作（如停调度）不应与执行器回收并发。</p>
     *
     * <p>执行器线程为守护线程，不调用也不会阻塞 JVM 退出；
     * 需要回收线程时（如测试、长驻进程中的引擎热替换）显式调用。</p>
     *
     * <p>本方法幂等：重复调用只是重复回调 onDetach（各实现应保持幂等）与重复 shutdown 执行器。</p>
     */
    public void shutdown() {
        // 不判 isEnabled()：清理不得被开关阻止（与 removeExtension 同口径）
        for (HarnessExtension extension : options.getExtensions()) {
            try {
                extension.onDetach(this);
            } catch (Throwable e) {
                // 清理期异常不得打断其余扩展与执行器回收
                LOG.warn("HarnessExtension onDetach failed on shutdown: {}", extension.getClass().getName(), e);
            }
        }

        taskTalent.shutdown();
    }

    public CodeTalent getCodeTalent() {
        return codeTalent;
    }

    public MemoryTalent getMemoryTalent() {
        return memoryTalent;
    }

    public GenerateTalent getGenerateTalent() {
        return generateTalent;
    }

    public CodeSearchTalent getCodeSearchTalent() {
        return codeSearchTalent;
    }

    public ClockTalent getClockTalent() {
        return clockTalent;
    }

    public WebsearchTalent getWebsearchTalent() {
        return websearchTalent;
    }

    public WebfetchTalent getWebfetchTalent() {
        return webfetchTalent;
    }

    public McpGatewayTalent getMcpGatewayTalent() {
        return mcpGatewayTalent;
    }

    public OpenApiGatewayTalent getOpenApiGatewayTalent() {
        return openApiGatewayTalent;
    }

    public LspTalent getLspTalent() {
        return lspTalent;
    }

    public AgentSessionProvider getSessionProvider() {
        return options.getSessionProvider();
    }

    public MemorySolutionProvider getMemoryProvider() {
        return options.getMemoryProvider();
    }

    public SkillCatalog getSkillCatalog() {
        return skillTalent.getSkillCatalog();
    }

    public AgentCatalog getAgentCatalog() {
        return agentCatalog;
    }

    // ========== 配置读取（代理到 options） ==========

    public String getHarnessHome() {
        return options.getHarnessHome();
    }

    public String getHarnessSessions() {
        return options.getHarnessSessions();
    }

    public String getHarnessSkills() {
        return options.getHarnessSkills();
    }

    public String getHarnessAgents() {
        return options.getHarnessAgents();
    }

    public String getHarnessCommands() {
        return options.getHarnessCommands();
    }

    public String getHarnessMemory() {
        return options.getHarnessMemory();
    }

    public String getHarnessChannels() {
        return options.getHarnessChannels();
    }

    /**
     * 当前目录
     */
    public String getUserDir() {
        return System.getProperty("user.dir");
    }

    /**
     * 用户主目录
     */
    public String getUserHome() {
        return System.getProperty("user.home");
    }

    public String getUserAgent() {
        return options.getUserAgent();
    }

    public String getWorkspace() {
        return options.getWorkspace();
    }

    /**
     * 会话工作目录（宿主与工具共用的唯一口径）。
     *
     * <p>未显式设置时回退到 {@link #getWorkspace()}。先前宿主各自写
     * {@code attrs().getOrDefault(ATTR_CWD, ".")}：既把引擎内部键当私有状态用，
     * 又硬编码了与工作区默认值不一致的 {@code "."}。宿主一律改用本方法。</p>
     *
     * @since 4.2.2
     */
    public String getSessionCwd(AgentSession session) {
        if (session == null) return getWorkspace();
        Object value = session.attrs().get(ATTR_CWD);
        return value == null ? getWorkspace() : String.valueOf(value);
    }

    /**
     * 设置会话工作目录（null/空 表示清除，回退到工作区默认值）。
     *
     * @since 4.2.2
     */
    public void setSessionCwd(AgentSession session, String cwd) {
        if (session == null) return;
        if (cwd == null || cwd.trim().isEmpty()) {
            session.attrs().remove(ATTR_CWD);
        } else {
            session.attrs().put(ATTR_CWD, cwd);
        }
    }

    /** 会话工作目录仅在未设置时补写（首次进入时定位，不覆盖用户后续选择）。@since 4.2.2 */
    public void setSessionCwdIfAbsent(AgentSession session, String cwd) {
        if (session == null || cwd == null || cwd.trim().isEmpty()) return;
        session.attrs().putIfAbsent(ATTR_CWD, cwd);
    }

    // ========== 会话级选择项（模型 / 子代理）门面 ==========
    //
    // 与 getSessionCwd/setSessionCwd 对称：会话级选择是引擎与宿主共用的口径，
    // 宿主一律走本组方法（存储键已收为 SessionSelections 的私有实现）。
    // 此前同一键出现四种写法（put 值 / put 空串 / remove / 带 isNotEmpty 守卫），
    // 语义漂移正是这类「裸常量当私有状态用」的必然结果。

    /**
     * 会话级模型选择（未设置返回 {@code null}，调用方回退默认模型）。
     *
     * <p>口径：命令声明 → 会话选择 → 默认模型。本方法只读第二层。</p>
     *
     * @since 4.2.3
     */
    @Nullable
    public String getSessionModel(@Nullable AgentSession session) {
        return SessionSelections.modelOf(session);
    }

    /**
     * 设置会话级模型选择；{@code null}/空 表示清除（回退默认模型）。
     *
     * @since 4.2.3
     */
    public void setSessionModel(@Nullable AgentSession session, @Nullable String name) {
        SessionSelections.setModel(session, name);
    }

    /**
     * 会话级子代理选择（未设置返回 {@code null}，表示主代理）。
     *
     * @since 4.2.3
     */
    @Nullable
    public String getSessionAgent(@Nullable AgentSession session) {
        return SessionSelections.agentOf(session);
    }

    /**
     * 设置会话级子代理选择；{@code null}/空 表示清除（回退主代理）。
     *
     * <p><b>空值语义归一</b>：历史宿主对「清除」有三种写法（{@code put("")}、
     * {@code remove(...)}、{@code put(null)}）。本方法把三者统一为「清除」，
     * 读取侧 {@link #getSessionAgent} 也把空串视为未设置，故行为等价。</p>
     *
     * @since 4.2.3
     */
    public void setSessionAgent(@Nullable AgentSession session, @Nullable String name) {
        SessionSelections.setAgent(session, name);
    }

    public String getSystemPrompt() {
        return options.getSystemPrompt();
    }

    public int getMaxTurns() {
        return options.getMaxTurns();
    }

    public boolean isAutoRethink() {
        return options.isAutoRethink();
    }

    public int getSessionWindowSize() {
        return options.getSessionWindowSize();
    }

    public int getCompressionMaxMessages() {
        return options.getCompressionMaxMessages();
    }

    public double getCompressionMaxContextRatio() {
        return options.getCompressionMaxContextRatio();
    }

    public long getCompressionDefaultContextLength() {
        return options.getCompressionDefaultContextLength();
    }

    /**
     * @deprecated 4.0.4
     */
    @Deprecated
    public String getCompressionModel() {
        return options.getCompressionModel();
    }

    public boolean isMemoryEnabled() {
        return options.isMemoryEnabled();
    }

    public boolean isSandboxEnabled() {
        return options.isSandboxEnabled();
    }

    public boolean isHitlEnabled() {
        return options.isHitlEnabled();
    }

    public boolean isSubagentEnabled() {
        return options.isSubagentEnabled();
    }

    public boolean isBashAsyncEnabled() {
        return options.isBashAsyncEnabled();
    }

    /**
     * 命令前缀（默认 "/"）
     */
    public String getCommandPrefix() {
        return options.getCommandPrefix();
    }

    /**
     * 子代理提及前缀（默认 "@"）
     */
    public String getAgentPrefix() {
        return options.getAgentPrefix();
    }

    public int getApiRetries() {
        return options.getApiRetries();
    }

    public int getMcpRetries() {
        return options.getMcpRetries();
    }

    public int getModelRetries() {
        return options.getModelRetries();
    }

    public Collection<String> getTools() {
        return Collections.unmodifiableCollection(options.getTools());
    }

    public Collection<String> getDisallowedTools() {
        return Collections.unmodifiableCollection(options.getDisallowedTools());
    }

    public Collection<HarnessExtension> getExtensions() {
        return Collections.unmodifiableCollection(options.getExtensions());
    }

    /**
     * 按类型取已挂载的扩展或扩展提供的能力（宿主与插件取用扩展门面的唯一方式）
     *
     * <p>在 {@link #getExtensions()} 之上补一个类型化取用入口：扩展在 {@code addExtension} 之后
     * 原本只能通过遍历 + {@code instanceof} 找回，宿主每写一个扩展就要写一遍这段样板，
     * 且取不到时无统一口径可依。</p>
     *
     * <p><b>也用于能力查找</b>：{@code type} 可以是核心定义的能力接口（如
     * {@link InputBacklog}），返回首个实现了它的扩展。插件之间应按能力接口查找，
     * 而不是按对方的实现类查找（否则两个插件模块就耦合在一起了）。</p>
     *
     * <pre>{@code
     * engine.getExtension(QueueExtension.class)
     *       .ifPresent(queue -> queue.enqueue(session, "接着改文档"));
     *
     * InputBacklog backlog = engine.getExtension(InputBacklog.class).orElse(null);
     * }</pre>
     *
     * <p>多个同类实例时返回<b>首个</b>（与 {@code removeExtension} 只移除首个同口径）；
     * 未挂载或类型不匹配时返回 {@link Optional#empty()}（不抛异常——「没有这个能力」是正常状态，
     * 宿主据此决定是否降级，不该被迫用异常做分支）。</p>
     *
     * @param type 扩展类型或能力接口
     * @return 已挂载的扩展实例；未挂载时为 {@link Optional#empty()}
     * @since 4.2.0
     */
    public <T> Optional<T> getExtension(Class<T> type) {
        if (type == null) {
            return Optional.empty();
        }

        for (HarnessExtension extension : options.getExtensions()) {
            if (type.isInstance(extension)) {
                return Optional.of(type.cast(extension));
            }
        }

        return Optional.empty();
    }

    public Collection<Mount> getMounts() {
        return options.getMountManager().getMounts();
    }

    public Mount getMount(String alias) {
        return options.getMountManager().getMount(alias);
    }

    public SkillDescriptor getSkill(String name) {
        return skillTalent.getSkillCatalog().getDescriptor(name);
    }

    public Collection<SkillDescriptor> getSkills() {
        return skillTalent.getSkillCatalog().getDescriptors();
    }

    public Collection<SkillDescriptor> getSkillsByMount(String alias) {
        String mountAlias = alias.startsWith("@") ? alias : "@" + alias;
        return getSkills().stream().filter(s -> s.getId().equals(mountAlias)
                        || s.getId().startsWith(mountAlias + "/"))
                .collect(Collectors.toList());
    }

    /**
     * 获取被禁用的技能集合（按 aliasPath）
     */
    public Collection<String> getDisallowedSkills() {
        return Collections.unmodifiableCollection(options.getMountManager().getDisallowSkills());
    }

    /**
     * 判断技能是否被禁用（按 aliasPath）
     */
    public boolean isSkillDisallowed(String aliasPath) {
        return options.getMountManager().getDisallowSkills().contains(aliasPath);
    }

    /**
     * 动态禁用一个技能（按 aliasPath）
     */
    public void disallowSkill(String aliasPath) {
        options.getMountManager().disallowSkill(aliasPath);
    }

    /**
     * 动态允许一个技能（按 aliasPath）
     */
    public void allowSkill(String aliasPath) {
        options.getMountManager().allowSkill(aliasPath);
    }

    /**
     * 批量重置被禁用的技能集合（按 aliasPath）
     */
    public void disallowSkillReset(Collection<String> disallowedSkills) {
        options.getMountManager().setDisallowSkills(disallowedSkills);
    }

    public Collection<AgentDescriptor> getAgents() {
        return agentCatalog.getAgents();
    }

    public Collection<AgentDescriptor> getAgentsByMount(String alias) {
        return agentCatalog.getAgentsByMount(alias);
    }

    public Map<String, McpServerParameters> getMcpServers() {
        return Collections.unmodifiableMap(options.getMcpServers());
    }

    public Map<String, ApiSource> getApiServers() {
        return Collections.unmodifiableMap(options.getApiServers());
    }

    public Map<String, LspServerParameters> getLspServers() {
        return Collections.unmodifiableMap(options.getLspServers());
    }

    public Collection<ChatConfig> getModels() {
        return Collections.unmodifiableCollection(options.getModels().values());
    }

    public ChatConfig getModelOrNil(String name) {
        return options.getModelOrNil(name);
    }

    public ChatModel getModelOrNilInstance(String name) {
        ChatConfig tmp = options.getModelOrNil(name);

        if (tmp == null) {
            return null;
        } else {
            return tmp.toChatModel();
        }
    }

    public ChatConfig getModelOrDef(String name) {
        return options.getModelOrDef(name);
    }

    public ChatModel getModelOrDefInstance(String name) {
        ChatConfig tmp = options.getModelOrDef(name);

        if (tmp == null) {
            return null;
        } else {
            return tmp.toChatModel();
        }
    }

    public String getDefaultModel() {
        return options.getDefaultModel();
    }

    // ========== 运行时动态修改 ==========

    public void setMaxTurns(Integer maxTurns) {
        if (maxTurns != null) {
            options.setMaxTurns(maxTurns);
        }
    }

    public void setModelRetries(Integer modelRetries) {
        if (modelRetries != null) {
            options.setModelRetries(modelRetries);
        }
    }

    public void setMcpRetries(Integer mcpRetries) {
        if (mcpRetries != null) {
            options.setMcpRetries(mcpRetries);
            mcpGatewayTalent.retryConfig(mcpRetries);
        }
    }

    public void setApiRetries(Integer apiRetries) {
        if (apiRetries != null) {
            options.setApiRetries(apiRetries);
            openApiGatewayTalent.retryConfig(apiRetries);
        }
    }

    /**
     * 开启/关闭记忆能力
     * <p>
     * 未注入 {@code memoryProvider} 时没有 memory talent 可开关（构建期显式置 null），
     * 仅写入配置值，不抛异常 —— 与相邻的 setMemoryRelevanceCount 等保持一致的空值守卫。
     */
    public void setMemoryEnabled(Boolean memoryEnabled) {
        options.setMemoryEnabled(memoryEnabled);
        if (memoryTalent != null) {
            memoryTalent.setEnabled(memoryEnabled);
        }
    }

    public void setMemoryRelevanceCount(int n) {
        options.setMemoryRelevanceCount(n);
        if (memoryTalent != null) {
            memoryTalent.relevanceCount(n);
        }
    }

    public void setMemoryPriorityCount(int n) {
        options.setMemoryPriorityCount(n);
        if (memoryTalent != null) {
            memoryTalent.priorityCount(n);
        }
    }

    public void setMemorySummaryLength(int n) {
        options.setMemorySummaryLength(n);
        if (memoryTalent != null) {
            memoryTalent.summaryLength(n);
        }
    }

    public void setSandboxEnabled(Boolean sandboxEnabled) {
        options.setSandboxEnabled(sandboxEnabled);
        terminalTalent.setSandboxEnabled(sandboxEnabled);
    }

    public void setSandboxAllowUserHome(Boolean sandboxAllowUserHome) {
        options.setSandboxAllowUserHome(sandboxAllowUserHome);
        terminalTalent.setSandboxAllowUserHome(sandboxAllowUserHome);
    }

    public boolean isSandboxSystemRestrict() {
        return options.isSandboxSystemRestrict();
    }

    public void setSandboxSystemRestrict(Boolean sandboxSystemRestrict) {
        options.setSandboxSystemRestrict(sandboxSystemRestrict);
        terminalTalent.setSandboxSystemRestrict(sandboxSystemRestrict);
    }

    public void setHitlEnabled(Boolean hitlEnabled) {
        options.setHitlEnabled(hitlEnabled);

        if (options.getHitlInterceptor() != null) {
            options.getHitlInterceptor().setEnabled(hitlEnabled);
        }
    }

    public void setSubagentEnabled(Boolean subagentEnabled) {
        options.setSubagentEnabled(subagentEnabled);

        generateTalent.setEnabled(options.isSubagentEnabled());
        taskTalent.setEnabled(options.isSubagentEnabled());
    }

    /**
     * 子代理任务默认并发上限（模型未配置 {@code AiConfig#getConcurrency()} 时生效）
     *
     * <p>恒为有限正整数，不存在「不限制」；归一规则见
     * {@link TaskTalent#normalizeDefaultConcurrency(int)}。</p>
     */
    public int getSubagentDefaultConcurrency() {
        return options.getSubagentDefaultConcurrency();
    }

    /**
     * 设置子代理任务默认并发上限
     *
     * <p>取值：{@code 0} = 未设置（取 {@link TaskTalent#DEFAULT_CONCURRENCY}）；
     * 上界 {@link TaskTalent#MAX_CONCURRENCY}（超出按上界处理）。
     * <b>不存在「0 = 不限制」</b>——无限并发会让线程数、上游 QPS、内存退化为只受任务数约束，
     * 且信号量缺席会让模型级 {@code concurrency} 形同虚设；需要更高并发请显式配置模型级
     * {@code AiConfig#concurrency}。</p>
     *
     * @param subagentDefaultConcurrency 默认并发上限（0 = 未设置；负数报错）
     */
    public void setSubagentDefaultConcurrency(Integer subagentDefaultConcurrency) {
        if (subagentDefaultConcurrency != null) {
            options.setSubagentDefaultConcurrency(subagentDefaultConcurrency);
            // 推送已归一的生效值：归一（0→默认、超上界→上界）只在 options 那一层做一次，避免重复告警
            taskTalent.setDefaultConcurrency(options.getSubagentDefaultConcurrency());
        }
    }

    public void setBashAsyncEnabled(Boolean bashAsyncEnabled) {
        options.setBashAsyncEnabled(bashAsyncEnabled);

        terminalTalent.setBashAsyncEnabled(bashAsyncEnabled);
    }

    public void setSessionWindowSize(Integer sessionWindowSize) {
        options.setSessionWindowSize(sessionWindowSize);
    }

    public void setCompressionThreshold(Integer maxMessages, Double maxContextRatio) {
        if (maxMessages != null) {
            options.setCompressionMaxMessages(maxMessages);
            options.getCompressionInterceptor().setMaxMessages(maxMessages);
        }

        if (maxContextRatio != null) {
            options.setCompressionMaxContextRatio(maxContextRatio);
            options.getCompressionInterceptor().setMaxContextLengthRatio(maxContextRatio);
        }
    }

    public void setCompressionDefaultContextLength(Long defaultContextLength) {
        if (defaultContextLength != null) {
            options.setCompressionDefaultContextLength(defaultContextLength);
            options.getCompressionInterceptor().setDefaultContextLength(defaultContextLength);
        }
    }


    public void setSystemPrompt(String systemPrompt) {
        if (Assert.isNotEmpty(systemPrompt)) {
            options.setSystemPrompt(systemPrompt);
        }
    }

    public void setCacheControl(CacheControl cacheControl) {
        if (cacheControl != null) {
            options.setCacheControl(cacheControl);
        }
    }

    // ========== 引擎级 http 定制（运行时动态切换） ==========

    /**
     * 动态切换引擎级代理（host/port 形式）。O(1) 生效，无需重建 Agent/模型。
     *
     * @since 4.0.5
     */
    public void setHttpProxy(String host, int port) {
        options.setHttpProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
    }

    /**
     * 动态切换引擎级代理（java.net.Proxy 形式）；传 null 恢复直连。
     *
     * @since 4.0.5
     */
    public void setHttpProxy(Proxy proxy) {
        options.setHttpProxy(proxy);
    }

    public Proxy getHttpProxy() {
        return options.getHttpProxy();
    }

    /**
     * 动态设置引擎级 http 定制钩子（每次 LLM 请求创建 HttpUtils 后执行）。
     *
     * @since 4.0.5
     */
    public void addHttpCustomize(Consumer<HttpUtils> httpCustomize) {
        options.addHttpCustomize(httpCustomize);
    }

    public void setHttpCustomize(Consumer<HttpUtils> httpCustomize) {
        options.setHttpCustomize(httpCustomize);
    }

    public Consumer<HttpUtils> getHttpCustomize() {
        return options.getHttpCustomize();
    }

    // ========== 动态模型管理 ==========

    public void setDefaultModel(String defaultModel) {
        String oldDefault = options.getDefaultModel();
        options.setDefaultModel(defaultModel);

        if (mainAgent != null
                && !defaultModel.equals(oldDefault)
                && (oldDefault == null || oldDefault.equals(mainAgent.getModel().getNameOrModel()))) {
            refreshMainAgent();
        }
    }

    public void addModel(ChatConfig config) {
        options.addModel(config);

        if (mainAgent != null && mainAgent.getModel().getNameOrModel().equals(config.getNameOrModel())) {
            refreshMainAgent();
        }
    }

    public void removeModel(String name) {
        options.removeModel(name);

        if (mainAgent != null && mainAgent.getModel().getNameOrModel().equals(name)) {
            refreshMainAgent();
        }
    }

    public boolean hasModel(String name) {
        return options.hasModel(name);
    }

    /**
     * 动态授权一个工具（允许使用）
     */
    public void allowTool(String toolName) {
        if (Assert.isEmpty(toolName)) {
            return;
        }

        options.getDisallowedTools().remove(toolName);
        options.getTools().add(toolName);

        // 权限变更需要重建 Agent 以重新生成工具集
        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }

    public void allowToolReset(Collection<String> tools) {
        options.getTools().clear();
        if (tools != null) {
            options.getTools().addAll(tools);
        }

        // 权限变更需要重建 Agent 以重新生成工具集
        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }

    /**
     * 一次性重置工具白名单与黑名单，并只重建一次主 Agent。
     * <p>避免连续调用 {@link #allowToolReset} + {@link #disallowToolReset} 导致双倍重建抖动。</p>
     */
    public void toolPermissionReset(Collection<String> tools, Collection<String> disallowedTools) {
        options.getTools().clear();
        if (tools != null) {
            options.getTools().addAll(tools);
        }

        options.getDisallowedTools().clear();
        if (disallowedTools != null) {
            options.getDisallowedTools().addAll(disallowedTools);
        }

        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }

    /**
     * 动态撤销一个工具的权限（禁用）
     */
    public void disallowTool(String toolName) {
        if (Assert.isEmpty(toolName)) {
            return;
        }

        options.getTools().remove(toolName);
        options.getDisallowedTools().add(toolName);

        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }

    public void disallowToolReset(Collection<String> disallowedTools) {
        options.getDisallowedTools().clear();
        if (disallowedTools != null) {
            options.getDisallowedTools().addAll(disallowedTools);
        }

        // 权限变更需要重建 Agent 以重新生成工具集
        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }


    public void addMount(Mount mount) {
        // 内置挂载不可被同名覆盖
        if (mount != null && BUILTIN_AGENT_MOUNT.equals(normalizeMountAlias(mount.getAlias()))) {
            throw new IllegalArgumentException("Built-in mount cannot be replaced: " + BUILTIN_AGENT_MOUNT);
        }
        // 同别名替换时，清除旧挂载关联的运行时定义。
        if (mount != null && options.getMountManager().hasMount(mount.getAlias())) {
            String key = mount.getAlias().startsWith("@") ? mount.getAlias() : "@" + mount.getAlias();
            agentManager.removeByMountAlias(key);
        }

        options.getMountManager().register(mount);
    }

    public void removeMount(String alias) {
        if (BUILTIN_AGENT_MOUNT.equals(normalizeMountAlias(alias))) {
            throw new IllegalArgumentException("Built-in mount cannot be removed: " + BUILTIN_AGENT_MOUNT);
        }
        String key = alias.startsWith("@") ? alias : "@" + alias;
        agentManager.removeByMountAlias(key);

        options.getMountManager().remove(alias);
    }

    private static String normalizeMountAlias(String alias) {
        if (alias == null) return null;
        return alias.startsWith("@") ? alias : "@" + alias;
    }

    public boolean hasMount(String alias) {
        return options.getMountManager().hasMount(alias);
    }

    public void refreshMount(@Nullable String alias) {
        if (alias != null) {
            String key = alias.startsWith("@") ? alias : "@" + alias;
            agentManager.removeByMountAlias(key);
        } else {
            agentManager.clearCustomAgents();
        }

        if (alias == null) {
            skillCatalog.refresh();
            agentCatalog.refresh();
        } else {
            skillCatalog.refreshByMount(alias);
            agentCatalog.refreshByMount(alias);
        }
    }


    /**
     * 动态添加 API 源
     */
    public void addApiServer(ApiSource apiSource) {
        openApiGatewayTalent.addApi(apiSource);
        options.getApiServers().put(apiSource.getDocUrl(), apiSource);
    }

    /**
     * 获取指定 docUrl 的 ApiSourceClient
     */
    public ApiSourceClient getApiServer(String docUrl) {
        return openApiGatewayTalent.getApiSource(docUrl);
    }

    /**
     * 刷新指定 API 源（权限变更后）
     */
    public void refreshApiServer(String docUrl) {
        openApiGatewayTalent.refreshApi(docUrl);
    }

    /**
     * 动态移除 API 源
     */
    public void removeApiServer(String docUrl) {
        openApiGatewayTalent.removeApi(docUrl);
        options.getApiServers().remove(docUrl);
    }

    /**
     * 动态添加 MCP 服务
     */
    public void addMcpServer(String name, McpServerParameters mcpServer) {
        mcpGatewayTalent.addMcpServer(name, mcpServer);
        options.getMcpServers().put(name, mcpServer);
    }

    public McpClientProvider getMcpServer(String name) {
        return mcpGatewayTalent.getMcpServer(name);
    }

    public void refreshMcpServer(String name) {
        mcpGatewayTalent.refreshMcpServer(name);
    }

    /**
     * 动态移除 MCP 服务（并关闭连接）
     */
    public void removeMcpServer(String name) {
        mcpGatewayTalent.removeMcpServer(name);
        options.getMcpServers().remove(name);
    }

    /**
     * 动态添加一个 LSP 服务器
     */
    public void addLspServer(String name, LspServerParameters lspServer) {
        if (lspServer == null) {
            return;
        }

        // 将配置同步到 options 中，以便后续快照使用
        options.getLspServers().put(name, lspServer);
        // 让 LspManager 运行时加载该服务器
        lspManager.registerServer(name, lspServer);

        // LSP 工具的增减会影响 Agent 的可用工具集，需重建 Agent
        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }

    /**
     * 动态移除一个 LSP 服务器
     */
    public void removeLspServer(String name) {
        if (name == null) {
            return;
        }

        options.getLspServers().remove(name);
        lspManager.unregisterServer(name);

        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }


    /**
     * 动态开关一个扩展
     * <p>
     * 扩展的开关由两部分组成：
     * <ul>
     *   <li>{@code extension.setEnabled(...)}：扩展自己把状态传播给子组件（Talent / 拦截器 / 命令）；</li>
     *   <li>重建主代理：拦截器与才能是装配期下发的，必须在重建后生效（命令、拦截器内部判断即时生效）。</li>
     * </ul>
     * 未加入本引擎的扩展会先被加入（与 {@link #addExtension} 一致），便于「先建后开」的宿主。
     *
     * @param extension 目标扩展
     * @param enabled   true 开 / false 关 / null 不改变
     */
    public void setExtensionEnabled(HarnessExtension extension, Boolean enabled) {
        if (extension == null) {
            return;
        }

        extension.setEnabled(enabled);

        if (options.getExtensions().contains(extension) == false) {
            addExtension(extension);
            return;
        }

        //开关影响才能/拦截器装配，必须重建主代理（命令与拦截器的运行期判定本身即时生效）
        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }

    /**
     * 按类型幂等挂载扩展：引擎上已有同类型（{@code extension.getClass()} 的实例）时不再添加。
     *
     * <p>{@link #addExtension} 不查重，重复添加会二次回调 {@code onAttach}（重复注册、重复建调度器）。
     * 组合根装配插件时用本方法，重复装配拿到的是<b>已挂载</b>的那个实例：</p>
     *
     * <pre>{@code
     * engine.addExtensionIfAbsent(new SteerExtension());
     * engine.addExtensionIfAbsent(new QueueExtension());
     * GoalExtension goal = engine.addExtensionIfAbsent(GoalExtension.builder(id).build());
     * }</pre>
     *
     * @param extension 待挂载的扩展（null 时返回 null）
     * @return 实际生效的实例（已有则为已挂载者，否则为入参）
     * @since 4.2.0
     */
    @SuppressWarnings("unchecked")
    public <T extends HarnessExtension> T addExtensionIfAbsent(T extension) {
        if (extension == null) {
            return null;
        }

        agentLock.lock();
        try {
            Optional<? extends HarnessExtension> mounted = getExtension(extension.getClass());
            if (mounted.isPresent()) {
                return (T) mounted.get();
            }
            addExtension(extension);
            return extension;
        } finally {
            agentLock.unlock();
        }
    }

    public void addExtension(HarnessExtension extension) {
        if (extension == null) {
            return;
        }
        options.getExtensions().add(extension);

        //与构造器对称：动态添加的扩展也要触发初始化（此刻引擎已装配完毕，任意引擎方法都安全）。
        //嵌套调用安全：agentLock 可重入，且 mainAgent 尚未构建时重建分支会跳过。
        extension.onAttach(this);

        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }

    /**
     * 动态移除一个扩展
     */
    public void removeExtension(HarnessExtension extension) {
        if (extension == null) {
            return;
        }

        options.getExtensions().remove(extension);
        extension.onDetach(this);

        // 重建 mainAgent 以应用变更，使用 agentLock 保证线程安全
        agentLock.lock();
        try {
            if (this.mainAgent != null) {
                this.mainAgent = createMainAgent();
            }
        } finally {
            agentLock.unlock();
        }
    }

    private void initHitlDefault() {
        BashToolStrategy bashStrategy = new BashToolStrategy()
                .permissionContextSupplier(options::getPermissionContext);

        options.setHitlInterceptor(new HITLInterceptor()
                .onTool("bash", bashStrategy)
                .onApproved((toolName, args) -> {
                    // 记住本次审批：提取命令/路径，注入放行规则，后续相同操作免弹窗
                    String pattern = null;
                    if (args != null) {
                        // 优先提取 command（bash 工具）
                        Object cmd = args.get("command");
                        if (cmd instanceof String && !((String) cmd).trim().isEmpty()) {
                            pattern = (String) cmd;
                        }

                        // 其次提取 file_path（write/edit 工具）
                        if (pattern == null) {
                            Object path = args.get("file_path");
                            if (path instanceof String && !((String) path).trim().isEmpty()) {
                                pattern = (String) path;
                            }
                        }
                    }

                    if (pattern != null) {
                        // 带模式的细粒度放行：后续相同 pattern 自动放行
                        options.setPermissionContext(
                                options.getPermissionContext().addRule(
                                        PermissionRule.allow(toolName, pattern)));
                    } else {
                        // 无精确模式时退化为工具级放行（后续该工具的所有操作不再弹窗）
                        options.setPermissionContext(
                                options.getPermissionContext().addRule(
                                        PermissionRule.allow(toolName)));
                    }
                }));
    }

    private HarnessEngine(HarnessOptions options) {
        this.options = options;

        //上下文压缩拦截器默认处理
        if (options.getCompressionInterceptor() == null) {
            CompressionStrategy strategy = new CompositeCompressionStrategy()
                    .addStrategy(new KeyInfoExtractionStrategy())      // 提取干货（去水）
                    .addStrategy(new HierarchicalCompressionStrategy()); // 滚动更新摘要

            options.setCompressionInterceptor(new ContextCompressionInterceptor(
                    options.getCompressionMaxMessages(),
                    options.getCompressionMaxContextRatio(),
                    options.getModelRetries(),
                    strategy));
        }

        //停止循环拦截器默认处理
        if (options.getStopLoopInterceptor() == null) {
            options.setStopLoopInterceptor(new StopLoopInterceptor(3, 8));
        }

        //人工介入拉截器默认处理
        if (options.getHitlInterceptor() == null) {
            initHitlDefault();
        }


        MountManager mountManager = options.getMountManager();
        mountManager.register(Mount.builder()
                .alias(BUILTIN_AGENT_MOUNT)
                .description("Harness built-in agents")
                .type(MountType.AGENTS)
                .primary(true)
                .enabled(true)
                .writeable(false)
                // 内置挂载不在上层展示（隐藏类路径实现细节）；不影响解析与使用
                .visible(false)
                .source(ClasspathMountSource.of(HarnessEngine.class.getClassLoader(), BUILTIN_AGENT_PATH))
                .build());

        this.skillCatalog = mountManager.getSkillCatalog();
        this.agentCatalog = mountManager.getAgentCatalog();

        this.todoTalent = new TodoTalent(options.getHarnessSessions());
        this.codeTalent = new CodeTalent(options.getWorkspace(), options.getHarnessHome());
        this.taskTalent = new TaskTalent(this);
        // 将配置面的默认并发推送到执行者（yaml / Builder / setter 三种入口都只写 options）
        this.taskTalent.setDefaultConcurrency(options.getSubagentDefaultConcurrency());
        this.generateTalent = new GenerateTalent(this);

        this.clockTalent = new ClockTalent();

        this.codeSearchTalent = new CodeSearchTalent().retryConfig(options.getMcpRetries());
        this.websearchTalent = new WebsearchTalent().retryConfig(options.getMcpRetries());
        this.webfetchTalent = new WebfetchTalent().retryConfig(options.getApiRetries());

        //lsp
        this.lspManager = new LspManager(options.getWorkspace());
        if (Assert.isNotEmpty(options.getLspServers())) {
            for (Map.Entry<String, LspServerParameters> entry : options.getLspServers().entrySet()) {
                lspManager.registerServer(entry.getKey(), entry.getValue());
            }
        }
        this.lspTalent = new LspTalent(lspManager, options.getWorkspace());
        this.lspManager.setDiagnosticsCallback(lspTalent::updateDiagnostics);
        if (options.getMountManager() != null) {
            //lsp 工具的路径入参要与 read/grep 一致地支持挂载点逻辑路径，否则同一个 @xx/a.java
            //能 read 却做不了导航，只报「文件不存在」
            this.lspTalent.setPathResolver(options.getMountManager()::resolve);
        }

        if (options.getMemoryProvider() != null) {
            this.memoryTalent = new MemoryTalent(options.getMemoryProvider())
                    .sessionIsolation(false)
                    .relevanceCount(options.getMemoryRelevanceCount())
                    .priorityCount(options.getMemoryPriorityCount())
                    .summaryLength(options.getMemorySummaryLength());
            this.memoryTalent.setEnabled(options.isMemoryEnabled());
        } else {
            this.memoryTalent = null;
        }

        openApiGatewayTalent = new OpenApiGatewayTalent().retryConfig(options.getApiRetries());
        if (Assert.isNotEmpty(options.getApiServers())) {
            for (Map.Entry<String, ApiSource> entry : options.getApiServers().entrySet()) {
                openApiGatewayTalent.addApi(entry.getValue());
            }
        }

        mcpGatewayTalent = new McpGatewayTalent().retryConfig(options.getMcpRetries());

        if (Assert.isNotEmpty(options.getMcpServers())) {
            for (Map.Entry<String, McpServerParameters> entry : options.getMcpServers().entrySet()) {
                if (entry.getValue().isEnabled()) {
                    mcpGatewayTalent.addMcpServer(entry.getKey(), entry.getValue());
                }
            }
        }

        terminalTalent = new TerminalTalent(options.getMountManager());
        skillTalent = new SkillTalent(this.skillCatalog);

        agentManager = new AgentManager(this.agentCatalog);

        terminalTalent.setBashAsyncEnabled(options.isBashAsyncEnabled());
        terminalTalent.setSandboxEnabled(options.isSandboxEnabled());
        terminalTalent.setSandboxAllowUserHome(options.isSandboxAllowUserHome());
        terminalTalent.setSandboxSystemRestrict(options.isSandboxSystemRestrict());

        //LSP 与文件工具接线：诊断靠 write/edit 自动注入（不依赖模型主动调工具），read 后异步预热。
        //钩子内部会在总开关关闭 / 文件类型无匹配服务器 / 服务器已知启动失败时零成本短路。
        terminalTalent.setFileDiagnosticsHook(lspTalent::reportFileDiagnostics);
        terminalTalent.setFileWarmupHook(lspTalent::warmupFile);

        //mainAgent = createMainAgent(); //改为懒加载
    }

    protected ReActAgent createMainAgent() {
        AgentDefinition agentDefinition = AgentDefinition.builder()
                .primary(true) // 主代理
                .name(AgentDefinition.AGENT_MAIN) // 名字
                .toolsAdd(options.getTools()) // 工具权限
                .systemPrompt(options.getSystemPrompt())// 系统提示词
                .build();

        ReActAgent.Builder agentBuilder = getAgentBuilder(agentDefinition, null);

        return agentBuilder.build();
    }


    public AgentSession getSession(String instanceId) {
        return options.getSessionProvider().getSession(instanceId);
    }

    /**
     * @deprecated 4.2
     * @since {@link #getAgentBuilder(AgentDefinition)}
     */
    @Deprecated
    public ReActAgent.Builder createSubagent(AgentDefinition definition) {
        return getAgentBuilder(definition);
    }

    /**
     * @deprecated 4.2
     * @since {@link #getAgentBuilder(AgentDefinition)}
     */
    @Deprecated
    public ReActAgent.Builder createSubagent(AgentDefinition definition, String sessionModel) {
        return getAgentBuilder(definition, sessionModel);
    }



    public ChatModel getMainModel() {
        return getMainAgent().getModel();
    }

    public ReActAgent getMainAgent() {
        ReActAgent agent = this.mainAgent; // 引入局部变量，仅执行一次 volatile read
        if (agent == null) {
            agentLock.lock();
            try {
                if (this.mainAgent == null) {
                    this.mainAgent = createMainAgent();
                }
                agent = this.mainAgent;
            } finally {
                agentLock.unlock();
            }
        }

        return agent;
    }

    /**
     * 刷新主代理
     */
    public void refreshMainAgent() {
        agentLock.lock();
        try {
            this.mainAgent = null;
        } finally {
            agentLock.unlock();
        }
    }

    /**
     * @deprecated 4.0.4 {@link #getModelOrDefInstance(String)}
     */
    @Deprecated
    public ChatModel getModelOrMain(String modelName) {
        return getModelOrDefInstance(modelName);
    }

    public ReActAgent getAgentOrMain(String agentName) {
        if (Assert.isEmpty(agentName)) {
            return getMainAgent();
        } else if (agentManager.hasAgent(agentName)) {
            AgentDefinition definition = agentManager.getAgent(agentName);
            return getAgentBuilder(definition, null).build();
        } else {
            return getMainAgent();
        }
    }

    public ReActAgent.Builder getAgentBuilder(AgentDefinition definition) {
        return getAgentBuilder(definition, null);
    }

    public ReActAgent.Builder getAgentBuilder(AgentDefinition definition, @Nullable String sessionModel) {
        return AgentFactory.create(this, definition, sessionModel);
    }

    public ReActAgent.Builder getAgentBuilderOrNil(String agentName, String sessionModel) {
        AgentDefinition definition = agentManager.getAgentOrNil(agentName);
        if (definition == null) {
            return null;
        }

        return getAgentBuilder(definition, sessionModel);
    }

    public ReActRequest prompt(Prompt prompt) {
        return promptDo(prompt, null);
    }

    public ReActRequest prompt(Prompt prompt, ReActAgent targetAgent) {
        return promptDo(prompt, targetAgent);
    }


    public ReActRequest prompt(String prompt) {
        return promptDo(Prompt.of(prompt), null);
    }


    public ReActRequest prompt(String prompt, ReActAgent targetAgent) {
        return promptDo(Prompt.of(prompt), targetAgent);
    }


    /**
     * @deprecated 4.2.0 {@link #resume()}
     */
    @Deprecated
    public ReActRequest prompt() {
        return promptDo(null, null);
    }

    /**
     * @deprecated 4.2.0 {@link #resume(ReActAgent)}
     */
    @Deprecated
    public ReActRequest prompt(ReActAgent targetAgent) {
        return promptDo(null, targetAgent);
    }

    /**
     * 恢复执行（最近一个工作记忆）
     *
     * @since 4.2.0
     */
    public ReActRequest resume() {
        return promptDo(null, null);
    }

    /**
     * 恢复执行（最近一个工作记忆）
     *
     * @since 4.2.0
     */
    public ReActRequest resume(ReActAgent targetAgent) {
        return promptDo(null, targetAgent);
    }



    private ReActRequest promptDo(Prompt prompt, ReActAgent targetAgent) {
        if (targetAgent == null) {
            targetAgent = getMainAgent();
        }

        final String agentName = targetAgent.name();

        return targetAgent.prompt(prompt)
                .options(o -> {
                    o.retryConfig(options.getModelRetries());
                    o.maxTurns(options.getMaxTurns());
                    o.sessionWindowSize(options.getSessionWindowSize());

                    for (HarnessExtension extension : getExtensions()) {
                        if (extension.isEnabled()) {
                            extension.onRequestAssemble(this, false, agentName, o);
                        }
                    }
                });
    }


    /**
     * @param selectedModel 选中的模型名
     * @param selectedAgent 选中的智能体名
     * @param selectedSkills 选中的技能名
     * @param selectedMcps  选中的连接名
     * @since 4.2.0
     */
    private ReActRequest promptDo(@Nullable AgentSession session, @Nullable Prompt prompt,
                                  @Nullable String selectedModel,
                                  @Nullable String selectedAgent,
                                  @Nullable List<String> selectedSkills,
                                  @Nullable List<String> selectedMcps) {
        ReActRequest request = promptDo(prompt, getAgentOrMain(selectedAgent));

        if (session != null) {
            request.session(session);
        }


        if (Assert.isNotEmpty(selectedSkills)) {
            request.options(o -> o.toolContextPut(SkillTalent.CTX_SKILLS_SELECTED, selectedSkills));
        }

        if (Assert.isNotEmpty(selectedMcps)) {
            request.options(o -> o.toolContextPut(McpGatewayTalent.CTX_MCPS_SELECTED, selectedMcps));
        }


        // request 级 cwd：会话工作目录是引擎已知的事实，装配时统一注入，宿主不必各写一遍。
        // 四个入口曾各自 o.toolContextPut(ATTR_CWD, ...)（ACP 传的是协议请求级 cwd，
        // 需按请求覆盖，属于真实差异，故它保留自己的那次写入）。
        // options() 是 andThen 叠加语义，宿主后写的值覆盖这里的缺省值。
        if (session != null) {
            final String sessionCwdForTurn = getSessionCwd(session);
            if (Assert.isNotEmpty(sessionCwdForTurn)) {
                request.options(o -> o.toolContextPut(ATTR_CWD, sessionCwdForTurn));
            }
        }

        final String realModelName = resolveSessionModelName(session, selectedModel);
        if (realModelName != null) {
            final ChatModel chatModel = getModelOrDefInstance(realModelName);
            if (chatModel != null) {
                request.options(o -> o.chatModel(chatModel));
            }
        }

        return request;
    }

    /**
     * 处理一次输入（文本版）
     *
     * @see #handle(HarnessInput)
     */
    public InputOutcome handle(AgentSession session, String text) {
        return handle(HarnessInput.of(session, text));
    }


    /**
     * 处理一次完整输入（唯一入口）
     * <p>
     * 一次调用完成五件事：命令识别与执行、HITL 决策应用、附件装配 + 子代理解析、
     * Prompt 构建、请求装配。宿主只需关心「回执怎么渲染」与「流怎么消费」。
     *
     * <p>HITL 决策与续流可合并为一次调用：{@code .hitl(action, callId).resume(true)} 会在写入决策后
     * 尝试续流。是否真的续流由引擎自检后决定，宿主只 switch {@link InputOutcome.Kind}：</p>
     * <ul>
     * <li>{@link InputOutcome.Kind#TASK} —— 已续流（决策写入，且本批已齐备）；</li>
     * <li>{@link InputOutcome.Kind#HITL} —— 未续流。再用 {@link InputOutcome#isHitlApplied()} 与
     * {@link InputOutcome#getPendingHitlCount()} 区分：决策未命中（过期卡）/ 本批尚有未决卡 / 宿主未传 resume。</li>
     * </ul>
     * <p>不传 {@code resume} 则只写决策，无论本批是否齐备都返回 {@link InputOutcome.Kind#HITL}。
     * 决策动作是 {@link org.noear.solon.ai.agent.react.intercept.HITLAction} 枚举，
     * 线上字符串的解析由宿主完成（{@code HITLAction.of(action)}）。</p>
     *
     * <pre>{@code
     * InputOutcome outcome = engine.handle(HarnessInput.of(session, input)
     *         .selectedModel(model).selectedAgent(agent));
     *
     * switch (outcome.getKind()) {
     *     case TASK:   // 有可执行的任务（命令触发的也在这里，回执由 isCommand() 读回）
     *         if (outcome.hasReply()) {
     *             render(outcome.getCommandOutput());
     *         }
     *         outcome.getRequest().stream().subscribe(event -> render(event));
     *         break;
     *     case REPLY:  // 只有回执（含命令在本渠道不可用）
     *         render(outcome.getCommandOutput());
     *         break;
     *     case HITL:   // 决策已写入但未续流（未传 resume），是否恢复流由宿主决定
     *     default:     // IGNORED：无事可做
     *         break;
     * }
     * }</pre>
     *
     * <p>宿主取流只有一条路径：{@code outcome.getRequest().stream()}。回执读
     * {@code outcome.getCommandOutput()}，两者的先后顺序与订阅时机均由宿主掌握
     * （各端时序要求不同：CLI 即时写终端、Web 要求流门复位早于回执、桌面需要序号回放）。</p>
     *
     * @param input 输入上下文（含命令上下文工厂，为空时使用缓冲式默认实现）
     */
    public InputOutcome handle(HarnessInput input) {
        for(HarnessExtension extension: options.getExtensions()) {
            if (extension.isEnabled()) {
                extension.onInput(this, input);
            }
        }

        final AgentSession session = input.getSession();
        final InputAttachments attachments = input.getAttachments();

        // ========== 1. HITL 决策（优先于空输入判空：审批请求通常不带文本）==========
        if (input.isHitlDecision()) {
            //审批请求通常不带 selectedAgent：回退到会话级选择，避免宿主续流时误回主代理
            //（trace 按代理名分 key，取错代理会取不到挂起的 tool_calls）。
            String agentName = resolveHitlAgent(session, input.getSelectedAgent());
            boolean hitlApplied = applyHitlDecision(session, input.getHitlAction(), input.getHitlCallId(), input.getHitlOutput());
            int pendingHitl = HitlCard.pendingCount(session);

            if (input.isResume() && hitlApplied && pendingHitl == 0) {
                //决策写入后同一次调用完成续流装配，宿主无须再发一次 handle。
                //三个前置条件缺一不可：
                //1) hitlApplied —— 无匹配挂起任务时续流会把上一轮问题重跑一遍（空提示词回落 originalPrompt）；
                //2) pendingHitl == 0 —— 批内仍有未决卡时续流会把它们一并带走；
                //3) 宿主显式传 resume —— 只写决策的批量场景不自作主张。
                rememberEffectiveAgent(session, agentName);
                return InputOutcome.task(null, agentName, resolveSessionModelName(session, input.getSelectedModel()),
                        promptDo(session, null, input.getSelectedModel(), agentName, input.getSelectedSkills(), input.getSelectedMcps()), true);
            }

            //未续流的两种情形都归入 HITL，由宿主按 isHitlApplied / getPendingHitlCount 决定后续动作。
            return InputOutcome.hitl(agentName, resolveSessionModelName(session, input.getSelectedModel()),
                    hitlApplied, pendingHitl, HitlCard.list(session));
        }

        // ========== 2. 续流（无新输入，按挂起的 tool_calls 继续执行）==========
        if (input.isResume()) {
            //生效代理的会话级记忆：续流未显式指定时回填上一轮生效值（无状态宿主跨请求续流的关键）
            String agentName = Assert.isNotEmpty(input.getSelectedAgent())
                    ? input.getSelectedAgent() : Assert.isNotEmpty(input.getExplicitAgent())
                    ? input.getExplicitAgent() : effectiveAgentOf(session);
            rememberEffectiveAgent(session, agentName);
            return InputOutcome.task(null, agentName, resolveSessionModelName(session, input.getSelectedModel()),
                    promptDo(session, null, input.getSelectedModel(), agentName, input.getSelectedSkills(), input.getSelectedMcps()));
        }

        if (Assert.isEmpty(input.getText()) && !attachments.hasAny()) {
            return InputOutcome.ignored();
        }

        // ========== 3. 命令识别与执行（有附件或声明非命令语境时不当作命令，与既有口径一致）==========
        if (input.isCommandAware() && !attachments.hasAny()) {
            InputOutcome commandOutcome = dispatchCommand(session, input);
            if (commandOutcome != null) {
                return commandOutcome;
            }
        }

        // ========== 4. 子代理解析 + 附件前缀 ==========
        //附件由宿主落盘并装配（各端校验规则不同：桌面端有符号链接与总量上限，Web 端只需路径穿越校验），
        //引擎不再触碰文件系统，只负责提示词装配。
        String text = input.getText();

        AgentMentions.Mention mention = input.parseMention(this);
        String agentName = mention.getAgentName();
        text = attachments.withFilePrefix(mention.getText());

        if (Assert.isEmpty(text) && !attachments.hasMedia()) {
            return InputOutcome.ignored();
        }

        if (Assert.isEmpty(text)) {
            if (attachments.isAllImages()) {
                text = attachments.getMediaBlocks().size() > 1 ? "请描述这些图片" : "请描述这张图片";
            } else {
                text = "请分析这些媒体文件";
            }
        }

        // ========== 5. Prompt 构建 ==========
        Prompt prompt = buildInputPrompt(text, attachments, input.getOrigin().getChannel(), agentName, input);

        // ========== 6. 请求装配 ==========
        String modelName = resolveSessionModelName(session, input.getSelectedModel());
        rememberEffectiveAgent(session, agentName);
        return InputOutcome.task(prompt, agentName, modelName,
                promptDo(session, prompt, input.getSelectedModel(), agentName, input.getSelectedSkills(), input.getSelectedMcps()));
    }

    /**
     * 记录本轮生效代理到会话属性（主代理归一为 "main"）。
     *
     * <p>写入时机与流启动同步：发生在 handle 返回 TASK 后、宿主订阅前，与 Web 端原先
     * 「StreamBuilder 每轮写入」等价。HITL 挂起期间不覆盖（决策分支仅在真正续流时写入）。</p>
     */
    private static void rememberEffectiveAgent(@Nullable AgentSession session, @Nullable String agentName) {
        if (session == null) {
            return;
        }
        session.attrs().put(ATTR_AGENT_EFFECTIVE,
                Assert.isNotEmpty(agentName) ? agentName : AgentDefinition.AGENT_MAIN);
    }

    /**
     * 读取生效代理的会话级记忆（未记忆过时为 null）。
     */
    @Nullable
    private static String effectiveAgentOf(@Nullable AgentSession session) {
        if (session == null) {
            return null;
        }
        return session.attrs().get(ATTR_AGENT_EFFECTIVE) instanceof String
                ? (String) session.attrs().get(ATTR_AGENT_EFFECTIVE) : null;
    }

    /**
     * 构建输入提示词（有多媒体时组装多模态内容，并写入来源 / 附件 / 子代理 / 视野元数据）
     */
    private Prompt buildInputPrompt(String text, InputAttachments attachments, @Nullable String channel,
                                    @Nullable String agentName, @Nullable HarnessInput input) {
        UserMessage userMsg;

        if (attachments.hasMedia()) {
            Contents contents = new Contents();
            contents.addBlock(TextBlock.of(text));
            for (MediaBlock block : attachments.getMediaBlocks()) {
                contents.addBlock(block);
            }
            userMsg = new UserMessage(contents);

            String attachMeta = attachments.buildAttachmentMeta();
            if (attachMeta != null) {
                userMsg.addMetadata("attachments", attachMeta);
            }
        } else {
            userMsg = ChatMessage.ofUser(text);
        }

        if (channel != null) {
            userMsg.addMetadata("source", channel);
        }
        if (Assert.isNotEmpty(agentName)) {
            userMsg.addMetadata("agent", agentName);
        }

        Prompt prompt = Prompt.of(userMsg);

        if (input != null) {
            // 双写：attrs 是「本次输入选了什么」的事实（供回执 / 日志 / 宿主回读），
            // toolContext 是「本次执行生效什么」（promptDo 写入）。两者冲突时 toolContext 优先。
            if (input.hasSkillSelection()) {
                SkillTalent.putSelected(prompt, input.getSelectedSkills());
            }

            if (input.hasMcpSelection()) {
                McpGatewayTalent.putSelected(prompt, input.getSelectedMcps());
            }
        }

        return prompt;
    }

    /**
     * 应用 HITL 审批决策
     *
     * @param output 工具返回值改写文本（可空），非空时作为决策备注写入，由 HITLInterceptor 覆写工具返回
     * @return 决策是否真的写入；无匹配的挂起任务（过期审批卡）时返回 false
     */
    private boolean applyHitlDecision(@Nullable AgentSession session, HITLAction action, @Nullable String callId,
                                     @Nullable String output) {
        if (session == null) {
            return false;
        }

        HITLTask task = Assert.isNotEmpty(callId)
                ? HITL.getPendingTaskByCallUuid(session, callId)
                : HITL.getPendingTask(session);

        if (task == null) {
            return false;
        }

        // 与三端既有的「批准/拒绝并改写工具返回」口径一致（桌面端 HITL 三段重载）
        if (Assert.isNotEmpty(output)) {
            switch (action) {
                case APPROVE:
                    HITL.approve(session, task, output);
                    break;
                case REJECT:
                    HITL.reject(session, task, output);
                    break;
                case SKIP:
                    HITL.skip(session, task, output);
                    break;
            }
        } else {
            switch (action) {
                case APPROVE:
                    HITL.approve(session, task);
                    break;
                case REJECT:
                    HITL.reject(session, task);
                    break;
                case SKIP:
                    HITL.skip(session, task);
                    break;
            }
        }

        return true;
    }


    /**
     * 命令识别与执行（非命令时返回 null）
     * <p>
     * 供 {@link #handle} 与宿主的「是不是命令」快速判断共用：后者在普通输入上不应触发
     * 附件落盘、提及解析与请求装配 —— 那些结果会被宿主丢弃，白跑一遍。
     */
    private @Nullable InputOutcome dispatchCommand(AgentSession session, HarnessInput input) {
        if (input.getText() == null || input.hasAttachments()) {
            return null;
        }

        ParsedCommand command = ParsedCommand.parse(input.getText(), options.getCommandPrefix());
        if (command == null) {
            return null;
        }

        CommandContextFactory factory = input.getCommandContextFactory();
        CommandContextFactory realFactory = factory != null ? factory : new BufferedCommandContextFactory();
        try {
            return CommandDispatcher.dispatch(this, session, command, realFactory, input.getOrigin(), input.isTerminal(), input);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Command execute failed: " + command.getName(), e);
        }
    }

    /**
     * 解析生效的模型名：显式入参优先，其次会话级选择
     */
    private String resolveSessionModelName(@Nullable AgentSession session, @Nullable String modelName) {
        if (modelName != null) {
            return modelName;
        }

        return SessionSelections.modelOf(session);
    }

    /**
     * 解析 HITL 请求的生效子代理名：审批入参优先，其次会话级选择
     * <p>
     * Web 端审批请求通常不携带 selectedAgent，而首轮生效代理（含 {@code @子代理} 解析结果）
     * 已由宿主写入会话级子代理选择（{@link #setSessionAgent}）。HITL 续流必须沿用它：trace 按代理名分 key，
     * 取错代理会取不到挂起的 tool_calls，表现为续跑空转。
     */
    private String resolveHitlAgent(@Nullable AgentSession session, @Nullable String selectedAgent) {
        if (Assert.isNotEmpty(selectedAgent)) {
            return selectedAgent;
        }

        return SessionSelections.agentOf(session);
    }


    /**
     * 中断会话当前正在执行的任务（程序化 /interrupt）。
     *
     * <p>收敛此前只存在于 {@code /interrupt} 命令里的内部细节：回调扩展的中断参与（如积压插件清待执行项）、
     * 释放运行流句柄、补写取消消息（带 runId 元数据）与快照。
     * {@code /interrupt} 命令也走本方法，不存在第二套中断逻辑。</p>
     *
     * <p><b>先清积压再停流</b>：{@link HarnessExtension#onInterrupt} 保证在认领/停流之前回调。
     * 停流后宿主常在终态回调里续发队首，顺序颠倒会把刚被停掉的任务重新推起来。</p>
     *
     * <pre>{@code
     * if (engine.interrupt(session)) {
     *     engine.handle(HarnessInput.of(session, "改用 PostgreSQL 方言重写迁移脚本"));
     * }
     * }</pre>
     *
     * @return true = 已中断；false = 当前无正在执行的任务（会话为 null 时也返回 false）
     */
    public boolean interrupt(@Nullable AgentSession session) {
        InterruptPlan plan = prepareInterrupt(session);
        return plan != null && plan.commit();
    }

    /**
     * 两段式中断的第一段：扩展中断参与（清积压等）+ 原子认领运行句柄（不停流、不写取消消息）。
     *
     * <p>给需要自定义终态时序的宿主（如 Web 的「先落库 → error → done → 最后停流」）留出
     * 编排窗口；编排完调用 {@link InterruptPlan#commit()} 提交。不需要自定义时序的宿主
     * 继续用 {@link #interrupt(AgentSession)} 即可（等价于 prepare + commit）。</p>
     *
     * <p>计划必须闭合：用 try-with-resources 持有，或确保任何路径上都调
     * {@link InterruptPlan#close()}（与 commit 等价且幂等）——忘记闭合会留下一条
     * 无法再次取消的孤儿流。</p>
     *
     * <pre>{@code
     * try (InterruptPlan plan = engine.prepareInterrupt(session)) {
     *     if (plan == null || !plan.hasActiveRun()) { emitFallbackDone(); return; }
     *     emitError(cancelText);
     *     emitDone();
     *     plan.commit();
     * }
     * }</pre>
     *
     * @return 中断计划；null 仅当会话为 null。无活跃流时返回 {@code hasActiveRun()==false}
     *         的计划（积压与取消代数已处理，宿主走空态出口）
     * @since 4.2.0
     */
    @Nullable
    public InterruptPlan prepareInterrupt(@Nullable AgentSession session) {
        if (session == null) {
            return null;
        }

        // ① 扩展中断参与（先于停流）：逐个隔离异常，任何扩展都不能阻止中断生效
        for (HarnessExtension extension : options.getExtensions()) {
            try {
                extension.onInterrupt(this, session);
            } catch (Throwable e) {
                LOG.warn("Extension onInterrupt failed: {}", extension.getClass().getName(), e);
            }
        }

        // ② 核心：原子认领运行句柄（停流与补收尾在 plan.commit）
        return InterruptHelper.claim(session);
    }

    /**
     * 只停当前运行流，不补取消消息、不清队列（程序化「停流」）。
     *
     * <p>与 {@link #interrupt(AgentSession)} 的差别仅在于<b>不写历史</b>：适合轮次由程序接管、
     * 后续会重新发起新一轮的场景（如自动循环的上一轮被新目标顶掉）。这类场景若补写取消消息，
     * 会在会话历史里留下「用户已取消」的假象，与真实的用户取消混淆。</p>
     *
     * <p>返回 {@code false} 同样不代表什么都没发生：取消代数依然推进（见
     * {@link InterruptHelper#interruptStreamOnly(AgentSession)}）。</p>
     *
     * @return true = 确有运行中的流被停掉；false = 调用时没有已登记的活跃流
     * @since 4.2.0
     */
    public boolean interruptStream(@Nullable AgentSession session) {
        return InterruptHelper.interruptStreamOnly(session);
    }

    /**
     * 只停当前运行流，且不推进取消代数（新轮次顶掉旧轮次用）。
     *
     * <p>与 {@link #interruptStream(AgentSession)} 的差别在于「这次停流算不算取消」：
     * 推进代数后，被顶掉的旧流能据此判定自己是被取消的、不再发终态帧（适合 Stop）；
     * 不推进代数则适合「新一轮腾位」——旧流本该自然结束，若当成被取消会把新一轮收尾牵连进去。</p>
     *
     * @return true = 确有活跃流被停掉
     * @since 4.2.0
     */
    public boolean interruptStreamActive(@Nullable AgentSession session) {
        return InterruptHelper.interruptStreamActive(session);
    }

    /**
     * 读取会话运行态快照（核心事实 + 各扩展贡献的事实，一次读全）
     *
     * <p>宿主的「能不能接新输入」判定需要同时看运行流、挂起卡、积压等多项。读一次快照即可，不要自己拼。
     * 核心事实由 {@link SessionStatus#builder} 读定，扩展事实由 {@link HarnessExtension#onStatus} 贡献
     * （按插件自定义的 {@link SessionStatus.Key} 读取，如 {@code QueueExtension.STATUS_PENDING}）。</p>
     *
     * <p>只想问「现在能不能注入一轮程序任务」用 {@link #isIdle(AgentSession)}，不要自己拼。</p>
     *
     * @since 4.2.0
     */
    public SessionStatus status(@Nullable AgentSession session) {
        SessionStatus.Builder builder = SessionStatus.builder(session);
        if (session != null) {
            for (HarnessExtension extension : options.getExtensions()) {
                try {
                    extension.onStatus(this, session, builder);
                } catch (Throwable e) {
                    LOG.warn("Extension onStatus failed: {}", extension.getClass().getName(), e);
                }
            }
        }
        return builder.build();
    }

    /**
     * 会话是否完全空闲（可以注入一轮程序任务）。
     *
     * <p>口径：不占用（无运行流、不在受理窗口、无待审批卡，即 {@link SessionStatus#isOccupied()} 为假）
     * <b>且</b>没有任何积压能力报告待执行输入（{@link InputBacklog#hasPending}）——有积压时注入等于插队。
     * 两项在同一把会话锁内读取，结果一致。</p>
     *
     * <p>给 goal 调度、宿主定时任务等「程序代替用户发起一轮」的场景用；用户输入请用 {@link #admit}。</p>
     *
     * @return session 为 null 时返回 false（无从注入）
     * @since 4.2.0
     */
    public boolean isIdle(@Nullable AgentSession session) {
        if (session == null) {
            return false;
        }
        synchronized (session.attrs()) {
            return SessionStatus.of(session).isOccupied() == false && hasPendingBacklog(session) == false;
        }
    }

    /** 引擎上是否装有积压能力（决定忙态受理给 DEFER 还是 REJECT）。 */
    private boolean hasBacklog() {
        return getExtension(InputBacklog.class).isPresent();
    }

    /** 是否有任一积压能力报告待执行输入（先进先出与空闲判定共用；逐个隔离异常）。 */
    private boolean hasPendingBacklog(AgentSession session) {
        for (HarnessExtension extension : options.getExtensions()) {
            if (extension instanceof InputBacklog) {
                try {
                    if (((InputBacklog) extension).hasPending(session)) {
                        return true;
                    }
                } catch (Throwable e) {
                    LOG.warn("InputBacklog hasPending failed: {}", extension.getClass().getName(), e);
                }
            }
        }
        return false;
    }

    /**
     * 输入是否为「忙碌可穿透」的斜杠命令。
     *
     * <p>宿主的受理控制需要回答「这条输入能不能在任务运行中被受理」。答案不该由宿主
     * 复刻「解析命令名 → 查注册表 → 读 terminalOnly/runnableWhenBusy」这条链——
     * 三处口径任一漂移（前缀、大小写、参数切分），忙态旁路就会静默失效。
     * 本方法把该判定收归引擎，宿主只需一个布尔。</p>
     *
     * <p>判定口径：输入以引擎命令前缀开头、能解析出命令名、且该命令已注册、
     * 非 {@link Command#terminalOnly()} 且声明 {@link Command#runnableWhenBusy()}。</p>
     *
     * <p><b>注意</b>：本方法只回答「忙态是否放行」，不回答「空闲时能否启动任务」。
     * 后者（如 {@code /steer}）仍由宿主按自己的占位闩语义处理。</p>
     *
     * @see Command#runnableWhenBusy()
     * @since 4.2.1
     */
    public boolean isBusyBypassCommand(@Nullable String input) {
        Command command = resolveCommand(input);
        return command != null && !command.terminalOnly() && command.runnableWhenBusy();
    }

    /**
     * 输入受理判定：这条输入现在该走哪条路（原子：判定与占位同临界区）。
     *
     * <p><b>为什么要有它</b>：入口最易写错的一段不是「怎么装配」，而是「现在能不能接」。
     * 它要同时看运行流、受理窗口、待审批卡、积压、是否程序派发、是否忙态旁路命令，
     * 六个判定分散在三个包，宿主每次都要各查一遍并自行组合。Web 端这份判定曾长达 50 余行，
     * 且用到的每个原语其实都已由 harness 提供。</p>
     *
     * <p><b>口径固定</b>：忙态一律为「有活跃流或已在受理窗口」（{@link InputGate#tryAcquire} 同口径），
     * 待审批卡另判。宿主不得传入自己的忙判定——这正是历史上最容易漂移的一处。</p>
     *
     * <p><b>本方法不做</b>：不执行入积压、不渲染回执、不发终态。{@link AdmitDecision.Kind#DEFER}
     * 由宿主交给积压插件（推理强度/思考模式属于宿主域概念）；
     * {@link AdmitDecision.Kind#REJECT} 由宿主渲染拒因。</p>
     *
     * <p><b>没有积压能力时</b>（引擎上未装 {@link InputBacklog}）：忙态给 {@code REJECT("busy")}，
     * 而不是宿主无处可延的 {@code DEFER}。</p>
     *
     * <p>典型用法：</p>
     * <pre>{@code
     * AdmitDecision admit = engine.admit(session, input, hitlDecision, hitlExempt);
     * switch (admit.getKind()) {
     *     case REJECT: renderError(admit.getRejectReason(), admit.getRejectDetail()); return;
     *     case DEFER:  offerToBacklogAndDrain(); return;
     *     default:     break;  // ADMIT / ADMIT_BYPASS
     * }
     * try (InputGate.Ticket ticket = admit.getTicket()) {
     *     InputOutcome outcome = engine.handle(input);
     *     // ... 起流
     * }
     * }</pre>
     *
     * @param session      目标会话（可为 null：按空闲处理）
     * @param input        原始输入（用于识别忙态旁路命令；可为 null）
     * @param hitlDecision 本输入是否携带 HITL 决策（决策输入总是豁免挂起拦截）
     * @param hitlExempt   额外豁免谓词（可为 null）。用于「续流 / 特定命令」等宿主域豁免；
     *                     宿主不传时只按 {@code hitlDecision} 与「队列派发」判豁免
     * @since 4.2.3
     */
    public AdmitDecision admit(@Nullable AgentSession session, @Nullable String input,
                               boolean hitlDecision,
                               java.util.function.Predicate<String> hitlExempt) {
        if (session == null) {
            return AdmitDecision.admit(InputGate.Ticket.NONE);
        }

        synchronized (session.attrs()) {
            TurnDispatch dispatch = TurnDispatch.current(session);
            boolean onDispatchThread = dispatch != null && dispatch.isOnDispatchThread();
            boolean busyCommand = isBusyBypassCommand(input);

            // 1) HITL 挂起：审批未决策前开新回合会与挂起 trace 并存，
            //    用户点「同意」时续流被运行态认领拒绝，或两条流交叉写快照。
            SessionStatus status = status(session);
            if (status.hasPendingHitl()) {
                boolean exempt = hitlDecision || onDispatchThread
                        || (hitlExempt != null && hitlExempt.test(input));
                if (exempt == false) {
                    HitlCard next = HitlCard.next(session);
                    String toolName = next == null ? null : next.getToolName();
                    return AdmitDecision.reject("hitl_pending",
                            toolName == null ? "请先处理待审批操作" : "请先处理待审批操作：" + toolName);
                }
            }

            // 2) 忙态（有活跃流 / 已在受理窗口）或积压里还有待执行输入：应延后（保证先进先出）。
            //    程序派发本身与忙态旁路命令除外（后者的语义是在运行中的任务旁插一脚）。
            //    没装积压能力时无处可延：拒绝（busy）。
            if (onDispatchThread == false && busyCommand == false
                    && (status.isBusy() || status.isAdmitting() || hasPendingBacklog(session))) {
                return hasBacklog() ? AdmitDecision.defer()
                        : AdmitDecision.reject("busy", "当前任务正在执行，请稍后再试");
            }

            // 3) 程序派发的一轮：天然空闲、不占位（再占位会让同步完成的命令误判成忙态）
            if (onDispatchThread) {
                return AdmitDecision.bypassDispatch(dispatch);
            }

            // 4) 已在真实任务上运行的旁路命令：不占启动闩、不改写回复路由
            boolean sideCommand = busyCommand && status.isBusy();
            if (sideCommand) {
                return AdmitDecision.bypassSideCommand();
            }

            // 5) 正常受理：判定与占位同临界区
            return AdmitDecision.admit(InputGate.tryAcquire(session));
        }
    }

    /**
     * 解析输入对应的已注册命令（未以命令前缀开头、只有前缀、或未注册时返回 null）。
     *
     * <p>宿主在忙态/空闲态之间分流前常需先知道「这是不是一条命令」，
     * 但不应自行解析前缀与参数。命名/前缀口径的唯一实现留在引擎侧。</p>
     *
     * @since 4.2.1
     */
    @Nullable
    public Command resolveCommand(@Nullable String input) {
        if (input == null || commandRegistry == null) {
            return null;
        }
        String trimmed = input.trim();
        ParsedCommand parsed = ParsedCommand.parse(trimmed, options.getCommandPrefix());
        if (parsed == null) {
            return null;
        }
        Command command = commandRegistry.find(parsed.getName());
        if (command == null || command.isEnabled() == false) {
            return null;
        }
        return command;
    }

    public static Builder of(String workspace, String harnessHome) {
        return new Builder(workspace, harnessHome);
    }

    public static class Builder {
        private final HarnessOptions options;

        public Builder(String workspace, String harnessHome) {
            this.options = new HarnessOptions(workspace, harnessHome);
        }

        // ========== 服务注入（代理到 options） ==========

        public Builder sessionProvider(AgentSessionProvider sessionProvider) {
            options.setSessionProvider(sessionProvider);
            return this;
        }

        /**
         * 人工介入拦截器
         */
        public Builder hitlInterceptor(HITLInterceptor hitlInterceptor) {
            options.setHitlInterceptor(hitlInterceptor);
            return this;
        }

        /**
         * 心智记忆存储方案
         */
        public Builder memoryProvider(MemorySolutionProvider memoryProvider) {
            options.setMemoryProvider(memoryProvider);
            return this;
        }

        // ========== 简单属性配置（代理到 options） ==========

        public Builder systemPrompt(String systemPrompt) {
            options.setSystemPrompt(systemPrompt);
            return this;
        }

        public Builder userAgent(String userAgent) {
            options.setUserAgent(userAgent);
            return this;
        }

        /**
         * 引擎级代理（host/port 形式），构建期配置。
         *
         * @since 4.0.5
         */
        public Builder httpProxy(String host, int port) {
            options.setHttpProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
            return this;
        }

        /**
         * 引擎级代理（java.net.Proxy 形式），构建期配置；传 null 表示直连。
         *
         * @since 4.0.5
         */
        public Builder httpProxy(Proxy proxy) {
            options.setHttpProxy(proxy);
            return this;
        }

        /**
         * 引擎级 http 定制钩子（每次 LLM 请求创建 HttpUtils 后执行，可覆盖/追加 proxy、header、timeout 等）。
         *
         * @since 4.0.5
         */
        public Builder httpCustomizeAdd(Consumer<HttpUtils> httpCustomize) {
            options.addHttpCustomize(httpCustomize);
            return this;
        }

        public Builder httpCustomizeSet(Consumer<HttpUtils> httpCustomize) {
            options.setHttpCustomize(httpCustomize);
            return this;
        }

        public Builder maxTurns(Integer maxTurns) {
            options.setMaxTurns(maxTurns);
            return this;
        }

        public Builder autoRethink(Boolean autoRethink) {
            options.setAutoRethink(autoRethink);
            return this;
        }

        public Builder sessionWindowSize(Integer sessionWindowSize) {
            options.setSessionWindowSize(sessionWindowSize);
            return this;
        }

        public Builder compressionThreshold(Integer maxMessages, Double maxContextRatio) {
            options.setCompressionMaxMessages(maxMessages);
            options.setCompressionMaxContextRatio(maxContextRatio);
            return this;
        }

        public Builder compressionDefaultContextLength(Long defaultContextLength) {
            options.setCompressionDefaultContextLength(defaultContextLength);
            return this;
        }

        /**
         * @deprecated 4.0.4
         */
        @Deprecated
        public Builder compressionModel(String compressionModel) {
            options.setCompressionModel(compressionModel);
            return this;
        }

        public Builder compressionInterceptor(ContextCompressionInterceptor compressionInterceptor) {
            options.setCompressionInterceptor(compressionInterceptor);
            return this;
        }

        public Builder stopLoopInterceptor(StopLoopInterceptor stopLoopInterceptor) {
            options.setStopLoopInterceptor(stopLoopInterceptor);
            return this;
        }

        public Builder cacheControl(CacheControl cacheControl) {
            options.setCacheControl(cacheControl);
            return this;
        }

        public Builder memoryEnabled(Boolean memoryEnabled) {
            options.setMemoryEnabled(memoryEnabled);
            return this;
        }

        /**
         * 记忆注入：按对话内容语义匹配的条数（默认 6）。小窗口模型建议 3-4，大窗口建议 8-10。
         */
        public Builder memoryRelevanceCount(int n) {
            options.setMemoryRelevanceCount(n);
            return this;
        }

        /**
         * 记忆注入：按重要度兜底的条数（默认 5）。
         */
        public Builder memoryPriorityCount(int n) {
            options.setMemoryPriorityCount(n);
            return this;
        }

        /**
         * 记忆 listAll 视图的摘要截断长度（默认 80）。
         */
        public Builder memorySummaryLength(int n) {
            options.setMemorySummaryLength(n);
            return this;
        }

        public Builder sandboxEnabled(Boolean sandboxEnabled) {
            options.setSandboxEnabled(sandboxEnabled);
            return this;
        }

        public Builder sandboxAllowUserHome(Boolean sandboxAllowUserHome) {
            options.setSandboxAllowUserHome(sandboxAllowUserHome);
            return this;
        }

        public Builder sandboxSystemRestrict(Boolean sandboxSystemRestrict) {
            options.setSandboxSystemRestrict(sandboxSystemRestrict);
            return this;
        }

        // ========== 权限系统 ==========

        /**
         * 添加权限规则
         */
        public Builder permissionRuleAdd(PermissionRule rule) {
            options.setPermissionContext(options.getPermissionContext().addRule(rule));
            return this;
        }

        public Builder hitlEnabled(Boolean hitlEnabled) {
            options.setHitlEnabled(hitlEnabled);
            return this;
        }

        public Builder subagentEnabled(Boolean subagentEnabled) {
            options.setSubagentEnabled(subagentEnabled);
            return this;
        }

        /**
         * 子代理任务默认并发上限（默认 5，取值 1~{@link TaskTalent#MAX_CONCURRENCY}；
         * 0 = 未设置即取默认，超出上界按上界处理，<b>不存在「不限制」</b>）
         *
         * <p>仅在模型未配置 {@code AiConfig#concurrency} 时生效；模型配了就以模型为准。</p>
         */
        public Builder subagentDefaultConcurrency(Integer subagentDefaultConcurrency) {
            options.setSubagentDefaultConcurrency(subagentDefaultConcurrency);
            return this;
        }

        /**
         * @deprecated 旧名，请改用 {@link #subagentDefaultConcurrency(Integer)}
         */
        @Deprecated
        public Builder multitaskMaxTasks(Integer multitaskMaxTasks) {
            options.setSubagentDefaultConcurrency(multitaskMaxTasks);
            return this;
        }

        public Builder bashAsyncEnabled(Boolean bashAsyncEnabled) {
            options.setBashAsyncEnabled(bashAsyncEnabled);
            return this;
        }

        public Builder apiRetries(Integer apiRetries) {
            options.setApiRetries(apiRetries);
            return this;
        }

        public Builder mcpRetries(Integer mcpRetries) {
            options.setMcpRetries(mcpRetries);
            return this;
        }

        public Builder modelRetries(Integer modelRetries) {
            options.setModelRetries(modelRetries);
            return this;
        }

        // ========== 集合类配置（代理到 options） ==========

        /**
         * @deprecated 4.0.4
         */
        @Deprecated
        public Builder toolsAdd(ToolPermission... tools) {
            options.addTools(tools);
            return this;
        }

        public Builder toolsAdd(ToolName... tools) {
            options.addTools(tools);
            return this;
        }

        public Builder toolsAdd(Collection<String> tools) {
            options.getTools().addAll(tools);
            return this;
        }

        /**
         * @deprecated 4.0.4
         */
        @Deprecated
        public Builder disallowedToolsAdd(ToolPermission... tools) {
            options.addDisallowedTools(tools);
            return this;
        }

        public Builder disallowedToolsAdd(ToolName... tools) {
            options.addDisallowedTools(tools);
            return this;
        }

        public Builder disallowedToolsAdd(Collection<String> tools) {
            options.getDisallowedTools().addAll(tools);
            return this;
        }

        public Builder extensionAdd(HarnessExtension val) {
            options.getExtensions().add(val);
            return this;
        }

        public Builder modelAdd(ChatConfig val) {
            options.addModel(val);
            return this;
        }

        public Builder modelAdd(Iterable<ChatConfig> models) {
            for (ChatConfig val : models) {
                options.addModel(val);
            }
            return this;
        }

        public Builder mountAdd(Mount mount) {
            if (mount != null && BUILTIN_AGENT_MOUNT.equals(normalizeMountAlias(mount.getAlias()))) {
                throw new IllegalArgumentException("Built-in mount cannot be replaced: " + BUILTIN_AGENT_MOUNT);
            }
            options.getMountManager().register(mount);
            return this;
        }

        public Builder mcpServerAdd(String name, McpServerParameters params) {
            options.getMcpServers().put(name, params);
            return this;
        }

        public Builder apiServerAdd(String name, ApiSource source) {
            options.getApiServers().put(name, source);
            return this;
        }

        public Builder lspServerAdd(String name, LspServerParameters lspServer) {
            options.getLspServers().put(name, lspServer);
            return this;
        }

        public Builder defaultModel(String defaultModel) {
            options.setDefaultModel(defaultModel);
            return this;
        }

        /**
         * 命令前缀（默认 "/"），支持多字符（如 "::"）
         */
        public Builder commandPrefix(String commandPrefix) {
            options.setCommandPrefix(commandPrefix);
            return this;
        }

        /**
         * 子代理提及前缀（默认 "@"），支持多字符（如 "#"）
         */
        public Builder agentPrefix(String agentPrefix) {
            options.setAgentPrefix(agentPrefix);
            return this;
        }

        public HarnessEngine build() {
            Objects.requireNonNull(options.getSessionProvider(), "sessionProvider");

            HarnessEngine engine = new HarnessEngine(options);

            for (HarnessExtension extension : options.getExtensions()) {
                extension.onAttach(engine);
            }

            return engine;
        }
    }
}