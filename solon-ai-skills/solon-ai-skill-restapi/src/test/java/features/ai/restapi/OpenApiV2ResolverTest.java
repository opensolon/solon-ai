package features.ai.restapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.skills.restapi.ApiTool;
import org.noear.solon.ai.skills.restapi.resolver.OpenApiV2Resolver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class OpenApiV2ResolverTest {

    private OpenApiV2Resolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new OpenApiV2Resolver();
    }

    @Test
    @DisplayName("完整流程覆盖：包含描述提取、多种响应码、废弃状态、方法过滤")
    void testComprehensiveResolve() {
        String json = "{" +
                "  \"swagger\": \"2.0\"," +
                "  \"paths\": {" +
                "    \"/api/v1/user/{id}\": {" +
                "      \"get\": {" +
                "        \"summary\": \"获取用户\"," +
                "        \"description\": \"详细的用户描述\"," +
                "        \"deprecated\": true," +
                "        \"parameters\": [" +
                "           {\"name\": \"id\", \"in\": \"path\", \"required\": true, \"type\": \"string\"}" +
                "        ]," +
                "        \"responses\": {" +
                "           \"201\": {\"schema\": {\"type\": \"object\", \"description\": \"创建成功\"}}" +
                "        }" +
                "      }," +
                "      \"options\": {" +
                "        \"description\": \"应该被忽略的方法\"" +
                "      }," +
                "      \"x-custom\": {" +
                "        \"description\": \"扩展方法也应被忽略\"" +
                "      }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);

        // 验证方法过滤：只保留 GET
        assertEquals(1, tools.size());
        ApiTool tool = tools.get(0);

        // 验证名称生成逻辑 (GET + path)
        assertEquals("get_api_v1_user_id_", tool.getName());
        // 验证描述提取优先级 (summary -> description)
        assertEquals("获取用户", tool.getDescription());
        // 验证状态
        assertTrue(tool.isDeprecated());
        // 验证响应 (没有200时取201)
        assertTrue(tool.getOutputSchema().contains("创建成功"));
    }

    @Test
    @DisplayName("复杂引用覆盖：多级引用、数组类型、枚举类型")
    void testComplexSchemaAndRef() {
        String json = "{" +
                "  \"swagger\": \"2.0\"," +
                "  \"paths\": {" +
                "    \"/order\": {" +
                "      \"post\": {" +
                "        \"parameters\": [{\"in\": \"body\", \"name\": \"body\", \"schema\": {\"$ref\": \"#/definitions/Order\"}}]" +
                "      }" +
                "    }" +
                "  }," +
                "  \"definitions\": {" +
                "    \"Order\": {" +
                "      \"type\": \"object\"," +
                "      \"properties\": {" +
                "        \"items\": {" +
                "           \"type\": \"array\"," +
                "           \"items\": {\"$ref\": \"#/definitions/Item\"}" +
                "        }," +
                "        \"status\": {\"type\": \"string\", \"enum\": [\"PENDING\", \"DONE\"]}" +
                "      }" +
                "    }," +
                "    \"Item\": {" +
                "      \"properties\": {\"id\": {\"type\": \"integer\"}}" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        String input = tools.get(0).getDataSchema();

        System.out.println(input);
        // 验证数组展开
        assertTrue(input.contains("\"items\""));
        // 验证二级引用展开
        assertTrue(input.contains("\"id\":{\"type\":\"integer\"}"));
        // 验证枚举包含
        assertTrue(input.contains("\"enum\":[\"PENDING\",\"DONE\"]"));
    }

    @Test
    @DisplayName("边界覆盖：参数为空、缺失响应、非对象参数节点")
    void testEdgeCases() {
        String json = "{" +
                "  \"swagger\": \"2.0\"," +
                "  \"paths\": {" +
                "    \"/empty\": {" +
                "      \"get\": {" +
                "        \"responses\": {}" +
                "      }" +
                "    }," +
                "    \"/no-params\": {" +
                "      \"put\": {" +
                "        \"parameters\": null," +
                "        \"responses\": {\"200\": {}}" +
                "      }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);

        // 验证无响应体情况
        ApiTool emptyTool = tools.stream().filter(t -> t.getPath().equals("/empty")).findFirst().get();
        assertEquals("{}", emptyTool.getOutputSchema());

        // 验证 null 参数处理
        ApiTool noParamTool = tools.stream().filter(t -> t.getPath().equals("/no-params")).findFirst().get();
        assertNotNull(noParamTool.getDataSchema());
    }

    @Test
    @DisplayName("逻辑覆盖：带有 required 属性和 cleaning 逻辑")
    void testCleaningLogic() {
        String json = "{" +
                "  \"swagger\": \"2.0\"," +
                "  \"paths\": {" +
                "    \"/clean\": {" +
                "      \"post\": {" +
                "        \"parameters\": [{" +
                "          \"in\": \"body\", \"schema\": {" +
                "             \"type\": \"object\"," +
                "             \"required\": [\"name\"]," +
                "             \"properties\": {\"name\": {\"type\": \"string\"}}," +
                "             \"x-internal-meta\": \"should be filtered\"" +
                "          }" +
                "        }]" +
                "      }" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        String input = tools.get(0).getDataSchema();

        // 验证保留了有效字段
        assertTrue(input.contains("\"required\":[\"name\"]"));
        // 验证 AbsOpenApiResolver 的过滤逻辑：只保留 type, properties, required 等，忽略 x- 开头的自定义字段（除非在 whitelist）
        assertFalse(input.contains("x-internal-meta"));
    }

    @Test
    @DisplayName("引用解析：解析非 definition 的 root 节点路径")
    void testRootPathRef() {
        String json = "{" +
                "  \"swagger\": \"2.0\"," +
                "  \"custom_schema\": {\"type\": \"boolean\"}," +
                "  \"paths\": {" +
                "    \"/ref\": {" +
                "      \"get\": {\"responses\": {\"200\": {\"schema\": {\"$ref\": \"#/custom_schema\"}}}}" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        assertEquals("{\"type\":\"boolean\"}", tools.get(0).getOutputSchema());
    }

    @Test
    @DisplayName("逻辑覆盖：Parameters 为非数组引用 (覆盖 V2 doResolve else 分支)")
    void testNonArrayParameters() {
        String json = "{" +
                "  \"swagger\": \"2.0\"," +
                "  \"paths\": {" +
                "    \"/external\": {" +
                "      \"get\": {" +
                "        \"parameters\": { \"$ref\": \"#/parameterDefinitions/SingleParam\" }," + // 注意这里引用的是单个对象
                "        \"responses\": { \"200\": { \"description\": \"ok\" } }" +
                "      }" +
                "    }" +
                "  }," +
                "  \"parameterDefinitions\": {" +
                "    \"SingleParam\": {" + // 这里定义为 Object 才能匹配 resolveRef 的逻辑
                "       \"name\": \"token\"," +
                "       \"in\": \"header\"," +
                "       \"type\": \"string\"," +
                "       \"description\": \"access_token\"" + // 使用 description 因为它在白名单里
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        // 触发了 tool.setInputSchema(resolveRef(root, params)) 逻辑
        System.out.println(tools.get(0).getDataSchema());
        assertTrue(tools.get(0).getDataSchema().contains("token"));
    }

    @Test
    @DisplayName("逻辑覆盖：Schema 顶层为数组类型 (覆盖 AbsOpenApiResolver isArray 分支)")
    void testArraySchemaResolve() {
        String json = "{" +
                "  \"swagger\": \"2.0\"," +
                "  \"paths\": {" +
                "    \"/tags\": {" +
                "      \"get\": {" +
                "        \"responses\": {" +
                "          \"200\": {" +
                "            \"schema\": { \"$ref\": \"#/definitions/TagList\" }" +
                "          }" +
                "        }" +
                "      }" +
                "    }" +
                "  }," +
                "  \"definitions\": {" +
                "    \"TagList\": [" +
                "      { \"type\": \"string\" }" +
                "    ]" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        // 触发了 resolveRefNode 中的 node.isArray() 逻辑
        assertEquals("[{\"type\":\"string\"}]", tools.get(0).getOutputSchema());
    }

    @Test
    @DisplayName("循环引用覆盖：防止无限递归")
    void testCircularReference() {
        String json = "{" +
                "  \"swagger\": \"2.0\"," +
                "  \"paths\": {\"/loop\": {\"get\": {\"responses\": {\"200\": {\"schema\": {\"$ref\": \"#/definitions/Node\"}}}}}}," +
                "  \"definitions\": {" +
                "    \"Node\": {" +
                "      \"properties\": {\"next\": {\"$ref\": \"#/definitions/Node\"}}" +
                "    }" +
                "  }" +
                "}";

        List<ApiTool> tools = resolver.resolve(null, json);
        String output = tools.get(0).getOutputSchema();
        // 验证是否正确触发了基类的防护标识
        assertTrue(output.contains("_Circular_Reference_"));
    }
}