package org.noear.solon.ai.harness.extension.steer;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.extension.steer.i18n.SteerI18n;
import org.noear.solon.ai.harness.extension.steer.i18n.SteerI18nKeys;
import org.noear.solon.ai.i18n.testkit.I18nLint;

/**
 * steer 文案资源的格式与一致性检查（规则见 {@link I18nLint}）
 */
public class SteerI18nLintTest {
    @Test
    public void testLint() throws Exception {
        new I18nLint(SteerI18n.messages(), SteerI18nKeys.class, "SteerI18n")
                .minCalls(3, 6)
                .verify();
    }
}
