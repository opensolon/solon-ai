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
public class CangjieProvider implements LanguageProvider {
    @Override
    public String id() {
        return "Cangjie";
    }

    @Override
    public String typeName() {
        return "仓颉项目";
    }

    @Override
    public String[] markers() {
        return new String[]{"cjpm.toml"};
    }

    @Override
    public String[] ignoreFolders() {
        // 仓颉默认的构建输出目录
        return new String[]{"target", ".cjpm"};
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        buf.append("### 根项目 (Cangjie)\n")
                .append("- 构建: `cjpm build`\n")
                .append("- 全量测试: `cjpm test`\n")
                .append("- 运行: `cjpm run`\n")
                .append("- 单项测试: `cjpm test --filter <test_case>` (替换为实际测试用例名)\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        buf.append("### 模块 (Module): ").append(moduleName).append(" (Cangjie)\n")
                .append("- 构建: `cd ").append(moduleName).append(" && cjpm build`\n")
                .append("- 全量测试: `cd ").append(moduleName).append(" && cjpm test`\n")
                .append("- 单项测试: `cd ").append(moduleName).append(" && cjpm test --filter <test_case>`\n\n");
    }
}