---
title: "harness - 自定义命令"
---

自定义命令是一种把“常用提示词模板”固化为可复用指令的能力（兼容 Claude Code 的 Custom Commands 规范）。命令本质是带参数占位的 Markdown 模板，执行时替换参数后作为 Agent 任务运行。

### 1、命令定义文件

命令以 Markdown 文件定义，放在 `{harnessHome}/commands/` 目录下：YAML Front Matter 描述元数据，正文即提示词模板。

```markdown
---
description: 生成一条 git 提交信息
argument-hint: [message]
model: deepseek-v4-flash
---
请根据当前暂存区的变更，生成一条简洁的提交信息。
补充说明：$ARGUMENTS
```

元数据字段：

| 字段 | 描述 |
|------|------|
| `description` | 命令描述 |
| `argument-hint` | 参数提示（用于补全提示） |
| `model` | 指定执行该命令的模型（可选） |

### 2、参数占位符

模板正文支持两种占位符（兼容 Claude Code）：

* `$1`、`$2`、`$3` …：按位置取单个参数。
* `$ARGUMENTS`：把所有参数拼接为一个字符串。

替换顺序为“先位置参数、后 `$ARGUMENTS`”。例如模板 `部署 $1 到 $2 环境（$ARGUMENTS）`，传入 `["web", "prod"]` 后得到 `部署 web 到 prod 环境（web prod）`。

### 3、命名空间（子目录）

`commands/` 支持子目录递归扫描，子目录会成为命令的命名空间（用冒号分隔）。例如：

* `commands/deploy/staging.md` → 命令名 `deploy:staging`
* `commands/git/commit.md` → 命令名 `git:commit`

### 4、注册与查找

命令由 `CommandRegistry` 管理，可从目录加载，也可用代码注册：

```java
CommandRegistry registry = engine.getCommandRegistry();

// 从目录加载（递归扫描 .md）
registry.load(Paths.get(engine.getWorkspace(), engine.getHarnessCommands()));

// 查找与列举
Command cmd = registry.find("git:commit");
List<Command> all = registry.all();        // 排序后的全部命令
List<String> names = registry.names();     // 命令名列表（用于 Tab 补全）
```

> 同名命令只会注册一次，重复注册会被忽略并打印告警。
