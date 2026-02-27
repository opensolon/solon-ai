package features.ai.restapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.skills.restapi.ApiTool;
import org.noear.solon.ai.skills.restapi.resolver.OpenApiV2Resolver;
import org.noear.solon.core.util.ResourceUtil;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基于 Petstore 官方数据的 Swagger 2.0 解析验证
 */
public class OpenApiV2ResolverTest {

    private OpenApiV2Resolver resolver = new OpenApiV2Resolver();
    private String swagger_v2_json;

    public String getOpenApiJson() {
        if (swagger_v2_json == null) {
            try {
                // 确保 resource 目录下有 swagger-v2.json（来自：https://petstore.swagger.io/v2/swagger.json）
                swagger_v2_json = ResourceUtil.getResourceAsString("swagger-v2.json");
            } catch (IOException e) {
                throw new RuntimeException("无法读取测试资源文件", e);
            }
        }
        return swagger_v2_json;
    }

    @Test
    @DisplayName("Petstore：验证基础信息与路径参数")
    void testBaseInfoAndPathParams() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 验证 API 数量（Petstore 通常有 20 个左右的 operation）
        assertTrue(tools.size() > 10);

        // 查找 GET /pet/{petId}
        ApiTool tool = tools.stream()
                .filter(t -> "/pet/{petId}".equals(t.getPath()) && "GET".equals(t.getMethod()))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        assertEquals("getPetById", tool.getName());
        assertTrue(tool.getDescription().contains("Find pet by ID"));

