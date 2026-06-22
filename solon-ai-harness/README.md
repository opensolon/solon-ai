# solon-ai-harness — AI 代理编排引擎

## 简介

`solon-ai-harness`（马具引擎）是 Solon AI 框架的核心编排模块，提供 **Agent 运行时容器、子代理调度、命令系统、安全策略**等一整套 AI 代理基础设施。它将 AI 代理的能力封装为可编程、可配置、可扩展的工程化组件，适用于构建智能编码助手、自动化工作流等多种场景。

---

## 项目结构

```
solon-ai-harness/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/org/noear/solon/ai/harness/
    │   │   ├── HarnessEngine.java          # 核心引擎入口
    │   │   ├── HarnessExtension.java        # 引擎扩展点接口
    │   │   ├── HarnessOptions.java          # 引擎配置选项（内部）
    │   │   ├── agent/                       # 代理定义与调度
    │   │   ├── channel/                     # IM 通道抽象
    │   │   ├── command/                     # 命令系统
    │   │   ├── hitl/                        # 人工介入安全审计
    │   │   └── permission/                  # 工具权限枚举
    │   └── resources/META-INF/solon/ai/harness/
    │       ├── bash.md                      # bash 子代理定义
    │       ├── explore.md                   # explore 子代理定义
    │       ├── general.md                   # general 子代理定义
    │       ├── git-summary.md               # git 提交摘要子代理
    │       └── plan.md                      # plan 子代理定义
    └── test/java/
        ├── demo/ai/harness/DemoApp.java     # 完整使用示例
        └── features/ai/harness/
            ├── AgentDefaultTest.java        # 内置子代理加载验证
            ├── AgentFactoryCommandSessionTest.java
            ├── AgentMetadataTest.java       # 代理元数据解析测试
            ├── CommandUtilTest.java         # 命令解析测试
            └── JsonSchemaTest.java          # JSON Schema 测试
```

---

## 架构总览

### 核心组件关系图

```
┌─────────────────────────────────────────────────────────────┐
│                     HarnessEngine                            │
│              (核心引擎 - Agent 运行时容器)                     │
│                                                             │
│  ┌──────────┐  ┌──────────┐  ┌───────────┐  ┌───────────┐  │
│  │Agent     │  │Agent     │  │TaskTalent │  │Generate   │  │
│  │Manager   │  │Factory   │  │(子代理调度)│  │Talent     │  │
│  │(代理定义)│  │(构建代理) │  │           │  │(动态创建) │  │
│  └──────────┘  └──────────┘  └───────────┘  └───────────┘  │
│                                                             │
│  ┌──────────┐  ┌──────────┐  ┌────────────┐  ┌──────────┐  │
│  │Command   │  │Channel   │  │HitlStrategy│  │Tool      │  │
│  │Registry  │  │Interface │  │(安全审计)  │  │Permission│  │
│  └──────────┘  └──────────┘  └────────────┘  └──────────┘  │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              ReActAgent (主代理/子代理)               │   │
│  │   ┌───────────┐ ┌──────────┐ ┌──────────────────┐   │   │
│  │   │System     │ │Tool      │ │ChatModel +       │   │   │
│  │   │Prompt     │ │Set       │ │Interceptors      │   │   │
│  │   └───────────┘ └──────────┘ └──────────────────┘   │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 模块职责

| 模块 | 包路径 | 职责 |
|------|--------|------|
| **引擎核心** | `HarnessEngine` | Agent 运行时容器，管理配置、生命周期、Talent 和能力注入 |
| **扩展点** | `HarnessExtension` | SPI 接口，允许外部介入 Agent 构建过程 |
| **配置** | `HarnessOptions` | 引擎运行时配置（路径、开关、重试策略等） |
| **代理定义** | `agent.AgentDefinition` | 代理的元数据模型（名称、描述、工具权限、模型等） |
| **代理工厂** | `agent.AgentFactory` | 根据定义构建 `ReActAgent`，绑定工具集、拦截器等 |
| **代理管理** | `agent.AgentManager` | 管理和发现内置及挂载的代理定义 |
| **子代理调度** | `agent.TaskTalent` | 将任务委派给专项子代理，支持 `task`（串行）和 `multitask`（并行） |
| **动态创建** | `agent.GenerateTalent` | 运行时动态创建新的子代理定义 |
| **命令系统** | `command.*` | 通用命令框架（CLI/Web 共用），支持 Markdown 自定义命令 |
| **IM 通道** | `channel.Channel` | 统一 IM 通道（微信、飞书、钉钉等）的接口抽象 |
| **安全审计** | `hitl.HitlStrategy` | 人工介入策略，对 bash 等高危操作进行安全检查 |
| **工具权限** | `permission.ToolPermission` | 工具权限枚举（read, write, bash 等） |

---

## 核心模型

### AgentDefinition（代理定义）

代理是 Harness 的核心模型。一个代理通过 **Markdown + YAML 前置元数据** 来定义：

```markdown
---
name: explore                          # 代理名称（唯一标识）
description: 全域信息探索专家           # 代理描述
tools: [list, read, grep, glob]        # 允许的工具
model: glm-4-flash                     # 指定模型（可选）
disallowedTools: [Bash, Write]         # 禁用工具（可选）
skills: [commit, review]              # 绑定的技能（可选）
mcpServers: [slack, github]           # 绑定的 MCP 服务（可选）
memory: user                          # 记忆级别（user/project/local）
hidden: true                          # 是否隐藏（可选）
---

