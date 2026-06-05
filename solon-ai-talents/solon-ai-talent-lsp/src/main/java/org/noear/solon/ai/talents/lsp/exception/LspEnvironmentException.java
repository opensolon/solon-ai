/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.lsp.exception;

/**
 * LSP 服务器运行环境不满足时抛出
 *
 * <p>典型场景：
 * <ul>
 *   <li>jdtls 需要 Java 21+，但当前环境是 Java 8</li>
 *   <li>rust-analyzer 需要 Rust 工具链</li>
 *   <li>进程启动成功但初始化握手失败（说明运行时环境有问题）</li>
 * </ul>
 *
 * @author noear
 * @since 3.10.0
 */
public class LspEnvironmentException extends RuntimeException {
    private final String detail;
    private final String commandName;
    private final String[] fullCommand;

    public LspEnvironmentException(String[] command, String detail, Throwable cause) {
        super("LSP server '" + command[0] + "' environment requirement not met: " + detail
                + ". Command: " + String.join(" ", command), cause);
        this.detail = detail;
        this.commandName = command[0];
        this.fullCommand = command;
    }

    public String getDetail() {
        return detail;
    }

    public String getCommandName() {
        return commandName;
    }

    public String[] getFullCommand() {
        return fullCommand;
    }
}
