# solon-ai-harness-loop

solon-ai harness 引擎插件：**会话定时任务**（`/loop` 命令、`loop_*` 模型工具、宿主 API）。

每个会话可以建多条任务，按**固定间隔**、**cron** 或**一次性时刻**触发；每次到点向该会话注入一段提示词，执行一轮。任务可持久化到任意 `MountSource`（本地目录、db、http 等）。

- 包名：`org.noear.solon.ai.harness.extension.loop`
- 起始版本：4.2.0（独立模块）
- 宿主只需接触一个类：`LoopExtension`（门面 + Builder）

---

## 1. 功能介绍

### 1.1 与 goal 插件的区别

本模块与会话目标（`solon-ai-harness-goal`）是两个独立插件，不共享模型：

| 维度 | loop（本模块） | goal |
|---|---|---|
| 数量 | 每会话可多条 | 每会话一个 |
| 驱动方式 | 按时间表独立触发（间隔 / cron / 一次性） | 由目标状态驱动续轮 |
| 记录内容 | 只记最终文本（`lastResult`）与轮次 | 有预算与完成判定 |
| 执行器签名 | `LoopExecutor`（返回 `LoopOutcome`） | 另有签名，不与 loop 共用 |

### 1.2 调度与执行流程

```
到点（IJobManager 回调）/ 手动 trigger / 失败重试
  └─ 守卫：扩展已摘除？ → 放弃
         任务已删除或已被 update 替换成新版本？ → 静默放弃
         任务已暂停？ → 摘掉 job，放弃
         会话忙？（engine.isIdle(session) 为 false）→ 记日志，放弃本轮（不计错误）
  └─ 运行锁（CAS）：同一任务上一轮未结束 → 放弃本轮
  └─ 认领执行器：按注册顺序取首个谓词命中者；都不命中走缺省执行器；仍无 → skip
  └─ 结论：
         done   → 迭代 +1、记 lastResult、清零错误计数、落盘
         skip   → 本轮放弃，不计错误，不再尝试其它执行器
         failed / 抛异常 → 记 lastResult="error [类型]: 信息"，错误计数 +1；
                           未达阈值：延迟 5s × 计数 后重试；达到阈值：自动停用并摘除 job
  └─ 一次性任务：由调度触发且通过守卫的那一轮结束后自动删除（无论结论如何）
```

要点（均以源码为准）：

- **会话忙判定由插件统一完成**：调用执行器之前用 `engine.isIdle(session)` 判定（会话通过 `getSessionOrNil` 获取，不会因判定而新建会话；取不到会话时**不跳过**）。执行器只需关心「我这个通道此刻能不能跑」（如终端是否空闲）。
- **错误计数是任务级、瞬态的**：一个任务失败不影响其它任务；成功一轮即清零；不持久化。阈值经 `maxErrors` 的 `IntSupplier` 每次实时读取，实际取值 `Math.max(1, supplier.getAsInt())`。
- **退避**：第 n 次连续失败后延迟 `5 × n` 秒重试（5s、10s、15s……），重试不经调度器，不消耗一次性配额。
- **版本与身份**：`update` 会产出同 id 的新版本替换旧版本，新旧版本共享运行锁与错误计数，因此修改执行中的任务不会与在途一轮并发，本轮结果记到新版本上。
- **job 名**：`loop-<实例序号>:<sessionId>:<taskId>`。实例序号在进程内递增，多个扩展实例共用 JVM 单例 `JobManager` 也不会撞名；job 名不落盘。

### 1.3 引擎钩子

| 钩子 | 行为 |
|---|---|
| `onAttach` | 绑定引擎；启用时设置缺省执行器；注册 `/loop` 命令；加载并调度存储中的任务 |
| `onRequestAssemble` | 仅顶层请求挂 `LoopTalent`（委派请求不挂：子代理走临时会话，建出的任务会成为孤儿） |
| `contributeSessionStatus` | 贡献 `STATUS_TASKS`（只读内存计数） |
| `onSessionRemove` | 停止并删除该会话全部任务（幂等） |
| `onDetach` | 卸载全部 job，保留存储记录；此后不再写盘、不再注册 job、不再放行触发，直到下次加载 |

不实现 `onSessionInterrupt`：用户中断不会删除或暂停定时任务。

---

## 2. 依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-harness-loop</artifactId>
    <version>${solon-ai.version}</version>
