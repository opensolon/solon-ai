/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.talents.cli;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 统一的 shell 命令启动拼装。
 * <p>
 * 同步 {@code bash} 与异步 {@code bash_start} 都应通过这里生成 argv，避免两套启动契约分叉：
 * <ul>
 *   <li>Unix: {@code shell -lc command}</li>
 *   <li>Windows CMD: {@code cmd /c command}</li>
 *   <li>Windows PowerShell: {@code powershell -Command command}</li>
 * </ul>
 *
 * @author noear
 * @since 4.0.4
 */
public final class ShellCommandFactory {

    private static volatile String cachedUnixShell;

    private final ShellMode shellMode;
    private final String shellCmd;

    public ShellCommandFactory(ShellMode shellMode, String shellCmd) {
        this.shellMode = Objects.requireNonNull(shellMode, "shellMode");
        this.shellCmd = requireNonEmpty(shellCmd, "shellCmd");
    }

    /**
     * 按当前 OS / COMSPEC 探测默认 shell。
     */
    public static ShellCommandFactory detect() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            String comspec = System.getenv("COMSPEC");
            if (comspec != null && comspec.toLowerCase().contains("powershell")) {
                return new ShellCommandFactory(ShellMode.POWERSHELL, "powershell");
            }
            return new ShellCommandFactory(ShellMode.CMD, "cmd");
        }
        return new ShellCommandFactory(ShellMode.UNIX_SHELL, probeUnixShell());
    }

    public ShellMode getShellMode() {
        return shellMode;
    }

    public String getShellCmd() {
        return shellCmd;
    }

    /**
     * 将用户命令组装为直接执行的进程参数列表（不落临时脚本）。
     */
    public List<String> build(String command) {
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
        if (shellMode == ShellMode.POWERSHELL) {
            return Arrays.asList(shellCmd, "-Command", command);
        }
        if (shellMode == ShellMode.CMD) {
            return Arrays.asList(shellCmd, "/c", command);
        }
        return Arrays.asList(shellCmd, "-lc", command);
    }

    static String probeUnixShell() {
        String cached = cachedUnixShell;
        if (cached != null) {
            return cached;
        }
        synchronized (ShellCommandFactory.class) {
            if (cachedUnixShell != null) {
                return cachedUnixShell;
            }
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                boolean ok = p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
                p.destroyForcibly();
                cachedUnixShell = ok ? "bash" : "/bin/sh";
            } catch (Throwable e) {
                cachedUnixShell = "/bin/sh";
            }
            return cachedUnixShell;
        }
    }

    private static String requireNonEmpty(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
