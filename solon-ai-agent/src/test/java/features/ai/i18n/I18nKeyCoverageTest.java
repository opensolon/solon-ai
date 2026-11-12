package features.ai.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.i18n.testkit.I18nKeyCoverage;
import org.noear.solon.ai.i18n.testkit.I18nKeyRecorder;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

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
            I18nKeyScenarioTest.class,
            AgentI18nTextGoldenTest.class);

    /**
     * 要求覆盖的语言（内置资源的语言）
     */
    static final List<Locale> LOCALES = Arrays.asList(Locale.CHINESE, Locale.ENGLISH);

    @Test
    public void testEveryKeyIsExercised() throws Throwable {
        I18nKeyRecorder recorder = new I18nKeyRecorder(AgentI18n.messages(),
                Collections.singletonList("org.noear.solon.ai.agent."), AgentI18n.class);

        for (Class<?> suite : SUITES) {
            runSuite(suite, recorder);
        }

        AgentI18n.clearCache();

        List<String> errors = new ArrayList<>();
        for (Locale l : LOCALES) {
            Set<String> missing = new TreeSet<>(AgentI18n.messages(l).keySet());
            missing.removeAll(recorder.hitsOf(l.getLanguage()));
            if (missing.isEmpty() == false) {
                errors.add(l + " (" + missing.size() + "): " + String.join(", ", missing));
            }
        }

        Assertions.assertTrue(errors.isEmpty(), "i18n keys not exercised by any test:\n" + String.join("\n", errors));
    }

    /**
     * 断言覆盖：每个 key 的文案被改坏时（中英各变异一次），至少一个用例失败
     */
    @Test
    public void testEveryKeyIsAsserted() throws Throwable {
        I18nKeyCoverage.verifyAsserted(AgentI18n.messages(),
                Collections.singletonList("org.noear.solon.ai.agent."),
                Collections.singletonList(AgentI18n.class), SUITES);
    }

    // ---------------- 迷你运行器（被统计的用例只用到 @Test / @BeforeEach / @AfterEach）----------------

    static void runSuite(Class<?> suite, I18nKeyRecorder recorder) throws Throwable {
        for (Method test : suite.getDeclaredMethods()) {
            if (test.isAnnotationPresent(Test.class) == false) {
                continue;
            }

            Object instance = suite.getDeclaredConstructor().newInstance();
            try {
                invokeAll(instance, BeforeEach.class);
                recorder.install();
                invoke(instance, test);
            } finally {
                invokeAll(instance, AfterEach.class);
            }
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