## 系统提示词正文
你是一个探索专家...
```

### 内置子代理

| 代理 | 名称 | 工具集 | 描述 |
|------|------|--------|------|
| **general** | `general` | `*`（全部公有工具） | 通用全能专家，处理所有任务 |
| **bash** | `bash` | `list, read, bash` | 命令执行专家（无文件写权限） |
| **explore** | `explore` | `list, read, grep, glob, skill, webfetch, websearch, codesearch` | 全域信息探索专家 |
| **plan** | `plan` | `list, read, grep, glob, skill, webfetch, websearch, codesearch` | 规划与计划专家 |
| **git-summary** | `git-summary` | 无工具 | Git 提交摘要生成专家（隐藏代理） |

### 工具权限级别

通过 `ToolPermission` 枚举定义了三级权限快捷方式：

| 权限 | 标识 | 包含工具 |
|------|------|----------|
| 全部完整 | `**` | read, write, edit, glob, grep, ls, bash, bash_start/stop, skill, todo, code, codesearch, websearch, webfetch, task, generate, mcp, openapi, hitl, lsp, memory |
| 全部公有 | `*` | read, write, edit, glob, grep, ls, bash(及扩展), skill, todo, code, codesearch, websearch, webfetch, task, lsp |
| 核心操作 | `pi` | read, write, edit, bash(及扩展) |

---

## 使用说明

### 1. 添加依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-harness</artifactId>
    <version>${solon-ai.version}</version>
</dependency>
```

### 2. 初始化引擎

```java
// 构建引擎实例
HarnessEngine engine = HarnessEngine.of("/path/to/workspace", ".tmp")
    .systemPrompt("你是一名 AI 编程助手")
    .sessionProvider(InMemoryAgentSession::of)
    .toolsAdd(ToolPermission.TOOL_ALL_FULL)   // 授权全部工具
    .modelAdd(new ChatConfig().then(slf -> {
        slf.setApiUrl("https://api.deepseek.com");
        slf.setApiKey("sk-xxx");
        slf.setModel("deepseek-v4-flash");
    }))
    .build();
```

### 3. 使用主代理执行任务

```java
AgentSession session = engine.getSession("default");

// 同步调用
engine.prompt("请分析当前项目的代码结构")
    .session(session)
    .options(o -> {
        o.toolContextPut(HarnessEngine.ATTR_CWD, "/my/project/path");
    })
    .call();
```

### 4. 使用子代理

```java
// 直接通过 AgentManager 获取内置子代理
AgentDefinition bashDef = engine.getAgentManager().getAgent("bash");

// 构建子代理
ReActAgent bashAgent = bashDef.builder(engine).build();

// 执行
bashAgent.prompt("列出当前目录下所有 Java 文件")
    .session(session)
    .call();
```

或通过 AgentFactory 手动定义：

```java
AgentDefinition definition = new AgentDefinition();
definition.setSystemPrompt("你是前端专家");
definition.getMetadata().addTools(ToolPermission.TOOL_READ, ToolPermission.TOOL_GREP);

ReActAgent subagent = engine.createSubagent(definition).build();
subagent.prompt("分析一下 src/main.js").session(session).call();
```

### 5. 动态创建子代理（运行时）

通过 `generate` 工具可以在运行时动态创建新的子代理定义：

```java
// 这通常在模型对话中自动触发，也可编程调用
engine.getGenerateTalent().generate(
    "code_reviewer",           // name
    "代码审查专家",             // description
    "你是一位严格的代码审查专家...",  // systemPrompt
    Arrays.asList("read", "grep", "code"),  // tools
    null,                      // skills
    false                      // saveToFile
);
```

### 6. 多任务并行调度

```java
// 使用 multitask 并行执行不冲突的子任务
// 通常在 LLM 对话中通过工具调用自动触发
```

### 7. 配置项参考

