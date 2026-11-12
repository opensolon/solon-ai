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
public class PythonProvider implements LanguageProvider {
    private static final String[] MARKERS = {"requirements.txt", "pyproject.toml", "setup.py"};
    private static final String[] IGNORE_FOLDERS = {"venv", ".venv", "__pycache__"};

    @Override public String id() { return "Python"; }
    @Override
    public String typeName() {
        return typeName(null);
    }

    @Override
    public String typeName(Locale locale) {
        return CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_PYTHON_TYPE);
    }
    @Override public String[] markers() { return MARKERS; }

    @Override
    public String[] ignoreFolders() {
        return IGNORE_FOLDERS;
    }

    @Override
    public String detectVersion(Path dir) {
        // pyproject.toml: requires-python = ">=3.9"
        String pyproject = LanguageProvider.readText(dir, "pyproject.toml");
        String v = LanguageProvider.find(pyproject, "requires-python\\s*=\\s*\"([^\"]+)\"");
        if (v != null) {
            return "Python " + v;
        }

        // setup.py: python_requires=">=3.9"
        String setup = LanguageProvider.readText(dir, "setup.py");
        v = LanguageProvider.find(setup, "python_requires\\s*=\\s*['\"]([^'\"]+)['\"]");
        if (v != null) {
            return "Python " + v;
        }

        // .python-version
        String pv = LanguageProvider.readText(dir, ".python-version");
        if (pv != null && !pv.trim().isEmpty()) {
            return "Python " + pv.trim();
        }

        return null;
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        appendRootCommands(buf, null);
    }

    @Override
    public void appendRootCommands(StringBuilder buf, Locale locale) {
        buf.append(CodeI18n.get(locale, CodeI18nKeys.CODE_LANG_PYTHON_ROOT)).append("\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        appendModuleCommands(buf, moduleName, null);
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName, Locale locale) {
        buf.append(CodeI18n.fmt(locale, CodeI18nKeys.CODE_LANG_PYTHON_MODULE, "module", moduleName)).append("\n\n");
    }
}