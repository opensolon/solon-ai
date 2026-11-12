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
package org.noear.solon.ai.talents.memory.i18n;

import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.I18nMessages;
import org.noear.solon.lang.Preview;

import java.util.Locale;
import java.util.Map;

/**
 * memory 才能的文案（资源：{@code solon-ai-talent-memory/i18n/memory.yml}（英文兜底）与 {@code memory_zh.yml}）
 *
 * <p>默认语言、覆盖工厂等全局设置见 {@link AiI18n}。key 常量见 {@link MemoryI18nKeys}。</p>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class MemoryI18n {
    private static final I18nMessages MESSAGES = new I18nMessages("solon-ai-talent-memory.i18n.memory");

    private MemoryI18n() {
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
}
