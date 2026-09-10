# AssistantMessage 协议状态分层与 `contentRaw` 兼容迁移方案

## 1. 背景与结论

`AssistantMessage.contentRaw` 当前同时可能承载普通正文副本、供应商原始 content，以及 thinking signature、encrypted content、server-tool block、container 等只能由原协议继续使用的状态。该字段没有协议作用域、版本和失效规则，既不利于持久化演进，也可能在切换 LLM 后影响消息过滤或被错误回放。

本方案确认应推进以下分层：

- **通用语义层**：`text`、`thinking`、`blocks`、`toolCalls`、`searchResults`、`citations`、应用 `metadata`。可跨 LLM 持久化，并由目标方言重新编码。
- **协议回放状态层**：按协议命名空间隔离，只允许匹配方言精确回放不可重建数据。
- **旧字段兼容层**：`contentRaw` 等字段保留、标记弃用、允许旧 JSON 反序列化；新解析链路停止写入，原方言提供懒兼容读取。

迁移目标不是消灭所有 raw，而是消灭**无作用域、无版本、职责混杂**的 raw。

## 2. 设计原则

1. `solon-ai-core` 不识别 Anthropic、OpenAI、Gemini 等具体供应商字段。
2. 新状态使用稳定协议标识，例如：
   - `anthropic.messages`
   - `openai.responses`
   - `google.generate-content`
   - `google.interactions`
3. 目标方言只读取自己的状态；foreign state 一律忽略。
4. 请求构建优先级：
   1. 匹配且有效的新协议状态；
   2. 当前方言认识的旧字段兼容状态；
   3. 通用语义字段重建。
5. 旧 JSON 必须继续反序列化；新消息不再制造正文镜像 `contentRaw`。
6. 修改 `text`、`thinking`、`blocks` 或 `toolCalls` 后，不得盲目复用旧协议快照。
7. 协议状态只保存无法安全重建或精确回放必需的数据，避免长期重复保存普通正文和媒体。
8. `toString()`、日志不得直接输出 signature、encrypted content 等敏感载体。

## 3. 目标数据模型

### 3.1 `MessageProtocolState`

在 core 中增加供应商无关的普通 Bean：

```java
public class MessageProtocolState implements Serializable {
    private int version;
    private String semanticHash;
    private Map<String, Object> data;
}
```

字段语义：

- `version`：该协议状态自身的数据结构版本，由对应方言解释。
- `semanticHash`：协议快照生成时对应的通用消息语义摘要；用于防止消息被修改后仍回放陈旧快照。
- `data`：仅由匹配方言解释的 JSON 可序列化数据树，不保存 SDK 运行时对象。

### 3.2 `AssistantMessage.protocolStates`

```java
private Map<String, MessageProtocolState> protocolStates;
```

核心 API：

```java
Map<String, MessageProtocolState> getProtocolStates();
MessageProtocolState getProtocolState(String protocol);
boolean hasProtocolState(String protocol);
boolean hasProtocolStates();
AssistantMessage putProtocolState(String protocol, MessageProtocolState state);
AssistantMessage removeProtocolState(String protocol);
AssistantMessage clearProtocolStates();
```

Map 支持迁移期并存多个协议投影，但每个解析器默认只写自己的协议状态。

### 3.3 语义摘要

摘要至少覆盖：

- `text`
- `thinking`
- `blocks` 的稳定语义
- `toolCalls` 的 id、name、arguments
- 非空 `searchResults` 与 `citations` 的公共语义

为保持 M1–M5 已持久化状态的摘要兼容，没有来源数据时不向摘要树增加新键；只有类型化来源非空时才纳入摘要。

摘要不覆盖：

- `createdAt`
- 应用 metadata
- protocol states
- 临时 UUID

同协议精确回放前必须校验版本与摘要；缺少摘要、版本不匹配或摘要不匹配均按 fail-closed 处理，并降级为通用语义重建。只有旧字段兼容路径可以在没有摘要时由原方言识别，不能把无摘要的新 protocol state 当作可信快照。

## 4. 旧字段兼容策略

### 4.1 `contentRaw`

保留字段和 getter，以便旧 JSON Bean 反序列化：

```java
/**
 * @deprecated 使用通用语义字段与 protocolStates；仅用于旧数据兼容。
 */
@Deprecated
private Object contentRaw;
```

