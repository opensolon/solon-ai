package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.agent.SessionAgents;
import org.noear.solon.ai.harness.agent.GenerateTalent;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.input.RejectReason;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.ai.harness.session.SessionSelections;
import org.noear.solon.ai.talents.cli.CommandResult;
import org.noear.solon.ai.harness.spi.InputBacklog;
import org.noear.solon.ai.harness.session.NotOwnerException;
import org.noear.solon.ai.harness.session.SessionOwnership;
import reactor.core.Disposable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SaaS 改造契约（M3a / M3b / M4）：门面归属、命名空间租约、作用域预检与积压、命令名单、
 * generate 会话级 / 全局分级、注册表封口、引擎关闭、会话主体与 cwd 持久化。
 */
class SaasContractTest {
    @TempDir
    Path workspace;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    private HarnessEngine.Builder builder() {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .modelAdd(model)
                .defaultModel("fake");
    }

    private AgentSession session(String id) {
        return sessions.computeIfAbsent(id, InMemoryAgentSession::of);
    }

    private static void busy(AgentSession session) {
        SessionRuns.attach(session, new Disposable() {
            volatile boolean disposed;

            @Override
            public void dispose() {
                disposed = true;
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }
        });
    }

    /** 记录入积压输入的桩（保存快照语义：直接持有输入对象） */
    static class RecordingBacklog implements HarnessExtension, InputBacklog {
        final List<HarnessInput> offered = Collections.synchronizedList(new ArrayList<>());

        @Override
        public int offer(AgentSession session, HarnessInput input) {
            offered.add(input);
            return offered.size();
        }

        @Override
        public boolean hasPending(AgentSession session) {
            return false;
        }
    }

    /** 其它节点持有全部会话 */
    static final SessionOwnership REMOTE = new SessionOwnership() {
        @Override
        public boolean tryAcquire(String sessionId) {
            return false;
        }

        @Override
        public String ownerOf(String sessionId) {
            return "node-b";
        }

        @Override
        public void release(String sessionId) {
        }
    };

    // ---------- M3a 归属 ----------

    @Test
    void notOwnerRejectsBeforeResolverAndInterruptIsNoop() {
        AtomicInteger resolverCalls = new AtomicInteger();
        HarnessEngine engine = builder()
                .sessionOwnership(REMOTE)
                .scopeResolver((s, in) -> {
                    resolverCalls.incrementAndGet();
                    return null;
                })
                .build();
        AgentSession s = session("s1");

        InputOutcome o = engine.handle(HarnessInput.of(s, "hi"));
        assertEquals(InputOutcome.Kind.REJECTED, o.getKind());
        assertEquals(RejectReason.NOT_OWNER, o.getRejectReason());
        assertEquals("node-b", o.getRejectDetail());
        assertEquals(0, resolverCalls.get(), "非持有节点不调用作用域解析器");

        assertFalse(engine.checkOwner("s1").isOwned());
        assertThrows(NotOwnerException.class, () -> engine.requireOwner("s1"));

        InterruptPlan plan = engine.prepareInterrupt(s);
        assertTrue(plan.isNotOwner());
        assertEquals("node-b", plan.getOwner());
        assertFalse(plan.hasActiveRun());
        assertEquals(HarnessResult.Code.NOT_OWNER, engine.interrupt(s, InterruptMode.INTERRUPT_ALL).getCode());
    }

    /** 三种中断方式在非持有节点上一律什么都不做，并显式返回 NOT_OWNER（此前 cancel/preempt 不查归属） */
    @Test
    void notOwnerInterruptModesLeaveRunUntouched() {
        HarnessEngine engine = builder().sessionOwnership(REMOTE).build();
        AgentSession s = session("s1");
        AtomicInteger disposed = new AtomicInteger();
        SessionRuns.attach(s, new Disposable() {
            @Override
            public void dispose() {
                disposed.incrementAndGet();
            }

            @Override
            public boolean isDisposed() {
                return disposed.get() > 0;
            }
        });

        for (InterruptMode mode : InterruptMode.values()) {
            HarnessResult<Boolean> r = engine.interrupt(s, mode);
            assertEquals(HarnessResult.Code.NOT_OWNER, r.getCode(), mode.name());
            assertEquals("node-b", r.getOwner(), mode.name());
        }
        assertEquals(0, disposed.get(), "非持有节点不得停掉运行流");
        assertTrue(SessionRuns.isBusy(s));
        assertEquals(0, SessionRuns.cancelCount(s), "非持有节点不得推进取消代数");
    }

