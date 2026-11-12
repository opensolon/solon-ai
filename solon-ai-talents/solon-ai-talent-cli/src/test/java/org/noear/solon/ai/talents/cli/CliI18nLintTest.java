package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.i18n.testkit.I18nLint;
import org.noear.solon.ai.talents.cli.i18n.CliI18n;
import org.noear.solon.ai.talents.cli.i18n.CliI18nKeys;

/**
 * cli 文案资源的格式与一致性检查（规则见 {@link I18nLint}）
 */
public class CliI18nLintTest {
    @Test
    public void testLint() throws Exception {
        new I18nLint(CliI18n.messages(), CliI18nKeys.class, "CliI18n")
                .minCalls(40, 80)
                .verify();
    }
}
