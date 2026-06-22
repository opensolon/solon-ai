# solon-ai-harness — AI 代理编排引擎

## 简介

`solon-ai-harness`（马具引擎）是 Solon AI 框架的核心编排模块，提供 **Agent 运行时容器、子代理调度、命令系统、安全策略**等一整套 AI 代理基础设施。它将 AI 代理的能力封装为可编程、可配置、可扩展的工程化组件，适用于构建智能编码助手、自动化工作流等多种场景。

---

## 项目结构

`solon-ai-harness` 的物理代码与依赖包共同构成了完整的代理编排能力。下图从**模块自身源码**和**集成依赖**两个维度展示全貌：

```
solon-ai-harness/                                  # AI 代理编排引擎
│
├── pom.xml                                        # Maven 坐标: org.noear:solon-ai-harness
│
├── # ═══════════ 模块自身源码 ═══════════
│
├── src/main/java/org/noear/solon/ai/harness/
│   ├── HarnessEngine.java                         # 核心引擎入口（Builder 模式，主代理运行时容器）
│   ├── HarnessExtension.java                      # 引擎扩展点 SPI 接口
│   ├── HarnessOptions.java                        # 引擎运行时配置（路径、重试、窗口策略）
│   │
│   ├── agent/                                     # 代理定义、工厂、调度
│   │   ├── AgentDefinition.java                   # 代理元数据模型（名称/描述/工具/模型/MCP）
│   │   ├── AgentFactory.java                      # 根据 AgentDefinition 构建 ReActAgent
│   │   ├── AgentManager.java                      # 发现、注册、管理内置和挂载的代理定义
│   │   ├── TaskTalent.java                        # 子代理调度器（task 串行 / multitask 并行）
│   │   └── GenerateTalent.java                    # 运行时动态创建子代理定义
│   │
│   ├── command/                                   # 通用命令框架
│   │   ├── Command.java                           # 命令接口
│   │   ├── CommandContext.java                    # 命令执行上下文
│   │   ├── CommandRegistry.java                   # 命令注册中心
│   │   ├── MarkdownCommand.java                   # Markdown 命令模型
│   │   └── MarkdownCommandLoader.java             # 从 Markdown 文件加载自定义命令
│   │
│   ├── channel/
│   │   └── Channel.java                           # IM 通道接口抽象（微信/飞书/钉钉等）
│   │
│   ├── hitl/
│   │   └── HitlStrategy.java                      # 人工介入安全审计策略
│   │
│   └── permission/
│       └── ToolPermission.java                    # 工具权限枚举（三级快捷权限）
│
├── src/main/resources/META-INF/solon/ai/harness/  # 内置子代理定义（Markdown + YAML）
│   ├── general.md                                 # 通用全能专家  工具: *
│   ├── bash.md                                    # 命令执行专家  工具: list, read, bash
│   ├── explore.md                                 # 信息探索专家  工具: list, read, grep, glob, ...
│   ├── plan.md                                    # 规划与计划专家 工具: list, read, grep, glob, ...
│   └── git-summary.md                             # Git 提交摘要   隐藏代理
│
├── src/test/java/
│   ├── demo/ai/harness/DemoApp.java               # 完整使用示例
│   └── features/ai/harness/
│       ├── AgentDefaultTest.java                  # 内置子代理加载验证
│       ├── AgentFactoryCommandSessionTest.java     # 代理工厂 & 命令会话测试
│       ├── AgentMetadataTest.java                 # 代理元数据解析测试
│       ├── CommandUtilTest.java                   # 命令解析工具测试
│       └── JsonSchemaTest.java                    # JSON Schema 生成与验证
│
├── # ═══════════ 依赖包（整合能力） ═══════════
│
├── [solon-lib]                                   # Solon 核心 IoC/AOP/Web 框架
│   └── org.noear:solon-lib                        #   提供依赖注入、配置管理、生命周期
│
├── [solon-ai-agent]                              # ⭐ AI Agent 核心引擎
│   └── org.noear:solon-ai-agent                  #   提供 ReActAgent（思考-行动循环）、
│                                                  #   ChatModel、ToolSet、Interceptor 等
│
├── [solon-ai-talent-cli]                         # CLI 终端交互能力
│   └── org.noear:solon-ai-talent-cli             #   提供 TerminalTalent（bash/read/write/edit/glob/grep/ls）、
│                                                  #   BashInterceptor 沙箱保护、挂载点路径解析
│
├── [solon-ai-talent-code]                        # 代码工程规约
│   └── org.noear:solon-ai-talent-code            #   提供 CodeTalent（技术栈自动识别、
│                                                  #   .soloncode/CODE.md 规约生成与验证）
│
├── [solon-ai-talent-web]                         # 网络搜索与抓取
│   └── org.noear:solon-ai-talent-web             #   提供 WebsearchTalent、WebfetchTalent（
│                                                  #   互联网搜索 & 网页内容抓取）
│
├── [solon-ai-talent-lsp]                         # LSP 语言服务器协议
│   └── org.noear:solon-ai-talent-lsp             #   提供 LspTalent（深度代码理解、
│                                                  #   符号解析、引用查找）
│
├── [solon-ai-talent-memory]                      # 长期记忆与心智模型
│   └── org.noear:solon-ai-talent-memory          #   提供 MemoryTalent（用户偏好提取、
│                                                  #   知识检索、认知演进与冲突消解）
│
├── [solon-ai-talent-gateway]                     # MCP / OpenAPI 网关
│   └── org.noear:solon-ai-talent-gateway         #   提供 McpGatewayTalent（MCP 工具发现与调用、
│                                                  #   OpenAPI 导入、第三方工具集成）
│
└── [solon-serialization-snack4]                  # JSON 序列化引擎
    └── org.noear:solon-serialization-snack4      #   用于配置序列化、指令参数编解码
```

