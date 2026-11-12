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
package org.noear.solon.ai.harness.extension.goal.i18n;

import org.noear.solon.lang.Preview;

/**
 * goal 插件的文案 key（常量名 = key 转大写，"." 换成 "_"）
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class GoalI18nKeys {
    private GoalI18nKeys() {
    }

    // ---- 工具返回值（错误 JSON 的 message） ----
    public static final String GOAL_TOOL_NO_GOAL = "goal.tool.no_goal";
    public static final String GOAL_TOOL_STATUS_REQUIRED = "goal.tool.status_required";
    public static final String GOAL_TOOL_NO_SESSION = "goal.tool.no_session";
    public static final String GOAL_TOOL_NO_ACTIVE_GOAL = "goal.tool.no_active_goal";
    public static final String GOAL_TOOL_NOT_ACTIVE = "goal.tool.not_active";
    public static final String GOAL_TOOL_ACTION_EVIDENCE_REQUIRED = "goal.tool.action_evidence_required";
    public static final String GOAL_TOOL_TODO_UNFINISHED = "goal.tool.todo_unfinished";
    public static final String GOAL_TOOL_VALIDATION_FAILED = "goal.tool.validation_failed";
    public static final String GOAL_TOOL_UNKNOWN_STATUS = "goal.tool.unknown_status";

    // ---- 轮次提示词（引擎语言） ----
    public static final String GOAL_PROMPT_FULL_CONTINUATION = "goal.prompt.full.continuation";
    public static final String GOAL_PROMPT_FULL_EVIDENCE = "goal.prompt.full.evidence";
    public static final String GOAL_PROMPT_FULL_FIDELITY = "goal.prompt.full.fidelity";
    public static final String GOAL_PROMPT_FULL_AUDIT = "goal.prompt.full.audit";
    public static final String GOAL_PROMPT_FULL_BLOCKED = "goal.prompt.full.blocked";
    public static final String GOAL_PROMPT_STAGNATION = "goal.prompt.stagnation";
    public static final String GOAL_PROMPT_ERROR_HEADER = "goal.prompt.error.header";
    public static final String GOAL_PROMPT_ERROR_SUMMARY = "goal.prompt.error.summary";
    public static final String GOAL_PROMPT_ERROR_SSL = "goal.prompt.error.ssl";
    public static final String GOAL_PROMPT_ERROR_HTTP_4XX = "goal.prompt.error.http_4xx";
    public static final String GOAL_PROMPT_ERROR_NETWORK = "goal.prompt.error.network";
    public static final String GOAL_PROMPT_ERROR_TOOL_EXECUTION = "goal.prompt.error.tool_execution";
    public static final String GOAL_PROMPT_ERROR_OTHER = "goal.prompt.error.other";
    public static final String GOAL_PROMPT_URGENT = "goal.prompt.urgent";
    public static final String GOAL_PROMPT_LAST_SUMMARY_TITLE = "goal.prompt.last_summary.title";
    public static final String GOAL_PROMPT_LAST_SUMMARY_HINT = "goal.prompt.last_summary.hint";
    public static final String GOAL_PROMPT_COMPACT_CONTINUATION = "goal.prompt.compact.continuation";
    public static final String GOAL_PROMPT_COMPACT_AUDIT = "goal.prompt.compact.audit";
    public static final String GOAL_PROMPT_COMPACT_LAST = "goal.prompt.compact.last";
    public static final String GOAL_PROMPT_MINIMAL_OBJECTIVE = "goal.prompt.minimal.objective";
    public static final String GOAL_PROMPT_MINIMAL_INSTRUCTION = "goal.prompt.minimal.instruction";
    public static final String GOAL_PROMPT_BUDGET_LIMIT_HEADER = "goal.prompt.budget_limit.header";
    public static final String GOAL_PROMPT_BUDGET_LIMIT_OBJECTIVE = "goal.prompt.budget_limit.objective";
    public static final String GOAL_PROMPT_BUDGET_LIMIT_TITLE = "goal.prompt.budget_limit.title";
    public static final String GOAL_PROMPT_BUDGET_LIMIT_ELAPSED = "goal.prompt.budget_limit.elapsed";
    public static final String GOAL_PROMPT_BUDGET_LIMIT_CONSUMED = "goal.prompt.budget_limit.consumed";
    public static final String GOAL_PROMPT_BUDGET_LIMIT_INSTRUCTION = "goal.prompt.budget_limit.instruction";
    public static final String GOAL_PROMPT_BUDGET_LIMIT_NO_UPDATE = "goal.prompt.budget_limit.no_update";
    public static final String GOAL_PROMPT_BUDGET_CONSUMED = "goal.prompt.budget.consumed";
    public static final String GOAL_PROMPT_BUDGET_REMAINING = "goal.prompt.budget.remaining";
    public static final String GOAL_PROMPT_BUDGET_WARNING = "goal.prompt.budget.warning";
    public static final String GOAL_PROMPT_BUDGET_CONSUMED_UNLIMITED = "goal.prompt.budget.consumed_unlimited";
    public static final String GOAL_PROMPT_BUDGET_ELAPSED = "goal.prompt.budget.elapsed";
    public static final String GOAL_PROMPT_ITERATION = "goal.prompt.iteration";
    public static final String GOAL_PROMPT_TRUNCATED = "goal.prompt.truncated";

    // ---- 轮次结果记录（引擎语言，写入 lastResult） ----
    public static final String GOAL_ROUND_UNCLAIMED = "goal.round.unclaimed";
    public static final String GOAL_ROUND_COMPLETION_REJECTED = "goal.round.completion_rejected";

    // ---- 执行型目标关键词（识别时合并所有语言） ----
    public static final String GOAL_ACTION_KEYWORDS = "goal.action_keywords";

    // ---- 门面与命令的冲突说明 ----
    public static final String GOAL_ERROR_ACTIVE_EXISTS = "goal.error.active_exists";
    public static final String GOAL_ERROR_TERMINAL_EDIT = "goal.error.terminal_edit";

    // ---- 验证说明（调用方传入的语言） ----
    public static final String GOAL_VALIDATE_ERROR = "goal.validate.error";
    public static final String GOAL_VALIDATE_NO_RESULT = "goal.validate.no_result";
    public static final String GOAL_VALIDATE_UNABLE = "goal.validate.unable";
    public static final String GOAL_VALIDATE_WORKDIR_EMPTY = "goal.validate.workdir_empty";
    public static final String GOAL_VALIDATE_DETECT_FAILED = "goal.validate.detect_failed";
    public static final String GOAL_VALIDATE_RUN_FAILED = "goal.validate.run_failed";
    public static final String GOAL_VALIDATE_RUN_NO_RESULT = "goal.validate.run_no_result";
    public static final String GOAL_VALIDATE_RUN_TIMED_OUT = "goal.validate.run_timed_out";
    public static final String GOAL_VALIDATE_TESTS_FAILED = "goal.validate.tests_failed";
    public static final String GOAL_VALIDATE_BUILD_FAILED = "goal.validate.build_failed";
    public static final String GOAL_VALIDATE_UNKNOWN_PROJECT = "goal.validate.unknown_project";

    // ---- /goal 命令 ----
    public static final String COMMAND_GOAL_DESCRIPTION = "command.goal.description";
    public static final String COMMAND_GOAL_USAGE_EXAMPLE = "command.goal.usage_example";
}
