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
package org.noear.solon.ai.harness.code.impl;

import org.noear.solon.ai.harness.code.LanguageProvider;

/**
 * @author noear
 * @since 3.10.5
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