# solon-ai-harness

> Solon AI 马具引擎 — 一个基于 Solon 框架的 AI Agent 编排与执行引擎

## 概述

`solon-ai-harness` 是一个功能完整的 AI Agent 编排框架，提供主代理（Main Agent）与子代理（Subagent）的多智能体协作能力。它支持动态工具权限管理、挂载池加载、MCP 服务集成、REST API 集成、人工介入（HITL）安全审计等高级特性。

## 核心特性

- **多智能体架构**: 主代理 + 子代理（task/multitask）协作模式，支持上下文隔离与并行任务执行
- **动态工具权限**: 细粒度的工具权限控制（read/write/edit/bash/grep/glob 等）
- **挂载池管理**: 支持全局/本地/自定义挂载池，兼容 CLI Skill 规范
- **MCP 服务集成**: 动态加载 MCP Server 工具
- **REST API 集成**: 将 REST API 作为 Agent 工具调用
- **人工介入（HITL）**: 对 bash 等高危命令进行安全审计与拦截
- **上下文压缩**: 自动滚动压缩，避免上下文窗口溢出
- **代码工程规约**: 自动识别技术栈（Maven/Node），生成构建与测试指令
- **动态代理生成**: 运行时动态创建子代理并持久化到文件

## 模块结构

```
solon-ai-harness/
├── src/main/java/org/noear/solon/ai/harness/
│   ├── HarnessEngine.java          # 马具引擎核心（Builder 模式）
│   ├── HarnessOptions.java        # 运行时配置（内部使用）
│   ├── agent/
│   │   ├── AgentDefinition.java    # 代理定义（支持 Markdown YAML Frontmatter 解析）
│   │   ├── AgentFactory.java       # 代理工厂（根据定义构建 ReActAgent）
│   │   ├── AgentManager.java       # 代理管理器（注册/加载/扫描 agents 目录）
│   │   ├── TaskSkill.java          # 子代理工具包（task/multitask 工具）
│   │   └── GenerateTool.java       # 动态代理生成工具
│   ├── code/
│   │   └── CodeSkill.java          # 代码工程规约工具包（技术栈识别、CODE.md 生成）
│   ├── hitl/
│   │   └── HitlStrategy.java       # 人工介入策略（bash 安全审计规则）
│   └── permission/
│       └── ToolPermission.java     # 工具权限枚举
└── src/test/java/demo/ai/harness/
    └── DemoApp.java                # 使用示例
```

## 快速开始

### 1. 依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-harness</artifactId>
    <version>${solon.version}</version>
</dependency>
```

### 2. 初始化引擎

```java
// 构建引擎（Builder 链式配置）
HarnessEngine engine = HarnessEngine.of(".solon/")
        .tools(ToolPermission.TOOL_ALL_FULL) // 设定工具权限
        .model(new ChatConfig().then(slf -> {
            slf.setApiUrl("https://api.deepseek.com");
            slf.setApiKey("sk-***");
            slf.setModel("deepseek-v4-flash");
        }))
        .sessionProvider(InMemoryAgentSession::of)
        .build();
```

### 3. 使用主代理

```java
AgentSession session = engine.getSession("default");

engine.prompt("分析当前项目的技术栈")
        .session(session)
        .options(o -> {
            o.toolContextPut(HarnessEngine.ATTR_CWD, "/path/to/project");
        })
        .call();
```

### 4. 动态创建子代理

```java
AgentDefinition definition = new AgentDefinition();
definition.setSystemPrompt("你是一个代码审查专家，负责检查代码质量。");
definition.getMetadata().addTools(ToolPermission.TOOL_READ, ToolPermission.TOOL_GREP);

ReActAgent subagent = engine.createSubagent(definition).build();
subagent.prompt("审查 src/main/java 目录下的代码")
        .session(session)
        .call();
