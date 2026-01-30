<h1 align="center" style="text-align:center;">
<img src="solon_icon.png" width="128" />
<br />
Solon-AI
</h1>
<p align="center">
	<strong>Java LLM(tool, skill) & RAG & MCP & Agent(ReAct, Team) 应用开发框架</strong>
    <br/>
    <strong>克制、高效、开放</strong>
    <br/>
    <strong>与 LangChain、LangGraph 是同类型的开发框架</strong>
</p>
<p align="center">
	<a href="https://solon.noear.org/article/learn-solon-ai">https://solon.noear.org/article/learn-solon-ai</a>
</p>

<p align="center">
    <a href="https://deepwiki.com/opensolon/solon-ai"><img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki"></a>
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
    <a target="_blank" href="https://www.oracle.com/java/technologies/downloads/">
		<img src="https://img.shields.io/badge/JDK-25-green.svg" alt="jdk-25" />
	</a>
    <br />
    <a target="_blank" href='https://gitee.com/opensolon/solon-ai/stargazers'>
        <img src='https://gitee.com/opensolon/solon-ai/badge/star.svg?theme=gvp' alt='gitee star'/>
	</a>
    <a target="_blank" href='https://github.com/opensolon/solon-ai/stargazers'>
		<img src="https://img.shields.io/github/stars/opensolon/solon-ai.svg?style=flat&logo=github" alt="github star"/>
	</a>
    <a target="_blank" href='https://gitcode.com/opensolon/solon-ai/stargazers'>
		<img src='https://gitcode.com/opensolon/solon-ai/star/badge.svg' alt='gitcode star'/>
	</a>
</p>


##### 语言： 中文 | [English](README_EN.md) 

<hr />

## 简介

Solon AI 是 Solon 项目核心子项目之一。它是一个全场景的 Java AI 开发框架，旨在将 LLM 大模型、RAG 知识库、MCP 协议以及 Agent 协作编排进行深度整合。

* 全场景支持：完美契合 Solon 生态，亦可无缝嵌入 SpringBoot、Vert.X、Quarkus 等框架。
* 多模型方言：采用 ChatModel 统一接口，通过方言适配模型差异（OpenAI, Gemini, Claude, Ollama, DeepSeek, Dashscope 等）。
* 图驱动编排：支持将 Agent 推理转化为可观测、可治理的计算流图。


其中 solon-ai 的嵌入（包括第三方框架）示例：

* https://gitee.com/solonlab/solon-ai-mcp-embedded-examples
* https://gitcode.com/solonlab/solon-ai-mcp-embedded-examples
* https://github.com/solonlab/solon-ai-mcp-embedded-examples


## 能用来开发什么应用？

* 通用自主智能体应用（比如：Manus、OpenOperator）
* 智能助理与 RAG 知识库应用（比如：Dify、Coze）
* 多 Agent 协作的任务编排应用（比如：AutoGPT、MetaGPT）
* 业务驱动的受控流程审批应用（比如：智能版钉钉审批流、SAP 智能模块）
* 结构化数据处理与 ETL 应用（比如：Instabase、Unstructured.io）
* 实时数据智能看板应用（比如：Text-to-SQL 类应用）
* 自动化测试与质量保障应用（比如：GitHub Copilot Workspace）
* 低代码/可视化 AI 工作流平台（比如：LangFlow、Flowise）
* 等等...


## 核心模块体验

* ChatModel（通用大语言模型 LLM 调用接口）

支持同步、流式（Reactive）调用，内置方言适配，工具（Tool），技能（Skill），会话记忆（ChatSession）等能力。

```java
ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .provider("ollama") //需要指定供应商，用于识别接口风格（也称为方言）
                .model("qwen2.5:1.5b")
                .defaultSkillAdd(new ToolGatewaySkill())
                .build();

//同步调用，并打印响应消息
AssistantMessage result = ChatchatModel.prompt("今天杭州的天气情况？")
         .options(op->op.toolAdd(new WeatherTools())) //添加工具
         .call()
         .getMessage();
System.out.println(result);

//响应式调用
chatModel.prompt("hello").stream(); //Publisher<ChatResponse>
```

* Skills（Solon AI Skills 技能）


```java
Skill skill = new SkillDesc("order_expert")
        .description("订单助手")
        // 动态准入：只有提到“订单”时才激活
        .isSupported(prompt -> prompt.getUserMessageContent().contains("订单"))
        // 动态指令：根据用户是否是 VIP 注入不同 SOP
        .instruction(prompt -> {
            if ("VIP".equals(prompt.getMeta("user_level"))) {
                return "这是尊贵的 VIP 客户，请优先调用 fast_track_tool。";
            }
            return "按常规流程处理订单查询。";
        })
        .toolAdd(new OrderTools());

chatModel.prompt("我昨天的订单到哪了？")
         .options(o->o.skillAdd(skill))
         .call();
```


