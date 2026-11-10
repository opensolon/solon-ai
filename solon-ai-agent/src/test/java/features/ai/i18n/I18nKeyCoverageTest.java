package features.ai.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.i18n.AgentI18n;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文案 key 的测试覆盖率（要求 100%）
 *
 * <p>以 {@link #SUITES} 中的行为用例为准：逐个运行这些用例，并记录<b>产品代码</b>（{@code org.noear.solon.ai.agent.*}）
 * 实际读取过的 key（测试代码自己读取的 key 不计入）。要求中英两种语言下，资源中的每个 key 都至少被读取过一次——
 * 也就是说每个 key 都有对应的用例走到它的调用点（并由该用例的断言或快照校验输出）。</p>
 *
 * <p>新增 key 而没有用例覆盖时本测试失败，并列出缺失的 key。</p>
 */
public class I18nKeyCoverageTest {
    /**
     * 参与统计的行为用例（不含只测 AgentI18n 自身或只读资源的用例，如 lint、消息快照）
     */
    static final List<Class<?>> SUITES = Arrays.asList(
            PromptGoldenTest.class,
            RuntimeRenderGoldenTest.class,
            ReActRuntimeI18nTest.class,
            TeamBaseI18nTest.class,
            TeamProtocolI18nTest.class,
            TeamLocaleInheritTest.class,
            CompressionI18nTest.class,
            I18nKeyScenarioTest.class);

    /**
     * 要求覆盖的语言（内置资源的语言）
     */
    static final List<Locale> LOCALES = Arrays.asList(Locale.CHINESE, Locale.ENGLISH);

    /**
     * 需要记录的 locale（用例中也会用到地区变体，按语言归并统计）
     */
    static final List<Locale> WATCHED = Arrays.asList(Locale.CHINESE, Locale.CHINA, Locale.SIMPLIFIED_CHINESE,
            Locale.ENGLISH, Locale.US, Locale.UK);

    @Test
    public void testEveryKeyIsExercised() throws Throwable {
        Map<String, Set<String>> hits = new ConcurrentHashMap<>();
        for (Locale l : LOCALES) {
            hits.put(l.getLanguage(), ConcurrentHashMap.newKeySet());
        }

        for (Class<?> suite : SUITES) {
            runSuite(suite, hits);
        }

        AgentI18n.clearCache();

        List<String> errors = new ArrayList<>();
        for (Locale l : LOCALES) {
            Set<String> missing = new TreeSet<>(AgentI18n.messages(l).keySet());
            missing.removeAll(hits.get(l.getLanguage()));
            if (missing.isEmpty() == false) {
                errors.add(l + " (" + missing.size() + "): " + String.join(", ", missing));
            }
        }

        Assertions.assertTrue(errors.isEmpty(), "i18n keys not exercised by any test:\n" + String.join("\n", errors));
    }

    // ---------------- 迷你运行器（被统计的用例只用到 @Test / @BeforeEach / @AfterEach）----------------

    static void runSuite(Class<?> suite, Map<String, Set<String>> hits) throws Throwable {
        for (Method test : suite.getDeclaredMethods()) {
            if (test.isAnnotationPresent(Test.class) == false) {
                continue;
            }

            Object instance = suite.getDeclaredConstructor().newInstance();
            try {
                invokeAll(instance, BeforeEach.class);
                install(hits);
                invoke(instance, test);
            } finally {
                invokeAll(instance, AfterEach.class);
            }
        }
    }

    static void invokeAll(Object instance, Class<? extends java.lang.annotation.Annotation> type) throws Throwable {
        for (Method m : instance.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(type)) {
                invoke(instance, m);
            }
        }
    }

    static void invoke(Object instance, Method m) throws Throwable {
        m.setAccessible(true);
        try {
            m.invoke(instance);
        } catch (InvocationTargetException e) {
            throw new AssertionError(instance.getClass().getSimpleName() + "#" + m.getName() + " failed", e.getCause());
        }
    }

    /**
     * 把缓存中的中英文案替换为记录读取的视图（用例中途清缓存会丢失记录，只会让统计偏少，不会误报覆盖）
     */
    @SuppressWarnings("unchecked")
    static void install(Map<String, Set<String>> hits) throws Exception {
        Field f = AgentI18n.class.getDeclaredField("CACHE");
        f.setAccessible(true);
        Map<Locale, Map<String, String>> cache = (Map<Locale, Map<String, String>>) f.get(null);

        for (Locale l : WATCHED) {
            Map<String, String> current = AgentI18n.messages(l);
            if (current instanceof RecordingMap == false) {
                cache.put(l, new RecordingMap(current, hits.get(l.getLanguage())));
            }
        }
    }

    static final class RecordingMap extends AbstractMap<String, String> {
        private final Map<String, String> target;
        private final Set<String> hits;

        RecordingMap(Map<String, String> target, Set<String> hits) {
            this.target = target;
            this.hits = hits;
        }

        @Override
        public String get(Object key) {
            record(key);
            return target.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            record(key);
            return target.containsKey(key);
        }

        @Override
        public Set<Entry<String, String>> entrySet() {
            return target.entrySet();
        }

        private void record(Object key) {
            if (key instanceof String && calledByProduction()) {
                hits.add((String) key);
            }
        }

        /**
         * 第一个非 AgentI18n / 非 JDK / 非本类的调用帧是否为产品代码
         */
        private static boolean calledByProduction() {
            for (StackTraceElement e : new Throwable().getStackTrace()) {
                String cn = e.getClassName();
                if (cn.startsWith(RecordingMap.class.getName()) || cn.equals(AgentI18n.class.getName())
                        || cn.startsWith("java.") || cn.startsWith("jdk.") || cn.startsWith("sun.")) {
                    continue;
                }
                return cn.startsWith("org.noear.solon.ai.agent.");
            }
            return false;
        }
    }
}
