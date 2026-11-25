package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITLStrategy;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.hitl.BashToolStrategy;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.hitl.RememberedApprovals;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionRule;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HITL「总是允许」的记忆规则契约：只放行批准过的那一条（逐字相等），不做 glob，不退化为整工具放行
 */
class HitlRememberedRuleTest {

    @TempDir
    Path workspace;

    private static Map<String, Object> cmd(String c) {
        Map<String, Object> m = new HashMap<>();
        m.put("command", c);
        return m;
    }

    private static String evalBash(PermissionContext ctx, String command) {
        return new BashToolStrategy().permissionContextSupplier(() -> ctx).evaluate(null, cmd(command));
    }

    /** 按拦截器里登记的策略求值（策略表是私有的，测试经反射读取）；该工具没有登记策略时返回 null */
    @SuppressWarnings("unchecked")
    private static String evalTool(HITLInterceptor hitl, String toolName, Map<String, Object> args) throws Exception {
        Field f = HITLInterceptor.class.getDeclaredField("strategyMap");
        f.setAccessible(true);
        HITLStrategy strategy = ((Map<String, HITLStrategy>) f.get(hitl)).get(toolName);
        return strategy == null ? null : strategy.evaluate(null, args);
    }

    @Test
    void rememberedCommandIsExactNotGlob() {
        PermissionContext ctx = PermissionContext.create().addRule(PermissionRule.ask("bash"));
        PermissionRule rule = HarnessEngine.rememberedRuleOf("bash", cmd("rm -rf build/*"));
        assertNotNull(rule);
        ctx = ctx.addRule(rule);

        assertNull(evalBash(ctx, "rm -rf build/*"), "批准过的那一条应免审");
        assertNotNull(evalBash(ctx, "rm -rf build/; echo pwn"), "原文里的 * 不得被当作通配符");
        assertNotNull(evalBash(ctx, "RM -RF BUILD/*"), "精确匹配区分大小写");
    }

    /** 引擎默认审批：bash_start 与 bash 同一套判定（否则换用 bash_start 就绕过）；bash_wait 只作用于已启动的会话，不单独审批 */
    @Test
    void defaultHitlJudgesBashStartLikeBash() throws Exception {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, parentId, c) -> InMemoryAgentSession.of(id))
                .permissionRuleAdd(PermissionRule.ask("bash", "touch *"))
                .build();
        HITLInterceptor hitl = engine.getHitlInterceptor();

        assertNotNull(evalTool(hitl, "bash", cmd("touch a.txt")));
        assertNotNull(evalTool(hitl, "bash_start", cmd("touch a.txt")), "bash_start 不得绕过审批");
        assertNull(evalTool(hitl, "bash_start", cmd("echo hi")), "未命中规则的命令与 bash 一样放行");

        Map<String, Object> stdin = new HashMap<>();
        stdin.put("session_id", "x");
        stdin.put("chars", "touch a.txt\n");
        assertNull(evalTool(hitl, "bash_wait", stdin));
    }

    /** 经 bash_start 批准的「总是允许」记成 bash 规则：策略按 bash 求值，记成 bash_start 永远命不中 */
    @Test
    void bashStartApprovalIsRememberedAsBash() {
        PermissionRule rule = HarnessEngine.rememberedRuleOf("bash_start", cmd("mvn clean"));
        assertEquals(HarnessEngine.rememberedRuleOf("bash", cmd("mvn clean")), rule);

        PermissionContext ctx = PermissionContext.create().addRule(PermissionRule.ask("bash")).addRule(rule);
        assertNull(evalBash(ctx, "mvn clean"), "批准过的那一条应免审");
        assertNotNull(evalBash(ctx, "mvn install"));
    }

    /** bash_start 也会弹审批卡：宿主靠 isBash() 把命令原文提到显眼处（如 CLI 的审批提示） */
    @Test
    void bashStartCardShowsCommandLikeBash() {
        AgentSession session = InMemoryAgentSession.of();
        session.getContext().put(HITL.PENDING_TASKS, new ArrayList<>(Arrays.asList(
                new HITLTask("c1", "bash_start", cmd("mvn test"), null),
                new HITLTask("c2", "bash_wait", cmd("ignored"), null))));

        List<HitlCard> cards = HitlCard.list(session);
        assertTrue(cards.get(0).isBash());
        assertEquals("mvn test", cards.get(0).getCommand());
        assertFalse(cards.get(1).isBash(), "bash_wait 只向已启动的会话写入，不当作命令卡");
    }

    @Test
    void noPinnableArgMeansNoRule() {
        assertNull(HarnessEngine.rememberedRuleOf("bash", null));
        assertNull(HarnessEngine.rememberedRuleOf("bash", Collections.<String, Object>emptyMap()));
        Map<String, Object> blank = new HashMap<>();
        blank.put("command", "   ");
        assertNull(HarnessEngine.rememberedRuleOf("bash", blank));
        Map<String, Object> other = new HashMap<>();
        other.put("url", "https://x");
        assertNull(HarnessEngine.rememberedRuleOf("webfetch", other), "不得退化为整工具放行");
    }

    @Test
    void filePathIsPinnedAndRulesDedupe() {
        Map<String, Object> a = new HashMap<>();
        a.put("file_path", "src/A.java");
        PermissionRule r1 = HarnessEngine.rememberedRuleOf("write", a);
        PermissionRule r2 = HarnessEngine.rememberedRuleOf("write", new HashMap<>(a));
        assertEquals(r1, r2, "同一条批准应可去重");
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void concurrentUpdatesDoNotLoseRules() throws Exception {
        HarnessOptions options = new HarnessOptions(".", ".solon/");
        int n = 100;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        for (int i = 0; i < n; i++) {
            final int k = i;
            pool.execute(() -> {
                try {
                    start.await();
                    options.updatePermissionContext(ctx -> ctx.addRule(PermissionRule.allowExact("bash", "command", "echo " + k)));
                } catch (Throwable e) {
                    err.set(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertNull(err.get());
        assertEquals(n, options.getPermissionContext().rules().size());
    }

    @Test
    void rememberedRulesAreSessionScopedDedupedAndBounded() {
        AgentSession a = InMemoryAgentSession.of();
        AgentSession b = InMemoryAgentSession.of();
        PermissionRule rule = HarnessEngine.rememberedRuleOf("bash", cmd("mvn clean"));

        RememberedApprovals.remember(a, rule);
        RememberedApprovals.remember(a, HarnessEngine.rememberedRuleOf("bash", cmd("mvn clean")));
        assertEquals(1, RememberedApprovals.of(a).size(), "同一条只记一次");
        assertTrue(RememberedApprovals.of(b).isEmpty(), "其它会话 / 用户不受影响");

        // 叠加到 ask 规则上：本会话放行，其它会话仍需审批
        PermissionContext base = PermissionContext.create().addRule(PermissionRule.ask("bash"));
        assertNotEquals(evalBash(base, "mvn clean"),
                evalBash(base.addRules(RememberedApprovals.of(a)), "mvn clean"));

        for (int i = 0; i < RememberedApprovals.MAX_RULES + 10; i++) {
            RememberedApprovals.remember(a, HarnessEngine.rememberedRuleOf("bash", cmd("echo " + i)));
        }
        assertEquals(RememberedApprovals.MAX_RULES, RememberedApprovals.of(a).size(), "有上限，淘汰最早的");

        RememberedApprovals.remember(null, rule); // 无会话：不记忆、不抛异常
    }
}
