/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.loop;

import org.noear.java_cron.CronUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * cron 可触发性校验（创建入口与注册路径共用的唯一口径）。
 *
 * <p>与 SimpleScheduler 使用同一个解析器，保证校验语义与实际调度行为一致。</p>
 */
final class LoopCron {
    private static final DateTimeFormatter NOW_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private LoopCron() {
    }

    /**
     * 计算 cron 的下次触发时刻。
     *
     * @return 下次触发时刻（不为 null）
     * @throws IllegalArgumentException 表达式无法解析，或已无未来触发时刻
     */
    static Date requireNextFireTime(String cron) {
        Date nextTime;
        try {
            nextTime = CronUtils.getNextTime(cron, new Date());
        } catch (Exception e) {
            throw new IllegalArgumentException("cron 表达式无法解析: " + cron
                    + "（需 6 或 7 位：秒 分 时 日 月 周 [年]，日与周必须有一个为 ?）");
        }
        if (nextTime == null) {
            // 带年份的 cron 整体过期，或日期永不存在（如 2 月 31 日）
            throw new IllegalArgumentException("cron 无有效触发时刻: " + cron
                    + "（时刻已过或日期不存在，服务器当前时间 " + LocalDateTime.now().format(NOW_FORMAT) + "）");
        }
        return nextTime;
    }
}
