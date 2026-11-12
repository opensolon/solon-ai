package features.ai.talent.gateway.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.i18n.testkit.I18nLint;
import org.noear.solon.ai.talents.gateway.i18n.McpGatewayI18n;
import org.noear.solon.ai.talents.gateway.i18n.McpGatewayI18nKeys;

/**
 * McpGateway 文案资源的格式与一致性检查（规则见 {@link I18nLint}）
 */
public class McpGatewayI18nLintTest {
    @Test
    public void testLint() throws Exception {
        new I18nLint(McpGatewayI18n.messages(), McpGatewayI18nKeys.class, "McpGatewayI18n")
                .minCalls(5, 5)
                .verify();
    }
}
