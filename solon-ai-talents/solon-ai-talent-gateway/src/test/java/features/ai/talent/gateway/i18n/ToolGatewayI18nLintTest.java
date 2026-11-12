package features.ai.talent.gateway.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.i18n.testkit.I18nLint;
import org.noear.solon.ai.talents.gateway.i18n.ToolGatewayI18n;
import org.noear.solon.ai.talents.gateway.i18n.ToolGatewayI18nKeys;

/**
 * ToolGateway 文案资源的格式与一致性检查（规则见 {@link I18nLint}）
 */
public class ToolGatewayI18nLintTest {
    @Test
    public void testLint() throws Exception {
        new I18nLint(ToolGatewayI18n.messages(), ToolGatewayI18nKeys.class, "ToolGatewayI18n")
                .minCalls(5, 5)
                .verify();
    }
}
