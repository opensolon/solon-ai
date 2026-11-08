# solon-ai-harness 核心概念

只列 SaaS 开发者（L0，见 [QUICKSTART-SAAS.md](QUICKSTART-SAAS.md)）需要掌握的概念。其余类型属于 L1 / L2，
见 [README.md](README.md) 与 [SAAS.md](SAAS.md)。

## 术语

| 术语 | 含义 |
|---|---|
| **input** | 一次提交（用户消息、斜杠命令、审批决策）。对应 `host.submit` / `host.decide` 的一次调用 |
| **run** | 受理后开出的一条运行流。一个 input 最多开一个 run；排队的 input 在会话空闲后各自开 run |
| **step** | ReAct 内部的一步（推理或工具调用）。`maxTurns` 沿用旧名，计的是 step |
| **积压** | 会话忙时排进来的 input（由 queue 插件保存），空闲后按先进先出自动续发 |

## 13 个概念

| 类别 | 概念 | 一句话 |
|---|---|---|
| 部署 | `HarnessHost` | 宿主骨架：受理、开流、终态、续发都在这里，宿主只投递输出 |
| | `HarnessProfile.SERVER` | 服务端预设：保守缺省 + 构建期校验 + 运行期锁定引擎全局配置（扩展在构建期 `extensionAdd`） |
| | `stateSource` | harness 自身状态（goal 索引、loop 任务等）的存储来源，必须在工作区之外 |
| 输出 | `HostSink` | 输出通道：`reply`（文本回执）+ `event`（流事件），其余可选 |
| | `InputOrigin` | 一次输入的来源（渠道、发送者、返回地址），也是回复路由 |
| 权限 | 会话主体（principal） | 「这个会话属于谁」，`engine.setSessionPrincipal` 写入，随会话持久化 |
| | `InputScope` / `InputScopeResolver` | 「这次输入能用什么」：工具、技能、MCP、命令名单；按主体解析，只能收窄 |
| 交互 | `SubmitResult` | 提交结果，只用于映射状态码（内容已经经 sink 发出） |
| | `HitlCard` / `HITLAction` | 待审批的工具调用 / 审批决策（approve、reject、skip） |
| | `InterruptMode` | `INTERRUPT_ALL`（清队列 + 停流 + 写取消消息）、`CANCEL_STREAM`（只停当前流）或 `PREEMPT_STREAM`（腾位，不推进取消代数） |
| 管理 | `SessionAdmin` | 管理态 `call(sid, 扩展类型, 操作)`：归属、存在性检查 + 成功后自动续发，任意扩展同一写法；鉴权归宿主 |
| | `HarnessResult` | goal / loop / queue / steer 写门面与管理入口的统一结果：粗码 OK / NOT_FOUND / NOT_OWNER / DISABLED / FULL / INVALID / CONFLICT / FORBIDDEN，失败时可带细分码 `getReason()`（应答码用 `getReasonOrCode()`） |
| 多节点（可选） | `SessionOwnership` | 会话单写者租约：同一时刻只由一个节点执行一个会话 |

## 隔离的三层

| 层 | 载体 | 隔离什么 |
|---|---|---|
| 引擎 | `HarnessEngine`（每租户一个，host 模块的 `HarnessEnginePool` 管理） | 文件系统、MCP / LSP 连接、模型配置、扩展实例 |
| 会话 | `AgentSession` | 对话历史、主体、审批挂起、goal / queue / steer / loop 状态 |
| 输入 | `InputScope` | 这次输入能用哪些工具、技能、MCP、命令 |

## 不需要掌握的（L1，由 HarnessHost 代办）

`InputOutcome` 的六种结局与约 25 个访问方法、`RunTicket`、`RunDispatch`、`afterRun`、`discard`、`QueueClaim` /
`drainLoop`、`InterruptPlan`、`isBusy` / `isOccupied` / `isIdle` 等状态口径、两段式中断 `prepareInterrupt`
与 `NotOwnerException`。只有自带终态门、需要自定义输出时序的宿主才需要它们。
