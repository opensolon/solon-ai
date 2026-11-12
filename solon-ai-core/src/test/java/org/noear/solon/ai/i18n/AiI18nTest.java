package org.noear.solon.ai.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.core.Props;
import org.noear.solon.i18n.I18nBundle;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AiI18n 与 I18nMessages（行 / 分支 100%）
 */
public class AiI18nTest {
    static final String BUNDLE = "solon-ai-core-test.i18n.demo";
    static final I18nMessages DEMO = new I18nMessages(BUNDLE);

    @AfterEach
    public void reset() {
        AiI18n.setDefaultLocale(Locale.CHINESE);
        AiI18n.setBundleFactory(null);
    }

    // ==================== 全局设置 ====================

    @Test
    public void testDefaultLocale() {
        Assertions.assertEquals(Locale.CHINESE, AiI18n.getDefaultLocale());
        Assertions.assertEquals("你好 x", DEMO.fmt(null, "demo.hello", "name", "x"));

        AiI18n.setDefaultLocale(Locale.ENGLISH);
        Assertions.assertEquals(Locale.ENGLISH, AiI18n.getDefaultLocale());
        Assertions.assertEquals("Hello x", DEMO.fmt(null, "demo.hello", "name", "x"));
        Assertions.assertFalse(AiI18n.isChinese(null));
        Assertions.assertTrue(AiI18n.isChinese(Locale.CHINA));

        Assertions.assertThrows(NullPointerException.class, () -> AiI18n.setDefaultLocale(null));
        Assertions.assertThrows(NullPointerException.class, () -> AiI18n.registerLocale(null));
    }

    @Test
    public void testClearCacheAndGeneration() {
        Assertions.assertNotNull(DEMO.get(Locale.ENGLISH, "demo.hello"));
        int gen = AiI18n.generation();

        AiI18n.clearCache();

        Assertions.assertEquals(gen + 1, AiI18n.generation());
        Assertions.assertNotNull(DEMO.get(Locale.ENGLISH, "demo.hello"));
    }

    @Test
    public void testBundleFactoryGetter() {
        Assertions.assertNull(AiI18n.getBundleFactory());
        AiI18n.setBundleFactory((name, l) -> null);
        Assertions.assertNotNull(AiI18n.getBundleFactory());
    }

    // ==================== 语言 ====================

    @Test
    public void testPromptLocale() {
        Prompt prompt = Prompt.of("hi");
        Assertions.assertSame(prompt, prompt.locale(Locale.ENGLISH));
        Assertions.assertEquals("en", prompt.attrs().get(AiI18n.ATTR_LOCALE));
        Assertions.assertEquals(Locale.ENGLISH, prompt.locale());
        Assertions.assertEquals(Locale.ENGLISH, AiI18n.localeOf(prompt));

        prompt.locale(Locale.CHINA);
        Assertions.assertEquals("zh-CN", prompt.attrs().get(AiI18n.ATTR_LOCALE));
        Assertions.assertEquals(Locale.CHINA, prompt.locale());

        //null 表示移除
        prompt.locale(null);
        Assertions.assertFalse(prompt.attrs().containsKey(AiI18n.ATTR_LOCALE));
        Assertions.assertNull(prompt.locale());

        //语言值的各种形态（prompt.locale() 与工具参数 __locale 都按此规则解析）
        Assertions.assertEquals(Locale.ENGLISH, AiI18n.toLocale(Locale.ENGLISH));
        Assertions.assertEquals(Locale.ENGLISH, AiI18n.toLocale("en"));
        Assertions.assertEquals(Locale.SIMPLIFIED_CHINESE, AiI18n.toLocale("zh_CN"));
        Assertions.assertEquals(Locale.SIMPLIFIED_CHINESE, AiI18n.toLocale("zh-CN"));
        Assertions.assertEquals(Locale.ENGLISH, AiI18n.toLocale("  en  "));
        for (Object val : new Object[]{null, "", "   ", "!!!", 123}) {
            Assertions.assertNull(AiI18n.toLocale(val), String.valueOf(val));
        }

        Assertions.assertEquals("zh-CN", AiI18n.toTag(Locale.CHINA));
    }

    @Test
    public void testPromptLocaleCopy() {
        Prompt prompt = Prompt.of("hi").locale(Locale.ENGLISH);
        Prompt copy = prompt.copy();
        Assertions.assertEquals(Locale.ENGLISH, copy.locale());

        copy.locale(Locale.CHINESE);
        Assertions.assertEquals(Locale.ENGLISH, prompt.locale(), "副本独立");
    }

