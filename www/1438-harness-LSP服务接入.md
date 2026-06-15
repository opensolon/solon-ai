---
title: "harness - LSP 服务接入"
---

马具可以接入 LSP（Language Server Protocol）服务，让 Agent 具备更深入的代码理解能力，例如定义跳转、引用查找、诊断等。MCP 第三方工具网关接入见 [《harness - MCP 服务接入》](/article/1433)，OpenAPI 业务接口接入见 [《harness - OpenAPI 业务接口接入》](/article/1437)。

### 1、开启 LSP 工具权限

LSP 属于私域工具，使用前需要给 Agent 授权：

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .sessionProvider(sessionProvider)
        .toolsAdd(ToolPermission.TOOL_LSP)        // 开启 lsp 工具权限
        .build();
```

也可以在运行时动态授权：

```java
engine.allowTool("lsp");
```

### 2、构建期静态注册

在构建 `HarnessEngine` 时注册 LSP 服务：

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .sessionProvider(sessionProvider)
        .toolsAdd(ToolPermission.TOOL_LSP)
        .lspServerAdd("java", lspServerParameters)
        .build();
```

如果项目需要多种语言服务，可以按名称分别注册：

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .sessionProvider(sessionProvider)
        .toolsAdd(ToolPermission.TOOL_LSP)
        .lspServerAdd("java", javaLspServer)
        .lspServerAdd("typescript", tsLspServer)
        .build();
```

### 3、运行时动态管理

引擎启动后，仍可动态添加或移除 LSP 服务：

```java
engine.addLspServer("java", lspServerParameters);
engine.removeLspServer("java");
```

LSP 服务增删后，会触发主代理重建以刷新工具集，并对后续会话生效。

### 4、说明

| 配置 / 权限 | 说明 |
|------|------|
| `ToolPermission.TOOL_LSP` | 开启 LSP 工具权限 |
| `lsp` | 工具权限名，运行时可通过 `allowTool("lsp")` 授权 |
| `lspServers` | LSP 服务配置集合 |

> LSP 能力更偏向“代码理解”，适合和 `read`、`grep`、`glob`、`code` 等工具一起使用。授权方式见 [《harness - 配置参考》](/article/1427)。
