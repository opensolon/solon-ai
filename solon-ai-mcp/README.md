

### Server

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

```java
public void test(){
    String baseUri = "http://localhost:8080";

    McpClientSimp mcpClient = new McpClientSimp(baseUri, "/mcp/sse");

    String response = mcpClient.callToolAsText("sum", Map.of("a", 1, "b", 2));

    assert response != null;
}
```