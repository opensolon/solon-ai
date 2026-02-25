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
        assertEquals("Summary Info", tool.getDescription()); // 优先取 summary
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
                "            \"application/json\": {\"schema\": {\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"int\"}}}}" +
                "          }" +
                "        }" +
                "      }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        String input = tools.get(0).getInputSchema();

        // 验证即使 application/json 不是第一个，也能被正确选中
        assertTrue(input.contains("Body:"));
        assertTrue(input.contains("\"properties\":{\"id\":{\"type\":\"int\"}}"));
    }

    @Test
    @DisplayName("RequestBody 深度覆盖：无 JSON 时回退到首个内容类型")
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
        String input = tools.get(0).getInputSchema();

        assertTrue(input.contains("file_data"));
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
    @DisplayName("组合参数：Parameters + RequestBody 拼接覆盖")
    void testParametersAndBodyCombination() {
        String json = "{" +
                "  \"openapi\": \"3.0.0\"," +
                "  \"paths\": {" +
                "    \"/mix/{id}\": {" +
                "      \"put\": {" +
                "        \"parameters\": [{\"name\": \"id\", \"in\": \"path\"}]," +
                "        \"requestBody\": {" +
                "          \"content\": {\"application/json\": {\"schema\": {\"type\": \"string\"}}}" +
                "        }" +
                "      }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        String input = tools.get(0).getInputSchema();

        // 验证拼接逻辑 "Parameters + Body:..."
        assertTrue(input.contains("\"name\":\"id\""));
        assertTrue(input.contains(" + Body:"));
        assertTrue(input.contains("{\"type\":\"string\"}"));
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
        String input = tools.get(0).getInputSchema();

        // 验证 oneOf 是否被保留
        assertTrue(input.contains("\"oneOf\""));
        // 验证嵌套引用是否展开
        assertTrue(input.contains("\"meow\""));
        assertTrue(input.contains("\"bark\""));
        // 验证非法字段是否被过滤（AbsOpenApiResolver 逻辑）
        assertFalse(input.contains("x-internal"));
    }

    @Test
    @DisplayName("空值与特殊结构：处理 content 为空或参数为空的情况")
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

        assertEquals("{}", tool.getInputSchema());
        assertEquals("{}", tool.getOutputSchema());
    }

    @Test
    @DisplayName("RequestBody 覆盖：处理非标准的 content 数组结构")
    void testRequestBodyAsArray() {
        // 触发源码中 if(content.isArray()) 分支
        String json = "{" +
                "  \"openapi\": \"3.0.0\"," +
                "  \"paths\": {" +
                "    \"/array-content\": {" +
                "      \"post\": {" +
                "        \"requestBody\": {" +
                "          \"content\": [" +
                "            { \"schema\": { \"type\": \"string\", \"description\": \"array_style\" } }" +
                "          ]" +
                "        }" +
                "      }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        assertTrue(tools.get(0).getInputSchema().contains("array_style"));
    }

    @Test
    @DisplayName("RequestBody 深度覆盖：application/json 优先逻辑")
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
        String input = tools.get(0).getInputSchema();
        assertTrue(input.contains("\"id\""));
    }

    @Test
    @DisplayName("Responses 覆盖：处理 content 为数组的情况")
    void testResponseContentAsArray() {
        // 触发源码中 responses 里的 content.isArray() 分支
        String json = "{" +
                "  \"openapi\": \"3.0.0\"," +
                "  \"paths\": {" +
                "    \"/resp-array\": {" +
                "      \"get\": {" +
                "        \"responses\": {" +
                "          \"200\": {" +
                "            \"content\": [" +
                "              { \"schema\": { \"type\": \"integer\" } }" +
                "            ]" +
                "          }" +
                "        }" +
                "      }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        assertEquals("{\"type\":\"integer\"}", tools.get(0).getOutputSchema());
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
                "      \"Node\": { \"properties\": { \"next\": { \"$ref\": \"#/components/schemas/Node\" } } }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        assertTrue(tools.get(0).getInputSchema().contains("_Circular_Reference_"));
    }
}