</dependency>
```

本模块依赖 `solon-ai-harness-core`、`solon-scheduling-simple`、`java-cron`（cron 预校验，与 `solon-scheduling-simple` 内部同一解析器）。聚合包 `solon-ai-harness` 已包含本模块；**引入不等于装配**，仍需 `addExtensionIfAbsent`。

调度依赖 `JobManager.getInstance()`（需 `solon-scheduling-simple` 已启动）。未显式 `jobManager(null)` 却取不到 JobManager 时，构建时会打 WARN 日志：任务会被存储但永远不会触发。

---

## 3. 使用说明

### 3.1 简单示例

```java
import org.noear.solon.ai.harness.extension.loop.LoopExtension;
import org.noear.solon.ai.harness.extension.loop.LoopSpec;
import org.noear.solon.ai.harness.extension.loop.LoopTask;

// 装配：任务存到引擎状态来源下的 tasks.json；不注册执行器则走缺省执行器（engine.handle 同步跑一轮）
LoopExtension loop = engine.addExtensionIfAbsent(LoopExtension.builder()
        .store(engine.getStateSource(), "tasks.json")
        .build());

// 每 10 分钟执行一次，并在创建后立即跑首轮
LoopTask task = loop.schedule(sessionId, LoopSpec.of("检查 CI 是否通过").everyMinutes(10).runNow(true));
String taskId = task.getId();
```

### 3.2 完整示例

#### 装配（Builder 全配置）

```java
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.scheduling.simple.JobManager;

LoopExtension loop = engine.addExtensionIfAbsent(LoopExtension.builder()
        .store(engine.getStateSource(), "tasks.json")            // 或 FileMountSource.of(dir)；不设 = 只在内存
        .sessionAlive(id -> Files.isDirectory(sessionsRoot.resolve(id))) // 加载时孤儿清理
        .maxErrors(() -> settings.getLoopMaxErrors())            // 连续失败阈值，每次实时读
        .maxTasks(500)                                           // 本实例任务总量上限（跨全部会话）
        .triggerWrapper(r -> () -> withLogScope(r))              // 包装调度线程（如日志归属）
        .defaultExecutor(true)                                   // 是否启用缺省执行器
        .jobManager(JobManager.getInstance())                    // 自备调度器；传 null 关闭调度
        .build());
```

#### 注册执行器（按通道认领）

```java
// 按注册顺序认领，先于缺省执行器；谓词为 null 表示认领全部
loop.addExecutor("cli", "cli"::equals, (sid, prompt) -> {
    if (!terminalIdle()) {
        return LoopOutcome.skip("terminal busy");      // 本轮跳过，不计错误
    }
    return LoopOutcome.done(runRound(sid, prompt));    // 抛异常 = 失败（计错误、退避、达阈值停用）
});

loop.addExecutor("web", id -> id.startsWith("web-"), (sid, prompt) ->
        runtimeReady() ? LoopOutcome.done(runWeb(sid, prompt))
                       : LoopOutcome.failed("runtime not ready")); // 与抛异常等价
```

执行器不得返回 `null`，否则按失败计（`lastResult` 中可见）。

#### 创建任务（`schedule`）

```java
// 固定间隔：[1, 1440] 分钟；everyMinutes(null) = 缺省 5 分钟
loop.schedule(sid, LoopSpec.of("check deploy").everyMinutes(15));

// cron：6 或 7 位（秒 分 时 日 月 周 [年]），日与周必须有一个为 ?
loop.schedule(sid, LoopSpec.of("daily report").cron("0 0 22 * * ?"));

// cron + 一次性：首次触发执行一轮后自动删除
loop.schedule(sid, LoopSpec.of("年终提醒").cron("0 0 9 31 12 ? 2026").oneShot(true));

// 一次性时刻（服务器本地时间），自动转为带年份的 7 位 cron 并标记 oneShot
loop.schedule(sid, LoopSpec.of("remind me").at(LocalDateTime.of(2026, 12, 5, 18, 0)));

// 请求参数可原样传入：null（及空白 cron）即未设置
LoopTask t = loop.schedule(sid, LoopSpec.of(req.prompt)
        .everyMinutes(req.interval).cron(req.cron).runNow(req.runNow).oneShot(req.oneShot));
```

调度方式优先级 `at > cron > everyMinutes`。校验失败抛 `IllegalArgumentException`，任务总数达上限抛 `IllegalStateException`，失败时不会留下半个任务。

#### 修改任务（`update` + `LoopPatch`）

```java
// null = 保持原值；启停、迭代、最近结果、一次性属性与扩展字段都保留
LoopTask u = loop.update(sid, taskId, LoopPatch.create()
        .prompt(req.prompt)            // null 或空白 = 保持
        .everyMinutes(req.interval)    // null = 保持；越界抛 IllegalArgumentException
        .cron(req.cron)                // null = 保持；"" = 清除 cron，回到固定间隔
        .runNow(req.runNow));          // null = 保持
if (u == null) {
    // 任务不存在
}

