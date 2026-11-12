# harness 国际化方案（一期）

> 状态：一期完成（H0~H5），代码未提交。使用文档：`solon-ai-harness-core/docs/I18n.md`；前置：solon-ai-agent 的 i18n（`solon-ai-agent/docs/I18n.md`）。

## 1. 范围

| 本期做 | 本期不做（二期） |
|---|---|
| solon-ai-harness-core / goal / loop / queue / steer（host 无文案） | solon-ai-talent-cli / code / memory / gateway |
| solon-ai-talent-todo / web / lsp | |

本期完成后，英文会话里 Terminal、Skill、Code、Memory、Gateway 的指令与工具描述仍是中文（会中英混排），二期补齐。

## 2. 目标

- 模型看到的文案、用户看到的文案都跟随请求语言；
- 中文输出与改造前逐字不变；
- 新增语言只加 yml；
- 沿用 agent 约定：yml `|-` 块标量、值首尾无空白（空白由代码补）、命名占位符 `{name}`、key 统一在常量类、key 测试覆盖 100%；
- 日志、javadoc 不做国际化；原本就是英文的协议性文案（如 `ERROR:` 前缀）保持不变。

## 3. 设计决策

### D1 引擎下沉到 solon-ai-core

- core 新增依赖 `solon-i18n`（只依赖 `solon`）。
- 新增包 `org.noear.solon.ai.i18n`：
  - `AiI18n`：全局部分，包括默认语言、覆盖工厂、已知语言、语言属性 key `ATTR_LOCALE = "__locale"`、`localeOf`（写入改用 `Prompt#locale(Locale)`，`putLocale` 已删除）、`format`、缓存清理（清空所有已注册实例）。
  - `I18nMessages`：每个模块各建一个实例（各自的内容包列表），提供 `get` / `fmt` / `messages` / `textsOfAllLocales` / `itemsOfAllLocales`。加载在锁外进行，带代际号和线程内重入保护（逻辑从 `AgentI18n` 原样搬入）。
- `AgentI18n` 只保留 agent 的内容包列表，静态方法签名不变，内部调用上述引擎。默认语言和覆盖工厂改为全局共享，agent、harness、talent 一致。

### D2 工具描述本地化（已撤回）

最初的做法是 core 新增 `LocalizedFunctionTool`、`AbsTalent#i18n()`，工具描述按 `tool.<工具名>.desc` / `tool.<工具名>.param.<参数名>` 从 yml 读取。2026-11 评估后撤回：工具描述只给模型看、界面上不显示，对回复语言影响很弱，第三方 / MCP / 网关工具又本就做不到本地化；代价是注解失去可读性、工具提示词要维护两份且英文版未经模型实测。现状：描述与参数说明留在注解里（单一语言，与 4.1 逐字一致），`LocalizedFunctionTool` 与 `AbsTalent` 的本地化钩子已删除，资源中不得出现 `tool.*` key（lint 拦截）。工具**返回文案**仍按请求语言输出。

### D3 每类文案用什么语言

| 类别 | 语言来源 |
|---|---|
| 指令、工具描述 | `Prompt.attrs[__locale]` |
| 工具返回值 | 工具方法参数 `String __locale`（toolContext 注入），没有时用默认语言 |
| harness 给用户看的输出 | 输入的生效语言 `engine.localeOf(input)`（见下文「语言来源」） |
| 参与判断的标记、关键词 | 生成时用当前语言；识别时匹配所有已知语言 |
| 整份提示词文件（`agents/*.md`） | 按语言加后缀的文件（如 `explore_en.md`），找不到时用不带后缀的文件 |

### D4 公开常量改为方法（均为 4.2.0 新增、未发布）

- `InterruptPlan.CANCEL_TEXT` 改为 `cancelText(Locale)` + `isCancelText(String)`
- `SteerProtocol.PREFIX` 改为 `prefix(Locale)` + 跨语言识别
- `CommandDispatcher.COMMAND_*_HINT` 改为方法，开头的空格由代码补
- soloncode 要同步修改：`WebRunSink`、`LastTraceService`

