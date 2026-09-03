# solon-ai-ui-agui

为 Solon AI 提供 AG-UI 协议事件模型，并提供核心 `ChatEvent` 到 AG-UI 的流式适配器。

## ChatEvent 适配

```java
AgUiStreamWrapper wrapper = AgUiStreamWrapper.of("thread-1", "run-1");
Flux<Event> stream = wrapper.toAgUiStream(chatModel.prompt(prompt).stream());
```

适配器负责：

- 将 `TEXT_*` 转换为 `TEXT_MESSAGE_*`；
- 将 `THINKING_*` 转换为现代 `REASONING_*`（包含 reasoning message 边界）；
- 将客户端工具调用转换为 `TOOL_CALL_START → TOOL_CALL_ARGS → TOOL_CALL_END`；
- 将 `RESPONSE_*`、`STEP_*` 转换为 `RUN_*`、`STEP_*`，并保证正常、错误和中断收尾；
- 使用 `itemId/index` 维持多内容块的稳定消息 ID；
- AG-UI 尚无标准表示的服务端工具、媒体、安全、用量和自定义事件统一保留为 `CUSTOM`，`RAW` 则保留为 `RAW`。

`ChatEvent` 仍是 Solon AI 内部的供应商无关事件模型，AG-UI 只作为对外协议适配层。需要 SSE 时，可在控制器中将 `Flux<Event>` 按项目使用的 JSON 序列化器输出。

## 状态增量

`StateDeltaEvent` 提供强类型的 RFC 6902 JSON Patch：

```java
StateDeltaEvent delta = new StateDeltaEvent()
        .add(JsonPatchOperation.replace("/progress", 50))
        .add(JsonPatchOperation.add("/message", "处理中"));
```

原始协议说明见：https://docs.ag-ui.com/concepts/events
