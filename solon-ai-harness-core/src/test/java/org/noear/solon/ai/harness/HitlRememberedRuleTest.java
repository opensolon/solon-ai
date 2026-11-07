package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.hitl.BashToolStrategy;
import org.noear.solon.ai.harness.hitl.SessionPermissions;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionRule;

import java.util.Collections;
import java.util.HashMap;
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

    private static Map<String, Object> cmd(String c) {
        Map<String, Object> m = new HashMap<>();
        m.put("command", c);
        return m;
    }

    private static String evalBash(PermissionContext ctx, String command) {
        return new BashToolStrategy().permissionContextSupplier(() -> ctx).evaluate(null, cmd(command));
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

        SessionPermissions.remember(a, rule);
        SessionPermissions.remember(a, HarnessEngine.rememberedRuleOf("bash", cmd("mvn clean")));
        assertEquals(1, SessionPermissions.of(a).size(), "同一条只记一次");
        assertTrue(SessionPermissions.of(b).isEmpty(), "其它会话 / 用户不受影响");

        // 叠加到 ask 规则上：本会话放行，其它会话仍需审批
        PermissionContext base = PermissionContext.create().addRule(PermissionRule.ask("bash"));
        assertNotEquals(evalBash(base, "mvn clean"),
                evalBash(base.addRules(SessionPermissions.of(a)), "mvn clean"));

        for (int i = 0; i < SessionPermissions.MAX_RULES + 10; i++) {
            SessionPermissions.remember(a, HarnessEngine.rememberedRuleOf("bash", cmd("echo " + i)));
        }
        assertEquals(SessionPermissions.MAX_RULES, SessionPermissions.of(a).size(), "有上限，淘汰最早的");

        SessionPermissions.remember(null, rule); // 无会话：不记忆、不抛异常
    }
}
