
solon-ai-flow  = solon-ai + solon-flow，是一个 AI 流编排框架。

* 旨在实现一种 docker-compose 风格的 AI-Flow 
* 算是一种新的思路（或参考）

有别常见的 AI 流编排工具（或低代码工具）。我们是开发框架，暂时不考虑可视界面。


## 设计概要

* 把 AI 能力，封装为 solon-flow 的 TaskComponent 组件。分为：输入输出组件，属性组件。然后用 solon-flow 编排串起来
  * 有输入输出需求的组件，为输入输出组件
  * 有属性添加或获取需求的组件，属性组件
* 所有相关数据或属性通过 FlowContext 共享和中转

比如：

* ChatInputCom，会接收请求数据，并转为输出字段（FlowContext 里的约定字段）
* McpToolCom，会根据元信息配置，并添加属性（FlowContext 里的约定字段）
* ChatModelCom，会尝试从 FlowContext 获取“上个节点”的输出数据；尝试获取“上个节点”添加的属性。

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