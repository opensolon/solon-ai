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
 * LSP 服务器命令未找到或不可执行时抛出
 *
 * <p>典型场景：用户未安装 pylsp、gopls、jdtls 等语言服务器二进制文件，
 * 或命令不在 PATH 中。
 *
 * @author noear
 * @since 3.10.0
 */
public class LspCommandNotFoundException extends RuntimeException {
    private final String commandName;
    private final String[] fullCommand;

    public LspCommandNotFoundException(String[] command, Throwable cause) {
        super("LSP server '" +  command[0] + "' command not found. Please install it before using LSP features for this language.", cause);
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
