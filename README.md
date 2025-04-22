<h1 align="center" style="text-align:center;">
<img src="solon_icon.png" width="128" />
<br />
Solon-AI
</h1>
<p align="center">
	<strong>Java AI（智能体） 全场景应用开发框架（支持已知 AI 开发的各种能力）</strong>
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
    <a target="_blank" href="https://www.oracle.com/java/technologies/javase/jdk23-archive-downloads.html">
		<img src="https://img.shields.io/badge/JDK-23-green.svg" alt="jdk-23" />
	</a>
    <br />
    <a target="_blank" href='https://gitee.com/noear/solon/stargazers'>
		<img src='https://gitee.com/noear/solon/badge/star.svg' alt='gitee star'/>
	</a>
    <a target="_blank" href='https://github.com/noear/solon/stargazers'>
		<img src="https://img.shields.io/github/stars/noear/solon.svg?style=flat&logo=github" alt="github star"/>
	</a>
    <a target="_blank" href='https://gitcode.com/opensolon/solon/star'>
		<img src='https://gitcode.com/opensolon/solon/star/badge.svg' alt='gitcode star'/>
	</a>
</p>

<hr />


## 简介

面向全场景的 Java AI 应用开发框架（支持已知 AI 开发的各种能力）。是 Solon 项目的一部分。也可嵌入到 SpringBoot2、jFinal、Vert.x 等框架中使用。

其中 solon-mcp 的嵌入示例：

* https://gitee.com/opensolon/solon-ai-mcp-embedded-examples
* https://gitcode.com/opensolon/solon-ai-mcp-embedded-examples
* https://github.com/opensolon/solon-ai-mcp-embedded-examples

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
  - title: "开始"
    type: start
  - title: "文件提取"
    meta.input: "file" # 可视界面的配置（通过元信息表示）
    meta.output: "fileTxt"
    task: @FileLoaderCom
  - title: "LLM"
    meta.model: "Qwen/Qwen2.5-72B-Instruct" # 可视界面的配置（通过元信息表示）
    meta.input: "fileTxt"
    meta.messages:
      - role: system
        content: "#角色\n你是一个数据专家，删除数据的格式整理和转换\n\n#上下文\n${fileTxt}\n\n#任务\n提取csv格式的字符串"
    task: @ChatModelCom
  - title: "参数提取器"
    meta.model: "Qwen/Qwen2.5-72B-Instruct" # 可视界面的配置（通过元信息表示）
    meta.output: "csvData"
    task: @ParamExtractionCom
  - title: "执行代码"
    meta.input: "csvData"
    task: |
      import com.demo.DataUtils;
      
      String json = DataUtils.csvToJson(node.meta().get("meta.input"));  //转为 json 数据
      String echatCode = DataUtils.jsonAsEchatCode(json); //转为 echat 图表代码
      context.result = echatCode; //做为结果返回
  - title: "结束"
    type: end

# FlowEngine flowEngine = FlowEngine.newInstance();
# ...
# flowEngine.eval("demo1");
```

* MCP server（支持多端点）

```java
//组件方式构建
@McpServerEndpoint(name="mcp-case1", sseEndpoint = "/case1/sse") 
public class McpServerTool {
    @ToolMapping(description = "查询天气预报")
    public String getWeather(@ToolParam(description = "城市位置") String location) {
        return "晴，14度";
    }
}

//原生 java 方式构建
McpServerEndpointProvider serverEndpoint = McpServerEndpointProvider.builder()
        .name("mcp-case2")
        .sseEndpoint("/case2/sse")
        .build();

serverEndpoint.addTool(new MethodToolProvider(new McpServerTool()));
serverEndpoint.postStart();
```

* MCP client

```java
McpClientToolProvider clientToolProvider = McpClientToolProvider.builder()
                .apiUrl("http://localhost:8080/case1/sse")
                .build();

String rst = clientToolProvider.callToolAsText("getWeather", Map.of("location", "杭州"));
```

## Solon 项目相关代码仓库



| 代码仓库                                            | 描述                               | 
|-------------------------------------------------|----------------------------------| 
| https://gitee.com/opensolon/solon               | Solon ,主代码仓库                     | 
| https://gitee.com/opensolon/solon-examples      | Solon ,官网配套示例代码仓库                |
|                                                 |                                  |
| https://gitee.com/opensolon/solon-ai            | Solon Ai ,代码仓库                   | 
| https://gitee.com/opensolon/solon-flow          | Solon Flow ,代码仓库                 | 
| https://gitee.com/opensolon/solon-cloud         | Solon Cloud ,代码仓库                | 
| https://gitee.com/opensolon/solon-admin         | Solon Admin ,代码仓库                | 
| https://gitee.com/opensolon/solon-jakarta       | Solon Jakarta ,代码仓库（base java21） | 
| https://gitee.com/opensolon/solon-integration   | Solon Integration ,代码仓库          | 
|                                                 |                                  |
| https://gitee.com/opensolon/solon-gradle-plugin | Solon Gradle ,插件代码仓库             | 
| https://gitee.com/opensolon/solon-idea-plugin   | Solon Idea ,插件代码仓库               | 
| https://gitee.com/opensolon/solon-vscode-plugin | Solon VsCode ,插件代码仓库             | 
|                                                 |                                  |
| https://gitee.com/dromara/solon-plugins         | Solon 第三方扩展插件代码仓库                | 


