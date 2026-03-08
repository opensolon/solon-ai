# solon-ai-ui-aisdk

Solon AI 对接 [Vercel AI SDK](https://ai-sdk.dev/) 的 UI 协议适配模块（转换器，或者，是包装器）。

_目前，有官方的AI Elments组件库，是基于Shadcn的，也有Vue版本，觉得很不错，这个协议也比较成熟，适合拿来直接用，无论是否用 Vercel 的东西，无需自己再造轮子。_

## 功能
- 将 `ChatModel.prompt().stream()` 返回的 `Flux<ChatResponse>` 自动转换为
[UI Message Stream Protocol v1](https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol) 格式的 SSE 事件流，
前端可直接使用 `@ai-sdk/vue` 的 `useChat` 或 `@ai-sdk/react` 的 `useChat` 无缝对接，只需要修改下端点即可。
- 也支持将`prompt().call()`的阻塞式调用，转换为假流式，虽然场景比较罕见；
- 只用 Parts 包内相关内容，自己管理事件。

支持：文本流、深度思考(reasoning)、工具调用(tool-calls)、搜索结果引用(source-url)、
文档引用(source-document)、文件(file)、自定义数据(data-*)、元数据(metadata)。

## Maven 依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-ui-aisdk</artifactId>
</dependency>
```

## 包结构

```
org.noear.solon.ai.ui.aisdk
├── AiSdkStreamWrapper              # 核心包装器：Flux<ChatResponse> → Flux<SseEvent>
├── part/                            # 协议 Part 类（模板方法模式）
│   ├── AiSdkStreamPart            #   抽象基类
│   ├── StartPart                   #   流开始
│   ├── FinishPart                  #   流结束（含 usage）
│   ├── ErrorPart                   #   错误
│   ├── MetadataPart                #   消息元数据
│   ├── StartStepPart               #   步骤开始（多步工具调用）
│   ├── FinishStepPart              #   步骤结束（多步工具调用）
│   ├── AbortPart                   #   流中止
│   ├── FilePart                    #   文件（图片、PDF 等）
│   ├── DataPart                    #   自定义数据（抽象，data-* 类型模式）
│   ├── ToolInputStartPart          #   工具输入开始
│   ├── ToolInputDeltaPart          #   工具输入增量
│   ├── ToolInputAvailablePart      #   工具输入完成
│   ├── ToolOutputAvailablePart     #   工具输出完成
│   ├── text/                        #   Text Parts
│   │   ├── TextStartPart          #     文本开始
│   │   ├── TextDeltaPart          #     文本增量
│   │   └── TextEndPart            #     文本结束
│   ├── reasoning/                   #   Reasoning Parts
│   │   ├── ReasoningStartPart     #     推理开始
│   │   ├── ReasoningDeltaPart     #     推理增量
│   │   └── ReasoningEndPart       #     推理结束
│   └── source/                      #   Source Parts
│       ├── SourceUrlPart           #     URL 来源引用
│       └── SourceDocumentPart      #     文档来源引用
└── util/                            # 工具类
    └── AiSdkIdGenerator            #   ID 生成策略接口（策略模式）
```

---

## 事件流时序

`AiSdkStreamWrapper` 自动按以下顺序组织 SSE 事件。
其中，有一个seesion id和每个环节的id非常重要。
有些时候一些复杂的agent是多路推理、多路调用并行，本身就是乱序的，如果内部不加以区分，那么就会错乱。
### 关于ID的说明

- Session ID 代表当前会话ID，这个一般就是会话的主键，标识符之类，前端可以动态生成（新会话），也可以从后端拿；
- Part ID，也就是比如工具调用的ID、推理ID等，这个一般是后端针对同一个动作内部的不同事件（Delta、Start、End），自己生成的，没有什么特殊规则，但一定要确保不能重复（即：如果涉及到2个以上同类型的Part，比如有2个工具调用，那每次工具调用内部需要用同一个ID，不同工具之间要用不同的ID）

```
┌─ 连接建立 ────────────────────────────────────────────────┐
│                                                            │
│  ① StartPart           {"type":"start","messageId":"…"}    │
│  ② MetadataPart        (可选) {"type":"message-metadata"}  │
│                                                            │
│  ┌─ Step 循环（每次 LLM 调用为一个 Step）──────────────┐   │
│  │                                                      │   │
│  │  ③ StartStepPart     {"type":"start-step"}           │   │
│  │                                                      │   │
│  │  ┌─ 推理阶段（深度思考模型，可选）───────────────┐   │   │
│  │  │  ④ ReasoningStartPart   推理开始              │   │   │
│  │  │  ⑤ ReasoningDeltaPart   推理增量 × N          │   │   │
│  │  │  ⑥ ReasoningEndPart     推理结束              │   │   │
│  │  └────────────────────────────────────────────────┘   │   │
│  │                                                      │   │
│  │  ┌─ 工具调用阶段（可选，可多个）─────────────────┐   │   │
│  │  │  ⑦ ToolInputStartPart     工具输入开始       │   │   │
│  │  │  ⑧ ToolInputDeltaPart     工具输入增量       │   │   │
│  │  │  ⑨ ToolInputAvailablePart 工具输入完成       │   │   │
│  │  │  ⑩ ToolOutputAvailablePart 工具输出          │   │   │
│  │  └────────────────────────────────────────────────┘   │   │
│  │                                                      │   │
│  │  ┌─ 引用阶段（搜索结果，可选）───────────────────┐   │   │
│  │  │  ⑪ SourceUrlPart       URL 来源 × N          │   │   │
│  │  │  ⑫ SourceDocumentPart  文档来源 × N          │   │   │
│  │  └────────────────────────────────────────────────┘   │   │
│  │                                                      │   │
│  │  ┌─ 正文阶段 ────────────────────────────────────┐   │   │
│  │  │  ⑬ TextStartPart     文本开始                │   │   │
│  │  │  ⑭ TextDeltaPart     文本增量 × N            │   │   │
│  │  │  ⑮ TextEndPart       文本结束                │   │   │
│  │  └────────────────────────────────────────────────┘   │   │
│  │                                                      │   │
│  │  ┌─ 附件阶段（可选）─────────────────────────────┐   │   │
│  │  │  ⑯ FilePart          文件 × N                │   │   │
│  │  │  ⑰ DataPart          自定义数据 × N          │   │   │
│  │  └────────────────────────────────────────────────┘   │   │
│  │                                                      │   │
│  │  ⑱ FinishStepPart      {"type":"finish-step"}        │   │
│  │                                                      │   │
│  └──── 如有工具调用 → 执行工具 → 下一个 Step ─────────┘   │
│                                                            │
│  ⑲ FinishPart    {"type":"finish","finishReason":"stop"}   │
│  ⑳ [DONE]         流终止标记                               │
│                                                            │
└─ 连接关闭 ────────────────────────────────────────────────┘

异常时序：ErrorPart → FinishPart → [DONE]
中止时序：AbortPart → [DONE]（用户主动停止）
```

> **Step 说明**：单轮对话只有 1 个 Step；后端多步工具调用（tool-calls → re-prompt）
> 场景下会有多个 Step，前端 `useChat` 的 steps 机制依赖 `start-step` / `finish-step`
> Part 正确拼接多轮 assistant 消息。
> 
> 其实比较简单的Agent，一般是不会有多路任务的，这种一般是聊天助手居多，那么可以完全不用Step事件，实测也正常。


### Part 速查表

| 序号 | Part 类 | type 值 | 关键字段 | 说明 |
|:---:|--------|--------|---------|------|
| 1 | `StartPart` | `start` | messageId | 每次对话流唯一 |
| 2 | `MetadataPart` | `message-metadata` | messageMetadata | 自定义数据（如 sessionId） |
| 3 | `StartStepPart` | `start-step` | — | 步骤开始（每次 LLM 调用） |
| 4 | `ReasoningStartPart` | `reasoning-start` | id | 推理块开始 |
| 5 | `ReasoningDeltaPart` | `reasoning-delta` | id, delta | 推理内容增量 |
| 6 | `ReasoningEndPart` | `reasoning-end` | id | 推理块结束 |
| 7 | `ToolInputStartPart` | `tool-input-start` | toolCallId, toolName | 工具调用开始 |
| 8 | `ToolInputDeltaPart` | `tool-input-delta` | toolCallId, inputTextDelta | 工具参数增量 |
| 9 | `ToolInputAvailablePart` | `tool-input-available` | toolCallId, toolName, input | 工具参数完整 |
| 10 | `ToolOutputAvailablePart` | `tool-output-available` | toolCallId, output | 工具返回结果 |
| 11 | `SourceUrlPart` | `source-url` | sourceId, url, title | 搜索结果引用 |
| 12 | `SourceDocumentPart` | `source-document` | sourceId, mediaType, title | 文档来源引用 |
| 13 | `TextStartPart` | `text-start` | id | 正文块开始 |
| 14 | `TextDeltaPart` | `text-delta` | id, delta | 正文内容增量 |
| 15 | `TextEndPart` | `text-end` | id | 正文块结束 |
| 16 | `FilePart` | `file` | url, mediaType | 文件（图片、PDF 等） |
| 17 | `DataPart` | `data-*` | data | 自定义数据（抽象类） |
| 18 | `FinishStepPart` | `finish-step` | — | 步骤结束（多步工具调用必需） |
| 19 | `FinishPart` | `finish` | finishReason, usage | 流结束，含 token 统计 |
| 20 | `ErrorPart` | `error` | errorText | 错误信息 |
| 21 | `AbortPart` | `abort` | reason (可选) | 流被主动中止 |

---

## 后端示例

### 示例 1：AI SDK 协议流（推荐）

使用 `AiSdkStreamWrapper` 一行代码将 ChatModel 流转换为完整的 AI SDK 协议格式，
自动处理 reasoning/text/toolCall 状态跟踪和 start/finish 生命周期。

```java
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.ui.aisdk.AiSdkStreamWrapper;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.web.sse.SseEvent;
import reactor.core.publisher.Flux;

@Controller
public class AiChatController {
    @Inject
    ChatModel chatModel;

    // 创建 wrapper 实例（可复用）
    private final AiSdkStreamWrapper wrapper = AiSdkStreamWrapper.of();

    /**
     * AI SDK 协议流式端点
     * 兼容 @ai-sdk/vue useChat 和 @ai-sdk/react useChat
     */
    @Produces(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE)
    @Mapping("/ai/chat/stream")
    public Flux<SseEvent> stream(String prompt, Context ctx) {
        // 必须：设置 AI SDK 协议版本头
        ctx.headerSet("x-vercel-ai-ui-message-stream", "v1");

        return wrapper.toAiSdkStream(
                chatModel.prompt(prompt).stream()
        );
    }
}
```

### 示例 2：阻塞式调用转 AI SDK 协议流

适用于 `chatModel.prompt(prompt).call()` 的阻塞式调用场景，
将完整的 `ChatResponse` 一次性转换为 AI SDK 协议格式的 SSE 事件流。

```java
@Controller
public class AiChatController {
    @Inject
    ChatModel chatModel;

    private final AiSdkStreamWrapper wrapper = AiSdkStreamWrapper.of();

    @Produces(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE)
    @Mapping("/ai/chat/call")
    public Flux<SseEvent> call(String prompt, Context ctx) {
        ctx.headerSet("x-vercel-ai-ui-message-stream", "v1");

        // 阻塞式调用，结果自动转换为 AI SDK 协议事件流
        ChatResponse response = chatModel.prompt(prompt).call();
        return wrapper.toAiSdkStream(response);
    }
}
```

### 示例 3：自定义 ID 策略（如雪花算法）

```java
import org.noear.solon.ai.ui.aisdk.AiSdkStreamWrapper;
import org.noear.solon.ai.ui.aisdk.util.AiSdkIdGenerator;

// 使用自定义 ID 生成策略
AiSdkIdGenerator snowflakeGenerator = prefix -> prefix + snowflake.nextId();
AiSdkStreamWrapper wrapper = AiSdkStreamWrapper.of(snowflakeGenerator);

// ID 生成器提供常用前缀的快捷方法
snowflakeGenerator.ofMessage();   // → "msg_1234567890"
snowflakeGenerator.ofText();      // → "txt_1234567890"
snowflakeGenerator.ofReasoning(); // → "rsn_1234567890"
snowflakeGenerator.ofToolCall();  // → "call_1234567890"
snowflakeGenerator.ofSource();    // → "src_1234567890"
```

### 示例 4：AI SDK 协议流 + 会话记忆 + 元数据

```java
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.ui.aisdk.AiSdkStreamWrapper;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.web.sse.SseEvent;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class AiChatController {
    @Inject
    ChatModel chatModel;

    private final AiSdkStreamWrapper wrapper = AiSdkStreamWrapper.of();
    private final Map<String, ChatSession> sessionMap = new ConcurrentHashMap<>();

    @Produces(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE)
    @Mapping("/ai/chat/stream")
    public Flux<SseEvent> stream(@Header("sessionId") String sessionId,
                                 String prompt, Context ctx) {
        ctx.headerSet("x-vercel-ai-ui-message-stream", "v1");

        ChatSession session = sessionMap.computeIfAbsent(sessionId,
                k -> InMemoryChatSession.builder().sessionId(k).build());

        // 通过 metadata 将 sessionId 传给前端
        Map<String, Object> metadata = Map.of("sessionId", sessionId);

        return wrapper.toAiSdkStream(
                chatModel.prompt(prompt).session(session).stream(),
                metadata
        );
    }
}
```

### 示例 5：自定义 Data Part（自定义数据流）

```java
import org.noear.solon.ai.ui.aisdk.part.DataPart;

// 方式一：工厂方法快速创建
DataPart weatherPart = DataPart.of("weather", Map.of(
        "location", "SF",
        "temperature", 100
));
// → {"type":"data-weather","data":{"location":"SF","temperature":100}}

// 方式二：子类化（适用于固定类型的自定义数据）
public class ProgressDataPart extends DataPart {
    private final int percent;
    private final String stage;

    public ProgressDataPart(int percent, String stage) {
        this.percent = percent;
        this.stage = stage;
    }

    @Override
    public String getDataType() {
        return "progress";
    }

    @Override
    public Object getData() {
        return Map.of("percent", percent, "stage", stage);
    }
}
// → {"type":"data-progress","data":{"percent":50,"stage":"analyzing"}}
```

### 示例 6：普通 SSE 流（不使用 AI SDK 协议）

如果前端不使用 Vercel AI SDK，可以直接返回普通 SSE 流：

```java
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.annotation.*;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.web.sse.SseEvent;
import reactor.core.publisher.Flux;

@Controller
public class AiChatController {
    @Inject
    ChatModel chatModel;

    @Produces(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE)
    @Mapping("/ai/chat/stream")
    public Flux<SseEvent> stream(String prompt) {
        return chatModel.prompt(prompt).stream()
                .map(resp -> resp.getMessage())
                .map(msg -> new SseEvent().data(msg.getContent()))
                .doOnError(err -> {
                    log.error("{}", err);
                });
    }
}
```

---

## 前端示例

### 方案 A：Vue 3 + @ai-sdk/vue（推荐）

安装依赖：

```bash
npm install ai @ai-sdk/vue
```

```vue
<script setup lang="ts">
import { ref, computed } from 'vue'
import { useChat } from '@ai-sdk/vue'

const { messages, input, handleSubmit, status, error } = useChat({
  api: '/ai/chat/stream',
  // 自定义请求体：将 input 映射为后端 prompt 参数
  fetch: async (url, options) => {
    const body = JSON.parse(options?.body as string || '{}')
    const lastMsg = body.messages?.findLast((m: any) => m.role === 'user')
    return fetch(url, {
      ...options,
      body: JSON.stringify({
        prompt: lastMsg?.content || '',
        sessionId: 'demo-session'
      })
    })
  }
})

const isStreaming = computed(() =>
  status.value === 'streaming' || status.value === 'submitted'
)
</script>

<template>
  <div class="chat-container">
    <!-- 消息列表 -->
    <div class="messages">
      <div v-for="msg in messages" :key="msg.id"
           :class="msg.role === 'user' ? 'user-msg' : 'ai-msg'">
        <template v-for="part in msg.parts" :key="part.type">
          <!-- 推理/思考内容 -->
          <details v-if="part.type === 'reasoning'" class="reasoning">
            <summary>思考过程</summary>
            <p>{{ part.reasoning }}</p>
          </details>
          <!-- 正文内容 -->
          <p v-else-if="part.type === 'text'">{{ part.text }}</p>
          <!-- 来源引用 -->
          <a v-else-if="part.type === 'source-url'"
             :href="part.url" target="_blank">{{ part.title }}</a>
        </template>
      </div>
    </div>

    <!-- 错误提示 -->
    <div v-if="error" class="error">{{ error.message }}</div>

    <!-- 输入框 -->
    <form @submit.prevent="handleSubmit">
      <input v-model="input" placeholder="输入消息..." :disabled="isStreaming" />
      <button type="submit" :disabled="isStreaming">
        {{ isStreaming ? '生成中...' : '发送' }}
      </button>
    </form>
  </div>
</template>
```

### 方案 B：Vanilla JavaScript（原生 fetch）

无需任何 AI SDK 前端依赖，直接解析 SSE 事件流：

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <title>Solon AI Chat</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 600px; margin: 2rem auto; }
    #messages { border: 1px solid #ddd; border-radius: 8px; padding: 1rem; min-height: 300px; margin-bottom: 1rem; }
    .reasoning { color: #888; font-style: italic; }
    .error { color: red; }
    form { display: flex; gap: 0.5rem; }
    input { flex: 1; padding: 0.5rem; border: 1px solid #ccc; border-radius: 4px; }
    button { padding: 0.5rem 1rem; border: none; border-radius: 4px; background: #0070f3; color: #fff; cursor: pointer; }
  </style>
</head>
<body>
  <div id="messages"></div>
  <form id="chatForm">
    <input id="input" placeholder="输入消息..." autocomplete="off" />
    <button type="submit">发送</button>
  </form>

  <script>
    const messagesDiv = document.getElementById('messages');
    const form = document.getElementById('chatForm');
    const input = document.getElementById('input');

    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const prompt = input.value.trim();
      if (!prompt) return;

      // 显示用户消息
      messagesDiv.innerHTML += `<p><b>你：</b>${escapeHtml(prompt)}</p>`;
      input.value = '';

      // 创建 AI 消息容器
      const aiMsg = document.createElement('p');
      aiMsg.innerHTML = '<b>AI：</b>';
      const textSpan = document.createElement('span');
      aiMsg.appendChild(textSpan);
      messagesDiv.appendChild(aiMsg);

      let reasoningSpan = null;

      try {
        const resp = await fetch('/ai/chat/stream', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ prompt })
        });

        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';

          for (const line of lines) {
            if (!line.startsWith('data:')) continue;
            const data = line.slice(5).trim();
            if (data === '[DONE]') continue;

            try {
              const event = JSON.parse(data);

              switch (event.type) {
                case 'text-delta':
                  textSpan.textContent += event.delta;
                  break;
                case 'reasoning-start':
                  reasoningSpan = document.createElement('span');
                  reasoningSpan.className = 'reasoning';
                  reasoningSpan.textContent = '[思考] ';
                  aiMsg.insertBefore(reasoningSpan, textSpan);
                  break;
                case 'reasoning-delta':
                  if (reasoningSpan) reasoningSpan.textContent += event.delta;
                  break;
                case 'reasoning-end':
                  if (reasoningSpan) reasoningSpan.textContent += '\n';
                  reasoningSpan = null;
                  break;
                case 'error':
                  textSpan.innerHTML = `<span class="error">${escapeHtml(event.errorText)}</span>`;
                  break;
              }
            } catch { /* 跳过非 JSON 行 */ }
          }
        }
      } catch (err) {
        textSpan.innerHTML = `<span class="error">请求失败: ${escapeHtml(err.message)}</span>`;
      }

      messagesDiv.scrollTop = messagesDiv.scrollHeight;
    });

    function escapeHtml(text) {
      const div = document.createElement('div');
      div.textContent = text;
      return div.innerHTML;
    }
  </script>
</body>
</html>
```

---

## 设计模式说明

本模块使用了以下设计模式：

| 模式 | 应用位置 | 说明 |
|------|---------|------|
| **模板方法** | `AiSdkStreamPart` 抽象基类 | `toJson()` 固定序列化骨架，子类只需实现 `getType()` 和 `writeFields()` |
| **策略模式** | `AiSdkIdGenerator` 接口 | ID 生成策略可替换（默认 UUID，可换雪花算法等），含常用前缀快捷方法 |
| **工厂方法** | `AiSdkStreamWrapper.of()` / `DataPart.of()` | 统一创建入口，简洁易用 |

### 单独使用 Part 类

如需手动构建 Part（如在自定义流处理中），可直接使用具体 Part 类：

```java
import org.noear.solon.ai.ui.aisdk.part.text.*;
import org.noear.solon.ai.ui.aisdk.part.reasoning.*;
import org.noear.solon.ai.ui.aisdk.part.*;

// 构建 Part 并序列化
String json = new TextDeltaPart("txt_001", "Hello").toJson();
// → {"type":"text-delta","id":"txt_001","delta":"Hello"}

String json2 = new ReasoningStartPart("rsn_001").toJson();
// → {"type":"reasoning-start","id":"rsn_001"}

// 使用 ID 生成器创建带前缀的 ID
AiSdkIdGenerator gen = AiSdkIdGenerator.DEFAULT;
String json3 = new TextStartPart(gen.ofText()).toJson();
// → {"type":"text-start","id":"txt_a1b2c3d4e5f6"}

// 自定义数据 Part
String json4 = DataPart.of("weather", Map.of("temp", 72)).toJson();
// → {"type":"data-weather","data":{"temp":72}}
```