```

## 工具权限

| 权限 | 说明 |
|------|------|
| `read` | 读取文件完整内容 |
| `write` | 写入文件完整内容 |
| `edit` | 修改文件内容（含 read+write+edit） |
| `bash` | 运行 Shell 命令 |
| `glob` | 使用模式匹配查找文件 |
| `grep` | 基于正则表达式的全文检索 |
| `ls` / `list` | 列出目录内容 |
| `pi` | 核心操作（read + write + edit + bash） |
| `task` | 调度子代理 |
| `skill` | 调用预定义的技能模块 |
| `code` | 编码指导模块 |
| `todo` | 任务清单管理 |
| `webfetch` | 抓取网页内容 |
| `websearch` | 互联网搜索 |
| `codesearch` | 代码仓库搜索 |
| `mcp` | MCP 服务工具 |
| `restapi` | REST API 工具 |
| `generate` | 动态创建子代理 |
| `hitl` | 人工介入拦截 |
| `*` | 全部公开工具 |
| `**` | 全部工具（含私有） |

## 配置说明

### Builder 配置

```java
HarnessEngine engine = HarnessEngine.of(".solon/")
        // 工作区
        .workspace("/path/to/workspace")
        // 工具权限
        .tools(ToolPermission.TOOL_BASH, ToolPermission.TOOL_READ)
        // 最大回合数
        .maxTurns(30)
        // 会话窗口与压缩
        .sessionWindowSize(8)
        .compressionMaxMessages(15)
        .compressionMaxTokens(15000)
        // 沙箱模式
        .sandboxMode(true)
        // 启用人工介入
        .hitlEnabled(true)
        // 启用子代理
        .subagentEnabled(true)
        // 构建引擎
        .sessionProvider(InMemoryAgentSession::of)
        .build();

// 运行时动态修改（支持）
engine.setMaxTurns(50);
engine.setHitlEnabled(false);
```

### 配置加载优先级

`config.yml` 和 `AGENTS.md` 的加载顺序：

1. 资源文件（classpath）
2. 工作区配置（`{userDir}/{harnessHome}/`）
3. 用户目录配置（`{userHome}/{harnessHome}/`）
4. 程序边上的配置文件

### 目录结构约定

```
{harnessHome}/
├── sessions/   # 会话存储
├── skills/     # 技能定义
├── agents/     # 子代理定义（.md 文件）
└── memory/     # 主代理记忆
```

## 子代理定义

子代理使用 Markdown 格式定义，支持 YAML Frontmatter：

```markdown
---
name: code_reviewer
description: 代码审查专家
enabled: true
tools:
  - read
  - grep
  - bash
---

你是一个资深的代码审查专家，负责检查代码质量、发现潜在问题并提供改进建议。

## 审查标准
1. 代码可读性
2. 性能优化
3. 安全隐患
4. 设计模式
```

## HITL 安全审计

`HitlStrategy` 对 bash 命令进行多层安全审计：

- **命令注入防御**: 拦截反引号、`$(...)`、设备重定向
- **系统级安全**: 拦截 sudo/chown/chmod/kill/reboot 等
- **路径边界**: 禁止 `../` 回溯和敏感目录访问
- **包管理变更**: 拦截 install/remove/commit 等变更操作
- **网络行为**: 拦截 curl/wget/ssh 等外连命令
- **管道安全**: 限制管道组合，只允许只读工具链
- **文件操作**: 拦截递归删除（rm -rf）和文件移动（mv）

## 架构设计

```
┌─────────────────────────────────────────────────┐
│                  HarnessEngine                   │
│  ┌───────────┐  ┌────────────┐  ┌────────────┐  │
│  │ MainAgent │  │ Subagent 1 │  │ Subagent N │  │
│  └───────────┘  └────────────┘  └────────────┘  │
│         │              │               │         │
│  ┌──────┴──────────────┴───────────────┴──────┐  │
│  │              AgentFactory                   │  │
│  └────────────────────────────────────────────┘  │
│  ┌────────┐ ┌───────┐ ┌──────┐ ┌──────────────┐  │
│  │ CliSkill│ │Code   │ │Task  │ │MCP/RestApi   │  │
│  │        │ │Skill  │ │Skill │ │Skill         │  │
│  └────────┘ └───────┘ └──────┘ └──────────────┘  │
│  ┌────────────────────────────────────────────┐  │
│  │        ContextCompressionInterceptor        │  │
│  │        HITLInterceptor                      │  │
│  └────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

## 依赖

- `solon-lib` — Solon 基础库
- `solon-ai-skill-cli` — CLI 工具包
- `solon-ai-skill-web` — Web 工具包（webfetch/websearch/codesearch）
- `solon-ai-skill-restapi` — REST API 工具包
- `solon-ai-skill-toolgateway` — MCP 网关工具包
- `solon-ai-agent` — Agent 核心
- `solon-serialization-snack4` — JSON 序列化
- `lombok` — 代码简化

## License

Apache License 2.0
