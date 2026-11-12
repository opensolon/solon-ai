/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.extension.goal.i18n;

import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.I18nMessages;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.Locale;
import java.util.Set;

/**
 * goal 插件的文案（资源：{@code solon-ai-harness-goal/i18n/goal.yml}（英文兜底）与 {@code goal_zh.yml}）
 *
 * <p>默认语言、覆盖工厂等全局设置见 {@link AiI18n}。key 常量见 {@link GoalI18nKeys}。</p>
 *
 * <p>语言来源：轮次提示词、轮次结果记录、门面结果说明跟随引擎实例的语言（{@code HarnessEngine#getLocale}，
 * goal 轮次由后台调度发起、不带输入语言）；工具描述跟随请求，工具返回值跟随工具参数 {@code __locale}；
 * 命令输出跟随命令上下文；验证说明跟随调用方传入的语言。都没有时用默认语言。</p>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class GoalI18n {
    private static final I18nMessages MESSAGES = new I18nMessages("solon-ai-harness-goal.i18n.goal");

    private GoalI18n() {
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

    public static String fmt(@Nullable Locale locale, String key, String name1, Object value1,
                             String name2, Object value2) {
        return MESSAGES.fmt(locale, key, name1, value1, name2, value2);
    }

    public static String fmt(@Nullable Locale locale, String key, String name1, Object value1,
                             String name2, Object value2, String name3, Object value3) {
        return MESSAGES.fmt(locale, key, name1, value1, name2, value2, name3, value3);
    }

    /**
     * 某 key 在所有已知语言下的列表项（用于关键词识别）
     */
    public static Set<String> itemsOfAllLocales(String key) {
        return MESSAGES.itemsOfAllLocales(key);
    }
}
