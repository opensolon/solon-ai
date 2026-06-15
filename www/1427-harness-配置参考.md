---
title: "harness - 配置参考"
---



HarnessEngine 的配置示例：

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
    .systemPrompt("你是一个 AI 助手")
    .sessionWindowSize(8)
    .compressionThreshold(30, 30_000)
    .toolsAdd(ToolPermission.TOOL_ALL_FULL) // 设定工具权限
    .build();
```

可选配置：

### 1、核心配置项

| 配置项 | 类型 | 默认值 | 描述                              |
|-----|---|--------|---------------------------------|
| `workspace` | `String` | work | 工作区                             |
| `harnessHome` | `String` | .solon/ | 马具主目录（例：`.soloncode`）           |
| `systemPrompt` | `String` | / | 系统提示词                           |
| `tools`         | `Set<String>` | / | 工具权限配置（`**` = 所有工具；`*` = 仅公域工具） |
| `disallowedTools`         | `Set<String>` | / | 禁用工具配置（使用具体工具名）                 |
| `defaultModel`  | `String` | / | 默认模型名（不指定则取 models 中第一个）        |
| `models`      | `Map<String, ChatConfig>` | / | 大模型配置                           | 
| `maxTurns`   | int | `20` | 根代理最大循环步数                       |
| `autoRethink`  | bool | `true` | 最大步数自动续航（由 LLM 反思控制）            |
| `sessionWindowSize`  | int | `8` | 会话历史窗口大小（新指令时使用几条历史消息）          |
| `compressionMaxMessages`  | int | `30` | 触发上下文压缩的消息条数阈值                  |
| `compressionMaxTokens`  | int | `30000` | 触发上下文压缩的内容长度阈值                  |
| `compressionModel`  | `String` | / | 压缩用大模型（不指定则使用主模型）               |


models 模型配置参考：[《模型配置与请求选项》](/article/1087)

### 2、安全与行为配置

| 配置项 | 类型 | 默认值 | 描述 |
|--------|---|--------|------|
| `sandboxEnabled` | bool | `true` | 沙盒模式，启用时禁止访问绝对路径（只能访问工作区与用户主目录） |
| `sandboxAllowUserHome` | bool | `true` | 沙盒模式下允许访问用户主目录 |
| `sandboxSystemRestrict` | bool | `true` | 沙盒系统级限制 |
| `hitlEnabled` | bool | `false` | 是否启用人工审核（危险操作需人工确认） |
| `subagentEnabled` | bool | `true` | 是否启用子代理模式（自动委派任务给专家代理） |
| `bashAsyncEnabled` | bool | `false` | 是否启用 Bash 异步执行 |
| `memoryEnabled` | bool | `true` | 是否启用心智记忆 |
| `userAgent` | `String` | / | 用户代理标识（会自动传播给所有模型） |
| `apiRetries` | int | `3` | API 重试次数 |
| `mcpRetries` | int | `3` | MCP 重试次数 |
| `modelRetries` | int | `3` | 模型重试次数 |


### 3、扩展配置


| 配置项 | 类型 | 默认值 | 描述 |
|--------|------|------|------|
| `mounts` | `MountDir` | / | 挂载配置（alias 须以 `@` 开头） |
| `mcpServers` | `Map<String, McpServerParameters>` | / | Mcp 服务配置 |
| `apiServers` | `Map<String, ApiSource>` | / | Web Api 服务配置 |
| `lspServers` | `Map<String, LspServerParameters>` | / | LSP 服务配置 |
| `extensions` | `List<HarnessExtension>` | / | 扩展接口配置 |


### 附录、工具权限配置选择（tools）

| 工具名 | 类型 | 描述 |
|--------|------|------|
| `**` | - | 所有公域 + 私域工具 |
| `*` | - | 仅所有公域工具 |
| `pi` | 聚合 | 微形命令行工具（包括工具：read, write, edit, bash） |
| | | |
| `hitl` | 私域 | 人工介入审核 |
| `generate` | 私域 | 动态生成子代理 |
| `restapi` | 私域 | Web 服务 API 接入 |
| `mcp` | 私域 | MCP 服务接入 |
| `lsp` | 私域 | LSP 代码理解服务 |
| | | |
| `code` | 公域 | 编码指引（自动分析项目类型、编译指令等） |
| `codesearch` | 公域 | 网络代码搜索 |
| `websearch` | 公域 | 网络搜索 |
| `webfetch` | 公域 | 网页内容抓取 |
| `todo` | 公域 | 任务清单管理 |
| `skill` | 公域 | 专家技能调用 |
| `task` | 公域 | 子代理任务委派 |
| `bash` | 公域 | Shell 命令执行 |
| `ls` | 公域 | 列出目录内容 |
| `grep` | 公域 | 递归内容搜索 |
| `glob` | 公域 | 通配符文件搜索 |
| `edit` | 公域 | 文件编辑（精准文本替换，支持多处原子编辑；含 write、read） |
| `read` | 公域 | 读取文件内容 |
| `write` | 公域 | 写入文件内容 |


### 附录、运行时动态修改

`HarnessEngine` 构建完成后，仍可在运行时动态调整工具权限与模型（变更后会自动重建主代理，使其立即生效）。

* 动态调整工具权限

```java
engine.allowTool("websearch");           // 动态授权一个工具
engine.disallowTool("bash");             // 动态禁用一个工具

engine.allowToolReset(Arrays.asList("read", "write", "edit")); // 重置允许工具集
engine.disallowToolReset(Arrays.asList("bash"));              // 重置禁用工具集
```

* 动态管理模型

```java
engine.addModel(new ChatConfig().then(c -> {
    c.setApiUrl("https://api.deepseek.com");
    c.setApiKey("sk-***");
    c.setModel("deepseek-v4-flash");
}));                                     // 添加模型

engine.setDefaultModel("deepseek-v4-flash"); // 设定默认模型（影响主代理时自动刷新）
engine.removeModel("deepseek-v4-flash");     // 移除模型
```

* 其它运行时配置

```java
engine.setMaxTurns(30);
engine.setSessionWindowSize(8);
engine.setCompressionThreshold(30, 30_000);
engine.setSandboxEnabled(true);
engine.setHitlEnabled(false);
engine.setSubagentEnabled(true);
```
