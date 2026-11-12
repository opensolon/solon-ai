package features.ai.talents.web.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.i18n.testkit.I18nKeyCoverage;
import org.noear.solon.ai.talents.web.i18n.WebI18n;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * web 文案 key 的测试覆盖率（中英 100%；新增行为用例类要登记到 SUITES）
 *
 * <p>读取覆盖：每个 key 都被产品代码读取过；断言覆盖：每个 key 的文案被改坏时，至少一个用例失败。</p>
 */
public class WebI18nCoverageTest {
    static final List<String> PRODUCTION = Arrays.asList("org.noear.solon.ai.talents.web.",
            "org.noear.solon.ai.talents.search.",
            "org.noear.solon.ai.talents.crawler.");
    static final List<Class<?>> FACADES = Collections.singletonList(WebI18n.class);
    static final List<Class<?>> SUITES = Collections.<Class<?>>singletonList(WebRenderGoldenTest.class);

    @Test
    public void testEveryKeyIsExercised() throws Throwable {
        I18nKeyCoverage.verify(WebI18n.messages(), PRODUCTION, FACADES, SUITES);
    }

    @Test
    public void testEveryKeyIsAsserted() throws Throwable {
        I18nKeyCoverage.verifyAsserted(WebI18n.messages(), PRODUCTION, FACADES, SUITES);
    }
}