相关旧构造器和 `getContentRaw()` 同步标记弃用。新推荐构造器不接受 raw，新解析器不再写 raw。

普通：

```java
new AssistantMessage("hello")
```

应满足：

```java
getContentRaw() == null
```

### 4.2 反序列化与重新序列化

- `ChatMessage.fromJson()` 不猜测旧 raw 属于哪个供应商，只负责无损恢复旧字段。
- 各方言在自己的 support 类中识别并读取旧状态。
- 第一阶段允许旧消息重新序列化时保留 deprecated 字段，避免尚未经过原方言升级的数据被静默丢失。
- 待所有已知协议完成迁移后，可增加显式 `upgradeLegacyState()`，不在序列化阶段做隐式、不可逆迁移。

### 4.3 其他旧字段

按独立里程碑逐步迁移并保留反序列化：

- `reasoningFieldName`
- `toolCallsRaw`
- `searchResultsRaw`
- `ToolCall.thoughtSignature`

其中 `searchResultsRaw` 已在 M7 标记弃用：新解析器只写 `searchResults`，旧 JSON 仍恢复原字段；`resolveSearchResults()` 在类型化字段为空时按 `index/id/title/url/snippet/summary` 公共键提供只读兼容投影，不修改或隐式升级旧消息。

不能在通用替代模型和原方言兼容回放完成前物理删除。

## 5. 各协议状态边界

### 5.1 Anthropic Messages

协议键：`anthropic.messages`

建议状态 data 的长期目标是去供应商前缀的结构化数据树；第一阶段为降低旧数据兼容分支复杂度，在 `anthropic.messages` 命名空间内继续使用既有键名，同时同时兼容 JSON 字符串块与结构化 Map 块：

```json
{
  "thinkingSignature": "sig_xxx",
  "redactedThinkingBlocks": [],
  "anthropicContentBlocks": [],
  "anthropicServerToolBlocks": [],
  "anthropicContainer": {"id": "cnt_xxx"}
}
```

其中完整 ordered blocks 用于保留 thinking/signature 配对、citations、redacted thinking、server tool opaque 数据和协议顺序；顶层 container 独立保存。

兼容读取旧键：

- `thinkingSignature`
- `redactedThinkingBlocks`
- `redactedThinkingData`
- `anthropicContentBlocks`
- `anthropicServerToolBlocks`
- `anthropicContainer`

### 5.2 OpenAI Responses

协议键：`openai.responses`

建议状态 data：

```json
{
  "reasoningItemId": "rs_xxx",
  "reasoningEncryptedContent": "enc_xxx",
  "reasoningItems": [],
  "outputItems": [],
  "responseMessageItems": [],
  "phase": "commentary"
}
```

迁移后新消息不再把内部回放键写进应用 metadata；请求构建器仍兼容旧 metadata。

### 5.3 Gemini

协议键：

- `google.generate-content`
- `google.interactions`

建议按工具调用标识保存 thought signature。`ToolCall.thoughtSignature` 已标记弃用：保留 Bean 字段和访问器，以便旧 AssistantMessage/ToolCall JSON 反序列化及原协议回放；新解析器只写 Gemini 协议状态，不再双写旧字段。

## 6. 核心链路改造

### 6.1 `ChatAccumulator`

增加终态协议状态聚合：

- replace：完整替换 protocol states；
- merge：按 protocol key 后写覆盖；
- `replaceTerminalMessage` 清空旧状态，避免 return-direct 继承上一步状态；
- metadata 与 protocol states 始终独立。

旧 `terminalContentRaw` 在兼容期保留，只承载旧消息数据，不能作为新状态的主路径。

### 6.2 `ChatResponseDefault`

构建最终 `AssistantMessage` 时回填终态 protocol states。普通流式正文不得再次生成正文镜像 raw。

### 6.3 请求过滤

纯 thinking 消息不能再被任意 metadata 或 foreign raw 决定是否保留。每个目标方言应判断是否存在**自己可回放**的状态：

- 匹配状态存在：保留；
- 只有 foreign state：忽略并按目标能力决定是否跳过；
- 旧状态：仅由认识它的原方言兼容读取。

### 6.4 reasoning 字段

`reasoningFieldName` 不应由源消息无条件决定目标请求 JSON key。后续由目标方言根据当前模型和配置选择字段；旧值仅在同类 OpenAI-compatible 协议内按白名单兼容。

