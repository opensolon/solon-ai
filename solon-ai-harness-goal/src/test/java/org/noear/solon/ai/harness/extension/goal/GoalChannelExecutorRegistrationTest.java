package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行器注册的通道化契约：
 *
 * <p>多入口宿主（CLI/Web/桌面）各注册一个执行器，认领边界由谓词显式给出——
 * 引擎可在不执行 handler 的前提下评估互斥性并告警；归属只由谓词决定，结论不穿透。</p>
 *
 * <p>观测口用包内方法而非日志捕抓：测试 classpath 无 SLF4J 后端（NOP），
 * ListAppender 方案会静默断言失败。</p>
 */
class GoalChannelExecutorRegistrationTest {

    private static GoalRuntime engine() {
        return new GoalRuntime((org.noear.solon.ai.harness.HarnessEngine) null,
                BudgetThresholds.defaults(), 3, 3, false);
    }

    // ========== 注册校验 ==========

    @Test
    void channelRegistrationRejectsBlankChannel() {
        GoalRuntime ge = engine();

        assertThrows(IllegalArgumentException.class,
                () -> ge.addExecutor("  ", id -> true, (id, p) -> null),
                "空白通道名应拒绝");

        ge.addExecutor("cli", id -> true, (id, p) -> null);
        assertEquals(1, ge.channelsClaiming("any").size(), "合法注册应生效");
    }

    @Test
    void nullPredicateClaimsAllButIsNotEvaluatedForOverlap() throws Exception {
        GoalRuntime ge = engine();
        ge.addExecutor("all", null, (id, p) -> TestOutcomes.ofText("from-all"));
        ge.addExecutor("web", id -> true, (id, p) -> TestOutcomes.ofText("from-web"));

        assertEquals(Collections.singletonList("web"), ge.channelsClaiming("any"),
                "channelsClaiming 只评估带谓词的注册");
        assertEquals("from-all", ge.execute("any", "p").getText(), "无谓词 = 认领全部，按注册顺序优先");
    }

    // ========== 互斥告警 ==========

    @Test
    void overlappingClaimsWarnExactlyOncePerCombination() throws Exception {
        GoalRuntime ge = engine();
        ge.addExecutor("cli", id -> id.startsWith("cli"), (id, p) -> TestOutcomes.ofText("from-cli"));
        ge.addExecutor("web", id -> id.contains("cli"), (id, p) -> TestOutcomes.ofText("from-web"));
        // 两个谓词都命中 "cli-1"：边界重叠

        GoalOutcome r1 = ge.execute("cli-1", "p");
        GoalOutcome r2 = ge.execute("cli-1", "p");

        assertEquals("from-cli", r1.getText(), "按注册顺序取首个认领者");
        assertEquals("from-cli", r2.getText());
        assertEquals(1, ge.overlapWarnCount(), "同一组合的重叠只告警一次（防批量路径刷屏）");

        // 换组合（仅 web 命中）不告警
        ge.execute("web-only", "p");
        assertEquals(1, ge.overlapWarnCount(), "单一命中不算重叠");
    }

    @Test
    void claimedExecutorResultIsFinalEvenIfNull() throws Exception {
        // 谓词即归属：命中后 handler 返回 null 是编程错误，按失败处理，不再穿透到下一注册者
        GoalRuntime ge = engine();
        ge.addExecutor("cli", id -> true, (id, p) -> null);
        ge.addExecutor("web", id -> true, (id, p) -> TestOutcomes.ofText("from-web"));

        assertTrue(ge.execute("s", "p").isFailed(), "首个认领者的结论即最终结论");
    }

    @Test
    void throwingPredicateIsSkippedNotFatal() throws Exception {
        GoalRuntime ge = engine();
        ge.addExecutor("bad", id -> {
            throw new IllegalStateException("boom");
        }, (id, p) -> TestOutcomes.ofText("bad"));
        ge.addExecutor("web", id -> true, (id, p) -> TestOutcomes.ofText("from-web"));

        assertEquals("from-web", ge.execute("s", "p").getText(), "谓词异常按不命中处理，不得击穿轮次");
        assertEquals(Collections.singletonList("web"), ge.channelsClaiming("s"),
                "评估口径与执行口径一致：坏谓词不进评估结果");
    }

    @Test
    void predicateEvaluatedOncePerRound() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        GoalRuntime ge = engine();
        ge.addExecutor("cli", id -> {
            calls.incrementAndGet();
            return true;
        }, (id, p) -> TestOutcomes.ofText("from-cli"));

        ge.execute("s", "p");

        assertEquals(1, calls.get(), "一轮只能评估一次谓词");
    }

    @Test
    void unclaimedFallsBackToDefaultExecutor() throws Exception {
        GoalRuntime ge = engine();
        ge.addExecutor("web", id -> id.startsWith("web-"), (id, p) -> TestOutcomes.ofText("from-web"));
        ge.setDefaultExecutor((id, p) -> TestOutcomes.ofText("fallback"));

        assertEquals("fallback", ge.execute("cli", "p").getText(), "无人认领时走兜底");
        assertEquals("from-web", ge.execute("web-1", "p").getText(), "宿主执行器优先于兜底");
    }

    // ========== 门面直通 ==========

    @Test
    void facadeChannelRegistrationReachesRuntime() {
        GoalExtension ext = GoalExtension.builder("ws-channel").jobManager(null).build();
        org.noear.solon.ai.harness.HarnessEngine.of("ws", "home")
                .sessionProvider((id, c) -> org.noear.solon.ai.agent.session.InMemoryAgentSession.of(id))
                .extensionAdd(ext)
                .build();

        GoalExtension returned = ext.addExecutor("desktop", id -> id.matches("\\d+"),
                (id, p) -> TestOutcomes.ofText("from-desktop"));

        assertSame(ext, returned, "门面保持链式");
        assertEquals(Collections.singletonList("desktop"), ext.getGoalRuntime().channelsClaiming("123456"));
    }

    @Test
    void facadeRegistrationBeforeAttachFailsFast() {
        GoalExtension ext = GoalExtension.builder("ws-early").jobManager(null).build();
        assertThrows(IllegalStateException.class,
                () -> ext.addExecutor("cli", id -> true, (id, p) -> null),
                "未挂接就注册属于装配顺序错误，必须当场失败而不是静默丢失");
    }

    @Test
    void jobManagerNullDisablesScheduling() {
        GoalExtension ext = GoalExtension.builder("ws-nosched").jobManager(null).build();
        org.noear.solon.ai.harness.HarnessEngine.of("ws", "home")
                .sessionProvider((id, c) -> org.noear.solon.ai.agent.session.InMemoryAgentSession.of(id))
                .extensionAdd(ext)
                .build();

        assertFalse(ext.hasScheduler());
        assertFalse(ext.hasActiveGoals());
    }
}
