# solon-ai-harness 文件读写根目录分析

> 分析时间：2026-11-06。范围：`solon-ai-harness` 聚合包，包括 `harness-core` 和 goal / loop / queue / steer 四个插件，以及它们依赖的 `solon-ai-mount`、`solon-ai-agent` 与 talent-cli / code / todo / lsp / memory / web / gateway 等模块。宿主一栏以 soloncode-cli 的装配为例（`WorkspaceManager`），用来说明文件在实际部署里落在哪里。

## 1. 结论

1. 引擎只有两个来源对象：
   - **stateSource**：存 harness 自身状态，不进挂载表。注意：缺省位置 `<ws>/<hh>` 在工作区内，模型仍可按相对路径读写；只有宿主把它设到工作区外（如 soloncode-cli）时模型才真正看不到。
   - **workspaceSource**：harness 写进工作区的产物经它落地，模型看得到。

   它们只覆盖 harness 主动读写的少数几类文件：TODO 清单、loop 任务、生成的子代理、子任务超长结果、工作区命令。
2. 模型通过文件工具、bash、LSP、CodeTalent 对工作区的读写，都直接走本地路径 `Path(__cwd 或 workspace)`，不经过 workspaceSource。原因是远程工作区还没打通，`MountManager(String, MountSource)` 的注释里写明了这一点。
3. 还有一类用户级或进程级的本地缓存与临时文件，与两个来源都无关：
   - 物化缓存：`~/.soloncode/cache/mount`
   - jdtls 索引：`<用户缓存目录>/solon-lsp`
   - 临时脚本：系统临时目录 `java.io.tmpdir`
4. 会话本身（消息和快照）、记忆、技能 / 子代理 / 命令目录，由宿主注入的组件决定存放位置（sessionProvider、memoryProvider、mount、commandRegistry），引擎不认识这些位置。goal 状态存在 `session.attrs()` 里，跟着会话一起持久化；queue / steer 的数据只在内存里。

## 2. 两个来源的定义

| 项 | stateSource | workspaceSource |
|---|---|---|
| 获取方式 | `engine.getStateSource()` | `engine.getWorkspaceSource()`，即 `MountManager.getWorkspaceSource()` |
| 设置方式 | `HarnessEngine.Builder.stateSource(MountSource)` | 引擎没有入口；`HarnessOptions` 固定 `new MountManager(workspace)` |
| 缺省值 | `FileMountSource.of(<workspace>/<harnessHome>)`，延迟创建（`HarnessOptions.getStateSource`） | `FileMountSource.of(workspace)`（`MountManager` 构造） |
| 是否注册进挂载表 | 否；但缺省根 `<ws>/<hh>` 在工作区内，模型可用相对路径 `<hh>sessions/<sid>/TODO.md` 直接读写（沙盒只强制拒绝 `.soloncode/agents`、`.soloncode/commands`）。设到工作区外才真正不可达 | 否（不是 `@alias` 挂载），但它的根就是模型工具的工作区根，`@workspace/...` 会解析到它 |
| 能否换成远程 | 可以（db / http 等），算存分离的主要入口 | 现在不能，否则模型读不到 harness 写的产物（见第 5 节） |
| 是否随 `__cwd` 变化 | 否 | 否（根固定为 workspace）；TaskTalent 在 cwd 不等于 workspace 时会改用 `FileMountSource.of(cwd)` |
| 宿主 soloncode-cli 的取值 | `~/.soloncode/workspaces/<key>/`（`WorkspaceDataUtil.dataDir`），与会话目录同处 | 缺省值，即 `<workspace>` |

`harnessHome` 默认是 `.solon/`，soloncode-cli 传入 `.soloncode/`。`getHarnessSessions/Skills/Agents/Commands/Memory/Channels()` 只是拼出 `harnessHome + "xxx/"` 这样的**相对路径字符串**。引擎本身只用到 `getHarnessHome()`（CodeTalent）和 `getHarnessAgents()`（GenerateTalent），其余由宿主自己选根目录去拼。

## 3. 总表：谁读写什么、根目录是什么