    @Test
    void namespacedOwnershipPrefixesKeysAndIsIdempotent() {
        List<String> keys = new ArrayList<>();
        SessionOwnership shared = new SessionOwnership() {
            @Override
            public boolean tryAcquire(String sessionId) {
                keys.add(sessionId);
                return true;
            }

            @Override
            public String ownerOf(String sessionId) {
                return "me";
            }

            @Override
            public void release(String sessionId) {
                keys.add("release:" + sessionId);
            }
        };

        SessionOwnership a = SessionOwnership.namespaced("tenant-a", shared);
        assertSame(a, SessionOwnership.namespaced("tenant-b", a), "已包装的不再叠加前缀");
        assertSame(SessionOwnership.LOCAL, SessionOwnership.namespaced("t", SessionOwnership.LOCAL));
        assertThrows(IllegalArgumentException.class, () -> SessionOwnership.namespaced("", shared));

        HarnessEngine engine = builder().sessionOwnership(a).build();
        assertTrue(engine.checkOwner("cli").isOwned());
        engine.shutdown();
        assertEquals(Arrays.asList("tenant-a:cli", "release:tenant-a:cli"), keys);
    }

    // ---------- M4.2 作用域预检与积压 ----------

    @Test
    void scopeRejectedBeforeBacklogDoesNotConsumeQuota() {
        HarnessEngine engine = builder().scopeRequired(true).scopeResolver((s, in) -> null).build();
        RecordingBacklog backlog = new RecordingBacklog();
        engine.addExtension(backlog);
        AgentSession s = session("s1");
        busy(s);

        InputOutcome o = engine.handle(HarnessInput.of(s, "queued?"));
        assertEquals(RejectReason.SCOPE_UNAVAILABLE, o.getRejectReason());
        assertTrue(backlog.offered.isEmpty(), "解析不到作用域的输入不进积压");
    }

    @Test
    void nonDeferrableExplicitScopeIsRejectedWhenBusy() {
        HarnessEngine engine = builder().build();
        RecordingBacklog backlog = new RecordingBacklog();
        engine.addExtension(backlog);
        AgentSession s = session("s1");
        busy(s);

        InputScope withAttr = InputScope.builder("u").attr("k", new Object()).build();
        InputOutcome o = engine.handle(HarnessInput.of(s, "x").scope(withAttr));
        assertEquals(RejectReason.SCOPE_NOT_DEFERRABLE, o.getRejectReason());
        assertTrue(backlog.offered.isEmpty());

        InputScope plain = InputScope.builder("u").tools("read").build();
        InputOutcome ok = engine.handle(HarnessInput.of(s, "y").scope(plain));
        assertEquals(InputOutcome.Kind.DEFERRED, ok.getKind());
        assertTrue(backlog.offered.get(0).isScopeExplicit(), "显式作用域原样交给积压保存");
    }

    @Test
    void resolvedScopeIsNotExplicitAndRestoredScopeIsNarrowedByLatestGrant() {
        InputScope full = InputScope.builder("u").tools("read", "write", "bash").build();
        HarnessEngine engine = builder().scopeResolver((s, in) -> full).build();
        AgentSession s = session("s1");

        HarnessInput user = HarnessInput.of(s, "hi");
        engine.handle(user).discard();
        assertSame(full, user.getScope(), "解析结果回写到输入");
        assertFalse(user.isScopeExplicit(), "解析所得不算显式，积压不保存它");

        // 积压续发：保存的只读作用域 ∩ 最新完整授权 → 仍是只读（不被放宽）
        InputScope readOnly = InputScope.builder("u").tools("read").build();
        InputScope restored = InputScope.fromSpec(readOnly.toSpec());
        HarnessInput resumed = HarnessInput.of(s, "go on").restoredScope(restored);
        engine.handle(resumed).discard();
        assertEquals(readOnly.getTools(), resumed.getScope().getTools());

        // 主体不一致：拒绝
        HarnessInput other = HarnessInput.of(s, "x").restoredScope(InputScope.builder("someone-else").build());
        assertEquals(RejectReason.SCOPE_UNAVAILABLE, engine.handle(other).getRejectReason());
    }

