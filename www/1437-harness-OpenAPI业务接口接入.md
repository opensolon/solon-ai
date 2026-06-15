---
title: "harness - OpenAPI 业务接口接入"
---

马具可以把现有的 HTTP 业务接口（OpenAPI 文档）接入为 Agent 可调用的工具，让 Agent 直接调用你的业务系统。接入以 `docUrl`（OpenAPI 文档地址）为唯一标识，支持 OpenAPI v2 / v3 文档。

### 1、构建期静态注册

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .sessionProvider(sessionProvider)
        .toolsAdd(ToolPermission.TOOL_RESTAPI)        // 开启 restapi 工具权限
        .apiServerAdd("orders", new ApiSource().then(s -> {
            s.setDocUrl("https://api.example.com/v3/api-docs");  // OpenAPI 文档地址（唯一标识）
            s.setApiBaseUrl("https://api.example.com");          // 接口基地址
        }))
        .build();
```

`ApiSource` 也可用标准 setter 逐项设置：

```java
ApiSource source = new ApiSource();
source.setDocUrl("https://api.example.com/v3/api-docs");
source.setApiBaseUrl("https://api.example.com");
source.addHeaderVar("Authorization", "Bearer ${token}");   // 透传请求头
source.addAllowedTool("getOrder");                          // 只暴露指定接口
```

### 2、ApiSource 字段说明

| 字段 | 默认值 | 描述 |
|------|--------|------|
| `docUrl` | / | OpenAPI 文档地址，作为该 API 源的唯一标识 |
| `apiBaseUrl` | / | 接口基地址（覆盖文档中的 server 地址） |
| `headers` | / | 自定义请求头（可用 `addHeaderVar` 追加） |
| `allowedTools` | / | 白名单，仅暴露列出的接口为工具 |
| `disallowedTools` | / | 黑名单，排除列出的接口 |
| `timeout` | / | 请求超时（`Duration`） |
| `authenticator` | / | 鉴权器（`ApiAuthenticator`） |
| `enabled` | `true` | 是否启用 |

> 接口工具的暴露由 `allowedTools` / `disallowedTools` 共同裁剪：先取白名单（为空则全取），再剔除黑名单。

### 3、运行时动态管理

```java
engine.addApiServer(apiSource);          // 添加（以 docUrl 为标识）
engine.getApiServer(docUrl);             // 获取 ApiSourceClient
engine.refreshApiServer(docUrl);         // 刷新（权限/文档变更后）
engine.removeApiServer(docUrl);          // 移除
engine.getApiServers();                  // 全部 API 源
```

`ApiSourceClient` 可查看该源解析出的接口工具：

```java
ApiSourceClient client = engine.getApiServer(docUrl);
client.getTools();             // 全部接口工具
client.getToolsActivated();    // 经白/黑名单裁剪后实际生效的工具
```

### 4、说明

* 工具权限：OpenAPI 接入需授予 `TOOL_RESTAPI`（即 `restapi`）。授权方式见 [《harness - 配置参考》](/article/1427)。
* OpenAPI 工具由 `OpenApiGatewayTalent` 统一网关管理，重试次数由 `apiRetries`（默认 3）控制。
* 动态注册都会同步到引擎配置中，并即时对后续会话生效。
* MCP 接入见 [《harness - MCP 服务接入》](/article/1433)，LSP 接入见 [《harness - LSP 服务接入》](/article/1438)。
