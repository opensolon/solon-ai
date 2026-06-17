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
package org.noear.solon.ai.talents.code;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * 探测该目录下配置文件中“声明”的环境/语言版本（注意：是项目配置声明的版本，而非本机安装版本）。
     * <p>例如 Maven 的 Java 编译版本、Node 的 engines.node、Go 的 go 指令等。
     * <p>解析不到时返回 null（例如版本以属性占位符表示，或继承自外部父配置）。
     *
     * @return 形如 "Java 1.8"、"Node >=18" 的可读版本标签；无法确定时为 null
     */
    default String detectVersion(Path dir) {
        return null;
    }

    /**
     * 读取目录下指定文件的文本内容；文件不存在或读取失败返回 null。
     */
    static String readText(Path dir, String fileName) {
        try {
            Path f = dir.resolve(fileName);
            if (!Files.isRegularFile(f)) {
                return null;
            }
            return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 返回首个匹配项的第 1 分组（忽略大小写、支持跨行）；无匹配返回 null。
     */
    static String find(String content, String regex) {
        if (content == null) {
            return null;
        }
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(content);
        return m.find() ? m.group(1).trim() : null;
    }
}