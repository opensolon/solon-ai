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
package org.noear.solon.ai.talents.cli.i18n;

import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.I18nMessages;
import org.noear.solon.lang.Preview;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * cli 才能（Terminal / Skill / Clock）的文案（资源：{@code solon-ai-talent-cli.i18n.cli.yml}（英文兜底）与 {@code cli_zh.yml}）
 *
 * <p>默认语言、覆盖工厂等全局设置见 {@link AiI18n}。key 常量见 {@link CliI18nKeys}。</p>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class CliI18n {
    private static final I18nMessages MESSAGES = new I18nMessages("solon-ai-talent-cli.i18n.cli");

    private CliI18n() {
    }

    public static I18nMessages messages() {
        return MESSAGES;
    }

    public static String get(Locale locale, String key) {
        return MESSAGES.get(locale, key);
    }

    public static String fmt(Locale locale, String key, String name, Object value) {
        return MESSAGES.fmt(locale, key, name, value);
    }

    public static String fmt(Locale locale, String key, String name1, Object value1, String name2, Object value2) {
        return MESSAGES.fmt(locale, key, name1, value1, name2, value2);
    }

    public static String fmt(Locale locale, String key, String name1, Object value1, String name2, Object value2,
                             String name3, Object value3) {
        return MESSAGES.fmt(locale, key, name1, value1, name2, value2, name3, value3);
    }

    public static String fmt(Locale locale, String key, Map<String, ?> vars) {
        return MESSAGES.fmt(locale, key, vars);
    }

    /**
     * 异常消息（带文案 key 的异常按语言渲染，其它异常取原消息）
     */
    public static String messageOf(Throwable e, Locale locale) {
        if (e instanceof Localized) {
            return ((Localized) e).getMessage(locale);
        }
        return e.getMessage();
    }

    /**
     * 带文案 key 的异常：消息按语言渲染（{@link Throwable#getMessage()} 用绑定的语言，未绑定时用默认语言）
     */
    public interface Localized {
        String getMessage(Locale locale);
    }

    /**
     * 异常的文案（key + 命名参数）
     */
    public static final class Text {
        public final String key;
        public final Map<String, Object> vars;

        private Text(String key, Map<String, Object> vars) {
            this.key = key;
            this.vars = vars;
        }

        public static Text of(String key) {
            return new Text(key, Collections.<String, Object>emptyMap());
        }

        public static Text of(String key, String name, Object value) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put(name, value);
            return new Text(key, vars);
        }

        public String render(Locale locale) {
            return vars.isEmpty() ? get(locale, key) : fmt(locale, key, vars);
        }
    }
}