## 7. Agent、复制和压缩规则

- 完全克隆且语义未变化：可复制 protocol states。
- 修改正文、thinking、blocks 或 tool calls：默认清除 protocol states。
- 只修改普通 metadata：可以保留。
- 上下文压缩生成的新消息不继承旧协议状态。
- 对含 opaque 协议状态的消息，默认不做局部截断。
- SimpleAgent、ReActAgent、return-direct、媒体重建等链路必须明确选择“保留”或“失效”，不能机械复制。

## 8. 分阶段落地

### M0：兼容基线

- 固定旧 `content`、Anthropic `contentRaw`、OpenAI Responses metadata、Gemini thought signature JSON fixture。
- 验证 JSON/NDJSON/File Session 恢复。

### M1：Core 基础设施

- 增加 `MessageProtocolState`、`protocolStates` 和语义摘要支持。
- 打通 accumulator、response、JSON 持久化。
- 普通消息停止生成正文镜像 raw。

### M2：Anthropic

- call/stream 统一写 `anthropic.messages`。
- RequestBuilder 优先读取新状态，兼容旧 `contentRaw`。
- 覆盖 thinking signature、citations、redacted thinking、server tools、container、pause_turn。
- 验证持久化恢复与跨方言隔离。

### M3：OpenAI Responses

- 将 reasoning/output item/phase 从 metadata 迁入 `openai.responses`。
- 保留旧 metadata 懒兼容读取。

### M4：Gemini

- thought signature 迁入对应 Gemini 协议状态。
- 保留旧 `ToolCall.thoughtSignature` 兼容读取并标记弃用。
- 停止生成 thought/content 冗余 raw。

### M5：通用 tool call 与 reasoning（已完成）

- `ToolCall` 已成为通用工具调用重建主来源：OpenAI-compatible、Anthropic、Gemini、DashScope、Ollama 均优先从 typed call 按目标协议编码。
- `toolCallsRaw` 仅在 typed call 不存在时作旧 JSON 兼容回退；只转换标准 `type=function`，不把 server tool 或未知 vendor item 伪装为本地 function call。
- 新解析路径停止重复生成 `toolCallsRaw`；出站参数统一在协议边界执行 object 校验，截断或非法参数降级为 `{}`。
- 目标方言决定 reasoning 请求字段：DeepSeek-compatible 使用 `reasoning_content`，OpenRouter 使用 `reasoning`，Ollama 固定使用 `thinking`，Anthropic/Gemini 使用各自内容块规则；未知目标默认不回放。
- `toolCallsRaw`、`reasoningFieldName` 已标记弃用，但 Bean 字段和访问方法继续保留，旧 JSON 仍可恢复并按目标协议重建。
- `reasoningFieldName` 不再作为任意出站 JSON key，避免历史数据在切换模型后覆盖 `role` 等保留字段。
- Agent 在正文或块发生投影时不再复制旧 tool-call raw/reasoning 字段；Token 估算在 typed/raw 并存时只计算 typed 语义，避免双重计费。

### M6：`contentRaw` 完成弃用（已完成兼容期收敛）

- 所有新 parser 已停止写入。
- 新消息构建使用无 raw 构造器；生产读取仅剩各 dialect legacy adapter，以及 Accumulator/Agent/压缩器对历史消息的无损传递和保护。
- 字段、getter 与旧构造器保留并标记弃用，至少维持一个完整大版本兼容周期；旧 JSON 可恢复并重新序列化。

### M7：类型化 SearchResult/Citation（已完成）

- 新增 `SearchResult(index,id,title,url,snippet)` 与 `Citation(type,title,url,citedText)` 跨协议模型。
- `AssistantMessage`、`ChatResponse`、`ChatEvent` 与 `ChatAccumulator` 已打通类型化来源；新增 `SEARCH_RESULT` 事件，`CITATION` 增加 typed payload。
- OpenAI Responses、Anthropic、Gemini Generate Content/Interactions、DashScope 已从协议结构投影公共字段，供应商完整 raw 仍留在事件 raw 或对应 protocol state。
- `searchResultsRaw` 已弃用但继续反序列化；新解析链路停止写入，`resolveSearchResults()` 只作旧数据懒投影。
- Agent 内容投影会保留类型化来源但让不匹配的精确回放状态失效；上下文局部截断不会静默丢弃来源。
- Vercel AI SDK UI 包装器优先消费类型化来源，同时兼容旧 `searchResultsRaw`。

