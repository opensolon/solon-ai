package org.noear.solon.ai.talents.lsp.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.i18n.testkit.I18nKeyCoverage;

import java.util.Collections;
import java.util.List;

/**
 * lsp 文案 key 的测试覆盖率（中英 100%；新增行为用例类要登记到 SUITES）
 *
 * <p>读取覆盖：每个 key 都被产品代码读取过；断言覆盖：每个 key 的文案被改坏时，至少一个用例失败。</p>
 */
public class LspI18nCoverageTest {
    //测试类与产品类同包：用类名精确限定产品代码，避免把测试自己的读取算作覆盖
    static final List<String> PRODUCTION = Collections.singletonList("org.noear.solon.ai.talents.lsp.LspTalent");
    static final List<Class<?>> FACADES = Collections.singletonList(LspI18n.class);
    static final List<Class<?>> SUITES = Collections.<Class<?>>singletonList(LspRenderGoldenTest.class);

    @Test
    public void testEveryKeyIsExercised() throws Throwable {
        I18nKeyCoverage.verify(LspI18n.messages(), PRODUCTION, FACADES, SUITES);
    }

    @Test
    public void testEveryKeyIsAsserted() throws Throwable {
        I18nKeyCoverage.verifyAsserted(LspI18n.messages(), PRODUCTION, FACADES, SUITES);
    }
}
