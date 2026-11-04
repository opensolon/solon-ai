### vNEXT

* **宿主接入面收敛：Session* 内核类改名 + 引擎级门面补齐**（配套 README §5 全量同步；取代此前「静态工具类 + instanceof 遍历」的接入方式）:
  * **改名清单（零行为变更，纯重命名 + 包内迁移）**：`SessionQueue → QueueHelper`、`SessionQueueItem → QueueItem`、`SessionQueueDrainer → 并入 QueueHelper.drainOne`、`SessionInterrupt → InterruptHelper`（迁至 `session/interrupt/`）；静态方法签名与语义均未变，仅类名与归属包。规约沉淀在 harness `CODE.md`（`Session*` 前缀只留给 `AgentSession`/`SessionStatus`）
  * **新增 `HarnessEngine.getExtension(Class<T>)`**：替代 `getExtensions() + instanceof` 遍历（此前宿主取 QueueExtension/SteerExtension/GoalExtension 只能强转，WebSettingsController 的 instanceof 循环已改直取）
  * **中断四形态**（`InterruptHelper` + 引擎对应入口）：`interrupt`（清队列+停流+补消息，原有）/ 新增 `interruptStream`（只停流，推代数，不写历史——goal 轮次被顶掉时用，避免假「用户已取消」）/ 新增 `interruptStreamActive`（只停流且不推代数——新一轮腾位，避免旧流把新轮收尾牵连）/ 既有 `writeCancel`（只补取消消息，Web 崩溃恢复用）。四入口语义对照表已补进 README §5.4
  * **新增 `HitlCard`（input 包）**：`HITLTask` 的稳定投影值对象。`list/undecided/next/pendingCount` 四个静态入口收拢了宿主四处自写的「首个未决策项、整批已决策回退首项」判定（旧 `HITL.getPendingTasks + getDecision` 过滤不再需要）；`isBash/getCommand` 供 CLI 确认界面直接用；宿主不再接触 React 引擎内部实体 `HITLTask`
  * **新增 `SessionStatus`（session 包）**：`isBusy/hasPendingHitl/queuedCount` 一次读全，取代宿主自拼 `SessionRuns.isBusy || HITL.isHitl || pendingSize>0`；入口 `engine.status(session)`
  * **SteerExtension 补齐**：实例方法 `activeRunId(session)`（取代宿主直读 `SteerInterceptor.ATTR_ACTIVE_RUN_ID`）、`handover(session)`（把「残留插话转排队」的 12 行 attrs 内务收进 harness，WebSessionRunner 原地删）；静态访问器 `maxTextLength/maxIdLength/isSteerMessage/stripSteerPrefix`（取代宿主引用内部常量，LastTraceService 的元数据嗅探改语义化调用）
  * **QueueExtension 补齐**：`generation/isGenerationActive/requeueFront/importItems`（WebQueueDispatcher 两阶段 drain 的代际校验不再穿透静态内核）
  * **宿主修复（本次发现的三处行为收窄）**：`CliShell:450/486`、`DesktopStreamTurn:164` 的 HITL 判据由 `status().hasPendingHitl()` 改为 `HitlCard.next() != null`——残留决策（整批已决策但续流未发生，如崩溃恢复窗口）也会进审批循环透传给引擎，不再直接 done 掉导致决策永远等不到续流
  * **同步**：README 11 处旧类名、§5.4 四形态对照表、FAQ 18 重写（「停当前留队列」从权衡题变为 `interruptStream` 正解）、模块图；harness `CODE.md` 新建（命名规约 + 门面分层 + 域边界提醒）；`InputOrigin` javadoc 同步
  * **验证**：harness `mvn clean install` 通过（38 例失败经 `git stash` 基线比对全部为既有，与本轮无关）；cli `mvn clean test-compile` 零错误；定向契约测试 16 例全绿（DesktopStreamTurnCancelTest 3 + SessionActivityTest 3 + SteerCancelWebContractTest 2 + TaskQueueDockWebContractTest 3 + ImBusySteerContractTest 5）

* **Mcp 服务端：未配置传输安全校验器时启动 warn 提示，并补上 `ServerHttpHeaderValidator` 接入**:
  * **背景**：`mcp-core` 已将 `ServerHttpHeaderValidator` 定为 `ServerTransportSecurityValidator` 的替代接口，但 `solon-ai-mcp` 的三个 Web 传输实现仍只认旧接口；用户按新接口实现校验器会被**静默忽略**，实际生效的是 NOOP，且启动日志完全看不出“到底有没有校验”
  * **新增 `HttpHeaderValidatorAdapter`**（`org.noear.solon.ai.mcp.server`）：把 `ServerHttpHeaderValidator` 适配为旧接口，内置大小写不敏感的 `MapHeaderAccessor`
  * **`McpServerEndpointProvider`**：新增 `Builder.httpHeaderValidator(ServerHttpHeaderValidator)`（刻意**不**重载 `securityValidator`，否则同时实现两接口的 `DefaultServerTransportSecurityValidator` 会调用歧义）；`Builder` 与 `Properties` 构造器的默认值均保持为 `NOOP` 不变（判定只看哨兵常量，无需区分“没配”与“显式配了 NOOP”），故对既有调用方零影响
  * **启动提示**：新增 `McpServerHost.warnIfNoop(...)` 统一判定与文案（`isNoop` 一并从适配器移入此处，适配器只负责接口转换），`StatefulMcpServerHost` / `StatelessMcpServerHost` 在既有 INFO 之后各调一行；仅 HTTP 通道提示（stdio 无 HTTP 头），带 name/channel/endpoint 与建议方案；`build()` 本身幂等，故单实例只提示一次
  * **`McpPlugin`**：`@McpServerEndpoint` bean 若实现新接口，走 `httpHeaderValidator` 接入（旧接口分支保留在前）
  * **验证**：新增 `HttpHeaderValidatorAdapterTest` 3 例（新接口适配生效/大小写不敏感/缺头拦截、哨兵 NOOP 判定、header 访问语义）；`McpStartTest`/`McpPropsTest(2)`/`McpContextPathTest`/`McpHttpAuth2Test` 均绿（24 例）；实测启动日志中 17 个 HTTP 端点告警 15 条，已配校验器的 `McpServerAuth2` 与两个 stdio 端点均不告警；`McpHttpClientMixTest7_sse/10_stateless` 的 `prompt1` 失败经 `git stash -u` 基线对照复现，属既有问题

* **会话域命令自包含：GoalCommand/QueueCommand/SteerCommand/InterruptCommand 迁出 command.builtin，随域包就位（消除 command.builtin ↔ session.* 双向引用环）**:
  * **动机**：域包通过 Extension 装配命令（GoalExtension import GoalCommand…），命令又 import 域类，形成集中在装配点的双向环。每个能力自包含（域 + 命令 + Extension）后，`command.builtin` 只留通用基础命令，职责名副其实
  * **移动**：GoalCommand→`session.goal`、QueueCommand→`session.queue`、SteerCommand→`session.steer`、InterruptCommand→`session.interrupt`（均改 package 声明、删升为同包的 import）；三个 Extension 的对应 import 删除，装配代码零改动（命令注册本就由 Extension 完成）
  * **测试随迁**：四个 CommandTest 同步移入域包；`Fakes`/`ContinueCommandTest`（含 newTrace/setOriginalPrompt）公开为 public 供跨包测试共用；`BuiltinCommandsSystemTest` 补显式 import；`GoalBoundaryTest` 硬编码路径同步；三个 ExtensionTest 的全限定名改简名
  * **宿主（soloncode-cli）**：WorkspaceManager 的 `command.builtin.*` 通配 import 保持不动（InterruptCommand 单独 import，因通配已不覆盖它）；宿主 SteerCommandTest 改 import session.steer；IM SOP 表格同步
  * **包名不重命名**：`command.builtin` 余下 6 个（help/clear/model/rewind/rerun/continue）均为通用基础命令，名实相符；README §4.1 已同步两段式描述
  * **验证**：harness 定向 43 例全绿（四命令测试+系统测试+护栏）；全量 818 例的 31F+5E 经 `git stash -u` 基线对照逐项一致（ToolStrategy/TaskTalent/Accessor，均为既有问题）；宿主 clean 后定向全绿（首次运行的 SteerCommandTest Error 系陈旧字节码，clean 消除）
* **steer 输出改为 AgentEvent：删掉端口接口与全部宿主装配**（净删约 110 行 harness + 60 行宿主，修三处真实缺陷；取代本条此前记录的「端口 + session 打标」中间态）:
  * **问题（设计层）**：`SteerOutputPort` 是为「拦截器要把消息送到前端」而造的一层接口，但同一件事在本仓其他组件里根本不需要接口——HITL、上下文压缩、Run 起止全都直接 `trace.pushAgentEvent(...)`。**steer 是唯一特例**，为此多出泛型令牌 + Supplier + 宿主 instanceof 三层间接。而真正决定「投递到哪」的工作区，其实在事件消费方（`WebEventMapper` 构造时就持有 `wsContext`）手上，根本不需要从 session 里翻
  * **问题（实现层，三处真缺陷）**：
    1. **`steer_applied` 在默认实例路径永不发出**：两条出口守卫不对称（`emitSteerApplied` 判 `wsContext != null`，单参构造恰好传 `null`）。受影响路径：Web 队列派发 / 自动任务 / Loop / 命令触发
    2. **`onAgentEnd` 的 dropped 调用无 try-catch**：`null` 路由送进 `WebGate.emitToClient` 的 `wsContext.getConnections()`（在 try 之外）炸 NPE，并沿 `ReActAgent` 的拦截器遍历冒泡，导致后续拦截器 `onAgentEnd` 全部不执行
    3. **双写路径的投递乱序**：`steer_applied` 走拦截器线程直写 socket，与 flux 管线的 `doOnNext` 是两条独立写入路径，顺序无保证
  * **方案**：新增 `SteerAppliedEvent` / `SteerDroppedEvent`（均继承 `AbsReActEvent`），由 `SteerInterceptor` 直接 `pushAgentEvent`。宿主按事件类型映射（Web 加两个 `instanceof` 分支 → `system.steer_applied` / `system.steer_dropped`），不认识的宿主零成本忽略。投递与 `reason.delta` 走同一条 flux，乱序问题随之消失
  * **展示与持久化分离（关键）**：`pushAgentEvent` 在非流式（无 sink）或订阅已取消时**静默丢弃**，所以事件绝不能承载业务动作。新增 `SteerInterceptor#enqueueIntoSessionQueue`，把「未消费插话转 `QueueHelper`」上移到 harness 并**先落盘、再推事件**。附带收益：CLI / Desktop 也获得了把残留插话转为排队任务的能力（此前是丢弃 + warn）
  * **两个事件刻意不覆写 `getText()`**：它们不属于文本流。一旦给出文本，按文本兜底渲染的宿主（ACP 的 `concatMap` 收尾分支、Desktop 的 frame 转换）会把插话内容顺手打进对话。需要展示的宿主必须显式按类型分支
  * **删除清单（harness）**：`SteerOutputPort` 整个接口（53 行）、`SteerInterceptor` 的 `outputPortSupplier` / `withPort` / `emitSteerApplied`（约 47 行）、`SteerExtension` 的 `(Supplier)` 构造器。`new SteerExtension()` 成为唯一构造器——没有注入入口，也就没有「漏传端口」这种陷阱
  * **删除清单（soloncode-cli）**：`WebGate` 的 `SteerOutputPort` 实现 + `routeOf` + `ATTR_REPLY_WORKSPACE`（约 60 行）、`WorkspaceManager` 的 lambda 装配、`WebStreamBuilder` 的每请求 `new SteerInterceptor(...)` + `interceptorAdd` 样板、`WebSessionRunner#beginStreamTurn` 的 session 打标（改为直接复用 `enqueueIntoSessionQueue`，排队逻辑只有一处）
  * **行为等价性**：正常路径（Web 主对话插话 → applied 落气泡、任务结束 → dropped 转排队并继续执行）一字未变。改变的是原先失效/崩溃的三条路径，外加 CLI/Desktop 的 dropped 从「丢弃」变为「转排队」
  * **测试**：`SteerInterceptorTest` 14 → 22 例（用覆写 `pushAgentEvent` 的记录型 trace，不依赖流式 sink），覆盖 applied 真实推出、守卫跳过不推、dropped 先落盘后广播、**无订阅者时队列仍增长**、队列满回填邮箱且 `failed` 可辨识、入队失败不冒泡。`SteerExtensionTest` 删除名不副实的「惰性端口」空转用例。新增 `WebEventMapperSteerTest`（6 例）覆盖两种事件映射、failed 额外出 error、空 dropped 不出噪音、以及**穿过管线三个过滤器**（防静默过滤）。`WebGateRejectedInputTest` 删除三个针对已下线端口方法的用例（语义已由 harness 侧用例覆盖）
  * 验证：harness 相关 75 例全绿；`soloncode-cli` 全量 1226 例 3 失败，与 `git stash` 后的干净基线逐项一致（ImageReply 异常文案 / AgentSettings 400 / Mount 安装目标数），均为既有问题
  * 文档：README §5.3 装配段与输出契约整体重写为事件形态，§9 拦截器挂载段删除请求级挂载示例
  * **环境坑（值得记录）**：本机环境变量 `CLASSPATH` 残留了旧的 JMeter 路径，会串进 javac 的类路径并**禁用 Lombok 注解处理**，表现为全仓 200 个 `找不到符号 getXxx()`。基线同样复现，与代码无关；构建时用 `env -u CLASSPATH mvn ...` 规避

* **TaskTalent 调度内核重写：信号量 + 轮询分派 → 单队列 + N 个 worker 自取**（有效代码 668 → 562 行，总行数 1000 → 884；旧调度段 250 → 新调度段约 80）:
  * **动机**：`Semaphore` + `acquirePolling` + `dispatchParallel` + `tryAcquireReady` + `ModelSlot` 这一套的存在前提是「槽位引擎级、跨会话共享」。槽位一旦改为「按调用算一个数」，就没有待抢的资源，也就不需要抢——**共享与轮询是耦合的，去掉共享顺带去掉轮询**
  * **新形态**：一批任务进 `ConcurrentLinkedQueue`，起 `min(cap, size)` 个 worker 各自 `poll`；worker 跑完一个立刻取下一个，这正是「首发 cap 个、完成一个补一个」的全部实现，不需要完成回调与计数递减。抽为包级类 `TaskDispatcher`，`TaskTalent#dispatchQueued` 只剩一次调用
  * **生效上限改为按调用**：取**当前会话所选模型**的 `AiConfig#concurrency`（0 = 未设置 → `subagentDefaultConcurrency`）。不再按任务自身模型分组——`AgentDefinition#model` 路由到其它子代理时，其模型上的 `concurrency` 对本次调用不再生效
  * **行为变更（有意识地接受）**：① 多会话之间不再共享额度，同一模型的最坏并发 = 会话数 × cap（原设计正是为消除这一点而引入信号量，`UPDATE_LOG` 前文「5 个会话各跑一个 task」即指此）；② 串行快路径删除，cap=1 也走 worker 线程（多一次线程投递，换来串行/并行不再是两个分支）；③ 「未启动」原因不再区分取消与中断，统一为「主流程已取消，任务未启动」
  * **连带删除**：`CAP_NONE`、`modelSlots`/`ModelSlot`、`semaphoreOf`、`slotKeyOf`、`finalConcurrency`、`isSerialOnly`、`dispatchSerial`、`dispatchParallel`、`tryAcquireReady`、`markNotStarted`、`submitTask`、`acquirePolling`、`releaseQuietly`、`SLOT_WARN_MS`
  * **顺带消失的一整类缺陷**：没有 permit 就没有「提交失败漏还许可」语义（原先要 `catch Throwable` 兜住建线程时的 `OutOfMemoryError`，否则该模型后续全部卡死）；`SynchronousQueue` 那条「core=0 配无界队列会退化成并发 1」的约束也不再是关键路径
  * **保留下来的不变式**：`aggregate` 按下标聚合、`<multitask_results>` 与任务数 1:1（任何一个 future 不完成都会让主流程永久挂住，故正常/取消/提交失败/worker 异常四条退出路径都会把剩余项补成占位）；`resolveConcurrency` 与 `normalizeDefaultConcurrency` 契约不变
  * **顺手改名**：`SLOT_POLL_MS` → `CANCEL_POLL_MS`（它承担的其实是取消检测节拍，旧名是当前最大的认知负担）
  * **测试**：新增 `TaskDispatcherTest` 6 例（窗口真实生效且完成即补位、cap=1 必须串行、整批已取消、执行中途取消、执行器已关闭、任务体抛异常不留下未完成 future）；`TaskTalentSlotTest` 16 → 3 例（只保留执行器真并发/关闭拒绝/上界截断）；`TaskTalentSchedulingTest` 删除按下标写入与许可归还两例（所锁定的缺陷在新结构里不存在），保留取消聚合与坏条目隔离；`TaskTalentParallelCapTest` 删除 `isSerialOnly` 5 例。定向 28 例全绿；harness 全量 **777 例 29F+3E**，失败仍全部集中在 `ToolStrategyDefaultsTest`/`BashToolStrategyTest`/`WebToolStrategyTest`/`WriteToolStrategyTest`，与既有基线逐项一致
  * **复审后四项修正**（第三轮）：
    1. **`resolveConcurrency` 补齐上界截断**（真缺陷）：类注释承诺「上界 `MAX_CONCURRENCY`」，但只对默认值生效——`concurrencyOf` 直返模型配置、`resolveConcurrency` 只判 `> 0`，模型配 200 就会真的 execute 200 个 worker，而执行器是 `core=0/max=MAX_VALUE/SynchronousQueue`，等于真建 200 个平台线程，多会话再按会话数叠加。现改为 `Math.min(modelConcurrency, MAX_CONCURRENCY)`，与默认值归一规则对齐；补两例锁死（`resolveConcurrency(200,5)==MAX_CONCURRENCY`、`resolveConcurrency(Integer.MAX_VALUE,100)<=MAX_CONCURRENCY`）
    2. **`dispatchQueued` 注释与代码对齐**：原注释称「不可解析的任务不入队」，实际全部下标都交给 `TaskDispatcher`，invalid 条目会入队但被 `runTask` 入口立即短路。现改为如实说明「入队但立即短路」，并注明**不要为此加过滤**——让它们留在队列里才能使「下标 → 结果」与入参完全一致
    3. **`aggregate` 删除未使用的 `plans` 参数**（全文无引用）；`notStartedReason` 三层重载压成一层；`multitask` 内 `sessionModelOf(__parentSession)` 提取局部变量（两次调用之间配置被换掉会让上限与实例不一致）
    4. **「未启动」原因注释如实化**：中断判定读的是当前线程标志，worker 线程上的占位补全读到的是 worker 自身状态，可能退化为「主流程已取消」。不引入额外状态传递，但在注释中写明这是有意的取舍（真正常见的只有取消，中断那条是兜底）

