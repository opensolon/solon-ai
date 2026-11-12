/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.cli;

/**
 * 命令执行的结构化结果（程序侧调用用，例如 goal 验证器）
 *
 * @author noear
 * @since 4.2.0
 */
public final class CommandResult {
    /** 执行状态 */
    public enum Status {
        /** 进程自然结束（退出码见 {@link #getExitCode()}） */
        COMPLETED,
        /** 超时，进程树已终止 */
        TIMED_OUT,
        /** 未能执行或未正常结束（沙盒不可用、路径越界、启动失败、输出超上限被终止、被中断等） */
        FAILED
    }

    private final Status status;
    private final int exitCode;
    private final String text;

    private CommandResult(Status status, int exitCode, String text) {
        this.status = status;
        this.exitCode = exitCode;
        this.text = text == null ? "" : text;
    }

    public static CommandResult completed(int exitCode, String text) {
        return new CommandResult(Status.COMPLETED, exitCode, text);
    }

    public static CommandResult timedOut(String text) {
        return new CommandResult(Status.TIMED_OUT, -1, text);
    }

    public static CommandResult failed(String text) {
        return new CommandResult(Status.FAILED, -1, text);
    }

    public Status getStatus() {
        return status;
    }

    /** 退出码；非 COMPLETED 时为 -1 */
    public int getExitCode() {
        return exitCode;
    }

    /** 面向 LLM 的输出文本（与 bash 工具返回值同口径） */
    public String getText() {
        return text;
    }

    /**
     * 进程自然结束且退出码为 0
     *
     * <p>供程序侧判断用（如 goal 验证器要求退出码为 0）。与 bash 工具的失败标记含义不同：
     * 工具只在未能正常结束（{@link Status#TIMED_OUT} / {@link Status#FAILED}）时标记失败，不看退出码。</p>
     */
    public boolean isSuccess() {
        return status == Status.COMPLETED && exitCode == 0;
    }

    @Override
    public String toString() {
        return "CommandResult{status=" + status + ", exitCode=" + exitCode + '}';
    }
}
