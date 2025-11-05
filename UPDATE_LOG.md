### 可测试目标

* Trae
* Dify
* Cherry Studio


### 待定


*  channel 概念改为 transport （并保持兼容）???


### 3.5.8

* 修复 solon-ai parseToolCall 接收 stream 中间消息时可能会异常（添加 hasNestedJsonBlock 检测）

### 3.5.7

* 优化 solon-ai-mcp 取消 request.contentType("") 设置

### 3.5.6

* 修复 solon-ai-core chatModel:stream 长流输出可能出错的问题
* mcp 移除 WebRxSseClientTransport:sendMessage 的 accept 声明（是在 3.5.5 时加的，此处没必要）

### 3.5.5

* 添加 solon-ai-mcp McpClientProvider:httpFactory 默认为 jdkhttp（okhttp 有些平台不兼容），不再随 HttpUtils 的全局走
* 添加 solon-ai-mcp WebRxSseClientTransport:sendMessage  accept 显示声明

### 3.5.3

* 优化 solon-ai-core chatModel.stream 与背压处理的兼容性
* 调整 solon-ai-map getPrompt,readResource,callTool 取消自动异常转换（侧重原始返回）
* 调整 solon-ai-map callTool 错误结果传递，自动添加 'Error:' （方便 llm 识别）
* 修复 solon-ai-mcp callTool isError=true 时，不能正常与 llm 交互的问题
* 修复 solon-ai-mcp ToolAnnotations:returnDirect 为 null 时的传递兼容性

### 3.5.2

* 添加 solon-ai-core ToolSchemaUtil 简化方法
* 添加 solon-ai-mcp McpClientProperties:timeout 属性，方便简化超时配置（可省略 httpTimeout, requestTimeout, initializationTimeout）
* 添加 solon-ai-mcp McpClientProvider:toolsChangeConsumer,resourcesChangeConsumer,resourcesUpdateConsumer,promptsChangeConsumer 配置支持
* 添加 solon-ai-mcp McpClientProvider 缓存锁和变更刷新控制
* 添加 solon-ai-mcp IMcpServerEndpoint 接口（方便可批量获取组件）
* 优化 solon-ai-core RepositoryStorable 接口定义，用 save 替代 insert(标为弃用)
* 调整 solon-ai-core FunctionToolDesc:doHandle 改用 ToolHandler 参数类型（之前为 Function），方便传递异常

### 3.5.1

* 新增 solon-ai-a2a 插件
* 新增 solon-ai-core GenerateModel 接口，替代 ImageModel
* 新增 solon-ai-core ChatModel 增加多媒体内容输出（增强感知型模型的兼容，比如输出图片或视频）
* 新增 solon-ai-core ImageModel 增加结构体提示语输入（比如图片编辑模型）
* 添加 solon-ai-core AbstractChatDialect 对多媒体内容输出的支持
* 添加 solon-ai-core AssistantMessage:contentRaw 原生内容（可能是 String、Map、List、null）
* 添加 solon-ai-dialect-dashscope 通过接口地址识别方言
* 添加 solon-ai-mcp McpServerEndpointProvider:Builder 添加 context-path 配置
* 优化 solon-ai-mcp McpClientProvider 配置向 McpServers json 格式上靠
* 修复 solon-ai-core `think-> tool -> think` 时，工具调用的内容无法加入到对话的问题
* 修复 solon-ai-mcp 服务端传输层的会话长连会超时的问题
* 修复 solon-ai-mcp 客户端提供者心跳失效的问题
* 修复 solon-ai-mcp SSE 传输时 message 端点未附加 context-path 的问题
* mcp `McpSchema:*Capabilities` 添加 `@JsonIgnoreProperties(ignoreUnknown = true)` 增强跨协议版本兼容性


### 3.5.0

* 新增 solon-ai-mcp mcp-java-sdk v0.11.0 适配（支持 2025-03-26 版本协议）
* 调整 solon-ai-mcp channel 取消默认值（之前为 sse），且为必填（利于协议升级过度，有明确的开发时、启动时提醒）
  * 如果默认值仍为 sse ，升级后可能忘了修改了升级
  * 如果默认值改为 streamable，升级后会造成不兼容

### 3.4.3

