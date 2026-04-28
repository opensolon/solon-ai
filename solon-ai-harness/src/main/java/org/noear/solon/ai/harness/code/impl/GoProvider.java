package org.noear.solon.ai.harness.code.impl;

import org.noear.solon.ai.harness.code.LanguageProvider;

/**
 *
 * @author noear 2026/4/28 created
 *
 */
public class GoProvider implements LanguageProvider {
    @Override
    public String id() {
        return "Go";
    }

    @Override
    public String typeName() {
        return "Go 模块";
    }

    @Override
    public String[] markers() {
        return new String[]{"go.mod"};
    }

    @Override
    public String[] ignoreFolders() {
        return new String[]{"vendor"};
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        buf.append("### 根项目 (Go)\n")
                .append("- 依赖同步: `go mod tidy`\n")
                .append("- 构建: `go build ./...`\n")
                .append("- 全量测试: `go test ./...`\n")
                .append("- 单测执行: `go test -v -run TestName ./...` (替换 TestName 为实际函数名)\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        buf.append("### 模块 (Module): ").append(moduleName).append(" (Go)\n")
                .append("- 构建: `cd ").append(moduleName).append(" && go build`\n")
                .append("- 全量测试: `cd ").append(moduleName).append(" && go test ./...`\n")
                .append("- 单测执行: `cd ").append(moduleName).append(" && go test -v -run TestName` (替换 TestName 为实际函数名)\n\n");
    }
}