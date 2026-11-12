package org.noear.solon.ai.talents.lsp.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.i18n.testkit.I18nLint;

/**
 * lsp 文案资源的格式与一致性检查（规则见 {@link I18nLint}）
 */
public class LspI18nLintTest {
    @Test
    public void testLint() throws Exception {
        new I18nLint(LspI18n.messages(), LspI18nKeys.class, "LspI18n")
                .minCalls(1, 0)
                .verify();
    }
}
