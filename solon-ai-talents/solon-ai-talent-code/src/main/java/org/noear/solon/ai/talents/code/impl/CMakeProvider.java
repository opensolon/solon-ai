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
public class CMakeProvider implements LanguageProvider {
    private static final String[] MARKERS = {"CMakeLists.txt"};
    private static final String[] IGNORE_FOLDERS = {"build", "CMakeFiles", ".cmake"};

    @Override public String id() { return "CMake"; }
    @Override
    public String typeName() {
        return typeName(null);
    }

    @Override
    public String typeName(Locale locale) {
        return CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_CMAKE_TYPE);
    }
    @Override public String[] markers() { return MARKERS; }

    @Override
    public boolean isAggregator(Path dir, Set<String> entryNames) {
        // 声明了 add_subdirectory 的 CMakeLists.txt：子目录里还有真正的工程
        String cmake = LanguageProvider.readText(dir, "CMakeLists.txt");
        return cmake != null && LanguageProvider.find(cmake, "(add_subdirectory)\\s*\\(") != null;
    }

    @Override
    public String detectVersion(Path dir) {
        // CMakeLists.txt: set(CMAKE_CXX_STANDARD 17)
        String cmake = LanguageProvider.readText(dir, "CMakeLists.txt");
        String v = LanguageProvider.find(cmake, "set\\s*\\(\\s*CMAKE_CXX_STANDARD\\s+(\\d+)");
        if (v != null) {
            return "C++ " + v;
        }
        v = LanguageProvider.find(cmake, "set\\s*\\(\\s*CMAKE_C_STANDARD\\s+(\\d+)");
        return (v == null) ? null : "C " + v;
    }

    @Override
    public String[] ignoreFolders() {
        return IGNORE_FOLDERS;
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        appendRootCommands(buf, null);
    }

    @Override
    public void appendRootCommands(StringBuilder buf, Locale locale) {
        buf.append(CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_CMAKE_ROOT)).append("\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        appendModuleCommands(buf, moduleName, null);
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName, Locale locale) {
        buf.append(CodeI18n.fmt(locale, CodeI18nKeys.CODE_LANG_CMAKE_MODULE, "module", moduleName)).append("\n\n");
    }
}