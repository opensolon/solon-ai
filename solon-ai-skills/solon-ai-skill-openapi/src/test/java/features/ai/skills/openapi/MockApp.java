package features.ai.skills.openapi;

import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.annotation.*;

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

    // --- 1. 模拟 OpenAPI 描述文件接口 ---

    @Get
    @Mapping("swagger/v1/api-docs")
    public String apiDocs() {
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
                "    }\n" +
                "  },\n" +
                "  \"components\": {\n" +
                "    \"schemas\": {\n" +
                "      \"User\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": {\n" +
                "          \"id\": { \"type\": \"integer\" },\n" +
                "          \"name\": { \"type\": \"string\" },\n" +
                "          \"status\": { \"type\": \"string\", \"description\": \"用户状态: active, disabled\" }\n" +
                "        }\n" +
                "      },\n" +
                "      \"OrderRequest\": {\n" +
                "        \"type\": \"object\",\n" +
                "        \"properties\": {\n" +
                "          \"productName\": { \"type\": \"string\" },\n" +
                "          \"amount\": { \"type\": \"integer\" }\n" +
                "        },\n" +
                "        \"required\": [\"productName\"]\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";
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
            // 故意抛出错误，提示需要 age 参数
            throw new IllegalArgumentException("Missing required parameter: age");
        }
        return Utils.asMap("result", "ok");
    }
}