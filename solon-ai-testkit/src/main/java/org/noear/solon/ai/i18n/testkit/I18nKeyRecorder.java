package org.noear.solon.ai.i18n.testkit;

import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.I18nMessages;

import java.lang.reflect.Field;
import java.security.CodeSource;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录产品代码实际读取过的文案 key（用于 key 覆盖率统计）
 *
 * <p>把 {@link I18nMessages} 缓存中被关注语言的文案替换为“读取即记录”的视图；只有第一个非 i18n、非 JDK
 * 的调用帧位于产品包（{@code productionPrefixes}）时才计入——测试代码自己读取的 key 不算覆盖。</p>
 *
 * <p>用例中途清缓存会丢失记录视图，只会让统计偏少，不会误报覆盖。</p>
 */
public final class I18nKeyRecorder {
    /**
     * 统计时需要记录的 locale（用例中会用到地区变体，按语言归并统计）
     */
    public static final List<Locale> WATCHED = Collections.unmodifiableList(Arrays.asList(
            Locale.CHINESE, Locale.CHINA, Locale.SIMPLIFIED_CHINESE,
            Locale.ENGLISH, Locale.US, Locale.UK));

    private final I18nMessages messages;
    private final List<String> productionPrefixes;
    private final List<String> skipClasses;
    /**
     * 语言 -> 已读取的 key
     */
    private final Map<String, Set<String>> hits = new ConcurrentHashMap<>();

    /**
     * @param messages           被统计的文案实例
     * @param productionPrefixes 产品代码的包前缀（如 "org.noear.solon.ai.agent."）
     * @param facades            转发调用的门面类（如 AgentI18n；调用帧会被跳过）
     */
    public I18nKeyRecorder(I18nMessages messages, List<String> productionPrefixes, Class<?>... facades) {
        this.messages = messages;
        this.productionPrefixes = productionPrefixes;
        this.skipClasses = new ArrayList<>();
        for (Class<?> c : facades) {
            skipClasses.add(c.getName());
        }
        skipClasses.add(I18nMessages.class.getName());
        skipClasses.add(AiI18n.class.getName());
        skipClasses.add(I18nKeyRecorder.class.getName());
        //记录器自身（含内部类 RecordingMap）
    }

    /**
     * 已读取的 key（按语言，如 "zh"、"en"）
     */
    public Set<String> hitsOf(String language) {
        return hits.computeIfAbsent(language, k -> ConcurrentHashMap.newKeySet());
    }

    /**
     * 安装记录视图（每个用例开始前调用；已安装的语言不会重复包装）
     */
    @SuppressWarnings("unchecked")
    public void install() throws Exception {
        Field f = I18nMessages.class.getDeclaredField("cache");
        f.setAccessible(true);
        Map<Locale, Map<String, String>> cache = (Map<Locale, Map<String, String>>) f.get(messages);

        for (Locale l : WATCHED) {
            Map<String, String> current = messages.messages(l);
            if (current instanceof RecordingMap == false) {
                cache.put(l, new RecordingMap(current, hitsOf(l.getLanguage()), this));
            }
        }
    }

    /**
     * 第一个非跳过、非 JDK 的调用帧是否为产品代码
     */
    boolean calledByProduction() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String cn = e.getClassName();
            if (skip(cn) || cn.startsWith("java.") || cn.startsWith("jdk.") || cn.startsWith("sun.")) {
                continue;
            }

            if (isTestCode(cn)) {
                //测试类与产品类同包时，包前缀区分不了，按类的来源（test-classes）判断
                return false;
            }

            for (String prefix : productionPrefixes) {
                if (cn.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static final Map<String, Boolean> TEST_CODE = new ConcurrentHashMap<>();

    /**
     * 是否为测试代码（类文件位于构建目录的 test-classes 下）
     */
    static boolean isTestCode(String className) {
        Boolean val = TEST_CODE.get(className);
        if (val == null) {
            val = locate(className).contains("/test-classes/");
            TEST_CODE.put(className, val);
        }
        return val;
    }

    private static String locate(String className) {
        try {
            Class<?> clz = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            CodeSource cs = clz.getProtectionDomain().getCodeSource();
            return (cs == null || cs.getLocation() == null) ? "" : cs.getLocation().toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private boolean skip(String cn) {
        for (String s : skipClasses) {
            if (cn.equals(s) || cn.startsWith(s + "$")) {
                return true;
            }
        }
        return false;
    }

    static final class RecordingMap extends AbstractMap<String, String> {
        private final Map<String, String> target;
        private final Set<String> hits;
        private final I18nKeyRecorder recorder;

        RecordingMap(Map<String, String> target, Set<String> hits, I18nKeyRecorder recorder) {
            this.target = target;
            this.hits = hits;
            this.recorder = recorder;
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
            if (key instanceof String && recorder.calledByProduction()) {
                hits.add((String) key);
            }
        }
    }
}
