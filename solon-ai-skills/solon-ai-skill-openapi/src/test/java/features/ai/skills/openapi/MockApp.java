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
        // 这里返回刚才补全的带 responses 的标准 JSON
        return "{\n" +
                "  \"openapi\": \"3.0.0\",\n" +
                "  \"paths\": {\n" +
                "    \"/users/{id}\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"获取用户信息\",\n" +
                "        \"parameters\": [ { \"name\": \"id\", \"in\": \"path\", \"required\": true, \"schema\": { \"type\": \"integer\" } } ],\n" +
                "        \"responses\": { \"200\": { \"content\": { \"application/json\": { \"schema\": { \n" +
                "                \"type\": \"object\", \"properties\": { \"id\": {\"type\":\"integer\"}, \"name\": {\"type\":\"string\"}, \"status\": {\"type\":\"string\"} } \n" +
                "        } } } } }\n" +
                "      }\n" +
                "    },\n" +
                "    \"/users/add\": {\n" +
                "      \"post\": {\n" +
                "        \"summary\": \"创建用户\",\n" +
                "        \"requestBody\": { \"content\": { \"application/json\": { \"schema\": { \"type\": \"object\", \"properties\": { \"name\": { \"type\": \"string\" } } } } } },\n" +
                "        \"responses\": { \"200\": { \"content\": { \"application/json\": { \"schema\": { \n" +
                "                \"type\": \"object\", \"properties\": { \"code\": {\"type\":\"integer\"}, \"data\": {\"type\":\"string\"} } \n" +
                "        } } } } }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";
    }

    // --- 2. 模拟业务逻辑接口 ---

    @Get
    @Mapping("users/{id}")
    public Map<String, Object> get(int id) {
        // 返回符合 outputSchema 定义的结构，方便 AI 解析 status 等字段
        return Utils.asMap("id", id, "name", "用户-" + id, "status", "active");
    }

    @Post
    @Mapping("users/add")
    public Map<String, Object> add(@Body User user) {
        // 模拟生成一个新的 ID
        String newId = "100" + (int)(Math.random() * 100);
        return Utils.asMap("code", 200, "data", newId);
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

    public static class User {
        public String id;
        public String name;
    }
}