// 从 cron 切回固定间隔
loop.update(sid, taskId, LoopPatch.create().cron("").everyMinutes(30));
```

非法补丁抛 `IllegalArgumentException`，原任务保持不变（先校验、后替换）。

#### 启停、触发、删除、查询

```java
loop.pause(sid, taskId);      // 暂停：保留记录、摘除 job；已暂停则不动
loop.resume(sid, taskId);     // 恢复：清零错误计数并重新注册 job；已启用则不动
loop.toggle(sid, taskId);     // 启停切换
loop.trigger(sid, taskId);    // 立即异步执行一轮（仍经守卫；不消耗一次性配额）
loop.remove(sid, taskId);     // 删除单个任务
loop.removeAll(sid);          // 停止并删除本会话全部任务

LoopTask one = loop.get(sid, taskId);                  // 不存在为 null
List<LoopTask> mine = loop.list(sid);                  // 本会话全部（含已暂停）
Map<String, List<LoopTask>> all = loop.listAll();      // 全部会话，按 sessionId 排序
boolean active = loop.hasActiveTasks();                // 是否有启用中的任务（闲置回收判定用）

Map<String, Object> view = one.toMap();                // 标准展示视图（不含扩展字段）
int count = engine.status(session).get(LoopExtension.STATUS_TASKS);
```

`pause` / `resume` / `toggle` / `trigger` / `remove` 返回 `false` 表示任务不存在。

#### 宿主扩展字段（extra）

```java
import org.noear.snack4.ONode;

LoopTask t = loop.schedule(sid, LoopSpec.of("日报")
        .cron("0 0 18 * * ?")
        .extra("automation", new ONode().set("id", "a1").set("name", "日报")));

String name = t.getExtra("automation").get("name").getString();

// 只改列出的键；value 为 null 表示删除该键
loop.update(sid, t.getId(), LoopPatch.create()
        .extra("automation", new ONode().set("id", "a2"))
        .extra("legacy", null));
```

扩展字段随任务原样持久化（插件不解析）；键为空或与核心字段同名时抛 `IllegalArgumentException`。

#### /loop 命令与模型工具

装配后 `/loop` 命令自动注册（语法见 4.8，宿主接入见第 8 章），顶层请求自动挂上 `loop_add` / `loop_list` / `loop_control` 工具（见 4.7）。

```text
/loop 5m check if deployment finished
/loop cron:"0 */5 * * * ? *" check status
/loop ls
```

`loop.setEnabled(false)` 只关闭模型工具；`/loop` 命令与已有定时任务照常运行。

#### 把任务存到非本地来源

`store` 接收任意 `MountSource`。测试或演示可用内存来源：

```java
import org.noear.solon.ai.mount.source.InMemoryMountSource;

InMemoryMountSource source = new InMemoryMountSource();
LoopExtension loop = engine.addExtensionIfAbsent(LoopExtension.builder()
        .store(source, "loop/tasks.json")
        .build());