符号说明：
- `ws`：引擎 workspace
- `cwd`：本轮会话工作目录 `__cwd`（缺省为 `engine.getSessionCwd()`，再缺省为 ws）
- `hh`：harnessHome
- `S`：stateSource
- `W`：workspaceSource
- `L`：直接本地 Path / Files

### 3.1 harness 自身的状态与产物（经 MountSource）

| 组件 | 文件 | 读/写 | 根目录 | 经由 | 与两个来源的关系 | soloncode-cli 实际位置 |
|---|---|---|---|---|---|---|
| TodoTalent（talent-todo） | `sessions/<sid>/TODO.md` | 读写 | S | `TodoTalent(S, "sessions/")` | **stateSource**；按会话存放，不随 cwd 变化 | `~/.soloncode/workspaces/<key>/sessions/<sid>/TODO.md` |
| GoalRuntime.countUnfinishedTodos | 同上 | 读 | S | `todoTalent.readTodo` | stateSource，间接使用 | 同上 |
| LoopStore（harness-loop） | `tasks.json`，损坏时备份为 `tasks.json.corrupt-<ms>` | 读写、移动 | 宿主传入的来源 | `LoopExtension.Builder.store(source, path)` | 约定传 **stateSource**；不传时只存内存 | `~/.soloncode/workspaces/<key>/tasks.json` |
| GenerateTalent（harness-core） | `<hh>agents/<name>.md` | 写 | W | `engine.getWorkspaceSource().writeString` | **workspaceSource**；不随 cwd 变化，和 `@workspace-agents` 挂载是同一个位置 | `<ws>/.soloncode/agents/<name>.md` |
| TaskTalent 子任务超长结果 | `.files/task/<taskId>/result.txt` | 写 | cwd | `spillSourceOf(W, ws, cwd)` | cwd 等于 ws 且 W 在本地时用 **workspaceSource**；否则用 `FileMountSource.of(cwd)`；都没有时不落盘、只截断 | `<cwd>/.files/task/<taskId>/result.txt` |
| CommandRegistry / MarkdownCommandLoader | `**.md` 命令定义 | 读 | 宿主传入的来源 | `commandRegistry.load(source, dir)` | 工作区命令约定传 **workspaceSource** + `getHarnessCommands()` | 工作区命令：`<ws>/.soloncode/commands/`（W）；用户命令：`~/.soloncode/commands/`（`FileMountSource.of(dir, true)`） |

### 3.2 模型可见的工作区读写（直接本地，不经 workspaceSource）

| 组件 | 文件 | 读/写 | 根目录 | 经由 | 与两个来源的关系 |
|---|---|---|---|---|---|
| TerminalTalent：read / write / edit / ls / glob / grep（相对路径、`@workspace/`） | 任意工作区文件 | 读写 | cwd（`getWorkPath(__cwd)`，缺省 `MountManager.getWorkDir()`） | L（`Files.write` / `newBufferedReader`） | **不经过 W**；只在缺省情况下与 W 的根相同 |
| TerminalTalent：`@alias/...` 访问本地挂载 | 挂载目录内文件 | 读写（需挂载可写） | 挂载的本地根 | L（`MountManager.resolve`，带越界检查） | 与两个来源无关 |
| TerminalTalent：`@alias/...` 访问虚拟挂载（classpath / db / http） | 来源内文件 | 读写 | 挂载来源 | 来源自身的 `stat / list / find / openRead / openWrite` | 与两个来源无关；是唯一真正经过 MountSource 的模型文件工具路径 |
| TerminalTalent：bash | 命令所触及的任意文件 | 读写 | cwd（OS 沙盒白名单为 ws 和不在 ws 下的 cwd，另含系统临时目录，可选加用户主目录） | 子进程 | 与两个来源无关 |
| CodeTalent（talent-code） | 扫描项目树；写 `<hh>CODE.md`；如已有 `.gitignore` 则追加 `<hh>` | 读写 | cwd（`__cwd` 缺省为 ws） | L | 与两个来源无关；不在 stateSource 里 |
| LspTalent / LspClientImpl（talent-lsp） | 读源文件、给语言服务器发通知 | 读 | ws（cwd 不在 ws 内时拒绝） | L | 与两个来源无关 |
| goal 验证器 TestsPass / BuildPass | 探测 `pom.xml` / `build.gradle` / `package.json` / `pyproject.toml` 等后运行构建 | 读、执行 | cwd（`validate(condition, workDir)`） | L | 与两个来源无关 |

