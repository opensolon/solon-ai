package org.noear.solon.ai.i18n.testkit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.I18nMessages;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 文案 key 的测试覆盖率（要求中英 100%）
 *
 * <p>逐个运行行为用例，用 {@link I18nKeyRecorder} 记录<b>产品代码</b>实际读取过的 key（测试代码自己读取的不算）。</p>
 *
 * <p>被统计的用例只能用到 {@code @Test} / {@code @BeforeEach} / {@code @AfterEach} 与 {@code @TempDir Path} 字段。</p>
 */
public final class I18nKeyCoverage {
    static final List<Locale> LOCALES = Arrays.asList(Locale.CHINESE, Locale.ENGLISH);

    private I18nKeyCoverage() {
    }

    /**
     * @param messages           被统计的文案实例
     * @param productionPrefixes 产品代码的包前缀
     * @param facades            门面类（调用帧跳过）
     * @param suites             行为用例
     */
    public static void verify(I18nMessages messages, List<String> productionPrefixes, List<Class<?>> facades,
                              List<Class<?>> suites) throws Throwable {
        List<String> prefixes = new ArrayList<>(productionPrefixes);

        I18nKeyRecorder recorder = new I18nKeyRecorder(messages, prefixes, facades.toArray(new Class<?>[0]));

        for (Class<?> suite : suites) {
            runSuite(suite, recorder);
        }

        AiI18n.clearCache();

        List<String> errors = new ArrayList<>();
        for (Locale l : LOCALES) {
            Set<String> missing = new TreeSet<>(messages.messages(l).keySet());
            missing.removeAll(recorder.hitsOf(l.getLanguage()));
            if (missing.isEmpty() == false) {
                errors.add(l + " (" + missing.size() + "): " + String.join(", ", missing));
            }
        }

        Assertions.assertTrue(errors.isEmpty(), "i18n keys not exercised by any test:\n" + String.join("\n", errors));
    }

