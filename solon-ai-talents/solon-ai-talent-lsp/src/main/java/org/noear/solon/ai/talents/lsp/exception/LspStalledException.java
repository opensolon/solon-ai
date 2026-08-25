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
 * 语言服务器已停止消费 stdin（写入超时）时抛出。
 *
 * <p>与「启动失败」是两类问题：进程还活着、握手也成功过，但它不再读自己的输入管道
 * （典型成因是它自己阻塞在写 stderr/stdout 上）。这种状态不会自行恢复，写入方唯一
 * 正确的应对是立即放弃本次通知并让上层重建连接，绝不能继续等待。
 *
 * @author noear
 * @since 4.1
 */
public class LspStalledException extends RuntimeException {
    private final String serverName;

    public LspStalledException(String serverName, String message) {
        super("LSP server '" + serverName + "' is not reading its input: " + message);
        this.serverName = serverName;
    }

    public String getServerName() {
        return serverName;
    }
}
