package org.noear.solon.ai.harness.extension.loop;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.extension.loop.i18n.LoopI18n;
import org.noear.solon.ai.i18n.testkit.I18nKeyCoverage;

import java.util.Collections;
import java.util.List;

/**
 * loop 文案 key 的测试覆盖率（中英 100%；新增行为用例类要登记到 SUITES）
 *
 * <p>读取覆盖：每个 key 都被产品代码读取过；断言覆盖：每个 key 的文案被改坏时，至少一个用例失败。</p>
 */
public class LoopI18nCoverageTest {
    static final List<String> PRODUCTION = Collections.singletonList("org.noear.solon.ai.harness.extension.loop.");
    static final List<Class<?>> FACADES = Collections.singletonList(LoopI18n.class);
    static final List<Class<?>> SUITES = Collections.<Class<?>>singletonList(LoopRenderGoldenTest.class);

    @Test
    public void testEveryKeyIsExercised() throws Throwable {
        I18nKeyCoverage.verify(LoopI18n.messages(), PRODUCTION, FACADES, SUITES);
    }

    @Test
    public void testEveryKeyIsAsserted() throws Throwable {
        I18nKeyCoverage.verifyAsserted(LoopI18n.messages(), PRODUCTION, FACADES, SUITES);
    }
}
