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
public class NodeProvider implements LanguageProvider {
    private static final String[] MARKERS = {"package.json"};
    private static final String[] IGNORE_FOLDERS = {"node_modules"};

    @Override
    public String id() {
        return "Node";
    }

    @Override
    public String typeName() {
        return typeName(null);
    }

    @Override
    public String typeName(Locale locale) {
        return CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_NODE_TYPE);
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
        // package.json 声明了 workspaces（npm/yarn/pnpm monorepo）
        String pkg = LanguageProvider.readText(dir, "package.json");
        return pkg != null && LanguageProvider.find(pkg, "(\"workspaces\")") != null;
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
        appendRootCommands(buf, null);
    }

    @Override
    public void appendRootCommands(StringBuilder buf, Locale locale) {
        buf.append(CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_NODE_ROOT)).append("\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        appendModuleCommands(buf, moduleName, null);
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName, Locale locale) {
        buf.append(CodeI18n.fmt(locale, CodeI18nKeys.CODE_LANG_NODE_MODULE, "module", moduleName)).append("\n\n");
    }
}