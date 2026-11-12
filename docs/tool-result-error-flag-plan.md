# 工具失败标记（ToolResult）优化方案

## 规则

- 失败标记只表示"这次调用没完成要做的事"（参数不合法、被拒绝、找不到对象、执行出异常）；查询结果为空、状态本来就是"没有"、`WARNING:` 都算成功。
- 给模型看的文本一个字不改：`ERROR: `、`[OK] `、`WARNING: `、错误码 JSON 全部保留（前缀给模型看，失败标记给宿主看）。
- 只把带 `__locale` 的工具方法（4.2 新增）改为返回 `ToolResult`；参数更少的旧签名已全部删除（见「第 5 批」）。
- 每个类用一个私有 `err(...)` 同时生成前缀和失败标记。
- harness 前缀固定英文 `ERROR: `；Terminal 前缀跟随语言。本次不统一。

## 进度

- [x] 第 1 批：`bash` 按进程是否正常结束判成败（不看退出码）；Anthropic 方言在 `ToolMessage.isError()` 时设置 `is_error`（两件一起上）
- [x] 第 2 批：`generate`、`loop_*`、`goal_update` 改返回 `ToolResult`；`goal_get` 不改
- [x] 第 3 批：`task` / `multitask` 用"成功计数"判成败
  - `task`：成功数为 0 即失败
  - `multitask`：被取消/中断，或成功数为 0，算失败；部分成功算成功；空列表（`WARNING:`）算成功
  - 嵌套调用直接失败

## 验证

- 每批：快照文件（`i18n-golden/*.txt`、`*_tools.txt`）一个字节不变
- 第 1 批：talent-cli、dialect-anthropic 全量通过
- 第 2 批：harness-loop、harness-goal 全量通过，harness-core 编译通过
- 第 3 批：harness-core 不新增失败
- 收尾：core、agent、dialect-anthropic、talent-cli、harness-core、loop、goal、host 跑 `mvn clean test`；soloncode-cli 测试编译通过

## 不在本次范围

- 用 `metas` 带退出码或"3/5 成功"给前端
- 统一 `ERROR: ` 与"错误："两种前缀
- 改 `goal_get` 的文本

## 审查后修正（2026-11-12）

- [x] `ProcessExecutor`：二进制输出分支也追加 `[exit_code=N]`（故障注入验证用例能拦住回退）
- [x] `LoopTalent.doPauseResume` 去掉多余括号
- [x] `TaskTalentErrorFlagTest` 移到 `features.ai.harness`；`taskOp` / `multitaskContext` / `aggregate` 移入 `HarnessFixtures`（改为 public），不再依赖 `HarnessRenderGoldenTest`
- [x] `LoopTalentErrorFlagTest` 解析 JSON 断言 `total`，不依赖输出空格

## 第 4 批：Terminal 剩余工具（2026-11-12）

- [x] `read` / `ls` / `grep` / `glob` / `bash_start`（带 `__locale`，4.2 新增）改返回 `ToolResult`
- [x] `bash_wait`：返回 `ToolResult`，仍是 4 个参数（快照文本不随语言变化，不取 `__locale`）
- [x] `bash_stop` 不改（终止即成功；会话不存在本就抛异常，框架按失败处理）
- 算失败：路径/文件不存在、二进制、读目录、虚拟文件超限、起始行越界、搜索路径不存在或多路径、bash_start 拒绝/沙盒不可用、会话被硬超时终止
- 算成功：空目录、空文件、未找到结果、会话仍在运行或正常结束（不论退出码）
- 验证：`TerminalTalentFileToolErrorFlagTest` 6 例（故障注入验证）；快照未变；cli 全量仅 2 例既有时序不稳；依赖 cli 的模块与 soloncode-cli 测试编译通过

## 第 5 批：删除工具方法的兼容重载（2026-11-12）

- [x] 才能里与 `@ToolMapping` 方法同名、参数更少的重载全部删除（15 个类共 48 个；soloncode 无）。工具由框架反射调用，这些重载只有测试在用
- [x] `bash_wait` 去掉只为区分旧签名才加的 `__locale`
- [x] 测试改调工具方法（末尾传 `null`，返回 `ToolResult` 的取 `getContent()`），删除比较新旧签名文本的断言；harness-core README 的 `generate` 示例同步
- 验证：cli 725、todo、web、lsp、memory、gateway、harness-core（733，33F+5E 与基线相同）、goal 248、loop 112、host 80 通过（host `streamCancelledDuringAdmissionStopsTheRun` 时序不稳，重跑通过）；web `WebsearchTalentTest` 依赖外部服务、gateway `Petstore2/3ApiTest.case12_disabledSource` 是既有失败（"未找到"文案里带着搜索词），与本次无关；快照未变；soloncode-cli 测试编译通过
