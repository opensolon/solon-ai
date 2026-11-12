package features.ai.talents.code.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.i18n.testkit.I18nLint;
import org.noear.solon.ai.talents.code.i18n.CodeI18n;
import org.noear.solon.ai.talents.code.i18n.CodeI18nKeys;

/**
 * code 文案资源的格式与一致性检查（规则见 {@link I18nLint}；本才能没有工具）
 */
public class CodeI18nLintTest {
    @Test
    public void testLint() throws Exception {
        new I18nLint(CodeI18n.messages(), CodeI18nKeys.class, "CodeI18n")
                .minCalls(20, 20)
                .verify();
    }
}