    @Test
    void scopeSpecRoundTripAndIntersect() {
        InputScope a = InputScope.builder("p").tools("read", "bash").skills("s1", "s2").commands("model").build();
        InputScope back = InputScope.fromSpec(a.toSpec());
        assertEquals(a.getTools(), back.getTools(), "运行时工具名原样还原，不再展开");
        assertEquals(a.getSkills(), back.getSkills());
        assertEquals(a.getCommands(), back.getCommands());
        assertNull(back.getMcps());

        InputScope b = InputScope.builder("p").skills("s2", "s3").build();
        InputScope m = InputScope.intersect(a, b);
        assertEquals(Collections.singleton("s2"), m.getSkills());
        assertEquals(a.getTools(), m.getTools(), "一方不限时取另一方");
        assertNull(InputScope.intersect(a, InputScope.builder("q").build()));
    }

    // ---------- M4.3 命令名单 ----------

    @Test
    void commandOutsideScopeIsUnavailable() {
        AtomicInteger runs = new AtomicInteger();
        HarnessEngine engine = builder()
                .commandAdd(new Command() {
                    @Override
                    public String name() {
                        return "danger";
                    }

                    @Override
                    public String description() {
                        return "d";
                    }

                    @Override
                    public String[] examples() {
                        return new String[0];
                    }

                    @Override
                    public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) {
                        runs.incrementAndGet();
                    }
                })
                .scopeResolver((s, in) -> InputScope.builder("u").commands("help").build())
                .build();

