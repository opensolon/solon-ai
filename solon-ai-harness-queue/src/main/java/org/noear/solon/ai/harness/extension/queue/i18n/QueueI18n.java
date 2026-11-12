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
package org.noear.solon.ai.harness.extension.queue.i18n;

import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.I18nMessages;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.Locale;

/**
 * queue 插件的文案（资源：{@code solon-ai-harness-queue/i18n/queue.yml}（英文兜底）与 {@code queue_zh.yml}）
 *
 * <p>默认语言、覆盖工厂等全局设置见 {@link AiI18n}。key 常量见 {@link QueueI18nKeys}。
 * 命令输出跟随命令上下文的语言，门面结果说明跟随输入的语言；都没有时用默认语言。</p>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class QueueI18n {
    private static final I18nMessages MESSAGES = new I18nMessages("solon-ai-harness-queue.i18n.queue");

    private QueueI18n() {
    }

    public static I18nMessages messages() {
        return MESSAGES;
    }

    public static String get(@Nullable Locale locale, String key) {
        return MESSAGES.get(locale, key);
    }

    public static String fmt(@Nullable Locale locale, String key, String name, Object value) {
        return MESSAGES.fmt(locale, key, name, value);
    }
}
