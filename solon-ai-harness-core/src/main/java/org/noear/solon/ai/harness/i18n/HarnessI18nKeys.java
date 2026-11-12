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
package org.noear.solon.ai.harness.i18n;

import org.noear.solon.lang.Preview;

/**
 * harness-core 的文案 key（常量名 = key 转大写，"." 换成 "_"）
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class HarnessI18nKeys {
    private HarnessI18nKeys() {
    }

    public static final String TASK_DESCRIPTION = "task.description";
    public static final String TASK_INSTRUCTION_AGENTS_TITLE = "task.instruction.agents_title";
    public static final String TASK_INSTRUCTION_SESSION_AGENTS_TITLE = "task.instruction.session_agents_title";
    public static final String TASK_INSTRUCTION_STRATEGY = "task.instruction.strategy";
    public static final String TASK_ERROR_NESTED_TASK = "task.error.nested_task";
    public static final String TASK_ERROR_NESTED_MULTITASK = "task.error.nested_multitask";
    public static final String TASK_ERROR_EMPTY_TASKS = "task.error.empty_tasks";
    /**
     * 变量：index
     */
    public static final String TASK_ERROR_NULL_TASK = "task.error.null_task";
    /**
     * 变量：error, name
     */
    public static final String TASK_ERROR_LOAD_FAILED = "task.error.load_failed";
    /**
     * 变量：name
     */
    public static final String TASK_ERROR_UNKNOWN_AGENT = "task.error.unknown_agent";
    public static final String TASK_ERROR_PARENT_PAUSED = "task.error.parent_paused";
    public static final String TASK_ERROR_NO_RESPONSE = "task.error.no_response";
    /**
     * 变量：error
     */
    public static final String TASK_ERROR_FAILED = "task.error.failed";
    public static final String TASK_AGGREGATE_CANCELLED = "task.aggregate.cancelled";
    public static final String TASK_AGGREGATE_INTERRUPTED = "task.aggregate.interrupted";
    /**
     * 变量：error
     */
    public static final String TASK_AGGREGATE_FAILED = "task.aggregate.failed";
    public static final String TASK_ADVICE_RATE_LIMIT = "task.advice.rate_limit";
    public static final String TASK_ADVICE_UPSTREAM = "task.advice.upstream";
    public static final String TASK_ADVICE_TIMEOUT = "task.advice.timeout";
    public static final String TASK_NOT_STARTED_CANCELLED = "task.not_started.cancelled";
    public static final String TASK_NOT_STARTED_INTERRUPTED = "task.not_started.interrupted";
    /**
     * 变量：kept, length, path
     */
    public static final String TASK_RESULT_SPILLED = "task.result.spilled";
    /**
     * 变量：length
     */
    public static final String TASK_RESULT_TRUNCATED = "task.result.truncated";
    public static final String GENERATE_ERROR_BAD_NAME = "generate.error.bad_name";
    /**
     * 变量：name
     */
    public static final String GENERATE_ERROR_RESERVED = "generate.error.reserved";
    /**
     * 变量：name
     */
    public static final String GENERATE_ERROR_GLOBAL_EXISTS = "generate.error.global_exists";
    public static final String GENERATE_ERROR_GLOBAL_DENIED = "generate.error.global_denied";
    /**
     * 变量：path
     */
    public static final String GENERATE_ERROR_FILE_EXISTS = "generate.error.file_exists";
    public static final String GENERATE_ERROR_NO_SESSION = "generate.error.no_session";
    /**
     * 变量：name
     */
    public static final String GENERATE_ERROR_CONFLICT_GLOBAL = "generate.error.conflict_global";
    /**
     * 变量：max
     */
    public static final String GENERATE_ERROR_SESSION_FULL = "generate.error.session_full";
    /**
     * 变量：error
     */
    public static final String GENERATE_ERROR_FAILED = "generate.error.failed";
    /**
     * 变量：description, name, scope
     */
    public static final String GENERATE_OK = "generate.ok";
    public static final String GENERATE_SCOPE_GLOBAL = "generate.scope.global";
    public static final String GENERATE_SCOPE_SESSION = "generate.scope.session";
    public static final String HITL_PATH_TRAVERSAL = "hitl.path_traversal";
    public static final String HITL_SENSITIVE_FILE = "hitl.sensitive_file";
    public static final String HITL_BASH_INJECTION = "hitl.bash.injection";
    /**
     * 变量：command
     */
    public static final String HITL_BASH_PRIVILEGED = "hitl.bash.privileged";
    public static final String HITL_BASH_INCOMPLETE = "hitl.bash.incomplete";
    public static final String HITL_BASH_DENY = "hitl.bash.deny";
    public static final String HITL_BASH_ASK = "hitl.bash.ask";
    public static final String HITL_WRITE_DENY = "hitl.write.deny";
    public static final String HITL_WRITE_ASK = "hitl.write.ask";
    /**
     * 变量：host
     */
    public static final String HITL_WEB_RISKY = "hitl.web.risky";
    public static final String HITL_WEB_UNKNOWN_HOST = "hitl.web.unknown_host";
    public static final String HITL_WEB_DENY = "hitl.web.deny";
    public static final String HITL_WEB_ASK = "hitl.web.ask";
    public static final String COMMAND_UNAVAILABLE = "command.unavailable";
    public static final String COMMAND_DISABLED = "command.disabled";
    public static final String COMMAND_SESSION_REQUIRED = "command.session_required";
    /**
     * 变量：command
     */
    public static final String COMMAND_SCOPE_DENIED = "command.scope_denied";
    public static final String COMMAND_HELP_DESCRIPTION = "command.help.description";
    public static final String COMMAND_HELP_TITLE = "command.help.title";
    public static final String COMMAND_HELP_USAGE = "command.help.usage";
    /**
     * 变量：name
     */
    public static final String COMMAND_HELP_NOT_FOUND = "command.help.not_found";
    public static final String COMMAND_HELP_FOOTER = "command.help.footer";
    public static final String COMMAND_HELP_NO_DESCRIPTION = "command.help.no_description";
    public static final String COMMAND_CLEAR_DESCRIPTION = "command.clear.description";
    public static final String COMMAND_CONTINUE_DESCRIPTION = "command.continue.description";
    public static final String COMMAND_CONTINUE_NONE = "command.continue.none";
    public static final String COMMAND_INTERRUPT_DESCRIPTION = "command.interrupt.description";
    public static final String COMMAND_INTERRUPT_DONE = "command.interrupt.done";
    public static final String COMMAND_INTERRUPT_IDLE = "command.interrupt.idle";
    public static final String COMMAND_MODEL_DESCRIPTION = "command.model.description";
    public static final String COMMAND_MODEL_TITLE = "command.model.title";
    public static final String COMMAND_MODEL_ACTIVE = "command.model.active";
    public static final String COMMAND_MODEL_USAGE = "command.model.usage";
    public static final String COMMAND_MODEL_HELP_TITLE = "command.model.help.title";
    public static final String COMMAND_MODEL_HELP_LIST = "command.model.help.list";
    public static final String COMMAND_MODEL_HELP_SWITCH = "command.model.help.switch";
    /** 模型不存在（标签，模型名由代码加粗追加） */
    public static final String COMMAND_MODEL_NOT_FOUND = "command.model.not_found";
    public static final String COMMAND_MODEL_SEE_LIST = "command.model.see_list";
    /** 已切换模型（标签，模型名由代码加粗追加） */
    public static final String COMMAND_MODEL_SWITCHED = "command.model.switched";
    /** Markdown 自定义命令没写 description 时的兜底描述 */
    public static final String COMMAND_CUSTOM_DESCRIPTION = "command.custom.description";
    public static final String COMMAND_RERUN_DESCRIPTION = "command.rerun.description";
    public static final String COMMAND_REWIND_DESCRIPTION = "command.rewind.description";
    public static final String COMMAND_REWIND_INVALID = "command.rewind.invalid";
    /**
     * 变量：count
     */
    public static final String COMMAND_REWIND_DONE = "command.rewind.done";
    public static final String INPUT_HITL_PENDING = "input.hitl_pending";
    /**
     * 变量：tool
     */
    public static final String INPUT_HITL_PENDING_TOOL = "input.hitl_pending_tool";
    public static final String INPUT_BUSY = "input.busy";
    public static final String INPUT_BUSY_ATTACHMENTS = "input.busy_attachments";
    public static final String INPUT_BUSY_SCOPE = "input.busy_scope";
    public static final String INPUT_BUSY_BACKLOG = "input.busy_backlog";
    public static final String INPUT_DESCRIBE_IMAGE = "input.describe_image";
    public static final String INPUT_DESCRIBE_IMAGES = "input.describe_images";
    public static final String INPUT_ANALYZE_MEDIA = "input.analyze_media";
    /**
     * 变量：path
     */
    public static final String INPUT_ATTACHMENT = "input.attachment";
    public static final String INPUT_ATTACHMENTS_DEFAULT = "input.attachments_default";
    public static final String RUN_CANCELLED = "run.cancelled";
}
