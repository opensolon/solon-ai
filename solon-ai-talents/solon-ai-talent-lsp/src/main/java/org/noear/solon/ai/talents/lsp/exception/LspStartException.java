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
 * LSP 服务器启动失败时抛出（命令不存在、进程启动失败、初始化超时等）
 *
 * @author noear
 * @since 3.10.0
 */
public class LspStartException extends RuntimeException {
    private final String commandName;
    private final String[] fullCommand;

    public LspStartException(String[] command, Throwable cause) {
        super("LSP server '" + command[0] + "' failed to start: " + cause.getMessage(), cause);
        this.commandName = command[0];
        this.fullCommand = command;
    }

    public String getCommandName() {
        return commandName;
    }

    public String[] getFullCommand() {
        return fullCommand;
    }
}