| 配置 | 方法 | 默认值 | 说明 |
|------|------|--------|------|
| 工作区 | `of(workspace, harnessHome)` | `"work"` | 工作目录路径 |
| 引擎根目录 | `of(workspace, harnessHome)` | `.solon/` | 引擎配置/数据存放目录 |
| 系统提示词 | `systemPrompt(...)` | `null` | 主代理系统提示词 |
| 最大轮次 | `maxTurns(20)` | `20` | Agent 最大推理-行动轮次 |
| 自动反思 | `autoRethink(true)` | `true` | 失败后自动重试 |
| 会话窗口 | `sessionWindowSize(8)` | `8` | 保留最近 N 轮消息 |
| 压缩阈值 | `compressionThreshold(30, 30000)` | `30 条/3 万 token` | 触发历史压缩的阈值 |
| 记忆 | `memoryEnabled(true)` | `true` | 是否启用长期记忆 |
| 沙箱 | `sandboxEnabled(true)` | `true` | 是否启用沙箱模式 |
| 人工介入 | `hitlEnabled(false)` | `false` | 是否启用 HITL 拦截 |
| 子代理 | `subagentEnabled(true)` | `true` | 是否启用子代理功能 |
| API 重试 | `apiRetries(3)` | `3` | API 调用重试次数 |
| MCP 重试 | `mcpRetries(3)` | `3` | MCP 服务重试次数 |
| 模型重试 | `modelRetries(3)` | `3` | 大模型调用重试次数 |

### 8. 运行时动态配置

```java
// 启用/禁用沙箱
engine.setSandboxEnabled(false);

// 切换默认模型
engine.setDefaultModel("deepseek-v4-flash");

// 增加/移除模型
engine.addModel(new ChatConfig(...));
engine.removeModel("gpt-4");

// 动态授权/撤销工具
engine.allowTool("bash");
engine.disallowTool("write");

// 添加 MCP 服务
engine.addMcpServer("my-service", mcpServerParams);

// 添加 API 源
engine.addApiServer(apiSource);

// 添加挂载点
engine.addMount(mountDir);

// 添加扩展
engine.addExtension((name, builder) -> {
    // 自定义配置逻辑
});
```

### 9. 自定义扩展

实现 `HarnessExtension` 接口，可在 Agent 构建过程中注入自定义逻辑：

```java
engine.extensionAdd((agentName, agentBuilder) -> {
    if ("main".equals(agentName)) {
        agentBuilder.defaultInterceptorAdd(myCustomInterceptor);
    }
});
```

### 10. 自定义命令

在 `.soloncode/commands/` 目录下放置 Markdown 文件即可注册自定义命令：

```markdown
---
description: Create a git commit
argument-hint: [message]
---

执行 git add . 和 git commit -m "$ARGUMENTS"
```

支持 `$ARGUMENTS`、`$1`、`$2` 等变量替换，支持子目录命名空间（如 `deploy/staging.md` → 命令名 `deploy:staging`）。

---

## 安全机制

`HitlStrategy` 提供了多层安全审计策略：

1. **注入防御** — 拦截反引号、`$()`、设备重定向
2. **系统黑名单** — 禁止 `sudo`、`kill`、`reboot` 等系统级命令
3. **路径边界检查** — 拦截 `../` 路径回溯和敏感目录访问
4. **环境变更分级** — `npm install`、`pip install` 等需人工确认
5. **网络行为零信任** — `curl`、`wget` 等网络工具需审核
6. **管道安全链** — 只允许 grep/head/tail 等安全工具的管道组合
7. **破坏性操作拦截** — 递归删除、文件移动等操作需确认

---

## 运行时目录结构

```
{solonHome}/
├── sessions/          # 会话存储目录
├── skills/            # 技能目录
├── agents/            # 代理定义目录
├── commands/          # Markdown 自定义命令目录
├── memory/            # 长期记忆存储目录
├── download/          # 下载目录
└── channels/          # IM 通道配置目录
```

---

## 依赖关系

`solon-ai-harness` 整合了以下 AI Talent 模块：

| 依赖 | 说明 |
|------|------|
| `solon-lib` | Solon 核心框架 |
| `solon-ai-agent` | AI Agent 核心（ReAct 模式） |
| `solon-ai-talent-cli` | CLI 终端交互能力 |
| `solon-ai-talent-code` | 代码分析与工程规约 |
| `solon-ai-talent-web` | 网络搜索与抓取能力 |
| `solon-ai-talent-lsp` | LSP 语言服务器协议支持 |
| `solon-ai-talent-memory` | 长期记忆与心智模型 |
| `solon-ai-talent-gateway` | MCP/OpenAPI 网关 |
| `solon-serialization-snack4` | JSON 序列化 |