        InputOutcome o = engine.handle(HarnessInput.of(session("s1"), "/danger"));
        assertEquals(0, runs.get(), "名单外命令不执行");
        assertTrue(o.getCommandOutput() != null && o.getCommandOutput().contains("不可用"), String.valueOf(o.getCommandOutput()));
    }

    // ---------- M3b generate 分级 ----------

    @Test
    void generateSessionScopedAndGlobalTiers() throws Exception {
        HarnessEngine engine = builder().build();
        GenerateTalent gen = new GenerateTalent(engine);
        session("s1");
        session("s2");

        String r = gen.generate("helper", "d", "you are helper", null, null, false, null, "s1");
        assertTrue(r.startsWith("[OK]"), r);
        assertNotNull(engine.resolveAgent(sessions.get("s1"), "helper"), "本会话可见");
        assertNull(engine.resolveAgent(sessions.get("s2"), "helper"), "其它会话不可见");
        assertFalse(engine.getAgentManager().hasAgent("helper"), "不进引擎级注册表");
        assertTrue(SessionAgents.names(sessions.get("s1")).contains("helper"));

        // 保留名
        assertTrue(gen.generate("explore", "d", "x", null, null, true, null, "s1").startsWith("ERROR"));
        assertTrue(gen.generate("general", "d", "x", null, null, false, null, "s1").startsWith("ERROR"));
        assertEquals("explore", engine.getAgentManager().getAgent("explore").getName());

        // 全局（STANDALONE 缺省允许持久化）
        String g = gen.generate("reviewer", "d", "x", null, null, true, null, "s1");
        assertTrue(g.startsWith("[OK]"), g);
        assertNotNull(engine.resolveAgent(sessions.get("s2"), "reviewer"), "全局对所有会话可见");
        assertTrue(engine.getWorkspaceSource().exists(engine.getHarnessAgents() + "reviewer.md"), "全局子代理已持久化");

        // 同名不覆盖（全局与会话级都拒绝）
        assertTrue(gen.generate("reviewer", "d", "y", null, null, true, null, "s2").startsWith("ERROR"));
        assertTrue(gen.generate("reviewer", "d", "y", null, null, false, null, "s2").startsWith("ERROR"));
    }

    @Test
    void generateGlobalIsDeniedOnServerByDefaultAndPolicyCanAllow() {
        HarnessEngine engine = builder().runtimeMutable(false).build();
        session("s1");
        GenerateTalent gen = new GenerateTalent(engine);
        String r = gen.generate("x1", "d", "p", null, null, true, null, "s1");
        assertTrue(r.startsWith("ERROR") && r.contains("saveToFile=false"), r);
        assertTrue(gen.generate("x1", "d", "p", null, null, false, null, "s1").startsWith("[OK]"));

        HarnessEngine admin = builder().runtimeMutable(false)
                .agentPersistPolicy((s, d) -> "admin".equals(SessionSelections.principalOf(s)))
                .build();
        AgentSession s2 = session("s-admin");
        admin.setSessionPrincipal(s2, "admin");
        assertTrue(new GenerateTalent(admin).generate("x2", "d", "p", null, null, true, null, "s-admin").startsWith("[OK]"));
    }

    /**
     * 会话级子代理的清单由 TaskTalent 按 prompt 携带的会话 ID 现查列出（替代原按请求挂载的 SessionAgentsTalent）：
     * 只对创建它的会话可见，且列清单不得顺带把不存在的会话建出来。
     */
    @Test
    void taskInstructionListsSessionAgentsOnlyForOwnerSession() {
        HarnessEngine engine = builder().build();
        session("s1");
        session("s2");

        String r = new GenerateTalent(engine).generate("helper", "helper-desc", "you are helper", null, null, false, null, "s1");
        assertTrue(r.startsWith("[OK]"), r);

        String own = engine.getTaskTalent().getInstruction(promptOf("s1"));
        assertTrue(own.contains("<session_agents>"), own);
        assertTrue(own.contains("\"helper\": helper-desc"), own);
        assertTrue(own.indexOf("</session_agents>") < own.indexOf("## 任务分配策略"), "清单应在分配策略之前: " + own);

        String other = engine.getTaskTalent().getInstruction(promptOf("s2"));
        assertFalse(other.contains("<session_agents>"), "其它会话不列: " + other);

        assertFalse(engine.getTaskTalent().getInstruction(null).contains("<session_agents>"), "无 prompt 不列");
        assertFalse(engine.getTaskTalent().getInstruction(Prompt.of("hi")).contains("<session_agents>"), "无会话 ID 不列");

        assertFalse(engine.getTaskTalent().getInstruction(promptOf("s-missing")).contains("<session_agents>"));
        assertFalse(sessions.containsKey("s-missing"), "列清单不得新建会话");
    }

    private static Prompt promptOf(String sessionId) {
        Prompt prompt = Prompt.of("hi");
        prompt.attrs().put(ChatSession.ATTR_SESSIONID, sessionId);
        return prompt;
    }

    @Test
    void sessionAgentsHaveUpperBound() {
        AgentSession s = session("s1");
        for (int i = 0; i < SessionAgents.MAX_PER_SESSION; i++) {
            assertTrue(SessionAgents.putIfAbsent(s, AgentDefinition.builder().name("a" + i).build(), SessionAgents.MAX_PER_SESSION));
        }
        assertFalse(SessionAgents.putIfAbsent(s, AgentDefinition.builder().name("overflow").build(), SessionAgents.MAX_PER_SESSION));
    }

    // ---------- M3b 封口与锁 ----------

    @Test
    void registriesAreSealedWhenRuntimeImmutable() {
        HarnessEngine engine = builder().runtimeMutable(false).build();
        assertThrows(IllegalStateException.class, () -> engine.getCommandRegistry().register(new Command() {
            @Override
            public String name() {
                return "late";
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public String[] examples() {
                return new String[0];
            }

            @Override
            public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) {
            }
        }));
        assertThrows(IllegalStateException.class, () -> engine.getAgentManager().addAgent(AgentDefinition.builder().name("z").build()));
        assertThrows(IllegalStateException.class, () -> engine.toolPermissionReset(Collections.emptyList(), Collections.emptyList()));
        assertThrows(IllegalStateException.class, () -> engine.setMemoryEnabled(true));
        assertThrows(IllegalStateException.class, () -> engine.setSubagentDefaultConcurrency(2));

        HarnessEngine coding = builder().build();
        assertFalse(coding.getCommandRegistry().isSealed());
    }

    // ---------- S17 关闭 ----------

    @Test
    void shutdownIsIdempotentAndRejectsLaterInput() {
        HarnessEngine engine = builder().build();
        assertFalse(engine.isClosed());
        engine.shutdown();
        engine.shutdown();
        assertTrue(engine.isClosed());
        InputOutcome o = engine.handle(HarnessInput.of(session("s1"), "hi"));
        assertEquals(RejectReason.ENGINE_CLOSED, o.getRejectReason());
    }

    // ---------- M4.1 主体与 cwd ----------

    @Test
    void principalIsImmutableAndResolverByPrincipal() {
        HarnessEngine engine = builder()
                .scopeResolver(InputScopeResolver.byPrincipal(p -> InputScope.builder(p).tools("read").build()))
                .scopeRequired(true)
                .build();
        AgentSession s = session("s1");

        assertEquals(RejectReason.SCOPE_UNAVAILABLE, engine.handle(HarnessInput.of(s, "hi")).getRejectReason(),
                "无主体时解析不到作用域");

        engine.setSessionPrincipal(s, "tenant-a");
        engine.setSessionPrincipal(s, "tenant-a");
        assertThrows(SecurityException.class, () -> engine.setSessionPrincipal(s, "tenant-b"));
        engine.rebindSessionPrincipal(s, "tenant-b");
        assertEquals("tenant-b", engine.getSessionPrincipal(s));

        HarnessInput in = HarnessInput.of(s, "hi");
        InputOutcome o = engine.handle(in);
        assertEquals(InputOutcome.Kind.TASK, o.getKind());
        o.discard();
        assertEquals("tenant-b", in.getScope().getPrincipal());
    }

    @Test
    void sessionCwdSurvivesRestartAndRespectsPolicy() throws Exception {
        Path tenantRoot = Files.createDirectories(workspace.resolve("tenant"));
        Path project = Files.createDirectories(tenantRoot.resolve("p1"));
        String root = tenantRoot.toRealPath().toString();
        HarnessEngine engine = builder().cwdPolicy(c -> c.startsWith(root)).build();
        AgentSession s = session("s1");
        engine.setSessionCwd(s, project.toString());

        s.attrs().remove(HarnessEngine.ATTR_CWD); // 模拟重启：内存键丢失，上下文仍在
        assertEquals(project.toString(), engine.getSessionCwd(s));

        // 符号链接逃逸被拒绝
        Path outside = Files.createDirectories(workspace.resolve("outside"));
        Path link = tenantRoot.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // 平台不支持符号链接
        }
        assertThrows(SecurityException.class, () -> engine.setSessionCwd(s, link.toString()));
    }

    @Test
    void cwdPolicyIsEnforcedAtConsumerSide() throws Exception {
        Path tenantRoot = Files.createDirectories(workspace.resolve("tenant"));
        String root = tenantRoot.toRealPath().toString();
        HarnessEngine engine = builder().cwdPolicy(c -> c.startsWith(root)).build();
        Path outside = Files.createDirectories(workspace.resolve("outside"));

        // 绕过 setSessionCwd（如请求级 toolContext 传入 __cwd）：消费端拒绝执行
        CommandResult r = engine.getTerminalTalent().exec("echo hi", outside.toString(), 5000, 1000);
        assertEquals(CommandResult.Status.FAILED, r.getStatus());
        assertTrue(r.getText().contains("out of policy"), r.getText());
    }

    // ---------- M5a（引擎池契约已迁到 host 模块的 HarnessEnginePoolTest） ----------

    @Test
    void sharedTaskExecutorIsNotClosedByEngine() {
        ExecutorService shared = Executors.newFixedThreadPool(2);
        try {
            HarnessEngine engine = builder().taskExecutor(shared).build();
            engine.shutdown();
            assertFalse(shared.isShutdown(), "注入的执行器生命周期归宿主");
        } finally {
            shared.shutdownNow();
        }
    }

    // ---------- M4.4 ----------

    @Test
    void requireSessionDoesNotCreate() {
        HarnessEngine engine = builder().build();
        assertThrows(IllegalArgumentException.class, () -> engine.requireSession("nope"));
        assertFalse(sessions.containsKey("nope"));
    }

}
