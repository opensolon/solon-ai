package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行器注册的通道化契约（4.2.1）：
 *
 * <p>多入口宿主（CLI/Web/桌面）各注册一个执行器，认领边界互斥靠约定——
 * 写宽了就按注册顺序静默抢跑，此前只有一条无上下文的 debug 行。通道化注册
 * （{@code addTaskExecutor(channel, claim, handler)}）把认领条件显式交给引擎，
 * 引擎可在不执行 handler 的前提下静态评估互斥性并告警。</p>
 *
 * <p>观测口用包内方法而非日志捕抓：测试 classpath 无 SLF4J 后端（NOP），
 * ListAppender 方案会静默断言失败。</p>
 */
class GoalChannelExecutorRegistrationTest {

    private static GoalEngine engine() {
        return new GoalEngine((org.noear.solon.ai.harness.HarnessEngine) null,
                BudgetThresholds.defaults(), 3, 3, false);
    }

    // ========== 注册校验 ==========

    @Test
    void channelRegistrationRejectsBlankChannelAndNullPredicate() {
        GoalEngine ge = engine();

        assertThrows(IllegalArgumentException.class,
                () -> ge.addTaskExecutor("  ", id -> true, (id, p) -> null),
                "空白通道名应拒绝");

        assertThrows(IllegalArgumentException.class,
                () -> ge.addTaskExecutor("web", null, (id, p) -> null),
                "无谓词应拒绝（那是旧形态的职责，混用会退化回不可评估）");

        ge.addTaskExecutor("cli", id -> true, (id, p) -> null);
        assertEquals(1, ge.channelsClaiming("any").size(), "合法注册应生效");
    }

    @Test
    void legacyRegistrationIsNotEvaluatedForOverlap() {
        GoalEngine ge = engine();
        ge.addTaskExecutor((id, p) -> null);  // 旧形态：无谓词可评
        ge.addTaskExecutor("web", id -> true, (id, p) -> null);

        assertEquals(java.util.Collections.singletonList("web"), ge.channelsClaiming("any"),
                "channelsClaiming 只评估通道化注册，旧形态不得被重复计入");
    }

    // ========== 互斥告警 ==========

    @Test
    void overlappingClaimsWarnExactlyOncePerCombination() {
        GoalEngine ge = engine();
        ge.addTaskExecutor("cli", id -> id.startsWith("cli"), (id, p) ->
                org.noear.solon.ai.harness.runtime.RoundResult.ofText("from-cli"));
        ge.addTaskExecutor("web", id -> id.contains("cli"), (id, p) ->
                org.noear.solon.ai.harness.runtime.RoundResult.ofText("from-web"));
        // 两个谓词都命中 "cli-1"：边界重叠

        org.noear.solon.ai.harness.runtime.RoundResult r1 = ge.execute("cli-1", "p");
        org.noear.solon.ai.harness.runtime.RoundResult r2 = ge.execute("cli-1", "p");

        assertEquals("from-cli", r1.getFinalResult(), "仍按注册顺序取首个认领者（语义不变）");
        assertEquals("from-cli", r2.getFinalResult());
        assertEquals(1, ge.overlapWarnCount(),
                "同一组合的重叠只告警一次（防批量路径刷屏）");

        // 换组合（仅 web 命中）不告警
        ge.execute("web-only", "p");
        assertEquals(1, ge.overlapWarnCount(), "单一命中不算重叠");
    }

    @Test
    void predicateMatchDoesNotBypassHandlerNull() {
        // 谓词是预过滤不是权威判定：命中谓词后 handler 仍可返回 null（本次不认领），
        // 由下一个注册者接管——运行时端口暂不可用等场景依赖此回退。
        GoalEngine ge = engine();
        ge.addTaskExecutor("cli", id -> true, (id, p) -> null);
        ge.addTaskExecutor("web", id -> true, (id, p) ->
                org.noear.solon.ai.harness.runtime.RoundResult.ofText("from-web"));

        assertEquals("from-web", ge.execute("s", "p").getFinalResult(),
                "谓词命中但 handler 返 null 时应穿透到下一注册者");
    }

    @Test
    void throwingPredicateIsSkippedNotFatal() {
        GoalEngine ge = engine();
        ge.addTaskExecutor("bad", id -> { throw new IllegalStateException("boom"); },
                (id, p) -> org.noear.solon.ai.harness.runtime.RoundResult.ofText("bad"));
        ge.addTaskExecutor("web", id -> true, (id, p) ->
                org.noear.solon.ai.harness.runtime.RoundResult.ofText("from-web"));

        assertEquals("from-web", ge.execute("s", "p").getFinalResult(),
                "谓词异常按不命中处理，不得击穿轮次");
        assertEquals(java.util.Collections.singletonList("web"), ge.channelsClaiming("s"),
                "评估口径与执行口径一致：坏谓词不进评估结果，web 恒真仍命中");
    }

