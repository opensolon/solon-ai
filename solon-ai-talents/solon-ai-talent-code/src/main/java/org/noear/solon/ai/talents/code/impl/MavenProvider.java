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
public class MavenProvider implements LanguageProvider {
    private static final String[] MARKERS = {"pom.xml"};
    private static final String[] IGNORE_FOLDERS = {"target"};

    @Override
    public String id() {
        return "Maven";
    }

    @Override
    public String typeName() {
        return typeName(null);
    }

    @Override
    public String typeName(Locale locale) {
        return CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_MAVEN_TYPE);
    }

    @Override
    public String[] markers() {
        return MARKERS;
    }

    @Override
    public String[] ignoreFolders() {
        return IGNORE_FOLDERS;
    }

    @Override
    public boolean isAggregator(Path dir, Set<String> entryNames) {
        // 声明了 <modules> 的父 POM：自身是模块，但真正的代码模块在子目录
        String pom = LanguageProvider.readText(dir, "pom.xml");
        return pom != null && LanguageProvider.find(pom, "(<modules>)") != null;
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
        appendRootCommands(buf, null);
    }

    @Override
    public void appendRootCommands(StringBuilder buf, Locale locale) {
        buf.append(CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_MAVEN_ROOT)).append("\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        appendModuleCommands(buf, moduleName, null);
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName, Locale locale) {
        buf.append(CodeI18n.fmt(locale, CodeI18nKeys.CODE_LANG_MAVEN_MODULE, "module", moduleName)).append("\n\n");
    }
}