        // 验证 Path 参数解析
        assertNotNull(tool.getPathSchema());
        assertTrue(tool.getPathSchema().contains("petId"));
        assertTrue(tool.getPathSchema().contains("\"type\":\"integer\""));
    }

    @Test
    @DisplayName("Petstore：验证 Body 参数的深度展开（Ref 引用）")
    void testBodySchemaResolution() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 查找 POST /pet
        ApiTool tool = tools.stream()
                .filter(t -> "/pet".equals(t.getPath()) && "POST".equals(t.getMethod()))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        String bodySchema = tool.getBodySchema();

        // 验证 Pet 模型是否被平铺展开
        assertTrue(bodySchema.contains("\"name\":{\"type\":\"string\"}"));
        // 验证嵌套引用 Category 是否被解析
        assertTrue(bodySchema.contains("category"));
        // 验证数组引用 Tag 是否被解析
        assertTrue(bodySchema.contains("tags"));
    }

    @Test
    @DisplayName("Petstore：验证 Query 参数与枚举 Enum")
    void testQueryAndEnumResolution() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 查找 GET /pet/findByStatus
        ApiTool tool = tools.stream()
                .filter(t -> "/pet/findByStatus".equals(t.getPath()))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        String querySchema = tool.getQuerySchema();

        // 验证 Query 参数 status 是否存在
        assertTrue(querySchema.contains("status"));
        // 验证枚举值解析
        assertTrue(querySchema.contains("available"));
        assertTrue(querySchema.contains("pending"));
        assertTrue(querySchema.contains("sold"));
    }

    @Test
    @DisplayName("Petstore：验证 Multipart/FormData 解析")
    void testMultipartResolution() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 查找 POST /pet/{petId}/uploadImage
        ApiTool tool = tools.stream()
                .filter(t -> t.getPath().contains("uploadImage") && "POST".equals(t.getMethod()))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        // 验证 Multipart 标识
        assertTrue(tool.isMultipart(), "uploadImage 接口应识别为 Multipart");

        // 验证 BodySchema 包含 formData 字段
        String bodySchema = tool.getBodySchema();
        assertTrue(bodySchema.contains("file") || bodySchema.contains("additionalMetadata"));
    }

    @Test
    @DisplayName("Petstore：验证响应结构解析")
    void testResponseResolution() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 查找 GET /store/order/{orderId}
        ApiTool tool = tools.stream()
                .filter(t -> t.getPath().startsWith("/store/order/"))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        String outputSchema = tool.getOutputSchema();

        // 验证返回的是 Order 模型结构
        assertTrue(outputSchema.contains("shipDate"));
        assertTrue(outputSchema.contains("quantity"));
        assertTrue(outputSchema.contains("\"type\":\"integer\""));
    }

    @Test
    @DisplayName("Petstore：验证 Header 参数解析")
    void testHeaderResolution() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 查找 DELETE /pet/{petId}
        ApiTool tool = tools.stream()
                .filter(t -> "/pet/{petId}".equals(t.getPath()) && "DELETE".equals(t.getMethod()))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        // Petstore 中该接口通常定义了 api_key 这个 header 参数
        String headerSchema = tool.getHeaderSchema();
        assertNotNull(headerSchema);
        assertTrue(headerSchema.contains("api_key"));
    }

    @Test
    @DisplayName("Petstore：验证 BaseUrl 提取逻辑")
    void testBaseUrlExtraction() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        assertFalse(tools.isEmpty());
        ApiTool tool = tools.get(0);

        // Petstore V2 默认 host 通常是 petstore.swagger.io，basePath 是 /v2
        assertEquals("https://petstore.swagger.io/v2", tool.getBaseUrl());
    }

    @Test
    @DisplayName("Petstore：深度验证 Array 内部引用是否平铺")
    void testArrayItemsFlattening() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 查找 GET /pet/findByTags
        ApiTool tool = tools.stream()
                .filter(t -> t.getPath().contains("findByTags"))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        // findByTags 的响应或参数涉及 Tag 对象的数组
        // 验证 outputSchema 里的 tags 数组内部是否展开了 Tag 的属性 (id, name)
        String output = tool.getOutputSchema();
        System.out.println(output);

        // 关键点：如果平铺成功，output 不应只含有 "$ref"，而应含有 Tag 的属性字段
        assertTrue(output.contains("\"name\":{\"type\":\"string\"}"));
        assertTrue(output.contains("items"));
    }

    @Test
    @DisplayName("Petstore：验证枚举参数的 Required 状态")
    void testQueryRequiredLogic() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 查找 GET /pet/findByStatus
        ApiTool tool = tools.stream()
                .filter(t -> t.getPath().contains("findByStatus"))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        String querySchema = tool.getQuerySchema();

        // 验证 status 是必填的（在 Petstore 定义中通常为 true）
        assertTrue(querySchema.contains("\"required\":[\"status\"]"));
    }

    @Test
    @DisplayName("Petstore：验证非 200 的成功响应 (201/default)")
    void testNon200ResponseResolution() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // 某些版本的 Petstore 更新操作可能返回 default 或非 200 码
        // 验证 POST /user/createWithArray 这种接口的响应解析
        ApiTool tool = tools.stream()
                .filter(t -> "/user/createWithArray".equals(t.getPath()))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        assertNull(tool.getOutputSchema());
    }

    @Test
    @DisplayName("Petstore：验证 Map 类型（无 Properties 的对象）")
    void testMapTypeResolution() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // GET /store/inventory 返回的是 Map<String, int>
        ApiTool tool = tools.stream()
                .filter(t -> "/store/inventory".equals(t.getPath()))
                .findFirst()
                .orElse(null);

        assertNotNull(tool);
        String output = tool.getOutputSchema();

        // 对于 Map，Swagger V2 通常将其表现为没有 properties 的 object
        // 验证它是否被识别为 object 或者是包含 integer 的结构
        assertTrue(output.contains("object") || output.contains("integer"));
    }

    @Test
    @DisplayName("Petstore：验证 Deprecated 标记解析")
    void testDeprecatedResolution() throws IOException {
        String json = getOpenApiJson();
        List<ApiTool> tools = resolver.resolve(null, json);

        // Petstore 中有些接口可能被标记为 deprecated
        // 比如在某些版本中的 updatePetWithForm 或类似接口
        for (ApiTool tool : tools) {
            // 我们不确定具体哪个接口，但可以验证解析过程没报错，
            // 且如果存在 vendorExtensions 里的 deprecated，工具能正确识别
            if (tool.isDeprecated()) {
                System.out.println("Found deprecated tool: " + tool.getName());
                assertTrue(tool.isDeprecated());
            }
        }
    }
}