---
title: "harness - 子代理定义与任务委派"
---

主代理（main）在处理复杂任务时，可以把子任务委派给“专项子代理”执行。子代理拥有独立的上下文与工具权限，互不污染。

### 1、内置子代理

`solon-ai-harness` 内置了几个常用子代理（开箱即用）：

| 名称 | 说明 |
|------|------|
| `general` | 通用全能专家。其它子代理不匹配时优先选它 |
| `explore` | 全域信息探索专家（本地文件 + 全网检索，无写权限） |
| `plan` | 规划与计划专家（制定逻辑路径与执行步骤，无写权限） |
| `bash` | Bash 命令执行专家（git、命令行操作，无写权限） |
| `git-summary` | Git 提交摘要生成专家（隐藏代理，内部使用） |

### 2、子代理定义文件（Markdown）

子代理通过 Markdown 文件定义：YAML Front Matter 描述元数据，正文即系统提示词。文件放在 `{harnessHome}/agents/` 或挂载的 `AGENTS` 目录下。

```markdown
---
name: "code_reviewer"
description: "代码评审专家，定位风险并给出改进建议"
tools: ["read", "grep", "glob"]
model: "deepseek-v4-flash"
---

你是一位严谨的代码评审专家。

输出规范：
- 按“风险等级”分组列出问题。
- 每条问题须标注文件路径与行号。
```

元数据字段（Front Matter）：

| 字段 | 类型 | 描述 |
|------|------|------|
| `name` | String | 代理唯一标识 |
| `description` | String | 职能描述（供主代理识别调度） |
| `tools` | List | 允许的工具（见《配置参考》工具权限表） |
| `disallowedTools` | List | 禁用的工具 |
| `model` | String | 指定模型（不指定则用会话选中或主模型） |
| `skills` | List | 绑定的技能标识 |
| `mcpServers` | List | 绑定的 MCP 服务 |
| `memory` | String | 记忆作用域（user / project / local） |
| `permissionMode` | String | 权限模式 |
| `enabled` | bool | 是否启用（默认 true） |
| `hidden` | bool | 是否隐藏（不出现在可用代理列表） |

> 注：`tools` 工具名大小写均可解析，`ls` 与 `list` 等价。

### 3、用代码动态定义子代理

无文件时，也可用代码构建子代理（详见 [《harness - 进一步扩展定制参考》](/article/1439)）：

```java
AgentDefinition definition = new AgentDefinition();
definition.setSystemPrompt("你是一位代码评审专家...");
definition.getMetadata().setName("code_reviewer");
definition.getMetadata().setDescription("代码评审专家");
definition.getMetadata().addTools(ToolPermission.TOOL_READ, ToolPermission.TOOL_GREP);

ReActAgent subagent = engine.createSubagent(definition).build();
subagent.prompt("评审 src 目录").session(session).call();
```

### 4、任务委派（task / multitask）

当主代理拥有 `task` 工具权限时，模型可自主把任务委派给子代理。马具内部由 `TaskTalent` 暴露两个能力：

* `task`：委派单一任务给某个子代理（串行）。
* `multitask`：并行执行多个互不依赖的子任务（无资源竞争时优先用它以省时）。

每个子任务都是无状态的（上下文隔离），因此委派时必须在 prompt 中提供完成任务所需的全部背景。委派结果会以结构化片段（含 `agent_name`、`result_status`、`result_content`）回传给主代理。

### 5、动态生成子代理（generate）

当主代理拥有 `generate` 工具权限（且 `subagentEnabled=true`）时，模型可在运行中“即时创建”一个垂直领域的专家子代理。若 `saveToFile=true`，定义会持久化到 `{workspace}/.soloncode/agents/{name}.md`，后续可复用。
