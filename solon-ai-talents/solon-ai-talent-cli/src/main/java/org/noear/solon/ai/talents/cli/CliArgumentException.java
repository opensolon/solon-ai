/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.talents.cli.i18n.CliI18n;

import java.util.Locale;

/**
 * 编辑匹配失败（消息按语言渲染，见 {@link CliI18n.Localized}）
 *
 * @author noear
 * @since 4.2
 */
final class CliArgumentException extends IllegalArgumentException implements CliI18n.Localized {
    private final CliI18n.Text text;

    CliArgumentException(String key) {
        super(key);
        this.text = CliI18n.Text.of(key);
    }

    @Override
    public String getMessage() {
        return text.render(null);
    }

    @Override
    public String getMessage(Locale locale) {
        return text.render(locale);
    }
}
