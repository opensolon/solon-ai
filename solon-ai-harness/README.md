# solon-ai-harness — AI 代理编排引擎

## 简介

`solon-ai-harness`（马具引擎）是 Solon AI 框架的**核心编排容器**，提供 **Agent 运行时、子代理调度、命令系统、安全策略**等一整套 AI 代理基础设施。

它的本质是一个 **能力整合者**：`HarnessEngine` 通过接收 8 个 Maven 依赖包提供的 20+ 个 Talent/组件，在构造时完成实例化与配置，再通过 `AgentFactory.create()` 按 `AgentDefinition` 中的工具权限清单，将这些组件选择性注入到 `ReActAgent` 中，最终形成具备完整交互能力的 AI 代理。

---

## 项目结构

`solon-ai-harness` 的代码由 **14 个自有源文件** 和 **9 个集成依赖包** 共同构成。下图从两个维度展示全貌：

```
solon-ai-harness/                                  # AI 代理编排引擎
│
├── pom.xml                                        # Maven 坐标: org.noear:solon-ai-harness
│
├── # ═══════════ 模块自身源码 ═══════════
│
├── src/main/java/org/noear/solon/ai/harness/
│   ├── HarnessEngine.java                         # ★ 核心引擎（Builder 模式，编排全部组件）
│   ├── HarnessExtension.java                      # 扩展 SPI 接口（构建时注入）
│   ├── HarnessOptions.java                        # 运行时配置（路径/重试/窗口/开关）
│   │
│   ├── agent/                                     # 代理定义层
│   │   ├── AgentDefinition.java                   # 代理元数据模型（YAML frontmatter 驱动）
│   │   ├── AgentFactory.java                      # ★ 代理工厂（按工具名从 engine 取 talent 并注册）
│   │   ├── AgentManager.java                      # 内置 + 挂载代理的发现/缓存/管理
│   │   ├── TaskTalent.java                        # 子代理调度器（task 串行 / multitask 并行）
│   │   └── GenerateTalent.java                    # 运行时动态创建子代理
│   │
│   ├── command/                                   # 通用命令框架（CLI/Web 共用）
│   │   ├── Command.java                           # 命令接口
│   │   ├── CommandContext.java                    # 命令执行上下文
│   │   ├── CommandRegistry.java                   # 命令注册中心
│   │   ├── MarkdownCommand.java                   # Markdown 命令模型（变量替换）
│   │   └── MarkdownCommandLoader.java             # 从 .md 文件加载自定义命令
│   │
│   ├── channel/
│   │   └── Channel.java                           # IM 通道接口（微信/飞书/钉钉等）
│   │
│   ├── hitl/
│   │   └── HitlStrategy.java                      # 人工介入安全审计（7 层防护）
│   │
│   └── permission/
│       └── ToolPermission.java                    # 工具权限枚举（三级快捷权限）
│
├── src/main/resources/META-INF/solon/ai/harness/  # 内置子代理 Markdown 定义
│   ├── general.md                                 # 通用全能专家  [tools: *]
│   ├── bash.md                                    # 命令执行      [list, read, bash]
│   ├── explore.md                                 # 信息探索      [list, read, grep, glob, ...]
│   ├── plan.md                                    # 规划计划      [list, read, grep, glob, ...]
│   └── git-summary.md                             # Git 提交摘要  [hidden]
│
├── src/test/java/
│   ├── demo/ai/harness/DemoApp.java               # 完整使用示例
│   └── features/ai/harness/
│       ├── AgentDefaultTest.java                  # 内置子代理加载验证
│       ├── AgentFactoryCommandSessionTest.java     # 工厂 & 命令会话测试
│       ├── AgentMetadataTest.java                 # 代理元数据解析测试
│       ├── CommandUtilTest.java                   # 命令解析工具测试
│       └── JsonSchemaTest.java                    # JSON Schema 生成验证
│
├── # ═══════════ 依赖包（能力来源） ═══════════
│
├── [solon-ai-agent]   ReAct 代理引擎与拦截器链
│   └── org.noear:solon-ai-agent
│       └── → ReActAgent / ChatModel / CompressionStrategy / HITLInterceptor
│
├── [solon-ai-talent-cli]  终端交互与挂载管理
│   └── org.noear:solon-ai-talent-cli
│       └── → TerminalTalent / SkillTalent / TodoTalent / ClockTalent / MountManager
│
├── [solon-ai-talent-code]  代码工程规约
│   └── org.noear:solon-ai-talent-code
│       └── → CodeTalent
│
├── [solon-ai-talent-web]  网搜与抓取
│   └── org.noear:solon-ai-talent-web
│       └── → WebsearchTalent / WebfetchTalent / CodeSearchTalent
│
├── [solon-ai-talent-lsp]  LSP 语言服务器
│   └── org.noear:solon-ai-talent-lsp
│       └── → LspManager / LspTalent / LspServerParameters
│
├── [solon-ai-talent-memory]  长期记忆与心智模型
│   └── org.noear:solon-ai-talent-memory
│       └── → MemoryTalent / MemorySolutionProvider
│
├── [solon-ai-talent-gateway]  MCP / OpenAPI 网关
│   └── org.noear:solon-ai-talent-gateway
│       └── → McpGatewayTalent / OpenApiGatewayTalent / McpServerParameters / ApiSource
│
├── [solon-lib]  Solon 核心框架
│   └── org.noear:solon-lib
│       └── → IoC / 配置管理 / 生命周期
│
└── [solon-serialization-snack4]  JSON 序列化
    └── org.noear:solon-serialization-snack4
        └── → 配置序列化、指令参数编解码
```

