package org.noear.solon.ai.harness.session.loop;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * cron 可触发性校验：loop 任务创建（loop_add、/loop cron:、宿主 Web 端）与注册（加载/更新/启用）
 * 共用的唯一校验入口，钉住它对各类输入的判定与文案。
 */
class LoopCronTest {

    @Test
    void sevenFieldFutureCronReturnsThatMoment() {
        LocalDateTime future = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        String cron = String.format("0 %d %d %d %d ? %d",
                future.getMinute(), future.getHour(),
                future.getDayOfMonth(), future.getMonthValue(), future.getYear());

        Date next = LoopCron.requireNextFireTime(cron);

        assertNotNull(next);
        assertEquals(future, LocalDateTime.ofInstant(next.toInstant(), ZoneId.systemDefault()));
    }

    /** 6 位（省略年份）同样被解析器接受 —— 错误文案里的「6 或 7 位」据此而写 */
    @Test
    void sixFieldCronIsAccepted() {
        assertNotNull(LoopCron.requireNextFireTime("0 0 18 * * ?"));
    }

    @Test
    void expiredYearHasNoFireTime() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LoopCron.requireNextFireTime("0 0 18 5 9 ? 2020"));
        assertTrue(e.getMessage().contains("无有效触发时刻"), e.getMessage());
    }

    /** 语法合法但日期永不存在（2 月 31 日）：同样没有触发时刻，不能当成语法错误 */
    @Test
    void nonExistentDateHasNoFireTime() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LoopCron.requireNextFireTime("0 0 18 31 2 ? 2030"));
        assertTrue(e.getMessage().contains("无有效触发时刻"), e.getMessage());
    }

    @Test
    void garbageExpressionReportsParseFailure() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LoopCron.requireNextFireTime("every day at 6"));
        assertTrue(e.getMessage().contains("无法解析"), e.getMessage());
    }

    /** 日与周同时给出具体值（都不是 ?）属非法 —— 这是 LLM 手写 7 位 cron 的高频错点 */
    @Test
    void dayAndWeekBothSpecifiedReportsParseFailure() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LoopCron.requireNextFireTime("0 0 18 * * * *"));
        assertTrue(e.getMessage().contains("无法解析"), e.getMessage());
    }
}
