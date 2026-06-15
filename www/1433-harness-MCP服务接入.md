---
title: "harness - MCP 服务接入"
---

马具可以接入 MCP（Model Context Protocol）服务，把第三方工具网关扩展为 Agent 可调用的工具。MCP 服务既可在构建期静态注册，也可在运行时动态增删。LSP 代码理解服务见 [《harness - LSP 服务接入》](/article/1438)，OpenAPI 业务接口接入见 [《harness - OpenAPI 业务接口接入》](/article/1437)。

### 1、开启 MCP 工具权限

MCP 属于私域工具，使用前需要给 Agent 授权：

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .sessionProvider(sessionProvider)
        .toolsAdd(ToolPermission.TOOL_MCP)        // 开启 mcp 工具权限
        .build();
```

也可以在运行时动态授权：

```java
engine.allowTool("mcp");
```

### 2、构建期静态注册

在构建 `HarnessEngine` 时注册 MCP 服务：

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .sessionProvider(sessionProvider)
        .toolsAdd(ToolPermission.TOOL_MCP)
        .mcpServerAdd("github", McpServerParameters.builder()
                .url("http://localhost:8080/mcp")
                .build())
        .build();
```

多个 MCP 服务可以继续追加：

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .sessionProvider(sessionProvider)
        .toolsAdd(ToolPermission.TOOL_MCP)
        .mcpServerAdd("github", githubMcpServer)
        .mcpServerAdd("slack", slackMcpServer)
        .build();
```

### 3、运行时动态管理

引擎启动后，仍可动态添加、刷新或移除 MCP 服务：

```java
engine.addMcpServer("slack", mcpServerParameters);  // 添加并连接
engine.getMcpServer("slack");                        // 获取客户端
engine.refreshMcpServer("slack");                    // 刷新（权限变更后）
engine.removeMcpServer("slack");                     // 移除并关闭连接
```

动态注册会同步到引擎配置中，并即时对后续会话生效。

### 4、说明

| 配置 / 权限 | 说明 |
|------|------|
| `ToolPermission.TOOL_MCP` | 开启 MCP 工具权限 |
| `mcp` | 工具权限名，运行时可通过 `allowTool("mcp")` 授权 |
| `mcpServers` | MCP 服务配置集合 |
| `mcpRetries` | MCP 调用重试次数，默认 `3` |

> MCP 工具由 `McpGatewayTalent` 统一网关管理。授权方式见 [《harness - 配置参考》](/article/1427)。
