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

import java.nio.file.Path;

/**
 * @author noear
 * @since 3.10.5
 */
public class NodeProvider implements LanguageProvider {
    @Override
    public String id() {
        return "Node";
    }

    @Override
    public String typeName() {
        return "Node 项目";
    }

    @Override
    public String[] markers() {
        return new String[]{"package.json"};
    }

    @Override
    public String[] ignoreFolders() {
        return new String[]{"node_modules"};
    }

    @Override
    public String detectVersion(Path dir) {
        // 1) package.json 的 engines.node（最权威的声明）
        String pkg = LanguageProvider.readText(dir, "package.json");
        String node = LanguageProvider.find(pkg, "\"engines\"\\s*:\\s*\\{[^}]*?\"node\"\\s*:\\s*\"([^\"]+)\"");
        if (node != null) {
            return "Node " + node;
        }

        // 2) .nvmrc
        String nvmrc = LanguageProvider.readText(dir, ".nvmrc");
        if (nvmrc != null && !nvmrc.trim().isEmpty()) {
            return "Node " + nvmrc.trim();
        }

        return null;
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        buf.append("### 根项目 (Node/TS)\n")
                .append("- 安装: `npm install`\n")
                .append("- 构建: `npm run build`\n")
                .append("- 全量测试: `npm test`\n")
                .append("- 单文件测试: `npm test -- path/to/file.test.ts` (替换为实际文件路径)\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        buf.append("### 模块 (Module): ").append(moduleName).append(" (Node/TS)\n")
                .append("- 安装: `cd ").append(moduleName).append(" && npm install`\n")
                .append("- 构建: `cd ").append(moduleName).append(" && npm run build`\n")
                .append("- 全量测试: `cd ").append(moduleName).append(" && npm test`\n")
                .append("- 单文件测试: `cd ").append(moduleName).append(" && npm test -- path/to/file.test.ts` (替换为实际文件路径)\n\n");
    }
}