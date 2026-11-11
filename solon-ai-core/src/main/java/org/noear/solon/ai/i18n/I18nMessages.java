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

import org.noear.solon.core.Props;
import org.noear.solon.core.util.Assert;
import org.noear.solon.i18n.I18nBundle;
import org.noear.solon.i18n.I18nBundleFactory;
import org.noear.solon.i18n.impl.I18nBundleLocal;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个模块的文案（基于 solon-i18n）
 *
 * <p>文案资源按领域拆分为多个内容包（yml 格式），每个包的默认文件为英文（兜底），中文放在 {@code _zh} 文件中。
 * 用户可在自己的类路径下放置 {@code _ja} 等文件扩展语言。每个模块持有一个实例（常量），
 * 默认语言与覆盖工厂由 {@link AiI18n} 统一设置。</p>
 *
 * <p>约定：</p>
 * <ul>
 *     <li>不使用 {@code I18nUtil} 的全局工厂，避免用户替换 {@code I18nBundleFactory} 后缺 key；</li>
 *     <li>占位符只用命名形式 {@code {name}}（{@link AiI18n#format(String, Map)}），不走 MessageFormat 或表达式引擎；</li>
 *     <li>值只写正文，首尾不带换行或空格；段落之间的换行、空格由调用处的代码负责；</li>
 *     <li>资源文本禁止出现 "$&#123;" 与 "#&#123;"，以免被提示词的 SnEL 模板渲染误解析。</li>
 * </ul>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class I18nMessages {
    private static final Logger LOG = LoggerFactory.getLogger(I18nMessages.class);

    private final List<String> bundles;
    private final Map<Locale, Map<String, String>> cache = new ConcurrentHashMap<>();
    /**
     * 当前线程正在加载的语言（覆盖工厂回调同一实例、同一语言时快速失败，而不是无限递归）
     */
    private final ThreadLocal<Set<Locale>> loading = new ThreadLocal<>();

    /**
     * @param bundles 内容包名（如 {@code solon-ai-agent.i18n.react}，对应类路径下的 yml 文件）
     */
    public I18nMessages(String... bundles) {
        this(Arrays.asList(bundles));
    }

    public I18nMessages(List<String> bundles) {
        Assert.notEmpty(bundles, "bundles");
        this.bundles = Collections.unmodifiableList(new ArrayList<>(bundles));
        AiI18n.register(this);
    }

    /**
     * 内容包名
     */
    public List<String> bundles() {
        return bundles;
    }

    /**
     * 是否存在 key
     */
    public boolean has(Locale locale, String key) {
        return messages(locale).containsKey(key);
    }

    /**
     * 获取文案
     *
     * @throws MissingResourceException 找不到 key 时
     */
    public String get(Locale locale, String key) {
        String val = messages(locale).get(key);
        if (val == null) {
            throw new MissingResourceException("Can't find i18n resource, locale=" + AiI18n.resolve(locale)
                    + ", key=" + key + ", bundles=" + bundles, I18nMessages.class.getName(), key);
        }
        return val;
    }

    /**
     * 获取文案并替换一个命名占位符（如 {@code {role}}）
     */
    public String fmt(Locale locale, String key, String name, Object value) {
        return AiI18n.format(get(locale, key), Collections.singletonMap(name, value));
    }

    /**
     * 获取文案并替换两个命名占位符
     *
     * @throws IllegalArgumentException 变量名重复时（否则后者会静默覆盖前者，文本里残留未替换的占位符）
     */
    public String fmt(Locale locale, String key, String name1, Object value1, String name2, Object value2) {
        Map<String, Object> vars = new HashMap<>(4);
        putVar(vars, name1, value1);
        putVar(vars, name2, value2);
        return AiI18n.format(get(locale, key), vars);
    }

    /**
     * 获取文案并替换三个命名占位符
     *
     * @throws IllegalArgumentException 变量名重复时
     */
    public String fmt(Locale locale, String key, String name1, Object value1, String name2, Object value2,
                      String name3, Object value3) {
        Map<String, Object> vars = new HashMap<>(4);
        putVar(vars, name1, value1);
        putVar(vars, name2, value2);
        putVar(vars, name3, value3);
        return AiI18n.format(get(locale, key), vars);
    }

    /**
     * 获取文案并替换命名占位符
     */
    public String fmt(Locale locale, String key, Map<String, ?> vars) {
        return AiI18n.format(get(locale, key), vars);
    }

    private static void putVar(Map<String, Object> vars, String name, Object value) {
        if (vars.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate i18n variable name: " + name);
        }
        vars.put(name, value);
    }

    /**
     * 取某 key 在所有已知语言下的文案（用于识别标记）
     */
    public Set<String> textsOfAllLocales(String key) {
        Set<String> texts = new LinkedHashSet<>();
        for (Locale l : AiI18n.knownLocales()) {
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
    public Set<String> itemsOfAllLocales(String key) {
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

    /**
     * 获取某语言下合并后的全部文案（只读）
     */
    public Map<String, String> messages(Locale locale) {
        Locale l = AiI18n.resolve(locale);
        Map<String, String> map = cache.get(l);
        if (map == null) {
            map = loadAndCache(l);
        }
        return map;
    }

    /**
     * 清空本实例的缓存（全局清理请用 {@link AiI18n#clearCache()}）
     */
    void clearLocal() {
        cache.clear();
    }

    /**
     * 加载并缓存（在 ConcurrentHashMap 的锁外加载）
     *
     * <p>不用 computeIfAbsent：加载会读类路径、调用户工厂（可能是远程），不应持有缓存的桶锁；
     * 而且工厂回调本实例时，Java 8 的 computeIfAbsent 会在同一个桶上死循环。
     * 并发首次加载同一语言时可能重复加载一次，结果等价，后写入者生效。</p>
     */
    private Map<String, String> loadAndCache(Locale l) {
        Set<Locale> current = loading.get();
        if (current == null) {
            current = new HashSet<>();
            loading.set(current);
        }

        if (current.add(l) == false) {
            //覆盖工厂内又读取了同一语言：抛出后由 load() 的异常处理落回内置文案
            throw new IllegalStateException("Recursive i18n loading, locale=" + l + ", bundles=" + bundles
                    + " (the overlay factory must not read the same locale from the same messages)");
        }

        try {
            int gen = AiI18n.generation();
            Map<String, String> map = load(l);
            AiI18n.registerLocale(l);

            cache.put(l, map);
            if (gen != AiI18n.generation()) {
                //加载期间缓存被清空（如切换了覆盖工厂）：本次结果可能来自旧工厂，不保留
                cache.remove(l, map);
            }
            return map;
        } finally {
            current.remove(l);
            if (current.isEmpty()) {
                loading.remove();
            }
        }
    }

    private Map<String, String> load(Locale locale) {
        Map<String, String> map = new HashMap<>();
        I18nBundleFactory overlay = AiI18n.getBundleFactory();

        for (String bundleName : bundles) {
            //1.内置资源（底层，保证 key 齐全）
            putAll(map, new I18nBundleLocal(bundleName, locale));

            //2.用户工厂（覆盖层）
            if (overlay != null) {
                try {
                    putAll(map, overlay.create(bundleName, locale));
                } catch (RuntimeException e) {
                    LOG.warn("AI i18n overlay factory failed, use the built-in messages. bundle={}, locale={}",
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
