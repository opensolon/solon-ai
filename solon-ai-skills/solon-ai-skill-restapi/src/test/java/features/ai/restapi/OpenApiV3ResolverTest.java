package features.ai.restapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.skills.restapi.ApiTool;
import org.noear.solon.ai.skills.restapi.resolver.OpenApiV3Resolver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class OpenApiV3ResolverTest {

    private OpenApiV3Resolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new OpenApiV3Resolver();
    }

    @Test
    @DisplayName("基础解析：获取名称、描述、方法和路径")
    void testBaseInfoResolve() {
        String json = "{" +
                "  \"openapi\": \"3.0.0\"," +
                "  \"paths\": {" +
                "    \"/api/v3/user\": {" +
                "      \"get\": {" +
                "        \"operationId\": \"getUser\"," +
                "        \"summary\": \"Summary Info\"," +
                "        \"description\": \"Detailed Description\"," +
                "        \"deprecated\": true" +
                "      }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);

        assertEquals(1, tools.size());
        ApiTool tool = tools.get(0);
        assertEquals("getUser", tool.getName());
        assertEquals("GET", tool.getMethod());
        assertEquals("/api/v3/user", tool.getPath());
        assertEquals("Summary Info", tool.getDescription());
        assertTrue(tool.isDeprecated());
    }

    @Test
    @DisplayName("RequestBody 深度覆盖：application/json 优先逻辑")
    void testRequestBodyWithJson() {
        String json = "{" +
                "  \"openapi\": \"3.0.0\"," +
                "  \"paths\": {" +
                "    \"/save\": {" +
                "      \"post\": {" +
                "        \"requestBody\": {" +
                "          \"content\": {" +
                "            \"text/plain\": {\"schema\": {\"type\": \"string\"}}," +
                "            \"application/json\": {\"schema\": {\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"integer\"}}}}" +
                "          }" +
                "        }" +
                "      }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        String input = tools.get(0).getDataSchema();

        // 验证即使 application/json 不是第一个，也能被正确选中，且类型为 integer
        assertTrue(input.contains("\"id\""));
        assertTrue(input.contains("\"type\":\"integer\""));
    }

    @Test
    @DisplayName("RequestBody 深度覆盖：无 JSON 时回退到首个内容类型并标记 Multipart")
    void testRequestBodyFallback() {
        String json = "{" +
                "  \"openapi\": \"3.0.0\"," +
                "  \"paths\": {" +
                "    \"/upload\": {" +
                "      \"post\": {" +
                "        \"requestBody\": {" +
                "          \"content\": {" +
                "            \"multipart/form-data\": {" +
                "              \"schema\": {\"type\": \"object\", \"description\": \"file_data\"}" +
                "            }" +
                "          }" +
                "        }" +
                "      }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        ApiTool tool = tools.get(0);

        // 验证 Multipart 标志
        assertTrue(tool.isMultipart(), "应当识别为 Multipart 模式");
        assertTrue(tool.getDataSchema().contains("file_data"));
    }

    @Test
    @DisplayName("响应解析：支持 200 或 default 节点")
    void testResponseResolve() {
        String json = "{" +
                "  \"openapi\": \"3.0.0\"," +
                "  \"paths\": {" +
                "    \"/resp\": {" +
                "      \"get\": {" +
                "        \"responses\": {" +
                "          \"default\": {" +
                "            \"content\": {" +
                "              \"application/json\": {\"schema\": {\"type\": \"boolean\"}}" +
                "            }" +
                "          }" +
                "        }" +
                "      }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        assertEquals("{\"type\":\"boolean\"}", tools.get(0).getOutputSchema());
    }

    @Test
    @DisplayName("组合参数：Parameters + RequestBody 合并解析")
    void testParametersAndBodyCombination() {
        String json = "{" +
                "  \"openapi\": \"3.0.0\"," +
                "  \"paths\": {" +
                "    \"/mix/{id}\": {" +
                "      \"put\": {" +
                "        \"parameters\": [{\"name\": \"id\", \"in\": \"path\", \"required\": true, \"schema\": {\"type\": \"string\"}}]," +
                "        \"requestBody\": {" +
                "          \"content\": {\"application/json\": {\"schema\": {\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}}}}" +
                "        }" +
                "      }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        String pathSchema = tools.get(0).getPathSchema();
        String dataSchema = tools.get(0).getDataSchema();

        // 验证 Path 参数解析到 pathSchema
        assertTrue(pathSchema.contains("\"id\""));
        // 验证 Body 参数解析到 dataSchema (不再检查非标的 " + Body:" 字符串)
        assertTrue(dataSchema.contains("\"name\""));
    }

    @Test
    @DisplayName("递归解析与清理：OneOf/AnyOf 与属性过滤覆盖")
    void testRecursiveCleaning() {
        String json = "{" +
                "  \"openapi\": \"3.0.0\"," +
                "  \"paths\": {" +
                "    \"/poly\": {" +
                "      \"post\": {" +
                "        \"requestBody\": {\"content\": {\"application/json\": {\"schema\": {\"$ref\": \"#/components/schemas/Pet\"}}}}" +
                "      }" +
                "    }" +
                "  }," +
                "  \"components\": {" +
                "    \"schemas\": {" +
                "      \"Pet\": {" +
                "        \"oneOf\": [" +
                "          {\"$ref\": \"#/components/schemas/Cat\"}," +
                "          {\"$ref\": \"#/components/schemas/Dog\"}" +
                "        ]," +
                "        \"x-internal\": \"removed\"" +
                "      }," +
                "      \"Cat\": {\"type\": \"object\", \"properties\": {\"meow\": {\"type\": \"boolean\"}}}," +
                "      \"Dog\": {\"type\": \"object\", \"properties\": {\"bark\": {\"type\": \"boolean\"}}}" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        String input = tools.get(0).getDataSchema();

        assertTrue(input.contains("\"oneOf\""));
        assertTrue(input.contains("\"meow\""));
        assertTrue(input.contains("\"bark\""));
        assertFalse(input.contains("x-internal"));
    }

    @Test
    @DisplayName("空值与特殊结构：处理 content 为空的情况")
    void testNullContent() {
        String json = "{" +
                "  \"openapi\": \"3.0.0\"," +
                "  \"paths\": {" +
                "    \"/null\": {" +
                "      \"post\": {" +
                "        \"requestBody\": {\"content\": {}}," +
                "        \"responses\": {\"200\": {\"description\": \"no content\"}}" +
                "      }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        ApiTool tool = tools.get(0);

        // 规范：空的 content 映射不产生 properties
        assertEquals("{\"type\":\"object\"}", tool.getDataSchema());
        assertNull(tool.getOutputSchema());
    }

    @Test
    @DisplayName("RequestBody 覆盖：符合标准的媒体类型结构")
    void testRequestBodyStandard() {
        String json = "{" +
                "  \"openapi\": \"3.0.0\"," +
                "  \"paths\": {" +
                "    \"/save\": {" +
                "      \"post\": {" +
                "        \"requestBody\": {" +
                "          \"content\": {" +
                "            \"application/json\": {\"schema\": {\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"integer\"}}}}" +
                "          }" +
                "        }" +
                "      }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        String input = tools.get(0).getDataSchema();
        assertTrue(input.contains("\"id\""));
    }

    @Test
    @DisplayName("循环引用覆盖：V3 场景下的递归防护")
    void testCircularReferenceV3() {
        String json = "{" +
                "  \"openapi\": \"3.0.0\"," +
                "  \"paths\": {" +
                "    \"/loop\": {" +
                "      \"post\": {" +
                "        \"requestBody\": { \"content\": { \"application/json\": { \"schema\": { \"$ref\": \"#/components/schemas/Node\" } } } }" +
                "      }" +
                "    }" +
                "  }," +
                "  \"components\": {" +
                "    \"schemas\": {" +
                "      \"Node\": { \"type\": \"object\", \"properties\": { \"next\": { \"$ref\": \"#/components/schemas/Node\" } } }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        assertTrue(tools.get(0).getDataSchema().contains("_Circular_Reference_"));
    }
}