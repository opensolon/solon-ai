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
package org.noear.solon.ai.talents.cli.i18n;

import org.noear.solon.lang.Preview;

/**
 * cli 才能（Terminal / Skill / Clock）的文案 key（资源见 {@link CliI18n}）
 *
 * <p>常量名 = key 转大写（"." 换成 "_"）。</p>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class CliI18nKeys {
    private CliI18nKeys() {
    }

    public static final String SKILL_DESCRIPTION = "skill.description";
    public static final String SKILL_INSTRUCTION_HEAD = "skill.instruction.head";
    public static final String SKILL_INSTRUCTION_PRELOADED_TITLE = "skill.instruction.preloaded_title";
    public static final String SKILL_INSTRUCTION_TITLE = "skill.instruction.title";
    public static final String SKILL_INSTRUCTION_SUMMARY = "skill.instruction.summary";
    public static final String SKILL_INSTRUCTION_LIST = "skill.instruction.list";
    public static final String SKILL_INSTRUCTION_SEARCH = "skill.instruction.search";
    public static final String SKILL_LIST_EMPTY = "skill.list.empty";
    public static final String SKILL_LIST_TOO_MANY = "skill.list.too_many";
    public static final String SKILL_LIST_TITLE = "skill.list.title";
    public static final String SKILL_SEARCH_NONE = "skill.search.none";
    public static final String SKILL_READ_INVALID = "skill.read.invalid";
    public static final String SKILL_REFRESH_OK = "skill.refresh.ok";
    public static final String TERMINAL_DESCRIPTION = "terminal.description";
    public static final String TERMINAL_INSTRUCTION_TITLE = "terminal.instruction.title";
    public static final String TERMINAL_INSTRUCTION_SANDBOX = "terminal.instruction.sandbox";
    public static final String TERMINAL_INSTRUCTION_SANDBOX_ON = "terminal.instruction.sandbox_on";
    public static final String TERMINAL_INSTRUCTION_SANDBOX_OFF = "terminal.instruction.sandbox_off";
    public static final String TERMINAL_INSTRUCTION_OS = "terminal.instruction.os";
    public static final String TERMINAL_INSTRUCTION_SHELL = "terminal.instruction.shell";
    public static final String TERMINAL_INSTRUCTION_DIALECT_POWERSHELL = "terminal.instruction.dialect_powershell";
    public static final String TERMINAL_INSTRUCTION_DIALECT_CMD = "terminal.instruction.dialect_cmd";
    public static final String TERMINAL_INSTRUCTION_DIALECT_UNIX = "terminal.instruction.dialect_unix";
    public static final String TERMINAL_INSTRUCTION_SELF_PROTECT = "terminal.instruction.self_protect";
    public static final String TERMINAL_INSTRUCTION_KILL_WINDOWS = "terminal.instruction.kill_windows";
    public static final String TERMINAL_INSTRUCTION_KILL_TIP_WINDOWS = "terminal.instruction.kill_tip_windows";
    public static final String TERMINAL_INSTRUCTION_KILL_UNIX = "terminal.instruction.kill_unix";
    public static final String TERMINAL_INSTRUCTION_KILL_TIP_UNIX = "terminal.instruction.kill_tip_unix";
    public static final String TERMINAL_INSTRUCTION_FORBIDDEN = "terminal.instruction.forbidden";
    public static final String TERMINAL_INSTRUCTION_FORBIDDEN_RM_WINDOWS = "terminal.instruction.forbidden_rm_windows";
    public static final String TERMINAL_INSTRUCTION_FORBIDDEN_RM_UNIX = "terminal.instruction.forbidden_rm_unix";
    public static final String TERMINAL_INSTRUCTION_RUNTIME_TITLE = "terminal.instruction.runtime_title";
    public static final String TERMINAL_INSTRUCTION_RUNTIME_PYTHON = "terminal.instruction.runtime_python";
    public static final String TERMINAL_INSTRUCTION_RUNTIME_NODE = "terminal.instruction.runtime_node";
    public static final String TERMINAL_INSTRUCTION_PATH_TITLE = "terminal.instruction.path_title";
    public static final String TERMINAL_INSTRUCTION_WORKSPACE = "terminal.instruction.workspace";
    public static final String TERMINAL_INSTRUCTION_WORKSPACE_NAME = "terminal.instruction.workspace_name";
    public static final String TERMINAL_INSTRUCTION_MOUNTS = "terminal.instruction.mounts";
    public static final String TERMINAL_INSTRUCTION_SECURITY_SANDBOX_MOUNTS = "terminal.instruction.security_sandbox_mounts";
    public static final String TERMINAL_INSTRUCTION_SECURITY_SANDBOX = "terminal.instruction.security_sandbox";
    public static final String TERMINAL_INSTRUCTION_HOME_ON = "terminal.instruction.home_on";
    public static final String TERMINAL_INSTRUCTION_HOME_OFF = "terminal.instruction.home_off";
    public static final String TERMINAL_INSTRUCTION_SECURITY_OPEN = "terminal.instruction.security_open";
    public static final String TERMINAL_INSTRUCTION_RULES_TITLE = "terminal.instruction.rules_title";
    public static final String TERMINAL_INSTRUCTION_MOUNT_WRITABLE = "terminal.instruction.mount_writable";
    public static final String TERMINAL_INSTRUCTION_MOUNT_READONLY = "terminal.instruction.mount_readonly";
    public static final String TERMINAL_INSTRUCTION_CMD_SANDBOX_MOUNTS = "terminal.instruction.cmd_sandbox_mounts";
    public static final String TERMINAL_INSTRUCTION_CMD_SANDBOX = "terminal.instruction.cmd_sandbox";
    public static final String TERMINAL_INSTRUCTION_CMD_OPEN_MOUNTS = "terminal.instruction.cmd_open_mounts";
    public static final String TERMINAL_INSTRUCTION_CMD_OPEN = "terminal.instruction.cmd_open";
    public static final String TERMINAL_INSTRUCTION_TOOLS_FIRST = "terminal.instruction.tools_first";
    public static final String TERMINAL_INSTRUCTION_FILE_CMDS_POWERSHELL = "terminal.instruction.file_cmds_powershell";
    public static final String TERMINAL_INSTRUCTION_FILE_CMDS_CMD = "terminal.instruction.file_cmds_cmd";
    public static final String TERMINAL_INSTRUCTION_FILE_CMDS_UNIX = "terminal.instruction.file_cmds_unix";
    public static final String TERMINAL_INSTRUCTION_ASYNC = "terminal.instruction.async";
    public static final String TERMINAL_INSTRUCTION_ARGS_WINDOWS = "terminal.instruction.args_windows";
    public static final String TERMINAL_INSTRUCTION_ARGS_UNIX = "terminal.instruction.args_unix";
    public static final String TERMINAL_INSTRUCTION_PS_TITLE = "terminal.instruction.ps_title";
    public static final String TERMINAL_INSTRUCTION_PS_DISCARD = "terminal.instruction.ps_discard";
    public static final String TERMINAL_INSTRUCTION_PS_CHAIN_CORE = "terminal.instruction.ps_chain_core";
    public static final String TERMINAL_INSTRUCTION_PS_CHAIN_51 = "terminal.instruction.ps_chain_51";
    public static final String TERMINAL_INSTRUCTION_PS_CHAIN_51_NOTE = "terminal.instruction.ps_chain_51_note";
    public static final String TERMINAL_INSTRUCTION_PS_EQUIVALENTS = "terminal.instruction.ps_equivalents";
    public static final String TERMINAL_INSTRUCTION_PS_ALIAS_TRAP = "terminal.instruction.ps_alias_trap";
    public static final String TERMINAL_INSTRUCTION_PS_CURL_CORE = "terminal.instruction.ps_curl_core";
    public static final String TERMINAL_INSTRUCTION_PS_CURL_51 = "terminal.instruction.ps_curl_51";
    public static final String TERMINAL_INSTRUCTION_STDERR_POWERSHELL = "terminal.instruction.stderr_powershell";
    public static final String TERMINAL_INSTRUCTION_STDERR_CMD = "terminal.instruction.stderr_cmd";
    public static final String TERMINAL_INSTRUCTION_PS_ENCODING = "terminal.instruction.ps_encoding";
    public static final String TERMINAL_INSTRUCTION_PS_ENCODING_GBK = "terminal.instruction.ps_encoding_gbk";
    public static final String TERMINAL_INSTRUCTION_PS_ENCODING_BOM = "terminal.instruction.ps_encoding_bom";
    public static final String TERMINAL_INSTRUCTION_PS_ENCODING_GARBLED = "terminal.instruction.ps_encoding_garbled";
    public static final String TERMINAL_INSTRUCTION_CMD_TITLE = "terminal.instruction.cmd_title";
    public static final String TERMINAL_INSTRUCTION_CMD_EQUIVALENTS = "terminal.instruction.cmd_equivalents";
    public static final String TERMINAL_INSTRUCTION_CMD_CD = "terminal.instruction.cmd_cd";
    public static final String TERMINAL_INSTRUCTION_CMD_CHAIN = "terminal.instruction.cmd_chain";
    public static final String TERMINAL_INSTRUCTION_CMD_PERCENT = "terminal.instruction.cmd_percent";
    public static final String TERMINAL_INSTRUCTION_CMD_GARBLED = "terminal.instruction.cmd_garbled";
    public static final String TERMINAL_INSTRUCTION_CONSTRAINTS = "terminal.instruction.constraints";
    public static final String TERMINAL_ERROR = "terminal.error";
    public static final String TERMINAL_EXEC_EMPTY = "terminal.exec.empty";
    public static final String TERMINAL_SANDBOX_DISABLED = "terminal.sandbox.disabled";
    public static final String TERMINAL_SANDBOX_UNAVAILABLE = "terminal.sandbox.unavailable";
    public static final String TERMINAL_WINDOWS_PREPARE_FAILED = "terminal.windows.prepare_failed";
    public static final String TERMINAL_PATH_MISSING = "terminal.path.missing";
    public static final String TERMINAL_LS_EMPTY = "terminal.ls.empty";
    public static final String TERMINAL_LS_DENIED = "terminal.ls.denied";
    public static final String TERMINAL_READ_MISSING = "terminal.read.missing";
    public static final String TERMINAL_READ_BINARY = "terminal.read.binary";
    public static final String TERMINAL_READ_EMPTY = "terminal.read.empty";
    public static final String TERMINAL_READ_DIR = "terminal.read.dir";
    public static final String TERMINAL_READ_LINE_TRUNCATED = "terminal.read.line_truncated";
    public static final String TERMINAL_READ_OFFSET_OUT = "terminal.read.offset_out";
    public static final String TERMINAL_READ_MORE = "terminal.read.more";
    public static final String TERMINAL_READ_CHAR_LIMIT = "terminal.read.char_limit";
    public static final String TERMINAL_READ_LINE_LIMIT = "terminal.read.line_limit";
    public static final String TERMINAL_READ_NEXT = "terminal.read.next";
    public static final String TERMINAL_READ_DONE = "terminal.read.done";
    public static final String TERMINAL_WRITE_OK = "terminal.write.ok";
    public static final String TERMINAL_EDIT_MISSING = "terminal.edit.missing";
    public static final String TERMINAL_EDIT_EMPTY_OLD = "terminal.edit.empty_old";
    public static final String TERMINAL_EDIT_ALL_MISSING = "terminal.edit.all_missing";
    public static final String TERMINAL_EDIT_MISMATCH = "terminal.edit.mismatch";
    public static final String TERMINAL_EDIT_NOT_UNIQUE = "terminal.edit.not_unique";
    public static final String TERMINAL_EDIT_APPLY_FAILED = "terminal.edit.apply_failed";
    public static final String TERMINAL_EDIT_OK = "terminal.edit.ok";
    public static final String TERMINAL_VEDIT_EMPTY_OLD = "terminal.vedit.empty_old";
    public static final String TERMINAL_VEDIT_MISMATCH = "terminal.vedit.mismatch";
    public static final String TERMINAL_VEDIT_ALL_MISSING = "terminal.vedit.all_missing";
    public static final String TERMINAL_GREP_NONE = "terminal.grep.none";
    public static final String TERMINAL_GREP_TRUNCATED = "terminal.grep.truncated";
    public static final String TERMINAL_GREP_SKIPPED_LARGE = "terminal.grep.skipped_large";
    public static final String TERMINAL_GLOB_NONE = "terminal.glob.none";
    public static final String TERMINAL_VFILE_TOO_LARGE = "terminal.vfile.too_large";
    public static final String TERMINAL_SEARCH_SEPARATOR = "terminal.search.separator";
    public static final String TERMINAL_SEARCH_MISSING = "terminal.search.missing";
    public static final String TERMINAL_VALIDATE_HOME = "terminal.validate.home";
    public static final String TERMINAL_VALIDATE_EMPTY = "terminal.validate.empty";
    public static final String TERMINAL_VALIDATE_KILL = "terminal.validate.kill";
    public static final String TERMINAL_VALIDATE_EXIT = "terminal.validate.exit";
    public static final String TERMINAL_VALIDATE_RM_WINDOWS = "terminal.validate.rm_windows";
    public static final String TERMINAL_DENIED_NOT_LOCAL = "terminal.denied.not_local";
    public static final String TERMINAL_DENIED_UNKNOWN_MOUNT = "terminal.denied.unknown_mount";
    public static final String TERMINAL_DENIED_READONLY_MOUNT = "terminal.denied.readonly_mount";
    public static final String TERMINAL_DENIED_NO_LOCAL_ROOT = "terminal.denied.no_local_root";
    public static final String TERMINAL_DENIED_ABSOLUTE = "terminal.denied.absolute";
    public static final String TERMINAL_DENIED_OUTSIDE = "terminal.denied.outside";
    public static final String TERMINAL_DENIED_CACHE = "terminal.denied.cache";
    public static final String TERMINAL_DENIED_PROTECTED = "terminal.denied.protected";
    public static final String TERMINAL_DENIED_READ = "terminal.denied.read";
    public static final String TERMINAL_DENIED_WRITE_WHITELIST = "terminal.denied.write_whitelist";
    public static final String TERMINAL_DENIED_HOME = "terminal.denied.home";
    public static final String TERMINAL_DENIED_MOUNT_OUTSIDE = "terminal.denied.mount_outside";
    public static final String TERMINAL_DENIED_SYMLINK = "terminal.denied.symlink";
    public static final String TERMINAL_DENIED_MOUNT_UNAVAILABLE = "terminal.denied.mount_unavailable";
    public static final String TERMINAL_DENIED_UNREADABLE = "terminal.denied.unreadable";
    public static final String TERMINAL_DENIED_READONLY_SOURCE = "terminal.denied.readonly_source";
    public static final String TERMINAL_MATERIALIZE_NO_CACHE = "terminal.materialize.no_cache";
    public static final String TERMINAL_MATERIALIZE_NO_DIR = "terminal.materialize.no_dir";
    public static final String TERMINAL_MATERIALIZE_FAILED = "terminal.materialize.failed";
    public static final String TERMINAL_APPLY_EMPTY_OLD = "terminal.apply.empty_old";
    public static final String TERMINAL_APPLY_ALL_MISSING = "terminal.apply.all_missing";
    public static final String TERMINAL_APPLY_MISSING = "terminal.apply.missing";
    public static final String TERMINAL_APPLY_NOT_UNIQUE = "terminal.apply.not_unique";
    public static final String TERMINAL_DIAG_NO_CONTENT = "terminal.diag.no_content";
    public static final String TERMINAL_DIAG_BLANK = "terminal.diag.blank";
    public static final String TERMINAL_DIAG_LINES = "terminal.diag.lines";
    public static final String TERMINAL_DIAG_MISMATCH = "terminal.diag.mismatch";
    public static final String TERMINAL_DIAG_MISMATCH_COLLAPSE = "terminal.diag.mismatch_collapse";
    public static final String EXECUTOR_NO_CMD = "executor.no_cmd";
    public static final String EXECUTOR_CODE_FAILED = "executor.code_failed";
    public static final String EXECUTOR_TIMEOUT = "executor.timeout";
    public static final String EXECUTOR_TIMEOUT_OUTPUT = "executor.timeout_output";
    public static final String EXECUTOR_SIZE_CAPPED = "executor.size_capped";
    public static final String EXECUTOR_OK = "executor.ok";
    public static final String EXECUTOR_INTERRUPTED = "executor.interrupted";
    public static final String EXECUTOR_SYSTEM_FAILED = "executor.system_failed";
    public static final String EXECUTOR_OMITTED = "executor.omitted";
    public static final String EXECUTOR_BINARY_READABLE = "executor.binary_readable";
    public static final String EXECUTOR_BINARY_SIZE = "executor.binary_size";
    public static final String EXECUTOR_BINARY_TITLE = "executor.binary_title";
    public static final String EXECUTOR_BINARY_SUMMARY = "executor.binary_summary";
    public static final String EXECUTOR_BINARY_SUMMARY_TYPED = "executor.binary_summary_typed";
    public static final String EXECUTOR_BINARY_HINT = "executor.binary_hint";
    public static final String EXECUTOR_BINARY_HINT_TYPED = "executor.binary_hint_typed";
    public static final String EXECUTOR_TRUNCATED = "executor.truncated";
}

