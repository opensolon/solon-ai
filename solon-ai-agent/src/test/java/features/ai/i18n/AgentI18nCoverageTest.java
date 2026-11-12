package features.ai.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.core.Props;
import org.noear.solon.i18n.I18nBundle;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AgentI18n 的分支覆盖（补齐 100% 行 / 分支覆盖）
 *
 * <p>这些用例覆盖的是“异常与边界”而不是主流程：默认语言读写、缓存清理、Prompt 语言标签的
 * 各种取值、占位符替换的非法形式、覆盖工厂返回 null 或抛异常。主流程见 {@link AgentI18nTest}
 * 与各协议 / 运行时用例。</p>
 *
 * <p>用例会改全局默认语言与覆盖工厂，{@link #reset()} 负责还原。</p>
 */
public class AgentI18nCoverageTest {
    @AfterEach
    public void reset() {
        AgentI18n.setDefaultLocale(Locale.CHINESE);
        AgentI18n.setBundleFactory(null);
    }

    // ==================== 默认语言 / 缓存 ====================

    @Test
    public void testDefaultLocale() {
        Assertions.assertEquals(Locale.CHINESE, AgentI18n.getDefaultLocale());

        AgentI18n.setDefaultLocale(Locale.ENGLISH);
        Assertions.assertEquals(Locale.ENGLISH, AgentI18n.getDefaultLocale());
        //默认语言即“未指定 locale”时的语言
        Assertions.assertEquals(AgentI18n.get(Locale.ENGLISH, "react.prompt.role.default"),
                AgentI18n.get(null, "react.prompt.role.default"));
        Assertions.assertFalse(AgentI18n.isChinese(null));

        //null 不允许：否则 resolve(null) 会返回 null，后续 locale.hashCode() 空指针
        Assertions.assertThrows(NullPointerException.class, () -> AgentI18n.setDefaultLocale(null));
        Assertions.assertThrows(NullPointerException.class, () -> AgentI18n.registerLocale(null));
    }

    @Test
    public void testClearCache() {
        //先读一次，填满缓存
        Assertions.assertNotNull(AgentI18n.get(Locale.ENGLISH, "react.prompt.role.default"));

        AgentI18n.clearCache();

        //清空后仍能重新加载
        Assertions.assertNotNull(AgentI18n.get(Locale.ENGLISH, "react.prompt.role.default"));
    }

    // ==================== Prompt 语言标签 ====================

    @Test
    public void testPromptLocale() {
        Prompt prompt = Prompt.of("hi");
        prompt.locale(Locale.ENGLISH);
        //存的是语言标签字符串：Locale 对象无法序列化进会话快照
        Assertions.assertEquals("en", prompt.attrs().get(AgentI18n.ATTR_LOCALE));
        Assertions.assertEquals(Locale.ENGLISH, AiI18n.localeOf(prompt));

        //null 表示移除（需要记下默认语言时，调用方先 resolve）
        prompt.locale(null);
        Assertions.assertNull(AiI18n.localeOf(prompt));
        prompt.locale(AiI18n.resolve(null));
        Assertions.assertEquals(Locale.CHINESE.toLanguageTag(), prompt.attrs().get(AgentI18n.ATTR_LOCALE));
    }

    @Test
    public void testLocaleOfVariants() {
        Assertions.assertNull(AiI18n.localeOf(null), "空提示词");
        Assertions.assertNull(AiI18n.localeOf(Prompt.of("hi")), "未携带语言");

        Assertions.assertEquals(Locale.ENGLISH, AiI18n.localeOf(Prompt.of("hi").locale(Locale.ENGLISH)));
        Assertions.assertEquals(Locale.SIMPLIFIED_CHINESE, AiI18n.localeOf(Prompt.of("hi").locale(Locale.SIMPLIFIED_CHINESE)));
        //语言值各种形态的解析规则由 core 的 AiI18nTest#testPromptLocale 覆盖
    }

    // ==================== 占位符替换的非法形式 ====================

    @Test
    public void testFormatEdgeCases() {
        Map<String, Object> a = Collections.singletonMap("a", "A");

        //无需替换的四种情况：原样返回
        Assertions.assertNull(AgentI18n.format(null, a));
        Assertions.assertEquals("x {a}", AgentI18n.format("x {a}", null));
        Assertions.assertEquals("x {a}", AgentI18n.format("x {a}", Collections.emptyMap()));
        //没有花括号：不进入扫描
        Assertions.assertEquals("abc", AgentI18n.format("abc", a));

        //空占位符 {}：不是已提供的名字，原样保留
        Assertions.assertEquals("{} A", AgentI18n.format("{} {a}", a));
        //未闭合的 {：原样保留
        Assertions.assertEquals("x { y", AgentI18n.format("x { y", a));
        //花括号内容超过 64 个字符：不当作占位符（即使变量里恰好有这个名字）
        String longName = repeat('n', 65);
        Assertions.assertEquals("{" + longName + "}", AgentI18n.format("{" + longName + "}", Collections.singletonMap(longName, "X")));
        //恰好 64 个字符：仍是占位符
        String maxName = repeat('m', 64);
        Assertions.assertEquals("X", AgentI18n.format("{" + maxName + "}", Collections.singletonMap(maxName, "X")));
        //未提供的名字：原样保留
        Assertions.assertEquals("{b}", AgentI18n.format("{b}", a));
        //连续占位符与后置文本
        Map<String, Object> ab = new HashMap<>();
        ab.put("a", "A");
        ab.put("b", "B");
        Assertions.assertEquals("A-B!", AgentI18n.format("{a}-{b}!", ab));
        //变量内容不被二次替换
        Assertions.assertEquals("{b}", AgentI18n.format("{a}", Collections.singletonMap("a", "{b}")));
        //变量值为 null：按 "null" 输出（与 StringBuilder.append 一致）
        Assertions.assertEquals("null", AgentI18n.format("{a}", Collections.singletonMap("a", null)));
    }

    /**
     * 名字必须是 [A-Za-z_][A-Za-z0-9_]*：即使 vars 里恰好有这个名字，非法形式也原样保留
     */
    @Test
    public void testFormatNameRules() {
        //非法名字：空名、含空格、含引号、含点、数字开头、旧的序号写法
        for (String name : new String[]{"", "a b", " a ", "\"q\"", "a.b", "a-b", "a:b", "1a", "0", "角色", "a角"}) {
            String tml = "x {" + name + "} y";
            Assertions.assertEquals(tml, AgentI18n.format(tml, Collections.singletonMap(name, "X")), name);
        }

        //合法名字：下划线开头、大写、后续带数字
        Map<String, Object> vars = new HashMap<>();
        vars.put("_x1", "A");
        vars.put("Role", "B");
        Assertions.assertEquals("A B", AgentI18n.format("{_x1} {Role}", vars));

        //未闭合且到达模板末尾
        Assertions.assertEquals("x {a", AgentI18n.format("x {a", Collections.singletonMap("a", "A")));
        //嵌套花括号：只替换内层的合法占位符
        Assertions.assertEquals("{A}", AgentI18n.format("{{a}}", Collections.singletonMap("a", "A")));
        //JSON 中的名字不会被误替换
        Assertions.assertEquals("{\"a\": 1} A", AgentI18n.format("{\"a\": 1} {a}", Collections.singletonMap("a", "A")));
    }

    /**
     * 扫描耗时与模板长度成线性（旧实现对大量未闭合的 '{' 是平方级，20 万个约 3.6 秒）
     */
    @Test
    public void testFormatIsLinear() {
        String tml = repeat('{', 200_000) + "{a}";
        long start = System.nanoTime();
        String out = AgentI18n.format(tml, Collections.singletonMap("a", "A"));
        long ms = (System.nanoTime() - start) / 1_000_000;

        Assertions.assertEquals(repeat('{', 200_000) + "A", out);
        Assertions.assertTrue(ms < 500, "format took " + ms + "ms");
    }

    /**
     * 变量名重复：快速失败（否则后者静默覆盖前者，提示词里残留 {desc}）
     */
    @Test
    public void testFmtDuplicateNames() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> AgentI18n.fmt(Locale.ENGLISH,
                "react.plan.result.progress.next", "step", 1, "step", 2));
        Assertions.assertThrows(IllegalArgumentException.class, () -> AgentI18n.fmt(Locale.ENGLISH,
                "team.base.supervisor.protocol", "protocol", "P", "candidates", "c", "protocol", "F"));
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    // ==================== 覆盖值原样使用 ====================

    @Test
    public void testOverlayValuesAreVerbatim() {
        Locale locale = I18nTestSupport.uniqueLocale();
        AgentI18n.setBundleFactory((name, l) -> new I18nBundle() {
            @Override
            public Props toProps() {
                Props props = new Props();
                props.put("react.prompt.role.default", "Role \u23CE");
                return props;
            }

            @Override
            public Locale locale() {
                return l;
            }

            @Override
            public String get(String key) {
                return toProps().getProperty(key);
            }
        });

        //不再做任何字符转换：空白由代码负责，资源值原样使用
        Assertions.assertEquals("Role \u23CE", AgentI18n.get(locale, "react.prompt.role.default"));
    }

    // ==================== 覆盖工厂的异常路径 ====================

    @Test
    public void testOverlayFactoryReturnsNull() {
        Locale locale = I18nTestSupport.uniqueLocale();
        AgentI18n.setBundleFactory((name, l) -> null);

        //工厂返回 null：内置文案照常可用
        Assertions.assertNotNull(AgentI18n.get(locale, "react.prompt.role.default"));
    }

    @Test
    public void testOverlayFactoryBundleWithoutProps() {
        Locale locale = I18nTestSupport.uniqueLocale();
        AgentI18n.setBundleFactory((name, l) -> new I18nBundle() {
            @Override
            public Props toProps() {
                return null;
            }

            @Override
            public Locale locale() {
                return l;
            }

            @Override
            public String get(String key) {
                return null;
            }
        });

        Assertions.assertNotNull(AgentI18n.get(locale, "react.prompt.role.default"));
    }

    @Test
    public void testOverlayFactoryThrows() {
        Locale locale = I18nTestSupport.uniqueLocale();
        AgentI18n.setBundleFactory((name, l) -> {
            throw new IllegalStateException("boom");
        });

        //工厂抛异常：落回内置文案，不影响提示词生成
        Assertions.assertNotNull(AgentI18n.get(locale, "react.prompt.role.default"));
    }

    @Test
    public void testOverlayFactoryOverridesAndSuppliesItems() {
        Locale locale = I18nTestSupport.uniqueLocale();
        AgentI18n.setBundleFactory((name, l) -> new I18nBundle() {
            @Override
            public Props toProps() {
                Props props = new Props();
                props.put("react.prompt.role.default", "Overlay role");
                //分隔符两侧含空白项：itemsOfAllLocales 需跳过空项
                props.put("protocol.contractnet.bidding_keywords", "bid |  |tender");
                return props;
            }

            @Override
            public Locale locale() {
                return l;
            }

            @Override
            public String get(String key) {
                return toProps().getProperty(key);
            }
        });

        Assertions.assertEquals("Overlay role", AgentI18n.get(locale, "react.prompt.role.default"));

        Set<String> items = AgentI18n.itemsOfAllLocales("protocol.contractnet.bidding_keywords");
        Assertions.assertTrue(items.contains("tender"), items.toString());
        Assertions.assertFalse(items.contains(""), "空项必须被跳过：" + items);
    }

    // ==================== 加载与缓存 ====================

    /**
     * 覆盖工厂回调 AgentI18n（如以英文为底稿）：不能卡死。
     *
     * <p>旧实现在 computeIfAbsent 内加载，Java 8 下会死循环；现在加载在锁外进行，
     * 同一语言的重入会快速失败并落回内置文案。</p>
     */
    @Test
    public void testOverlayFactoryReadsAgentI18n() throws Exception {
        Locale locale = I18nTestSupport.uniqueLocale();
        AgentI18n.setBundleFactory((name, l) -> {
            String base = AgentI18n.get(Locale.ENGLISH, "react.prompt.role.default");
            Props props = new Props();
            if (l.equals(locale)) {
                props.put("react.prompt.role.default", "Overlay of " + base);
            }
            return bundleOf(l, props);
        });

        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            String val = ex.submit(() -> AgentI18n.get(locale, "react.prompt.role.default"))
                    .get(10, TimeUnit.SECONDS);
            Assertions.assertEquals("Overlay of " + AgentI18n.get(Locale.ENGLISH, "react.prompt.role.default"), val);
        } finally {
            ex.shutdownNow();
        }
    }

    /**
     * 加载期间缓存被清空：本次结果照常返回，但不写入缓存（下次重新加载）
     */
    @Test
    public void testCacheClearedWhileLoading() {
        Locale locale = I18nTestSupport.uniqueLocale();
        AtomicInteger calls = new AtomicInteger();
        AgentI18n.setBundleFactory((name, l) -> {
            Props props = new Props();
            if (l.equals(locale) && "solon-ai-agent.i18n.react".equals(name)) {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    //模拟加载过程中另一个线程切换了工厂或热更新
                    AgentI18n.clearCache();
                }
                props.put("react.prompt.role.default", "v" + n);
            }
            return bundleOf(l, props);
        });

        Assertions.assertEquals("v1", AgentI18n.get(locale, "react.prompt.role.default"));
        //第一次的结果未入缓存：重新加载
        Assertions.assertEquals("v2", AgentI18n.get(locale, "react.prompt.role.default"));
        //第二次正常入缓存
        Assertions.assertEquals("v2", AgentI18n.get(locale, "react.prompt.role.default"));
        Assertions.assertEquals(2, calls.get());
    }

    private static I18nBundle bundleOf(Locale l, Props props) {
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
                return props.getProperty(key);
            }
        };
    }

    // ==================== 标记识别 ====================

    @Test
    public void testTextsOfAllLocalesSkipsMissingKey() {
        //不存在的 key：所有语言都取不到，返回空集合（不抛异常）
        Assertions.assertTrue(AgentI18n.textsOfAllLocales("no.such.key").isEmpty());

        //内置 zh / en 都有值时，两种语言的文案都在集合里
        Set<String> texts = AgentI18n.textsOfAllLocales("compress.vector.archived_tag");
        Assertions.assertEquals(2, texts.size(), texts.toString());
    }

    @Test
    public void testKnownLocalesContainsDefaultAndBuiltIn() {
        Set<Locale> known = AgentI18n.knownLocales();
        Assertions.assertTrue(known.contains(Locale.CHINESE));
        Assertions.assertTrue(known.contains(Locale.ENGLISH));
    }
}