---

## 架构总览

### 核心组件关系图（以 HarnessEngine 依赖整合为中心）

`HarnessEngine` 的核心工作分为两阶段：**构造期** 和 **构建期**。

```
┌═══════════════════════════════════════════════════════════════════════════════════════════════════┐
║                             构造期：HarnessEngine(HarnessOptions)                                    ║
║                                     (第 755-838 行)                                                 ║
╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝
         │
         │ options 传入的配置（或默认值）驱动整个构造过程
         ▼
┌────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│  solon-ai-agent 包提供的组件                                                                        │
│  ─────────────────────────────────────────────────────────────────────────────────────               │
│                                                                                                     │
│  ContextCompressionInterceptor  ←  CompressionStrategy 链 (默认组合策略)                            │
│  (上下文压缩)                             ├── KeyInfoExtractionStrategy (干货提取)                   │
│                                            └── HierarchicalCompressionStrategy (滚动摘要)            │
│                                                                                                     │
│  HITLInterceptor  (人工介入拦截器)  ←  默认对 bash 绑定 HitlStrategy                               │
│  CacheControl / AgentSessionProvider  (缓存 / 会话提供者)                                           │
│                                                                                                     │
└────────────────────────────────────────────────────────────────────────────────────────────────────────┘
         │
         │
┌────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│  solon-ai-talent-* 包提供的 Talent 组件                                                              │
│  ────────────────────────────────────────────────────────────────────────────────────               │
│                                                                                                     │
│  ┌─ cli ─────────────────────────────────────────────────────────────────────┐                     │
│  │  TerminalTalent (bash/read/write/edit/glob/grep/ls + 沙箱)  ← options 控制  │                     │
│  │  SkillTalent (skillread/skillrefresh) ← SkillProvider 或 MountManager       │                     │
│  │  TodoTalent / ClockTalent ← 文件路径                                        │                     │
│  │  MountManager ← workspace                                                   │                     │
│  └────────────────────────────────────────────────────────────────────────────-┘                     │
│                                                                                                     │
│  ┌─ code ──────────────────────────────────────────────────────────────────────────┐                │
│  │  CodeTalent (技术栈识别 / CODE.md 规约) ← workspace, harnessHome                │                │
│  └──────────────────────────────────────────────────────────────────────────-───────┘                │
│                                                                                                     │
│  ┌─ web ────────────────────────────────────────────────────────────────────────┐                  │
│  │  WebsearchTalent / WebfetchTalent / CodeSearchTalent  ← retryConfig(options)  │                 │
│  └────────────────────────────────────────────────────────────────────────────-───┘                 │
│                                                                                                     │
│  ┌─ lsp ─────────────────────────────────────────────────────────────────────────┐                 │
│  │  LspManager (服务生命周期)  ← 遍历 options.LspServers 注册                      │                 │
│  │  LspTalent (符号解析/引用查找) ← lspManager, workspace                        │                 │
│  └────────────────────────────────────────────────────────────────────────────-───┘                 │
│                                                                                                     │
│  ┌─ memory ──────────────────────────────────────────────────────────────────────┐                │
│  │  MemoryTalent (记忆提取/检索/演进) ← MemorySolutionProvider + enabled 开关     │                 │
│  └──────────────────────────────────────────────────────────────────────────-──────┘                 │
│                                                                                                     │
│  ┌─ gateway ─────────────────────────────────────────────────────────────────────┐                 │
│  │  McpGatewayTalent  ← retryConfig + 遍历 options.McpServers 注册               │                 │
│  │  OpenApiGatewayTalent  ← retryConfig + 遍历 options.ApiServers 注册            │                 │
│  └────────────────────────────────────────────────────────────────────────────-───┘                  │
│                                                                                                     │
└────────────────────────────────────────────────────────────────────────────────────────────────────────┘
         │
         │ 自有核心组件：AgentManager / CommandRegistry / TaskTalent / GenerateTalent
         │ 其中 TaskTalent / GenerateTalent 反持有 engine 引用，用于调度
         ▼
┌════════════════════════════════════════════════════════════════════════════════════════════════════╗
║                           构建期：AgentFactory.create(engine, definition)                            ║
║                                    (第 50-118 行 + toolAddDo 第 120-246 行)                          ║
╚════════════════════════════════════════════════════════════════════════════════════════════════════╝
         │
         │ definition.getMetadata().getTools() 遍历工具名
         │ 每个工具名 -> toolAddDo() 从 engine getter 获取实际组件
         ▼

┌─────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                          工具名 → 组件映射（AgentFactory.toolAddDo）                                   │
├─────────────────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                                      │
│  ┌── 文件操作 --→ TerminalTalentProxy(权限代理) ──→ TerminalTalent ──┐                                │
│  │  read / write / edit / glob / grep / ls / bash+扩展                 │                                │
│  └────────────────────────────────────────────────────────────────────-┘                                │
│                                                                                                      │
│  todo / todoread / todowrite ──→ TodoTalent + ClockTalent (主代理) / planningMode (子代理)             │
│  skill ──→ SkillTalent                                                                               │
│  code  ──→ CodeTalent                                                                                │
│                                                                                                      │
│  webfetch ──→ WebfetchTalent                                                                         │
│  websearch ──→ WebsearchTalent                                                                       │
│  codesearch ──→ CodeSearchTalent                                                                     │
│                                                                                                      │
│  task / subagent ──→ TaskTalent ← engine.isSubagentEnabled() 开关                                    │
│  generate ──→ GenerateTalent ← engine.isSubagentEnabled() 开关                                       │
│                                                                                                      │
│  memory ──→ MemoryTalent                                                                             │
│  mcp ──→ McpGatewayTalent                                                                            │
│  openapi ──→ OpenApiGatewayTalent                                                                    │
│  lsp ──→ LspTalent                                                                                   │
│  hitl ──→ HITLInterceptor (从 engine 取)                                                              │
│                                                                                                      │
│  ┌── 快捷权限 ──────────────────────────────────────────────────────────────┐                        │
│  │  pi    = {read, write, edit, bash+扩展}                                    │                        │
│  │  *     = {read, write, edit, glob, grep, ls, bash+扩展, skill, todo,      │                        │
│  │          code, codesearch, websearch, webfetch, task, lsp}                │                        │
│  │  **    = {* + generate, mcp, openapi, hitl, memory}                      │                        │
│  └──────────────────────────────────────────────────────────────────────────-┘                        │
│                                                                                                      │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                              最终产物：ReActAgent                                                        │
│                                                                                                      │
│  ┌──────────────────┐  ┌──────────────────────────┐  ┌───────────────────────────────────┐          │
│  │     ChatModel     │  │     Interceptor 链        │  │       SystemPrompt                 │          │
│  │  (LLM 调用)       │  │  ContextCompression       │  │  (AgentDefinition 解析 + 注入)     │          │
│  │                   │  │  HITL (可选)              │  │                                   │          │
│  └──────────────────┘  │  StopLoop                  │  └───────────────────────────────────┘          │
│                        │  ToolRetry/ToolSanitizer   │  ┌───────────────────────────────────┐          │
│                        └──────────────────────────┘  │     ToolSet（Talent 集合）            │          │
│                                                       │  ┌─ TerminalTalentProxy           │          │
│                                                       │  ├─ SkillTalent                   │          │
│                                                       │  ├─ WebsearchTalent               │          │
│                                                       │  ├─ ...（按工具名动态拼接）        │          │
│                                                       │  └─ HarnessExtension 注入          │          │
│                                                       └───────────────────────────────────┘          │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 组件流向说明

| 编号 | 流向 | 源码位置 |
|------|------|----------|
| ① | `HarnessEngine` 构造时，依据 `HarnessOptions` 中的配置初始化全部 Talent 组件，并设置重试、开关等参数 | `HarnessEngine.java:755-838` |
| ② | `MountManager` 负责扫描所有挂载点（本地目录、远程等），暴露 `MountDir` / `SkillDir` / `AgentMd` 给 `AgentManager` | `solon-ai-talent-cli` → `MountManager` |
| ③ | `AgentManager` 从 `META-INF/solon/ai/harness/*.md` 加载 5 个内置子代理（general/bash/explore/plan/git-summary），并预留挂载代理的按需解析 | `AgentManager.java:55-61` |
| ④ | `AgentFactory.create()` 根据 `AgentDefinition` 中的 `tools` 清单，通过 `toolAddDo()` 从 engine 各 getter 获取实例，注册到 `ReActAgent.Builder` | `AgentFactory.java:50-118` • `120-246` |
| ⑤ | `TerminalTalentProxy` 包装 `TerminalTalent`，按白名单选择性暴露文件/命令工具（而不是直接暴露终端） | `AgentFactory.java:82` |
| ⑥ | `TaskTalent` / `GenerateTalent` 持有 `HarnessEngine` 引用，通过 `task()`/`multitask()` 获取 `AgentDefinition` → 构建子代理 → 分发执行 | `TaskTalent.java:94-162` • `GenerateTalent.java:50-131` |
| ⑦ | `HarnessExtension` 的 `configure()` 在 Agent 构建最后被调用，允许外部代码注入自定义拦截器/Talent | `AgentFactory.java:107-109` |
| ⑧ | `McpGatewayTalent` / `OpenApiGatewayTalent` 运行时动态管理外部服务连接池，`retryConfig()` 同步引擎重试策略 | `HarnessEngine.java:805-820` |

### 模块职责

| 模块 | 包路径 | 职责 |
|------|--------|------|
| **引擎核心** | `HarnessEngine` | 编排全部外部依赖组件，管理配置/生命周期/Talent 注入 |
| **扩展点** | `HarnessExtension` | SPI 接口，允许外部代码在 Agent 构建时介入 |
| **配置** | `HarnessOptions` | 运行时配置（路径、开关、重试策略、窗口策略） |
| **代理定义** | `agent.AgentDefinition` | 代理元数据模型（名称/描述/工具权限/模型/MCP/记忆等） |
| **代理工厂** | `agent.AgentFactory` | 按工具名从 engine 获取组件，构建 `ReActAgent` |
| **代理管理** | `agent.AgentManager` | 内置 + 挂载代理的发现、缓存、生命周期管理 |
| **子代理调度** | `agent.TaskTalent` | 串行/并行委派任务给专项子代理 |
| **动态创建** | `agent.GenerateTalent` | 运行时动态创建子代理定义（可持久化到文件） |
| **命令系统** | `command.*` | CLI/Web 共用的命令框架，支持 Markdown 模板自定义命令 |
| **IM 通道** | `channel.Channel` | 统一 IM 通道接口（微信、飞书、钉钉等） |
| **安全审计** | `hitl.HitlStrategy` | 针对 bash 等高危操作的 7 层防护策略 |
| **工具权限** | `permission.ToolPermission` | 三级快捷权限（`pi` / `*` / `**`） |

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
primary: true                         # 是否为主代理
---

## 系统提示词正文
你是一个探索专家...
```

定义文件被放置在 `META-INF/solon/ai/harness/` 或挂载点的 `agents/` 目录下，由 `AgentManager` 自动发现。

### 内置子代理

| 代理 | 名称 | 工具集 | 描述 |
|------|------|--------|------|
| **general** | `general` | `*`（全部公有工具） | 通用全能专家，处理所有问题 |
| **bash** | `bash` | `list, read, bash` | 命令执行专家（无文件写权限） |
| **explore** | `explore` | `list, read, grep, glob, skill, webfetch, websearch, codesearch` | 全域信息探索专家 |
| **plan** | `plan` | `list, read, grep, glob, skill, webfetch, websearch, codesearch` | 规划与计划专家 |
| **git-summary** | `git-summary` | 无工具（hidden） | Git 提交摘要生成 |

### 工具权限级别

三级快捷权限映射到 `AgentFactory` 中预定义的工具集合：

| 权限 | 标识 | 包含工具 |
|------|------|----------|
| 全部完整 | `**` | `read, write, edit, glob, grep, ls, bash(含扩展), skill, todo, code, codesearch, websearch, webfetch, task, generate, mcp, openapi, hitl, lsp, memory` |
| 全部公有 | `*` | `read, write, edit, glob, grep, ls, bash(含扩展), skill, todo, code, codesearch, websearch, webfetch, task, lsp` |
| 核心操作 | `pi` | `read, write, edit, bash(含扩展)` |

> 实际注册到 Agent 的工具集 = `definition.getMetadata().getTools()` 各条目展开后的并集，减去 `disallowedTools`（定义级 + 全局级）。

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

engine.prompt("请分析当前项目的代码结构")
    .session(session)
    .options(o -> {
        o.toolContextPut(HarnessEngine.ATTR_CWD, "/my/project/path");
    })
    .call();
```

### 4. 使用子代理

```java
// 通过 AgentManager 获取内置子代理定义
AgentDefinition bashDef = engine.getAgentManager().getAgent("bash");

// 构建子代理并执行
ReActAgent bashAgent = bashDef.builder(engine).build();
bashAgent.prompt("列出当前目录所有 Java 文件")
    .session(session)
    .call();
```

通过 `AgentFactory` 手动构建：

```java
AgentDefinition definition = new AgentDefinition();
definition.setSystemPrompt("你是前端专家");
definition.getMetadata().addTools(ToolPermission.TOOL_READ, ToolPermission.TOOL_GREP);

ReActAgent subagent = engine.createSubagent(definition).build();
subagent.prompt("分析 src/main.js").session(session).call();
```

### 5. 动态创建子代理（运行时）

通过 `generate` 工具可以在运行时动态创建新的子代理定义（基于内置 `general` 定义复制修改）：

```java
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
// 通常在 LLM 对话中通过工具调用自动触发
// 对应 @ToolMapping(name="multitask") 方法
```

### 7. 配置项参考

| 配置 | 方法 | 默认值 | 说明 |
|------|------|--------|------|
| 工作区 | `of(workspace, harnessHome)` | — | 工作目录路径 |
| 引擎根目录 | `of(workspace, harnessHome)` | `.solon/` | 引擎配置/数据存放目录 |
| 系统提示词 | `systemPrompt(...)` | `null` | 主代理系统提示词 |
| 最大轮次 | `maxTurns(20)` | `20` | Agent 最大推理-行动轮次 |
| 自动反思 | `autoRethink(true)` | `true` | 失败后自动重试 |
| 会话窗口 | `sessionWindowSize(8)` | `8` | 保留最近 N 轮消息 |
| 压缩阈值 | `compressionThreshold(30, 30000)` | 30 条 / 3 万 token | 触发历史压缩阈值 |
| 压缩模型 | `compressionModel(...)` | `null`（复用主模型） | 上下文压缩专用模型 |
| 记忆 | `memoryEnabled(true)` | `true` | 长期记忆开关 |
| 沙箱 | `sandboxEnabled(true)` | `true` | 文件系统沙箱开关 |
| 沙箱限制用户目录 | `sandboxAllowUserHome(true)` | `true` | 允许访问用户主目录 |
| 沙箱保护系统 | `sandboxSystemRestrict(true)` | `true` | 禁止访问系统路径 |
| 人工介入 | `hitlEnabled(false)` | `false` | HITL 拦截开关 |
| 子代理 | `subagentEnabled(true)` | `true` | 子代理功能开关 |
| 异步 Bash | `bashAsyncEnabled(false)` | `false` | Bash 异步执行开关 |
| API 重试 | `apiRetries(3)` | `3` | HTTP API 调用重试次数 |
| MCP 重试 | `mcpRetries(3)` | `3` | MCP 服务调用重试次数 |
| 模型重试 | `modelRetries(3)` | `3` | 大模型调用重试次数 |

### 8. 运行时动态配置

```java
// 启用/禁用沙箱
engine.setSandboxEnabled(false);

// 切换默认模型（触发主代理重建）
engine.setDefaultModel("deepseek-v4-flash");

// 增加/移除模型
engine.addModel(new ChatConfig(...));
engine.removeModel("gpt-4");

// 动态授权/撤销工具（触发主代理重建）
engine.allowTool("bash");
engine.disallowTool("write");

// 添加 MCP 服务
engine.addMcpServer("my-service", mcpServerParams);

// 添加 API 源
engine.addApiServer(apiSource);

// 添加 LSP 服务器（触发主代理重建）
engine.addLspServer("java-lsp", lspServerParams);

// 添加挂载点
engine.addMount(mountDir);

// 添加扩展（触发主代理重建）
engine.addExtension((name, builder) -> {
    // 自定义配置逻辑
});
```

### 9. 自定义扩展

实现 `HarnessExtension` 接口，在 Agent 构建过程中注入自定义逻辑：

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

变量替换规则：
- `$ARGUMENTS` → 所有参数拼接为单个字符串
- `$1`、`$2`、`$3` ... → 按位置取单个参数

支持子目录命名空间：`deploy/staging.md` → 命令名 `deploy:staging`

---

## 安全机制

`HitlStrategy` 提供了 7 层安全审计策略，默认绑定到 `bash` 工具：

1. **注入防御** — 拦截反引号、`$()`、设备重定向（`/dev/`）
2. **系统黑名单** — 禁止 `sudo`、`kill`、`reboot`、`systemctl` 等系统级命令
3. **路径边界检查** — 拦截 `../` 路径回溯和 `/etc/`、`~/.ssh/` 等敏感目录
4. **环境变更分级** — `npm install`、`pip install`、`docker` 等需人工确认
5. **网络行为零信任** — `curl`、`wget`、`ssh`、`scp` 等网络工具需审核
6. **管道安全链** — 只允许 `grep/head/tail/awk/sort/uniq/wc/jq/column/less/sed/xxd` 等安全工具的管道
7. **破坏性操作拦截** — 递归删除（`rm -rf`）、文件移动（`mv`）等操作需确认

---

## 运行时目录结构

```
{solonHome}/             # 引擎根目录（默认 .solon/）
├── sessions/            # 会话持久化存储
├── skills/              # 技能（Skill）文件存放目录
├── agents/              # 自定义代理定义目录
├── commands/            # Markdown 自定义命令目录
├── memory/              # 长期记忆存储目录
├── download/            # 下载文件缓存目录
└── channels/            # IM 通道配置目录
```

> 实际路径由 `HarnessOptions` 计算，通过 `getHarnessSessions()` / `getHarnessSkills()` 等方法派生，默认基于 `harnessHome` 拼接。

---

## 依赖关系

```
solon-ai-harness
  │
  ├── solon-lib                          # Solon 核心 IoC/配置/生命周期
  │
  ├── solon-ai-agent                     # ★ ReActAgent 引擎核心
  │   └── ReActAgent / ChatModel / CompressionStrategy / HITLInterceptor
  │
  ├── solon-ai-talent-cli                # ★ 终端交互 & 文件系统 & 挂载管理
  │   └── TerminalTalent/SkillTalent/TodoTalent/ClockTalent/MountManager
  │
  ├── solon-ai-talent-code               # 代码工程规约验证
  │   └── CodeTalent
  │
  ├── solon-ai-talent-web                # 网络搜索与内容获取
  │   └── WebsearchTalent/WebfetchTalent/CodeSearchTalent
  │
  ├── solon-ai-talent-lsp                # LSP 语言服务器协议
  │   └── LspManager / LspTalent / LspServerParameters
  │
  ├── solon-ai-talent-memory             # 长期记忆与心智模型
  │   └── MemoryTalent / MemorySolutionProvider
  │
  ├── solon-ai-talent-gateway            # MCP / OpenAPI 第三方工具集成
  │   └── McpGatewayTalent / OpenApiGatewayTalent
  │
  └── solon-serialization-snack4         # JSON 序列化
```

> **测试依赖**: `solon-test` (test scope)  
> **编译辅助**: `lombok` (provided scope)
