package org.noear.solon.ai.harness.code.impl;

import org.noear.solon.ai.harness.code.LanguageProvider;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;


/**
 * @author noear
 * @since 3.9.4
 */
public class DotNetProvider implements LanguageProvider {
    @Override public String id() { return "C#/.NET"; }
    @Override public String typeName() { return "C# 项目"; }

    @Override public String[] markers() { return new String[]{".sln", ".csproj"}; }

    @Override
    public String[] ignoreFolders() {
        return new String[]{"obj", "bin"};
    }

    @Override
    public boolean isMatch(Path dir) {
        return hasFileWithExtension(dir, ".sln") || hasFileWithExtension(dir, ".csproj");
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

    private boolean hasFileWithExtension(Path dir, String ext) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + ext)) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }
}