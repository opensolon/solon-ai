package org.noear.solon.ai.harness.session.goal;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.builtin.GoalCommand;
import org.noear.solon.ai.harness.session.goal.validate.BuildPassValidator;
import org.noear.solon.ai.harness.session.goal.validate.TestsPassValidator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goal 子系统的边界行为：状态迁移、扩展装配、调度契约与回执格式化。
 *
 * <p>GoalScheduler 依赖 solon 容器 JobManager 单例，此处只测其纯函数部分
 * （jobName / sanitize）与构造契约，实际调度注册需容器环境。</p>
 */
class GoalComponentsTest {

    /** 构造 GoalEngine：桥负责 sessionId → AgentSession 解析，null 桥表示无解析能力。 */
    private static GoalEngine newGoalEngine(
            org.noear.solon.ai.agent.AgentSessionProvider provider) {
        org.noear.solon.ai.harness.runtime.ExecutionBridge bridge = provider == null
                ? null
                : new org.noear.solon.ai.harness.runtime.ExecutionBridge(provider);
        return new GoalEngine(bridge, null, 3, 5, false);
    }

    // ========== GoalState.setStatus ==========

    @Test
    void setStatusReplacesValue() {
        GoalState gs = new GoalState("做事", 0);
        assertEquals(GoalState.Status.PURSUING, gs.getStatus());

        gs.setStatus(GoalState.Status.ACHIEVED);
        assertEquals(GoalState.Status.ACHIEVED, gs.getStatus());

        gs.setStatus(GoalState.Status.PAUSED);
        assertEquals(GoalState.Status.PAUSED, gs.getStatus());
    }

    @Test
    void statusActiveFlag() {
        GoalState gs = new GoalState("做事", 0);
        assertTrue(gs.getStatus().isActive());

        gs.setStatus(GoalState.Status.ACHIEVED);
        assertFalse(gs.getStatus().isActive());

        gs.setStatus(GoalState.Status.BLOCKED);
        assertFalse(gs.getStatus().isActive());
    }

    // ========== GoalExtension ==========

    @Test
    void goalExtensionExposesTalent() {
        GoalEngine goalEngine = newGoalEngine(null);
        GoalExtension ext = new GoalExtension(goalEngine, null);

        assertNotNull(ext.getGoalTalent());
        // 同一实例被复用（每次 configure 不新建）
        assertSame(ext.getGoalTalent(), ext.getGoalTalent());
    }

    @Test
    void goalExtensionConfiguresOnlyMainAgent() throws Exception {
        GoalEngine goalEngine = newGoalEngine(null);
        GoalExtension ext = new GoalExtension(goalEngine, null);
        org.noear.solon.ai.agent.react.ReActAgent.Builder builder =
                new org.noear.solon.ai.agent.react.ReActAgent.Builder(chatModel());

        // 非主代理：不注册 talent
        ext.configure(null, "code", builder);
        assertFalse(defaultTalents(builder).contains(ext.getGoalTalent()));

        // 主代理：注册 talent
        ext.configure(null, org.noear.solon.ai.harness.agent.AgentDefinition.AGENT_MAIN, builder);
        assertTrue(defaultTalents(builder).contains(ext.getGoalTalent()));
    }

    private static java.util.List<org.noear.solon.ai.chat.talent.Talent> defaultTalents(
            org.noear.solon.ai.agent.react.ReActAgent.Builder builder) throws Exception {
        Field f = org.noear.solon.ai.agent.react.ReActAgent.Builder.class.getDeclaredField("config");
        f.setAccessible(true);
        Object config = f.get(builder);
        Method m = config.getClass().getMethod("getDefaultTalents");
        Object v = m.invoke(config);
        return v == null ? Collections.emptyList() : new java.util.ArrayList<>((java.util.Collection<org.noear.solon.ai.chat.talent.Talent>) v);
    }

    private static org.noear.solon.ai.chat.ChatModel chatModel() {
        org.noear.solon.ai.chat.ChatConfig c = new org.noear.solon.ai.chat.ChatConfig();
        c.setName("goal-test");
        c.setModel("goal-test-model");
        c.setApiUrl("https://example.invalid/v1");
        c.setApiKey("k");
        return org.noear.solon.ai.chat.ChatModel.of(c);
    }

    // ========== GoalScheduler 纯函数 ==========

    @Test
    void jobNameIncludesWorkspacePrefix() throws Exception {
        GoalScheduler s = new GoalScheduler(newGoalEngine(null), "ws-1");
        Method m = GoalScheduler.class.getDeclaredMethod("jobName", String.class);
        m.setAccessible(true);

        assertEquals("goal-ws-1:sess-1", m.invoke(s, "sess-1"));
    }

    @Test
    void jobNameWithoutWorkspaceHasNoPrefix() throws Exception {
        GoalScheduler s = new GoalScheduler(newGoalEngine(null));
        Method m = GoalScheduler.class.getDeclaredMethod("jobName", String.class);
        m.setAccessible(true);

        assertEquals("goal-sess-1", m.invoke(s, "sess-1"));
    }

