<h1 align="center" style="text-align:center;">
<img src="solon_icon.png" width="128" />
<br />
Solon-AI
</h1>
<p align="center">
	<strong>Java LLM(tool, skill) & RAG & MCP & Agent(ReAct, Team) Application development framework</strong>
    <br/>
    <strong>Restraint, efficiency and openness</strong>
    <br/>
    <strong>It is the same type of development framework as LangChain and LangGraph</strong>
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


##### Language: English | [中文](README_CN.md)

<hr />


## 简介

Solon AI is one of the core subprojects of the Solon project. It is a full-scenario Java AI development framework, which aims to deeply integrate LLM large model, RAG knowledge base, MCP protocol and Agent collaboration choreography.

* Full use case support: fits perfectly into the Solon ecosystem and can be seamlessly integrated into frameworks like SpringBoot, Vert.X, Quarkus, etc.
* Multi-model dialects: Adapt model differences by dialect using ChatModel's unified interface (OpenAI, Gemini, Claude, Ollama, DeepSeek, Dashscope, etc.).
* Graph-driven orchestration: supports the transformation of Agent reasoning into observable and governable computation flow graphs.


Examples of embeddings (including third-party frameworks) for solon-ai:

* https://gitee.com/solonlab/solon-ai-mcp-embedded-examples
* https://gitcode.com/solonlab/solon-ai-mcp-embedded-examples
* https://github.com/solonlab/solon-ai-mcp-embedded-examples


## What types of applications can be developed?

* General-purpose Autonomous Agents (e.g., Manus, OpenOperator)
* Intelligent Assistants & RAG Knowledge Bases (e.g., Dify, Coze)
* Multi-Agent Collaborative Orchestration (e.g., AutoGPT, MetaGPT)
* Business-Driven Controlled Workflows (e.g., AI-enhanced DingTalk/Lark approvals, SAP Intelligent Modules)
* Intelligent Document Processing & ETL (e.g., Instabase, Unstructured.io)
* Real-time Data Insights & Dashboards (e.g., Text-to-SQL applications)
* Automated Testing & Quality Assurance (e.g., GitHub Copilot Workspace)
* Low-Code/Visual AI Workflow Platforms (e.g., LangFlow, Flowise)
* And more...

## Core Module Experience

* ChatModel(General Purpose LLM call interface)

Support for synchronous and Reactive calls, built-in dialect adaptation, Tool, Skill, ChatSession, etc.

```java
ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .provider("ollama") //Need to specify vendor, used to identify interface style (also called dialect)
                .model("qwen2.5:1.5b")
                .defaultSkillAdd(new ToolGatewaySkill())
                .build();

// Synchronize the call and print the response message
AssistantMessage result = ChatchatModel.prompt("The weather in Hangzhou today？")
         .options(op->op.toolAdd(new WeatherTools())) //Adding tools
         .call()
         .getMessage();
System.out.println(result);

// Stream call
chatModel.prompt("hello").stream(); //Publisher<ChatResponse>
```

* Skills（Solon AI Skills）


```java
Skill skill = new SkillDesc("order_expert")
        .description("Order Assistant")
        // Dynamic admission: Activated only when "order" is mentioned
        .isSupported(prompt -> prompt.getUserMessageContent().contains("order"))
        // Dynamic instructions: Inject different Sops depending on whether the user is a VIP or not
        .instruction(prompt -> {
            if ("VIP".equals(prompt.getMeta("user_level"))) {
                return "This is a VIP customer, please call fast_track_tool first.";
            }
            return "Process the order inquiry according to the normal process.";
        })
        .toolAdd(new OrderTools());

chatModel.prompt("Where is my order from yesterday？")
         .options(o->o.skillAdd(skill))
         .call();
```


* RAG（知识库）

It provides full-link support from DocumentLoader, DocumentSplitter, EmbeddingModel, and RerankingModel.

```java
//Building a Knowledge Warehouse
EmbeddingModel embeddingModel = EmbeddingModel.of(apiUrl).apiKey(apiKey).provider(provider).model(model).batchSize(10).build();
RerankingModel rerankingModel = RerankingModel.of(apiUrl).apiKey(apiKey).provider(provider).model(model).build();
InMemoryRepository repository = new InMemoryRepository(TestUtils.getEmbeddingModel()); //3.初始化知识库

repository.insert(new PdfLoader(pdfUri).load());

//retrieval
List<Document> docs = repository.search(query);

//You can rearrange it if you want
docs = rerankingModel.rerank(query, docs);

//Cue enhancement is
ChatMessage message = ChatMessage.ofUserAugment(query, docs);

//Calling the llm
chatModel.prompt(message) 
    .call();
```