```

远端（db、http 等）存储：自行实现 `org.noear.solon.ai.mount.source.MountSource` 并传入 `store(...)` 即可。本插件对来源的使用包括 `normalize`、`readStringOrNull`、`writeString`（整体写入）以及损坏备份时的 `move`；写入原子性取决于该来源的 `capabilities().isAtomicWrite()`。

#### 测试中使用

```java
// 关闭调度（任务仅作元数据）与缺省执行器，不依赖 Solon 容器
LoopExtension loop = LoopExtension.builder().jobManager(null).defaultExecutor(false).build();
```

---

## 4. 配置说明

### 4.1 Builder 选项

| 方法 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `store(source, path)` | `MountSource`, `String` | 不设置（只在内存） | 任务存储来源与来源内路径；`source` 为 null = 只在内存；`source` 非 null 时 `path` 不可为空，否则 `build()` 抛 `IllegalArgumentException` |
| `sessionAlive(predicate)` | `Predicate<String>` | 恒 `true` | 加载时孤儿判定：返回 false 的会话其任务不再恢复并从存储删除；谓词抛异常按存在处理；传 null 忽略 |
| `maxErrors(supplier)` | `IntSupplier` | `() -> 3`（`DEFAULT_MAX_ERRORS`） | 任务级连续失败阈值，每次实时读取，实际取值不小于 1；传 null 忽略 |
| `maxTasks(max)` | `int` | `200`（`DEFAULT_MAX_TASKS`） | 本扩展实例任务总量上限（跨全部会话），小于 1 时取 1 |
| `triggerWrapper(wrapper)` | `UnaryOperator<Runnable>` | 无（原样执行） | 包装每一轮执行（调度线程无宿主上下文时用于打标）；包装结果为 null 时退回原 Runnable |
| `defaultExecutor(enabled)` | `boolean` | `true` | 是否启用缺省执行器：`engine.handle(HarnessInput.ofProgram(session, prompt, "loop")).call()`，取最终文本 |
| `jobManager(jobManager)` | `IJobManager` | `JobManager.getInstance()` | 自备调度器；显式传 null = 关闭调度（任务仅作元数据，不告警） |

### 4.2 LoopSpec（创建参数）

入口：`LoopSpec.of(String prompt)`。所有可选项都接受 null。

| 方法 | 类型 | 默认 | 规则 |
|---|---|---|---|
| `of(prompt)` | `String` | 必填 | 为空或空白抛 `IllegalArgumentException`；保存前 trim |
| `everyMinutes(minutes)` | `Integer` | 5（`DEFAULT_INTERVAL_MINUTES`） | 须在 [1, 1440]，否则抛异常；设置了 cron 或 at 时不生效 |
| `cron(cron)` | `String` | 不用 | null/空白 = 不用；6 或 7 位；无法解析或已无未来触发时刻抛异常 |
| `at(at)` | `LocalDateTime` | 不用 | 服务器本地时间，须为未来时刻；转为 `0 分 时 日 月 ? 年`，自动 oneShot |
| `oneShot(oneShot)` | `Boolean` | false | 为 true 时必须有 at 或 cron |
| `runNow(runNow)` | `Boolean` | false | 创建后立即执行首轮（仅固定间隔生效）；与一次性互斥 |
| `extra(key, value)` | `String`, `ONode` | 无 | 宿主扩展字段；value 为 null 表示不写；键非法抛异常 |

`schedule` 时统一校验，失败抛 `IllegalArgumentException`：prompt 为空、间隔越界、at 不是未来时刻、一次性却没有 at/cron、一次性又要求 runNow、cron 无法解析或已无未来触发时刻、sessionId 为空。扩展字段键非法在调用 `extra` 时即抛出。

### 4.3 LoopPatch（修改参数）

入口：`LoopPatch.create()`。

| 方法 | 类型 | null 语义 | 其它规则 |
|---|---|---|---|
| `prompt(prompt)` | `String` | 保持 | 空白同样视为保持；保存前 trim |
| `everyMinutes(minutes)` | `Integer` | 保持 | 越界 [1, 1440] 抛 `IllegalArgumentException` |
| `cron(cron)` | `String` | 保持 | 空串 = 清除 cron、回到固定间隔；新 cron 无法解析或不可触发时抛异常 |
| `runNow(runNow)` | `Boolean` | 保持 | 只更新记录上的标志；修改不会触发立即执行（立即首轮只在创建时生效） |
| `extra(key, value)` | `String`, `ONode` | value 为 null = 删除该键 | 只改列出的键；键非法抛异常 |

保留不变：任务 ID、创建时间、启停状态、迭代数、最近结果、一次性属性、未列出的扩展字段。修改后若任务为启用状态，会以新定义重新注册 job；cron 在注册时已不可触发则任务被自动停用并记 WARN。

### 4.4 LoopTask（只读快照）

`LoopTask` 对宿主只读：无公开构造器，无公开修改方法。

| 读取方法 | 类型 | 说明 |
|---|---|---|
| `getId()` | `String` | 任务 ID（新任务为 32 位小写 hex，`HarnessIds.newId()`） |
| `getPrompt()` | `String` | 每轮执行的提示词 |
| `getIntervalMinutes()` | `int` | 固定间隔分钟数（cron 模式下忽略） |
| `getCron()` | `String` | cron 表达式，可能为 null |
| `isCronMode()` | `boolean` | cron 非空即为 cron 模式 |
| `getCreatedAt()` | `Instant` | 创建时间 |
| `isRunNow()` | `boolean` | 是否创建后立即执行 |
| `isOneShot()` | `boolean` | 是否一次性 |
| `isEnabled()` | `boolean` | 是否启用（false = 已暂停或被自动停用） |
| `isCancelled()` | `boolean` | 是否已删除 |
| `isRunning()` | `boolean` | 是否正在执行（瞬态） |
| `getCurrentIteration()` | `int` | 成功轮次数 |
| `getLastResult()` | `String` | 最近一轮结果；失败时为 `error [类型]: 信息` |
| `getLastExecutedAt()` | `Instant` | 最近执行时间 |
| `getExtra(key)` | `ONode` | 宿主扩展字段，不存在为 null |
| `toMap()` | `Map<String, Object>` | 标准展示视图 |
| `toONode()` / `fromONode(node)` | `ONode` | 序列化 / 反序列化（不含 sessionId） |

`toMap()` 字段（按顺序，不含扩展字段）：

| 键 | 出现条件 | 值 |
|---|---|---|
| `id` | 总是 | 任务 ID |
| `prompt` | 总是 | 提示词 |
| `intervalMinutes` | 总是 | 间隔分钟 |
| `cron` | 仅 cron 模式 | cron 表达式 |
| `oneShot` / `runNow` / `enabled` / `cancelled` / `running` | 总是 | boolean |
| `currentIteration` | 总是 | int |
| `lastResult` | 有值时 | 最近结果 |
| `lastExecutedAt` | 有值时 | ISO-8601 字符串 |

### 4.5 LoopOutcome（执行结论）

| 构造 | 判定方法 | 调度器处理 |
|---|---|---|
| `LoopOutcome.done(finalText)` | `isDone()` | 记成功：迭代 +1、`lastResult` = 文本（null 记为空串）、清零错误计数 |
| `LoopOutcome.skip(reason)` | `isSkipped()` | 本轮放弃：不计错误、不再尝试其它执行器，只记日志 |
| `LoopOutcome.failed(error)` | `isFailed()` | 记失败：与抛异常等价，错误计数 +1、退避重试、达到阈值自动停用 |

`getText()`：done 时为最终文本，skip/failed 时为原因。无执行器认领时等同 skip（`no executor claimed`），绝不记成成功。

`LoopExecutor` 签名：`LoopOutcome handle(String sessionId, String prompt) throws Exception`。

### 4.6 状态键

| 常量 | 定义 | 说明 |
|---|---|---|
| `LoopExtension.STATUS_TASKS` | `SessionStatus.Key.count("loop.tasks")` | 本会话任务数（含已暂停）；经 `engine.status(session).get(...)` 读取，只供展示与宿主业务，内核不读 |

### 4.7 模型工具（LoopTalent）

只挂在顶层请求上（委派不挂）。会话 ID 由框架注入（`__sessionId`），无活跃会话时返回 `ERROR:` 文本。所有成功响应为 JSON，附带 `serverNow`（`yyyy-MM-dd'T'HH:mm:ss`）与 `timezone`，供模型换算相对时间。

| 工具 | 参数 | 说明 |
|---|---|---|
| `loop_add` | `prompt`（必填）；`at`、`intervalMinutes`、`cron`、`oneShot`、`runNow`（可选） | 新增任务，返回 `taskId`、`schedule`（cron 模式含 `cron` 与 `nextFireTime`，否则含 `intervalMinutes`）、`oneShot`、`message` |
| `loop_list` | 无 | 返回 `tasks` 与 `total`；每项含 `taskId`、`prompt`（截断 120 字符）、`cron` 或 `intervalMinutes`、`oneShot`、`enabled`、`running`、`lastExecutedAt`、`lastResult`（截断 300 字符） |
| `loop_control` | `action`（`stop` / `pause` / `resume` / `trigger`）、`taskId` | `stop` 删除任务；`pause` / `resume` 启停；`trigger` 异步执行一轮（已暂停的任务拒绝触发） |

`loop_add` 参数规则：

| 参数 | 格式 / 规则 |
|---|---|
| `at` | `yyyy-MM-ddTHH:mm`（如 `2026-09-05T18:00`），须为未来时刻；提供后忽略 `cron` / `intervalMinutes`，强制 `oneShot=true` |
| `intervalMinutes` | 固定间隔，默认 5；提供 `cron` 时以 `cron` 为准 |
| `cron` | 工具描述引导模型写 7 位（秒 分 时 日 月 周 年）；实际校验与宿主相同（6 或 7 位） |
| `oneShot` | 默认 false；为 true 时必须有 `at` 或 `cron` |
| `runNow` | 默认 false；与 `oneShot=true` 互斥 |

### 4.8 /loop 命令（LoopCommand）

| 用法 | 说明 |
|---|---|
| `/loop` 或 `/loop ls` | 列出本会话任务（状态 paused / running / idle、调度、迭代、最近结果） |
| `/loop <interval> <prompt>` | 固定间隔任务；interval 如 `30s`、`5m`、`2h` |
| `/loop <prompt>` | 首个参数不是间隔时，按缺省 5 分钟 |
| `/loop cron:"<expr>" <prompt>` | cron 任务（引号可为双引号或单引号） |
| `--now` | 附加在参数中，创建后立即执行首轮；其它 `--xxx` 提示 Unknown flag |
| `/loop stop <id>` | 删除任务；若该任务正在执行，同时中断会话当前一轮 |
| `/loop stop-all` | 删除本会话全部任务；若有任务正在执行，同时中断会话 |
| `/loop pause <id>` / `/loop resume <id>` | 暂停 / 恢复 |

间隔单位：`s` / `sec` / `seconds`（向上取整为分钟，至少 1 分钟）、`m` / `min` / `minutes`、`h` / `hour` / `hours`；结果收敛到 [1, 1440] 分钟。命令没有 `trigger` 子命令，也不能创建一次性任务（需用宿主 API 或 `loop_add`）。

---

## 5. 持久化格式与兼容

存储内容（`store` 指定路径，如 `tasks.json`）：

```json
{
  "version": 2,
  "tasks": [
    {
      "id": "3f2a...（32 位 hex）",
      "prompt": "daily report",
      "intervalMinutes": 5,
      "cron": "0 0 22 * * ?",
      "createdAt": "2026-11-06T02:00:00Z",
      "autoInterval": false,
      "cancelled": false,
      "enabled": true,
      "lastResult": "...",
      "lastExecutedAt": "2026-11-06T14:00:00Z",
      "currentIteration": 3,
      "oneShot": true,
      "automation": { "id": "a1" },
      "sessionId": "web-1"
    }
  ]
}
```

| 规则 | 说明 |
|---|---|
| 写入时机 | 每次变更（创建、修改、启停、删除、一轮结束、出错）全量写入；写入串行化 |
| 可选字段 | `cron`、`lastResult`、`lastExecutedAt` 有值才写；`runNow`、`oneShot` 仅为 true 时写 |
| 不持久化 | 运行锁 `running`、连续错误计数 |
| 读取缺省 | 缺 `enabled` 视为 true；缺 `currentIteration` 为 0；缺 `runNow` / `oneShot` / `cancelled` / `autoInterval` 为 false |
| 必填字段 | `id`、`prompt`、`intervalMinutes`、`createdAt`；缺失则解析失败，按外来条目处理 |
| 本插件条目 | `type` 缺省、为空、`LOOP` 或 `HEARTBEAT`（历史取值，不区分大小写） |
| 外来条目 | 其它 `type`（如 `GOAL`）、缺 `sessionId`、解析失败的条目：不参与调度，之后每次写入都原样写回 |
| 扩展字段 | 不认识的顶层字段收进 extras，原样写回 |
| 已删除任务 | `cancelled` 的任务不写入；加载时也不恢复 |
| 旧 ID | 新任务为完整 32 位 hex ID；历史数据中的短 ID（如 8 位截断 ID）仍按原值加载与寻址 |
| 孤儿清理 | 加载时 `sessionAlive` 返回 false 的会话，其任务被删除并写回存储（外来条目保留，不会以空快照清空） |
| 损坏文件 | 内容无法解析或结构不对（根不是对象、无 `tasks` 数组）：先改名备份为 `<path>.corrupt-<毫秒时间戳>`，再按空列表继续 |
| 只读模式 | 读取发生 IO 错误，或损坏文件备份失败：本进程不再写入，避免覆盖可救回的数据 |
| 空文件 | 空白内容视为无任务，不备份 |
| 原子性 | 取决于来源的 `MountCapabilities#isAtomicWrite()` |

