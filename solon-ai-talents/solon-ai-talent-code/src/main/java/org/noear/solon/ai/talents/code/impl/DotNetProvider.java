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
package org.noear.solon.ai.talents.code.impl;

import org.noear.solon.ai.talents.code.LanguageProvider;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;


/**
 * @author noear
 * @since 3.10.5
 */
public class DotNetProvider implements LanguageProvider {
    private static final String[] IGNORE_FOLDERS = {"obj", "bin"};

    @Override public String id() { return "C#/.NET"; }
    @Override public String typeName() { return "C# 项目"; }

    /**
     * C# 按扩展名（*.sln / *.csproj）识别，无法用精确文件名表达，故返回空数组，判定逻辑见 {@link #isMatch(Path, Set)}
     */
    @Override public String[] markers() { return new String[0]; }

    @Override
    public String[] ignoreFolders() {
        return IGNORE_FOLDERS;
    }

    @Override
    public boolean isMatch(Path dir, Set<String> entryNames) {
        for (String name : entryNames) {
            if (name.endsWith(".sln") || name.endsWith(".csproj")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isAggregator(Path dir, Set<String> entryNames) {
        // 解决方案文件（*.sln）本身不是项目，它引用的 *.csproj 通常在子目录
        for (String name : entryNames) {
            if (name.endsWith(".sln")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String detectVersion(Path dir) {
        // 扫描目录下的 *.csproj，取 <TargetFramework>net8.0</TargetFramework>
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.csproj")) {
            for (Path csproj : stream) {
                String content = new String(Files.readAllBytes(csproj), java.nio.charset.StandardCharsets.UTF_8);
                String v = LanguageProvider.find(content, "<TargetFrameworks?>\\s*([^<;]+)");
                if (v != null) {
                    return ".NET " + v.trim();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        buf.append("### 根项目 (C#/.NET)\n")
                .append("- 还原: `dotnet restore`\n")
                .append("- 构建: `dotnet build`\n")
                .append("- 全量测试: `dotnet test`\n")
                .append("- 单测执行: `dotnet test --filter FullyQualifiedName=Namespace.ClassName` (替换为实际类名)\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        buf.append("### 模块 (Module): ").append(moduleName).append(" (C#/.NET)\n")
                .append("- 还原: `cd ").append(moduleName).append(" && dotnet restore`\n")
                .append("- 构建: `cd ").append(moduleName).append(" && dotnet build`\n")
                .append("- 全量测试: `cd ").append(moduleName).append(" && dotnet test`\n")
                .append("- 单测执行: `cd ").append(moduleName).append(" && dotnet test --filter FullyQualifiedName=Namespace.ClassName` (替换为实际类名)\n\n");
    }
}