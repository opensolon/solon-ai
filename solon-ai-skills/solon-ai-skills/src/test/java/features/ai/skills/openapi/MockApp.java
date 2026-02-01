package features.ai.skills.openapi;

import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Context;

import java.util.Map;

/**
 * 模拟业务系统应用
 *
 * @author noear 2026/1/30 created
 */
@Controller
public class MockApp {
    public static void main(String[] args) {
        Solon.start(MockApp.class, args);
    }

    // --- 1. 模拟 OpenAPI 3.0 (OAS3) ---
    @Get
    @Mapping("swagger/v3/api-docs")
    public String apiDocs3() {
        return "{\n" +
                "  \"openapi\": \"3.0.0\",\n" +
                "  \"paths\": {\n" +
                "    \"/users/{id}\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"获取用户信息\",\n" +
                "        \"parameters\": [ { \"name\": \"id\", \"in\": \"path\", \"required\": true, \"schema\": { \"type\": \"integer\" } } ],\n" +
                "        \"responses\": { \"200\": { \"content\": { \"application/json\": { \"schema\": { \"$ref\": \"#/components/schemas/User\" } } } } }\n" +
                "      }\n" +
                "    },\n" +
                "    \"/orders/create\": {\n" +
                "      \"post\": {\n" +
                "        \"summary\": \"创建订单\",\n" +
                "        \"requestBody\": { \"content\": { \"application/json\": { \"schema\": { \"$ref\": \"#/components/schemas/OrderRequest\" } } } },\n" +
                "        \"responses\": { \"200\": { \"content\": { \"application/json\": { \"schema\": { \"type\": \"object\", \"properties\": { \"orderId\": { \"type\": \"string\" } } } } } } }\n" +
                "      }\n" +
                "    },\n" + // <-- 修正：补齐逗号
                "    \"/admin/config\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"[鉴权] 获取系统配置\",\n" +
                "        \"responses\": { \"200\": { \"description\": \"OK\", \"content\": { \"application/json\": { \"schema\": { \"type\": \"object\" } } } } }\n" +
                "       }\n" +
                "    },\n" + // <-- 修正：补齐逗号
                "    \"/users/error_test\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"错误测试接口\",\n" +
                "        \"parameters\": [ { \"name\": \"age\", \"in\": \"query\", \"required\": true, \"schema\": { \"type\": \"integer\" } } ],\n" +
                "        \"responses\": { \"200\": { \"description\": \"OK\" } }\n" +
                "      }\n" +
                "    }\n" +
                "  },\n" +
                "  \"components\": {\n" +
                "    \"schemas\": {\n" +
                "      \"User\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"description\": \"用户信息\",\n" +
                "        \"properties\": {\n" +
                "          \"id\": { \"type\": \"integer\" },\n" +
                "          \"name\": { \"type\": \"string\" },\n" +
                "          \"status\": { \"type\": \"string\" },\n" +
                "          \"group\": { \"$ref\": \"#/components/schemas/Group\" }\n" + // 循环引用测试支持
                "        }\n" +
                "      },\n" +
                "      \"Group\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": { \"name\": { \"type\": \"string\" }, \"leader\": { \"$ref\": \"#/components/schemas/User\" } }\n" +
                "      },\n" +
                "      \"OrderRequest\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": { \"productName\": { \"type\": \"string\" }, \"amount\": { \"type\": \"integer\" } },\n" +
                "        \"required\": [\"productName\"]\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";
    }

    // --- 2. 模拟 OpenAPI 2.0 (Swagger) ---
    @Get
    @Mapping("swagger/v2/api-docs")
    public String apiDocs2() {
        return "{\n" +
                "  \"swagger\": \"2.0\",\n" +
                "  \"info\": { \"title\": \"Mock API\", \"version\": \"1.0\" },\n" +
                "  \"paths\": {\n" +
                "    \"/users/{id}\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"获取用户信息\",\n" +
                "        \"parameters\": [ { \"name\": \"id\", \"in\": \"path\", \"required\": true, \"type\": \"integer\" } ],\n" +
                "        \"responses\": {\n" +
                "          \"200\": {\n" +
                "            \"description\": \"OK\",\n" +
                "            \"schema\": { \"$ref\": \"#/definitions/User\" }\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    },\n" +
                "    \"/orders/create\": {\n" +
                "      \"post\": {\n" +
                "        \"summary\": \"创建订单\",\n" +
                "        \"parameters\": [ { \"name\": \"body\", \"in\": \"body\", \"required\": true, \"schema\": { \"$ref\": \"#/definitions/OrderRequest\" } } ],\n" +
                "        \"responses\": { \"200\": { \"description\": \"OK\", \"schema\": { \"type\": \"object\", \"properties\": { \"orderId\": { \"type\": \"string\" } } } } }\n" +
                "      }\n" +
                "    },\n" +
                "    \"/admin/config\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"[鉴权] 获取系统配置\",\n" +
                "        \"responses\": { \"200\": { \"description\": \"OK\", \"schema\": { \"type\": \"object\" } } }\n" +
                "      }\n" +
                "    }\n" +
                "  },\n" +
                "  \"definitions\": {\n" +
                "    \"User\": {\n" +
                "      \"type\": \"object\",\n" +
                "      \"properties\": {\n" +
                "        \"id\": { \"type\": \"integer\" },\n" +
                "        \"name\": { \"type\": \"string\" },\n" +
                "        \"status\": { \"type\": \"string\" },\n" + // 明确加上 status 类型
                "        \"group\": { \"$ref\": \"#/definitions/Group\" }\n" +
                "      }\n" +
                "    },\n" +
                "    \"Group\": {\n" +
                "      \"type\": \"object\",\n" +
                "      \"properties\": { \"name\": { \"type\": \"string\" }, \"leader\": { \"$ref\": \"#/definitions/User\" } }\n" +
                "    },\n" +
                "    \"OrderRequest\": {\n" +
                "      \"type\": \"object\",\n" +
                "      \"properties\": {\n" +
                "        \"productName\": { \"type\": \"string\" },\n" +
                "        \"detail\": { \"$ref\": \"#/definitions/OrderDetail\" }\n" +
                "      }\n" +
                "    },\n" +
                "    \"OrderDetail\": { \"type\": \"object\", \"properties\": { \"remark\": { \"type\": \"string\" } } }\n" +
                "  }\n" +
                "}";
    }

    // --- 业务接口实现 ---

    @Get
    @Mapping("admin/config")
    public Map<String, Object> getConfig(Context ctx, @Header("Authorization") String auth) {
        if (auth == null || !auth.contains("mock-token")) {
            ctx.status(401); // 显式设置 401，满足 testAuthentication_Flow
            return Utils.asMap("error", "Unauthorized");
        }
        return Utils.asMap("env", "prod", "version", "1.0.0");
    }

    @Get
    @Mapping("users/{id}")
    public Map<String, Object> get(int id) {
        return Utils.asMap("id", id, "name", "Noear", "status", "active");
    }

    @Post
    @Mapping("orders/create")
    public Map<String, Object> createOrder(@Body Map<String, Object> order) {
        return Utils.asMap("orderId", "ORD-" + System.currentTimeMillis());
    }

    @Get
    @Mapping("users/error_test")
    public Map<String, Object> errorTest(Integer age) {
        if (age == null) {
            throw new IllegalArgumentException("Missing required parameter: age");
        }
        return Utils.asMap("result", "ok");
    }
}