## 9. 兼容矩阵

| 数据来源 | 发往原协议 | 发往其他协议 | 持久化恢复 |
|---|---|---|---|
| 新通用语义 | 重建 | 重建 | 支持 |
| 新匹配 protocol state | 精确回放 | 忽略 | 支持 |
| 新 foreign state | 忽略 | 仅匹配方言可用 | 支持 |
| 旧 Anthropic contentRaw | Anthropic 兼容回放 | 忽略 | 支持 |
| 旧 OpenAI Responses metadata | Responses 兼容回放 | 忽略 | 支持 |
| 旧 Gemini thoughtSignature | Gemini 兼容回放 | 忽略 | 支持 |
| 旧 reasoningFieldName | 恢复通用 thinking，由当前目标方言选 key | 源字段名不直接使用 | 支持 |
| 旧 toolCallsRaw | typed 不存在时转换标准 function call | 按目标协议重建标准 function call | 支持 |

## 10. 必测项

### Core

1. 普通 assistant 的 `contentRaw == null`。
2. 旧 `content`、`contentRaw`、`toolCallsRaw`、`reasoningFieldName` 与嵌套 `ToolCall.thoughtSignature` JSON 可恢复。
3. protocol states JSON/NDJSON round-trip。
4. 多协议状态隔离。
5. semanticHash 在语义修改后失效。
6. accumulator merge/replace 行为。
7. metadata 与协议状态互不污染。

### Anthropic

1. call/stream 状态等价。
2. thinking signature、redacted thinking、citation、server tool encrypted block、container、pause_turn round-trip。
3. 旧 contentRaw 可回放。
4. foreign state 不进入请求。
5. 语义修改后不回放陈旧 ordered blocks。

### OpenAI Responses

1. reasoning id/encrypted content/output items/phase 持久化续跑。
2. 旧 metadata 回放。
3. 新消息 metadata 不含协议内部键。
4. foreign state 隔离。

### Gemini

1. 新响应只写 protocol state，不写 deprecated `ToolCall.thoughtSignature`。
2. thought signature、并行 tool call 与 JSON 状态恢复。
3. 旧 ToolCall literal JSON 可恢复，并由 Generate Content/Interactions 请求构建器回放。
4. 有效新状态优先；存在但失效的新状态不得回退旧字段。
5. foreign state 隔离，且不再生成冗余 raw。

### Agent

1. 纯克隆保留状态。
2. 修改语义清除状态。
3. 压缩不复制失效状态。
4. return-direct 不继承前序状态。

## 11. 风险控制

- 协议状态可能包含敏感 opaque 数据；`toString()` 只输出协议 key，不输出 data。
- 状态必须由 Map/List/String/Number/Boolean 构成，保证 JSON 可持久化；Anthropic 兼容期也接受旧的 JSON 字符串块。
- Session 的大媒体紧凑化只遍历类型化 `blocks`，禁止按字段名递归修改 `protocolStates`、metadata 或工具参数中的 opaque `data`。
- 完整 ordered blocks 可能较大；后续应逐步收敛为最小不可重建补丁。
- 每个方言集中一个 state support 类，统一版本、摘要、兼容读取和安全取值，禁止散落 Map key。
- 不在 core 的 `fromJson()` 中做供应商猜测。
- 不在所有方言未迁移前隐式删除旧字段。

## 12. 当前执行状态

### 已完成（M0–M7）