    @Test
    public void testLocaleOfVariants() {
        Assertions.assertNull(AiI18n.localeOf(null));
        Assertions.assertNull(AiI18n.localeOf(Prompt.of("hi")));

        Assertions.assertEquals(Locale.ENGLISH, AiI18n.toLocale(Locale.ENGLISH));
        Assertions.assertEquals(Locale.ENGLISH, AiI18n.toLocale("en"));
        Assertions.assertEquals(Locale.SIMPLIFIED_CHINESE, AiI18n.toLocale("zh-CN"));
        Assertions.assertEquals(Locale.SIMPLIFIED_CHINESE, AiI18n.toLocale("zh_CN"));
        Assertions.assertEquals(Locale.ENGLISH, AiI18n.toLocale("  en  "));
        Assertions.assertNull(AiI18n.toLocale(""));
        Assertions.assertNull(AiI18n.toLocale("   "));
        Assertions.assertNull(AiI18n.toLocale("!!!"));
        Assertions.assertNull(AiI18n.toLocale(123));
        Assertions.assertNull(AiI18n.toLocale(null));
    }

    @Test
    public void testRegisterLocaleOf() {
        Locale unique = uniqueLocale();

        AiI18n.registerLocaleOf(Prompt.of("hi"));
        Assertions.assertFalse(AiI18n.knownLocales().contains(unique));

        Prompt prompt = Prompt.of("hi");
        prompt.locale(unique);
        AiI18n.registerLocaleOf(prompt);
        Assertions.assertTrue(AiI18n.knownLocales().contains(unique));

        Set<Locale> known = AiI18n.knownLocales();
        Assertions.assertTrue(known.contains(Locale.CHINESE));
        Assertions.assertTrue(known.contains(Locale.ENGLISH));
    }

    // ==================== 占位符 ====================

    @Test
    public void testFormatEdgeCases() {
        Map<String, Object> a = Collections.singletonMap("a", "A");

        Assertions.assertNull(AiI18n.format(null, a));
        Assertions.assertEquals("x {a}", AiI18n.format("x {a}", null));
        Assertions.assertEquals("x {a}", AiI18n.format("x {a}", Collections.emptyMap()));
        Assertions.assertEquals("abc", AiI18n.format("abc", a));

        Assertions.assertEquals("{} A", AiI18n.format("{} {a}", a));
        Assertions.assertEquals("x { y", AiI18n.format("x { y", a));
        String longName = repeat('n', 65);
        Assertions.assertEquals("{" + longName + "}", AiI18n.format("{" + longName + "}", Collections.singletonMap(longName, "X")));
        String maxName = repeat('m', 64);
        Assertions.assertEquals("X", AiI18n.format("{" + maxName + "}", Collections.singletonMap(maxName, "X")));
        Assertions.assertEquals("{b}", AiI18n.format("{b}", a));
        Assertions.assertEquals("{b}", AiI18n.format("{a}", Collections.singletonMap("a", "{b}")));
        Assertions.assertEquals("null", AiI18n.format("{a}", Collections.singletonMap("a", null)));
    }

    @Test
    public void testFormatNameRules() {
        for (String name : new String[]{"", "a b", " a ", "\"q\"", "a.b", "a-b", "a:b", "1a", "0", "角色", "a角"}) {
            String tml = "x {" + name + "} y";
            Assertions.assertEquals(tml, AiI18n.format(tml, Collections.singletonMap(name, "X")), name);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("_x1", "A");
        vars.put("Role", "B");
        Assertions.assertEquals("A B", AiI18n.format("{_x1} {Role}", vars));
        Assertions.assertEquals("x {a", AiI18n.format("x {a", Collections.singletonMap("a", "A")));
        Assertions.assertEquals("{A}", AiI18n.format("{{a}}", Collections.singletonMap("a", "A")));
        Assertions.assertEquals("{\"a\": 1} A", AiI18n.format("{\"a\": 1} {a}", Collections.singletonMap("a", "A")));
    }

    @Test
    public void testFormatIsLinear() {
        String tml = repeat('{', 200_000) + "{a}";
        long start = System.nanoTime();
        String out = AiI18n.format(tml, Collections.singletonMap("a", "A"));
        long ms = (System.nanoTime() - start) / 1_000_000;

        Assertions.assertEquals(repeat('{', 200_000) + "A", out);
        Assertions.assertTrue(ms < 500, "format took " + ms + "ms");
    }

    // ==================== I18nMessages ====================

    @Test
    public void testMessagesBasics() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new I18nMessages(Collections.<String>emptyList()));
        Assertions.assertEquals(Collections.singletonList(BUNDLE), DEMO.bundles());

        Assertions.assertTrue(DEMO.has(Locale.ENGLISH, "demo.hello"));
        Assertions.assertFalse(DEMO.has(Locale.ENGLISH, "no.such"));
        MissingResourceException e = Assertions.assertThrows(MissingResourceException.class,
                () -> DEMO.get(Locale.ENGLISH, "no.such"));
        Assertions.assertTrue(e.getMessage().contains(BUNDLE), e.getMessage());