加载在 `onAttach` 中进行且幂等；扩展摘除（`onDetach`）后允许再次挂接时重新加载。

---

## 6. API 速查

| 方法 | 返回 | 说明 |
|---|---|---|
| `LoopExtension.builder()` | `Builder` | 构建入口 |
| `schedule(sessionId, LoopSpec)` | `LoopTask` | 新建并调度；`IllegalArgumentException` / `IllegalStateException` |
| `update(sessionId, taskId, LoopPatch)` | `LoopTask` | 按补丁修改；null = 不存在；`IllegalArgumentException` |
| `pause(sessionId, taskId)` | `boolean` | 暂停；false = 不存在 |
| `resume(sessionId, taskId)` | `boolean` | 恢复并清零错误计数；false = 不存在 |
| `toggle(sessionId, taskId)` | `boolean` | 启停切换；false = 不存在 |
| `trigger(sessionId, taskId)` | `boolean` | 立即异步执行一轮；false = 不存在 |
| `remove(sessionId, taskId)` | `boolean` | 删除；false = 不存在 |
| `removeAll(sessionId)` | `void` | 删除本会话全部任务 |
| `get(sessionId, taskId)` | `LoopTask` | 不存在为 null |
| `list(sessionId)` | `List<LoopTask>` | 本会话全部任务（含已暂停） |
| `listAll()` | `Map<String, List<LoopTask>>` | 全部会话，按 sessionId 排序 |
| `hasActiveTasks()` | `boolean` | 是否存在启用且未删除的任务 |
| `addExecutor(channel, claim, handler)` | `LoopExtension` | 注册通道执行器（可链式）；handler 为 null 时忽略 |
| `setEnabled(Boolean)` | `void` | 只开关模型工具 |
| `STATUS_TASKS` | `SessionStatus.Key<Integer>` | 会话任务数状态键 |
| `DEFAULT_MAX_TASKS` / `DEFAULT_MAX_ERRORS` | `int` | 200 / 3 |
| `LoopSpec.DEFAULT_INTERVAL_MINUTES` | `int` | 5 |

