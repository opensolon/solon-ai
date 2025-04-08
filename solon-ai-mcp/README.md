提示：需要 JDK 17 支持（modelcontextprotocol sdk 需要 java 17 支持）

### Server

引用插件后，所有的组件的 FunctionMapping 方法，会自动发布为 mcp 服务内容（可以配置不启用，则不发布）

```yaml
solon.ai.mcp.server:
  enabled: true //默认为 true
  sseEndpoint: "/mcp/sse" # 默认为 /mcp/sse

```

```java
@Component
public class McpServerTool {
    //
    // 建议开启编译参数：-parameters （否则，要再配置参数的 name）
    //
    @FunctionMapping(description = "整数求和函数")
    public int sum(@FunctionParam(description = "参数a") int a,
                   @FunctionParam(description = "参数b") int b) {
        return a + b;
    }
}

public class McpServerApp {
    public static void main(String[] args) {
        Solon.start(McpServerApp.class, args);
    }
}
```

### Client

客户端可以使用原生的 modelcontextprotocol 接口，也可以使用 McpClientSimp (简化过)

```java
public void test(){
    String baseUri = "http://localhost:8080";

    McpClientSimp mcpClient = new McpClientSimp(baseUri, "/mcp/sse");

    String response = mcpClient.callToolAsText("sum", Map.of("a", 1, "b", 2));

    assert response != null;
}
```