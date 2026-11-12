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
public class RustProvider implements LanguageProvider {
    private static final String[] MARKERS = {"Cargo.toml"};
    private static final String[] IGNORE_FOLDERS = {"target"};

    @Override
    public String id() {
        return "Rust";
    }

    @Override
    public String typeName() {
        return typeName(null);
    }

    @Override
    public String typeName(Locale locale) {
        return CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_RUST_TYPE);
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
        // Cargo 工作区：成员 crate 在子目录
        String cargo = LanguageProvider.readText(dir, "Cargo.toml");
        return cargo != null && LanguageProvider.find(cargo, "(?m)^\\s*(\\[workspace\\])") != null;
    }

    @Override
    public String detectVersion(Path dir) {
        // Cargo.toml: rust-version = "1.70"  (仅取 [package] 区的 edition 作为补充)
        String cargo = LanguageProvider.readText(dir, "Cargo.toml");
        String v = LanguageProvider.find(cargo, "rust-version\\s*=\\s*\"([^\"]+)\"");
        if (v != null) {
            return "Rust " + v;
        }
        String edition = LanguageProvider.find(cargo, "edition\\s*=\\s*\"([^\"]+)\"");
        return (edition == null) ? null : "Rust edition " + edition;
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        appendRootCommands(buf, null);
    }

    @Override
    public void appendRootCommands(StringBuilder buf, Locale locale) {
        buf.append(CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_RUST_ROOT)).append("\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        appendModuleCommands(buf, moduleName, null);
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName, Locale locale) {
        buf.append(CodeI18n.fmt(locale, CodeI18nKeys.CODE_LANG_RUST_MODULE, "module", moduleName)).append("\n\n");
    }
}