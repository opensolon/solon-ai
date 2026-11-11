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
package org.noear.solon.ai.i18n;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.core.util.Assert;
import org.noear.solon.i18n.I18nBundleFactory;
import org.noear.solon.lang.Preview;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 国际化的全局部分（各模块共享）
 *
 * <p>默认语言、覆盖工厂、已知语言、请求语言的传递方式在这里统一设置，智能体、harness 与各 talent 一致；
 * 文案本身由各模块的 {@link I18nMessages} 实例加载。</p>
 *
 * <p>请求语言的传递：智能体把语言标签写入 {@code Prompt.attrs} 与工具上下文，键为 {@link #ATTR_LOCALE}。
 * Talent 在 {@code getInstruction(prompt)} / {@code getTools(prompt)} 中用 {@link #localeOf(Prompt)} 读取；
 * 工具方法声明 {@code String __locale} 参数即可拿到（与 {@code __cwd} 一样由工具上下文注入），
 * 再用 {@link #toLocale(Object)} 转换。</p>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class AiI18n {
    /**
     * 请求语言在 Prompt.attrs 与工具上下文中的键（值为语言标签字符串，如 "en"、"zh-CN"）
     */
    public static final String ATTR_LOCALE = "__locale";

    /**
     * 占位符名字的最大长度（名字只能是 [A-Za-z_][A-Za-z0-9_]*）
     */
    private static final int NAME_MAX_LENGTH = 64;

    private static volatile Locale defaultLocale = Locale.CHINESE;
    private static volatile I18nBundleFactory bundleFactory;

    /**
     * 缓存代际：每次清空缓存加一（加载期间代际变化的结果不入缓存；派生缓存据此失效）
     */
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final Set<Locale> KNOWN_LOCALES = new CopyOnWriteArraySet<>(Arrays.asList(Locale.CHINESE, Locale.ENGLISH));
    /**
     * 已创建的文案实例（弱引用：清空缓存时逐个清理）
     */
    private static final Map<I18nMessages, Boolean> INSTANCES = new WeakHashMap<>();

    private AiI18n() {
    }

    // ==================== 全局设置 ====================

    /**
     * 获取默认语言（未显式设置 locale 时使用；默认中文）
     */
    public static Locale getDefaultLocale() {
        return defaultLocale;
    }

    /**
     * 设置默认语言
     */
    public static void setDefaultLocale(Locale locale) {
        Assert.notNull(locale, "locale");
        defaultLocale = locale;
    }

    /**
     * 获取覆盖用的内容包工厂（可能为 null）
     */
    public static I18nBundleFactory getBundleFactory() {
        return bundleFactory;
    }

    /**
     * 设置覆盖用的内容包工厂（作用于所有 AI 模块的文案），支持分布式 i18n
     *
     * <p>内置资源文件始终作为底层；工厂返回的文案只覆盖同名 key，缺失的 key 自动落回内置文案。
     * 工厂按内容包名区分模块（如 {@code solon-ai-agent.i18n.react}）。传入 null 表示取消覆盖。</p>
     */
    public static void setBundleFactory(I18nBundleFactory factory) {
        bundleFactory = factory;
        clearCache();
    }

    /**
     * 清空所有模块的文案缓存（例如热更新资源后）
     */
    public static void clearCache() {
        GENERATION.incrementAndGet();

        I18nMessages[] all;
        synchronized (INSTANCES) {
            all = INSTANCES.keySet().toArray(new I18nMessages[0]);
        }

        for (I18nMessages m : all) {
            m.clearLocal();
        }
    }

    /**
     * 缓存代际（每次清空缓存加一；加载期间被清空时，加载结果不入缓存）
     */
    static int generation() {
        return GENERATION.get();
    }

    static void register(I18nMessages messages) {
        synchronized (INSTANCES) {
            INSTANCES.put(messages, Boolean.TRUE);
        }
    }

    // ==================== 语言 ====================

    /**
     * 归一化 locale（null 时使用默认语言）
     */
    public static Locale resolve(Locale locale) {
        return locale == null ? defaultLocale : locale;
    }

    /**
     * 是否为中文
     */
    public static boolean isChinese(Locale locale) {
        return "zh".equals(resolve(locale).getLanguage());
    }

    /**
     * 将请求语言写入 Prompt.attrs（存为语言标签字符串，保证会话快照可序列化与还原；不要直接存 Locale 对象）
     */
    public static void putLocale(Prompt prompt, Locale locale) {
        if (prompt == null) {
            return;
        }

        prompt.attrs().put(ATTR_LOCALE, toTag(locale));
    }

    /**
     * 语言标签（null 时为默认语言的标签）
     */
    public static String toTag(Locale locale) {
        return resolve(locale).toLanguageTag();
    }

    /**
     * 从 Prompt.attrs 取请求语言（取不到时返回 null）
     *
     * <p>无副作用：识别旧会话消息中的标记请用 {@link #registerLocaleOf(Prompt)}。</p>
     */
    public static Locale localeOf(Prompt prompt) {
        if (prompt == null) {
            return null;
        }

        return toLocale(prompt.attrs().get(ATTR_LOCALE));
    }

    /**
     * 把语言值转为 Locale（取不到时返回 null）
     *
     * <p>支持 Locale 对象与语言标签字符串（会话持久化后会还原为字符串，如 "en"、"zh_CN"、"zh-CN"）；
     * 工具方法的 {@code __locale} 参数也用它转换。</p>
     */
    public static Locale toLocale(Object val) {
        if (val instanceof Locale) {
            return (Locale) val;
        }

        if (val instanceof String && ((String) val).trim().length() > 0) {
            Locale l = Locale.forLanguageTag(((String) val).trim().replace('_', '-'));
            return l.getLanguage().isEmpty() ? null : l;
        }

        return null;
    }

    /**
     * 从 Prompt 携带的语言标签登记语言（会话恢复时使用）
     *
     * <p>从 Redis/File 恢复的旧会话可能使用其它语言，其消息中的标记需在所有已知语言中识别。
     * 智能体在每次执行的准备阶段会自动调用本方法。</p>
     */
    public static void registerLocaleOf(Prompt prompt) {
        Locale locale = localeOf(prompt);
        if (locale != null) {
            KNOWN_LOCALES.add(locale);
        }
    }

    /**
     * 登记语言（参与逻辑判断的标记与关键词会覆盖所有已知语言）
     *
     * <p>内置 zh/en 已登记；其它语言在首次加载文案时自动登记。</p>
     */
    public static void registerLocale(Locale locale) {
        Assert.notNull(locale, "locale");
        KNOWN_LOCALES.add(locale);
    }

    /**
     * 已知语言（内置 + 已使用 + 已登记 + 默认语言）
     */
    public static Set<Locale> knownLocales() {
        Set<Locale> set = new LinkedHashSet<>(KNOWN_LOCALES);
        set.add(defaultLocale);
        return Collections.unmodifiableSet(set);
    }

    // ==================== 占位符 ====================

    /**
     * 替换命名占位符 {@code {name}}（单次扫描，变量内容不会被二次替换）
     *
     * <ul>
     *     <li>名字只能是 {@code [A-Za-z_][A-Za-z0-9_]*}，最长 64 个字符（与资源检查的规则一致）；</li>
     *     <li>只替换 vars 中存在的名字；其它花括号（JSON 示例、{@code {...}}、{@code {0}}、未闭合、
     *     未提供的名字）原样保留；</li>
     *     <li>变量值按 {@link StringBuilder#append(Object)} 输出（null 输出为 "null"，与字符串拼接一致）；</li>
     *     <li>不做表达式求值；耗时与模板长度成线性关系。</li>
     * </ul>
     */
    public static String format(String tml, Map<String, ?> vars) {
        if (tml == null || vars == null || vars.isEmpty()) {
            return tml;
        }

        int start = tml.indexOf('{');
        if (start < 0) {
            return tml;
        }

        StringBuilder sb = new StringBuilder(tml.length() + 32);
        int copied = 0;
        while (start >= 0) {
            int end = nameEnd(tml, start + 1);
            if (end > 0) {
                String name = tml.substring(start + 1, end);
                if (vars.containsKey(name)) {
                    sb.append(tml, copied, start).append(vars.get(name));
                    copied = end + 1;
                }
            }
            start = tml.indexOf('{', start + 1);
        }
        return sb.append(tml, copied, tml.length()).toString();
    }

    /**
     * 从 from 起识别占位符名字，返回闭合 '}' 的位置；不是合法名字时返回 -1
     *
     * <p>遇到第一个非名字字符即停止（名字里不会有 '{'），所以每个字符最多被扫描一次。</p>
     */
    private static int nameEnd(String tml, int from) {
        int limit = Math.min(tml.length(), from + NAME_MAX_LENGTH + 1);
        for (int j = from; j < limit; j++) {
            char c = tml.charAt(j);
            if (c == '}') {
                return j > from ? j : -1;
            }

            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'
                    || (j > from && c >= '0' && c <= '9');
            if (ok == false) {
                return -1;
            }
        }
        return -1;
    }
}