### 3.3 目录类与宿主注入的存储（引擎不认识具体位置）

| 组件 | 文件 | 读/写 | 根目录 | 经由 | 与两个来源的关系 | soloncode-cli 实际位置 |
|---|---|---|---|---|---|---|
| DefaultSkillCatalog（solon-ai-mount） | `**/SKILL.md`（深度不超过 3）及附属文件 | 读 | 各 SKILLS 挂载 | `source.find / openRead` | 与两个来源无关 | `@agent-skills`：`~/.agents/skills/`；`@user-skills`：`~/.soloncode/skills/`；`@workspace-skills`：`<ws>/.soloncode/skills/`（挂载未设 writable，缺省不可写；`FileMountSource.of(dir, true)` 的 `true` 是允许跟随外部链接，不是只读） |
| DefaultAgentCatalog / AgentDescriptor | `*.md` 子代理定义 | 读 | 各 AGENTS 挂载，以及内置的 `@harness-agents` | `source.find / openRead` | 与两个来源无关；`@workspace-agents` 的位置与 GenerateTalent 的写入位置相同 | `~/.soloncode/agents/`、`<ws>/.soloncode/agents/`、classpath 下的 `META-INF/solon/ai/harness/agents/` |
| MemoryTalent → MemorySolutionMdImpl / MemoryMdData（talent-memory） | `<storeKey>.md`（带 Front Matter） | 读写删 | 每个作用域一个来源 | `MemorySolutionMdImpl.of(Map<scope, MountSource>)` | **与两个来源都无关**；由宿主的 `memoryProvider(__cwd)` 决定，引擎缺省没有记忆 | user 作用域：`~/.soloncode/memory/`；local 作用域：`<cwd>/.soloncode/memory/` |
| 会话存储（sessionProvider，例如 FileAgentSession） | `<sid>.messages.ndjson`、`<sid>.snapshot.json` | 读写 | 宿主决定 | L（`File` / `Files`，原子写） | **与两个来源都无关**；但 soloncode-cli 把它和 stateSource 放在同一个父目录 | `~/.soloncode/workspaces/<key>/sessions/<sid>/` |
| GoalRuntime（harness-goal） | 目标状态 | 读写 | `session.attrs()` | sessionProvider | 跟着会话快照持久化，不直接操作文件 | 同上（在 snapshot 里） |
| queue / steer | 消息队列、转向 | — | 内存 | — | 不涉及文件 | — |

### 3.4 进程级、用户级的本地缓存与临时文件

| 组件 | 文件 | 根目录 | 说明 |
|---|---|---|---|
| MountMaterializer（talent-cli） | 虚拟挂载的物化快照 `<alias>/<fingerprint>/`，含 `.ready`、manifest、`.tmp-*` staging 目录 | `~/.soloncode/cache/mount`（取不到 `user.home` 时回退到 `<ws>/.soloncode/cache/mount`） | 让 bash 能执行 classpath 等虚拟来源里的脚本；文件工具和沙盒都禁止写入这里。目录名硬编码为 `.soloncode`，不跟随 harnessHome |
| ProcessExecutor / ShellCommandFactory | `solon-ai-script-*.bat/.ps1/...` | `java.io.tmpdir` | 超长或多行命令的临时脚本，用完即删 |
| JdtlsSupport（talent-lsp） | jdtls 的 `-data` 索引目录 `jdtls-<sha1(ws)>` | `<用户缓存目录>/solon-lsp`（macOS 为 `~/Library/Caches`，Windows 为 `%LOCALAPPDATA%`，其他系统为 `~/.cache`，没有主目录时为 `tmpdir`） | 按工作区绝对路径分开存放 |
| Websearch / Webfetch / CodeSearch / Clock / MCP / OpenAPI 网关 | — | — | 不读写本地文件 |

## 4. 关系图