* 新增 solon-ai-repo-mysql 插件
* 添加 solon-ai-core InMemoryChatSession（语义清晰） 替代 ChatSessionDefault（标为弃用）
* 优化 solon-ai-core ChatRequestDescDefault http 异常转换描述
* 优化 solon-ai-core 方言的 tool_calls 消息的构建（更好的兼容 vllm）
* 优化 solon-ai-mcp JsonSchema.additionalProperties 兼容性（兼容 bool, map）
* 优化 solon-ai-mcp McpClientProvider 改为 McpAsyncClient（为异步需求提供支持）
* 优化 solon-ai-mcp 初始化控制（禁用 connectOnInit），增加连接打印


### 3.4.1

* 新增 solon-ai-repo-pgvector 插件
* 新增 solon-ai-search-baidu 插件
* 添加 solon-ai-core `TextLoader(byte[])(SupplierEx<InputStream>)` 构造方法
* 添加 solon-ai-core `ChatConfig:defaultToolsContext`（默认工具上下文）, `defaultOptions`（默认选项） 属性
* 添加 solon-ai-core `RepositoryStorable:insert(list,progressCallback)` 和 `asyncInsert(list,progressCallback)` 方法，支持进度获取
* 添加 solon-ai-mcp 客户端 ssl 定制支持
* 优化 solon-ai 方言 think 思考内容和字段的兼容性处理
* 优化 solon-ai 方言处理与 modelscope（魔搭社区）的兼容性
* 优化 solon-ai 方言处理与 siliconflow（硅基流动）的兼容性
* 优化 solon-ai 方言处理的流式节点识别兼容性
* 优化 solon-ai 用户消息的请求构建（当内容为空时，不添加 text）
* 优化 solon-ai-mcp McpClientProvider 心跳间隔控制（5s 以下忽略）
* 优化 solon-ai-mcp McpServerContext 增加 stdio 代理支持（环境变量自动转为 ctx:header）
* mcp WebRxSseClientTransport 添加 debug 日志打印


### 3.4.0

* 新增 solon-ai-repo-opensearch 插件
* 添加 solon-ai Options:toolsContext 方法
* 调整 solon-ai-core ToolCallResultJsonConverter 更名为 ToolCallResultConverterDefault 并添加序列化插件支持
* 调整 solon-ai-mcp PromptMapping，ResourceMapping 取消 resultConverter 属性（没必要）
* 修复 solon-ai-core ChatModel:stream:doOnNext 可能无法获取 isFinished=true 情况

### 3.3.3

* 优化 solon-ai-core ToolSchemaUtil 对 Map 的处理（有些框架，太细不支持）
* 优化 solon-ai-core ToolSchemaUtil 对 Collection 的处理（有些框架，太细不支持）
* 优化 solon-ai-mcp MethodPromptProvider,MethodResourceProvider 改用 clz 构建(兼容外部代理情况)
* 优化 solon-ai-core MethodToolProvider 改用 clz 构建(兼容外部代理情况)
* 添加 solon-ai-core RepositoryStorable:insert(Doc...) 方法
* 添加 solon-ai-mcp McpServerEndpoint:enableOutputSchema 支持（默认为 false）
* 调整 solon-ai-core ToolCallResultConverter 接口定义（增加返回类型参数）
* 调整 solon-ai-core 移除 QueryCondition:doFilter 方法（避免误解）
* 调整 solon-ai-mcp tool,resource 结果默认处理改为 ToolCallResultJsonConverter
* 调整 solon-ai-repo-elasticsearch 搜索类型，默认改为相似搜索（之前为精准，需要脚本权限）
* mcp 优化 WebRxSseClientTransport 连接等待处理（异常时立即结束）
* elasticsearch-rest-high-level-client 升为 7.17.28
* milvus-sdk-java 升为 2.5.10
* vectordatabase-sdk-java 升为 2.4.5

### 3.3.2

