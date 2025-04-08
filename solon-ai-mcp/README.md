```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-mcp</artifactId>
    <version>3.1.3-SNAPSHOT</version>
</dependency>
```


### 1、描述

solon-ai 的扩展插件，提供 mcp 协议支持（包括客户端和服务端）。更多可参考 [《教程 / Solon AI 开发》](/article/learn-solon-ai)

提示：需要 JDK 17 支持（modelcontextprotocol sdk 需要 java 17 支持）

### 2、服务端示例

引用插件并通过配置服务端“启用”后，所有的组件的 FunctionMapping 方法，会自动发布为 mcp 工具服务

```yaml
solon.ai.mcp.server:  # 对应的实体为 McpServerProperties（使用最新的 Solon Idea Plugin 会自动提示）
  enabled: true             # 默认为 false
  sseEndpoint: "/mcp/sse"   # 默认为 /mcp/sse
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

### 3、客户端示例

客户端可以使用原生的 modelcontextprotocol 接口，也可以使用 McpClientWrapper (包装简化过)

```java
//直接调用
public void case1(){
    McpClientWrapper mcpClient = new McpClientWrapper("http://localhost:8080", "/mcp/sse");

    String rst = mcpClient.callToolAsText("sum", Map.of("a", 1, "b", 2));
}

//绑定给模型使用
public void case2(){
    ChatModel chatModel = null;
    McpClientWrapper mcpClient = null;

    chatModel.prompt("杭州今天的天气情况如何？")
            .options(options -> {
                //转为函数集用于绑定（后续会不断完善）
                options.functionAdd(mcpClient.toFunctions());
            })
            .call();
}
```
