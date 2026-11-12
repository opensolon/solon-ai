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
public class CangjieProvider implements LanguageProvider {
    private static final String[] MARKERS = {"cjpm.toml"};
    private static final String[] IGNORE_FOLDERS = {"target", ".cjpm"};

    @Override
    public String id() {
        return "Cangjie";
    }

    @Override
    public String typeName() {
        return typeName(null);
    }

    @Override
    public String typeName(Locale locale) {
        return CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_CANGJIE_TYPE);
    }

    @Override
    public String[] markers() {
        return MARKERS;
    }

    @Override
    public String detectVersion(Path dir) {
        // cjpm.toml: cjc-version = "0.53.4"
        String toml = LanguageProvider.readText(dir, "cjpm.toml");
        String v = LanguageProvider.find(toml, "cjc-version\\s*=\\s*\"([^\"]+)\"");
        return (v == null) ? null : "Cangjie " + v;
    }

    @Override
    public String[] ignoreFolders() {
        // 仓颉默认的构建输出目录
        return IGNORE_FOLDERS;
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        appendRootCommands(buf, null);
    }

    @Override
    public void appendRootCommands(StringBuilder buf, Locale locale) {
        buf.append(CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_CANGJIE_ROOT)).append("\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        appendModuleCommands(buf, moduleName, null);
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName, Locale locale) {
        buf.append(CodeI18n.fmt(locale, CodeI18nKeys.CODE_LANG_CANGJIE_MODULE, "module", moduleName)).append("\n\n");
    }
}