### 语言来源（输入优先，引擎实例兜底）

- 引擎实例配置默认语言：`HarnessEngine.Builder.locale(Locale)` / `engine.getLocale()`（不配置为 null，即全局默认语言）。
- 一次输入的生效语言：`engine.localeOf(input)` = `input.getLocale()`，没有时用 `engine.getLocale()`；都没有为 null（沿用智能体配置 / 全局默认）。不回写到输入：积压只保存输入自己指定的语言，续发时再按本规则取值。
- 受理时取一次并下传整条链路：本轮 `options.locale`（协议指令、才能指令与工具描述、工具参数 `__locale`、HITL 理由、取消消息、子代理）、命令输入 `input.getLocale()`、忙碌 / 待审批提示、附件前缀与默认提问、命令作用域拒绝说明。
- 后台轮次（goal / loop，`HarnessInput.ofProgram`）不带语言，因此用引擎实例的语言；goal 生成提示词、校验说明等没有输入的地方直接用 `engine.getLocale()`。
- 没有输入的门面 API：queue `enqueue` 的“队列已满”按 `engine.localeOf(input)`；loop `schedule` / `update` 的规格异常说明按 `engine.getLocale()`。
- 插话未消费、转入积压时带上本轮轨迹的语言（`SteerMailbox.transferToBacklog(..., locale)`），续发与原轮次同一语言。

### 续流的语言（语言属于一轮）

语言在一轮开始时定一次，记在这一轮自己身上（原始提示词的 `__locale`，随会话快照保存）。续流（`decide`、`resume(true)`、`/continue`、`engine.resume()`）不是新的一轮，默认不重新决定，所以 `decide` 不加语言参数。

- **agent 层（规则只在这里）**：`ReActTrace#getRunLocale()` 读原始提示词上的语言；`ReActAgent#runLocaleOf(trace, prompt)` 定本轮默认语言——新任务用智能体配置的语言（不再沿用上一轮），续流用 `getRunLocale()`、取不到再用智能体配置的语言。`ReActRequest.init` 在应用请求选项之前调用它，请求显式指定的语言最后生效；直接 `call(prompt, session)` 的非团队路径同样适用；团队成员仍继承团队语言。
- **harness 层**：`engine.localeOf(input)` 不变。装配本轮请求时（`InputPipeline.runLocaleOf`），新任务用 `localeOf(input)`；续流输入带了语言就用输入的，没带就不设，由智能体沿用；只有这次续流实际要接着跑的代理的轨迹没留下语言（升级前的旧会话或从未跑过）时，才用引擎语言。
- 续流被“会话忙”拒绝时，提示按 `localeOf(input)`（是对这次输入的回应）。
- 行为变化：引擎没配语言、输入也没带语言时，同一会话的新任务不再沿用上一轮的语言（原来只在内存里碰巧沿用，重新加载后又不沿用）。
- 测试：agent `ReActRunLocaleTest`（8 例），harness `HarnessEngineLocaleTest`（9 例，含审批决定与快照重新加载）；两处都做了变异验证（去掉改动后对应用例会失败）。

### 其它

- `__locale` 同时写入 `Prompt.attrs` 和 toolContext（原来的 `_agent_locale` 是 4.2.0 新增、未发布，直接改名，不保留兼容）。
- 子代理继承主代理的语言（`TaskTalent` 同步和流式两条路径）。

## 4. 里程碑

| 阶段 | 内容 | 完成标准 |
|---|---|---|
| H0 | 用改造前的代码生成本期 8 个模块的中文渲染快照 | 反向验证：删一个换行、改一个空格，快照都会报错 |
| H1 | core 引擎、`AgentI18n` 改为调用它、`LocalizedFunctionTool`、`AbsTalent.i18n()`（后两项已随 D2 撤回）、`__locale` 写入 toolContext | agent i18n 套件全部通过，148 份快照不变；新类行和分支覆盖 100% |
| H2 | todo / web / lsp | 中文快照不变；英文经人工审校；key 覆盖率 100% |
| H3 | harness-core | 同上；子代理继承语言；中文 CANCEL_TEXT 在英文会话下仍能识别 |
| H4 | goal / loop / queue / steer | 同上；插话前缀和执行意图关键词跨语言识别 |
| H5 | lint、覆盖率测试、文档、soloncode 同步清单、全量回归 | harness 全量测试与基线一致 |

