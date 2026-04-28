/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.harness.code;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Language 规范提供者
 *
 * @author noear
 * @since 3.10.5
 */
public interface LanguageProvider {
    /**
     * 语言 ID (如 "Maven", "Go")
     */
    String id();

    /**
     * 模块类型描述 (如 "Maven 模块", "Go 模块")
     */
    String typeName();

    /**
     * 静态标志文件列表。用于 isSupported 的快速全局判断。如果是 C#，可以返回空数组，由 isMatch 处理。
     */
    String[] markers();

    /**
     * 该语言特有的忽略目录 (如 Python 的 __pycache__)
     */
    default String[] ignoreFolders() {
        return new String[0];
    }

    /**
     * 判定该目录是否属于该语言应该忽略的范畴
     */
    default boolean isIgnored(String pathName) {
        return Arrays.asList(ignoreFolders()).contains(pathName);
    }

    /**
     * 核心匹配逻辑。默认通过 markers 匹配，子类可重写以支持复杂逻辑 (如 .sln)
     */
    default boolean isMatch(Path dir) {
        return Arrays.stream(markers()).anyMatch(m -> Files.exists(dir.resolve(m)));
    }

    /**
     * 根项目指令生成
     */
    void appendRootCommands(StringBuilder buf);

    /**
     * 子模块指令生成
     */
    void appendModuleCommands(StringBuilder buf, String moduleName);
}