        Assertions.assertEquals("1 与 2", DEMO.fmt(Locale.CHINESE, "demo.pair", "a", 1, "b", 2));
        Assertions.assertEquals("1, 2, 3", DEMO.fmt(Locale.ENGLISH, "demo.triple", "a", 1, "b", 2, "c", 3));
        Assertions.assertEquals("Hello m", DEMO.fmt(Locale.ENGLISH, "demo.hello", Collections.singletonMap("name", "m")));
        //地区变体落到语言文件
        Assertions.assertEquals("你好 x", DEMO.fmt(Locale.CHINA, "demo.hello", "name", "x"));
    }

    @Test
    public void testFmtDuplicateNames() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> DEMO.fmt(Locale.ENGLISH, "demo.pair", "a", 1, "a", 2));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> DEMO.fmt(Locale.ENGLISH, "demo.triple", "a", 1, "b", 2, "b", 3));
    }

    @Test
    public void testTextsAndItemsOfAllLocales() {
        Assertions.assertTrue(DEMO.textsOfAllLocales("no.such").isEmpty());

        Set<String> items = DEMO.itemsOfAllLocales("demo.keywords");
        Assertions.assertTrue(items.containsAll(Arrays.asList("done", "finished", "完成", "结束")), items.toString());
    }

    // ==================== 覆盖工厂 ====================

    @Test
    public void testOverlayVariants() {
        Locale l1 = uniqueLocale();
        AiI18n.setBundleFactory((name, l) -> null);
        Assertions.assertEquals("Hello {name}", DEMO.get(l1, "demo.hello"));

        Locale l2 = uniqueLocale();
        AiI18n.setBundleFactory((name, l) -> bundleOf(l, null));
        Assertions.assertEquals("Hello {name}", DEMO.get(l2, "demo.hello"));

        Locale l3 = uniqueLocale();
        AiI18n.setBundleFactory((name, l) -> {
            throw new IllegalStateException("boom");
        });
        Assertions.assertEquals("Hello {name}", DEMO.get(l3, "demo.hello"));

        Locale l4 = uniqueLocale();
        AiI18n.setBundleFactory((name, l) -> {
            Props props = new Props();
            props.put("demo.hello", "Hi {name}");
            props.put("demo.keywords", "ok |  |yes");
            return bundleOf(l, props);
        });
        Assertions.assertEquals("Hi z", DEMO.fmt(l4, "demo.hello", "name", "z"));
        Set<String> items = DEMO.itemsOfAllLocales("demo.keywords");
        Assertions.assertTrue(items.contains("yes"), items.toString());
        Assertions.assertFalse(items.contains(""), items.toString());
    }

    /**
     * 覆盖工厂回调文案（以英文为底稿）：不能卡死；同一语言重入快速失败并落回内置文案
     */
    @Test
    public void testOverlayFactoryReadsMessages() throws Exception {
        Locale locale = uniqueLocale();
        AiI18n.setBundleFactory((name, l) -> {
            String base = DEMO.get(Locale.ENGLISH, "demo.hello");
            Props props = new Props();
            if (l.equals(locale)) {
                props.put("demo.hello", "Overlay of " + base);
            }
            return bundleOf(l, props);
        });

        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            String val = ex.submit(() -> DEMO.get(locale, "demo.hello")).get(10, TimeUnit.SECONDS);
            Assertions.assertEquals("Overlay of Hello {name}", val);
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    public void testOverlayFactoryReadsSameLocale() {
        Locale locale = uniqueLocale();
        AiI18n.setBundleFactory((name, l) -> {
            //同一实例、同一语言：重入 -> 抛出 -> 落回内置文案
            DEMO.get(l, "demo.hello");
            return null;
        });

        Assertions.assertEquals("Hello {name}", DEMO.get(locale, "demo.hello"));
    }

    @Test
    public void testCacheClearedWhileLoading() {
        Locale locale = uniqueLocale();
        AtomicInteger calls = new AtomicInteger();
        AiI18n.setBundleFactory((name, l) -> {
            Props props = new Props();
            if (l.equals(locale)) {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    AiI18n.clearCache();
                }
                props.put("demo.hello", "v" + n);
            }
            return bundleOf(l, props);
        });

        Assertions.assertEquals("v1", DEMO.get(locale, "demo.hello"));
        Assertions.assertEquals("v2", DEMO.get(locale, "demo.hello"));
        Assertions.assertEquals("v2", DEMO.get(locale, "demo.hello"));
        Assertions.assertEquals(2, calls.get());
    }

    // ==================== helpers ====================

    static I18nBundle bundleOf(Locale l, Props props) {
        return new I18nBundle() {
            @Override
            public Props toProps() {
                return props;
            }

            @Override
            public Locale locale() {
                return l;
            }

            @Override
            public String get(String key) {
                return props == null ? null : props.getProperty(key);
            }
        };
    }

    static Locale uniqueLocale() {
        long n = System.nanoTime();
        StringBuilder sb = new StringBuilder("qq");
        for (int i = 0; i < 5; i++) {
            sb.append((char) ('a' + (int) (n % 26)));
            n /= 26;
        }
        return new Locale(sb.toString());
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