## 5. 进度与已定约定

| 阶段 | 状态 |
|---|---|
| H0（talent 部分）、H1 | 完成 |
| H2 todo / web / lsp | 完成：中文快照逐字不变；英文快照已审校；lint 与 key 覆盖率（中英 100%）通过；harness-core 回归与基线一致（32 失败 + 5 错误） |
| H3 harness-core | 完成：中文快照与改造前逐字一致；英文快照已审校纳入；lint 与 key 覆盖率（中英 100%，含 HarnessI18nScenarioTest 的低频分支）通过；内置子代理按语言分文件（BuiltinAgentI18n）；全量回归 698 例 32 失败 + 5 错误，与基线一致 |
| H4a steer | 完成：`SteerProtocol.PREFIX` 改为 `prefix(Locale)`（结尾空格由代码补），`textOf` 匹配所有已知语言的前缀（取最长）；注入前缀跟随轨迹语言；/steer 命令按命令上下文语言输出；core 新增 `Command.examples(Locale)`（HelpCommand 改用它）；模块 80 例全过 |
| H4b queue | 完成：/queue 命令与门面 `enqueue` 的“队列已满”说明（跟随 `HarnessInput.getLocale()`）；多行用法按行输出、续行按各语言对齐；模块 110 例全过 |
| H4c loop | 完成：工具描述与返回值走 `tool.*` / `loop.tool.*`；`LoopSpec` / `LoopCron` / `LoopScheduler` 的规格异常改为 `LoopArgumentException`（携带 key 与变量，`getMessage()` 为默认语言，`getMessage(Locale)` 由工具与命令按请求语言渲染）；`LoopHostApiTest` 公开类型白名单加入异常类与文案门面；模块 107 例全过 |
| 引擎语言 | 完成：`HarnessEngine.Builder.locale` + `engine.localeOf(input)`；InputPipeline / SessionControl / CommandDispatcher 统一取生效语言；queue 门面、loop 门面、steer 转积压补齐；`HarnessEngineLocaleTest`（引擎英文 / 输入覆盖 / 程序轮次 / 不回写输入 / 给用户的文案）与 steer 用例；回归：core 701 例 32 失败 + 5 错误（与基线一致），steer 80、queue 110、loop 107、goal 236、host 80 全过 |
| 续流语言 | 完成：语言属于一轮（见「续流的语言」）；agent `ReActTrace#getRunLocale` + `ReActAgent#runLocaleOf`，harness `InputPipeline.runLocaleOf`；`ReActRunLocaleTest` 8 例、`HarnessEngineLocaleTest` 共 9 例（变异验证可拦截）；回归：agent 离线套件 272 例仅 `MultiAgentHitlTest` 1 个既有错误，core 707 例 32 失败 + 5 错误（失败类与基线相同），steer 81、queue 110、loop 107、goal 236 全过，host 80 例除既有偶发的 `streamCancelledDuringAdmissionStopsTheRun` 外全过 |
| H4d goal | 完成：`GoalI18n` + `GoalI18nKeys`（62 个 key，另有 3 个工具描述 key 按约定读取）+ `goal.yml` / `goal_zh.yml`。轮次提示词（`GoalPromptBuilder` 方法加 `Locale` 参数，旧签名保留为默认语言）、轮次结果记录（未认领 / 完成被拒，写入 lastResult）、门面冲突说明用引擎语言 `engine.getLocale()`，Goal 不存语言；`goal_get` / `goal_update` 描述走 `tool.*`，返回值按 `__locale`（旧三参 / 两参签名保留）；验证说明按调用方语言：`GoalValidator` 新增默认方法 `validate(condition, workDir, Locale)`（自定义验证器不用改），`CommandValidator`（4.2.0 新增、未发布）的 `failurePrefix` / `whenNothingToRun` 直接改为带 `Locale`；创建冲突抛包内 `GoalActiveException`，/goal 命令改按命令上下文语言输出；执行型目标关键词放进 `goal.action_keywords`，识别时合并所有语言（判定结果与改造前一致）。测试：`GoalRenderGoldenTest`（中文 43 份由改造前代码生成，改造后默认 / 显式中文逐字一致，反向验证可拦截；英文 43 份已审校）、`GoalI18nLintTest`、`GoalI18nCoverageTest`（中英 100%）、`GoalI18nScenarioTest`；模块 243 例全过，host 80 例全过 |
| H5 | 完成：新增 `solon-ai-harness-core/docs/I18n.md`（core README 专题表与配置表加入口），`solon-ai-agent/docs/I18n.md` 补共享引擎、续流语言、工具 `__locale`；UPDATE_LOG 补 core / agent 续流 / harness 三条；soloncode 同步（`WebRunSink` 取消回执改 `InterruptPlan.cancelText(HarnessI18n.localeOf(trace))`，与落库的取消消息同一语言；`LastTraceServiceTest` 改 `prefix(null)`），soloncode 全模块编译通过，soloncode-cli 1227 例 3F（settings 既有）。全量回归：core 257 例 1F+10E（Redis / SemanticSplitter 需外部服务，`ToolCallJsonSanitizerTest` 在 HEAD 上同样失败），agent i18n 103 例、goal 243、loop 107、queue 110、steer 81、host 80、talent todo/web/lsp 178 全过，harness-core 707 例 32F+5E（与基线一致；其中 4 条中文断言的文案在 HEAD 策略类里原样存在，失败源于规则顺序而非文案） |