---

## 7. 注意事项

1. **用 `addExtensionIfAbsent` 装配**：按类型幂等；重复 `addExtension` 会二次 `onAttach`。
2. **会话忙时本轮直接放弃**：不计错误、不重试、也不消耗一次性配额，等下一次到点。执行器无需自行判断会话忙。
3. **同一任务不会并发**：上一轮未结束时到点的触发被丢弃。
4. **一次性任务**：由调度触发且通过守卫后，无论 done / skip / failed 都会被删除；`trigger` 手动执行不消耗配额。
5. **`runNow` 只在创建时生效**：加载恢复、恢复启用、修改后重新注册时都按完整间隔延迟首轮；cron 模式下 `runNow` 不起作用。
6. **cron 在恢复路径不会拒绝**：加载、修改、恢复启用时若 cron 已无未来触发时刻，任务被自动停用并记 WARN；创建入口则直接抛异常。
7. **自动停用后需手动恢复**：连续失败达阈值后任务 `enabled=false`，调用 `resume` 会清零计数并重新调度。
8. **时间为服务器本地时区**：`at`、cron、`loop_add` 的 `serverNow` 均基于 JVM 默认时区。
9. **删除会话**：经 `engine.removeSession(sessionId)` 触发 `onSessionRemove`，自动删除该会话全部任务。
10. **无 JobManager**：未显式关闭却取不到时只告警，任务只存不跑；测试中请显式 `jobManager(null)`。
11. **`setEnabled(false)` 不停任务**：只关闭 `loop_*` 工具，`/loop` 与已有任务继续运行；要停任务请 `pause` / `remove`。
12. **缺省执行器**：以程序输入（`HarnessInput.ofProgram`，来源 `loop`）进入引擎，提示词不解析命令与 @提及；执行失败抛给调度器计错误。

