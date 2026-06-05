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
 * 文件扩展名没有匹配的 LSP 服务器配置时抛出
 *
 * @author noear
 * @since 3.10.0
 */
public class LspNoMatchException extends RuntimeException {
    private final String filePath;
    private final String supportedExtensions;

    public LspNoMatchException(String filePath, String supportedExtensions) {
        super("No LSP server for extension of: " + filePath);
        this.filePath = filePath;
        this.supportedExtensions = supportedExtensions;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getSupportedExtensions() {
        return supportedExtensions;
    }
}