---

## 架构总览

### 核心组件关系图

下图完整展示了 `HarnessEngine` 构造函数和 `AgentFactory.create()` 中引用的所有组件，按**归属 Maven 依赖包**分区展示。

```
┌══════════════════════════════════════════════════════════════════════════════════════════┐
║                                    HarnessEngine                                        ║
║                            AI 代理编排引擎（核心容器）                                    ║
║                                                                                         ║
║  ┌──── solon-ai-harness (自有源码) ─────────────────────────────────────────────────┐    ║
║  │                                                                                   │    ║
║  │  HarnessOptions (配置模型)        ───→ AgentFactory (构建工厂, 静态方法)          │    ║
║  │     workspace / maxTurns / sandbox     │ 根据 AgentDefinition 中的工具名列表，     │    ║
║  │     sessionProvider / memoryProvider   │ 从 engine 各 getter 获取 Talent 实例，     │    ║
║  │     tools(白名单) / disallowedTools    │ 通过 builder.defaultTalentAdd() 注册       │    ║
║  │     models(多模型池) / extensions      │                                           │    ║
║  │     mcpServers / apiServers / lsp...   │  AgentDefinition (代理元数据模型)           │    ║
║  └──────────────────────────────────────┘ │   name/tools/disallowedTools/model/memory    │    ║
║                                           │   skills/mcpServers/hidden/primary          │    ║
║  ┌── 自有核心组件 ─────────────────────┐ └──────────┬─────────────────────────────────┘    ║
║  │                                     │            │ 构建结果                            ║
║  │  AgentManager  (代理定义发现/注册/管理)│           ▼                                   ║
║  │  CommandRegistry(命令注册中心)       │  ┌─────────────────────────────────────────┐  ║
║  │  Command / CommandContext /          │  │        ReActAgent (主代理/子代理)         │  ║
║  │    MarkdownCommand / Loader          │  │  思考-行动循环 + ToolSet + Interceptors  │  ║
║  │  Channel (IM 通道接口抽象)           │  │   + SystemPrompt + ChatModel             │  ║
║  │  HitlStrategy (bash 安全审计策略)    │  └─────────────────────────────────────────┘  ║
║  │  ToolPermission (工具权限枚举)       │            │                                 ║
║  │  HarnessExtension (扩展 SPI 接口)    │            │ 持有引用                         ║
║  │                                     │            ▼                                 ║
║  │  ┌── 子代理管理 ─────────────────┐   │  ┌──────────────────────────────────────┐   ║
║  │  │  TaskTalent (串行/并行调度)     │   │  │     Interceptors (拦截器链)          │   ║
║  │  │  GenerateTalent (动态创建定义)   │   │  │  HITLInterceptor  (人工介入,onTool) │   ║
║  │  └────────────────────────────────-┘   │  │  ContextCompressionInterceptor      │   ║
║  │                                         │  │  (上下文压缩,30条/3万token触发)     │   ║
║  │  ◄── 注入到 ReActAgent ────────────────┤  └──────────────────────────────────────┘   ║
║  └─────────────────────────────────────────┘                                           ║
╚══════════════════════════════════════════════════════════════════════════════════════════╝
           │ ① toolAddDo()                                                               
           │ 从 engine 各 getter 获取实例并注入                                           
           ▼                                                                             
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                          AgentFactory 注入的外部依赖组件                                  │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│  ┌─ solon-ai-talent-cli ──────────────────────────────────────────────────────────┐     │
│  │                                                                                 │     │
│  │  TerminalTalent          ◄── TerminalTalentProxy (权限白名单代理)                  │     │
│  │  (bash/read/write/edit      │  按工具名白名单选择性暴露子集                       │     │
│  │   glob/grep/ls/沙箱)        │                                                    │     │
│  │                          SkillTalent (技能发现与加载,skillread/skillrefresh)       │     │
│  │                          TodoTalent (任务清单管理,todoread/todowrite)             │     │
│  │                          ClockTalent (系统时间工具,get_current_time)              │     │
│  │                          MountManager (挂载点扫描与刷新)                          │     │
│  │                            ├── MountDir / SkillDir / AgentMd                     │     │
│  │                                                                                  │     │
│  └──────────────────────────────────────────────────────────────────────────────────┘     │
│                                                                                         │
│  ┌─ solon-ai-agent ───────────────────────────────────────────────────────────────┐     │
│  │                                                                                 │     │
│  │  ChatModel / ChatConfig (大模型调用,含重试和缓存)                               │     │
│  │  AgentSessionProvider / AgentSession (会话生命周期管理)                          │     │
│  │  CacheControl (缓存控制)                                                        │     │
│  │  Prompt (提示词构建)                                                            │     │
│  │  CompressionStrategy 体系:                                                      │     │
│  │    CompositeCompressionStrategy (组合策略)                                      │     │
│  │      ├── KeyInfoExtractionStrategy (去水提取干货)                               │     │
│  │      └── HierarchicalCompressionStrategy (滚动摘要)                             │     │
│  │                                                                                 │     │
│  └──────────────────────────────────────────────────────────────────────────────────┘     │
│                                                                                         │
│  ┌─ solon-ai-talent-gateway ──────────────────────────────────────────────────────┐     │
│  │                                                                                 │     │
│  │  McpGatewayTalent  (MCP 工具网关)                                               │     │
│  │    ├── McpClientProvider / McpServerParameters (MCP 服务连接管理)               │     │
│  │  OpenApiGatewayTalent (OpenAPI 导入网关)                                         │     │
│  │    └── ApiSource / ApiSourceClient (API 源客户端)                                │     │
│  │                                                                                 │     │
│  └──────────────────────────────────────────────────────────────────────────────────┘     │
│                                                                                         │
│  ┌─ solon-ai-talent-memory ───────────────────────────────────────────────────────┐     │
│  │                                                                                 │     │
│  │  MemoryTalent (用户心智模型)   ◄── MemorySolutionProvider (持久化存储接口)       │     │
│  │    memory_extract / memory_search / memory_recall / memory_consolidate / prune  │     │
│  │                                                                                 │     │
│  └──────────────────────────────────────────────────────────────────────────────────┘     │
│                                                                                         │
│  ┌─ solon-ai-talent-web ──────────┐    ┌─ solon-ai-talent-lsp ────────────────────┐     │
│  │                                 │    │                                           │     │
│  │  WebsearchTalent (互联网搜索)   │    │  LspManager (LSP 服务生命周期管理)         │     │
│  │  WebfetchTalent  (网页内容抓取) │    │    ├── LspServerParameters                │     │
│  │  CodeSearchTalent(代码搜索)     │    │  LspTalent (符号解析/引用查找)             │     │
│  │                                 │    │                                           │     │
│  └─────────────────────────────────┘    └───────────────────────────────────────────┘     │
│                                                                                         │
│  ┌─ solon-ai-talent-code ─────────────────────────────────────────────────────────┐     │
│  │                                                                                 │     │
│  │  CodeTalent (技术栈识别 / .soloncode/CODE.md 规约生成验证)                       │     │
│  │                                                                                 │     │
│  └──────────────────────────────────────────────────────────────────────────────────┘     │
│                                                                                         │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

**组件流向说明（对照 `HarnessEngine` 构造函数 & `AgentFactory.toolAddDo()`）：**

| 编号 | 流向 | 源码位置 |
|------|------|----------|
| ① | `AgentFactory.toolAddDo()` 遍历 `AgentDefinition.getMetadata().getTools()` 中的工具名；每个工具名映射到 `HarnessEngine` 的某个 getter（如 `read` → `getTerminalTalent()`），获取实例后通过 `builder.defaultTalentAdd()` 注册到 `ReActAgent.Builder` | `AgentFactory.java:120-246` |
| ② | `TerminalTalentProxy` 包装 `TerminalTalent`，按工具名白名单选择性暴露子集（如只暴露 `read` 而不暴露 `bash`），实现细粒度权限控制 | `AgentFactory.java:82` → `TerminalTalentProxy` |
| ③ | `TaskTalent` / `GenerateTalent` 持有 `HarnessEngine` 自身引用，通过 `task()`/`multitask()` 分发任务或动态创建 `AgentDefinition` | `TaskTalent.java` / `GenerateTalent.java` |
| ④ | `ContextCompressionInterceptor` 内部持有 `CompressionStrategy` 链（组合策略 → 去水 + 滚动摘要），在消息数或 token 超阈值时触发压缩 | `HarnessEngine.java:758-770` |
| ⑤ | `HITLInterceptor` 注册到 `ReActAgent` 的拦截器链，默认对 `bash` 工具启用 `HitlStrategy` 安全检查（注入防御/黑名单/路径边界等 7 层防护） | `HarnessEngine.java:772-775` |
| ⑥ | `MemorySolutionProvider` 是存储抽象接口，`MemoryTalent` 通过它完成记忆的持久化和检索；`SkillProvider` 同理（默认走 `MountManager` 文件扫描） | `HarnessEngine.java:798-803`、`824-828` |
| ⑦ | `McpGatewayTalent` / `OpenApiGatewayTalent` 分别管理 MCP 和 OpenAPI 服务池，运行时动态增删，且支持 `retryConfig()` | `HarnessEngine.java:805-820` |
| ⑧ | `HarnessExtension` SPI：`AgentFactory.create()` 末尾循环调用所有已注册扩展的 `configure()` 方法，允许外部介入 Agent 构建 | `AgentFactory.java:107-109` |

**图例：**

| 符号 | 含义 |
|------|------|
| `═══ HarnessEngine ═══` (双线边框) | solon-ai-harness 模块自有源码 |
| `┌─ dependency ─┐` (单线边框) | 外部 Maven 依赖包提供的组件 |
| `──→` | 直接引用/构建/注册关系 |
| `◄──` | 反向持有引用关系 |
| `└┬┘` 分支 | 同一包内的内部组件组合 |

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

各依赖包的详细角色已在上方 [项目结构](#项目结构) 中按模块标注。整体依赖拓扑如下：

```
solon-ai-harness
  ├── solon-lib                          # Solon 核心框架
  ├── solon-ai-agent                     # ReAct 代理引擎
  ├── solon-ai-talent-cli                # 终端交互 & 沙箱
  ├── solon-ai-talent-code               # 代码工程规约
  ├── solon-ai-talent-web                # 网络搜索 & 抓取
  ├── solon-ai-talent-lsp                # 语言服务器协议 (LSP)
  ├── solon-ai-talent-memory             # 长期记忆 & 心智模型
  ├── solon-ai-talent-gateway            # MCP / OpenAPI 网关
  └── solon-serialization-snack4         # JSON 序列化
```

> **测试依赖**: `solon-test` (test scope) | **编译辅助**: `lombok` (provided scope)