- M0 核心兼容基线：普通消息不再生成正文镜像 `contentRaw`；旧 `contentRaw` JSON 仍可恢复和重新序列化。
- M1 Core 基础设施：已增加 `MessageProtocolState`、`AssistantMessage.protocolStates`、语义摘要、终态聚合与 JSON 持久化支持。
- `contentRaw` 字段、getter 和旧 raw 构造器已标记弃用，但保留 Bean 字段以恢复旧数据。
- 新协议状态缺少 `semanticHash`、版本不匹配或摘要不匹配时 fail-closed；语义修改后自动降级为通用字段重建。
- 协议状态不参与媒体大字段紧凑化，避免 signature/encrypted content/opaque data 被静默截断。
- M2 Anthropic 已迁移：call/stream 新响应写入 `anthropic.messages`，RequestBuilder 优先读取有效新状态并兼容旧 `contentRaw`。
- Anthropic 可回放 thinking signature、redacted thinking、citations、有序 content blocks、server-tool opaque blocks 与 container；兼容字符串块和结构化 Map/List 数据树。
- foreign state 不被 Anthropic 消费；流式工具递归的合成消息不会覆盖真实响应保存的有序块。
- M3 OpenAI Responses 已迁移：reasoning id、encrypted content、reasoning/output/message items 与 phase 写入 `openai.responses`；新响应不再把这些内部键复制到应用 metadata，请求构建仍兼容旧 metadata。
- OpenAI Responses 协议状态优先于旧 metadata；持久化恢复后可继续精确回放，语义摘要不匹配或 foreign state 时退回通用消息重建。
- M4 Gemini 已迁移：Generate Content 与 Interactions 分别使用 `google.generate-content`、`google.interactions`；thought signature 按工具调用标识进入协议状态并支持 JSON 恢复。
- `ToolCall.thoughtSignature` 已标记弃用但保留 Bean 字段、getter/setter 和旧数据回放。新 Gemini 解析消息只写对应 `protocolStates`，不再同步写 deprecated ToolCall 字段；请求构建以有效目标协议状态优先，只有 Generate Content 与 Interactions 两类新状态都不存在时才兼容回退旧字段，避免两个 Gemini 协议之间串用签名。
- Gemini 不再生成 `{thought, content}` 形式的冗余 `contentRaw`；只有 metadata/foreign raw 的纯思考消息不会再产生空的目标协议消息。
- SimpleAgent 正文投影、ReAct 终态重建和 ContextCompression 已增加状态失效/保守保护。
- M5 通用工具调用已收敛：`ToolCall` 是所有已迁移请求构建器的主来源；`toolCallsRaw` 只作 raw-only 旧消息回退，新 parser 不再双写。
- 工具参数出站策略统一为“`argumentsStr` 权威、结构化 arguments 仅在原始字符串缺失时重建”；截断字符串不得被解析器生成的部分 Map 掩盖。
- M5 reasoning 已收敛：DeepSeek-compatible、OpenRouter、Ollama 等均由目标配置/方言选择请求字段，旧 `reasoningFieldName` 不再直写 JSON key；未知目标的纯 thinking 消息会安全过滤。
- `toolCallsRaw` 与 `reasoningFieldName` 字段/API 已弃用但保留反序列化能力；固定旧 JSON fixture 已验证恢复及下一轮请求重建。
- M6 已完成兼容期收敛：生产解析器不再生成 `contentRaw`，普通构造与消息工厂均使用通用字段；旧字段、getter、构造器及原方言 legacy adapter 保留。
- M7 已完成：Core 增加类型化搜索结果/引用、事件负载、终态聚合和响应 facade；`searchResultsRaw` 停止新写并标记弃用，旧 JSON 可继续恢复和懒投影。
- Anthropic 映射五类 citation 的公共字段及 `web_search_result`；OpenAI Responses 映射 URL/file citation；Gemini 映射 grounding web citation 与 Interactions 明确网页结果；DashScope 映射 `search_info.search_results`。
- 来源事件保留供应商 raw 与协议位置属性；重复累计快照按方言请求级稳定身份去重，但同一 URL 在不同引用位置不做全局去重。
- Agent 终态投影保留类型化来源，局部压缩对带来源消息采取保守策略；AI SDK UI 输出映射为 source-url/source-document。
- 收尾审查补强了协议状态的深层快照与冻结：输入 Map/List 在写入时深拷贝，绑定 semanticHash 后禁止再改版本、摘要或嵌套 data，避免状态内容被外部引用静默篡改而摘要仍匹配。
- OpenAI Responses 的解析期 metadata 使用 `__openai_responses.*` 私有命名空间，终态再显式提升为稳定 state key；用户 metadata 中同名旧键不会被误删。旧公开 metadata 仍由 legacy adapter 读取。
- 工具参数公共净化器严格要求“恰好一个完整 JSON object 根”，拒绝尾随垃圾与多根拼接；Gemini 累计快照的“取最后对象”行为收口在 Gemini 方言内部，不扩散到通用安全边界。
- OpenAI Chat Completions 的 `n>1` 输入按 4.1 单结果契约仅保留 `index=0`，并对主候选独立做累计快照归一化，其他候选不会混入正文或工具递归。

