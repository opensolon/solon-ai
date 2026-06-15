---
title: "harness - 目录结构与挂载机制"
---

`HarnessEngine.of(workspace, harnessHome)` 的两个参数，定义了马具的两个基准位置：

* `workspace`：工作区。Agent 默认的当前工作目录（文件查找、读写、命令执行都以它为根）。
* `harnessHome`：马具主目录（默认 `.solon/`，例：`.soloncode/`）。马具的运行态数据都落在这里。

### 1、马具主目录（harnessHome）

主目录下按用途划分为若干子目录，按需自动创建/读取：

| 子目录 | 用途 |
|--------|------|
| `sessions/` | 会话与 Todo 任务清单的持久化 |
| `skills/` | 技能（SKILL.md） |
| `agents/` | 子代理定义（xxx.md） |
| `commands/` | 自定义命令（xxx.md） |
| `memory/` | 心智记忆 |
| `download/` | 下载缓存 |
| `channels/` | IM 通道绑定数据 |

对应的派生路径，可通过 `engine.getHarnessAgents()`、`engine.getHarnessSkills()` 等方法读取。

### 2、挂载机制（Mount）

除了主目录里的内置位置，还可以通过“挂载”把外部目录接入马具，让技能、子代理可以来自全局共享库或第三方目录。

挂载通过 `MountDir` 描述，类型由 `MountType` 区分：

| 类型 | 说明 |
|------|------|
| `SKILLS` | 技能挂载（发现并加载 SKILL.md） |
| `AGENTS` | 子代理挂载（发现并加载子代理 md） |
| `FILES` | 只读文件挂载 |

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .sessionProvider(sessionProvider)
        .mountAdd(MountDir.builder()
                .alias("@global-agents")          // 虚拟别名，须以 @ 开头
                .type(MountType.AGENTS)           // 挂载类型
                .path("~/.soloncode/agents/")     // 物理路径
                .primary(true)                    // 原始挂载（不可删除）
                .writeable(false)                 // 是否可写（默认只读）
                .build())
        .mountAdd(MountDir.builder()
                .alias("@global-skills")
                .type(MountType.SKILLS)
                .path("~/.soloncode/skills/")
                .build())
        .build();
```

`MountDir` 字段说明：

| 字段 | 默认值 | 描述 |
|------|--------|------|
| `alias` | / | 虚拟别名，须以 `@` 开头（如 `@global-agents`） |
| `type` | / | 挂载类型（`SKILLS` / `AGENTS` / `FILES`） |
| `path` | / | 物理路径，支持 `~/`（用户目录相对）与 `./`（工作区相对） |
| `primary` | `false` | 是否为原始挂载（原始挂载不可被动态移除） |
| `enabled` | `true` | 是否启用 |
| `writeable` | `false` | 是否可写（挂载默认只读） |

### 3、路径别名（@别名）

挂载后，可在工具调用中直接用 `@别名` 引用其物理目录（如 `@global-skills/xxx`），由马具自动转换为真实路径。工作区内的访问则使用相对路径（如 `src/app.java`）。

### 4、运行时动态管理挂载

```java
engine.addMount(MountDir.builder()
        .alias("@team-skills")
        .type(MountType.SKILLS)
        .path("~/team/skills/")
        .build());                       // 添加挂载

engine.hasMount("@team-skills");         // 是否存在
engine.refreshMount("@team-skills");     // 刷新（重新扫描该挂载）
engine.refreshMount(null);              // 刷新全部自定义代理
engine.removeMount("@team-skills");      // 移除（primary 的不可移除）
```

读取挂载内容：

```java
engine.getMounts();                      // 全部挂载
engine.getSkills();                      // 全部技能
engine.getSkillsByMount("@global-skills");
engine.getAgents();                      // 全部子代理
engine.getAgentsByMount("@global-agents");
```
