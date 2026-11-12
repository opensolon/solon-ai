package features.ai.harness.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.ai.harness.i18n.HarnessI18nKeys;
import org.noear.solon.ai.i18n.testkit.I18nLint;

/**
 * harness-core 文案资源的格式与一致性检查（规则见 {@link I18nLint}）
 */
public class HarnessI18nLintTest {
    @Test
    public void testLint() throws Exception {
        new I18nLint(HarnessI18n.messages(), HarnessI18nKeys.class, "HarnessI18n")
                .minCalls(10, 30)
                .verify();
    }
}
