package org.noear.solon.ai.harness.extension.loop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LoopTalent（loop_ 工具族）单元测试（直接跑在只在内存、无调度的真实调度器上，不用桩）
 */
class LoopTalentTest {
    private static final DateTimeFormatter AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private LoopScheduler ops;
    private LoopTalent talent;

    @BeforeEach
    void setUp() {
        ops = scheduler(LoopExtension.DEFAULT_MAX_TASKS);
        talent = new LoopTalent(ops);
    }

    private static LoopScheduler scheduler(int maxTasks) {
        return LoopExtension.builder().jobManager(null).defaultExecutor(false).maxTasks(maxTasks)
                .build().scheduler();
    }

    /** 本会话唯一（或最后加入）的任务。 */
    private LoopTask last(String sessionId) {
        java.util.List<LoopTask> list = ops.list(sessionId);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    /** 生成一个未来时刻的 7 位 cron（带年份，只命中一次），避免固定日期随时间腐烂 */
    private static String futureOneShotCron(LocalDateTime time) {
        return String.format("0 %d %d %d %d ? %d",
                time.getMinute(), time.getHour(),
                time.getDayOfMonth(), time.getMonthValue(), time.getYear());
    }

    // ==================== loop_add ====================

    @Test
    void loopAddUsesDefaults() {
        String result = talent.loopAdd("check deployment", null, null, null, null, null, "session-1");

        LoopTask task = last("session-1");
        assertNotNull(task);
        assertEquals("check deployment", task.getPrompt());
        assertEquals(5, task.getIntervalMinutes());
        assertFalse(task.isRunNow());
        assertFalse(task.isOneShot());
        assertTrue(result.contains("taskId"));
        assertTrue(result.contains("serverNow"));
        assertTrue(result.contains("timezone"));
    }

    @Test
    void loopAddPassesCronAndRunNow() {
        String result = talent.loopAdd("finish migration", null, 15, "0 */15 * * * ? *", null, true, "session-2");

        LoopTask task = last("session-2");
        assertTrue(result.contains("taskId"));
        assertEquals("0 */15 * * * ? *", task.getCron());
        assertTrue(task.isRunNow());
        assertTrue(result.contains("nextFireTime"), "cron 模式回写首次触发时刻");
    }

    @Test
    void loopAddAtConvertsToCronAndForcesOneShot() {
        LocalDateTime future = LocalDateTime.now().plusHours(3).withSecond(0).withNano(0);
        String result = talent.loopAdd("evening report", future.format(AT_FORMAT), null, null, null, null, "session-1");

        LoopTask task = last("session-1");
        assertNotNull(task);
        assertTrue(task.isOneShot());
        assertTrue(task.isCronMode());
        assertEquals(futureOneShotCron(future), task.getCron());
        assertTrue(result.contains("\"oneShot\": true"));
        assertTrue(result.contains("nextFireTime"));
    }

    @Test
    void loopAddRejectsPastAt() {
        String past = LocalDateTime.now().minusHours(1).format(AT_FORMAT);
        assertTrue(talent.loopAdd("late task", past, null, null, null, null, "session-1").startsWith("ERROR:"));
        assertNull(last("session-1"));
    }

    @Test
    void loopAddRejectsMalformedAt() {
        assertTrue(talent.loopAdd("bad at", "2026/09/05 18:00", null, null, null, null, "session-1").startsWith("ERROR:"));
        assertNull(last("session-1"));
    }

    @Test
    void loopAddCronOneShot() {
        String cron = futureOneShotCron(LocalDateTime.now().plusDays(1).withSecond(0).withNano(0));
        String result = talent.loopAdd("evening report", null, null, cron, true, null, "session-1");

        LoopTask task = last("session-1");
        assertNotNull(task);
        assertTrue(task.isOneShot());
        assertEquals(cron, task.getCron());
        assertTrue(result.contains("\"oneShot\": true"));
    }

    @Test
    void loopAddRejectsExpiredCron() {
        String result = talent.loopAdd("stale one-shot", null, null, "0 0 18 5 9 ? 2020", true, null, "session-1");
        assertTrue(result.startsWith("ERROR:"));
        assertTrue(result.contains("无有效触发时刻"), result);
        assertNull(last("session-1"));
    }

    @Test
    void loopAddRejectsUnparsableCron() {
        String result = talent.loopAdd("bad cron", null, null, "every day at 6", null, null, "session-1");
        assertTrue(result.startsWith("ERROR:"));
        assertTrue(result.contains("无法解析"), result);
        assertNull(last("session-1"));
    }

    @Test
    void loopAddRejectsOneShotWithoutCron() {
        assertTrue(talent.loopAdd("bad one-shot", null, 10, null, true, null, "session-1").startsWith("ERROR:"));
        assertNull(last("session-1"));
    }

    @Test
    void loopAddRejectsOneShotWithRunNow() {
        String future = LocalDateTime.now().plusHours(2).format(AT_FORMAT);
        assertTrue(talent.loopAdd("conflict", future, null, null, null, true, "session-1").startsWith("ERROR:"));
        assertNull(last("session-1"));
    }

    @Test
    void loopAddReportsSchedulerRejection() {
        talent = new LoopTalent(ops = scheduler(1));
        talent.loopAdd("first", null, 10, null, null, null, "session-1");
        String result = talent.loopAdd("over", null, 10, null, null, null, "session-1");
        assertTrue(result.startsWith("ERROR:"));
        assertTrue(result.contains("Max tasks reached"), result);
    }

    // ==================== loop_list ====================

    @Test
    void loopListReturnsTasksWithServerNow() {
        talent.loopAdd("task a", null, 10, null, null, null, "session-1");
        talent.loopAdd("task b", null, null, "0 */5 * * * ? *", null, null, "session-1");

        String result = talent.loopList("session-1");

        assertTrue(result.contains("task a"));
        assertTrue(result.contains("task b"));
        assertTrue(result.contains("\"total\": 2"));
        assertTrue(result.contains("intervalMinutes"));
        assertTrue(result.contains("cron"));
        assertTrue(result.contains("serverNow"));
    }

    @Test
    void loopListEmptySession() {
        assertTrue(talent.loopList("no-such-session").contains("\"total\": 0"));
    }

    // ==================== loop_control ====================

    @Test
    void loopControlStopRemovesTaskFromCurrentSession() {
        talent.loopAdd("check status", null, 10, null, null, null, "session-3");
        String taskId = last("session-3").getId();

        String result = talent.loopControl("stop", taskId, "session-3");

        assertTrue(result.contains("\"action\": \"stop\""));
        assertNull(ops.get("session-3", taskId));
    }

    @Test
    void loopControlStopReportsMissingTask() {
        assertTrue(talent.loopControl("stop", "missing", "session-4").startsWith("ERROR:"));
    }

    @Test
    void loopControlPauseResumeTogglesEnabled() {
        talent.loopAdd("heartbeat", null, 10, null, null, null, "session-5");
        String taskId = last("session-5").getId();

        assertTrue(talent.loopControl("pause", taskId, "session-5").contains("\"enabled\": false"));
        assertFalse(ops.get("session-5", taskId).isEnabled());

        assertTrue(talent.loopControl("resume", taskId, "session-5").contains("\"enabled\": true"));
        assertTrue(ops.get("session-5", taskId).isEnabled());

        talent.loopControl("resume", taskId, "session-5");
        assertTrue(ops.get("session-5", taskId).isEnabled(), "已是目标状态时不得反向切换");
    }

    @Test
    void loopControlPauseReportsMissingTask() {
        assertTrue(talent.loopControl("pause", "missing", "session-7").startsWith("ERROR:"));
    }

    @Test
    void loopControlTriggerSubmitsExecution() {
        talent.loopAdd("manual run", null, 10, null, null, null, "session-8");
        String taskId = last("session-8").getId();

        assertTrue(talent.loopControl("trigger", taskId, "session-8").contains("\"submitted\": true"));
    }

    @Test
    void loopControlTriggerRejectsDisabledTask() {
        talent.loopAdd("paused run", null, 10, null, null, null, "session-9");
        String taskId = last("session-9").getId();
        talent.loopControl("pause", taskId, "session-9");

        assertTrue(talent.loopControl("trigger", taskId, "session-9").startsWith("ERROR:"));
    }

    @Test
    void loopControlRejectsUnknownAction() {
        String result = talent.loopControl("restart", "any", "session-10");
        assertTrue(result.startsWith("ERROR:"));
        assertTrue(result.contains("stop/pause/resume/trigger"));
    }

    @Test
    void missingSessionIsRejected() {
        assertTrue(talent.loopAdd("p", null, null, null, null, null, null).startsWith("ERROR:"));
        assertTrue(talent.loopList("").startsWith("ERROR:"));
        assertTrue(talent.loopControl("stop", "x", null).startsWith("ERROR:"));
    }
}
