package features.ai.restapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.skills.restapi.ApiTool;
import org.noear.solon.ai.skills.restapi.resolver.OpenApiV3Resolver;
import org.noear.solon.core.util.ResourceUtil;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基于 Petstore 3.0 官方数据的 OpenAPI V3 解析验证
 */
public class OpenApiV3ResolverTest {

    private OpenApiV3Resolver resolver = new OpenApiV3Resolver();
    private String openapi_v3_json;

    public String getOpenApiJson() {
        if (openapi_v3_json == null) {
            try {
                // 确保 resource 目录下有 openapi-v3.json (来自 https://petstore3.swagger.io/api/v3/openapi.json)
                openapi_v3_json = ResourceUtil.getResourceAsString("openapi-v3.json");
            } catch (IOException e) {
                throw new RuntimeException("无法读取测试资源文件", e);
            }
        }
        return openapi_v3_json;
    }

    @Test
    @DisplayName("Petstore V3：验证 BaseUrl 提取 (Server 节点)")
    void testV3BaseUrlExtraction() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        assertFalse(tools.isEmpty());
        // V3 官方数据第一个 server 通常是 https://petstore3.swagger.io/api/v3
        assertEquals("https://petstore3.swagger.io/api/v3", tools.get(0).getBaseUrl());
    }

    @Test
    @DisplayName("Petstore V3：验证 RequestBody 的深度平铺 (Ref 引用)")
    void testV3RequestBodyResolution() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 查找 POST /pet (V3 中 body 不再在 parameters 里)
        ApiTool tool = tools.stream()
                .filter(t -> "/pet".equals(t.getPath()) && "POST".equals(t.getMethod()))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        String bodySchema = tool.getBodySchema();

        // 验证是否从 #/components/schemas/Pet 平铺展开
        assertTrue(bodySchema.contains("\"name\":{\"type\":\"string\"}"));
        // 验证嵌套的 Category 引用是否展开
        assertTrue(bodySchema.contains("category"));
    }

    @Test
    @DisplayName("Petstore V3：验证 Query 参数与枚举")
    void testV3QueryAndEnumResolution() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 查找 GET /pet/findByStatus
        ApiTool tool = tools.stream()
                .filter(t -> "/pet/findByStatus".equals(t.getPath()))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        String querySchema = tool.getQuerySchema();

        assertTrue(querySchema.contains("status"));
        // 验证 V3 下枚举值解析是否正确
        assertTrue(querySchema.contains("available"));
        assertTrue(querySchema.contains("pending"));
    }

    @Test
    @DisplayName("Petstore V3：验证响应结构深度平铺 (Array<Pet>)")
    void testV3ResponseArrayFlattening() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 查找 GET /pet/findByTags (返回 Pet 数组)
        ApiTool tool = tools.stream()
                .filter(t -> t.getPath().contains("findByTags"))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        String output = tool.getOutputSchema();

        System.out.println(output);
        // 验证 Array 内部的 items 引用是否被平铺
        assertTrue(output.contains("items"));
        assertTrue(output.contains("\"name\":{\"type\":\"string\"}"));
        assertFalse(output.contains("$ref"), "输出 Schema 不应包含未解析的引用");
    }

    @Test
    @DisplayName("Petstore V3：验证 Multipart 识别")
    void testV3MultipartResolution() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 查找 POST /pet/{petId}/uploadImage
        ApiTool tool = tools.stream()
                .filter(t -> t.getPath().contains("uploadImage"))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        // V3 中通过 content 映射来识别
        assertTrue(tool.isMultipart(), "V3 uploadImage 应通过 content-type 识别为 Multipart");
    }

    @Test
    @DisplayName("Petstore V3：验证 Required 逻辑")
    void testV3RequiredLogic() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 查找 POST /pet
        ApiTool tool = tools.stream()
                .filter(t -> "/pet".equals(t.getPath()) && "POST".equals(t.getMethod()))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        // V3 的 required 字段通常定义在 schema 根部
        assertTrue(tool.getBodySchema().contains("\"required\":[\"name\""));
    }

    @Test
    @DisplayName("Petstore V3：验证 Deprecated 标记")
    void testV3DeprecatedResolution() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 检查是否存在被标记为废弃的 API
        boolean hasDeprecated = tools.stream().anyMatch(ApiTool::isDeprecated);
        // 这里的断言取决于具体 openapi.json 的内容，可以打印观察
        System.out.println("Has deprecated APIs: " + hasDeprecated);
    }

    @Test
    @DisplayName("Petstore V3：验证 Path 参数解析")
    void testV3PathParameterResolution() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 查找 GET /pet/{petId}
        ApiTool tool = tools.stream()
                .filter(t -> t.getPath().contains("/{petId}") && "GET".equals(t.getMethod()))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        String pathSchema = tool.getPathSchema();

        // 验证 pathSchema 是否包含 petId 且标记为必填
        assertTrue(pathSchema.contains("petId"));
        assertTrue(pathSchema.contains("\"required\":[\"petId\""));
    }

    @Test
    @DisplayName("Petstore V3：验证 API 总数加载")
    void testV3TotalApiCount() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // Petstore V3 官方数据大约有 19 个左右的操作节点
        assertTrue(tools.size() >= 18, "解析到的 API 数量不应少于 18 个");
    }

    @Test
    @DisplayName("Petstore V3：验证 Name(OperationId) 提取")
    void testV3OperationIdNaming() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        ApiTool tool = tools.stream()
                .filter(t -> "/pet".equals(t.getPath()) && "PUT".equals(t.getMethod()))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        // 官方 Petstore V3 中 PUT /pet 的 operationId 是 updatePet
        assertEquals("updatePet", tool.getName());
    }
}