<h1 align="center" style="text-align:center;">
<img src="solon_icon.png" width="128" />
<br />
Solon-AI
</h1>
<p align="center">
	<strong>Java AI & MCP 应用开发框架（支持已知 AI 开发的各种能力）</strong>
    <br/>
    <strong>【基于 Solon 应用开发框架构建】</strong>
</p>
<p align="center">
	<a href="https://solon.noear.org/article/learn-solon-ai">https://solon.noear.org/article/learn-solon-ai</a>
</p>

<p align="center">
    <a target="_blank" href="https://central.sonatype.com/search?q=org.noear%3Asolon-parent">
        <img src="https://img.shields.io/maven-central/v/org.noear/solon.svg?label=Maven%20Central" alt="Maven" />
    </a>
    <a target="_blank" href="LICENSE">
		<img src="https://img.shields.io/:License-Apache2-blue.svg" alt="Apache 2" />
	</a>
    <a target="_blank" href="https://www.oracle.com/java/technologies/javase/javase-jdk8-downloads.html">
		<img src="https://img.shields.io/badge/JDK-8-green.svg" alt="jdk-8" />
	</a>
    <a target="_blank" href="https://www.oracle.com/java/technologies/javase/jdk11-archive-downloads.html">
		<img src="https://img.shields.io/badge/JDK-11-green.svg" alt="jdk-11" />
	</a>
    <a target="_blank" href="https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html">
		<img src="https://img.shields.io/badge/JDK-17-green.svg" alt="jdk-17" />
	</a>
    <a target="_blank" href="https://www.oracle.com/java/technologies/javase/jdk21-archive-downloads.html">
		<img src="https://img.shields.io/badge/JDK-21-green.svg" alt="jdk-21" />
	</a>
    <a target="_blank" href="https://www.oracle.com/java/technologies/javase/jdk24-archive-downloads.html">
		<img src="https://img.shields.io/badge/JDK-24-green.svg" alt="jdk-24" />
	</a>
    <br />
    <a target="_blank" href='https://gitee.com/opensolon/solon-ai/stargazers'>
		<img src='https://gitee.com/opensolon/solon-ai/badge/star.svg' alt='gitee star'/>
	</a>
    <a target="_blank" href='https://github.com/opensolon/solon-ai/stargazers'>
		<img src="https://img.shields.io/github/stars/opensolon/solon-ai.svg?style=flat&logo=github" alt="github star"/>
	</a>
    <a target="_blank" href='https://gitcode.com/opensolon/solon-ai/stargazers'>
		<img src='https://gitcode.com/opensolon/solon-ai/star/badge.svg' alt='gitcode star'/>
	</a>
</p>

<hr />


## 简介

面向全场景的 Java AI 应用开发框架（支持已知 AI 开发的各种能力）。是 Solon 项目的一部分。也可嵌入到 SpringBoot、jFinal、Vert.x 等框架中使用。

其中 solon-ai(& mcp) 的嵌入示例：

* https://gitee.com/solonlab/solon-ai-mcp-embedded-examples
* https://gitcode.com/solonlab/solon-ai-mcp-embedded-examples
* https://github.com/solonlab/solon-ai-mcp-embedded-examples

## 主要接口体验示例

* ChatModel（通用接口，基于方言适配实现不同提供商与模型的扩展）

```java
ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .provider("ollama") //需要指定供应商，用于识别接口风格（也称为方言）
                .model("qwen2.5:1.5b")
                .build();
//同步调用，并打印响应消息
System.out.println(chatModel.prompt("hello").call().getMessage());

//响应式调用
chatModel.prompt("hello").stream(); //Publisher<ChatResponse>
```

* Function Calling（或者 Tool Calling）

```java
//可以添加默认工具（即所有请求有产），或请求时工具
chatModel.prompt("今天杭州的天气情况？")
    .options(op->op.toolsAdd(new FunctionTools()))
    .call();
```

* Vision（多媒体感知）

```java
chatModel.prompt(ChatMessage.ofUser("这图里有方块吗？", Image.ofUrl(imageUrl)))
    .call();
```

* RAG（EmbeddingModel，Repository，DocumentLoader，RerankingModel）

```java
//构建知识库
EmbeddingModel embeddingModel = EmbeddingModel.of(apiUrl).apiKey(apiKey).provider(provider).model(model).batchSize(10).build();
RerankingModel rerankingModel = RerankingModel.of(apiUrl).apiKey(apiKey).provider(provider).model(model).build();
InMemoryRepository repository = new InMemoryRepository(TestUtils.getEmbeddingModel()); //3.初始化知识库

repository.insert(new PdfLoader(pdfUri).load());

//检索
List<Document> docs = repository.search(query);

//如果有需要，可以重排一下
docs = rerankingModel.rerank(query, docs);

//提示语增强是
ChatMessage message = ChatMessage.augment(query, docs);

//调用大模型
chatModel.prompt(message) 
    .call();
```

* Ai Flow（模拟实现 Dify 的流程应用）

