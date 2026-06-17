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

import java.nio.file.Path;

/**
 * @author noear
 * @since 3.10.5
 */
public class MavenProvider implements LanguageProvider {
    @Override
    public String id() {
        return "Maven";
    }

    @Override
    public String typeName() {
        return "Maven 模块";
    }

    @Override
    public String[] markers() {
        return new String[]{"pom.xml"};
    }

    @Override
    public String[] ignoreFolders() {
        return new String[]{"target"};
    }

    @Override
    public String detectVersion(Path dir) {
        String pom = LanguageProvider.readText(dir, "pom.xml");
        if (pom == null) {
            return null;
        }

        // 1) maven.compiler.release / source / target （含 <release>17</release> 形式）
        String v = LanguageProvider.find(pom, "<maven\\.compiler\\.(?:release|source|target)>\\s*([\\d.]+)\\s*<");
        // 2) <java.version>
        if (v == null) {
            v = LanguageProvider.find(pom, "<java\\.version>\\s*([\\d.]+)\\s*<");
        }
        // 3) maven-compiler-plugin 的 <release>/<source>/<target>
        if (v == null) {
            v = LanguageProvider.find(pom, "<(?:release|source|target)>\\s*([\\d.]+)\\s*<");
        }

        // 注：版本可能继承自外部父 POM，本地 pom.xml 解析不到时返回 null（需 mvn help:evaluate 复核）
        return (v == null) ? null : "Java " + v;
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        buf.append("### 根项目 (Maven)\n")
                .append("- 构建: `mvn clean compile`\n")
                .append("- 全量测试: `mvn test`\n")
                .append("- 单测执行: `mvn test -Dtest=ClassName` (替换为实际类名)\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        buf.append("### 模块 (Module): ").append(moduleName).append(" (Maven)\n")
                .append("- 构建: `cd ").append(moduleName).append(" && mvn clean compile`\n")
                .append("- 全量测试: `cd ").append(moduleName).append(" && mvn test`\n")
                .append("- 单测执行: `cd ").append(moduleName).append(" && mvn test -Dtest=ClassName` (替换为实际类名)\n\n");
    }
}