    @Test
    void sanitizeKeepsSafeCharsOnly() throws Exception {
        Method m = GoalScheduler.class.getDeclaredMethod("sanitize", String.class);
        m.setAccessible(true);

        // 字母数字与 - _ . 保留，其余替换为 _
        assertEquals("a-b_c.d", m.invoke(null, "a-b_c.d"));
        assertEquals("a_b_c", m.invoke(null, "a/b\\c"));
        assertEquals("a_b", m.invoke(null, "a b"));
        // 全被替换时退化为 ws
        assertEquals("ws", m.invoke(null, "///"));
    }

    @Test
    void schedulerTracksNothingInitially() {
        // hasScheduledJobs 只看内存登记集合，不需要容器
        GoalScheduler s = new GoalScheduler(newGoalEngine(null));
        assertFalse(s.hasScheduledJobs());
    }

    @Test
    void unscheduleNullIsSafe() {
        GoalScheduler s = new GoalScheduler(newGoalEngine(null));
        s.unschedule(null);  // 不抛
        s.stopAll();         // 空集合，不抛
        assertFalse(s.hasScheduledJobs());
    }

    // ========== GoalCommand 回执格式化 ==========

    private static Object invokeStatic(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = GoalCommand.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    @Test
    void formatElapsedUnderOneMinute() throws Exception {
        assertEquals("0s", invokeStatic("formatElapsed", new Class<?>[]{long.class}, 0L));
        assertEquals("59s", invokeStatic("formatElapsed", new Class<?>[]{long.class}, 59L));
    }

    @Test
    void formatElapsedMinutes() throws Exception {
        assertEquals("1m 0s", invokeStatic("formatElapsed", new Class<?>[]{long.class}, 60L));
        assertEquals("2m 30s", invokeStatic("formatElapsed", new Class<?>[]{long.class}, 150L));
        assertEquals("59m 59s", invokeStatic("formatElapsed", new Class<?>[]{long.class}, 3599L));
    }

    @Test
    void formatElapsedHours() throws Exception {
        assertEquals("1h 0m", invokeStatic("formatElapsed", new Class<?>[]{long.class}, 3600L));
        assertEquals("2h 30m", invokeStatic("formatElapsed", new Class<?>[]{long.class}, 9000L));
    }

    @Test
    void statusColorByState() throws Exception {
        String achieved = (String) invokeStatic("statusColor",
                new Class<?>[]{GoalState.Status.class}, GoalState.Status.ACHIEVED);
        String blocked = (String) invokeStatic("statusColor",
                new Class<?>[]{GoalState.Status.class}, GoalState.Status.BLOCKED);
        String paused = (String) invokeStatic("statusColor",
                new Class<?>[]{GoalState.Status.class}, GoalState.Status.PAUSED);
        String active = (String) invokeStatic("statusColor",
                new Class<?>[]{GoalState.Status.class}, GoalState.Status.PURSUING);

        // 达成绿、阻塞/限额黄、暂停青、其余品红
        assertTrue(achieved.contains("32"));
        assertTrue(blocked.contains("33"));
        assertTrue(paused.contains("36"));
        assertTrue(active.contains("35"));
    }

    @Test
    void truncateFlattensAndClips() throws Exception {
        assertEquals("-", invokeStatic("truncate", new Class<?>[]{String.class, int.class}, null, 10));
        // 换行压成空格
        assertEquals("a b", invokeStatic("truncate", new Class<?>[]{String.class, int.class}, "a\nb", 10));
        // 超长截断加省略号
        assertEquals("abcde...", invokeStatic("truncate", new Class<?>[]{String.class, int.class}, "abcdefgh", 5));
        // 等长不截断
        assertEquals("abcde", invokeStatic("truncate", new Class<?>[]{String.class, int.class}, "abcde", 5));
    }

    // ========== 校验器 runCommand ==========

    @Test
    void buildValidatorRunsCommandInWorkspace() throws Exception {
        BuildPassValidator v = new BuildPassValidator();
        // workspace 为空时放行（不阻塞目标推进）
        assertTrue(v instanceof org.noear.solon.ai.harness.session.goal.validate.GoalValidator);
    }

    @Test
    void testsValidatorRunsCommandInWorkspace() {
        TestsPassValidator v = new TestsPassValidator();
        assertTrue(v instanceof org.noear.solon.ai.harness.session.goal.validate.GoalValidator);
    }

    // ========== GoalEngine.ChainValidator ==========

    @Test
    void validationResultFactories() {
        assertTrue(org.noear.solon.ai.harness.session.goal.validate.GoalValidator.ValidationResult
                .passed().isPassed());
        org.noear.solon.ai.harness.session.goal.validate.GoalValidator.ValidationResult failed =
                org.noear.solon.ai.harness.session.goal.validate.GoalValidator.ValidationResult
                        .failed("构建失败");
        assertFalse(failed.isPassed());
        assertEquals("构建失败", failed.detail());
    }

    // ========== GoalEngine.sessionOf ==========

    @Test
    void sessionOfReturnsNullWithoutBridge() {
        // 桥缺失表示引擎无会话解析能力（不发消息），应返回 null 而非抛错
        assertNull(newGoalEngine(null).sessionOf("any"));
    }

    @Test
    void sessionOfReturnsSessionViaBridge() {
        GoalEngine engine = newGoalEngine(InMemoryAgentSession::of);
        AgentSession s = engine.sessionOf("s-1");
        assertNotNull(s);
        assertEquals("s-1", s.getSessionId());
    }
}