```mermaid
flowchart LR
    subgraph Engine[HarnessEngine]
        S[stateSource<br/>缺省 ws/hh，宿主可换为 db/http]
        W[workspaceSource<br/>固定 FileMountSource ws]
        MM[MountManager 挂载表]
    end

    Todo[TodoTalent<br/>sessions/sid/TODO.md] --> S
    Loop[LoopStore<br/>tasks.json] --> S
    Gen[GenerateTalent<br/>hh/agents/name.md] --> W
    Task[TaskTalent 超长结果<br/>.files/task/id/result.txt] -->|cwd==ws 且本地| W
    Task -->|否则| CwdFS[FileMountSource.of cwd]
    Cmd[工作区命令<br/>hh/commands/] --> W

    Term[TerminalTalent 文件工具/bash] -->|相对路径| Local[(本地 cwd / ws)]
    Code[CodeTalent<br/>hh/CODE.md] --> Local
    Lsp[LspTalent] --> Local
    Val[goal 验证器] --> Local
    W -. 缺省同一目录 .- Local

    Term -->|@alias 虚拟来源| MM
    Skill[SkillCatalog / AgentCatalog] --> MM

    Mem[MemoryTalent] --> MemSrc[宿主 memoryProvider 的作用域来源]
    Sess[会话/goal 状态] --> SP[宿主 sessionProvider]
```

## 5. 观察与风险

1. **“模型可见”和“经过 workspaceSource”是两回事。** 模型工具、bash、LSP、CodeTalent 都直接操作本地 cwd / ws。workspaceSource 只承接 harness 自己写的四类东西：生成的子代理、子任务超长结果、工作区命令，以及经 `@workspace` 走 `resolveResource` 的场景。所以现在 workspaceSource 实际上必须是本地来源，且根目录等于 ws。引擎也没有提供替换它的 Builder 入口，这与 MountManager 注释里的警告一致。
2. **`getHarnessSessions()` 的含义已经和实际存储对不上。** 它返回相对 ws 的 `<hh>sessions/`，而 TODO 实际存在 `stateSource` 的 `sessions/` 下。两者只在缺省 stateSource 时巧合一致。宿主一旦设置了 stateSource（soloncode-cli 就设置了），它就只剩旧数据迁移的用途（`WorkspaceDataUtil` 迁移旧的 `<ws>/.soloncode/sessions/`）。建议在 Javadoc 里注明“相对工作区，不是 stateSource 内的路径”，或者改为提供 `stateSource` 内的会话前缀常量。
3. **`.soloncode` 在 talent-cli 里是硬编码的。** 沙盒强制拒绝写入的 `.soloncode/commands`、`.soloncode/agents`，物化缓存目录 `.soloncode/cache/mount`，以及 ignoreDirs，都不跟随 `harnessHome`。harness 缺省的 harnessHome 是 `.solon/`，所以其他宿主用缺省值时，`<ws>/.solon/agents`、`<ws>/.solon/commands` 不在强制拒绝之列，模型可以改写子代理和命令定义。建议把强制拒绝列表做成可由 TerminalTalent 注入的配置，引擎按 harnessHome 推送进去。
4. **CODE.md 位于 `<cwd>/<hh>`，不在 stateSource。** 它是工作区产物，模型要读，归到工作区一侧是合理的。项目已有 `.gitignore` 时，CodeTalent 追加的是整个 `<hh>` 目录（不只 CODE.md），所以缺省不进版本库；副作用是 `<hh>agents/`、`<hh>commands/`、`<hh>skills/` 也一并被忽略，团队想共享这些定义需要自己在 `.gitignore` 里反向放行。它不经过 workspaceSource，算存分离时要跟工作区一起同步。
5. **记忆和会话完全由宿主决定。** soloncode-cli 把会话放在 `~/.soloncode/workspaces/<key>/sessions/<sid>/`，和 stateSource 下的 `sessions/<sid>/TODO.md` 是**同一个目录**，因此删除会话目录时 TODO 会一并删掉，这对宿主是好事。但这是宿主自己的约定。如果 stateSource 换成远程来源，删除会话时宿主还需要另外调用 `stateSource.deleteIfExists("sessions/<sid>/TODO.md")`。引擎的 `removeSession` 只通知扩展，不清理 TodoTalent 的数据。
6. **MemoryTalent 的 local 作用域随 cwd 变化**（`<cwd>/.soloncode/memory/`），TODO 则按会话存放。这是有意的区分：记忆跟着项目走，清单跟着会话走。
7. **要做远程工作区（WorkspaceSync），需要一起同步这些内容**：工作区源码；`<hh>agents/`、`<hh>commands/`、`<hh>skills/`；`<hh>CODE.md`；`.files/`（子任务结果和宿主上传的附件）。stateSource 和记忆来源本来就可以独立放到远端，不需要同步。

