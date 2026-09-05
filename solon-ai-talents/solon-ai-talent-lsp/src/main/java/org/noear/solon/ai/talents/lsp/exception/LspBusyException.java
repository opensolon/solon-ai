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
 * 语言服务器暂时跟不上（单条出站消息在软预算内没写完）。
 *
 * <p>与 {@link LspStalledException} 的区别是「可重试」：项目导入、大文件重解析期间，
 * 服务器读 stdin 变慢是正常现象，此时该放弃的是本轮诊断，而不是这个进程——把正在正常
 * 导入项目的服务器当故障杀掉，只会让它永远停在冷启动，最终把整个会话的语言服务耗光。
 *
 * @author noear
 * @since 4.1
 */
public class LspBusyException extends RuntimeException {
    private final String serverName;

    public LspBusyException(String serverName, String message) {
        super("LSP server '" + serverName + "' is busy: " + message);
        this.serverName = serverName;
    }

    public String getServerName() {
        return serverName;
    }
}
