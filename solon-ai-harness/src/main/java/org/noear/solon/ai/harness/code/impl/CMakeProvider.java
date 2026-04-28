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
public class CMakeProvider implements LanguageProvider {
    @Override public String id() { return "CMake"; }
    @Override public String typeName() { return "C/C++ 项目"; }
    @Override public String[] markers() { return new String[]{"CMakeLists.txt"}; }

    @Override
    public String[] ignoreFolders() {
        return new String[]{"build", "CMakeFiles", ".cmake"};
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        buf.append("### 根项目 (C/C++)\n")
                .append("- 构建: `mkdir -p build && cd build && cmake .. && make`\n")
                .append("- 全量测试: `cd build && ctest`\n")
                .append("- 单项测试: `cd build && ctest -R <test_name_regexp>` (替换为正则过滤)\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        buf.append("### 模块 (Module): ").append(moduleName).append(" (C/C++)\n")
                .append("- 构建: `cd ").append(moduleName).append(" && mkdir -p build && cd build && cmake .. && make`\n")
                .append("- 全量测试: `cd ").append(moduleName).append("/build && ctest`\n")
                .append("- 单项测试: `cd ").append(moduleName).append("/build && ctest -R <test_name_regexp>`\n\n");
    }
}