## 6. 速查：按根目录归类

| 根 | 包含的文件 |
|---|---|
| **stateSource**（缺省 `<ws>/<hh>`） | `sessions/<sid>/TODO.md`、`tasks.json`（宿主按约定传入时） |
| **workspaceSource**（`<ws>`） | `<hh>agents/<name>.md`（写）、`<hh>commands/**.md`（读，宿主按约定传入时）、`.files/task/<id>/result.txt`（cwd 等于 ws 时） |
| **本地 cwd**（不经来源） | 模型的全部相对路径读写、bash、`<hh>CODE.md`、`.gitignore` 追加、`.files/task/...`（cwd 不等于 ws 时，经 `FileMountSource.of(cwd)`）、goal 构建验证 |
| **本地 ws**（不经来源） | LSP 读源文件；jdtls 的项目根 |
| **各挂载来源** | 技能（`**/SKILL.md`）、子代理（`*.md`）、`@alias` 文件访问 |
| **宿主注入** | 会话消息和快照（含 goal 状态）、记忆 `<storeKey>.md`、IM 通道绑定（`~/<hh>channels/`，宿主私有） |
| **用户级缓存 / 临时目录** | `~/.soloncode/cache/mount`、`<用户缓存目录>/solon-lsp/jdtls-*`、`java.io.tmpdir/solon-ai-script-*` |

## 7. 与 soloncode-cli 的核对记录（2026-11-06）

逐项对照 `WorkspaceManager.doCreateWorkspaceContext`、`WorkspaceDataUtil`、`SessionManager`、`MemoryProvider`、`ImGateway` 核对，第 2–6 节的位置均与宿主实际装配一致：

| 项 | 宿主代码 | 结果 |
|---|---|---|
| stateSource | `.stateSource(FileMountSource.of(WorkspaceDataUtil.dataDir(ws)))`，`DATA_ROOT=.soloncode/workspaces` | 一致 |
| 会话目录 | `SessionManager` → `FileAgentSession(sid, sessions/<sid>)`，另有 `_meta.json` | 一致（与 TODO 同目录） |
| loop | `.store(engine.getStateSource(), "tasks.json")`；迁移器仍直接操作本地 `<dataDir>/tasks.json` | 一致 |
| 命令 | 用户级 `FileMountSource.of(~/.soloncode/commands, true)`；工作区 `load(engine.getWorkspaceSource(), getHarnessCommands())` | 一致 |
| 记忆 | `MemorySolutionMdImpl.of({user: ~/.soloncode/memory, local: <cwd>/.soloncode/memory})` | 一致 |
| 技能/子代理挂载 | 另有 `@agent-skills`（`~/.agents/skills/`） | 已补入第 3.3 节 |
| IM 通道 | `ImGateway`：`<userHome>/<hh>channels/` | 一致 |

本次核对顺带修正：
1. 报告本身：技能挂载“只读 FileMountSource”表述不准；CODE.md 与 `.gitignore` 的说法自相矛盾；“stateSource 模型看不到”忽略了缺省位置在工作区内。
2. 代码回归：`05a867d8f`（2026-11-03，删除 task_result_read）把超长结果提示里的“不要重新委派该任务”删掉了，而测试和 README 仍要求有这句，`oversizedResultSpillsToFilesDir` 从那时起一直失败并被当成“既有失败”。已恢复该提示。
3. 宿主注释：`WorkspaceManager` 中“验证器在 engine.getWorkspace() 下执行”已过时（现按本轮 `__cwd`），已改。
