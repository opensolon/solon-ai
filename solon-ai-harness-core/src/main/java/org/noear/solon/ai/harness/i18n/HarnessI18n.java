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
package org.noear.solon.ai.harness.i18n;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.harness.util.TraceUtil;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.I18nMessages;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.Locale;
import java.util.Map;

/**
 * harness-core 的文案（资源：{@code solon-ai-harness-core/i18n/harness.yml}（英文兜底）与 {@code harness_zh.yml}）
 *
 * <p>默认语言、覆盖工厂等全局设置见 {@link AiI18n}。key 常量见 {@link HarnessI18nKeys}。</p>
 *
 * <p>语言来源：给模型看的文案跟随请求（{@code Prompt} 的语言属性、工具参数 {@code __locale}、轨迹的 locale）；
 * 给用户看的文案跟随输入的生效语言 {@code HarnessEngine#localeOf(HarnessInput)}（输入优先，引擎实例兜底）；都没有时用默认语言。</p>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class HarnessI18n {
    private static final I18nMessages MESSAGES = new I18nMessages("solon-ai-harness-core.i18n.harness");

    private HarnessI18n() {
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

    public static String fmt(@Nullable Locale locale, String key, String name1, Object value1, String name2, Object value2) {
        return MESSAGES.fmt(locale, key, name1, value1, name2, value2);
    }

    public static String fmt(@Nullable Locale locale, String key, String name1, Object value1, String name2, Object value2,
                             String name3, Object value3) {
        return MESSAGES.fmt(locale, key, name1, value1, name2, value2, name3, value3);
    }

    public static String fmt(@Nullable Locale locale, String key, Map<String, ?> vars) {
        return MESSAGES.fmt(locale, key, vars);
    }

    /**
     * 轨迹的语言：这一轮运行的语言（原始提示词上的语言）优先，没有时用轨迹选项的语言。
     *
     * <p>续流轮（HITL 审批后继续、resume）不重设选项语言，此时只有原始提示词上的语言能代表这一轮。</p>
     *
     * @return 轨迹或其选项为空时返回 null，即默认语言
     */
    public static @Nullable Locale localeOf(@Nullable ReActTrace trace) {
        if (trace == null) {
            return null;
        }

        Locale runLocale = trace.getRunLocale();
        if (runLocale != null) {
            return runLocale;
        }

        return trace.getOptions() == null ? null : trace.getOptions().getLocale();
    }

    /**
     * 会话的语言（会话当前轨迹的语言）。
     *
     * <p>宿主只需要“这个会话/这一轮的文案用什么语言”时用它，不必自己去取轨迹再取语言。</p>
     *
     * @param session 会话（可为 null）
     * @return 无会话或无轨迹时返回 null，即默认语言
     * @since 4.2.0
     */
    public static @Nullable Locale localeOf(@Nullable AgentSession session) {
        return localeOf(TraceUtil.getCurrentTrace(session));
    }
}
