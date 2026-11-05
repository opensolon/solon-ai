package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.noear.solon.flow.FlowContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goal 投影门面契约：
 * 1. 字段名解析/写入只在 harness 内部发生，宿主不再拼投影 Map；
 * 2. GoalState.toMap/fromMap 与本门面双向一致（字段集不漂移）；
 * 3. 无投影 / 缺 status / 状态名非法 一律返回 null，不抛异常。
 */
class GoalProjectionTest {

    @Test
    void roundTripWithGoalState() {
        GoalState gs = new GoalState("全部测试通过", 1000);
        gs.setMaxIterations(5);
        gs.setMaxDurationMs(60000);
        gs.setCurrentIteration(2);
        gs.updateLastExecution("2 passed");

        Map<String, Object> map = gs.toMap();
        GoalProjection projection = GoalProjection.from((Object) map);

        assertTrue(projection != null);
        assertEquals(gs.getId(), projection.getGoalId());
        assertEquals("全部测试通过", projection.getCondition());
        assertEquals(GoalState.Status.PURSUING, projection.getStatus());
        assertTrue(projection.isActive());
        assertFalse(projection.isTerminal());
        assertEquals(1000, projection.getMaxTokens());
        assertEquals(5, projection.getMaxIterations());
        assertEquals(60000, projection.getMaxDurationMs());
        assertEquals(2, projection.getCurrentIteration());
        assertEquals("2 passed", projection.getLastResult());
        assertTrue(projection.getLastExecutedAt() > 0);
    }

    @Test
    void fromMapIsSymmetric() {
        GoalState gs = new GoalState("obj", 100);
        gs.pause();

        // 门面 → Map → 领域对象
        GoalProjection projection = GoalProjection.ofState(gs);
        GoalState restored = GoalState.fromMap(projection.toMap());

        assertTrue(restored != null);
        assertEquals(gs.getId(), restored.getId());
        assertEquals(gs.getCondition(), restored.getCondition());
        assertEquals(GoalState.Status.PAUSED, restored.getStatus());
        assertEquals(gs.getMaxTokens(), restored.getMaxTokens());
    }

    @Test
    void missingStatusReturnsNull() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("objective", "no status");
        assertNull(GoalProjection.from((Object) map));
    }

    @Test
    void unknownStatusNameReturnsNullInsteadOfThrowing() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", "TOTALLY_BOGUS");
        assertNull(GoalProjection.from((Object) map));
    }

    @Test
    void nonMapProjectionReturnsNull() {
        assertNull(GoalProjection.from((Object) "just a string"));
        assertNull(GoalProjection.from((Object) null));
        assertNull(GoalProjection.from((FlowContext) null));
    }

    @Test
    void writeToAndReadFromFlowContext() {
        FlowContext context = FlowContext.fromJson("{}");
        GoalProjection.builder()
                .goalId("g-1")
                .condition("完成重构")
                .status(GoalState.Status.PURSUING)
                .maxTokens(2000)
                .maxIterations(3)
                .build()
                .writeTo(context);

        GoalProjection read = GoalProjection.from(context);
        assertTrue(read != null);
        assertEquals("g-1", read.getGoalId());
        assertEquals("完成重构", read.getCondition());
        assertTrue(read.isActive());
        assertEquals(2000, read.getMaxTokens());

        assertEquals(GoalState.Status.PURSUING, GoalProjection.statusOf(context));
    }

    /** 迁移场景：按历史状态名填充，非法名不得产出半残投影。 */
    @Test
    void builderStatusNameToleratesBadInput() {
        GoalProjection ok = GoalProjection.builder().statusName("ACHIEVED").build();
        assertEquals(GoalState.Status.ACHIEVED, ok.getStatus());
        assertTrue(ok.isTerminal());

        GoalProjection bad = GoalProjection.builder().statusName("NOPE").build();
        assertNull(bad.getStatus());
        assertFalse(bad.isActive());
        assertFalse(bad.isTerminal());
    }

    /** 宿主只读探测：启动恢复判定不再比对状态名字符串。 */
    @Test
    void statusOfReflectsActiveAndTerminal() {
        FlowContext pursuing = FlowContext.fromJson("{}");
        GoalProjection.builder().status(GoalState.Status.PURSUING).build().writeTo(pursuing);
        assertTrue(GoalProjection.statusOf(pursuing).isActive());

        FlowContext achieved = FlowContext.fromJson("{}");
        GoalProjection.builder().status(GoalState.Status.ACHIEVED).build().writeTo(achieved);
        assertTrue(GoalProjection.statusOf(achieved).isTerminal());
        assertFalse(GoalProjection.statusOf(achieved).isActive());
    }
}