    /**
     * 断言覆盖：每个 key 的文案都被某个用例的断言（或快照）校验过
     *
     * <p>{@link #verify} 只保证产品代码读取过每个 key，读到之后输出有没有被检查并不知道。这里逐个 key、逐个语言做变异：
     * 把这个 key 在该语言下的值换成 {@link #MUTANT}，重新运行读取过它的用例，要求至少有一个用例因此失败。
     * 没有任何用例失败，说明这段文案改坏了（丢换行、改错字、整段丢失）也不会被发现。</p>
     *
     * <p>用例里用同一个 key 现取期望值（{@code assertEquals(Xxx.get(locale, key), out)}）的断言会随变异一起变化，
     * 校验不到文案本身，所以不计入。文案在产品代码里被静态缓存时变异不生效，也会被报告出来。</p>
     *
     * @param messages           被统计的文案实例
     * @param productionPrefixes 产品代码的包前缀
     * @param facades            门面类（调用帧跳过）
     * @param suites             行为用例（必须全部通过）
     */
    public static void verifyAsserted(I18nMessages messages, List<String> productionPrefixes, List<Class<?>> facades,
                                      List<Class<?>> suites) throws Throwable {
        //更新快照时所有比对都直接通过，变异无从发现
        Assumptions.assumeFalse(GoldenFiles.UPDATE, "golden.update=true");

        List<String> prefixes = new ArrayList<>(productionPrefixes);
        Class<?>[] skips = facades.toArray(new Class<?>[0]);

        List<TestCase> cases = new ArrayList<>();
        for (Class<?> suite : suites) {
            for (Method m : suite.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Test.class)) {
                    cases.add(new TestCase(suite, m));
                }
            }
        }
        cases.sort(Comparator.comparing(TestCase::toString));

        try {
            //1.逐个用例记录读取过的 key（按语言）
            Map<TestCase, I18nKeyRecorder> reads = new LinkedHashMap<>();
            for (TestCase c : cases) {
                I18nKeyRecorder recorder = new I18nKeyRecorder(messages, prefixes, skips);
                AiI18n.clearCache();
                c.run(recorder::install);
                reads.put(c, recorder);
            }

            //2.逐个语言、逐个 key 变异，重跑读取过它的用例
            List<String> unread = new ArrayList<>();
            List<String> unasserted = new ArrayList<>();
            for (Locale l : LOCALES) {
                String lang = l.getLanguage();
                for (String key : new TreeSet<>(messages.messages(l).keySet())) {
                    List<TestCase> readers = new ArrayList<>();
                    for (Map.Entry<TestCase, I18nKeyRecorder> kv : reads.entrySet()) {
                        if (kv.getValue().hitsOf(lang).contains(key)) {
                            readers.add(kv.getKey());
                        }
                    }

                    if (readers.isEmpty()) {
                        unread.add(lang + ": " + key);
                    } else if (caught(messages, lang, key, readers) == false) {
                        unasserted.add(lang + ": " + key + "  <- " + readers);
                    }
                }
            }

            List<String> errors = new ArrayList<>();
            if (unread.isEmpty() == false) {
                errors.add("not read by product code (" + unread.size() + "):\n  " + String.join("\n  ", unread));
            }
            if (unasserted.isEmpty() == false) {
                errors.add("mutation not detected by any test (" + unasserted.size() + "):\n  "
                        + String.join("\n  ", unasserted));
            }
            Assertions.assertTrue(errors.isEmpty(), "i18n keys not asserted:\n" + String.join("\n", errors));
        } finally {
            AiI18n.clearCache();
        }
    }

    /**
     * 变异后的文案（与任何真实文案都不同，且不含占位符与列表分隔符）
     */
    public static final String MUTANT = "\u27E6i18n-mutant\u27E7";

    /**
     * 变异后是否有用例失败
     */
    static boolean caught(I18nMessages messages, String lang, String key, List<TestCase> readers) throws Exception {
        Field f = I18nMessages.class.getDeclaredField("cache");
        f.setAccessible(true);
        Object original = f.get(messages);

        try {
            for (TestCase c : readers) {
                AiI18n.clearCache();
                try {
                    //@BeforeEach 之后换上变异缓存（它可能设置了覆盖工厂或默认语言）
                    c.run(() -> {
                        f.set(messages, new MutatingCache(lang, key));
                        preload(messages);
                    });
                } catch (Throwable e) {
                    return true;
                } finally {
                    f.set(messages, original);
                }
            }
            return false;
        } finally {
            AiI18n.clearCache();
        }
    }

    /**
     * 可能用到的语言先加载一遍：首次加载时 {@link I18nMessages#messages} 返回的是刚加载的原始文案（不是缓存里变异后的那份），
     * 预加载之后的读取都走缓存
     */
    static void preload(I18nMessages messages) {
        Set<Locale> locales = new LinkedHashSet<>(I18nKeyRecorder.WATCHED);
        locales.addAll(AiI18n.knownLocales());
        locales.add(Locale.JAPANESE);
        locales.add(Locale.FRENCH);
        locales.add(Locale.GERMAN);
        for (Locale l : locales) {
            messages.messages(l);
        }
    }

    /**
     * 写入即变异的缓存：加载到的文案若属于目标语言，就把目标 key 换成 {@link #MUTANT}
     *
     * <p>“属于目标语言”包括地区变体（如 zh_CN），以及没有自己资源、回落到目标语言的其它语言
     * （如日语回落到英文兜底）。否则用例从这些语言取到的仍是原值，变异会被掩盖。用例运行中才加载的语言同样会被变异。</p>
     */
    static final class MutatingCache extends ConcurrentHashMap<Locale, Map<String, String>> {
        private final String lang;
        private final String key;

        MutatingCache(String lang, String key) {
            this.lang = lang;
            this.key = key;
        }

        @Override
        public Map<String, String> put(Locale l, Map<String, String> map) {
            if (map.containsKey(key) && belongs(l)) {
                Map<String, String> copy = new HashMap<>(map);
                copy.put(key, MUTANT);
                map = Collections.unmodifiableMap(copy);
            }
            return super.put(l, map);
        }

        private boolean belongs(Locale l) {
            if (lang.equals(l.getLanguage())) {
                return true;
            }
            for (Locale builtIn : LOCALES) {
                if (builtIn.getLanguage().equals(l.getLanguage())) {
                    //另一种内置语言
                    return false;
                }
            }
            //没有自己资源的语言：回落到默认文件（英文）
            return Locale.ENGLISH.getLanguage().equals(lang);
        }
    }

    interface Hook {
        void run() throws Exception;
    }

    /**
     * 单个用例（@BeforeEach 之后、用例之前执行 hook）
     */
    static final class TestCase {
        final Class<?> suite;
        final Method method;

        TestCase(Class<?> suite, Method method) {
            this.suite = suite;
            this.method = method;
        }

        void run(Hook hook) throws Throwable {
            Object instance = suite.getDeclaredConstructor().newInstance();
            List<Path> temps = injectTempDirs(instance);
            try {
                invokeAll(instance, BeforeEach.class);
                hook.run();
                invoke(instance, method);
            } finally {
                invokeAll(instance, AfterEach.class);
                for (Path p : temps) {
                    deleteQuietly(p);
                }
            }
        }

        @Override
        public String toString() {
            return suite.getSimpleName() + "#" + method.getName();
        }
    }

    // ---------------- 迷你运行器 ----------------

    static void runSuite(Class<?> suite, I18nKeyRecorder recorder) throws Throwable {
        for (Method test : suite.getDeclaredMethods()) {
            if (test.isAnnotationPresent(Test.class) == false) {
                continue;
            }

            Object instance = suite.getDeclaredConstructor().newInstance();
            List<Path> temps = injectTempDirs(instance);
            try {
                invokeAll(instance, BeforeEach.class);
                recorder.install();
                invoke(instance, test);
            } finally {
                invokeAll(instance, AfterEach.class);
                for (Path p : temps) {
                    deleteQuietly(p);
                }
            }
        }
    }

    static List<Path> injectTempDirs(Object instance) throws Exception {
        List<Path> temps = new ArrayList<>();
        for (Field f : instance.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) == false && f.getType() == Path.class
                    && f.isAnnotationPresent(TempDir.class)) {
                Path p = Files.createTempDirectory("i18n-cov");
                f.setAccessible(true);
                f.set(instance, p);
                temps.add(p);
            }
        }
        return temps;
    }

    static void deleteQuietly(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
        }
    }

    static void invokeAll(Object instance, Class<? extends Annotation> type) throws Throwable {
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
}
