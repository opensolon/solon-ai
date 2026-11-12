package features.ai.talent.gateway.i18n;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 网关文案覆盖率统计用的行为用例（新增行为用例类要登记到 SUITES）
 */
final class GatewayI18nSuites {
    static final List<String> PRODUCTION = Collections.singletonList("org.noear.solon.ai.talents.gateway.");
    static final List<Class<?>> SUITES = Arrays.<Class<?>>asList(GatewayRenderGoldenTest.class, GatewayI18nScenarioTest.class);

    private GatewayI18nSuites() {
    }
}
