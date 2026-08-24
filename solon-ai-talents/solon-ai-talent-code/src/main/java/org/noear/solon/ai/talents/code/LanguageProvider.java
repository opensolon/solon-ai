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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
     * 静态标志文件列表（精确文件名，非扩展名）。用于默认的匹配判定。
     * <p>若该语言只能按扩展名识别（如 C# 的 *.sln、*.csproj），应返回空数组，并重写 {@link #isMatch(Path, Set)}。
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
        for (String folder : ignoreFolders()) {
            if (folder.equals(pathName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 核心匹配逻辑（推荐重写此方法）。基于目录条目名集合判定，避免逐个 marker 触发文件系统调用。
     *
     * @param dir        目录
     * @param entryNames 该目录下直接条目（文件与子目录）的名称集合
     */
    default boolean isMatch(Path dir, Set<String> entryNames) {
        for (String marker : markers()) {
            if (entryNames.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 核心匹配逻辑（便捷入口）。会列举一次目录条目后委派给 {@link #isMatch(Path, Set)}。
     * <p>批量判定多个 Provider 时，应自行列举一次条目名并调用 {@link #isMatch(Path, Set)}，避免重复列举。
     */
    default boolean isMatch(Path dir) {
        return isMatch(dir, listNames(dir));
    }

    /**
     * 判定该目录是否为“聚合器/工作区”（自身是模块，但真正的代码模块在其子目录下）。
     * <p>例如 Maven 声明了 {@code <modules>} 的父 POM、Cargo 的 {@code [workspace]}、npm 的 {@code workspaces}。
     * <p>返回 true 时，扫描器不会因为它被识别为模块而跳过其整棵子树。
     *
     * @since 3.10.6
     */
    default boolean isAggregator(Path dir, Set<String> entryNames) {
        return false;
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
     * 列出目录下直接条目（文件与子目录）的名称；目录不可读或列举失败时返回空集合。
     */
    static Set<String> listNames(Path dir) {
        Set<String> names = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                names.add(p.getFileName().toString());
            }
        } catch (IOException | RuntimeException e) {
            return Collections.emptySet();
        }
        return names;
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
     * 返回首个匹配项的第 1 分组（大小写敏感、支持跨行）；无匹配返回 null。
     * <p>注：配置文件中的键名（XML 标签、TOML/JSON 键）本身是大小写敏感的，故此处不做忽略大小写匹配，
     * 避免 {@code <source>} 误配 {@code <Source>} 之类的假阳性。
     */
    static String find(String content, String regex) {
        if (content == null) {
            return null;
        }
        Matcher m = pattern(regex).matcher(content);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * 编译并缓存正则（探测逻辑会被高频调用，避免重复编译）
     */
    static Pattern pattern(String regex) {
        return PatternCache.MAP.computeIfAbsent(regex, r -> Pattern.compile(r, Pattern.DOTALL));
    }

    /**
     * 正则缓存持有者（接口无法声明私有静态字段，故用嵌套类承载）
     */
    final class PatternCache {
        static final Map<String, Pattern> MAP = new ConcurrentHashMap<>();

        private PatternCache() {
        }
    }
}