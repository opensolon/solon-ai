package features.ai.talent.gateway.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.i18n.testkit.I18nKeyCoverage;
import org.noear.solon.ai.talents.gateway.i18n.OpenApiGatewayI18n;

import java.util.Collections;
import java.util.List;

/**
 * OpenApiGateway 文案 key 的测试覆盖率（中英 100%；读取覆盖 + 断言覆盖；行为用例见 {@link GatewayI18nSuites}）
 */
public class OpenApiGatewayI18nCoverageTest {
    static final List<Class<?>> FACADES = Collections.singletonList(OpenApiGatewayI18n.class);

    @Test
    public void testEveryKeyIsExercised() throws Throwable {
        I18nKeyCoverage.verify(OpenApiGatewayI18n.messages(), GatewayI18nSuites.PRODUCTION, FACADES, GatewayI18nSuites.SUITES);
    }

    @Test
    public void testEveryKeyIsAsserted() throws Throwable {
        I18nKeyCoverage.verifyAsserted(OpenApiGatewayI18n.messages(), GatewayI18nSuites.PRODUCTION, FACADES, GatewayI18nSuites.SUITES);
    }
}
