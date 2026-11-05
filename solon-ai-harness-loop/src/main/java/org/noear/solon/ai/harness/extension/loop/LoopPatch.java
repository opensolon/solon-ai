/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.loop;

import org.noear.snack4.ONode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 修改定时任务的补丁（{@link LoopExtension#update(String, String, LoopPatch)} 的入参）。
 *
 * <pre>{@code
 * // 请求参数原样传入：null = 保持原值，宿主不必写「传了用新值、没传沿用旧值」的合并代码
 * loop.update(sessionId, taskId, LoopPatch.create()
 *         .prompt(prompt)
 *         .everyMinutes(intervalMinutes)
 *         .cron(cron)
 *         .runNow(runNow));
 * }</pre>
 *
 * <ul>
 *   <li>{@link #prompt}：null 或空白 = 保持；</li>
 *   <li>{@link #everyMinutes}：null = 保持；越界（[1, 1440] 之外）抛 {@link IllegalArgumentException}；</li>
 *   <li>{@link #cron}：null = 保持；<b>空串 = 清除 cron、回到固定间隔</b>；新 cron 不可触发时抛异常；</li>
 *   <li>{@link #runNow}：null = 保持；</li>
 *   <li>{@link #extra}：只改列出的键，value 为 null 表示删除该键。</li>
 * </ul>
 *
 * <p>任务身份、启停、迭代、最近结果与一次性属性都随之保留；任务正在执行时也可修改
 * （新版本不会与在途那一轮并发，本轮结果记到新版本上）。</p>
 *
 * @author noear
 * @since 4.2.1
 */
public final class LoopPatch {
    private String prompt;
    private Integer intervalMinutes;
    private String cron;
    private Boolean runNow;
    private final Map<String, ONode> extras = new LinkedHashMap<>();

    private LoopPatch() {
    }

    public static LoopPatch create() {
        return new LoopPatch();
    }

    public LoopPatch prompt(String prompt) {
        this.prompt = prompt == null || prompt.trim().isEmpty() ? null : prompt.trim();
        return this;
    }

    public LoopPatch everyMinutes(Integer minutes) {
        this.intervalMinutes = minutes;
        return this;
    }

    public LoopPatch cron(String cron) {
        this.cron = cron == null ? null : cron.trim();
        return this;
    }

    public LoopPatch runNow(Boolean runNow) {
        this.runNow = runNow;
        return this;
    }

    public LoopPatch extra(String key, ONode value) {
        LoopTask.requireExtraKey(key);
        extras.put(key, value); // null 值 = 删除，保留占位
        return this;
    }

    // ==================== 包内：应用到当前版本 ====================

    /**
     * 基于当前版本产出新版本。
     *
     * @throws IllegalArgumentException 间隔越界，或新 cron 无法解析/已无未来触发时刻
     */
    LoopTask applyTo(LoopTask current) {
        int interval = intervalMinutes == null ? current.getIntervalMinutes() : LoopSpec.requireInterval(intervalMinutes);
        String cronVal = cron == null ? current.getCron() : (cron.isEmpty() ? null : cron);
        if (cron != null && !cron.isEmpty()) {
            LoopCron.requireNextFireTime(cron);
        }

        LoopTask next = current.copyWithUpdate(
                prompt == null ? current.getPrompt() : prompt,
                interval,
                cronVal,
                runNow);
        for (Map.Entry<String, ONode> e : extras.entrySet()) {
            next.putExtra(e.getKey(), e.getValue());
        }
        return next;
    }
}
