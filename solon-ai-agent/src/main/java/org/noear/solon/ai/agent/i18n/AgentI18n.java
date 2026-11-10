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
package org.noear.solon.ai.agent.i18n;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.core.Props;
import org.noear.solon.core.util.Assert;
import org.noear.solon.i18n.I18nBundle;
import org.noear.solon.i18n.I18nBundleFactory;
import org.noear.solon.i18n.impl.I18nBundleLocal;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智能体国际化工具（基于 solon-i18n）
 *
 * <p>文案资源按领域拆分为多个内容包（{@link #BUNDLES}，yml 格式），每个包的默认文件为英文（兜底），
 * 中文放在 {@code _zh} 文件中。用户可在自己的类路径下放置 {@code _ja} 等文件扩展语言，
 * 或放置 {@code _zh_CN} 等文件覆盖个别 key。</p>
 *
 * <p>locale 决定规则（只有一个来源：配置）：{@code options.locale}（显式设置） &gt;
 * 所在团队的语言（作为成员时继承） &gt; {@link #getDefaultLocale()}。
 * 提示词模板不自带语言，只按给定语言渲染。</p>
 *
 * <p>参与逻辑判断的标记与关键词通过 {@link #textsOfAllLocales(String)} / {@link #itemsOfAllLocales(String)}
 * 在所有已知语言中合并识别，新增语言只需加资源文件。</p>
 *
 * <p>注意：</p>
 * <ul>
 *     <li>不使用 {@code I18nUtil} 的全局工厂，避免用户替换 {@code I18nBundleFactory} 后智能体缺 key；</li>
 *     <li>占位符只用命名形式 {@code {name}}（{@link #format(String, Map)}），不走 MessageFormat 或表达式引擎（文案中含 JSON 示例与引号）；</li>
 *     <li>值只写正文，首尾不带换行或空格；段落之间的换行、空格由调用处的代码负责（扩展语言时无需关心空白）；</li>
 *     <li>资源文本禁止出现 "$&#123;" 与 "#&#123;"，以免被提示词的 SnEL 模板渲染误解析；</li>
 *     <li>恢复持久化会话时，智能体会自动登记快照携带的语言（{@link #registerLocaleOf(Prompt)}）。</li>
 * </ul>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class AgentI18n {
    /**
     * 内置内容包（按领域拆分）
     */
    public static final List<String> BUNDLES = Collections.unmodifiableList(Arrays.asList(
            "solon-ai-agent.i18n.react",
            "solon-ai-agent.i18n.team",
            "solon-ai-agent.i18n.team-protocol",
            "solon-ai-agent.i18n.compress"));

    /**
     * 请求语言在 Prompt.attrs 中的键（供拿不到 trace 的 Talent 读取）
     */
    public static final String ATTR_LOCALE = "_agent_locale";

    private static final Logger LOG = LoggerFactory.getLogger(AgentI18n.class);
    /**
     * 占位符名字的最大长度（名字只能是 [A-Za-z_][A-Za-z0-9_]*）
     */
    private static final int NAME_MAX_LENGTH = 64;

    private static volatile Locale defaultLocale = Locale.CHINESE;
    private static volatile I18nBundleFactory bundleFactory;
    private static final Map<Locale, Map<String, String>> CACHE = new ConcurrentHashMap<>();
    /**
     * 缓存代际：每次清空缓存加一，加载期间代际变化的结果不入缓存（避免旧工厂的文案在清空后又被写回）
     */
    private static final AtomicInteger GENERATION = new AtomicInteger();
    /**
     * 当前线程正在加载的语言（覆盖工厂回调同一语言时快速失败，而不是无限递归）
     */
    private static final ThreadLocal<Set<Locale>> LOADING = new ThreadLocal<>();
    private static final Set<Locale> KNOWN_LOCALES = new CopyOnWriteArraySet<>(Arrays.asList(Locale.CHINESE, Locale.ENGLISH));

    private AgentI18n() {
    }

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
     * 设置覆盖用的内容包工厂（只作用于智能体），支持分布式 i18n
     *
     * <p>内置资源文件始终作为底层；工厂返回的文案只覆盖同名 key，缺失的 key 自动落回内置文案。
     * 传入 null 表示取消覆盖。</p>
     */
    public static void setBundleFactory(I18nBundleFactory factory) {
        bundleFactory = factory;
        clearCache();
    }

    /**
     * 清空缓存（例如热更新资源后）
     */
    public static void clearCache() {
        GENERATION.incrementAndGet();
        CACHE.clear();
    }

    /**
     * 归一化 locale（null 时使用默认语言）
     */
    public static Locale resolve(Locale locale) {
        return locale == null ? defaultLocale : locale;
    }

    /**
     * 将请求语言写入 Prompt.attrs（存为语言标签字符串，保证会话快照可序列化与还原；不要直接存 Locale 对象）
     */
    public static void putLocale(Prompt prompt, Locale locale) {
        if (prompt == null) {
            return;
        }

        prompt.attrs().put(ATTR_LOCALE, resolve(locale).toLanguageTag());
    }

    /**
     * 从 Prompt.attrs 取请求语言（由智能体在激活 Talent 前写入；取不到时返回 null）
     *
     * <p>无副作用：识别旧会话消息中的标记请用 {@link #registerLocaleOf(Prompt)}。</p>
     */
    public static Locale localeOf(Prompt prompt) {
        if (prompt == null) {
            return null;
        }

        Object val = prompt.attrs().get(ATTR_LOCALE);
        if (val instanceof Locale) {
            return (Locale) val;
        }

        //会话持久化后可能被还原为字符串（如 "en"、"zh_CN"、"zh-CN"）
        if (val instanceof String && ((String) val).trim().length() > 0) {
            Locale l = Locale.forLanguageTag(((String) val).trim().replace('_', '-'));
            return l.getLanguage().isEmpty() ? null : l;
        }

        return null;
    }

    /**
     * 是否为中文
     */
    public static boolean isChinese(Locale locale) {
        return "zh".equals(resolve(locale).getLanguage());
    }

    /**
     * 是否存在 key
     */
    public static boolean has(Locale locale, String key) {
        return messages(locale).containsKey(key);
    }

    /**
     * 获取文案
     *
     * @throws MissingResourceException 找不到 key 时
     */
    public static String get(Locale locale, String key) {
        String val = messages(locale).get(key);
        if (val == null) {
            throw new MissingResourceException("Can't find agent i18n resource, locale=" + resolve(locale) + ", key=" + key,
                    AgentI18n.class.getName(), key);
        }
        return val;
    }

    /**
     * 获取文案并替换一个命名占位符（如 {@code {role}}）
     */
    public static String fmt(Locale locale, String key, String name, Object value) {
        return format(get(locale, key), Collections.singletonMap(name, value));
    }

    /**
     * 获取文案并替换两个命名占位符
     *
     * @throws IllegalArgumentException 变量名重复时（否则后者会静默覆盖前者，提示词里残留未替换的占位符）
     */
    public static String fmt(Locale locale, String key, String name1, Object value1, String name2, Object value2) {
        Map<String, Object> vars = new HashMap<>(4);
        putVar(vars, name1, value1);
        putVar(vars, name2, value2);
        return format(get(locale, key), vars);
    }

    /**
     * 获取文案并替换三个命名占位符
     *
     * @throws IllegalArgumentException 变量名重复时
     */
    public static String fmt(Locale locale, String key, String name1, Object value1, String name2, Object value2,
                             String name3, Object value3) {
        Map<String, Object> vars = new HashMap<>(4);
        putVar(vars, name1, value1);
        putVar(vars, name2, value2);
        putVar(vars, name3, value3);
        return format(get(locale, key), vars);
    }

    private static void putVar(Map<String, Object> vars, String name, Object value) {
        if (vars.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate agent i18n variable name: " + name);
        }
        vars.put(name, value);
    }

    /**
     * 获取文案并替换命名占位符
     */
    public static String fmt(Locale locale, String key, Map<String, ?> vars) {
        return format(get(locale, key), vars);
    }

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

    /**
     * 获取某语言下合并后的全部文案（只读）
     */
    public static Map<String, String> messages(Locale locale) {
        Locale l = resolve(locale);
        Map<String, String> map = CACHE.get(l);
        if (map == null) {
            map = loadAndCache(l);
        }
        return map;
    }

    /**
     * 加载并缓存（在 ConcurrentHashMap 的锁外加载）
     *
     * <p>不用 computeIfAbsent：加载会读类路径、调用户工厂（可能是远程），不应持有缓存的桶锁；
     * 而且工厂回调 AgentI18n 时，Java 8 的 computeIfAbsent 会在同一个桶上死循环。
     * 并发首次加载同一语言时可能重复加载一次，结果等价，后写入者生效。</p>
     */
    private static Map<String, String> loadAndCache(Locale l) {
        Set<Locale> loading = LOADING.get();
        if (loading == null) {
            loading = new HashSet<>();
            LOADING.set(loading);
        }

        if (loading.add(l) == false) {
            //覆盖工厂内又读取了同一语言：抛出后由 load() 的异常处理落回内置文案
            throw new IllegalStateException("Recursive agent i18n loading, locale=" + l
                    + " (the overlay factory must not read the same locale from AgentI18n)");
        }

        try {
            int gen = GENERATION.get();
            Map<String, String> map = load(l);
            KNOWN_LOCALES.add(l);

            CACHE.put(l, map);
            if (gen != GENERATION.get()) {
                //加载期间缓存被清空（如切换了覆盖工厂）：本次结果可能来自旧工厂，不保留
                CACHE.remove(l, map);
            }
            return map;
        } finally {
            loading.remove(l);
            if (loading.isEmpty()) {
                LOADING.remove();
            }
        }
    }

    /**
     * 从 Prompt 携带的语言标签登记语言（会话恢复时使用）
     *
     * <p>从 Redis/File 恢复的旧会话可能使用其它语言，其消息中的标记（无增量、归档等）需在所有已知
     * 语言中识别。智能体在每次执行的准备阶段会自动调用本方法，因此恢复会话时无需手动登记；
     * 自定义 Trace 实现可自行调用。</p>
     */
    public static void registerLocaleOf(Prompt prompt) {
        Locale locale = localeOf(prompt);
        if (locale != null) {
            KNOWN_LOCALES.add(locale);
        }
    }

    /**
     * 登记语言（会话可能先后出现不同语言，参与逻辑判断的标记与关键词会覆盖所有已知语言）
     *
     * <p>内置 zh/en 已登记；其它语言在首次使用时自动登记。恢复持久化会话时智能体会自动登记，
     * 仅当自定义 Trace 或自行拼接历史时需主动调用。</p>
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

    /**
     * 取某 key 在所有已知语言下的文案（用于识别标记）
     */
    public static Set<String> textsOfAllLocales(String key) {
        Set<String> texts = new LinkedHashSet<>();
        for (Locale l : knownLocales()) {
            String val = messages(l).get(key);
            if (val != null) {
                texts.add(val);
            }
        }
        return texts;
    }

    /**
     * 取某 key 在所有已知语言下的列表项（值以 "|" 分隔，去空白、去重；用于关键词识别）
     */
    public static Set<String> itemsOfAllLocales(String key) {
        Set<String> items = new LinkedHashSet<>();
        for (String text : textsOfAllLocales(key)) {
            for (String item : text.split("\\|")) {
                String trimmed = item.trim();
                if (trimmed.length() > 0) {
                    items.add(trimmed);
                }
            }
        }
        return items;
    }

    private static Map<String, String> load(Locale locale) {
        Map<String, String> map = new HashMap<>();
        I18nBundleFactory overlay = bundleFactory;

        for (String bundleName : BUNDLES) {
            //1.内置资源（底层，保证 key 齐全）
            putAll(map, new I18nBundleLocal(bundleName, locale));

            //2.用户工厂（覆盖层）
            if (overlay != null) {
                try {
                    putAll(map, overlay.create(bundleName, locale));
                } catch (RuntimeException e) {
                    LOG.warn("Agent i18n overlay factory failed, use the built-in messages. bundle={}, locale={}",
                            bundleName, locale, e);
                }
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static void putAll(Map<String, String> map, I18nBundle bundle) {
        Props props = (bundle == null ? null : bundle.toProps());
        if (props == null) {
            return;
        }

        //Props 基于 Properties，key / value 都不会为 null
        props.forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
    }
}
