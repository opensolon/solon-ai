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
package org.noear.solon.ai.talents.mount;

import java.nio.file.Path;

/**
 * 代理文件描述（轻量扫描产物）
 *
 * @author noear
 * @since 3.11.0
 */
public class AgentMd {
    private final String name;          // 代理名（如 "code-review"，来自文件名去掉 .md 后缀）
    private final String mountAlias;    // 所属挂载别名（如 "@team"）
    private final Path filePath;        // .md 文件物理路径

    AgentMd(String name, String mountAlias, Path filePath) {
        this.name = name;
        this.mountAlias = mountAlias;
        this.filePath = filePath;
    }

    public String getName() {
        return name;
    }

    /**
     * 挂载别名
     */
    public String getMountAlias() {
        return mountAlias;
    }

    public Path getFilePath() {
        return filePath;
    }
}