* MCP (Model Context Protocol)

Deep integration with MCP protocol (MCP_2025_06_18), supporting cross-platform tool, resource, and prompt sharing.


```java
//server
@McpServerEndpoint(channel = McpChannel.STREAMABLE, mcpEndpoint = "/mcp") 
public class MyMcpServer {
    @ToolMapping(description = "Checking the weather")
    public String getWeather(@Param(description = "city") String location) {
        return "It's sunny, 25 degrees";
    }
}

//client
McpClientProvider clientProvider = McpClientProvider.builder()
        .channel(McpChannel.STREAMABLE)
        .url("http://localhost:8080/mcp")
        .build();
```


* Agent (An Agent Experience with Computational Flow Graphs)

The Solon AI Agent transforms reasoning logic into graph-driven collaboration flows, enabling ReAct introspective reasoning and multi-agent Team collaboration.

```java
//Reflective intelligent agent:
ReActAgent agent = ReActAgent.of(chatModel) // 或者用 SimpleAgent.of(chatModel)
    .name("weather_expert")
    .description("Check the weather and provide advice")
    .defaultToolAdd(weatherTool) // Inject MCP or local tools
    .build();

agent.prompt("What to wear in Beijing today？").call(); // Autocomplete: Think -> Call tool -> Observe -> Summarize

// Constructing a team agent: Automatically arranging member roles through protocols
TeamAgent team = TeamAgent.of(chatModel)
    .name("marketing_team")
    .protocol(TeamProtocols.HIERARCHICAL) // Hierarchical collaboration (6 preset protocols)
    .agentAdd(copywriterAgent) // Copywriter expert
    .agentAdd(illustratorAgent) // Illustrator expert
    .build();

team.prompt("Plan a promotion scheme for deep-sea mineral water").call(); // Supervisor automatically decomposes tasks and assigns them to corresponding experts    .defaultToolAdd(weatherTool) // Inject MCP or local tools
```



* Ai Flow（Process orchestration experience）

The low-code flow application of Dify is simulated, and the links such as RAG, hint word enhancement and model call are YAML arranged.

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

## Solon Project code repository




| Code repository                                                             | Description                                               | 
|-----------------------------------------------------------------------------|-----------------------------------------------------------| 
| [/opensolon/solon](../../../../opensolon/solon)                             | Solon ,Main code repository                               | 
| [/opensolon/solon-examples](../../../../opensolon/solon-examples)           | Solon ,Official website supporting sample code repository |
|                                                                             |                                                           |
| [/opensolon/solon-expression](../../../../opensolon/solon-expression)       | Solon Expression ,Code repository                         | 
| [/opensolon/solon-flow](../../../../opensolon/solon-flow)                   | Solon Flow ,Code repository                               | 
| [/opensolon/solon-ai](../../../../opensolon/solon-ai)                       | Solon Ai ,Code repository                                 |
| [/opensolon/solon-cloud](../../../../opensolon/solon-cloud)                 | Solon Cloud ,Code repository                              | 
| [/opensolon/solon-admin](../../../../opensolon/solon-admin)                 | Solon Admin ,Code repository                              | 
| [/opensolon/solon-integration](../../../../opensolon/solon-integration)     | Solon Integration ,Code repository                        | 
| [/opensolon/solon-java17](../../../../opensolon/solon-java17)               | Solon Java17 ,Code repository（base java17）               | 
| [/opensolon/solon-java25](../../../../opensolon/solon-java25)               | Solon Java25 ,Code repository（base java25）                | 
|                                                                             |                                                           |
| [/opensolon/solon-gradle-plugin](../../../../opensolon/solon-gradle-plugin) | Solon Gradle ,Plugin code repository                      | 
| [/opensolon/solon-idea-plugin](../../../../opensolon/solon-idea-plugin)     | Solon Idea ,Plugin code repository                        | 
| [/opensolon/solon-vscode-plugin](../../../../opensolon/solon-vscode-plugin) | Solon VsCode ,Plugin code repository                      | 