* RAG（知识库）

提供从加载（DocumentLoader）、切分（DocumentSplitter）、向量化（EmbeddingModel）到检索重排（RerankingModel）的全链路支持。

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
ChatMessage message = ChatMessage.ofUserAugment(query, docs);

//调用大模型
chatModel.prompt(message) 
    .call();
```


* MCP (Model Context Protocol)

深度集成 MCP 协议（MCP_2025_06_18），支持跨平台的工具、资源与提示语共享。


```java
//服务端
@McpServerEndpoint(channel = McpChannel.STREAMABLE, mcpEndpoint = "/mcp") 
public class MyMcpServer {
    @ToolMapping(description = "查询天气")
    public String getWeather(@Param(description = "城市") String location) {
        return "晴，25度";
    }
}

//客户端
McpClientProvider clientProvider = McpClientProvider.builder()
        .channel(McpChannel.STREAMABLE)
        .url("http://localhost:8080/mcp")
        .build();
```


* Agent (基于计算流图的智能体体验)

Solon AI Agent 将推理逻辑转化为图驱动的协作流，支持 ReAct 自省推理和多智能体 Team 协作。


```java
//自省智能体：
ReActAgent agent = ReActAgent.of(chatModel) // 或者用 SimpleAgent.of(chatModel)
    .name("weather_expert")
    .description("查询天气并提供建议")
    .defaultToolAdd(weatherTool) // 注入 MCP 或本地工具
    .build();

agent.prompt("今天北京适合穿什么？").call(); // 自动完成：思考 -> 调用工具 -> 观察 -> 总结

// 组建团队智能体：通过协议（Protocol）自动编排成员角色
TeamAgent team = TeamAgent.of(chatModel)
    .name("marketing_team")
    .protocol(TeamProtocols.HIERARCHICAL) // 层级式协作（6种预置协议）
    .agentAdd(copywriterAgent) // 文案专家
    .agentAdd(illustratorAgent) // 视觉专家
    .build();

team.prompt("策划一个深海矿泉水的推广方案").call(); // Supervisor 自动拆解任务并分发给对应专家    .defaultToolAdd(weatherTool) // 注入 MCP 或本地工具
```



* Ai Flow（流程编排体验）

模拟 Dify 的低代码流式应用，将 RAG、提示词增强、模型调用等环节 YAML 化编排。

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

## Solon 项目相关代码仓库



| 代码仓库                                                                        | 描述                               | 
|-----------------------------------------------------------------------------|----------------------------------| 
| [/opensolon/solon](../../../../opensolon/solon)                             | Solon ,主代码仓库                     | 
| [/opensolon/solon-examples](../../../../opensolon/solon-examples)           | Solon ,官网配套示例代码仓库                |
|                                                                             |                                  |
| [/opensolon/solon-expression](../../../../opensolon/solon-expression)       | Solon Expression ,代码仓库           | 
| [/opensolon/solon-flow](../../../../opensolon/solon-flow)                   | Solon Flow ,代码仓库                 | 
| [/opensolon/solon-ai](../../../../opensolon/solon-ai)                       | Solon Ai ,代码仓库                   | 
| [/opensolon/solon-cloud](../../../../opensolon/solon-cloud)                 | Solon Cloud ,代码仓库                | 
| [/opensolon/solon-admin](../../../../opensolon/solon-admin)                 | Solon Admin ,代码仓库                | 
| [/opensolon/solon-integration](../../../../opensolon/solon-integration)     | Solon Integration ,代码仓库          | 
| [/opensolon/solon-java17](../../../../opensolon/solon-java17)               | Solon Java17 ,代码仓库（base java17） | 
| [/opensolon/solon-java25](../../../../opensolon/solon-java25)               | Solon Java25 ,代码仓库（base java25）  | 
|                                                                             |                                  |
| [/opensolon/solon-gradle-plugin](../../../../opensolon/solon-gradle-plugin) | Solon Gradle ,插件代码仓库             | 
| [/opensolon/solon-idea-plugin](../../../../opensolon/solon-idea-plugin)     | Solon Idea ,插件代码仓库               | 
| [/opensolon/solon-vscode-plugin](../../../../opensolon/solon-vscode-plugin) | Solon VsCode ,插件代码仓库             | 
