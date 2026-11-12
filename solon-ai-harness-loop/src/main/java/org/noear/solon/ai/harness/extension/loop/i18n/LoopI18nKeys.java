/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.extension.loop.i18n;

import org.noear.solon.lang.Preview;

/**
 * loop 插件的文案 key（常量名 = key 转大写，"." 换成 "_"）
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class LoopI18nKeys {
    private LoopI18nKeys() {
    }

    // ---- 规格校验异常（LoopArgumentException）----
    public static final String LOOP_ERROR_PROMPT_EMPTY = "loop.error.prompt_empty";
    public static final String LOOP_ERROR_AT_PAST = "loop.error.at_past";
    public static final String LOOP_ERROR_ONE_SHOT_NO_SCHEDULE = "loop.error.one_shot_no_schedule";
    public static final String LOOP_ERROR_ONE_SHOT_RUN_NOW = "loop.error.one_shot_run_now";
    public static final String LOOP_ERROR_INTERVAL_RANGE = "loop.error.interval_range";
    public static final String LOOP_ERROR_CRON_UNPARSABLE = "loop.error.cron_unparsable";
    public static final String LOOP_ERROR_CRON_NO_FIRE_TIME = "loop.error.cron_no_fire_time";
    public static final String LOOP_ERROR_SESSION_EMPTY = "loop.error.session_empty";

    // ---- 工具返回值（"ERROR: " 前缀由代码补）----
    public static final String LOOP_TOOL_NO_SESSION_ADD = "loop.tool.no_session_add";
    public static final String LOOP_TOOL_NO_SESSION_LIST = "loop.tool.no_session_list";
    public static final String LOOP_TOOL_NO_SESSION_STOP = "loop.tool.no_session_stop";
    public static final String LOOP_TOOL_NO_SESSION_PAUSE = "loop.tool.no_session_pause";
    public static final String LOOP_TOOL_NO_SESSION_RESUME = "loop.tool.no_session_resume";
    public static final String LOOP_TOOL_NO_SESSION_TRIGGER = "loop.tool.no_session_trigger";
    public static final String LOOP_TOOL_PROMPT_EMPTY = "loop.tool.prompt_empty";
    public static final String LOOP_TOOL_AT_FORMAT = "loop.tool.at_format";
    public static final String LOOP_TOOL_AT_PAST = "loop.tool.at_past";
    public static final String LOOP_TOOL_ONE_SHOT_NO_SCHEDULE = "loop.tool.one_shot_no_schedule";
    public static final String LOOP_TOOL_ONE_SHOT_RUN_NOW = "loop.tool.one_shot_run_now";
    /** cron 校验失败（包住异常说明，补句末标点） */
    public static final String LOOP_TOOL_CRON_INVALID = "loop.tool.cron_invalid";
    public static final String LOOP_TOOL_ADD_FAILED = "loop.tool.add_failed";
    public static final String LOOP_TOOL_ADDED = "loop.tool.added";
    public static final String LOOP_TOOL_ONE_SHOT_ADDED = "loop.tool.one_shot_added";
    public static final String LOOP_TOOL_BAD_ACTION = "loop.tool.bad_action";
    public static final String LOOP_TOOL_TASK_ID_EMPTY = "loop.tool.task_id_empty";
    public static final String LOOP_TOOL_TASK_NOT_FOUND = "loop.tool.task_not_found";
    public static final String LOOP_TOOL_STOPPED = "loop.tool.stopped";
    public static final String LOOP_TOOL_PAUSED = "loop.tool.paused";
    public static final String LOOP_TOOL_RESUMED = "loop.tool.resumed";
    public static final String LOOP_TOOL_TRIGGER_PAUSED = "loop.tool.trigger_paused";
    public static final String LOOP_TOOL_TRIGGERED = "loop.tool.triggered";

    // ---- /loop 命令（描述与回执）----
    public static final String COMMAND_LOOP_DESCRIPTION = "command.loop.description";
    public static final String COMMAND_LOOP_USAGE = "command.loop.usage";
    /** 用法示例（多行，逐行输出） */
    public static final String COMMAND_LOOP_USAGE_EXAMPLES = "command.loop.usage_examples";
    public static final String COMMAND_LOOP_ID_USAGE = "command.loop.id_usage";
    public static final String COMMAND_LOOP_BAD_INTERVAL = "command.loop.bad_interval";
    public static final String COMMAND_LOOP_UNKNOWN_FLAG = "command.loop.unknown_flag";
    public static final String COMMAND_LOOP_FAILED = "command.loop.failed";
    public static final String COMMAND_LOOP_REGISTERED = "command.loop.registered";
    public static final String COMMAND_LOOP_FIELD_INTERVAL = "command.loop.field.interval";
    public static final String COMMAND_LOOP_FIELD_PROMPT = "command.loop.field.prompt";
    public static final String COMMAND_LOOP_FIELD_RUN_NOW = "command.loop.field.run_now";
    public static final String COMMAND_LOOP_FIELD_STATE = "command.loop.field.state";
    public static final String COMMAND_LOOP_RUN_NOW_FIRST = "command.loop.run_now_first";
    public static final String COMMAND_LOOP_STATE_PERSISTED = "command.loop.state_persisted";
    public static final String COMMAND_LOOP_NOT_FOUND = "command.loop.not_found";
    public static final String COMMAND_LOOP_REMOVED = "command.loop.removed";
    public static final String COMMAND_LOOP_PAUSED = "command.loop.paused";
    public static final String COMMAND_LOOP_RESUMED = "command.loop.resumed";
    public static final String COMMAND_LOOP_CLEARED = "command.loop.cleared";
    public static final String COMMAND_LOOP_LIST_EMPTY = "command.loop.list.empty";
    public static final String COMMAND_LOOP_LIST_TITLE = "command.loop.list.title";
    public static final String COMMAND_LOOP_LIST_ITERATIONS = "command.loop.list.iterations";
    public static final String COMMAND_LOOP_LIST_LAST = "command.loop.list.last";
    public static final String COMMAND_LOOP_LIST_FOOTER = "command.loop.list.footer";
    public static final String COMMAND_LOOP_STATUS_PAUSED = "command.loop.status.paused";
    public static final String COMMAND_LOOP_STATUS_RUNNING = "command.loop.status.running";
    public static final String COMMAND_LOOP_STATUS_IDLE = "command.loop.status.idle";
    public static final String COMMAND_LOOP_EVERY_MINUTES = "command.loop.every_minutes";
    public static final String COMMAND_LOOP_EVERY_HOURS = "command.loop.every_hours";
    public static final String COMMAND_LOOP_AGO_SECONDS = "command.loop.ago_seconds";
    public static final String COMMAND_LOOP_AGO_MINUTES = "command.loop.ago_minutes";
    public static final String COMMAND_LOOP_AGO_HOURS = "command.loop.ago_hours";
}
