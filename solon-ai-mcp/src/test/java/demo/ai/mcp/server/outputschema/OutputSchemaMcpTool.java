package demo.ai.mcp.server.outputschema;

import demo.ai.mcp.server.outputschema.dataobject.CityInfo;
import demo.ai.mcp.server.outputschema.dataobject.Result;
import demo.ai.mcp.server.outputschema.dataobject.UserInfo;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Param;

import java.util.*;

/**
 * 自动生成 outputSchema 测试类：
 * 基础类型（String）
 * POJO 对象（UserInfo）
 * 泛型集合（List<CityInfo>）
 * Map<String, Object>
 * 泛型包装类（Result<UserInfo>）
 * Optional<T>
 * 数组（String[]）
 * 官方说明：https://modelcontextprotocol.io/specification/draft/server/tools#output-schema
 * 输出：data:{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"getTags","description":"获取标签列表","returnDirect":false,"inputSchema":{"type":"object","properties":{},"required":[]},"outputSchema":{"type":"object","properties":{"code":{"type":"string"},"message":{"type":"string"},"data":{"type":"array","items":{"type":"string"}}}}},{"name":"getWeather","description":"查询天气预报","returnDirect":false,"inputSchema":{"type":"object","properties":{"city":{"description":"城市","type":"string"}},"required":["city"]},"outputSchema":{"type":"string"}},{"name":"getWeather0","description":"查询天气预报","returnDirect":false,"inputSchema":{"type":"object","properties":{"city":{"description":"城市","type":"string"}},"required":["city"]}},{"name":"listCities","description":"获取所有城市信息","returnDirect":false,"inputSchema":{"type":"object","properties":{},"required":[]},"outputSchema":{"type":"object","properties":{"code":{"type":"string"},"message":{"type":"string"},"data":{"type":"array","items":{"type":"object","properties":{"name":{"description":"城市名","type":"string"},"code":{"description":"城市编码","type":"string"}},"required":["name","code"]}}}}},{"name":"getConfigs","description":"获取配置项","returnDirect":false,"inputSchema":{"type":"object","properties":{},"required":[]},"outputSchema":{"type":"object","properties":{"type":"object","properties":{},"required":[]}}},{"name":"getCurrentUser","description":"获取当前用户信息","returnDirect":false,"inputSchema":{"type":"object","properties":{},"required":[]},"outputSchema":{"type":"object","properties":{"code":{"type":"string"},"message":{"type":"string"},"data":{"type":"object","properties":{"name":{"description":"用户名","type":"string"},"age":{"description":"年龄","type":"integer"}},"required":["name","age"]}}}},{"name":"getSetting","description":"获取某个设置项","returnDirect":false,"inputSchema":{"type":"object","properties":{"key":{"description":"键","type":"string"}},"required":["key"]},"outputSchema":{"type":"string"}},{"name":"getUserInfo","description":"获取用户信息","returnDirect":false,"inputSchema":{"type":"object","properties":{"userId":{"description":"用户ID","type":"integer"}},"required":["userId"]},"outputSchema":{"type":"object","properties":{"name":{"description":"用户名","type":"string"},"age":{"description":"年龄","type":"integer"}},"required":["name","age"]}}]}}
 * @author ityangs@163.com 2025年05月20日15:54:04
 */
@McpServerEndpoint(sseEndpoint = "/mcp/outputSchema/sse")
public class OutputSchemaMcpTool {

    @ToolMapping(description = "查询天气预报")
    public String getWeather0(@Param(description = "城市") String city) {
        return "晴，14度";
    }

    @ToolMapping(description = "查询天气预报")
    public String getWeather(@Param(description = "城市") String city) {
        return "晴，14度";
    }

    @ToolMapping(description = "获取用户信息")
    public UserInfo getUserInfo(@Param(description = "用户ID") Long userId) {
        return new UserInfo(); // 假设 UserInfo 有 name、age 字段
    }

    @ToolMapping(description = "获取所有城市信息")
    public Result<List<CityInfo>> listCities() {
        return Result.ok(Arrays.asList(new CityInfo()));
    }

    @ToolMapping(description = "获取配置项")
    public Map<String, Object> getConfigs() {
        return Collections.singletonMap("env", "prod"); // 返回单一键值对的 Map
    }

    @ToolMapping(description = "获取当前用户信息")
    public Result<UserInfo> getCurrentUser() {
        return Result.ok(new UserInfo());
    }

    @ToolMapping(description = "获取某个设置项")
    public Optional<String> getSetting(@Param(description = "键") String key) {
        return Optional.of("值");
    }


    @ToolMapping(description = "获取标签列表")
    public Result<String[]> getTags() {
        return Result.ok(new String[]{"天气", "推荐"});
    }

}