    @Test
    void predicateEvaluatedOncePerRound() {
        // 谓词只评估一次：互斥检测与认领路由复用同一份结果（宿主谓词常是正则，两次遍历=双倍开销）
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        GoalEngine ge = engine();
        ge.addTaskExecutor("cli", id -> { calls.incrementAndGet(); return true; },
                (id, p) -> org.noear.solon.ai.harness.runtime.RoundResult.ofText("from-cli"));

        ge.execute("s", "p");

        assertEquals(1, calls.get(), "一轮只能评估一次谓词");
    }

    // ========== 门面直通 ==========

    @Test
    void facadeChannelRegistrationReachesEngine() {
        GoalExtension ext = GoalExtension.builder("ws-channel").scheduler(null).build();
        org.noear.solon.ai.harness.HarnessEngine engine =
                org.noear.solon.ai.harness.HarnessEngine.of("ws", "home")
                        .sessionProvider(id -> org.noear.solon.ai.agent.session.InMemoryAgentSession.of(id))
                        .extensionAdd(ext)
                        .build();

        GoalExtension returned = ext.addTaskExecutor("desktop", id -> id.matches("\\d+"),
                (id, p) -> org.noear.solon.ai.harness.runtime.RoundResult.ofText("from-desktop"));

        assertEquals(ext, returned, "门面保持链式");
        assertEquals(java.util.Collections.singletonList("desktop"), ext.getGoalEngine().channelsClaiming("123456"));
    }

    // ========== 无调度器静默 no-op 的告警门闹（4.2.1）==========

    /**
     * scheduler(null) 后调度方法静默 no-op 是排障盲区：
     * 「调了 scheduleNow 却没跑」的第一嫌疑就是这里，必须一条 warn 指路且不刷屏。
     */
    @Test
    void scheduleCallsWithoutSchedulerTripWarnLatchExactlyOnce() {
        GoalExtension ext = GoalExtension.builder("ws-nosched").scheduler(null).build();
        org.noear.solon.ai.harness.HarnessEngine.of("ws", "home")
                .sessionProvider(id -> org.noear.solon.ai.agent.session.InMemoryAgentSession.of(id))
                .extensionAdd(ext)
                .build();

        ext.scheduleNow("s1");
        ext.resumeScheduleOnly("s1");
        ext.scheduleNow("s2");

        assertTrue(ext.noSchedulerWarned(), "首次调用即应触发门闩（后续不再重复）");
    }

    /**
     * {@code stopAll()} 也在门闩覆盖范围内（它是关停路径，误调同样表现为「调度没停」）。
     */
    @Test
    void stopAllWithoutSchedulerAlsoTripsWarnLatch() {
        GoalExtension ext = GoalExtension.builder("ws-stopall").scheduler(null).build();
        org.noear.solon.ai.harness.HarnessEngine.of("ws", "home")
                .sessionProvider(id -> org.noear.solon.ai.agent.session.InMemoryAgentSession.of(id))
                .extensionAdd(ext)
                .build();

        ext.stopAll();

        assertTrue(ext.noSchedulerWarned(), "stopAll 也应指路，不得静默");
    }

    /**
     * 摘除时 scheduler 为 null 是正常形态（纯测试宿主）：onDetach 直连内部字段，
     * 不走 stopAll() 门面，否则每次摘除都会报一条误导性的「没配调度器」。
     */
    @Test
    void detachWithoutSchedulerDoesNotWarn() {
        GoalExtension ext = GoalExtension.builder("ws-detach").scheduler(null).build();
        org.noear.solon.ai.harness.HarnessEngine engine =
                org.noear.solon.ai.harness.HarnessEngine.of("ws", "home")
                        .sessionProvider(id -> org.noear.solon.ai.agent.session.InMemoryAgentSession.of(id))
                        .extensionAdd(ext)
                        .build();

        ext.onDetach(engine);

        org.junit.jupiter.api.Assertions.assertFalse(ext.noSchedulerWarned(),
                "摘除时 scheduler 空属正常，不应告警");
    }

    // ========== builder 通道化（装配时形态，与运行期形态对称）==========

    @Test
    void builderChannelClaimsRegisteredInDeclarationOrder() {
        GoalExtension ext = GoalExtension.builder("ws-builder")
                .scheduler(null)
                .taskExecutor("cli", id -> id.startsWith("cli"), (id, p) ->
                        org.noear.solon.ai.harness.runtime.RoundResult.ofText("from-cli"))
                .taskExecutor("desktop", id -> id.matches("\\d+"), (id, p) ->
                        org.noear.solon.ai.harness.runtime.RoundResult.ofText("from-desktop"))
                .build();
        org.noear.solon.ai.harness.HarnessEngine.of("ws", "home")
                .sessionProvider(id -> org.noear.solon.ai.agent.session.InMemoryAgentSession.of(id))
                .extensionAdd(ext)
                .build();

        assertEquals(java.util.Collections.singletonList("cli"),
                ext.getGoalEngine().channelsClaiming("cli-1"), "builder 声明应生效");
        assertEquals(java.util.Collections.singletonList("desktop"),
                ext.getGoalEngine().channelsClaiming("123456"), "桌面通道互斥正确");
        assertEquals(0, ext.getGoalEngine().overlapWarnCount(), "合法互斥不应告警");
    }
}