### 已验证

- Core/Agent 协议状态定向回归：Core 相关 44 项、Agent 相关 80 项通过，覆盖 protocol-state-only 终态、NDJSON/File Session、legacy raw-only 工具摘要、压缩不污染输入和 SimpleAgent 旧 thinking 投影。
- 五个方言模块全部测试共 670 项通过：DashScope 129、Ollama 97、OpenAI 246、Gemini 74、Anthropic 124；公共多模态/跨方言测试 45 项通过。
- 五方言 JaCoCo 全测试实测行覆盖率：DashScope 97.9%、Ollama 96.8%、OpenAI 85.4%、Anthropic 84.4%、Gemini 58.4%；方法覆盖率分别为 100%、100%、96.0%、96.0%、43.7%。Gemini 的整体数字包含大量纯配置/枚举/Bean 访问器和目前未启用的协议分支，不能用增加无语义断言的 getter 测试来机械追求 100%。
- 覆盖审查重点不是全模块的绝对 100%，而是本次协议状态变更的关键契约：新旧持久化、版本/摘要 fail-closed、foreign state 隔离、call/stream 回环、工具参数、Agent 语义变换和目标方言重建；这些路径均已有直接回归用例。
- 方言 Reactor 定向测试、公共方言测试及 `git diff --check` 均为 `BUILD SUCCESS`/通过。
- 全量测试命令另外确认了两类与本改造无关的环境阻塞：本机 Redis `localhost:6379` 未启动，以及远程 Embedding/LLM 集成测试缺少有效凭据或网络服务；这些不计为本地单元回归失败。

### 收尾审查补强

- Core：只有协议状态的终态消息不再被丢弃或误判为空；legacy `toolCallsRaw` 在 typed call 缺失时参与 semantic hash；NDJSON/File Session 恢复已锁定。
- Agent：自定义压缩策略即使返回输入原对象也不会原地清除状态或写 metadata；压缩新语义不会继承 legacy raw/signature；SimpleAgent 保留旧 `content` 中内嵌的 thinking。
- Anthropic：非流式 thinking 与流式统一走事件语义；空工具参数事件一致；有序 raw 块原子回放；block start 签名及异常 initial-input/delta 组合安全收敛。
- OpenAI Responses：旧 `responses_output_items` 与当前正文/工具调用可明确判定冲突时，不再覆盖当前通用语义；真实 parser 状态已覆盖 JSON 恢复与请求回放；Chat Completions 不消费 Responses 状态。
- Gemini：Generate Content/Interactions 的 legacy 签名回退互相隔离；协议状态只接受非空字符串签名；Interactions 新状态完成 JSON 回环覆盖。
- DashScope/Ollama：DashScope 不再因 deepseek 模型名生成空 Assistant，也不输出截断媒体造成的 `content:[]`；Ollama 统一 `thinking(Boolean)` 正确映射到原生 `think`。

### 已知边界与后续工作

- OpenAI Responses 的 `aggregationMetadata` 仅是单次解析工作区，键使用 `__openai_responses.*` 私有命名空间；终态消息把已知协议键提升到 protocol state 并清理这些私有键，未知应用键及同名 legacy 公开键不受影响。
- `MessageProtocolState` 只承诺 Map/List/String/Number/Boolean/null 组成的 JSON 数据树；未知可变 Java 对象不属于受支持的协议状态载荷。
- Gemini 新响应只写协议状态，不写 deprecated `ToolCall.thoughtSignature`；旧 AssistantMessage/ToolCall JSON 仍通过 Bean 字段恢复，并由 Gemini 请求构建器懒兼容读取。字段物理删除应等待既定兼容周期结束。
- `contentRaw`、`toolCallsRaw`、`searchResultsRaw`、`reasoningFieldName` 与 `ToolCall.thoughtSignature` 均处于兼容保留期，当前版本不物理删除。
- Citation 当前只统一稳定公共字段；供应商位置、页码、文件 ID、annotation index 等继续保留在事件 attrs/raw 或 protocol state，待有足够跨协议共性后再演进，避免过早扩张核心模型。
- 不宣称 Maven 全仓外部服务型测试全部通过；Redis、远程 Embedding 等环境依赖测试应在具备相应服务的环境执行。