---

## 8. 命令路径：/loop 与宿主协作

### 8.1 命令的边界

| 项 | 说明 |
|---|---|
| 注册 | `LoopExtension#onAttach` 自动注册，宿主不用注册 |
| 会话 | 命令作用于 `ctx.getSession().getSessionId()`，即本次输入所在会话 |
| 结局 | 恒为 `Kind.REPLY`：命令只改任务表并回执，不在本次 `handle` 里开流 |
| 渠道 | `terminalOnly() == false`，Web / IM / 桌面可用；不需要 `.terminal(true)` |
| 忙态 | `runnableWhenBusy()` 为缺省 `false`：任务运行中输入 `/loop ...`，`engine.admit` 给出 `DEFER`（排队，空闲后执行）；没装 queue 时给出 `REJECT("busy")` |
| 开关 | `loop.setEnabled(false)` 只关模型工具，`/loop` 照常可用 |
| 回执格式 | 缺省的缓冲式命令上下文会去掉 ANSI 颜色，回执从 `outcome.getCommandOutput()` 取回 |
| 任务 ID | `/loop ls` 与创建回执里打印完整 ID；`stop` / `pause` / `resume` 需要传完整 ID |

回执示例（实际文案以命令输出为准）：

```text
/loop 30m 检查 CI 是否通过 --now      # 每 30 分钟，并立即跑首轮
/loop cron:"0 0 9 * * ?" 汇总昨日告警  # 每天 9 点
/loop ls                               # 列出本会话任务
/loop pause <id>  /  /loop resume <id>
/loop stop <id>   /  /loop stop-all
```

### 8.2 宿主接入：完整示例

`/loop` 只负责"建任务"；任务到点后由**执行器**跑一轮，执行器的输出要由宿主送回渠道。所以宿主要接两处：输入入口（让命令能执行）与执行器（让到点的那一轮有去处）。

