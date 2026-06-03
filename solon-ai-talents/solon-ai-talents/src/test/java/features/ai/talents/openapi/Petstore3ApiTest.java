package features.ai.talents.openapi;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.openapi.ApiSourceClient;
import org.noear.solon.ai.talents.openapi.OpenApiTalent;

/**
 *
 * @author noear 2026/6/3 created
 *
 */
public class Petstore3ApiTest {
    private String docUrl = "https://petstore3.swagger.io/api/v3/openapi.json";
    private String baseUrl = "https://petstore3.swagger.io/api/v3";

    @Test
    public void case1() {
        OpenApiTalent openApiTalent = new OpenApiTalent();

        openApiTalent.addApi(docUrl, baseUrl);
        ApiSourceClient sourceProvider = openApiTalent.getApiSource(docUrl);
    }
}
