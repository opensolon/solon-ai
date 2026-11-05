/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.loop;

import org.noear.snack4.ONode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 新建定时任务的规格（{@link LoopExtension#schedule(String, LoopSpec)} 的入参）。
 *
 * <pre>{@code
 * loop.schedule(sessionId, LoopSpec.of("check ci").everyMinutes(10).runNow(true));
 * loop.schedule(sessionId, LoopSpec.of("daily report").cron("0 0 22 * * ?"));
 * loop.schedule(sessionId, LoopSpec.of("remind me").at(LocalDateTime.of(2026, 9, 5, 18, 0))); // 一次性
 * }</pre>
 *
 * <p><b>null 即未设置</b>：所有可选项都接受 null（和空串），宿主可以把请求参数原样传入，
 * 不必先判空再分支。调度方式优先级 {@code at > cron > everyMinutes}，缺省每 5 分钟。</p>
 *
 * <p>全部校验在 {@code schedule} 时一次完成，失败抛 {@link IllegalArgumentException}：
 * prompt 为空、间隔不在 [1, 1440] 分钟、at 不是未来时刻、一次性却没有 at/cron、一次性又要求立即执行、
 * cron 无法解析或已无未来触发时刻、扩展字段与核心字段同名。</p>
 *
 * @author noear
 * @since 4.2.1
 */
public final class LoopSpec {
    /** 缺省间隔（分钟）。 */
    public static final int DEFAULT_INTERVAL_MINUTES = 5;

    private final String prompt;
    private Integer intervalMinutes;
    private String cron;
    private LocalDateTime at;
    private boolean oneShot;
    private boolean runNow;
    private final Map<String, ONode> extras = new LinkedHashMap<>();

    private LoopSpec(String prompt) {
        this.prompt = prompt;
    }

    /** @param prompt 每轮交给 AI 执行的提示词（不可为空） */
    public static LoopSpec of(String prompt) {
        return new LoopSpec(prompt);
    }

    /** 固定间隔（分钟，[1, 1440]）；null = 缺省 5 分钟。 */
    public LoopSpec everyMinutes(Integer minutes) {
        this.intervalMinutes = minutes;
        return this;
    }

    /** cron 表达式（6 或 7 位）；null/空 = 不用 cron。 */
    public LoopSpec cron(String cron) {
        this.cron = cron == null || cron.trim().isEmpty() ? null : cron.trim();
        return this;
    }

    /** 一次性执行时刻（服务器本地时间）；设置后自动为一次性任务。null = 不用。 */
    public LoopSpec at(LocalDateTime at) {
        this.at = at;
        return this;
    }

    /** 是否一次性（调度触发一轮后自动注销；须配合 at 或 cron）。null = false。 */
    public LoopSpec oneShot(Boolean oneShot) {
        this.oneShot = Boolean.TRUE.equals(oneShot);
        return this;
    }

    /** 创建后立即执行首轮（仅固定间隔有效；与一次性互斥）。null = false。 */
    public LoopSpec runNow(Boolean runNow) {
        this.runNow = Boolean.TRUE.equals(runNow);
        return this;
    }

    /** 宿主扩展字段（插件不认识也原样持久化）；value 为 null 表示不写。 */
    public LoopSpec extra(String key, ONode value) {
        LoopTask.requireExtraKey(key);
        if (value == null) {
            extras.remove(key);
        } else {
            extras.put(key, value);
        }
        return this;
    }

    // ==================== 包内：校验并产出任务 ====================

    /** @throws IllegalArgumentException 规格不合法（cron 可触发性由调度器在同一入口校验） */
    LoopTask toTask() {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }

        String cronVal = cron;
        boolean oneShotVal = oneShot;
        int interval = DEFAULT_INTERVAL_MINUTES;

        if (at != null) {
            if (!at.isAfter(LocalDateTime.now())) {
                throw new IllegalArgumentException("at 时刻已过: " + at);
            }
            // 7 位 cron（带年份）天然只命中一次
            cronVal = String.format("0 %d %d %d %d ? %d",
                    at.getMinute(), at.getHour(), at.getDayOfMonth(), at.getMonthValue(), at.getYear());
            oneShotVal = true;
        } else if (cronVal == null && intervalMinutes != null) {
            interval = requireInterval(intervalMinutes);
        }

        if (oneShotVal) {
            if (cronVal == null) {
                throw new IllegalArgumentException("一次性任务必须提供 at 或 cron（固定间隔无法表达一次性语义）");
            }
            if (runNow) {
                throw new IllegalArgumentException("一次性任务不能同时要求立即执行（runNow）");
            }
        }

        LoopTask task = new LoopTask(prompt.trim(), interval, cronVal, runNow);
        task.setOneShot(oneShotVal);
        for (Map.Entry<String, ONode> e : extras.entrySet()) {
            task.putExtra(e.getKey(), e.getValue());
        }
        return task;
    }

    static int requireInterval(int minutes) {
        if (minutes < LoopTask.MIN_INTERVAL || minutes > LoopTask.MAX_INTERVAL) {
            throw new IllegalArgumentException("intervalMinutes 须在 [" + LoopTask.MIN_INTERVAL + ", "
                    + LoopTask.MAX_INTERVAL + "] 之间: " + minutes);
        }
        return minutes;
    }
}