* 优化 solon-ai-flow 插件
* 添加 solon-ai-core ChatInterceptor 聊天拦截机制
* 添加 solon-ai-core ChatMessage:ofUserAugment 替代 augment（后者标为弃用）
* 添加 solon-ai-core ProxyDesc 的 Serializable 接口实现
* 添加 solon-ai-core ChatOptions:response_format 方法
* 添加 solon-ai-core AssistantMessage:getSearchResultsRaw 方法
* 添加 solon-ai-mcp McpServerEndpointProvider:getMessageEndpoint 方法
* 添加 solon-ai-mcp McpServerParameters http 参数支持
* 添加 solon-ai-mcp McpClientProvider 本地缓存支持（默认 30秒）
* 添加 solon-ai-mcp 原语处理异常日志
* 优化 solon-ai-core ChatConfig.toString （增加 proxy）
* 优化 solon-ai-core Tool:outputSchema 改为必出
* 优化 solon-ai-core 添加 ToolCallException 异常类型，用于 tool call 异常传递（之前为 ChatException）
* 优化 solon-ai OpenaiChatDialect 方言，tool 消息也附带所有的 tools 元信息（之前被过滤了）
* 优化 solon-ai-mcp McpServerContext 同步连接时的请求参数，方便在 Tool 方法里获取
* 优化 solon-ai-mcp McpProviders 在 sse 时，支持 env 也作为 header 处理（有些服务方的配置，是用 env 的）
* 优化 solon-ai-mcp 取消 RefererFunctionTool（由 FunctionToolDesc 替代）
* 优化 solon-ai-mcp 基于 McpServerParameters 的构建能力
* 修复 solon-ai-flow ChatModel 没有加载 mcpServers 配置的问题
* mcp 适配优化 WebRxSseClientTransport 关闭时同时取消 sse 订阅（避免线程占用）


### 3.3.1

* 新增 solon-ai-flow 插件
* 新增 solon-ai-load-ddl 插件
* 添加 solon-ai-core ChatMessage:ofUser(media) 方法
* 添加 solon-ai-core ChatSession:addMessage(ChatPrompt) 方法
* 添加 solon-ai-core ChatSession:addMessage(Collection) 方法
* 添加 solon-ai-core RerankingConfig,RerankingModel toString 方法
* 添加 solon-ai-core 模型的网络代理支持（支持简单配置，和复杂构建）
* 添加 solon-ai-mcp 客户端的网络代理简单配置支持
* 添加 solon-ai-mcp messageEndpoint 端点配置支持（应对特殊需求，一般自动更好）
* 添加 solon-ai-mcp ToolMapping,ResourceMapping 注解方法对 Produces 注解的支持（用它可指定结果转换处理）
* 添加 solon-ai-mcp ToolCallResultConverter:matched 方法
* 添加 solon-ai-mcp 资源模板的响应适配
* 添加 solon-ai-mcp McpClientProvider:getResourceTemplates 方法
* 添加 solon-ai-mcp 检查原语是否存在的方法（hasTool, hasPrompt, hasResource）
* 添加 solon-ai-mcp 提示语支持 UserMessage 拆解成多条 mcp 内容（如果，同时有媒体和文本的话）
* 优化 solon-ai-core tool 空参数时的不同大模型兼容性
* 优化 solon-ai-core ChatSession 的作用，为限数提供支持
* 优化 solon-ai-core MethodFunctionTool 移除对 Mapping 注解的支持（语意更清楚，之前 MethodToolProvider 已经移除，这个落了）
* 优化 solon-ai-core EmbeddingRequest，ImageRequest，RerankingRequest 当 resp.getError() 非 null 时，直接出抛异常
* 优化 solon-ai-mcp 取消 MethodFunctionResource 对反回类型的限制（增加了 resultConverter 转换处理）
* 优化 solon-ai-mcp McpServerEndpointProvider 支持零添加原语，postStart 后，可添加原语
* 修复 solon-ai ChatRequestDefault:stream 请求 r1 时，可能会产生两次 tink 消息发射

### 3.3.0

