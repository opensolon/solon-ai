
solon-ai-flow  = solon-ai + solon-flow，是一个 AI 流编排框架。

有别常见的 AI 流编排工具（或低代码工具）。我们是开发框架，没有可视界面，编排风格类似 docker-compose。


##  简单示例

更多示例，参考模块的 test 目录

### chat_case1


```java
flowEngine.eval("chat_case1");
```


```yaml
id: chat_case1
layout:
  - type: "@TextInput"
    meta:
      text: "你好"
  - type: "@ChatModel"
    meta:
      systemPrompt: "你是个聊天助手"
      stream: false
      chatConfig: # "@type": "org.noear.solon.ai.chat.ChatConfig"
        provider: "ollama"
        model: "qwen2.5:1.5b"
        apiUrl: "http://127.0.0.1:11434/api/chat"
  - type: "@TextOutput"
```


### rag_case1

```java
flowEngine.eval("rag_case1");
```


```yaml

id: rag_case1
layout:
  - type: "@TextInput"
    meta:
      text: "Solon 是谁开发的？"
  - type: "@InMemoryRepository"
    meta:
      embeddingConfig: # "@type": "org.noear.solon.ai.embedding.EmbeddingConfig"
        provider: "ollama"
        model: "bge-m3"
        apiUrl: "http://127.0.0.1:11434/api/embed"
      documentSources:
        - "https://solon.noear.org/article/about?format=md"
      splitPipeline:
        - "org.noear.solon.ai.rag.splitter.RegexTextSplitter"
        - "org.noear.solon.ai.rag.splitter.TokenSizeTextSplitter"
  - type: "@ChatModel"
    meta:
      systemPrompt: "你是个知识库"
      stream: false
      chatConfig: # "@type": "org.noear.solon.ai.chat.ChatConfig"
        provider: "ollama"
        model: "qwen2.5:1.5b"
        apiUrl: "http://127.0.0.1:11434/api/chat"
  - type: "@TextOutput"
```


### tool_case1

```java
flowEngine.eval("tool_case1");
```


```yaml
id: tool_case1
layout:
  - type: "@TextInput"
    meta:
      text: "杭州今天天气怎么样？"
  - type: "@ChatModel"
    meta:
      systemPrompt: "你是个天气预报员"
      stream: false
      chatConfig: # "@type": "org.noear.solon.ai.chat.ChatConfig"
        provider: "ollama"
        model: "qwen2.5:1.5b"
        apiUrl: "http://127.0.0.1:11434/api/chat"
      toolProviders:
        - "features.ai.flow.ToolDemo"
  - type: "@TextOutput"
```


### mcp_case1


```java
flowEngine.eval("mcp_case1");
```

```yaml
id: mcp_case1
layout:
  - type: "@TextInput"
    meta:
      text: "杭州今天天气怎么样?"
  - type: "@McpTool"
    meta:
      mcpConfig:
        apiUrl: "http://127.0.0.1:8080/mcp/sse"
  - type: "@ChatModel"
    meta:
      systemPrompt: "你是个天气预报员"
      stream: false
      chatConfig: # "@type": "org.noear.solon.ai.chat.ChatConfig"
        provider: "ollama"
        model: "qwen2.5:1.5b"
        apiUrl: "http://127.0.0.1:11434/api/chat"
  - type: "@TextInput"
```