package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.session.goal.GoalExtension;
import org.noear.solon.ai.harness.session.queue.QueueExtension;
import org.noear.solon.ai.harness.session.steer.SteerExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HarnessExtensions} 装配聚合入口契约。
 *
 * <p>锁定三件事：①一次调用装齐三件套且命令注册完成；②重复调用幂等（不得二次 onAttach，
 * 否则 goal 会重复建调度器、命令重复注册刷 warn）；③句柄取回的是引擎上真正生效的实例。</p>
 *
 * <p>不建调度器：{@code scheduler(null)} 显式关闭，避免测试环境没有 Solon 容器时
 * {@code JobManager.getInstance()} 不可用。</p>
 */
class HarnessExtensionsTest {

    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        return HarnessEngine.of(workspace.toString(), null)
                .sessionProvider(InMemoryAgentSession::of)
                .build();
    }

    private static GoalExtension goalWithoutScheduler(String workspaceId) {
        // scheduler(null) 是显式关闭调度的三态标记，纯测试宿主必须用它
        return GoalExtension.builder(workspaceId).scheduler(null).build();
    }

    @Test
    void installDefaultsMountsAllThreeExtensions() {
        HarnessEngine engine = engine();

        HarnessExtensions.Handles handles = HarnessExtensions.installDefaults(engine, goalWithoutScheduler("ws-a"));

        assertNotNull(handles.steer(), "steer 句柄不可为空");
        assertNotNull(handles.queue(), "queue 句柄不可为空");
        assertNotNull(handles.goal(), "goal 句柄不可为空");

        // 句柄与引擎上的实例同源（宿主对句柄做配置必须作用到生效对象）
        assertSame(handles.steer(), engine.getExtension(SteerExtension.class).orElse(null));
        assertSame(handles.queue(), engine.getExtension(QueueExtension.class).orElse(null));
        assertSame(handles.goal(), engine.getExtension(GoalExtension.class).orElse(null));
    }

    @Test
    void installDefaultsRegistersDomainCommands() {
        HarnessEngine engine = engine();

        assertNull(engine.getCommandRegistry().find("steer"), "装配前不应有 /steer");
        assertNull(engine.getCommandRegistry().find("queue"), "装配前不应有 /queue");
        assertNull(engine.getCommandRegistry().find("goal"), "装配前不应有 /goal");

        HarnessExtensions.installDefaults(engine, goalWithoutScheduler("ws-b"));

        // 三件套的 onAttach 各自注册自己的命令；少装一个就是「功能静默不存在」
        assertNotNull(engine.getCommandRegistry().find("steer"), "/steer 未注册");
        assertNotNull(engine.getCommandRegistry().find("queue"), "/queue 未注册");
        assertNotNull(engine.getCommandRegistry().find("goal"), "/goal 未注册");
    }

    @Test
    void installDefaultsIsIdempotent() {
        HarnessEngine engine = engine();

        HarnessExtensions.Handles first = HarnessExtensions.installDefaults(engine, goalWithoutScheduler("ws-c"));
        HarnessExtensions.Handles second = HarnessExtensions.installDefaults(engine, goalWithoutScheduler("ws-c"));

        // 重复装配必须返回同一批实例：addExtension 不查重，二次 onAttach 会重复建调度器
        assertSame(first.steer(), second.steer(), "steer 被重复装配");
        assertSame(first.queue(), second.queue(), "queue 被重复装配");
        assertSame(first.goal(), second.goal(), "goal 被重复装配");

        long steerCount = engine.getExtensions().stream()
                .filter(e -> e instanceof SteerExtension).count();
        assertEquals(1, steerCount, "引擎上不应出现两个 steer 扩展");
    }

    @Test
    void installDefaultsReusesExistingExtension() {
        HarnessEngine engine = engine();
        SteerExtension preMounted = new SteerExtension();
        engine.addExtension(preMounted);

        HarnessExtensions.Handles handles = HarnessExtensions.installDefaults(engine, goalWithoutScheduler("ws-d"));

        assertSame(preMounted, handles.steer(), "已挂载的扩展必须复用，不得新建");
    }

    @Test
    void installDefaultsAllowsGoalAbsent() {
        HarnessEngine engine = engine();

        HarnessExtensions.Handles handles = HarnessExtensions.installDefaults(engine, (GoalExtension) null);

        assertNotNull(handles.steer());
        assertNotNull(handles.queue());
        assertNull(handles.goal(), "未装配 goal 时句柄为 null（宿主据此降级）");
        assertNull(engine.getExtension(GoalExtension.class).orElse(null));
    }

    @Test
    void hostConfiguredGoalIsMountedAsIs() {
        HarnessEngine engine = engine();
        GoalExtension configured = goalWithoutScheduler("ws-e");

        HarnessExtensions.Handles handles = HarnessExtensions.installDefaults(engine, configured);

        // 宿主传入的实例就是生效实例（预算等配置不得被聚合入口丢弃）
        assertSame(configured, handles.goal());
        assertSame(configured, engine.getExtension(GoalExtension.class).orElse(null));
    }

    @Test
    void nullEngineIsToleratedForDegradedHosts() {
        // 引擎尚未建成时的降级调用不应抛异常（返回的 goal 原样带回）
        GoalExtension goal = goalWithoutScheduler("ws-f");
        HarnessExtensions.Handles handles = HarnessExtensions.installDefaults(null, goal);

        assertNull(handles.steer());
        assertNull(handles.queue());
        assertSame(goal, handles.goal());
    }

    @Test
    void installDefaultsLeavesHostOwnedExtensionsAlone() {
        HarnessEngine engine = engine();
        HarnessExtension hostOwned = new AbsHarnessExtension() { };
        engine.addExtension(hostOwned);

        HarnessExtensions.installDefaults(engine, goalWithoutScheduler("ws-g"));

        // 聚合入口只承诺 harness 内置三件套，不驱逐也不重排宿主自有扩展
        assertTrue(engine.getExtensions().contains(hostOwned), "宿主扩展不应被动");
    }
}