```yaml
id: demo1
layout:
  - type: "start"
  - task: "@VarInput"
    meta:
      message: "Solon 是谁开发的？"
  - task: "@EmbeddingModel"
    meta:
      embeddingConfig: # "@type": "org.noear.solon.ai.embedding.EmbeddingConfig"
        provider: "ollama"
        model: "bge-m3"
        apiUrl: "http://127.0.0.1:11434/api/embed"
  - task: "@InMemoryRepository"
    meta:
      documentSources:
        - "https://solon.noear.org/article/about?format=md"
      splitPipeline:
        - "org.noear.solon.ai.rag.splitter.RegexTextSplitter"
        - "org.noear.solon.ai.rag.splitter.TokenSizeTextSplitter"
  - task: "@ChatModel"
    meta:
      systemPrompt: "你是个知识库"
      stream: false
      chatConfig: # "@type": "org.noear.solon.ai.chat.ChatConfig"
        provider: "ollama"
        model: "qwen2.5:1.5b"
        apiUrl: "http://127.0.0.1:11434/api/chat"
  - task: "@ConsoleOutput"

# FlowEngine flowEngine = FlowEngine.newInstance();
# ...
# flowEngine.eval("demo1");
```

* MCP server（支持多端点）

```java
//组件方式构建
@McpServerEndpoint(name="mcp-case1", sseEndpoint = "/case1/sse") 
public class McpServer {
    @ToolMapping(description = "查询天气预报")
    public String getWeather(@Param(description = "城市位置") String location) {
        return "晴，14度";
    }

    @ResourceMapping(uri = "config://app-version", description = "获取应用版本号", mimeType = "text/config")
    public String getAppVersion() {
        return "v3.2.0";
    }


    @PromptMapping(description = "生成关于某个主题的提问")
    public Collection<ChatMessage> askQuestion(@Param(description = "主题") String topic) {
        return Arrays.asList(
                ChatMessage.ofUser("请解释一下'" + topic + "'的概念？")
        );
    }
}

//原生 java 方式构建
McpServerEndpointProvider serverEndpoint = McpServerEndpointProvider.builder()
        .name("mcp-case2")
        .sseEndpoint("/case2/sse")
        .build();

serverEndpoint.addTool(new MethodToolProvider(new McpServerTools())); //添加工具
serverEndpoint.addResource(new MethodResourceProvider(new McpServerResources())); //添加资源
serverEndpoint.addPrompt(new MethodPromptProvider(new McpServerPrompts())); //添加提示语
serverEndpoint.postStart();
```

* MCP client

```java
McpClientToolProvider clientToolProvider = McpClientToolProvider.builder()
                .apiUrl("http://localhost:8080/case1/sse")
                .build();

String rst = clientToolProvider.callToolAsText("getWeather", Map.of("location", "杭州"))
                .getContent();
```


* MCP Proxy （示例，把 gitee mcp stdio 转为 sse 服务）

配置参考自：https://gitee.com/oschina/mcp-gitee

```java
@McpServerEndpoint(name = "mcp-case3", sseEndpoint="/case3/sse")
public class McpStdioToSseServerDemo implements ToolProvider {
    McpClientProvider stdioToolProvider = McpClientProvider.builder()
            .channel(McpChannel.STDIO) //表示使用 stdio
            .command("npx")
            .args("-y", "@gitee/mcp-gitee@latest")
            .addEnvVar("GITEE_API_BASE", "https://gitee.com/api/v5")
            .addEnvVar("GITEE_ACCESS_TOKEN", "<your personal access token>")
            .build();

    @Override
    public Collection<FunctionTool> getTools() {
        return stdioToolProvider.getTools();
    }
}
```

## Solon 项目相关代码仓库



| 代码仓库                                                             | 描述                               | 
|------------------------------------------------------------------|----------------------------------| 
| [/opensolon/solon](../../../../opensolon/solon)                             | Solon ,主代码仓库                     | 
| [/opensolon/solon-examples](../../../../opensolon/solon-examples)           | Solon ,官网配套示例代码仓库                |
|                                                                  |                                  |
| [/opensolon/solon-expression](../../../../opensolon/solon-expression)                   | Solon Expression ,代码仓库           | 
| [/opensolon/solon-flow](../../../../opensolon/solon-flow)                   | Solon Flow ,代码仓库                 | 
| [/opensolon/solon-ai](../../../../opensolon/solon-ai)                       | Solon Ai ,代码仓库                   | 
| [/opensolon/solon-cloud](../../../../opensolon/solon-cloud)                 | Solon Cloud ,代码仓库                | 
| [/opensolon/solon-admin](../../../../opensolon/solon-admin)                 | Solon Admin ,代码仓库                | 
| [/opensolon/solon-jakarta](../../../../opensolon/solon-jakarta)             | Solon Jakarta ,代码仓库（base java21） | 
| [/opensolon/solon-integration](../../../../opensolon/solon-integration)     | Solon Integration ,代码仓库          | 
|                                                                  |                                  |
| [/opensolon/solon-gradle-plugin](../../../../opensolon/solon-gradle-plugin) | Solon Gradle ,插件代码仓库             | 
| [/opensolon/solon-idea-plugin](../../../../opensolon/solon-idea-plugin)     | Solon Idea ,插件代码仓库               | 
| [/opensolon/solon-vscode-plugin](../../../../opensolon/solon-vscode-plugin) | Solon VsCode ,插件代码仓库             | 
