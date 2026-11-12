package features.ai.talents.web.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.i18n.testkit.I18nLint;
import org.noear.solon.ai.talents.web.i18n.WebI18n;
import org.noear.solon.ai.talents.web.i18n.WebI18nKeys;

/**
 * web 文案资源的格式与一致性检查（规则见 {@link I18nLint}）
 */
public class WebI18nLintTest {
    @Test
    public void testLint() throws Exception {
        new I18nLint(WebI18n.messages(), WebI18nKeys.class, "WebI18n")
                .minCalls(3, 3)
                .verify();
    }
}
