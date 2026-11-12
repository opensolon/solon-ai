/*
 * Copyright 2017-2026 noear.org and authors
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

import org.noear.solon.ai.talents.code.i18n.CodeI18n;
import org.noear.solon.ai.talents.code.i18n.CodeI18nKeys;
import org.noear.solon.ai.talents.code.LanguageProvider;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * @author noear
 * @since 3.10.5
 */
public class GradleProvider implements LanguageProvider {
    private static final String[] MARKERS = {"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"};
    private static final String[] IGNORE_FOLDERS = {"build", ".gradle"};

    @Override public String id() { return "Gradle"; }
    @Override
    public String typeName() {
        return typeName(null);
    }

    @Override
    public String typeName(Locale locale) {
        return CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_GRADLE_TYPE);
    }
    @Override public String[] markers() { return MARKERS; }

    @Override
    public String[] ignoreFolders() {
        return IGNORE_FOLDERS;
    }

    @Override
    public boolean isAggregator(Path dir, Set<String> entryNames) {
        // settings.gradle 所在位置即多项目构建的根，子项目在其下
        return entryNames.contains("settings.gradle") || entryNames.contains("settings.gradle.kts");
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
        appendRootCommands(buf, null);
    }

    @Override
    public void appendRootCommands(StringBuilder buf, Locale locale) {
        buf.append(CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_GRADLE_ROOT)).append("\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        appendModuleCommands(buf, moduleName, null);
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName, Locale locale) {
        buf.append(CodeI18n.fmt(locale, CodeI18nKeys.CODE_LANG_GRADLE_MODULE, "module", moduleName, "project", moduleName.replace("/", ":"))).append("\n\n");
    }
}