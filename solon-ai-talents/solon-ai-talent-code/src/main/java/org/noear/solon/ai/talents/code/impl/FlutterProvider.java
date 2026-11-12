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

/**
 * @author noear
 * @since 3.10.5
 */
public class FlutterProvider implements LanguageProvider {
    private static final String[] MARKERS = {"pubspec.yaml"};
    private static final String[] IGNORE_FOLDERS = {".dart_tool", ".packages", "build"};

    @Override public String id() { return "Flutter"; }
    @Override
    public String typeName() {
        return typeName(null);
    }

    @Override
    public String typeName(Locale locale) {
        return CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_FLUTTER_TYPE);
    }
    @Override public String[] markers() { return MARKERS; }

    @Override
    public String[] ignoreFolders() {
        return IGNORE_FOLDERS;
    }

    @Override
    public String detectVersion(Path dir) {
        // pubspec.yaml: environment: { sdk: ">=3.0.0 <4.0.0", flutter: ">=3.10.0" }
        // 注：版本约束常以比较符开头（>=、^、~），故首字符不能只允许数字
        String pubspec = LanguageProvider.readText(dir, "pubspec.yaml");
        String flutter = LanguageProvider.find(pubspec, "(?m)^\\s*flutter\\s*:\\s*[\"']?([\\d><=^~][^\"'\\n]*)");
        if (flutter != null) {
            return "Flutter " + flutter.trim();
        }
        String sdk = LanguageProvider.find(pubspec, "(?m)^\\s*sdk\\s*:\\s*[\"']?([\\d><=^~][^\"'\\n]*)");
        return (sdk == null) ? null : "Dart SDK " + sdk.trim();
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        appendRootCommands(buf, null);
    }

    @Override
    public void appendRootCommands(StringBuilder buf, Locale locale) {
        buf.append(CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_FLUTTER_ROOT)).append("\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        appendModuleCommands(buf, moduleName, null);
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName, Locale locale) {
        buf.append(CodeI18n.fmt(locale, CodeI18nKeys.CODE_LANG_FLUTTER_MODULE, "module", moduleName)).append("\n\n");
    }
}