* 新增 solon-ai-repo-dashvector 插件
* 插件 solon-ai 三次预览
* 插件 solon-ai-mcp 二次预览
* 调整 solon-ai 移除 ToolParam 注解，改用 `Param` 注解（通用参数注解）
* 调整 solon-ai ToolMapping 注解移到 `org.noear.solon.ai.annotation`
* 调整 solon-ai FunctionToolDesc:param 改为 `paramAdd` 风格
* 调整 solon-ai MethodToolProvider 取消对 Mapping 注解的支持（利于跨生态体验的统一性）
* 调整 solon-ai 拆分为 solon-ai-core 和 solon-ai-model-dialects（方便适配与扩展）
* 调整 solon-ai 模型方言改为插件扩展方式
* 调整 solon-ai-mcp McpClientToolProvider 更名为 McpClientProvider（实现的接口变多了）
* 优化 solon-ai 允许 MethodFunctionTool,MethodFunctionPrompt,MethodFunctionResource 没有 solon 上下文的用况
* 优化 solon-ai-core `model.options(o->{})` 可多次调用
* 优化 solon-ai-mcp McpClientProvider 同时实现 ResourceProvider, PromptProvider 接口
* 优化 solon-ai-repo-redis metadataIndexFields 更名为 `metadataFields` （原名标为弃用）
* 添加 solon-ai-core ChatSubscriberProxy 用于控制外部订阅者，只触发一次 onSubscribe
* 添加 solon-ai-mcp McpClientProperties:httpProxy 配置
* 添加 solon-ai-mcp McpClientToolProvider isStarted 状态位（把心跳开始，转为第一次调用这后）
* 添加 solon-ai-mcp McpClientToolProvider:readResourceAsText,readResource,getPromptAsMessages,getPrompt 方法
* 添加 solon-ai-mcp McpServerEndpointProvider:getVersion,getChannel,getSseEndpoint,getTools,getServer 方法
* 添加 solon-ai-mcp McpServerEndpointProvider:addResource,addPrompt 方法
* 添加 solon-ai-mcp McpServerEndpointProvider:Builder:channel 方法
* 添加 solon-ai-mcp ResourceMapping 和 PromptMapping 注解（支持资源与提示语服务）
* 添加 solon-ai-mcp McpServerEndpoint AOP 支持（可支持 solono auth 注解鉴权）
* 添加 solon-ai-mcp McpServerEndpoint 实体参数支持（可支持 solon web 的实体参数、注解相通）
* 添加 solon-ai-mpc `Tool.returnDirect` 属性透传（前后端都有 solon-ai 时有效，目前还不是规范）

### 3.2.1

* 添加 solon-ai ChatRequestDefault http 状态异常处理
* 添加 solon-ai ToolCallResultConverter 接口（工具调用结果转换器）
* 添加 solon-ai ToolCall 添加 Mapping 和 Param 注解（支持与 web api 打通）
* 添加 solon-ai Tool.returnDirect 属性，用于直接返回给调用者（mcp 目前无法传导此属性，只能地本地用）
* 添加 solon-ai-mcp McpChannel 通道（stdio, sse），实现不通道的支持
* 添加 solon-ai-mcp stdio 通道交换流支持
* 添加 solon-ai-mcp McpClientToolProvider 断线重连机制
* 添加 solon-ai-mcp McpClientProperties:fromMcpServers 方法
* 调整 solon-ai-mcp McpClientToolProvider.Builder:header 更名为 headerSet。保持与 ChatModel:Builder 相同风格
* 调整 solon-ai ToolCallResultConverter 申明不再扩展自 Converter，避免冲突
* 修复 solon-ai-mcp McpClientToolProvider 会丢失 queryString 的问题
* 修复 solon-ai-load-word WordLoader 流使用错误问题
* 修复 solon-ai ollama 方言，在多工具调用时产生 index 混乱的问题

### 3.2.0

* 新增 solon-ai-mcp
* 调整 函数(Function)相关的概念改为工具概念(Tool)。调整后适合 mcp 的相关概念

### v3.1.2

* 新增 solon-ai-repo-chroma 插件
* 优化 solon-ai-repo-tcvectordb 插件相似度处理
* 优化 solon-ai-repo-elasticsearch 插件相似度处理

### v3.1.1

* 新增 solon-ai-load-ppt 插件，添加对 ppt, pptx 文档的解析
* 新增 solon-ai-load-word 插件，添加对 doc, docx 文档的解析
* 新增 solon-ai-repo-qdrant 插件
* 新增 solon-ai-repo-tcvectordb 插件
* 新增solon-ai-repo-elasticsearch 插件
* 添加 solon-ai ChatResponse:getAggregationMessage(), isStream() 方法
* 添加 solon-ai ChatSession 自动登记处理
* 调整 solon-ai ChatDialect:parseResponseJson 定义
* 修复 solon-ai 在流式调用时 function call 出错的问题