harness-core 在 H4 改动 `Command` / `HelpCommand` 后全量回归：698 例 32 失败 + 5 错误，与基线一致。

每个接入模块的约定（H2 定下，H3/H4 沿用）：

- 门面 `XxxI18n`（持有本模块的 `I18nMessages`，提供 `messages()` / `get` / `fmt`）+ 常量类 `XxxI18nKeys`；资源为 `<artifactId>/i18n/<name>.yml`（英文兜底）与 `_zh.yml`。
- 工具描述与参数说明留在注解里，不进资源（见 D2）。
- 工具方法末尾加 `String __locale`（不带 `@Param`，不进 schema），用 `AiI18n.toLocale(__locale)` 转换；原来的公开签名保留为不带注解的重载（传 null，即默认语言）。（2026-11-12 更正：这些重载只有测试在用，已全部删除，见 UPDATE_LOG「工具方法删除兼容重载」。）
- 原本就是英文的返回文案（websearch / webfetch 的错误、lsp 的服务器提示、`TODO saved.`）保持不变。
- 内置子代理（`agents/*.md`）：基础文件是中文版兼兜底；其它语言放 `META-INF/solon/ai/harness/agents-i18n/<name>_<lang>[_<COUNTRY>].md`（不在内置挂载扫描目录里，避免重复注册），只替换 description 与正文，工具等元数据以基础文件为准；查找顺序 语言_国家 → 语言 → en；只作用于内置挂载的定义，工作区 / 用户挂载与运行时注册的同名定义原样使用。入口 `HarnessEngine.localizeAgent(def, locale)` 与 `getAgentBuilderOrNil(session, name, model, locale)`。
- 测试：`XxxRenderGoldenTest`（testZh / testZhExplicit / testEn）、`XxxI18nLintTest`（`I18nLint`）、`XxxI18nCoverageTest`（`I18nKeyCoverage`，新增行为用例类要登记）。生成英文快照只跑 en 方法：`-Dtest='Xxx#testEn' -Dgolden.update=true`（多个类用逗号分隔），避免覆写中文基线。
