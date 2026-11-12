package org.noear.solon.ai.harness.extension.loop;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.extension.loop.i18n.LoopI18n;
import org.noear.solon.ai.harness.extension.loop.i18n.LoopI18nKeys;
import org.noear.solon.ai.i18n.testkit.I18nLint;

/**
 * loop 文案资源的格式与一致性检查（规则见 {@link I18nLint}）
 */
public class LoopI18nLintTest {
    @Test
    public void testLint() throws Exception {
        new I18nLint(LoopI18n.messages(), LoopI18nKeys.class, "LoopI18n")
                .minCalls(5, 20)
                .verify();
    }
}