* **multitask 并行上限细化：新增模型级 `AiConfig#concurrency`；限流从「超限报错」改为「提交前排队」，并用引擎私有执行器隔离全局共享池**:
  * 问题一：并行上限只有引擎级一个标量（默认 5），一刀切，无法适配不同模型/API Key 的限流配额差异（免费中转可能只抵 2 并发，官方 Key 可抵 10+）
  * 问题二：超限直接返回 `ERROR: 单次最多只能执行 N 个子任务，请分批调用 multitask`，逼 LLM 再耗一整轮工具调用重试，白烧 token 与轮次
  * 问题三（本轮新识别的真缺陷）：`RunUtil.io()` 是 **JVM 全局共享池**（`RunUtil` 静态字段 + `max(cpu*4,16)`），不只属于 harness；goal 调度等其他模块也在用。若 multitask 的分派线程本身就跑在该池上（`GoalExtension` 的 `RunUtil.async` 承载整轮 goal），等待并发空位会把池线程占死，拿到空位的任务永远等不到线程 → 许可永不释放 → 全链路死锁
  * **配置项语义与命名（旧名已发布，保留 `@Deprecated` 桥接）**：
    * `HarnessOptions#multitaskMaxTasks` → **`subagentDefaultConcurrency`**：语义从「单次任务数上限（超限报错）」变为「子代理任务的默认并发上限（超限排队）」，故 `MaxTasks` 这个名字已失真。命名风格对齐同文件既有先例 `compressionDefaultContextLength`（`<模块>Default<属性>`），模块词选 `subagent` 以对齐 `subagentEnabled`。旧名 `multitaskMaxTasks`（自 v4.0.5 已发布）与中间过渡名 `multitaskMaxConcurrency` 均保留 `@Deprecated` 桥接，转发到新名。**但存量语义已变**：旧版该值只影响提示词文案（建议 2-N 个）、并不真正限流；新版它是真实的默认并发上限且不再参与提示词，升级后影响力变强，需复核存量配置
    * `ChatConfig#maxParallelTasks` → **`AiConfig#concurrency`**：字段上移到 `AiConfig` 基类（并发数是所有 AI 接口的通用属性，`EmbeddingConfig`/`GenerateConfig`/`RerankingConfig` 均继承），并去掉 `max` 前缀——「可并发数」本身就是上限，无需再加 max；`ChatConfigReadonly` 同步暴露读取口
  * **语义从「两级取 min」改为「模型优先、默认回退」**（关键设计变更）：
    * `AiConfig#concurrency` 的 **0 表示「未设置」**，与 `contextLength` 的 0=未设置 完全同构；模型未设置时回退 `subagentDefaultConcurrency`
    * 解析规则收敛到单一入口 `resolveConcurrency(model, default)`，与 `ContextCompressionInterceptor#finalContextLength`（先取默认、有配置才覆盖）同构
    * 收益：旧方案里「模型配了 8 却只跑出 5、必须查日志才知道为什么」的静默降级被彻底消除；用户配多少就是多少
    * **不存在「不限制」这个档位（本轮校正）**：默认值是兜底安全网，必须有界。`0` 不能被解释为「不限」——① 物理上不成立：线程数、上游 QPS、堆内存会退化为只受任务数约束，恰好是最容易打爆上游（429）与 OOM 的形态；② 信号量一旦缺席，「模型级 `concurrency`」就形同虚设；③ 0 已被「未设置」占用，同一个 0 不能既是「没配」又是「配成无限」。因此上一版「0 = 全局逃生门」的取舍已推翻：需要高并发时请显式配模型级 `AiConfig#concurrency`（那是用户亲手写下的具体数字，意图与责任都明）
    * **默认并发上限的取值契约归一到一个入口**：`TaskTalent.normalizeDefaultConcurrency(int)`（`HarnessOptions`/`HarnessEngine`/`Builder` 三处共用，避免各层各写一套）+ 常量 `DEFAULT_CONCURRENCY=5` / `MAX_CONCURRENCY=10`：`1~10` 原样；**`0` = 未设置 → 缺省 5**（与 `contextLength`、`concurrency` 的 0=未设置 同构）；`>10` → 上界 10；负数 → `IllegalArgumentException`。归一（0 或超上界）一律打 WARN——否则「配了却没生效」又退回成静默降级。`resolveConcurrency` 因此<b>恒返回正整数</b>，“0 = 不限”这个返回值已不存在；`TaskPlan.cap` 的 0 重定义为 `CAP_NONE`（无槽位：子代理未知/模型不可解析，任务会立即失败，不构成并发压力），与“不限”彻底脱钩；连带可删掉 `semaphoreOf` 的 `limit<=0` 死分支，线程数上界也从“正常情况下”变成“恒定不超过各模型生效上限之和”的可证不变式
    * 连带简化：只剩**一把**信号量，`globalSemaphore`/`globalSemaphoreLimit`/`acquireAll`/`releaseAll` 双许可形态/`globalCapWarned`/诊断日志全部删除。「取槽固定顺序避免死锁」这条约束也随之消失
  * **默认并发不再是「全局 5」而是「每模型 5」**（该方案的固有代价，已记录）：10 个不同模型时总并发可达 50。原全局 5 是总量硬闸门，现降级为单模型兜底
  * **配置可达性照抄 `compressionDefaultContextLength` 的三处同步**：`HarnessOptions` 持字段（保留 yaml 绑定）→ `HarnessEngine#setSubagentDefaultConcurrency` 先落配置再推送到 `TaskTalent` → `Builder#subagentDefaultConcurrency`。构造函数里 `new TaskTalent(this)` 之后立即把 `options.getSubagentDefaultConcurrency()` 推给执行者，否则 Builder/yaml 只写 options 不生效
  * **按任务自身模型取槽**（核心增益）：子任务可能各自路由到不同模型（`AgentDefinition.model` 优先于会话模型）。此前"批大小"是整次调用的单一标量，一个 `cap=1` 的子代理会拖垮整批；现在每个任务按自己会用的模型取槽，低配额模型只约束自己的任务
  * **提交前限流 + 引擎私有执行器**：在调用线程取到许可后才 `execute` 到 `TaskTalent` 私有 `ThreadPoolExecutor`，执行器里永远只有真正在跑的任务，不存在「占着线程等许可」。私有池与 `RunUtil.io()` 隔离，据此消除上述问题三。线程为守护线程，另提供 `TaskTalent.shutdown()` / `HarnessEngine.shutdown()` 显式回收
  * **`resolveTaskModelConfig` 与 `AgentFactory` 逐字对齐**（`AgentDefinition.model` → 会话模型 → 默认模型）：两者一旦分叉，限流用的模型和真正跑的模型会对不上，限流静默失效而不报错
  * **串行快路径的条件是「全部」而非「任一」，且必须用生效上限**：仅当**所有**任务的生效并发上限都为 1 时才在调用线程逐个执行。若写成「任一为 1」，一个 `cap=1` 的子代理会把整批拖成串行；若用裸 `getConcurrency()` 判定，则「模型未配置 + 默认值 1」会被误判为不限而错走并行路径（两点均有单测锁死）
  * **串行快路径也必须取槽**（后补）：信号量是引擎级、跨会话共享的；如果快路径绕开它，「显式配置 `concurrency=1`」的模型在任何走快路径的批次里就完全不受限流约束，而这恰恰是最需要严格限流的场景。cap=1 时首次取槽必定成功，不损快路径意义
  * **两轮取槽：先非阻塞、再阻塞等待**（后补）：第一轮 `tryAcquire` 抢到就立即提交，抢不到的收集起来第二轮才阻塞。若一开始就阻塞等待，一个低配额模型排在前头会把后面所有模型的任务都挡在「尚未提交」状态，整批吞吐退化为该模型的串行度——与「混合模型各自受限、互不拖累」的设计意图相反
  * **结果集合定长 + 按下标写入**：两轮取槽后提交顺序不再等于任务顺序，必须 `futures.set(i, ...)` 而非 `add`，否则 `<index>` 与 `<task_result>` 错位（有单测锁死）
  * **预解析只做一遍**：子代理、目标模型、生效上限先批量解析，串行判定与提交路径共用，「全部而非任一」的判断不会再因两次解析结果分叉
  * **取消响应**：取槽用 `tryAcquire(200ms)` 轮询而非无界阻塞，保证用户按停止后不会僵在原地；未启动任务填占位 `result_status=failure`，保证 `<multitask_results>` 条目数与任务数 1:1、`<index>` 不错位
  * **许可泄漏防护**：提交失败的 `catch` 从 `RejectedExecutionException` 改为 `Throwable`——`ThreadPoolExecutor` 创建线程时抛 `OutOfMemoryError` 属 `Error` 而非 `RejectedExecutionException`，只 catch 后者会让许可永久漏掉，该模型后续全部卡死。释放统一走 `releaseQuietly(Semaphore)`（容忍 null，不限并发时槽位就是 null）
  * **校验对齐参照实现**：`TaskTalent#setDefaultConcurrency` 与 `AiConfig#setConcurrency` 均对负数抛 `IllegalArgumentException`（`ContextCompressionInterceptor#setDefaultContextLength` 即此风格），配错立即暴露而非被静默吞掉
  * **提示词与文档同步**：`getInstruction` 第 1 条不插入任何具体数字（生效上限是「模型配置或默认值」，提示词无法预先得知），第 4 条去掉硬编码的"不超 10 个"改为"规模与体量"引导；`TOOL_MULTITASK` description 与 instruction 由"自动分批并行执行"改为"自动排队"（实现是提交前排队、完成即释放，**没有分批屏障**）；`<multitask_advice>` 三处改为可操作的真实配置名（`concurrency` / `subagentDefaultConcurrency`）；README §6.5/§8.1 同步重写
  * **有意不做**：未设"任务数绝对上限仍报错"——会把核心收益（消除报错→重试往返）重新抵掉，上下文防护交给提示词软引导。跨会话约束由模型级信号量本身覆盖（`modelSlots` 是 `TaskTalent` 字段，而 `TaskTalent` 是 `HarnessEngine` 单例字段，天然全引擎共享，不依赖已删除的全局信号量）
  * **调度复审后的五项修正**（第二轮）:
    1. **第二轮分派改为轮询而非按下标阻塞**：原实现第一轮非阻塞、第二轮 `for (int i : waiting)` 逐个阻塞。若 `waiting=[0(A 已满), 1(B 空闲)]`，下标 1 必须等下标 0 拿到 A 的许可才能提交，B 的空闲配额被白白晾着，整批吞吐退化为 A 的串行度——正是第一轮要消除的缺陷从「第二轮」漏了进来。改为每轮对所有待分派任务 `tryAcquire`，只剩一个都不成功才睡 200ms；抽成纯函数 `tryAcquireReady(waiting, plans)` 便于锁死
    2. **`modelSlots` 改按模型名分组**：原键为 `ChatConfig` 实例（未重写 equals/hashCode）。而 `HarnessOptions#addModel` 以 `getNameOrModel()` 为 Map 键覆盖，因此每次热更新都会遗留一个只剩 `modelSlots` 引用的旧实例，长驻进程里无界增长。改为键取 `getNameOrModel()`（无名配置退回实例身份，避免所有无名配置被误合并）——在已注册模型上两种分组等价
    3. **上限变更改为原地调整而非重建信号量**：原实现在 limit 变化时新建信号量，旧信号量交给在跑任务自然回收。但「旧的在跑 5 + 新的立即放出 2」=瞬时并发 7，恰好是最容易触发上游 429 的时刻。改为 `AdjustableSemaphore` + `reducePermits` 落入「欠账」：调低后要等在跑数降到新上限以下才恢复放行，绝不瞬时超额
    4. **预解析产物一路下行**：新增 `TaskPlan{op, agentDefinition, modelName, slot, cap}`，执行时不再重新走 `getModelOrDef`。原实现预解析与执行各调一次，而「未设 `defaultModel` 且有多个启用模型」时 fallback 走 `ConcurrentHashMap` 迭代取首个，并发修改下不保证稳定 → 限流用的模型可能不是真正跑的那个；顺带消除了每任务三次 `getAgent` 解析
    5. **`task` 单任务也取槽**：原实现只给 `multitask` 限流。信号量是引擎级、跨会话共享的，5 个会话各跑一个 `task` 时同一模型上就是 5 并发，`concurrency=2` 形同虚设。现单元任务走同一 `runTask` 前先取槽（单任务串行，无损并行度）
  * **其余健壮性**：`tasks` 含 null 元素时返回占位错误而非 NPE（并保证下标不错位）；聚合循环遇中断后不再继续 join 剩余项（否则连续抛同一异常刷屏）；debug 日志由「只打印默认值」改为同时打印各任务的实际生效上限；`HarnessOptions#setSubagentDefaultConcurrency` 补负数校验（与 `TaskTalent#setDefaultConcurrency`、`AiConfig#setConcurrency` 一致）；`shutdown()` 的 javadoc 更正——串行快路径不经过执行器，故关闭后并非「所有 multitask 都无法提交」
  * 测试：`TaskTalentParallelCapTest` 17 例（`resolveConcurrency` 锁定模型优先/未设置回退/模型低于默认值/**0 恒回退到缺省 5（返回值不为 0）**/负数防御/**setter 归一（0→5、超上界→10、区间内原样、负数抛异常且不改动已有值）**；`isSerialOnlyByCaps` 锁定"全部而非任一"、"模型未配置+默认 1 必须串行"、不可解析模型、空集合；`AiConfig#concurrency` 缺省 0 / 负数抛异常 / 只读透传 / `toString`）+ `TaskTalentSlotTest` 16 例（**模型未配置时按默认值限流**、**默认 0 回退到缺省 5 并照常建信号量（不再有“不限=不建信号量”形态）**、**超上界按 10 处理且真实作用在信号量上**、**模型配置覆盖默认值**、复用与**上限变更原地调整**、**运行期调低不得瞬时超额**、**同名模型不因替换配置而多出槽位**、**无名配置不得被合并**、运行中许可被占不得重建、中断退出不多许可、释放幂等含 null、执行器真并发、关闭后拒绝提交）+ `TaskTalentSchedulingTest` 9 例（**第二轮谁有空配额就提交谁**、null 槽位视为就绪、按下标写入、提交失败归还许可、未启动原因区分、提示词分条、旧名桥接、负数拦截）+ `HarnessOptionsAccessorTest` 16 例（含 **0 归一为缺省、超上界靠上界、负数报错**）、`HarnessEngineAccessorTest` 22 例、`HarnessEngineBuilderTest` 13 例、`TaskTalentGuardTest` 3 例，共 46 例定向全绿；harness 全量 784 例 29F+3E，失败全部集中于 `ToolStrategyDefaultsTest`/`BashToolStrategyTest`/`WebToolStrategyTest`/`WriteToolStrategyTest` 四个类，与既有基线一致
  * 过程坑（4 个，均属"静默失效"类）：
    1. `ThreadPoolExecutor(core=0, max=∞, 无界队列)` 配 `LinkedBlockingQueue` 时**并发恒为 1**：JDK 只在 `workerCount < corePoolSize` 才新建线程，`core=0` 永不成立，后续任务全部入队排队且不报错。必须用 `SynchronousQueue`（Cached 语义），已有用例锁死
    2. 用 `availablePermits()` 反推"上限是否变了"是错的：任务运行中许可被占，可用数必然小于上限，会被误判为上限变更而反复重建信号量，限流直接失效。改为 `ModelSlot{limit, semaphore}` 显式携带上限；上限变更走 `AdjustableSemaphore#reducePermits` 原地调整（不能用 `compute` 换新实例（会瞬时超额）
    3. `HarnessOptionsAccessorTest`/`HarnessEngineAccessorTest` 各有一次**反射遍历所有 `set*` 方法**的巡检，新 setter 不列入白名单就直接失败。改名/加 setter 时必须同步两处白名单（`@Deprecated` 桥接也是公开 setter，同样要列）
    4. 改 `solon-ai-core` 后必须先 `mvn install -pl solon-ai-core`，否则 harness 测试跑的是本地仓库旧 jar，会出现「测试明明写了负数校验却没抛异常」的假失败
    5. `reducePermits` 降容后 `availablePermits()` 会变负数，`tryAcquire` 自然失败即「欠账回补」；但这意味着**降容后的可用额度不等于 `新上限 - 在跑数`**，断言时必须用 `tryAcquire` 的真假而非直接读数

* **TaskTalent 复审第三轮：HITL 装配边界 + 取消传播 + 输出外壳安全**（不改调度内核；核已收敛为「许可先行 + 私有执行器 + 两轮分派 + 预解析透传」）:
  * **P1（结构隐患，用户明确要求）：HITL 只允许主代理使用**。`AgentFactory.toolAddDo` 的 `case "hitl"` 原对主/子代理一视同仁，而 `TOOL_ALL_FULL("**")` 含 `hitl`，子代理配 `**` 就会意外继承。子代理一旦命中审批规则即挂起，而父代理此刻正阻塞在 `aggregate` 里等它 → 无人能决策、互等不可解（外部只看到「子代理未正常完成：未收到正常响应」这类无线索报错）。现改为**装配期直接不挂**（`AgentFactory#hitlAllowed(metadata)` = `metadata.isPrimary()`，包级可见便于测试），而不是运行期拒绝——后者仍会白烧一轮 LLM 才拿到一句 ERROR
  * **P2：`aggregate` 不能用 `join()` 死等**。`CompletableFuture#join` 不可中断（只记录 interrupt 标志、不会抛），用户按停止后父线程会逐个等到底；原 `aborted` 分支的前提（“join 会立刻再次抛同样异常”）实际不成立，几乎永不触发。改为 `get(SLOT_POLL_MS)` 轮询：每轮检查 `sink.isCancelled()`，一旦取消/中断就 `interruptWorkers()` 中断所有在跑的子任务线程并不再等待后续任务（否则卡在 10 分钟 `bash` 里的子任务会把父代理拖到工具跑完）。工作线程登记在 `MultitaskContext.workers`，执行前先 `Thread.interrupted()` 清残留标志（池线程复用时上一个任务的标志会误伤下一个）
  * **P3：取槽排队可观测性**。`acquirePolling` / `dispatchWaiting` 原来全是静默轮询，被低配额模型堵住时外部唯一表现是「前端没有新内容」（无进展看门狗已移除）。现超过 `SLOT_WARN_MS=30s` 打一条 WARN（每个等待过程只打一次），带模型名/剩余任务数。**有意不做硬超时**——会误杀长任务；也**有意不推事件**——新增事件类型需宿主三端同步，超出本类职责
  * **P4：结果外壳三处注入面 + 无长度上限**（`formatTaskResp`）：①`]]>` 未处理会提前闭合 CDATA，子代理输出含 XML/diff 就会把外壳打散（新增 `toCdata`：**先截断再替换**——顺序反过来会把转义产物切成半个，同时截断边界避开代理对）②`description`/`agent_name` 同样来自模型输出，`<`/`&` 会破坏外层标签（新增 `escapeXml`）③补 `MAX_RESULT_CHARS=20000` 上限，否则单个超长输出就能灌爆父会话上下文并触发压缩
  * **P5：未知子代理的错误绕过了外壳**。`preparePlan` 原返回 `invalidReason=null`，于是 `runTask` 直接返回裸字符串，在 `multitask` 里变成「一条没有 `<index>`/`<description>` 的裸行」混在 `<multitask_results>` 中，模型对位解析易错位；`task` 与 `multitask` 两种形态也不一致。现统一走 `unknownAgentReason` + `formatTaskResp`
  * **P6：清理**：删除不可达的 `errRef` 检查（`doOnError` 只观察不吞信号，`blockLast()` 会先抛）与无调用者的 `ModelSlot#getLimit`；修正 `isSerialOnlyByCaps` 空分支的失真注释（“全部是未知子代理”不成立——那种情况会产生 `cap=CAP_NONE` 条目而非空列表）；`slotKeyOf` 补前提说明（按 `getNameOrModel()` 分组等价的前提是 `HarnessOptions#addModel` 也以该值为键）
  * 测试：新增 `TaskTalentResultFormatTest` 4 例（`]]>` 不得闭合 CDATA（**用极简 CDATA 解析器还原出原文**，比数终止符个数可靠）、结构字段转义、超长截断、正常内容逐字保留）+ `AgentFactoryHitlBoundaryTest` 3 例（主代理可用 / 子代理拒绝 / 配 `**` 也拒绝）+ `TaskTalentSchedulingTest` 增至 10 例（新增 `invalidReasonIsWrappedInEnvelope`）；定向 74 例全绿；harness 全量 **795 例 29F+3E**，失败类与既有基线逐项一致（全在 `ToolStrategyDefaultsTest`/`BashToolStrategyTest`/`WebToolStrategyTest`/`WriteToolStrategyTest` 权限域）
  * 已知但本轮未改：子代理内 `Prompt.attrs` 的 `ATTR_SESSIONID` 是子会话 id，而 `toolContextPut(ATTR_SESSIONID)` 是父会话 id，两者不一致（子代理工具调用归属父会话）。改它会连带影响权限上下文与会话级状态，属独立决策，未纳入本轮
  
  * **TaskTalent 精简轮：砍掉为测试/日志/极端场景而生的 5 处抽象**（不改调度模型，只减复杂度）:
  * 背景：前四轮修正累计把文件从 740 行推到 1329 行（+80%），其中 253 行是注释、93 个定向用例里大量反射锁住私有方法签名。复审判定：6 块核心修正（预解析透传、许可先行、取消轮询、未知 agent 隔离、结果外壳安全、并发契约归一）必须留，另有 5 处属过度设计，本轮清除
  * **砍 1：`interruptWorkers` 与工作线程登记**。收益只是「取消后不必等当前长工具跑完」，风险却是新开一个破坏面——中断正在写文件或读 HTTP 响应的线程可能留下半截文件或让连接进入不可用状态。而「不中断、只是不再等待」本身安全：任务在后台自然结束并自行归还许可，不泄漏。删 `MultitaskContext.workers`/`registerWorker`/`unregisterWorker`/`interruptWorkers` 与执行前的 `Thread.interrupted()` 清理
  * **砍 2：`dispatchParallel` 的双轮实现合并为一轮**。原「第一轮非阻塞抢槽 + `dispatchWaiting` 第二轮轮询」两段重复了「取消检查 → tryAcquire → 提交 → 取不到继续」。合并后：先扫一遍（取消的填占位、能抢到的直接提交、其余进 `waiting`），再进轮询循环；首次轮询不睡，与原第二轮语义等价。少一个方法、少一处重复分支
  * **砍 3：`effectiveCapsOf` 内联**。它唯一用途是把 `TaskPlan` 列表拍平成 `List<Integer>` 再交给 `isSerialOnlyByCaps` 判断，纯粹为「可单独测试」而存在。改为 `isSerialOnly(List<TaskPlan>)` 直接判断，省一次分配与一层抽象
  * **砍 4：`describeCap`/`describeCaps`/`waitingModelNames` 三个日志辅助方法删除**。它们只服务于两条 DEBUG 与一条 WARN 的文案拼接（其中 `describeCaps` 还要把列表格式化成 `[1:5, 2:5]`）。DEBUG 缩为一行任务数，WARN 只打排队数量与通用成因
  * **砍 5：`AdjustableSemaphore` 的欠账机制退回「换新信号量」**。原机制（`reducePermits` 打成负数形成欠账）正确，但 40 行只换「运行期显式改上限时不瞬时超额」这一个场景；而运行期改上限本身极罕见。改为 `ModelSlot{limit, semaphore}` + `compute` 换实例，约 10 行，代价是变更瞬间可能短暂超额——已写入注释
  * **注释压缩**：把「为什么不用 `RunUtil.io`」「为什么不限并发」「不设超时的代价」等长论证从字段 javadoc 收回本文件的段落与类注释，字段注释只留结论。注释行 253 → 178，文件 1329 → **1000 行（-25%）**，其中代码行 482 → 669（增量即前四轮 6 块核心修正的成本）
  * **测试同步瘦身**（定向 93 → 88 例，去反射化）：`TaskTalentParallelCapTest` 17→16（`isSerialOnly` 改吃 `TaskPlan`，删 `effectiveCapsOf` 相关）；`TaskTalentSlotTest` 16→15（删两个随 `AdjustableSemaphore` 报废的「原地调整/不瞬时超额」用例，换 `capChangeTakesEffect`；`finalConcurrency`/`semaphoreOf`/`acquirePolling`/`releaseQuietly` 降为包级可见，测试不再用反射样板）；`TaskTalentSchedulingTest` 12→9（删 `notStartedReason` 直调反射与「反射伪造 `TaskPlan` 再断言外壳」的假绿用例——后者已被真实链路的 `unknownAgentOnlyFailsItsOwnTask` 覆盖；`tryAcquireReady` 合并为一个含 null 槽位的用例）；`TaskTalentResultFormatTest` 4 例改为直接调用，删反射
  * **保留的取舍**：`tryAcquireReady` 仍作为包级纯函数保留（「不能按下标顺序阻塞」是核心且微妙的语义，值得一个用例锁住）；`TaskPlan` 仍是包级内部结构（分派与执行共用同一份预解析结果，是 P1 类修正的载体）
  * **副产品：修掉一处文案漂移（此前引入的静默回归）**。`SingleTaskOp.prompt` 的 `@Param` 描述在工作副本里被改成「任务所需的**全部**背景信息」，而 `JsonSchemaTest.case1` 期望（也是 HEAD 原文）是「**所有**背景信息」→ 全量首跑 30F，多出的一条正来自这里。已恢复原文，基线回到 29F+3E
  * 验证：定向 88 例全绿（`TaskTalentSchedulingTest 9` + `TaskTalentSlotTest 15` + `TaskTalentParallelCapTest 16` + `TaskTalentResultFormatTest 4` + `AgentFactoryHitlBoundaryTest 3` + `TaskTalentGuardTest 3` + `HarnessOptionsAccessorTest 16` + `HarnessEngineAccessorTest 22`）；harness 全量 **792 例 29F+3E**，失败全在 `ToolStrategyDefaultsTest`/`WebToolStrategyTest`/`WriteToolStrategyTest`/`BashToolStrategyTest` 权限策略域，与既有基线逐项一致（用例总数 797→792 即本轮删掉的 5 例）


* **TaskTalent 复审第四轮：无超时约束（子任务可能跑几天）+ 两个契约执行不到底的洞**（不改调度内核）:
  * **新增硬约束：本类不设任何超时**。子任务可能是长时间构建、等外部系统，**可能跑几天**，因此取槽 / 待分派 / 聚合三条等待路径只有轮询节拍、没有截止时间，只会因「主流程取消」或「线程中断」提前结束（已写进类注释）。配套代价：一个跑几天的子任务会长期占着 1 个槽位，因此两条排队 WARN 补上「或已有长任务占着槽位」这个更难排查的成因
  * **P0（回归，本轮引入后自查发现）：`aggregate` 被中断后是零间隔死循环**。`InterruptedException` 分支只做了 `Thread.currentThread().interrupt()` 而**没有 break**，下一轮 `isCancelled` 为 false、`get(200ms)` 因中断标志已置回而立即再抛 → 1 秒 73 万次迭代、线程永不返回，外部表现为「CPU 打满、任务永不结束」。可达路径：`dispatchWaiting` 的 `sleep` 被中断后置回标志并返回，进入 `aggregate` 时标志已是 set。修法：该分支补 `break`（连同上一轮的取消轮询，这条路径此前**零测试覆盖**）
  * **P1：未知子代理会炸掉整批**。`AgentManager#getAgent` 对未知名称是**抛异常**而不是返回 null（`AgentDefinitionTest` 反向锁死了这一点），而 `preparePlan` 直接调它、外层无 try/catch → 模型幻觉一个 agent 名就让整个 `multitask` 抛 `IllegalArgumentException`，把「一条坏、其余好」退化成全废；连带 `agentDefinition == null` 的四处分支全成死代码（含上一轮 P5 修的消费端），`invalidReasonIsWrappedInEnvelope` 因反射伪造 `TaskPlan` 而假绿。现改为 `hasAgent(name) ? getAgent(name) : null`，并对「定义存在但加载失败」单独 catch（只废这一条）
  * **P2：`multitask` 无条数上限，`MAX_RESULT_CHARS` 的注释靠一个不存在的约束**（原写「固定最多 5 个」）。**有意不加条数硬上限**——条数是模型编排意图的一部分，压成固定数字只会逼它多轮往返，且 `instructionNoLongerMentionsBatchSplit` 已明确拿掉了数字型批大小建议；改为把注释改正：单条上限的意义是「任何一条都不能独占上下文」，总量随条数线性增长，由父会话压缩机制兜住
  * **P3：连带小项**：`aggregate` 的 `f==null` 裸行改为带外壳（传递 `plans` 取 `op`）；串行快路径 `runTask` 包 try/catch，保证「先算后 set」不会因抛出而留下 null 下标；`preparePlan` 只解析一次生效上限（`defaultConcurrency` 是 volatile，分两次读会出现「信号量按新值、cap 记旧值」的错配，`semaphoreOf` 改为接收已解析的 limit）；删除无调用者的 `ModelSlot#getLimit`
  * 测试：新增 2 例（`TaskTalentSchedulingTest` 10→12）—— `aggregateReturnsWhenInterrupted`（`assertTimeoutPreemptively(2s)` + 永不完成的 future，锁住「无法靠任务自己结束来逃出循环」）与 `unknownAgentOnlyFailsItsOwnTask`（**走真实 `multitask` 链路**，两个未知 agent 必须各自出外壳，不伪造 `TaskPlan`）；两者均**做过变异验证**：回退 `break` → 用例超时失败；回退 `hasAgent` → 用例因丢原因失败。`TaskTalentSlotTest` 的反射 helper 跟着 `semaphoreOf(ChatConfig,int)` 调整；定向 93 例全绿；harness 全量 **797 例 29F+3E**，与既有基线逐项一致
  * 坑：`edit` 工具 `old_StrStartLine` 必须指向 `old_str` **首行**（本例四处偏移一行就被预检拦下）；改 `MAX_RESULT_CHARS` 注释时缩进被改成 1 空格（`old_str` 首行漏了缩进），事后已修——这类“编译过但格式坏”的问题只能靠 review diff 发现

* **goal 域宿主调用点收敛：宿主内部 41 处 `getGoalEngine()`/`getGoalScheduler()` 清零，全部改走 `GoalExtension` 门面**（P0/P1 落地，仍是宿主内部可维护性改造——Web 端点路径/参数/返回体/状态码一字未改，终端用户无感知）:
  * 背景：上一轮已把「装配」收敛为一个类，但宿主内部仍有 41 处直接取内部实例、6 个文件。其中约 10 组是**手工复刻门面已封装好的配对动作**（`pause`+`unschedule`、`resume`+`schedule(true)`、`clear`+`unschedule`）——同一语义两套写法并存，是「改了状态忘了挂/摘 job 导致 goal job 泄漏」的唯一来源
  * **门面补 8 个方法**：`stateOf(id)` / `sessionOf(id)` / `find(id, taskId)`（任务行分流三连收敛）/ `running(session|id)` / `attach(...)`（只建不挂）/ `commit(...)` / `hasScheduler()` / `resumeScheduleOnly(id)` / `scheduleNow(id)` / `runRoundAsync(session)` / `updateAndReschedule(...)`；`clear()` 内部改为**先 unschedule 再 clear**（消除「清完状态但 job 还在」的窗口期）
  * **两个必须按住的语义（否则会真实改变功能）**：
    * `resume()` 内含 `schedule(runNow=true)`，而服务重启/工作区恢复原用 `schedule(id, false)`——「静默挂上、等下一轮」。故新增 `resumeScheduleOnly(id)` 专用于 `WebQueueDispatcher.recoverSessionGoals`；若用 `resume()` 顶替，每次重启会对每个活跃 goal 立即多跑一轮
    * 改 goal 配置是 `pause → 宿主 interruptSession → 等轮次停(200×20ms) → update → resume`，中断与等待是宿主运行时职责（依赖注入进来的中断端口），**不能整段吞进门面**。`updateAndReschedule` 只覆盖后半段，且保留「`maxDurationMs != null` 才 set+commit」的条件分支（否则「只改 prompt 不动时长」会把时长清空）
  * **宿主改造**（6 文件，41→0）：`LoopWebController` 29 处（pause/resume/clear/status/remove/toggle/trigger/update 分流）、`DesktopGoalController`、`WsGate`、`DesktopInputRouter`、`DesktopGoalBroadcaster`、`WebQueueDispatcher`、`SessionWebController`；`WsGate`/`DesktopGoalController` 字段由 `GoalEngine`+`GoalScheduler` 收敛为单个 `GoalExtension`，`Configurator` 同步；`WorkspaceContext` 新增 `getGoalExtension()` 与 `attachGoal(GoalExtension, bridge)` 重载（旧三参重载保留）
  * **门面不代管的边界**：`getExecutionBridge()` 保留——WsGate/CliShell 还要往桥上注册 TaskHandler/BusyChecker，桥是 loop 与 goal 共享的横切设施；`GoalState` 作为值对象仍在宿主手中传递（`gs.setXxx` + `commit`）。实际消除的是两个**服务类**的实例依赖
  * 测试：`GoalExtensionTest` 由 5 例扩到 9 例（`find` 三态返回 null / `find` 命中返回同一实例 / `updateAndReschedule` 不因 null 清空时长与 token / `clear` 后 `state`/`stateOf`/`hasGoal` 全为 null / 无调度器时 `resumeScheduleOnly`+`scheduleNow`+`unschedule` 全 no-op 不 NPE）；新增 `GoalLoopEndpointTest` 12 例锁死 pause/resume/clear/toggle/trigger 五端点行为（分流命中、400/404 分支、以及「调度配对只由门面负责、控制器不再直接操作调度器」）
  * 验证：`GoalExtensionTest` 9 例、`GoalLoopEndpointTest` 12 例全绿；`soloncode-cli` 全量 **1216 例 4 失败**（= 基线 1204 + 新增 12），失败类与既有基线逐项一致（ImageReply 异常文案 / AgentSettings 400 / Mount 安装目标数 / Skillhub 联网）；harness 全量 736 例 29F+3E 全在 ToolStrategy/Hitl 域，与 goal 域零交集
  * 文档：README §5.1 运行期门面一览重写（补齐 13 个方法与 3 个易错点：`resume` 的 runNow 语义、改配置只吞后半段、`clear` 先摘后清）；第十三章新增坑 16（恢复期别用 `resume`）与坑 17（改配置别整段交给门面）
  * 未做（有意）：`getGoalEngine()`/`getGoalScheduler()` 在 harness 侧保留不标 `@Deprecated`——harness 是多宿主复用的库，其他宿主可能自建 `GoalEngine` 做二次集成；`soloncode-cli` 先清零，观测一两个版本后再决定库侧去留

* **goal / queue / steer 三命令：宿主侧与 solon-ai-harness 的交互体验简化——装配与操作各收敛到一个类**（响应「宿主侧接线太复杂」的反馈，功能效果与 attrs 契约零变化）:
  * 口径：本次改进的是**宿主侧与 solon-ai-harness 的交互面**（宿主开发者要认识并 new 的类、要写的装配样板、运行期要操作的对象），**不是终端用户交互**——`/goal`、`/queue`、`/steer` 三个命令的语法、回执文案、Web/桌面端点与 UI 行为一字未改，终端用户无感知
  * 问题：README 第五章的三个状态型命令，宿主接触面分别是 5 个类（`ExecutionBridge`+`GoalEngine`+`GoalScheduler`+`GoalExtension`+`GoalCommand`，25 行装配）、2 个静态工具类（`QueueHelper`）、以及「每请求 new `SteerInterceptor` + `interceptorAdd`」的样板。依赖链是刚性的，宿主真正能定的只有 workspaceId 与一两个 lambda，其余全是可推导的组装步骤
  * **`GoalExtension` 升级为 builder 一键装配**：`GoalExtension.builder(workspaceId).taskExecutor(...).busyChecker(...).budget(...).stagnation(...).maxErrors(...).validatorEnabled(...).workspace(...).bridge(...).scheduler(...).commandEnabled(...).listener(...).build()` —— `initialize(engine)` 内依次建 `ExecutionBridge`→`GoalEngine`→`GoalScheduler`→注册 `/goal` 命令→预注册 listener，`configure` 仍挂 `GoalTalent` 到主代理。`taskExecutor` 缺省内置 README 原模板（`engine.handle` + `call()`），`busyChecker` 缺省恒 false，其余全有缺省值。既有双参构造保留并标 `@Deprecated`
  * **`QueueExtension` 新建**：`addExtension` 内幂等注册 `/queue` 命令 + 提供完整操作门面（`enqueue`/`drainOne` 两态/`ack`/`view`/`pendingSize`/`cancelItem`/`promote`/`cancelPending`/`hasPersistedItems`/`importItems`），内部中转两个静态类。**保留「不自动 drain」决策**：Web 端 ack 跨越订阅生命周期，自动化会遮住宿主必须显式掌握的时机
  * **`SteerExtension` 新建**：`addExtension` 内幂等注册 `/steer` 命令 + `configure` 把拦截器挂为主代理**默认拦截器**。顺序安全性已核验：`LinkedHashMap<Class, RankEntity>` 按 Class 去重，`AgentFactory` 先挂压缩/StopLoop、后轮到 extension 的 configure，天然排在上下文压缩之后；`ReActOptions.copy()` 的 `interceptors.putAll` 使请求级同 Class 挂载覆盖默认实例（不双份），故 Web 多工作区仍可保留请求级挂载。端口用 `Supplier` 惰性解析（默认工作区可能在入口绑定之前创建）
  * **builder 只给 goal**：goal 有 8 个可配点才值得 builder；queue 零旋钮、steer 一个端口，给它们套 builder 是伪一致性。README 坑 #15 记录此判断
  * 三命令极简宿主终态：`addExtension(GoalExtension.builder(ws).taskExecutor(r).build())` / `addExtension(new QueueExtension())` / `addExtension(new SteerExtension<>(() -> port))`，运行期操作也只在各自那一个实例上
  * 测试：`GoalExtensionTest`(5) / `QueueExtensionTest`(5) / `SteerExtensionTest`(5) 共 15 例全绿，覆盖全链装配、共享桥复用、listener 预注册、命令幂等与关闭、门面语义透传（同步/异步 drain 差异）、惰性端口、旧构造兼容；`scheduler(null)` 三态语义修复（缺省新建 / 自备 / 显式关闭，后者供无 Solon 容器环境）
  * 宿主迁移（`soloncode-cli`）：`WorkspaceManager` 的 goal 四件套改 builder、`/steer`+`/queue` 命令注册改 Extension（WebStreamBuilder 请求级挂载保留，多 wsContext 现实约束）；compile 通过，全量 1204 例 4 失败均为既有基线（Mount/AgentSettings/ImageReply/Skillhub 联网类）
  * 文档：README 第五章表格与 5.1/5.2/5.3 装配段重写、第十三章坑位 11-15 同步

* **修复三处真实缺陷（P0/P1/P2）+ 两处清理（P3/P4）**（按用户"重新确认后再修"的要求，上一轮曾误报 4 项，此处仅保留复核后成立者）:
  * **P0 `HarnessEngine.setMemoryEnabled` 空指针**：`memoryTalent` 只在注入 `memoryProvider` 时构建（否则显式置 null），而该方法无守卫直接解引用；相邻的 `setMemoryRelevanceCount` / `setMemoryPriorityCount` / `setMemorySummaryLength` 三个邻居都有判空。未注入 provider 的宿主调一次开关即 NPE。已补守卫（配置值仍写入 options，无 talent 可开关属正常状态）
  * **P1 `getDefaultModelConfig` 回落不校验 `enabled`**：显式名路径（`getModelOrNil` / `getModelOrDef` 的命中分支）都检查 `isEnabled()`，回落路径却直接返回 `models.get(defaultModel)` 或 `values().iterator().next()`。后果：模型被禁用但仍是 `defaultModel` 或 Map 首项时，`getModelOrDefInstance` 会真的构建并调用已禁用模型；`/model` 的 active 标记还会指向被 `formatModels` 过滤掉的模型。已改为两条路径都跳过 disabled、全禁用时返回 null；**连带** `AgentFactory.create` 对 null 模型给出明确 `IllegalStateException`（此前会落到 `ReActAgentConfig` 的 `chatModel is required`，无线索）
  * **P2 `PermissionContext` null 契约不一致**：`addRule(null)` 把 null 真加进列表（`PermissionEngine` 遍历 `rule.toolName()` 会 NPE）；`addRules/replaceRules/addDirectories/removeDirectories/Builder.rules/Builder.additionalDirs` 直接 NPE。统一为「null 列表 = 无操作、null 单元素 = 忽略」，与 `HarnessEngine.addPermissionRule` 的上游守卫同语义；构造器额外剔除 null 元素做兜底
  * **P3 `GoalState.fromONode` 空状态 NPE**：`fromMap` 缺 status 规范地返回 null，`fromONode` 却 `Status.valueOf(null)` 抛 NPE。已对齐为返回 null；`toONode` 补 javadoc 说明运行时连续性字段（lastFingerprint/lastErrorType/lastErrorSummary/consecutiveSameTypeErrors）**按设计不持久化**——它们描述单一进程内的执行连续性，跨重启保留会误判去重与错误熔断（与 `toMap` 同一取舍）
  * **P4 清理**：`HarnessOptions.workspace` 去掉 `volatile`（唯一写入在构造器，`volatile` 是死修饰）；删除 `getHarnessDownload()`（`HarnessEngine` + `HarnessOptions` 两侧，全仓零消费者，CLI 那份是 `AgentFlags` 自己的同名字段）
  * **已撤回的上一轮误报**（记录以免再次误判）：`recordError` 不递增 `consecutiveErrors` 是有意分工（同类型熔断 vs 总数熔断由 `GoalEngine:612/622` 分别取用）；`toONode` 丢 4 字段是有意设计；`Builder.mountAdd(null)` 与 `HarnessEngine.addMount(null)` 行为一致（都抛 `IllegalArgumentException`）；`InMemoryAgentSession.of` 每次新实例是工厂方法正常语义
  * 测试：`PermissionContextTest` 4 处「锁定 NPE 现状」断言翻转为「锁定正确行为」+ 新增 `constructorStripsNullElements`；`HarnessEngineAccessorTest` 新增 `setMemoryEnabledToleratesMissingMemoryProvider` / `fallbackModelSkipsDisabledConfigs` / `disabledDefaultModelFallsBackToNextEnabled`；`GoalStateTest` 新增 `onodeRoundTripToleratesNullOrMissingStatus` 并改写 2 处旧注释；`HarnessOptionsAccessorTest` 的 disabled 模型断言翻转
  * 验证：受影响 4 类 81 例全绿；harness 全量 594 例 20F+8E，失败类集合与基线逐项一致（AgentDefault / AgentFactoryCommandSession / BashToolStrategy / HarnessAgentMount / HarnessExtensionLifecycle / HarnessSkillCatalog / WebToolStrategy / WriteToolStrategy，均为既有 `sessionProvider` NPE 类）

* **HITL 决策动作枚举化：`HarnessInput.hitl(String, String)` → `hitl(HITLAction, String)`**（新增 `react/intercept/HITLAction`）:
  * 问题：字符串动作约束性太弱——引擎侧 `"approve".equals(action)` 把一切非精确匹配值（含 `"Approve"`、拼写错误）静默判为拒绝执行；且 SKIP 在领域里存在（`HITL.skip` / `HITLDecision.ACTION_SKIP`）却从 `handle` 入口永远表达不出，只能二值
  * 枚举：`APPROVE / REJECT / SKIP` 三值，含边界工厂 `of(String)`——空值返回 null（非决策），小写三值精确匹配，脏值抛 `IllegalArgumentException`；**不做大小写容错**（容错等于把「静默当拒绝」这类 bug 放回来）。放在 `react/intercept` 与 `HITL`/`HITLDecision`/`HITLTask` 同包，依赖方向正确（harness → agent）
  * 引擎：`applyHitlDecision` 改三值 switch，SKIP 分支接 `HITL.skip(session, task[, comment])`（目标方法本就存在）；`isHitlDecision()` 简化为 `hitlAction != null`（枚举时代无空字符串）
  * **转换点按约定放宿主侧**（协议层签名 `WorkspaceRuntimePort`/`WebGate` 保持 String 不动）：Web 在 `WebChatInputHandler`（`.hitl(HITLAction.of(hitlAction), hitlCallId)`，脏值落入既有 try-catch），桌面在 `DesktopInputRouter`（同模式，异常由既有 catch 回 socket 错误），CLI 无线上字符串直接写枚举
  * 顺带修复 CLI 外部预置决策 bug：`existing.isRejected() ? "reject" : "approve"` 漏了 `isSkipped()`，外部预置的 skip 会被升格为 approve 重新提交覆盖原决策；改三态透传
  * 不用 `HITLDecision` 直接作参数的理由：它是管线内可变实体（携 modifiedArgs/alwaysAllow 等 applyHitlDecision 不消费的字段，类型承诺会静默无效）、无 callId 位置、其 action 本身是 int 常量而非枚举
  * 测试：`HarnessInputTest` 新增 `hitlActionOfParsesProtocolValues`（空值/三值/脏值抛异常）；既有 10 处调用点迁移，`hitl("", null)` 用例随空字符串语义消失而删除
  * 行为变化（仅脏值）：非法 action 从「引擎内静默按拒绝执行」变为「宿主解析点显式报错」；合法路径零变化
  * 验证：harness 定向 3 类 19 例全绿；全量 379 例 20F+8E 与基线一致（20F/8E 为 WriteToolStrategyTest 等既有基线）；`soloncode-cli` compile 通过、全量 1204 例 2 失败与基线完全相同

* **HITL 决策与续流合并为一次 `handle`（`InputOutcome.Kind.HITL` 保留为批量审批形态）**：
  * 问题：单卡同步审批的宿主（CLI）必须发两次 `handle`——先 `.hitl(...)` 拿到不携带请求的 `HITL`，再 `.resume(true)` 拿 `TASK`；而 `HITL` 分支原本无条件 `return`，使 `.hitl(...)` 与 `.resume(true)` 不能合用。CLI 因此绕开了唯一入口，直接调 `HITL.approve/reject(session, task)`，连带丢掉 `hitlOutput`（工具返回改写）与引擎的 callId 精确定位能力
  * 改为：HITL 分支写入决策后**自检三项**，全部成立才同一次调用完成续流并返回 `Kind.TASK`，任一不满足返回 `Kind.HITL`。三个条件：① `applyHitlDecision` 返回 true（决策真的命中挂起任务，改为返回 `boolean`）；② 新增 `pendingHitlCount(session)` == 0（本批已齐备）；③ 宿主显式传 `resume`。未新增引擎入口，只在既有分支内增加组合出口
  * 为何必须自检而非仅看 `isResume()`：①无命中时续流会因空提示词回落 `trace.getOriginalPrompt()`，把上一轮问题原样重跑（过期审批卡场景）；②批挂起时过早 resume 会把未决卡一起带走。仅有 `isResume()` 判断会把这两个问题留给宿主各写一份
  * **批量审批语义（旧记录已过时，以本条为准）**：三端统一 `hitl(...).resume(true)` 一次调用，宿主只 `switch (kind)`；不再需要宿主自持 `allHitlDecided`。「无挂起」视为已齐备，故 `resume(true)` 单独使用（宿主批量写完全部决策后统一恢复）的既有语义不变
  * `InputOutcome` 新增三个事实读取（不改 kind 推导，零兼容风险）：`isHitlInput()`、`isHitlApplied()`（null = 非审批输入，false = 过期审批卡）、`getPendingHitlCount()`；新增 `task(..., boolean hitlApplied)` 与 `hitl(agent, model, applied, pending)` 四参工厂（两参版合并后无调用者，已删除——留着会让 `isHitlApplied()` 返回 null 而非 false，宿主 `== FALSE` 判断被静默跳过）；`Kind.HITL` 与 `HarnessInput.resume` 的 javadoc 补全三种进入方式与「resume 只表达意愿、不保证续流」
  * CLI 侧：`performAgentTask` 的 HITL 循环改为 `handleHitlRound(session, agentName, modelName)`，内部走 `engine.handle(HarnessInput.of(session, null).hitl(action, task.getCallUuid()).resume(true).selectedModel(...).selectedAgent(...))`，返回必含请求的 `InputOutcome` 供下一轮订阅。决策按 `task.getCallUuid()` 精确指定（原实现提交 task 对象，语义相同但多依赖了一轮 `getPendingTask` 定位）；已存在的决策（外部预置 / 上轮残留）不再二次提问
  * 顺带修复 CLI 三处：①`runMessageInput` 的 `.terminal(true)` 与 `.commandContextFactory(...)` 是死配置（`commandAware(false)` 已保证 `dispatchCommand` 不会执行，而这两个字段全仓仅 `dispatchCommand` 读取）——合并时去重为单一 `handleInput(session, text, commandAware, model)`，这两个字段只在 `commandAware=true` 时装配；②`currentPrompt` 死变量（只被赋 null 与判空，内容从不使用）改名为 `isFirstRound`；③每轮 `isTaskCompleted.set(false)` 重置，避免上一轮收尾被当成本轮完成信号（原先靠 `currentPrompt = null` 间接表达）
  * 测试：`InputCommandAwareTest` 新增 `hitlDecisionWithResumeReturnsTaskInOneCall`（断言 `Kind.TASK` + 请求非空 + 代理名透传）与 `resumeAloneStillReturnsTask`；`HitlAgentResolutionTest` 3 例与 `InputCommandAwareTest` 另 4 例继续锁定「不传 resume 仍得 HITL」的批量语义
  * 文档（三端合并后重写，旧「单卡一次 / 批量两次」表述已全部过时）：README §2.1 决策步骤（“同一次调用**尝试**续流”）、§2.2 `resume` 开关（补“只表达意愿、不保证续流”）、§2.3 `Kind.HITL` 宿主动作与新增的「HITL 态三种情形」表（齐备未传 resume / 尚有未决卡 / 过期卡，各列判据与宿主动作）、§2.3 第三条「不对 HITL 自动续流」、§3.1 最小骨架 HITL 分支、§3.2 `decide` 改为一次调用 + `onHitl` 按事实分流、§3.5 不变量第一条与第四条（新增「不要用 `HITL.getPendingTasks().isEmpty()` 判齐备——该集合含已决策项，会把已齐备的批永远卡住」）、§13 常见坑第 2 条；`InputOutcome.Kind.HITL` 与 `HarnessInput.resume` 的 Javadoc 同步
  * 验证：harness 定向 8 类 46 例全绿；harness 全量 378 例 20F+8E（比上轮 376 例多 2 例即本次新增两例，失败数与基线一致）；`soloncode-cli` 全量 1204 例 2 失败均为既有基线（`ImageReplyLifecycleTest` 异常文案、`AgentSettingsControllerTest` 返回 400）

* **终端能力上收为输入属性：`HarnessInput.terminal` + `Command.terminalOnly()`**（删除 `CommandContextFactory.isRemote()` 与 `Command.cliOnly()`）：
  * 问题：原来的“远程”标志放在**工厂**上（`BufferedCommandContextFactory(boolean remote)` + 匿名子类覆盖 `isRemote()`），而工厂的职责是“怎么构造上下文”；更糟的是这个名字与实际语义不符——桌面端 `origin=NONE` 且运行在本地却 remote=true，CLI 有 `InputOrigin.channel("CLI")` 且实际具备终端却 remote=false。每次输入都会变的东西被固化在工厂构造参数里，且 `HarnessInput` 已有的 `origin` 无法推导出它（两者正交）
  * 改为：`HarnessInput.terminal(boolean)`（默认 false）作唯一真相源，经 `CommandDispatcher.dispatch(..., boolean terminal)` 同时驱动“拦截”与“上下文自述”。`Command.cliOnly()` → `terminalOnly()`（原名里的 “CLI” 指宿主形态，而命令真正需要的是终端能力）；`CommandContext.isRemote()` → `isTerminal()`（消除双关命名）；`CommandContextFactory.create` 增 `boolean terminal` 参，工厂退化为无状态（删字段、删构造参、删匿名子类）
  * 拦截判据从 `target.cliOnly() && factory.isRemote()` 变为 `target.terminalOnly() && !terminal`——直接可读为“没有终端却要求终端就拦截”；`HelpCommand.formatHelp` 第三参改名为 `terminal` 并取反，两处双重否定（`remote && cliOnly`）一并消除
  * 宿主侧：`CliShell` 两处 `HarnessInput` 补 `.terminal(true)`（否则 `/exit` 会被拦截、`/help` 裁掉 `terminalOnly` 命令——本次唯一可能导致真实回归的点）；`webCommandFactory()` / `desktopCommandFactory()` 退化为无参 `new BufferedCommandContextFactory()` 且不再注入 boolean（行为不变）；`CliCommandContext` 增 `terminalCapable` 字段（避免与 JLine `Terminal` 撞名）并覆写 `isTerminal()`，保证上下文自述与拦截判据同源
  * 连带改名：`ExitCommand.cliOnly()` → `terminalOnly()`；`ChatWebController.hints`、`WebChatInputHandler` 两处忙态判定的 `cliOnly()` 同步；`WebChatInputHandler` 原“远程渠道”注释改为终端语义
  * 存留的 `COMMAND_UNAVAILABLE_HINT`（“仅支持 CLI，Web/IM 端不可用”）未改文案：它是对用户的可读说明，保持稳定
  * 测试：`CommandDispatcherTest` 改断 `assertFalse(ctx.isTerminal())` 并新增 `bufferedContextHonorsTerminalFlag`（9 例）；`HelpCommandTest` 辅助方法 `cliOnlyCommand` → `terminalOnlyCommand`、四个 `formatHelp` 第三参取值全部取反、用例名改 `nonTerminalHelpListsOnlyCommandsAvailableToIm` / `terminalHelpKeepsTerminalOnlyCommands`（3 例）
  * 验证：harness 定向 6 类 37 例全绿；harness 全量 376 例 20F+8E（与改动前基线一致，多出 1 例即新增用例）；`soloncode-cli` 全量 1204 例 3 失败均为既有基线/联网类（`ImageReplyLifecycleTest` 异常文案、`AgentSettingsControllerTest` 返回 400、`SkillhubMarketTest.testInstallRealSlug`）

* **`HarnessInput` 命名收敛 + 命令上下文工厂下沉**：
  * `model(String)` → `selectedModel(String)`（`getModelName` → `getSelectedModel`）、`agent(String)` → `selectedAgent(String)`、`mentionAware(boolean)` → `agentAware(boolean)`（`isMentionAware` → `isAgentAware`）。原本 setter 叫 `model`/`agent` 而 getter 叫 `getModelName`/`getSelectedAgent`、字段叫 `selectedAgent`，三名互不一致；`mentionAware` 实际只解析 `@` 子代理提及，与 `commandAware`（`/` 前缀）不成对。**未保留旧名**（尚未发布），`AgentMentions.Options.mentionAware` 同步改名 `agentAware`（它是同一个开关的内层载体，两者不同名会让插件作者写错）
  * **删除 `handle(HarnessInput, @Nullable CommandContextFactory)` 两参版本**，改为 `HarnessInput.commandContextFactory(...)` 字段由 `handle(HarnessInput)` 唯一入口读取。与 `commandAware` 配套：前者决定「是否识别命令」，后者决定「识别到命令后回执怎么落地」。`HarnessEngine.dispatchCommand` 相应去掉 `factory` 形参，改为 `input.getCommandContextFactory()`，为空时回退 `BufferedCommandContextFactory`
  * 受益点：`handle` 从 2 参归一，宿主不再需要在同一行既构造 input 又补第二个参数（CLI/Web/桌面三端各一处）；`InputBoundaryTest` 的「工厂由宿主实现」护栏不受影响（`CommandContextFactory` 仍是接口且仍在 input 包）
  * 测试：`HarnessInputTest` 改断 `getSelectedModel`，`InputHandleTest.mentionAwareFalseKeepsTextAsIs` → `agentAwareFalseKeepsTextAsIs`，`HitlAgentResolutionTest` 改 `selectedAgent`；harness 定向 10 类 69 例全绿

* **命令渠道来源统一为值对象 `InputOrigin`**（删除 `CommandContext` 上重复的四元组）：
  * 背景：`getSource` / `getSourceUserId` / `getReplyTarget` / `getMessageId` 在全仓以 7 种形状并存（`HarnessInput` 四字段、`CommandContext` 四 default getter、`WebCommandContext` 四 override、`BufferedCommandContextFactory` 四 override、`session.replyRoute` 裸 Map、`QueueItem` 四字段、`SteerMessage` 四字段），且形成「渠道 → 入会话 → 命令 → 队列/插话箱 → 分发器 → 回到 Channel」的闭环，实为同一实体的 7 份副本。实测 28 处签名中 100% 四字段同时出现，不存在单独消费，故合并为单一实体而非拆分
  * 新增 `input.InputOrigin`（不可变，`NONE` 表示本地无渠道来源）：`channel` / `senderId` / `replyTarget` / `messageId`，并提供 `isEmpty` / `isIm`（收编 `WebChatInputHandler.isImSource`）/ `channelOr`（收编 `"CLI"` 与 `"command"` 两处不同兜底）/ `channelIs`（收编各处 `equalsIgnoreCase`）/ `withReplyTarget`（微信 `context_token` 刷新）/ `withChannel` / `toMap` / `fromMap`。`toMap`/`fromMap` **键名沿用历史命名**（source/sourceUserId/replyTarget/messageId），保证队列快照与 `session.replyRoute` 落盘数据向后兼容
  * `HarnessInput` 四字段合为 `origin`；新增 `origin(InputOrigin)`，旧 `route(4 参)` 转调并标 `@Deprecated`，四个旧 getter 同步转为 `origin.getXxx()` 转调
  * **`Command` 新增三参 `execute(engine, ctx, InputOrigin)` 的 default 委托**（`@since 4.2`）：仅 `/queue`、`/steer` 覆写，其余约 20 个实现零改动。`CommandDispatcher.dispatch` 增加 `origin` 参数，由 `HarnessEngine.dispatchCommand` 直接取 `input.getOrigin()` 传入——这是本次核心：删除了「工厂从 input 嗅探元数据」的第二条注入路径（`BufferedCommandContextFactory` 不再持有 `HarnessInput`）
  * 删除 `CommandContext` 的四个 getter、`soloncode-cli` 的 `WebCommandContext` 整个类（改造后无任何逻辑）、`webCommandFactory` 的四参数与匿名实现（缩为 `new BufferedCommandContextFactory(true)`）、桌面端等价匿名工厂、`CliCommandContext.getSource` 硬编码；CLI 入口改由 `CliShell.CLI_ORIGIN` 显式声明来源
  * `QueueItem` / `SteerMessage` 四字段合为 `origin`（旧 getter 转调 + `@Deprecated`），`SessionQueue.enqueue` 与 `SteerInterceptor.steer` 新增 `InputOrigin` 重载。**同时删除 `enqueue(session,text,String)` 与 `steer(...,String source,BooleanSupplier)` 两个 String 重载**：它们与 `InputOrigin` 版本在传 null 时无法区分（编译期 ambiguous），保留即缺陷
  * `WebChatInputHandler.setReplyRoute` 改为接收 `InputOrigin` 并用 `origin.toMap()` 写入 `session.replyRoute`（读取侧仍按 Map，键名不变，避免牵动约 80 处渠道测试断言）；`WebQueueDispatcher` / `WebGate` / `QueueWebController` 改用 `origin`
  * 顺带纠正（已于下一节被取代）：`CommandContext.isRemote()` 当时经核实并非死方法（`HelpCommand` 用它裁剪 `cliOnly` 命令），故保留；本轮已将其连根替换为 `isTerminal()` + `HarnessInput.terminal`，`BufferedCommandContextFactory(boolean)` 与匿名子类覆盖一并删除
  * 测试：新增 `InputOriginTest`（11 例，锁定 legacy 键名往返、渠道大小写、`isIm`、不可变刷新）；`Fakes.StubContext` 去掉四字段改为 `imOrigin()`/`localOrigin()`；`QueueCommandTest`/`SteerCommandTest`/`SessionQueueTest`/`SteerInterceptorTest`/`WebGateRejectedInputTest` 改为 origin 调用；`ImBusySteerContractTest` 源码断言同步（并修正 `indexOf` 起点，避免命中插话之前的注释）
  * 验证：harness 目标域 79 例全绿，全量 375 例 20F+8E 与改动前完全一致（`sessionProvider` NPE 等既有基线）；`soloncode-cli` 全量 1204 例 2 失败均为既有基线（`ImageReplyLifecycleTest` 异常文案、`AgentSettingsControllerTest`）


* 修复 `handle` 的 HITL 决策结果子代理解析：`InputOutcome.hitl(...)` 的 `agentName` 原先只回显入参 `selectedAgent`，而宿主审批请求通常不带该字段（前端只回 callId + action），宿主据此续流会错误回落主代理——trace 按代理名分 key，取错代理取不到挂起的 tool_calls，表现为「点了审批但续跑空转」。现改为「显式入参优先，其次会话级 `CTX_AGENT_SELECTED`」（新增私有 `resolveHitlAgent`；`HitlAgentResolutionTest` 3 例锁定）
* `InputAttachments.isEmpty()` 删除（与 `hasAny()` 同一判断的两种叫法），`HarnessEngine.handle` 的 `final InputAttachments resolved = attachments` 别名一并去除
* `InputHandleTest` +1（`commandWithAttachmentIsTreatedAsMessage`）：锁定「带附件的斜杠文本不当命令」——附件是要交给模型的内容，不能因命令分发被静默丢弃；桌面端原实现把命令识别放在附件处理之前，带附件的 `/cmd` 会丢掉附件，现与 Web/引擎口径统一
* **删除 `InputOutcome.stream()` 与 `InputTextEvent`**（无生产调用者的过度设计，且语义方向与真实宿主需求相反）：
  * 该 API 承诺「回执 + 任务拼成同一条流，宿主无需分两段消费」，但三端真实需求恰好相反——CLI 由命令即时写终端（合成会重复打印）、Web 要求流门复位早于回执（否则回执被前端静默丢弃）、桌面要求 `streamHub` 序号回放与 HITL 拦截器重挂、ACP 需要 `takeWhile` 取消判定。任何替宿主拼好的流都得被拆开重拼
  * 发布包袱核查：`InputOutcome` 与 `stream()` 同属本次重构提交，v4.1.1 中均不存在，删除不破坏外部实现
  * 连带收窄 `InputOutcome`：移除不再需要的 `session` 字段，`command(...)` / `unavailable(...)` 各减一个入参（会话只被删掉的 `stream()` 用来构造回执事件）
  * 取流口径统一为唯一路径：`getRequest().stream()`；回执读 `getCommandOutput()`，先后顺序由宿主掌握。`HarnessEngine#handle` javadoc 与 README（取用方式、成员表、映射规则表、常见坑、模块树）同步纠正
  * 测试：`InputStreamTest` 删除（7 例）、新增 `InputHandleTest`（6 例，聚焦 kind 分派与回执取用），`InputPrefixTest`/`CommandAgentTaskTest` 中依赖 `stream()` 的断言改为直接断言 `getCommandOutput()` / `getKind()`

* `MediaBlock` 新增 `getFilePath()`（附件本地副本路径）：
  * 声明为 `default` 方法返回 null（`@since 4.2.0`），不破坏 3.9.2 以来直接 `implements MediaBlock` 的外部实现；`AbsMedia` 以字段覆盖并提供链式 `filePath(...)`
  * 语义：工作区相对路径，仅用于历史消息的文件名标签，**不参与任何 dialect 的请求载荷**（请求只读 `data`/`url`/`mimeType`）
  * 会随 `ChatMessage.toJson` 落盘并在 `fromJson` 恢复，`MediaBlockFilePathTest` 锁死往返与请求隔离

* 重构 soloncode harness 命令体系：删除 `CommandContext.runAgentTask` / `isAgentTask` 回调模式，改为「命令只做预处理、声明意图」——
  * 新增 `command.AgentTaskIntent`（session/prompt/model 三字段值对象），`CommandContext` 由「回调 + 布尔标记」换成 `requestAgentTask(prompt, model)`、`requestAgentTask(session, prompt, model)`、`getAgentTaskIntent()`；`BufferedCommandContext` 不再持有 `AgentTaskRunner`，CLI/Web 端的上下文实现与工厂也随之瘦身
  * `CommandDispatcher.dispatch` 在命令执行后，把意图重新交给唯一的 `engine.handle(...)` 完成二段装配（`commandAware(false)` 不再识别命令、`resume(空正文即续流)`、`agent(会话级选择)`），结果作为 `InputOutcome.command(..., nested)` 的新参数；kind 由 nested 推导（有任务即 `TASK`，否则 `REPLY`），调用方不再手工传 boolean。**本次不新增引擎入口**，`HarnessEngine` 对外仍只有 `handle(...)` 系列（送审时曾新增过 `commandAgentTurn` 草稿，现已删除）
  * 删除 `InputOutcome.isAgentTask()`（kind 已够），`hasRequest()` 降级为派生判定并修正过期注释；`InputOutcome.command()` 第 5 参由 `boolean agentTask` 改为 `@Nullable InputOutcome nested`
  * 修复 `/continue` 三端静默失效：旧实现把 `(null, null)` 喂给 `input()`，命中空输入判空返回 `IGNORED`，命令只做了 trace 手术、任务从不启动。现在意图带 `resume` 语义，二段走 `input()` 的续流分支（在空输入判空之前生效），三端 `/continue` 从此真正续跑
  * 修复 `/<cmd> <sessionId>` 跨会话错位：命令在 A 会话做手术、宿主把流开在 B 会话。意图带目标会话后二者统一
  * 命令产出的提示词统一做 @ 子代理识别（此前仅 CLI 解析，Web/桌面不解析），模型回退统一为「命令声明 → 会话选择 → 默认」
* `InputOutcome.Kind` 收敛为 4 值：`TASK` / `REPLY` / `HITL` / `IGNORED`（原 6 值 `MESSAGE` / `COMMAND` / `COMMAND_AGENT` / `CLI_ONLY_BLOCKED` / `HITL` / `IGNORED`）。旧设计把两个语义轴混在一个枚举里——`isCommand()` 必须写成三个常量的 OR 才能表达「这是不是命令」这一事实，宿主也因此要写两层派发（先 `isCommand()` 再 `switch(kind)`）。现在 **kind 只回答「宿主该做什么」，命令事实降级为字段谓词**：
  * `isCommand()` 改判 `commandName != null`（与旧三常量 OR 语义等价，一次性判定）；新增 `hasReply()`
  * `REPLY` 同时涵盖「命令已执行」与「命令在本渠道不可用（未执行）」，宿主动作相同故合并；`CLI_ONLY_BLOCKED` 这个名字把渠道名与拒绝原因写进结局名，随之一并删除
  * 命令触发的任务与用户手输的任务共用 `TASK`，差异退化为「有没有回执」这份数据（`hasReply()`），宿主一个 `switch` 即可分完
  * `InputOutcome.blocked(...)` → `InputOutcome.unavailable(...)`；`CommandDispatcher.CLI_ONLY_HINT` → `COMMAND_UNAVAILABLE_HINT`（提示文案不变，仍点名 CLI，因为它面向用户）
  * `stream()` 对 `TASK` 改为「回执（若有）后接请求流」的同一条流，使 `.stream()` 真正成为「一次输入的完整事件流」；无回执时仍直接返回 `request.stream()`（与 `getRequest().stream()` 同构）
* 添加 soloncode harness `CommandAgentTaskTest` 4 例（命令意图 → `TASK` + 请求透传、空正文续流不被 `IGNORED`、无意图仍为 `REPLY`、回执排在任务流之前）与 `CommandDispatcherTest` 4 例（意图记录、续流意图、跨会话语义、kind 推导）。
* 同步 README 命令扩展示例为 `requestAgentTask` 并说明「宿主按 kind 分路由、不要自己再拼 Prompt 或开流」。

* 命名定稿：`InputResult` → **`InputOutcome`**，`HarnessEngine.input(...)` → **`HarnessEngine.handle(...)`**（4.2 未发布，无兼容影响）
  * 理由一（避免与既有语义冲突）：本仓 `turn` 专指「一次 agent 执行轮次」（`maxTurns`、`TurnOutcome`、`turnsExecuted`），而 `IGNORED` / `REPLY` 根本不是轮次，三端变量名 `turn` 属术语错误；`Result` 在 soloncode-cli 侧被 30+ 文件用作 `org.noear.solon.core.handle.Result`（HTTP 响应包装），语义冲突
  * 理由二（对齐产品既有命名家族）：`Outcome` 已是第四处同构用法——`RunOutcome`（`success | interrupt`）、`StreamLoop.TurnOutcome`、`WeChatLink.PollOutcome`，都是「一次处理后归入几类结局」
  * 理由三（动词更诚实）：旧签名 `input(HarnessInput input, ...)` 一个方法内 `input` 出现三次且身份各异，形似 `getInput()`，而实际是「HITL 写入 + 命令识别与执行（有副作用）+ 附件落盘 + 提及解析 + Prompt 构建 + 请求装配」六步流水线；与纯装配、无副作用的 `prompt(...)` 并列时看不出量级差。`handle` 在本模块已有同义先例（`ExecutionBridge.handle`），且不与 `dispatch` / `dispatchCommand` / `execute` 撞车
  * 工厂 `InputOutcome.message(...)` → `InputOutcome.task(...)`（它返回的就是 `Kind.TASK`，旧名与 kind 不一致）
  * 三端变量统一 `turn` → `outcome`（含 `hitlTurn` → `hitlOutcome`）；`InputBoundaryTest` 的源码路径字面量与两个方法名同步（`inputOutcomeMustNotDependOnEngineOrScheduler` / `inputOutcomeStreamMustNotResumeHitl`）
  * 修正 `HarnessInput` 类注释里过期的两分支示例（原 `if (isCommand()) ... else ...` 在「命令触发任务」时会漏掉执行任务，正是四值改造要消灭的写法），改为 `switch (getKind())` 三分支
  * 修正 README 两处过期文案：`input(session, "/help")` 返回 `MESSAGE` → 返回 `TASK`；模块树 `InputResult（六态）` → `InputOutcome（四态）`

### 行为变更

* `InputOutcome.Kind` 的常量名与数量变化属编译期破坏（`4.2` 未发布，无兼容影响）：只看 `getKind()` 不再能区分「命令触发」与「用户手输」，改读 `isCommand()` / `getCommandName()`。
* 远端渠道（Web/IM）现在也会收到 CLI 专属命令的不可用提示的 IM 转发（此前仅 Web 前端可见），属修正：IM 用户执行 `/exit` 至少能看到原因。

### 可测试目标

* Trae
* Dify
* Cherry Studio


### 待定


*  mcp channel 概念改为 transport （并保持兼容）???

### 4.2.0

* 调整 solon-ai-mcp 升为 mcp-java-jdk 2.1.0
* mcp-core 升为 mcp-java-sdk 2.1.0
* mcp-json-jackson2 升为 mcp-java-sdk 2.1.0

### 4.1.1

* 添加 solon-ai-core `ChatAccumulator` 承担事件归并、终态聚合与方言状态；`ChatDialect` 解析入口调整为 `parseResponseJson(ChatStreamContext, String)`。（**不兼容变更**：第三方方言需同步适配）
* 添加 solon-ai-core ToolMessage.isError 字段
* 添加 solon-ai-agent `AbsReActEvent`
* 添加 solon-ai-agent turnId 替代 reasonId (后者标为弃用)
* 添加 solon-ai-harness Command.runnableWhenBusy 方法（标注对话忙时也可运行，默认否）
* 优化 solon-ai-core `AssistantMessage` thinking/text 双字段及取值方法；新增版本化 `protocolStates` 与类型化 `SearchResult`/`Citation`，弃用但保留旧 `contentRaw`/raw 字段反序列化兼容。
* 调整 solon-ai-core `ChatResponse` 为只读终态结果，新增 `getToolCalls()`、`getBlocks()`、`getSearchResults()`、`getCitations()`；移除多候选、累积器、流状态及请求上下文相关旧方法。（**不兼容变更**）
* 调整 solon-ai-harness `MountDir` 重构为 `MountSource` 接口及相关调整
* 修复 solon-ai-core 空正文、静默空流、工具参数多根/尾随数据、Agent 取消/NPE、Flow 流类型适配、Anthropic 缓存率与叠字等问题；协议状态绑定后深层冻结，OpenAI Responses 解析工作区采用私有命名空间。
* 修复 solon-ai-mcp 增强 MCP 工具结果响应异常处理，确保异常可见并保证响应流正常终止

### 4.1.0

* 添加 solon-ai-core  `docs/chat-event-model.md`，说明事件分组、边界约束、取消语义及第三方方言迁移方式。
* 添加 solon-ai-core 事件模型、`ChatEventFilter`、`ChatEventNormalizer`、`ChatStreamSession` 与 `ChatStreamContext`，支持多内容块、并行工具调用、边界补齐和订阅级状态隔离。
* 添加 solon-ai-core `ChatAccumulator` 承担流式累积与方言状态；`ChatDialect` 解析入口调整为 `parseResponseJson(ChatStreamContext, String)`。（**不兼容变更**：第三方方言需同步适配）
* 添加 solon-ai-agent `ContextSizeEvent.contextLength`，并支持 Harness 默认压缩上下文长度配置。
* 添加 solon-ai-ui-aisdk `AgUiStreamWrapper`；重写 `AiSdkStreamWrapper` 多块状态机并完善取消、异常和结束传播，旧响应流适配方法更名为 `toAiSdkStreamOfResponses`。（**不兼容变更**）
* 优化 solon-ai-core  `EmbeddingModel`、`GenerateModel` 标准/provider/model 信息接口；`EmbeddingConfig.toString()` 增加 standard。
* 优化 solon-ai-core  `AssistantMessage` thinking/text 双字段及取值方法
* 优化 solon-ai-agent  `ReasonTask`、`SimpleAgent`、`SupervisorTask` 的思考/正文边界、终态归约、取消处理与异常结束补位；工具和动作事件统一由 `ActionTask` 发出。
* 优化 solon-ai-talents ChatModel 事件生命周期：内容块按 group/itemId/index 独立管理；`STEP_END` 携带单步 Usage，`RESPONSE_END` 携带累计 Usage，终态默认结束原因为 `stop`。
* 优化 solon-ai-llm-dialects OpenAI Responses、Anthropic、Gemini Interactions 等方言的状态、服务端工具、媒体、引用、拒绝、签名、心跳及错误事件映射。
* 优化 solon-ai-talents Code Talent 扫描缓存、LSP 超时体验及 OpenAI/Anthropic 方言兼容性。
* 调整 solon-ai-core `ChatResponse` 为不可变终态结果，新增 `getToolCalls()`、`getBlocks()`、`getAlternatives()`；移除累积器、流状态及请求上下文相关旧方法。
* 调整 solon-ai-core  Agent 流事件直接携带 `ChatEvent`，移除 `ChatDeltaEvent` 及各类旧 Chunk；Harness 终态统一使用 `RunEndEvent`。（**不兼容变更**）
* 调整 solon-ai-core 流式 API：`ChatModel.stream()` 改为 `Flux<ChatEvent>`。
* 调整 solon-ai-agent 移除 `ReasonDeltaEvent.isFinished()/isError()`；完成状态改取 `RunEndEvent`，异常状态改取 `ReActTrace.isAbnormal()`。（**不兼容变更**）
* 修复 solon-ai-core 空正文、静默空流、工具参数重复、Agent 取消/NPE、Flow 流类型适配、Anthropic 缓存率与叠字等问题。


变更说明：

| 旧接口                             | 新接口                          | 说明 |
|------------------------------------|---------------------------------|------|
| `chatModel:<ChatResponse>stream()` | `chatModel:<ChatEvent>stream()` |      |



### 4.0.6

* 添加 solon-ai-agent Metrics 缓存率计算支持
* 优化 solon-ai-llm 方言工具调用的兼容性处理（有些 toolCalls 参数格式不完整）
* 优化 solon-ai-llm gemini 方言兼容性处理
* 优化 solon-ai-mcp McpTalent 兼容性
* 调整 solon-ai-mcp IMcpHttpServerTransport 接口，完善生命周期（更方便动态增减）

### 4.0.5

* 添加 solon-ai-core ModelOptionsAmend http 定制传递（HarnessEngine->ReActAgent->ChatRequest）
* 优化 solon-ai-mcp 兼容处理无事件类型的SSE消息
* 优化 solon-ai-dialect-openai openai-responses 方言适配
* 优化 solon-ai-harness TaskTalent 增加 maxTasks 限制
* 优化 solon-ai-talent-web CodeSearchTalent，WebsearchTalent 适配
* 优化 solon-ai-talent-memory 引导词及搜索能力
* 优化 solon-ai-talent-cli TerminalTalent 引导词
* 优化 solon-ai-talent-mount MountManager 添加 disallowSkills
* 修复 solon-ai-talent-cli Windows 环境变量可能识别失误的问题
* 修复 solon-ai-talent-gateway OpenApiV2Resolver 可能无限递归解析的问题

### 4.0.4

* 添加 solon-ai-core ModelOptionsAmend.reasoning_effort 统一选项 API
* 添加 solon-ai-core ModelOptionsAmend.thinking 思考模式开关统一选项 API
* 添加 solon-ai-core Prompt.copy 方法
* 添加 solon-ai-core ChatRequestDesc.role, instruction, systemPrompt 方法
* 添加 solon-ai-core AssistantMessage 多模态支持
* 添加 solon-ai-agent HITLStrategy 接口，替代 HITLInterceptor.InterventionStrategy（后者标为弃用）
* 添加 solon-ai-agent ActionChunk, ObservationChunk, ToolExchanger callId 属性
* 添加 solon-ai-agent 新流块 RunStartEvent, RunEndEvent, ReasonStartEvent, ReasonEndEvent 事件块
* 添加 solon-ai-agent 新流块 ToolStartEvent、ToolEndEvent
* 添加 solon-ai-agent 新流块 HITLPendingEvent、HITLDecidedEvent
* 添加 solon-ai-agent 新流块 ActionStartEvent、ActionEndEvent、ReasonDeltaEvent（替代 ReasonChunk）
* 添加 solon-ai-agent 新流块 TeamStartEvent、TeamEndEvent（替代 TeamChunk）
* 添加 solon-ai-agent 新流块 SimpleStartEvent、SimpleEndEvent（替代 SimpleChunk）
* 添加 solon-ai-agent ReActInterceptor onActionStart, onActionEnd 事件
* 添加 solon-ai-agent ReActInterceptor onToolCallStart, onToolCallEnd 事件
* 添加 solon-ai-harness 新的工具权限体系
* 添加 solon-ai-harness ToolName 类，替代 ToolPermission（后者标为弃用）
* 添加 solon-ai-harness AgentDefinition 链式构造方法
* 添加 solon-ai-harness TaskWrapChuck 类，包装子代理的流块，提高子代理调用的透明度
* 优化 solon-ai-core AbstractChatDialect 方言适配
* 优化 solon-ai-agent TeamAgent 相关实现代码
* 优化 solon-ai-agent hitl 实现（以支持多工具批量）
* 优化 solon-ai-agent ContextCompressionInterceptor 压缩算法
* 优化 solon-ai-talent-cli bash 脚本处理
* 优化 solon-ai-dialect-anthropic 方言适配
* 优化 solon-ai-dialect-openai Responses 方言适配
* 优化 solon-ai-dialect-dashscope 方言适配
* 优化 solon-ai-dialect-gemini 方言适配
* 优化 solon-ai-harness 增加 skills 和 agents 局部（分组）动态刷新能力
* 调整 solon-ai-agent ToolCallEndEvent 替代 ObservationChunk, ToolCallStartEvent 替代 ActionChunk（后者标为弃用）
* 调整 solon-ai-agent ReActInterceptor onToolCallEnd 替代 onObservation，onToolCallStart 替代 onAction（后者标为弃用）
* 调整 solon-ai-agent ContextCompressionInterceptor 取消 maxTokens，由 maxContextLengthRatio 替代
* 调整 solon-ai-agent ContextCompressionInterceptor 取消 chatModelSupplier 参数（标为弃用）
* 调整 solon-ai-agent AgentChunk 更名为 AgentEvent
* 调整 solon-ai-harness 重构 hitl 实现
* 调整 solon-ai-harness getModelOrMain 标为弃用，由 getModelOrDefInstance 替代
* 调整 solon-ai-harness 取消 compressionMaxTokens，由 compressionMaxContextRatio 替代
* 修复 solon-ai-core ChatModel.stream 内部多次请求可能卡死的问题
* acp-sdk 升为 0.14

### 4.0.3

* 新增 solon-ai-loop 插件
* 新增 solon-ai-talent-code 才能插件（从 solon-ai-harness 分离出来）
* 新增 solon-ai-dialect-gemini gemini-interactions 方言适配
* 添加 solon-ai-core Talent.setEnabled 方法（提供接口级的开关设置）
* 添加 solon-ai-core 方言缓存控制机制
* 添加 solon-ai-agent ReActTrace.agentName 字段（不再从 config 获取）
* 添加 solon-ai-agent SimpleTrace.agentName 字段（不再从 config 获取）
* 添加 solon-ai-talent-cli TerminalTalent.maxCharacterLimit 设置
* 优化 solon-ai-dialect-x 方言解析
* 调整 solon-ai-harness GenerateTool 更名为 GenerateTalent（方便动态启用/禁用）
* 修复 solon-ai-core 方言结束时没有 choice 时，不会触发最后一个 stream 输出的问题（造成无法获取 getUsage）
* mcp McpSchema.Sampling 类添加 `@JsonIgnoreProperties(ignoreUnknown = true)` 增加兼容性

### 4.0.2

* 添加 solon-ai-harness allowToolReset,disallowToolReset 方法
* 添加 solon-ai-harness 子代理 ContextSizeChunk 流传递
* 优化 solon-ai-agent ContextCompressionInterceptor 对单条消息超过 maxTokens/2 进行限制（避免上下文溢出）
* 优化 solon-ai-talent-cli TerminalTalent.grep 参数（采用正则匹配，并增加 include 参数，）
* 优化 solon-ai-talent-cli TerminalTalent.bash 参数（增加 max_output_chars 参数，对输出限流）
* 优化 solon-ai-talent-cli TodoTalent 返回内容，增加各状态数据描述
* 优化 solon-ai-talent-web CodeSearchTalent，WebfetchTalent，WebsearchTalent 返回格式
* 调整 solon-ai-talent-memory 接口 MemorySolution.Factory 改为 MemorySolutionProvider
* 重构 solon-ai-core EmbeddingOptions 参数合并逻辑，将字符串类型的配置值自动转换为布尔、整数或浮点等强类型
* tika 升为 2.9.4

### 4.0.0

* 变更 solon skill “概念”改为 solon talent（避免与 agent skill 的冲突）
* 新增 mcp-core（替换之前的 mcp-sdk，保持与官方相同命名）
* 新增 mcp-json-jackson2
* 新增 solon-ai-sandbox
* 新增 solon-ai-mcp MCP_2025_11_25 协议支持（支持 ServerTransportSecurityValidator 签权）
* 新增 solon-ai-talent-mount 才能插件（原 PoolManager 独立出来）
* 添加 solon-ai-talent-openapi OpenApiSkill.removeApi 方法
* 添加 solon-ai-talent-openapi ApiSource 超时配置支持
* 添加 solon-ai-talent-gateway McpGatewaySkill 专门处理 mcp（ToolGatewaySkill 可以处理所有工具）
* 添加 solon-ai-talent-cli TerminalTalent 系统级沙盒限制支持
* 添加 solon-ai-core ChatOptions:httpCustomize 方法
* 添加 solon-ai-core Talent::isEnabled 方法
* 添加 solon-ai-core AiConfig::standard（替代 provider 之前的作用）
* 添加 solon-ai-core ReActAgent::getModel 方法
* 添加 solon-ai-agent ReActInterceptor.onReasonStart 事件方法
* 添加 solon-ai-agent AgentTrace.getOriginalPrompt, getWorkingMemory 方法
* 添加 solon-ai-agent AgentTrace.getRunId 方法
* 添加 solon-ai-agent AgentChunk.getRunId 方法
* 添加 solon-ai-agent TeamAgent 添加初心标记
* 添加 solon-ai-agent ToolExchanger 类
* 添加 solon-ai-mcp McpClientProvider allowedTools, disallowedTools 工具控制机制
* 添加 solon-ai-harness bashAsyncEnabled 开关
* 添加 solon-ai-harness HarnessEngine.removeApi 方法
* 添加 solon-ai-harness HarnessProperties.mountPools 属性（替代 skillPools，后者标为弃用）
* 添加 solon-ai-harness 用 McpGatewaySkill 替换 ToolGatewaySkill（更有争对性）
* 优化 solon-ai-agent 初始化时（rest）添加快照持久化
* 优化 solon-ai-harness AgentFactory `edit` 权限添加 `read`、`write` 控制
* 优化 solon-ai-agent ContextCompressionInterceptor 压缩时机（从 onObservation 改为 onReasonStart），并增加系统词大小
* 优化 solon-ai-agent ContextCompressionInterceptor 增强上下文压缩拦截器中过期区 tool-use 原子序列的追溯保护，并完善文档注释
* 优化 solon-ai-agent ContextCompressionInterceptor 算法，增加 tools token
* 优化 solon-ai-dialect-openai OpenaiResponses 协议兼容性
* 调整 solon-ai-core 取消 ChatConfig.reasoningFieldName 配置（这个配置不合理）
* 调整 solon-ai-core 移除 SkillProvider 类
* 调整 solon-ai-core 移除 toolAdd(Object) 方法（指向不明，容易出错）
* 调整 solon-ai-core 移除 defaultToolAdd(Object) 方法（指向不明，容易出错）
* 调整 solon-ai-agent 移除 toolAdd(Object) 方法（指向不明，容易出错）
* 调整 solon-ai-agent 移除 defaultToolAdd(Object) 方法（指向不明，容易出错）
* 调整 solon-ai-mcp McpClientProperties 默认不启用心跳（之前默认为 30 心跳一次）
* 调整 solon-ai-mcp McpProviders 更名为 McpClientProviders
* 调整 solon-ai-agent ActionChunk：ActionChunk 更名为 ObservationChunk；ActionStartChunk 更名为 ActionChunk
* 调整 solon-ai-agent ReActInterceptor.onAction,onObservation: toolName,args,result 合并为 toolExchanger（并允许修改）
* 调整 solon-ai-agent ReActInterceptor.onReason 更名为 onReasonEnd（和 onReasonStart 凑成一对）
* 调整 solon-ai-agent ReActAgent.maxSteps 更名为 maxTurns（保持行业习惯）
* 调整 solon-ai-agent SummarizationInterceptor 更名为 ContextCompressionInterceptor（更符合语义）
* 调整 solon-ai-talent-cli PoolManager 实现细节，并添加 refresh(alias)
* 调整 solon-ai-talent-cli 技能标识由 aliasPath 统一改为 name，并优化技能池加载逻辑及代码格式
* 调整 solon-ai-talent-web WebfetchTool 更名为 WebfetchTalent; WebsearchTool 更名为 WebsearchTalent; CodeSearchTool 更名为 CodeSearchTalent
* 调整 solon-ai-talent-diff ApplyDiffTool 更名为 ApplyDiffTalent；ApplyPatchTool 更名为 ApplyPatchTalent
* 调整 solon-ai-harness HarnessEngine 打断与 HarnessProperties 关联（后者删除），避免耦合影响未来的调整
* 调整 solon-ai-harness HarnessEngine 移除 mainModel 字段（减兴缓存麻烦）
* 移除 solon-ai-skill-browser（包太大了，没必要）
* 移除 solon-ai-skill-restapi （由 solon-ai-talent-gateway 替代）
* 移除 solon-ai-skill-toolgateway （由 solon-ai-talent-gateway 替代）
* 移除 mcp-sdk（由 mcp-core 替代）
* 修复 solon-ai-mcp WebRxStreamableHttpTransport 没有 event 的消息会出错的问题（优化非规范兼容）
* 修复 solon-ai-mcp WebRxStreamableHttpTransport 没有 contentType 的消息会出错的问题（优化非规范兼容）
* 修复 solon-ai-agent ContextCompressionInterceptor 压缩后可能会引起 ToolMessage 无法对齐的问题
* mcp-sdk 升为 1.1.3


变更对照表：

| 旧方法或参数                                            | 新方法或参数                                            |   |
|---------------------------------------------------|---------------------------------------------------|---|
| `ReActInterceptor.onAction(,toolName,args,)`      | `ReActInterceptor.onAction(,toolExchanger,)` |   |
| `ReActInterceptor.onObservation(,toolName,args,)` | `ReActInterceptor.onObservation(,toolExchanger,)` |   |
|                                                   |                                                   |   |
| `(toolName, args)` + `trace.lastObservation`      | `ToolExchanger` (thread-safe)                     |   |




### 3.10.7

* 添加 solon-ai-core ChatResponseDefault attrs 附件属性机制（方便方言解析处理），及 openai、anthropic、openai-response 方言调整
* 优化 solon-ai-dialect-anthropic message_stop 处理（增加 isEmpty 检测）
* 修复 solon-ai-skill-memory MemoryMdData 因key格式问题未完全加载的问题
* 修复 solon-ai-agent ReasonTask ‘您上一次的回答是空的’ 可能造成死循环的问题

### 3.10.6

* 调整 ChatDialect.buildRequestJson 返回类型（String 改为 ONode，方便日志定制）
* 修复 solon-ai-skill-web WebfetchTool 超时转换错位问题

### 3.10.5

* 添加 solon-ai-agent AgentTrace.getAgentName, getBeginTimeMs 方法
* 添加 solon-ai-agent 使用 autoRethink 替代 maxStepsAutoExtensible（后者标为弃用）
* 添加 solon-ai-harness command 接口管理
* 添加 solon-ai-harness HarnessEngine.getAgentOrMain 方法
* 添加 solon-ai-core CmdUtil 辅助工具（主要解析参数）
* 添加 solon-ai-core ChatMessage.getCreatedAt 方法
* 添加 solon-ai-core ChatSession.removeLatestMessage 方法（对话回退支持）
* 添加 solon-ai-core Repository.promptAugment 快捷方法
* 添加 solon-ai-core AiConfig.enabled 配置属性
* 添加 solon-ai-skill-cli TerminalSkill.edit old_str 预检处理（llm 可能会传空）
* 优化 solon-ai-agent ReasonTask Missing traceKey 处理
* 优化 solon-ai-core AiConfig.proxy 配置兼容（减少反序列化反射）
* 优化 solon-ai-core ChatModel llm- 日志打印，添加 agentName
* 优化 solon-ai-core EmbeddingModel、RerankingModel、GenerateModel llm- 日志打印，添加 modelName
* 优化 solon-ai-dialect apiUrl 自动补全的#号处理
* 优化 solon-ai-dialect OpenaiChatDialect 添加自动补地址处理
* 优化 solon-ai-harness CodeSkill 拆解语言支持
* 优化 solon-ai-harness taskDo 多层子代理嵌套时的容错处理
* 优化 solon-ai-skill-cli ExpertSkill 引导词
* 优化 solon-ai-skill-cli read 引导词（优先一次读完）
* 优化 solon-ai-skill-cli 关于 py 和 nodejs 环境识别
* 优化 solon-ai-skill-memory 添加 markdown 轻量实现方案（默认不启用会话隔离）
* 调整 solon-ai-core ChatOptions.name 更名为 agentName
* 调整 solon-ai-harness TaskSkill 只输出 ThoughtChunk（不输出 ReasonChunk）
* 调整 solon-ai-agent 移除 ReActOptions.maxStepsLimit 属性 (没什么必要)
* acp-sdk 升为 0.11.0
* roguemap 升为 1.1.5

### 3.10.4

* 添加 solon-ai-agent 动态切换 llm 支持（支持不同会话，在各自请求时切换）
* 添加 solon-ai-harness 重试次数配置支持
* 添加 solon-ai-harness lsp 功能
* 添加 solon-ai-harness HarnessProperties.disallowedTools 配置支持（全局禁用工具配置）
* 添加 solon-ai-harness HarnessProperties.agentPools（子代理池） 配置支持
* 添加 solon-ai-harness HarnessProperties.systemPrompt 配置支持（替代之前的 AGENT.md 预设处理，由外部定制）
* 优化 solon-ai-core embedding 配置默认选项的能力（之前只能在请求时添加）
* 优化 solon-ai-core ChatRequestDesc.internalStream 添加流接收超时处理（避免因 llm 服务原因一直卡着）
* 优化 solon-ai-dialect-anthropic anthropic 方言的异常兼容处理
* 优化 solon-ai-dialect-openai openai-response 方言的异常兼容处理
* 优化 solon-ai-agent 中断传递处理
* 优化 solon-ai-agent ActionTask onAction 日志
* 优化 solon-ai-agent retrying 中断处理（这前的 task 内部的 stream 可能会中断传递）
* 优化 solon-ai-agent AgentSession.updateSnapshot 添加锁机制
* 优化 solon-ai-skill-lsp 重构 LspSkill 实现（之前名为 LspTool）
* 优化 solon-ai-skill-cli ExpertSkill 引导词
* 优化 solon-ai-skill-cli TerminalSkill 安全引导词
* 优化 solon-ai-skill-sys 从 cli 同步 ProcessExecutor
* 优化 solon-ai-harness HarnessEngine 扩展机制（支持为子代理扩展，通过名字识别）
* 调整 solon-ai-harness ReActAgentExtension 更名为 ReActAgentExtension
* 调整 solon-ai-harness 切换 llm 的机制，并取消 switchMainModel 方法（有自由的机制了）
* 调整 solon-ai-harness TerminalSkillProxy 转到 solon-ai-skill-cli
* 修复 solon-ai-skill-toolgateway ToolGatewaySkill call_tool 不能传递 toolContext 的问题
* 修复 solon-ai-core AbstractChatDialect reasoning_field_name 可能传递中断的问题

### 3.10.2

* 新增 solon-ai-repo-dashvector-official 插件
* 添加 solon-ai-core ChatModel.getModel 方法
* 添加 solon-ai-core ChatModel.getProvider 方法
* 添加 solon-ai-core ChatOptions.name 方便 agent 传递 name（打印日志）
* 添加 solon-ai-core 基于语义的分割器 SemanticSplitter
* 添加 solon-ai-core AiConfig.name, description 配置属性（用于管理显示）
* 添加 solon-ai-agent AgentRequest.callAsync 异步调用方法
* 添加 solon-ai-agent AgentResponse.getTrace 方法
* 添加 solon-ai-agent SummarizationInterceptor.copyWith 方法（方便复制实例并调整限制）
* 添加 solon-ai-harness HarnessProperties.models 配置属性（替代 chatModel）
* 添加 solon-ai-harness 子代理切换模型的能力
* 添加 solon-ai-harness HarnessProperties.removeModel 方法
* 优化 solon-ai-core RepositoryTool 增加重排支持
* 优化 solon-ai-core OllamaEmbeddingDialect.matched 添加 endsWith 匹配
* 优化 solon-ai-core MethodToolProvider 允许是否使用自身的 ToolProvider 内容（同时优化 AbsSkill，AbsToolProvider）
* 优化 solon-ai-agent ReActAgent 流块输出
* 优化 solon-ai-cli TodoSkill 提示词
* 调整 solon-ai-cli 取消 TerminalSkill.undo 工具（几乎用不到，由 git 控制）
* 调整 solon-ai-harness 取消 SESSION_DEFAULT（由应用侧处理）
* 调整 solon-ai-harness 去除 getVersion 方法（由应用侧定义）
* 调整 solon-ai-harness CLAUDE.md 更名为 CODE.md（内部自动生成）
* 修复 solon-ai-core MethodExecuteHandler 当 llm.args 嵌套非规范 json 时出错的问题（比如，非规范控制符）
* 修复 solon-ai-core ChatResponseDefault.isEmpty 可能识别失败的问题（不同 llm 情况较多）
* 调整 solon-ai-harness HarnessProperties.restApis 更名为 apiServers；取消 chatModel（由 models 替代）
* 修复 solon-ai-agent FileAgentSession.getSessionId 可能为空的问题
* 修复 solon-ai-skill-web CodeSearchTool 失效的问题（mcp.exa.ai 变了地址）
* 修复 solon-ai-harness TaskOp jsonSchema 问题（可能造成 multitask 调度失败）

### 3.10.1

* 新增 acp-sdk 模块（从 solon-ai-acp 分离出来，方便独立升级）
* 新增 solon-ai-harness 智能体马具框架
* 添加 solon-ai-core ChatModel.of().systemPrompt 方法
* 添加 solon-ai-core 工具执行时的参数注解支持
* 添加 solon-ai-core RepositoryTool 工具，为实现 Agent RAG 提供支持
* 添加 solon-ai-core AbsToolProvider 类（取代之前添加 toolObj 的方式）
* 优化 solon-ai-skill-cli ExpertSkill 原来的二档展示升为三级
* 优化 solon-ai-skill-restapi 原来的三档展示升为四级，并添加分组（可增强注意力）
* 优化 solon-ai-skill-toolgateway 原来的三档展示升为四级，并添加分组（可增强注意力）
* 优化 solon-ai-agent ReActRequest, TeamRequest 支持叠加 options
* 修复 solon-ai-skill-cli ExpertSkill grep 可能失效的问题

### 3.10.0

* 添加 solon-ai-agent ThoughtChunk 流块
* 添加 solon-ai-agent Metrics.addMetrics 锁安全
* 添加 solon-ai-agent AgentChunk.getMeta 流块元数据（方便在传递过程中，做标识）
* 优化 solon-ai-agent ActionTask 执行完后就更新快照（避免可能的长时间没有更新快照）
* 修复 solon-ai-core ChatModel.outputSchema 配置无效的问题
* 修复 solon-ai-core 工具调用时‘可能’出现消息内容重复的问题（重要，v3.9.2 时出现）

### 3.9.7

* 添加 solon-ai-agent ThoughtChunk 流块
* 添加 solon-ai-agent Metrics.addMetrics 锁安全
* 添加 solon-ai-agent AgentChunk.getMeta 流块元数据（方便在传递过程中，做标识）
* 优化 solon-ai-agent ActionTask 执行完后就更新快照（避免可能的长时间没有更新快照）
* 修复 solon-ai-core ChatModel.outputSchema 配置无效的问题
* 修复 solon-ai-core 工具调用时‘可能’出现消息内容重复的问题（重要，v3.9.2 时出现）

### 3.9.6

* 添加 solon-ai-core ChatModel.outputSchema 配置支持（且与 Agent 统一基于方言实现，各方言可定制）
* 添加 solon-ai-core ChatModel.contextLength 配置支持（为 Agent 方便自动计算限制数）
* 添加 solon-ai-core AssistantMessage.getJsonContent 方法
* 添加 solon-ai-core MarkdownUtil 工具类
* 添加 solon-ai-dialect-gemini tool-call 的思考签名支持
* 添加 solon-ai-agent ReActChunk.getMetrics 方法
* 优化 solon-ai-core AbstractChatDialect 解析 tool 参数的兼容性
* 优化 solon-ai-dialect-dashscope 方言 支持流式传输与百炼联网搜索
* 优化 solon-ai-dialect-anthropic tool.args 不规范时的兼容性（比如 json 不完整）
* 优化 solon-ai-dialect-gemini 特定 provider 结束时返回 content 为空的消息才设置finishReason 导致结束标志被提前过滤
* 优化 solon-ai-agent ReActAgent onThought 事件，优先使用 getReasoning 获取 <think> 思考内容
* 优化 solon-ai-agent ReActAgent SummarizationInterceptor 添加 maxContextLength 控制参数（很重要）
* 优化 solon-ai-agent ActionChunk 分为 ActionEndChunk 和 ActionStartChunk（并保留 ActionChunk 标为弃用）
* 优化 solon-ai-agent 流式处理 `catch (Exception`，换成 `catch (Throwable`
* 优化 solon-ai-skill-restapi 每个 api 可以添加独立的验证机制
* 优化 solon-ai-skill-cli 避免 bash 把当前进程给删掉了
* 调整 solon-ai-agent 临时挂起操作，由 XxxTrace 转到 AgentSession（更统一）
* 调整 solon-ai-core Skill 的激活逻辑，如果 getInstruction 为空，则不进入 SystemPrompt（之前还要 description 为空）
* 修复 solon-ai-agent ReActAgent 思考事件触发逻辑，确保在所有情况下都被调用

### 3.9.5

* 新增 mcp-sdk 插件（从 solon-ai-mcp 独立出来）
* 新增 solon-ai-skill-toolgateway 插件
* 新增 solon-ai-search-tavily 插件
* 新增 solon-ai-ui-aisdk 插件
* 添加 solon-ai-core __sessionId 自动作为工具上下文和提示词属性（用于传递会话id）
* 添加 solon-ai-core SkillProvider 接口
* 添加 solon-ai-core AbsTool 类
* 添加 solon-ai-core AiUsage thinkTokens 字段
* 添加 solon-ai-core ChatConfig.getReasoningFieldName 自动处理，兼容 deepseek 新的变化
* 添加 solon-ai-core ChatModel options 配置自动转强类型（llm 不能接收str）
* 添加 solon-ai-agent ReActInterceptor.onObservation durationMs 参数（时长毫秒数）
* 添加 solon-ai-agent ReActChunk isNormal(用于识别是否为正常结束)
* 添加 solon-ai-skill-memory MemSkill 支持会话隔离与共享
* 优化 solon-ai-mcp 独立出 mcp-sdk 包
* 优化 solon-ai-dialect-claude 方言适配（及 api.kimi.com/coding 兼容性）
* 优化 solon-ai-dialect-openai 与 api.minimax.io 的兼容性
* 优化 solon-ai-agent SummarizationInterceptor
* 优化 solon-ai-agent ReActSystemPrompt 描述
* 优化 solon-ai-skill-memory MemSkill 取消 userId 参数，自动从上下文中取
* 优化 solon-ai-skill-cli
* 优化 Solon AI Skill 指令层级控制
* 调整 solon-ai-dialect-claude 更名为 solon-ai-dialect-anthropic（更合适）

### 3.9.4

* 新增 solon-ai-skill-lucene 插件
* 新增 solon-ai-skill-diff 插件
* 新增 solon-ai-skill-memory 插件
* 添加 solon-ai-core toolContext 自动转为 Prompt.attrs 的机制（方便 skill 传递）
* 添加 solon-ai-agent ReActChunk, TeamChunk getTrace 方法
* 添加 solon-ai-agent __sessionId 作为智能体的必要工具上下文（方便工具的会话数据隔离）
* 优化 solon-ai-core chatModel 方言的（不规范的）兼容性处理
* 优化 solon-ai-core buildToolCallBuilder 兼容（有些 llm stream ，会部分全量多次提供）
* 优化 solon-ai-agent SummarizationInterceptor 增加策略机制并内置4个策略
* 修复 solon-ai-core ChatModel.stream 过程异常时会破坏流响应的问题
* 修复 solon-ai-agent ReasonTask.callWithRetry 网络异常时会中断工作流的问题
* 修复 solon-ai-agent ReActAgent 流式请求时，可能无法记忆结果内容的问题

### 3.9.3

* 重构 solon-ai-agent Plan-ReAct 模式（相对之前，新设计智能、态动、按需）
* 优化 solon-ai-skill-cli CliSkill

### 3.9.2


* 新增 solon-ai-acp 插件（可以对接支持 acp 协议的 IDE）
* 添加 solon-ai-core ChatSessionProvider
* 添加 solon-ai-core FunctionTool:call 方法
* 添加 solon-ai-mcp FunctionPrompt:get 方法
* 添加 solon-ai-mcp FunctionResource:read 方法
* 添加 solon-ai-core ToolSchemaUtil.resultConvert 方法（将 tool 转换从内部，转到外部）
* 添加 solon-ai-agent ReActAgent maxStepsExtensible 配置，允许通过 HITL 扩容步数
* 优化 solon-ai-core ChatModel 与 DeepSeek-R1 兼容性
* 优化 solon-ai-agent ReActAgent 与 DeepSeek-R1 兼容性（手造的 AssistantMessage 需要自动补字段）
* 优化 solon-ai-agent FunctionTool 增加 tool 多模态与单模态兼容处理
* 优化 solon-ai-skill-cli CliSkill 进一步与 Claude Code 规范对齐（接近 100%）
* 优化 solon-ai-skill-cli CodeCLI exit 改为进程退出
* 优化 solon-ai-mcp 无心跳时，支持自动复位尝试
* 调整 solon-ai-mcp McpClientProvider 接口，优化多模态适配
* 调整 solon-ai-core 多模态体系（AiMedia 更名为 ContentBlock，）
* 调整 solon-ai-core UserMessage medias 更名为 blocks，hasMedias 更名为 isMultiModal
* 调整 solon-ai-core ToolMessage 添加 blocks，isMultiModal（工具支持多模态）
* 调整 solon-ai-core ChatDialect 接口，对多模态和 R1 更友好
* 移除 solon-ai-core 移除 ImageModel 体系，由 GenerateModel 体系接替（v3.5 时增加)
* 修复 solon-ai-agent ReActAgent 重试时会消息倍增的问题 
* 修复 solon-ai-agent ReActAgent，TeamAgent 在恢复执行时，会重置 Options 的问题


变更说明：

| 旧名（强调多媒体）                                  | 新名（强调多模态内容块）                                          |
|--------------------------------------------|-------------------------------------------------------|
| AiMedia                                    | ContentBlock                                          |
| Text                                       | TextBlock                                             |
| Image                                      | ImageBlock                                            |
| Audio                                      | AudioBlock                                            |
| Video                                      | VideoBlock                                            |
|                                            |                                                       |
| UserMessage.getMedias()                    | getBlocks()                                           |
| UserMessage.hasMedias()                    | isMultiModal()                                        |
| /                                          | ToolMessage.getBlocks()                               |
| /                                          | ToolMessage.isMultiModal()                            |
|                                            |                                                       |
| McpClientProvider.callTool()               | callToolRequest()                                     |
| McpClientProvider.callToolAsText()         | callTool()                                            |
| McpClientProvider.callToolAsImage()        | /                                                     |
| McpClientProvider.callToolAsAudio()        | /                                                     |
| McpClientProvider.readResource()           | readResourceRequest()                                 |
| McpClientProvider.readResourceAsText()     | readResource()                                        |
| McpClientProvider.readResourceAsBlob()     | /                                                     |
| McpClientProvider.getPrompt()              | getPromptRequest()                                    |
| McpClientProvider.getPromptAsMessage()     | getPrompt()                                           |
| `org.noear.solon.ai.mcp.server.prompt.*`   | `org.noear.solon.ai.chat.prompt.*` （prompt 是公用元素）     | 
| `org.noear.solon.ai.mcp.server.resource.*` | `org.noear.solon.ai.chat.resource.*` （resource 是公用元素） | 



### v3.9.1


* 新增 solon-ai-dialect-claude 插件
* 新增 solon-ai-dialect-openai OpenaiResponses 方言适配
* 新增 solon-ai-repo-weaviate 插件
* 新增 solon-ai-skill-cli 插件（含 CliSkill 和 SolonCodeCLI）
* 新增 solon-ai-skill-data 插件（含 RedisSkill）
* 新增 solon-ai-skill-file 插件（含 FileReadWriteSkill，ZipSkill）
* 新增 solon-ai-skill-generation 插件（含 ImageGenerationSkill， VideoGenerationSkill）
* 新增 solon-ai-skill-mail 插件（含 MailSkill）
* 新增 solon-ai-skill-pdf 插件（含 PdfSkill）
* 新增 solon-ai-skill-restapi 插件（含 RestApiSkill），内置 OpenApi3 和 swagger2 兼容适配（可扩展定制）
* 新增 solon-ai-skill-social 插件（含 DingTalkSkill，FeishuSkill，WeComSkill）
* 新增 solon-ai-skill-sys 插件（含 NodejsSkill，PythonSkill， ShellSkill，SystemClockSkill）
* 新增 solon-ai-skill-text2sql 插件（含 Text2SqlSkill）
* 新增 solon-ai-skill-web 插件（含 WebCrawlerSkill，WebSearchSkill）
* 添加 solon-ai-core ChatModel role 和 instruction 支持（提供类似 SimpleAgent 效果）
* 添加 solon-ai-core ChatModel.stream 背压流控支持
* 添加 solon-ai-core FileChatSession，RedisChatSession 聊天会话实现
* 添加 solon-ai-core ChatInterceptor.onPrepare 事件（勾子）
* 添加 solon-ai-core Prompt.removeLastMessage, removeLastAssistantMessage 方法
* 添加 solon-ai-agent FileAgentSession 智能体会话实现
* 添加 solon-ai-agent ReActTrace, TeamTrace pending 机制（方便按需拦截挂机）
* 添加 solon-ai-agent feedbackMode 模式（默认为 false）
* 添加 solon-ai-agent ReActInterceptor.onObservation toolName 参数
* 添加 solon-ai-agent HITL 专属接口，进一步优化人工机制（同意，拒绝，跳过）
* 添加 solon-ai-agent stream 流式输出机制
* 优化 solon-ai-agent ReActAgent 添加主动挂机的能力（处理无法完成的任务时，可避免幻觉轮询）
* 优化 solon-ai-agent description + systemPrompt 标为弃用，改由 role + instruction 替代（简化了）
* 优化 solon-ai-agent 内置的 Team 协议（如 ReActProtocol，SwarmProtocol 等）
* 优化 solon-ai-agent 内置的 ToolRetryInterceptor，ToolSanitizerInterceptor 等
* 优化 solon-ai-agent AgentSession 接口 updateSnapshot 取消入参
* 优化 solon-ai-agent ActionTask 取消正则匹配 json 方式，改用 json 流式读取方式
* 调整 solon-ai-core InMemoryChatSession systemMessages 标为弃用（不建议放到会话里，不方便做结构化的管理）
* 调整 solon-ai-agent 取消 title（减少迷惑），systemMessage 标为弃用。改用 role 和 instruction
* 调整 solon-ai-core ChatModel.stream 返回由 Publisher 改为 Flux
* 调整 solon-ai-agent enablePlanning 更名为 planningMode （前者标为弃用）
* 修复 solon-ai-agent ReActAgent 新话题开始时， ReActTrace 没有重置干净的问题
* 修复 solon-ai-agent SimpleAgent 没有接入协作协议 prepareAgentPrompt 勾子
* qdrant 升为 1.16.2

### v3.9.0

* 添加 solon-ai-core Prompt 更多方法（getLastAssistantMessage, size 等）
* 添加 solon-ai-core ChatSession 更多方法（getLatestMessages 等）
* 添加 solon-ai-agent Agent.call(session) 方法（作为恢复执行的入口）
* 添加 solon-ai-agent Agent.prompt 方法
* 添加 solon-ai-agent AgentResponse 接口，统一响应界面
* 添加 solon-ai-agent Trace.workingMemory 概念
* 优化 solon-ai-core Skill 接口设计，getTools 增加 prompt 参数
* 优化 solon-ai-agent AgentSession 接口，改为扩展自 ChatSession （只需入口 agent 有历史消息）
* 优化 solon-ai-agent 部分接口设计

### v3.8.4

* 新增 `solon-ai-core` Solon AI Skills（技能）体系
* 新增 `solon-ai-search-bocha` 插件
* 添加 `solon-ai-core` defaultToolsContextPut 方法
* 添加 `solon-ai-core` Prompt attrPut, attr 属性相关方法（可以在拦截时控制权限）
* 添加 `solon-ai-core` FunctionTool meta 元数据相关方法（可对描述语进行染色）
* 添加 `solon-ai-mcp` FunctionPrompt meta 元数据相关方法（可对描述语进行染色）
* 添加 `solon-ai-mcp` FunctionResource meta 元数据相关方法（可对描述语进行染色）
* 添加 `solon-ai-agent` NoneProtocol（无协议模式）
* 添加 `solon-ai-agent` ReActAgent Plan 支持（默认为关闭）。
* 添加 `solon-ai-agent`  SimpleInterceptor 替代 ChatInterceptor，方便后续扩展
* 优化 `solon-ai-core` Gemini 方言适配
* 优化 `solon-ai-core` Prompt 接口，方法更丰富
* 优化 `solon-ai-agent` A2AProtocol 协议代码
* 优化 `solon-ai-agent` SwarmProtocol 协议代码
* 优化 `solon-ai-agent` HierarchicalProtocol 协议代码
* 优化 `solon-ai-agent` SequentialProtocol 协议代码，添加专属任务（可节省 token）
* 修复 `solon-ai-core` ToolSchemaUtil `Param` 注解别名没有生效的问题
* 修复 `solon-ai-agent` ReActAgent 没有拦截器时 ReActAgent 不能传递 toolsContext 的问题
* 调整 `solon-ai-agent` Agent 相关接口保持与 ChatModel 一致性

### v3.8.3

* 修复 `solon-ai-agent` ActionTask 与 mcp 的兼容性（取消 trace 作为工具上下文注入，它不能被mcp 序列化）

### v3.8.2

* 修复 `solon-ai-agent` SimpleAgent 没有反回干净内容的问题（带了 think）
* 添加 `solon-ai-agent` SimpleRequest
* 添加 `solon-ai-agent` SimpleAgent.prompt 方法（为三个智能体提供相同体验）


### v3.8.1

* 新增 `solon-ai-agent` 插件
* 新增 `solon-ai-dialect-gemini` 插件
* 添加 `solon-ai-core` autoToolCall 聊天模型选项（默认为 true）
* 添加 `solon-ai-core` ChatResponse:getResultContent
* 添加 `solon-ai-core` AssistantMessage.toBean 方法。
* 优化 `solon-ai-core` AssistantMessage.getResultContent 处理
* 调整 `solon-ai-croe` ChatSession 不再扩展 Prompt（打断两者关系，后者定位偏固定数据


新增三种模式的智能体：

| 智能体          | 模式描述                                      |
|--------------|-------------------------------------------|
| SimpleAgent  | 简单模式                                      | 
| ReActAgent   | 自省模式，思考+行动。                               | 
| TeamAgent    | 协作模式，分工+编排。多智能体系统（multi-agent system，MAS） | 


新特性示例：ReActAgent

```java
public class DemoApp {
  public static void main(String[] args) throws Throwable {
    ChatModel chatModel = LlmUtil.getChatModel();

    SimpleAgent robot = SimpleAgent.of(chatModel)
            .toolAdd(new MethodToolProvider(new TimeTool()))
            .build();

    String answer = robot.prompt("现在几点了？").call().getContent();

    System.out.println("Robot 答复: " + answer);
  }

  public static class TimeTool {
    @ToolMapping(description = "获取当前系统时间")
    public String getTime() {
      return LocalDateTime.now().toString();
    }
  }
}
```

### v3.8.0

重要变化：

* mcp-java-sdk 升为 v0.17 （支持 2025-06-18 版本协议）
* 添加 mcp-server McpChannel.STREAMABLE_STATELESS 通道支持（集群友好）
* 添加 mcp-server 异步支持

具体更新：

* 添加 solon-ai FunctionPrompt:handleAsync（用于 mcp-server 异步支持）
* 添加 solon-ai FunctionResource:handleAsync（用于 mcp-server 异步支持）
* 添加 solon-ai FunctionTool:handleAsync（用于 mcp-server 异步支持）
* 添加 solon-ai-core ChatMessage:toNdjson,fromNdjson 方法（替代 ChatSession:toNdjson, loadNdjson），新方法机制上更自由
* 添加 solon-ai-core ToolSchemaUtil.jsonSchema Publisher 泛型支持
* 添加 solon-ai-core ToolSchemaUtil.isIgnoreOutputSchema Publisher 泛型支持
* 添加 solon-ai-mcp mcp-java-sdk v0.17 适配（支持 2025-06-18 版本协议）
* 添加 solon-ai-mcp mcp-server 异步支持
* 添加 solon-ai-mcp mcp-server streamable_stateless 支持
* 添加 solon-ai-mcp Tool,Resource,Prompt 对 org.reactivestreams.Publisher<X> 异步返回支持
* 添加 solon-ai-mcp McpServerHost 服务宿主接口，用于隔离有状态与无状态服务
* 添加 solon-ai-mcp McpChannel.STREAMABLE_STATELESS （服务端）无状态会话
* 添加 solon-ai-mcp McpClientProvider:customize 方法（用于扩展 roots, sampling 等）
* 添加 solon-ai-mcp mcpServer McpAsyncServerExchange 注入支持（用于扩展 roots, sampling 等）
* 优化 solon-ai-dialect-openai claude 兼容性
* 优化 solon-ai-mcp mcp StreamableHttp 模式下 服务端正常返回时 客户端异常日志打印的情况
* 调整 solon-ai-mcp getResourceTemplates、getResources 不再共享注册
* 调整 solon-ai-mcp McpServerManager 内部接口更名为 McpPrimitivesRegistry （MCP 原语注册器）
* 调整 solon-ai-mcp McpClientProvider 默认不启用心跳机制（随着 mcp-sdk 的成熟，server 都有心跳机制了）
* mcp ToolCapabilities 添加 @JsonIgnoreProperties(ignoreUnknown = true)


新特性展示：1.MCP 无状态会话（STREAMABLE_STATELESS）和 2.CompletableFuture 异步MCP工具

```java
@McpServerEndpoint(channel = McpChannel.STREAMABLE_STATELESS, mcpEndpoint = "/mcp1")
public class McpServerTool {
    @ToolMapping(description = "查询天气预报", returnDirect = true)
    public CompletableFuture<String> getWeather(@Param(description = "城市位置") String location) {
        return CompletableFuture.completedFuture("晴，14度");
    }
}
```

传输方式对应表：（服务端与客户端，须使用对应的传输方式才可通讯）

| 服务端                   | 客户端         |  备注               |
|-----------------------|-------------|-----------------|
| STDIO                 | STDIO       |                 |
| SSE                   | SSE         |                 |
| STREAMABLE            | STREAMABLE  |                 |
| STREAMABLE_STATELESS  | STREAMABLE  | 对 server 集群很友好  |


* STREAMABLE_STATELESS 集群，不需要 ip_hash，但“原语”变化后无法通知 client


### v3.7.4

* 添加 solon-ai-core ChatMessage:toNdjson,fromNdjson 方法（替代 ChatSession:toNdjson, loadNdjson），新方法机制上更自由
* 优化 solon-ai-mcp mcp StreamableHttp 模式下 服务端正常返回时 客户端异常日志打印的情况

### v3.7.3

* 优化 solon-ai-core ToolSchemaUtil 扩展能力，支持注解处理定制（方便第三方集成）
* 优化 solon-ai-mcp MCP 客户端与服务端异常链接状态 立即返回

### v3.7.2

* 引入 snack4-jsonschema 优化 ToolSchemaUtil
* 添加 solon-ai-core AiMedia:metas 方法
* 添加 solon-ai-core GenerateModel defaultOptions 配置支持
* 添加 solon-ai-core ToolSchemaUtil:buildInputParams 方法，支持 @Body 注解
* 添加 solon-ai-flow GenerateModelCom 组件
* 修复 solon-ai bean 有其它框架代理后，不能识别注解的问题
* 修复 solon-ai ToolSchemaUtil 部分泛型识别失败的问题
* 修复 solon-ai-flow ChatModelCom 加载 chatConfig 配置会出错的问题
* 修复 solon-ai-mcp/mcp-sdk listTools 的 nextCursor 为空时，不能正常获取问题


### v3.7.0

* 优化 solon-ai-core ToolSchemaUtil:paramOf 方法，增加泛型支持
* 优化 solon-ai-core ToolSchemaUtil:outputSchema 泛型处理
* 修复 solon-ai parseToolCall 接收 stream 中间消息时可能会异常（添加 hasNestedJsonBlock 检测）
* 修复 solon-ai-mcp 可能出现 Unknown media type 错误（取消 request.contentType 空设置）
* jedis 升为 7.0.0 （通过 redisx）



### 3.6.5


* 修复 solon-ai-mcp/mcp-sdk listTools 的 nextCursor 为空时，不能正常获取问题

### 3.6.4

* 修复 solon-ai parseToolCall 接收 stream 中间消息时可能会异常（添加 hasNestedJsonBlock 检测）

### 3.6.3

* 修复 solon-ai-mcp 可能出现 Unknown media type 错误（取消 request.contentType 空设置）
* 优化 solon-ai-mcp 取消 request.contentType("") 设置


### 3.6.1

* 修复 solon-ai-core chatModel:stream 长流输出可能出错的问题


### 3.6.0

* 优化 solon-ai-core XxxMessageTemplate 使用 SnEL 替代 TmplUtil


### 3.5.9

* 修复 solon-ai-mcp/mcp-sdk listTools 的 nextCursor 为空时，不能正常获取问题

### 3.5.8

* 修复 solon-ai parseToolCall 接收 stream 中间消息时可能会异常（添加 hasNestedJsonBlock 检测）

### 3.5.7

* 修复 solon-ai-mcp 可能出现 Unknown media type 错误（取消 request.contentType 空设置）
* 优化 solon-ai-mcp 取消 request.contentType("") 设置

### 3.5.6

* 修复 solon-ai-core chatModel:stream 长流输出可能出错的问题
* mcp 移除 WebRxSseClientTransport:sendMessage 的 accept 声明（是在 3.5.5 时加的，此处没必要）

### 3.5.5

* 添加 McpClientProvider:httpFactory 默认为 jdkhttp（okhttp 有些平台不兼容），不再随 HttpUtils 的全局走
* 添加 solon-ai-mcp McpClientProvider:httpFactory 默认为 jdkhttp（okhttp 有些平台不兼容），不再随 HttpUtils 的全局走

### 3.5.3

* 优化 solon-ai-core chatModel.stream 与背压处理的兼容性
* 调整 solon-ai-map getPrompt,readResource,callTool 取消自动异常转换（侧重原始返回）
* 调整 solon-ai-map callTool 错误结果传递，自动添加 'Error:' （方便 llm 识别）
* 修复 solon-ai-mcp callTool isError=true 时，不能正常与 llm 交互的问题
* 修复 solon-ai-mcp ToolAnnotations:returnDirect 为 null 时的传递兼容性

### 3.5.2

* 添加 solon-ai-core ToolSchemaUtil 简化方法
* 添加 solon-ai-mcp McpClientProperties:timeout 属性，方便简化超时配置（可省略 httpTimeout, requestTimeout, initializationTimeout）
* 添加 solon-ai-mcp McpClientProvider:toolsChangeConsumer,resourcesChangeConsumer,resourcesUpdateConsumer,promptsChangeConsumer 配置支持
* 添加 solon-ai-mcp McpClientProvider 缓存锁和变更刷新控制
* 添加 solon-ai-mcp IMcpServerEndpoint 接口（方便可批量获取组件）
* 优化 solon-ai-core RepositoryStorable 接口定义，用 save 替代 insert(标为弃用)
* 调整 solon-ai-core FunctionToolDesc:doHandle 改用 ToolHandler 参数类型（之前为 Function），方便传递异常

### 3.5.1

* 新增 solon-ai-a2a 插件
* 新增 solon-ai-core GenerateModel 接口，替代 ImageModel
* 新增 solon-ai-core ChatModel 增加多媒体内容输出（增强感知型模型的兼容，比如输出图片或视频）
* 新增 solon-ai-core ImageModel 增加结构体提示语输入（比如图片编辑模型）
* 添加 solon-ai-core AbstractChatDialect 对多媒体内容输出的支持
* 添加 solon-ai-core AssistantMessage:contentRaw 原生内容（可能是 String、Map、List、null）
* 添加 solon-ai-dialect-dashscope 通过接口地址识别方言
* 添加 solon-ai-mcp McpServerEndpointProvider:Builder 添加 context-path 配置
* 优化 solon-ai-mcp McpClientProvider 配置向 McpServers json 格式上靠
* 修复 solon-ai-core `think-> tool -> think` 时，工具调用的内容无法加入到对话的问题
* 修复 solon-ai-mcp 服务端传输层的会话长连会超时的问题
* 修复 solon-ai-mcp 客户端提供者心跳失效的问题
* 修复 solon-ai-mcp SSE 传输时 message 端点未附加 context-path 的问题
* mcp `McpSchema:*Capabilities` 添加 `@JsonIgnoreProperties(ignoreUnknown = true)` 增强跨协议版本兼容性

### 3.5.0

* 新增 solon-ai-mcp mcp-java-sdk v0.11.0 适配（支持 2025-03-26 版本协议）
* 调整 solon-ai-mcp channel 取消默认值（之前为 sse），且为必填（利于协议升级过度，有明确的开发时、启动时提醒）
  * 如果默认值仍为 sse ，升级后可能忘了修改了升级
  * 如果默认值改为 streamable，升级后会造成不兼容

### 3.4.5

* 优化 solon-ai-core chatModel.stream 与背压处理的兼容性
* 调整 solon-ai-map getPrompt,readResource,callTool 取消自动异常转换（侧重原始返回）
* 调整 solon-ai-map callTool 错误结果传递，自动添加 'Error:' （方便 llm 识别）
* 修复 solon-ai-mcp callTool isError=true 时，不能正常与 llm 交互的问题

### 3.4.4

* 添加 solon-ai-dialect-dashscope 通过接口地址识别方言
* 添加 solon-ai-mcp McpClientProperties:toolsChangeConsumer,resourcesChangeConsumer,resourcesUpdateConsumer,promptsChangeConsumer 配置支持
* 添加 solon-ai-mcp McpClientProvider 缓存锁和变更刷新控制
* 修复 solon-ai-core think-> tool -> think 时，工具调用的内容无法加入到对话的问题
* mcp XxxCapabilities 添加 `ignoreUnknown=true` 增强旧协议兼容


### 3.4.3

* 新增 solon-ai-repo-mysql 插件
* 添加 solon-ai-core InMemoryChatSession（语义清晰） 替代 ChatSessionDefault（标为弃用）
* 优化 solon-ai-core ChatRequestDescDefault http 异常转换描述
* 优化 solon-ai-core 方言的 tool_calls 消息的构建（更好的兼容 vllm）
* 优化 solon-ai-mcp JsonSchema.additionalProperties 兼容性（兼容 bool, map）
* 优化 solon-ai-mcp McpClientProvider 改为 McpAsyncClient（为异步需求提供支持）
* 优化 solon-ai-mcp 初始化控制（禁用 connectOnInit），增加连接打印


### 3.4.1

* 新增 solon-ai-repo-pgvector 插件
* 新增 solon-ai-search-baidu 插件
* 添加 solon-ai-core `TextLoader(byte[])(SupplierEx<InputStream>)` 构造方法
* 添加 solon-ai-core `ChatConfig:defaultToolsContext`（默认工具上下文）, `defaultOptions`（默认选项） 属性
* 添加 solon-ai-core `RepositoryStorable:insert(list,progressCallback)` 和 `asyncInsert(list,progressCallback)` 方法，支持进度获取
* 添加 solon-ai-mcp 客户端 ssl 定制支持
* 优化 solon-ai 方言 think 思考内容和字段的兼容性处理
* 优化 solon-ai 方言处理与 modelscope（魔搭社区）的兼容性
* 优化 solon-ai 方言处理与 siliconflow（硅基流动）的兼容性
* 优化 solon-ai 方言处理的流式节点识别兼容性
* 优化 solon-ai 用户消息的请求构建（当内容为空时，不添加 text）
* 优化 solon-ai-mcp McpClientProvider 心跳间隔控制（5s 以下忽略）
* 优化 solon-ai-mcp McpServerContext 增加 stdio 代理支持（环境变量自动转为 ctx:header）
* mcp WebRxSseClientTransport 添加 debug 日志打印


### 3.4.0

* 新增 solon-ai-repo-opensearch 插件
* 添加 solon-ai Options:toolsContext 方法
* 调整 solon-ai-core ToolCallResultJsonConverter 更名为 ToolCallResultConverterDefault 并添加序列化插件支持
* 调整 solon-ai-mcp PromptMapping，ResourceMapping 取消 resultConverter 属性（没必要）
* 修复 solon-ai-core ChatModel:stream:doOnNext 可能无法获取 isFinished=true 情况

### 3.3.3

* 优化 solon-ai-core ToolSchemaUtil 对 Map 的处理（有些框架，太细不支持）
* 优化 solon-ai-core ToolSchemaUtil 对 Collection 的处理（有些框架，太细不支持）
* 优化 solon-ai-mcp MethodPromptProvider,MethodResourceProvider 改用 clz 构建(兼容外部代理情况)
* 优化 solon-ai-core MethodToolProvider 改用 clz 构建(兼容外部代理情况)
* 添加 solon-ai-core RepositoryStorable:insert(Doc...) 方法
* 添加 solon-ai-mcp McpServerEndpoint:enableOutputSchema 支持（默认为 false）
* 调整 solon-ai-core ToolCallResultConverter 接口定义（增加返回类型参数）
* 调整 solon-ai-core 移除 QueryCondition:doFilter 方法（避免误解）
* 调整 solon-ai-mcp tool,resource 结果默认处理改为 ToolCallResultJsonConverter
* 调整 solon-ai-repo-elasticsearch 搜索类型，默认改为相似搜索（之前为精准，需要脚本权限）
* mcp 优化 WebRxSseClientTransport 连接等待处理（异常时立即结束）
* elasticsearch-rest-high-level-client 升为 7.17.28
* milvus-sdk-java 升为 2.5.10
* vectordatabase-sdk-java 升为 2.4.5

### 3.3.2

* 优化 solon-ai-flow 插件
* 添加 solon-ai-core ChatInterceptor 聊天拦截机制
* 添加 solon-ai-core ChatMessage:ofUserAugment 替代 augment（后者标为弃用）
* 添加 solon-ai-core ProxyDesc 的 Serializable 接口实现
* 添加 solon-ai-core ChatOptions:response_format 方法
* 添加 solon-ai-core AssistantMessage:getSearchResultsRaw 方法
* 添加 solon-ai-mcp McpServerEndpointProvider:getMessageEndpoint 方法
* 添加 solon-ai-mcp McpServerParameters http 参数支持
* 添加 solon-ai-mcp McpClientProvider 本地缓存支持（默认 30秒）
* 添加 solon-ai-mcp 原语处理异常日志
* 优化 solon-ai-core ChatConfig.toString （增加 proxy）
* 优化 solon-ai-core Tool:outputSchema 改为必出
* 优化 solon-ai-core 添加 ToolCallException 异常类型，用于 tool call 异常传递（之前为 ChatException）
* 优化 solon-ai OpenaiChatDialect 方言，tool 消息也附带所有的 tools 元信息（之前被过滤了）
* 优化 solon-ai-mcp McpServerContext 同步连接时的请求参数，方便在 Tool 方法里获取
* 优化 solon-ai-mcp McpProviders 在 sse 时，支持 env 也作为 header 处理（有些服务方的配置，是用 env 的）
* 优化 solon-ai-mcp 取消 RefererFunctionTool（由 FunctionToolDesc 替代）
* 优化 solon-ai-mcp 基于 McpServerParameters 的构建能力
* 修复 solon-ai-flow ChatModel 没有加载 mcpServers 配置的问题
* mcp 适配优化 WebRxSseClientTransport 关闭时同时取消 sse 订阅（避免线程占用）


### 3.3.1

* 新增 solon-ai-flow 插件
* 新增 solon-ai-load-ddl 插件
* 添加 solon-ai-core ChatMessage:ofUser(media) 方法
* 添加 solon-ai-core ChatSession:addMessage(Prompt) 方法
* 添加 solon-ai-core ChatSession:addMessage(Collection) 方法
* 添加 solon-ai-core RerankingConfig,RerankingModel toString 方法
* 添加 solon-ai-core 模型的网络代理支持（支持简单配置，和复杂构建）
* 添加 solon-ai-mcp 客户端的网络代理简单配置支持
* 添加 solon-ai-mcp messageEndpoint 端点配置支持（应对特殊需求，一般自动更好）
* 添加 solon-ai-mcp ToolMapping,ResourceMapping 注解方法对 Produces 注解的支持（用它可指定结果转换处理）
* 添加 solon-ai-mcp ToolCallResultConverter:matched 方法
* 添加 solon-ai-mcp 资源模板的响应适配
* 添加 solon-ai-mcp McpClientProvider:getResourceTemplates 方法
* 添加 solon-ai-mcp 检查原语是否存在的方法（hasTool, hasPrompt, hasResource）
* 添加 solon-ai-mcp 提示语支持 UserMessage 拆解成多条 mcp 内容（如果，同时有媒体和文本的话）
* 优化 solon-ai-core tool 空参数时的不同大模型兼容性
* 优化 solon-ai-core ChatSession 的作用，为限数提供支持
* 优化 solon-ai-core MethodFunctionTool 移除对 Mapping 注解的支持（语意更清楚，之前 MethodToolProvider 已经移除，这个落了）
* 优化 solon-ai-core EmbeddingRequest，ImageRequest，RerankingRequest 当 resp.getError() 非 null 时，直接出抛异常
* 优化 solon-ai-mcp 取消 MethodFunctionResource 对反回类型的限制（增加了 resultConverter 转换处理）
* 优化 solon-ai-mcp McpServerEndpointProvider 支持零添加原语，postStart 后，可添加原语
* 修复 solon-ai ChatRequestDefault:stream 请求 r1 时，可能会产生两次 tink 消息发射

### 3.3.0

* 新增 solon-ai-repo-dashvector 插件
* 插件 solon-ai 三次预览
* 插件 solon-ai-mcp 二次预览
* 调整 solon-ai 移除 ToolParam 注解，改用 `Param` 注解（通用参数注解）
* 调整 solon-ai ToolMapping 注解移到 `org.noear.solon.ai.annotation`
* 调整 solon-ai FunctionToolDesc:param 改为 `paramAdd` 风格
* 调整 solon-ai MethodToolProvider 取消对 Mapping 注解的支持（利于跨生态体验的统一性）
* 调整 solon-ai 拆分为 solon-ai-core 和 solon-ai-model-dialects（方便适配与扩展）
* 调整 solon-ai 模型方言改为插件扩展方式
* 调整 solon-ai-mcp McpClientToolProvider 更名为 McpClientProvider（实现的接口变多了）
* 优化 solon-ai 允许 MethodFunctionTool,MethodFunctionPrompt,MethodFunctionResource 没有 solon 上下文的用况
* 优化 solon-ai-core `model.options(o->{})` 可多次调用
* 优化 solon-ai-mcp McpClientProvider 同时实现 ResourceProvider, PromptProvider 接口
* 优化 solon-ai-repo-redis metadataIndexFields 更名为 `metadataFields` （原名标为弃用）
* 添加 solon-ai-core ChatSubscriberProxy 用于控制外部订阅者，只触发一次 onSubscribe
* 添加 solon-ai-mcp McpClientProperties:httpProxy 配置
* 添加 solon-ai-mcp McpClientToolProvider isStarted 状态位（把心跳开始，转为第一次调用这后）
* 添加 solon-ai-mcp McpClientToolProvider:readResourceAsText,readResource,getPromptAsMessages,getPrompt 方法
* 添加 solon-ai-mcp McpServerEndpointProvider:getVersion,getChannel,getSseEndpoint,getTools,getServer 方法
* 添加 solon-ai-mcp McpServerEndpointProvider:addResource,addPrompt 方法
* 添加 solon-ai-mcp McpServerEndpointProvider:Builder:channel 方法
* 添加 solon-ai-mcp ResourceMapping 和 PromptMapping 注解（支持资源与提示语服务）
* 添加 solon-ai-mcp McpServerEndpoint AOP 支持（可支持 solono auth 注解鉴权）
* 添加 solon-ai-mcp McpServerEndpoint 实体参数支持（可支持 solon web 的实体参数、注解相通）
* 添加 solon-ai-mpc `Tool.returnDirect` 属性透传（前后端都有 solon-ai 时有效，目前还不是规范）

### 3.2.1

* 添加 solon-ai ChatRequestDefault http 状态异常处理
* 添加 solon-ai ToolCallResultConverter 接口（工具调用结果转换器）
* 添加 solon-ai ToolCall 添加 Mapping 和 Param 注解（支持与 web api 打通）
* 添加 solon-ai Tool.returnDirect 属性，用于直接返回给调用者（mcp 目前无法传导此属性，只能地本地用）
* 添加 solon-ai-mcp McpChannel 通道（stdio, sse），实现不通道的支持
* 添加 solon-ai-mcp stdio 通道交换流支持
* 添加 solon-ai-mcp McpClientToolProvider 断线重连机制
* 添加 solon-ai-mcp McpClientProperties:fromMcpServers 方法
* 调整 solon-ai-mcp McpClientToolProvider.Builder:header 更名为 headerSet。保持与 ChatModel:Builder 相同风格
* 调整 solon-ai ToolCallResultConverter 申明不再扩展自 Converter，避免冲突
* 修复 solon-ai-mcp McpClientToolProvider 会丢失 queryString 的问题
* 修复 solon-ai-load-word WordLoader 流使用错误问题
* 修复 solon-ai ollama 方言，在多工具调用时产生 index 混乱的问题

### 3.2.0

* 新增 solon-ai-mcp
* 调整 函数(Function)相关的概念改为工具概念(Tool)。调整后适合 mcp 的相关概念

### v3.1.2

* 新增 solon-ai-repo-chroma 插件
* 优化 solon-ai-repo-tcvectordb 插件相似度处理
* 优化 solon-ai-repo-elasticsearch 插件相似度处理

### v3.1.1

* 新增 solon-ai-load-ppt 插件，添加对 ppt, pptx 文档的解析
* 新增 solon-ai-load-word 插件，添加对 doc, docx 文档的解析
* 新增 solon-ai-repo-qdrant 插件
* 新增 solon-ai-repo-tcvectordb 插件
* 新增solon-ai-repo-elasticsearch 插件
* 添加 solon-ai ChatResponse:getAggregationMessage(), isStream() 方法
* 添加 solon-ai ChatSession 自动登记处理
* 调整 solon-ai ChatDialect:parseResponseJson 定义
* 修复 solon-ai 在流式调用时 function call 出错的问题