# 工具失败标记（ToolResult）优化方案

## 规则

- 失败标记只表示"这次调用没完成要做的事"（参数不合法、被拒绝、找不到对象、执行出异常）；查询结果为空、状态本来就是"没有"、`WARNING:` 都算成功。
- 给模型看的文本一个字不改：`ERROR: `、`[OK] `、`WARNING: `、错误码 JSON 全部保留（前缀给模型看，失败标记给宿主看）。
- 只把带 `__locale` 的工具方法（4.2 新增）改为返回 `ToolResult`；旧签名保留返回 `String`，转调 `.getContent()`。
- 每个类用一个私有 `err(...)` 同时生成前缀和失败标记。
- harness 前缀固定英文 `ERROR: `；Terminal 前缀跟随语言。本次不统一。

## 进度

- [x] 第 1 批：`bash` 按进程是否正常结束判成败（不看退出码）；Anthropic 方言在 `ToolMessage.isError()` 时设置 `is_error`（两件一起上）
- [x] 第 2 批：`generate`（保留 8 参数、返回 `String` 的默认语言签名 `generate(..., __cwd, __sessionId)`，README 示例用它；4.1.1 的 7 参数签名补回并标 `@Deprecated`）、`loop_*`、`goal_update` 改返回 `ToolResult`；`goal_get` 不改
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

- Terminal 的 `read`/`ls`/`grep`/`glob`/`bash_*` 失败标记（以后单独做）
- 用 `metas` 带退出码或"3/5 成功"给前端
- 统一 `ERROR: ` 与"错误："两种前缀
- 改 `goal_get` 的文本

## 审查后修正（2026-11-12）

- [x] `generate`：恢复 8 参数默认语言签名（README 示例可编译），4.1.1 的 7 参数签名改为 `@Deprecated` 委托
- [x] `ProcessExecutor`：二进制输出分支也追加 `[exit_code=N]`（故障注入验证用例能拦住回退）
- [x] `LoopTalent.doPauseResume` 去掉多余括号
- [x] `TaskTalentErrorFlagTest` 移到 `features.ai.harness`；`taskOp` / `multitaskContext` / `aggregate` 移入 `HarnessFixtures`（改为 public），不再依赖 `HarnessRenderGoldenTest`
- [x] `LoopTalentErrorFlagTest` 解析 JSON 断言 `total`，不依赖输出空格
