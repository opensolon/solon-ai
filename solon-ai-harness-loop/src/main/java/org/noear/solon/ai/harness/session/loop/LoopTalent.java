/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.session.loop;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

/**
 * Loop 管理工具 — 模型侧定时/循环任务全生命周期管理
 *
 * <ul>
 *   <li>{@code loop_add} — 新增定时/循环任务（支持一次性定时：at 或 cron+oneShot）</li>
 *   <li>{@code loop_list} — 查询当前会话任务</li>
 *   <li>{@code loop_control} — 控制任务（action=stop/pause/resume/trigger，合一入口）</li>
 * </ul>
 *
 * <p>开关由 {@link LoopExtension#setEnabled} 统一控制（扩展停用时引擎不再回调
 * {@code onRequestAssemble}，工具自然不挂；命令与已有定时任务不受影响）。
 * 所有响应附带 serverNow/timezone，供模型换算「晚上 6 点」这类相对时间语义。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public class LoopTalent extends AbsTalent {

    private static final DateTimeFormatter AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter NOW_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final LoopScheduler loopTasks;

    LoopTalent(LoopScheduler loopTasks) {
        this.loopTasks = loopTasks;
    }

    // ==================== loop_add ====================

    @ToolMapping(name = "loop_add",
            description = "新增当前会话的定时任务（或循环任务），成功后返回 taskId。" +
                    "三种调度方式（互斥，优先级：at > cron > intervalMinutes）：" +
                    "1) at：指定一次性执行时刻（如 '2026-09-05T18:00'，即晚上 6 点），到点执行一次后自动注销；" +
                    "2) cron：标准 7 位表达式（秒 分 时 日 月 周 年），配合 oneShot=true 表示只执行一次；" +
                    "3) intervalMinutes：固定间隔循环（默认 5 分钟）。" +
                    "所有时间均为服务器本地时区（见响应中的 serverNow/timezone）：" +
                    "用户说'晚上 6 点/明早 9 点执行一次'时，须依据 serverNow 推算具体日期后传 at；" +
                    "若语义是'之后每天都这样'则用 cron 且不传 oneShot。")
    public String loopAdd(
            @Param(name = "prompt", description = "任务触发后交给 AI 执行的提示词") String prompt,
            @Param(name = "at", description = "一次性执行时刻，服务器本地时间，格式 yyyy-MM-ddTHH:mm（如 2026-09-05T18:00）；提供后忽略 cron/intervalMinutes", required = false) String at,
            @Param(name = "intervalMinutes", description = "固定执行间隔（分钟），默认 5；提供 cron 时以 cron 为准", required = false) Integer intervalMinutes,
            @Param(name = "cron", description = "标准 7 位 cron 表达式（秒 分 时 日 月 周 年）；提供后使用 cron 调度，例如 0 */5 * * * ? *", required = false) String cron,
            @Param(name = "oneShot", description = "是否只执行一次（默认 false）。为 true 时任务到点执行一次后自动注销；指定 at 时强制为 true", required = false) Boolean oneShot,
            @Param(name = "runNow", description = "是否在创建后立即执行一次，默认 false；一次性定时任务不应传 true", required = false) Boolean runNow,
            String __sessionId) {
        if (Assert.isEmpty(__sessionId)) {
            return "ERROR: 无活跃会话，无法新增定时任务。";
        }
        if (Assert.isEmpty(prompt)) {
            return "ERROR: prompt 不能为空。";
        }

        boolean oneShotVal = oneShot != null && oneShot;
        String cronVal = cron;
        int interval = intervalMinutes != null ? intervalMinutes : 5;

        // ---- at 糖参数：转 7 位 cron（带年份，天然只命中一次），并强制 oneShot ----
        if (Assert.isNotEmpty(at)) {
            LocalDateTime atTime;
            try {
                atTime = LocalDateTime.parse(at.trim(), AT_FORMAT);
            } catch (Exception e) {
                return "ERROR: at 格式错误，应为 yyyy-MM-ddTHH:mm（如 2026-09-05T18:00）。";
            }
            if (!atTime.isAfter(LocalDateTime.now())) {
                return "ERROR: at 时刻已过（服务器当前时间 " + LocalDateTime.now().format(NOW_FORMAT)
                        + "），请与用户确认后改为未来的时刻。";
            }
            cronVal = String.format("0 %d %d %d %d ? %d",
                    atTime.getMinute(), atTime.getHour(),
                    atTime.getDayOfMonth(), atTime.getMonthValue(), atTime.getYear());
            oneShotVal = true;
            interval = 0;
        }

        if (oneShotVal) {
            if (Assert.isEmpty(cronVal)) {
                return "ERROR: oneShot=true 必须提供 at 或 cron（固定间隔无法表达一次性语义）。";
            }
            if (runNow != null && runNow) {
                return "ERROR: oneShot=true 与 runNow=true 互斥（一次性定时任务不应创建后立即执行）。";
            }
        }

        // ---- cron 可触发校验：此处先行是为了给模型更明确的错误反馈（调度器 schedule 内同一口径） ----
        Date nextFireTime = null;
        if (Assert.isNotEmpty(cronVal)) {
            try {
                nextFireTime = LoopCron.requireNextFireTime(cronVal);
            } catch (IllegalArgumentException e) {
                return "ERROR: " + e.getMessage() + "。";
            }
        }

        LoopTask task = new LoopTask(prompt, interval, cronVal, runNow != null && runNow);
        task.setOneShot(oneShotVal);

        try {
            loopTasks.schedule(__sessionId, task);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "ERROR: 新增定时任务失败: " + e.getMessage();
        }

        ONode root = okNode();
        root.set("taskId", task.getId());
        ONode schedule = root.getOrNew("schedule");
        if (task.isCronMode()) {
            schedule.set("cron", task.getCron());
            // 回写首次触发时刻，供模型核对自己写的 cron 是否真的是用户要的那个时间
            schedule.set("nextFireTime", LocalDateTime
                    .ofInstant(nextFireTime.toInstant(), ZoneId.systemDefault())
                    .format(NOW_FORMAT));
        } else {
            schedule.set("intervalMinutes", task.getIntervalMinutes());
        }
        root.set("oneShot", task.isOneShot());
        root.set("message", oneShotVal ? "一次性任务已安排，到点执行后自动注销"
                : "任务已新增，后续可用 loop_list 查询、loop_control(action=stop) 停止");
        return root.toJson();
    }

    // ==================== loop_list ====================

    @ToolMapping(name = "loop_list",
            description = "查询当前会话的全部定时/循环任务（含已暂停的），返回 JSON 列表。" +
                    "字段：taskId、prompt（截断）、type、调度表达式、enabled、running、lastExecutedAt、lastResult（截断）。")
    public String loopList(String __sessionId) {
        if (Assert.isEmpty(__sessionId)) {
            return "ERROR: 无活跃会话，无法查询定时任务。";
        }

        List<LoopTask> tasks = loopTasks.list(__sessionId);

        ONode root = okNode();
        ONode array = root.getOrNew("tasks").asArray();
        for (LoopTask t : tasks) {
            array.add(buildTaskNode(t));
        }
        root.set("total", tasks.size());
        return root.toJson();
    }

    // ==================== loop_control（stop/pause/resume/trigger 合一） ====================

    @ToolMapping(name = "loop_control",
            description = "控制当前会话的定时/循环任务，通过 action 指定动作（taskId 来自 loop_add 返回值或 loop_list）：" +
                    "stop：停止并删除任务（不可恢复）；" +
                    "pause：暂停任务（保留记录，停止调度）；" +
                    "resume：恢复已暂停的任务，重新开始调度；" +
                    "trigger：立即手动执行一轮（异步提交，不消耗 oneShot 一次性配额，结果稍后用 loop_list 查看）。")
    public String loopControl(
            @Param(name = "action", description = "控制动作：stop / pause / resume / trigger") String action,
            @Param(name = "taskId", description = "目标定时任务 ID") String taskId,
            String __sessionId) {
        String act = action == null ? "" : action.trim().toLowerCase();
        switch (act) {
            case "stop":
                return doStop(taskId, __sessionId);
            case "pause":
                return doPauseResume(taskId, __sessionId, false);
            case "resume":
                return doPauseResume(taskId, __sessionId, true);
            case "trigger":
                return doTrigger(taskId, __sessionId);
            default:
                return "ERROR: action 必须是 stop/pause/resume/trigger 之一，当前传入: " + action;
        }
    }

    private String doStop(String taskId, String __sessionId) {
        if (Assert.isEmpty(__sessionId)) {
            return "ERROR: 无活跃会话，无法停止定时任务。";
        }
        if (Assert.isEmpty(taskId)) {
            return "ERROR: taskId 不能为空。";
        }
        if (!loopTasks.remove(__sessionId, taskId)) {
            return "ERROR: 定时任务不存在，taskId=" + taskId + "（可用 loop_list 查询当前任务）";
        }

        ONode root = okNode();
        root.set("taskId", taskId);
        root.set("action", "stop");
        root.set("message", "任务已停止并删除");
        return root.toJson();
    }

    private String doPauseResume(String taskId, String __sessionId, boolean resume) {
        if (Assert.isEmpty(__sessionId)) {
            return "ERROR: 无活跃会话，无法" + (resume ? "恢复" : "暂停") + "定时任务。";
        }
        if (Assert.isEmpty(taskId)) {
            return "ERROR: taskId 不能为空。";
        }

        LoopTask task = loopTasks.get(__sessionId, taskId);
        if (task == null) {
            return "ERROR: 定时任务不存在，taskId=" + taskId + "（可用 loop_list 查询当前任务）";
        }
        if (task.isEnabled() != resume) {
            loopTasks.toggle(__sessionId, taskId);
        }

        ONode root = okNode();
        root.set("taskId", taskId);
        root.set("action", resume ? "resume" : "pause");
        root.set("enabled", resume);
        root.set("message", resume ? "任务已恢复调度"
                : "任务已暂停（保留记录，可 loop_control(action=resume) 恢复）");
        return root.toJson();
    }

    private String doTrigger(String taskId, String __sessionId) {
        if (Assert.isEmpty(__sessionId)) {
            return "ERROR: 无活跃会话，无法触发定时任务。";
        }
        if (Assert.isEmpty(taskId)) {
            return "ERROR: taskId 不能为空。";
        }

        LoopTask task = loopTasks.get(__sessionId, taskId);
        if (task == null) {
            return "ERROR: 定时任务不存在，taskId=" + taskId + "（可用 loop_list 查询当前任务）";
        }
        if (!task.isEnabled()) {
            return "ERROR: 任务已暂停，请先 loop_control(action=resume) 恢复后再触发。";
        }

        loopTasks.trigger(__sessionId, taskId);

        ONode root = okNode();
        root.set("taskId", taskId);
        root.set("action", "trigger");
        root.set("submitted", true);
        root.set("message", "已提交异步执行，稍后可用 loop_list 查看结果");
        return root.toJson();
    }

    // ==================== 辅助 ====================

    private static ONode buildTaskNode(LoopTask task) {
        ONode node = new ONode();
        node.set("taskId", task.getId());
        node.set("prompt", truncate(task.getPrompt(), 120));
        if (task.isCronMode()) {
            node.set("cron", task.getCron());
        } else {
            node.set("intervalMinutes", task.getIntervalMinutes());
        }
        node.set("oneShot", task.isOneShot());
        node.set("enabled", task.isEnabled());
        node.set("running", task.isRunning());
        if (task.getLastExecutedAt() != null) {
            node.set("lastExecutedAt", task.getLastExecutedAt().toString());
        }
        if (task.getLastResult() != null) {
            node.set("lastResult", truncate(task.getLastResult(), 300));
        }
        return node;
    }

    /** 统一响应骨架：附服务器当前时间与时区，供模型换算相对时间 */
    private static ONode okNode() {
        ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
        root.set("serverNow", LocalDateTime.now().format(NOW_FORMAT));
        root.set("timezone", ZoneId.systemDefault().getId());
        return root;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }
}
