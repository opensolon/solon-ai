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
public class GradleProvider implements LanguageProvider {
    @Override public String id() { return "Gradle"; }
    @Override public String typeName() { return "Gradle 模块"; }
    @Override public String[] markers() { return new String[]{"build.gradle", "build.gradle.kts", "settings.gradle"}; }

    @Override
    public String[] ignoreFolders() {
        return new String[]{"build", ".gradle"};
    }

    @Override
    public String detectVersion(Path dir) {
        // 依次检查 kts / groovy 两种脚本
        for (String name : new String[]{"build.gradle.kts", "build.gradle"}) {
            String script = LanguageProvider.readText(dir, name);
            if (script == null) {
                continue;
            }
            // sourceCompatibility = JavaVersion.VERSION_17 / "17" / 1.8
            String v = LanguageProvider.find(script, "sourceCompatibility\\s*=?\\s*(?:JavaVersion\\.VERSION_)?[\"']?([\\d._]+)[\"']?");
            if (v == null) {
                // jvmToolchain(17) / languageVersion = JavaLanguageVersion.of(17)
                v = LanguageProvider.find(script, "(?:jvmToolchain\\(|JavaLanguageVersion\\.of\\()\\s*(\\d+)");
            }
            if (v != null) {
                return "Java " + v.replace('_', '.');
            }
        }
        return null;
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        buf.append("### 根项目 (Gradle)\n")
                .append("- 构建: `./gradlew build` (或 `gradle build`)\n")
                .append("- 全量测试: `./gradlew test`\n")
                .append("- 单测执行: `./gradlew test --tests \"ClassName\"` (替换为实际类名)\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        buf.append("### 模块 (Module): ").append(moduleName).append(" (Gradle)\n")
                .append("- 构建: `./gradlew :").append(moduleName.replace("/", ":")).append(":build`\n")
                .append("- 全量测试: `./gradlew :").append(moduleName.replace("/", ":")).append(":test`\n")
                .append("- 单测执行: `./gradlew :").append(moduleName.replace("/", ":")).append(":test --tests \"ClassName\"`\n\n");
    }
}