```java
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.extension.loop.LoopExtension;
import org.noear.solon.ai.harness.extension.loop.LoopOutcome;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.turn.AdmitDecision;
import org.noear.solon.ai.harness.turn.InputGate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ImLoopHost {
    private final HarnessEngine engine;
    private final QueueExtension queue;
    private final LoopExtension loop;
    /** 会话最近一次的回复路由（定时轮次没有入站消息，只能回到这里） */
    private final Map<String, InputOrigin> routes = new ConcurrentHashMap<>();

    public ImLoopHost(HarnessEngine engine) {
        this.engine = engine;
        this.queue = engine.addExtensionIfAbsent(new QueueExtension());
        this.loop = engine.addExtensionIfAbsent(LoopExtension.builder()
                .store(engine.getStateSource(), "tasks.json")       // 任务随引擎状态来源持久化
                .sessionAlive(this::sessionExists)                   // 按会话存储判断（勿用内存会话表，重启后会误删）
                .build());

        // 执行器：认领 IM 会话（谓词应互斥），把到点的一轮跑完并送回渠道
        loop.addExecutor("im", id -> id.startsWith("im-"), (sid, prompt) -> {
            AgentSession session = engine.getSessionOrNil(sid);
            InputOrigin route = routes.get(sid);
            if (session == null || route == null) {
                return LoopOutcome.skip("no session or reply route");  // 不计错误
            }
            try {
                // 程序输入：不识别命令与 @提及，来源标记为 loop
                String text = engine.handle(HarnessInput.ofProgram(session, prompt, "loop"))
                        .call().getContent();
                reply(route, text);
                return LoopOutcome.done(text);
            } finally {
                drain(session);   // 这一轮占用期间用户发来的消息被延后了：轮次结束立即续发
            }
        });
    }

    /** 输入入口：/loop 命令与普通消息共用 */
    public void onMessage(AgentSession session, String text, InputOrigin origin) {
        AdmitDecision admit = engine.admit(session, text, false, engine::isBusyBypassCommand);
        switch (admit.getKind()) {
            case REJECT:
                reply(origin, admit.getRejectDetail());
                return;
            case DEFER:   // 任务（含 loop 的一轮）运行中发来的 /loop：排队，空闲后按命令执行
                int pos = queue.enqueue(session, text, origin);
                reply(origin, pos < 0 ? "排队失败，请稍后再试" : "当前任务结束后执行（第 " + pos + " 位）");
                return;
            default:
                break;
        }

        routes.put(session.getSessionId(), origin);   // 记下回复路由，供定时轮次使用

        boolean acquired = admit.getTicket().isAcquired();
        try (InputGate.Ticket ticket = admit.getTicket()) {
            InputOutcome outcome = engine.handle(HarnessInput.of(session, text).origin(origin));
            if (outcome.getKind() == InputOutcome.Kind.REPLY) {
                reply(origin, outcome.getCommandOutput());          // /loop 的回执
            } else if (outcome.getKind() == InputOutcome.Kind.TASK) {
                reply(origin, outcome.getRequest().call().getContent());
            }
        } catch (Throwable e) {
            reply(origin, "处理失败：" + e.getMessage());
        } finally {
            if (acquired) {
                drain(session);
            }
        }
    }

    private void drain(AgentSession session) {
        queue.drainLoop(session, s -> !engine.status(s).isOccupied(), claim -> {
            onMessage(session, claim.getItem().getText(), claim.getItem().getOrigin());
            return true;
        });
    }

    /** 宿主永久删除会话：先通知引擎（loop 会删掉该会话全部任务），再删存储 */
    public void deleteSession(String sessionId) {
        engine.removeSession(sessionId);
        routes.remove(sessionId);
        // ... 移出内存、删除存储
    }

    private boolean sessionExists(String sessionId) { /* 查会话存储 */ return true; }
    private void reply(InputOrigin origin, String text) { /* 按 origin 回投渠道 */ }
}
```

### 8.3 协作要点

1. **命令建任务，执行器跑任务**：`/loop` 与 `loop_add`、`loop.schedule(...)` 建出的是同一种任务；到点后统一经执行器执行，命令本身从不开流。
2. **执行器决定输出去向**：缺省执行器只把结果写进 `lastResult`，不会推给任何渠道。需要把每一轮推给用户的宿主必须注册自己的通道执行器，并自行保存会话到渠道的回复路由。
3. **会话忙由插件判**：到点时插件先判 `engine.isIdle(session)`，忙（含有积压）则本轮放弃、不计错误；执行器只回答"本通道此刻能不能跑"，不能就返回 `skip`。
4. **轮次结束要续发队列**：loop 的一轮会占用会话，期间用户发来的消息被 `DEFER` 进队列；执行器结束时必须 `drain`，否则这些消息要等下一次用户输入才会发出。
5. **`/loop` 在忙时被延后**：它不是忙态旁路命令。任务（包括 loop 自己的一轮）运行中输入的 `/loop stop <id>` 会排队，等这一轮结束后才执行，此时任务已不在运行，命令只删除任务、不会中断。要立刻停掉正在跑的一轮，用宿主的停止入口 `engine.interrupt(session)`，再 `loop.remove(sid, taskId)`。不经 `admit`、直接在忙时执行命令的宿主（如命令行终端）才会走到命令里"顺带中断"的分支。
6. **中断不删任务**：`engine.interrupt` 只停当前一轮，定时任务照常保留，下次到点继续。
7. **删除会话要先调 `engine.removeSession`**：它回调 `onSessionRemove`，删除该会话的全部任务并注销 job；顺序颠倒时，定时任务可能在删除过程中触发并把会话重新建出来。
8. **一次性任务不能用命令建**：`/loop` 只支持间隔与 cron；一次性提醒用 `loop.schedule(sid, LoopSpec.of(...).at(...))` 或模型工具 `loop_add` 的 `at` 参数。
