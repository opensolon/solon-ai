package org.noear.solon.ai.harness.extension.steer;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.extension.steer.i18n.SteerI18n;
import org.noear.solon.ai.i18n.testkit.I18nKeyCoverage;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * steer 文案 key 的测试覆盖率（中英 100%；新增行为用例类要登记到 SUITES）
 *
 * <p>读取覆盖：每个 key 都被产品代码读取过；断言覆盖：每个 key 的文案被改坏时，至少一个用例失败。</p>
 */
public class SteerI18nCoverageTest {
    static final List<String> PRODUCTION = Collections.singletonList("org.noear.solon.ai.harness.extension.steer.");
    static final List<Class<?>> FACADES = Collections.singletonList(SteerI18n.class);
    static final List<Class<?>> SUITES = Arrays.<Class<?>>asList(SteerRenderGoldenTest.class, SteerI18nScenarioTest.class);

    @Test
    public void testEveryKeyIsExercised() throws Throwable {
        I18nKeyCoverage.verify(SteerI18n.messages(), PRODUCTION, FACADES, SUITES);
    }

    @Test
    public void testEveryKeyIsAsserted() throws Throwable {
        I18nKeyCoverage.verifyAsserted(SteerI18n.messages(), PRODUCTION, FACADES, SUITES);
    }
}
