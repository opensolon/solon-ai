/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.mcp.client;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.WebRxSseClientTransport;
import io.modelcontextprotocol.client.transport.WebRxStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.content.*;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.primitives.prompt.FunctionPrompt;
import org.noear.solon.ai.mcp.primitives.prompt.FunctionPromptDesc;
import org.noear.solon.ai.mcp.primitives.prompt.PromptProvider;
import org.noear.solon.ai.mcp.primitives.prompt.PromptResult;
import org.noear.solon.ai.mcp.primitives.resource.FunctionResource;
import org.noear.solon.ai.mcp.primitives.resource.FunctionResourceDesc;
import org.noear.solon.ai.mcp.primitives.resource.ResourceProvider;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.primitives.resource.ResourceResult;
import org.noear.solon.core.Props;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.data.cache.LocalCacheService;
import org.noear.solon.data.util.StringMutexLock;
import org.noear.solon.net.http.HttpSslSupplier;
import org.noear.solon.net.http.HttpTimeout;
import org.noear.solon.net.http.HttpUtilsBuilder;
import org.noear.solon.net.http.HttpUtilsFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Mcp 客户端提供者
 *
 * <pre>{@code
 * McpClientProvider toolProvider = McpClientProvider.builder()
 *                 .apiUrl("http://localhost:8081/sse")
 *                 .build();
 *
 * ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
 *                 .model("deepseek-v3")
 *                 .defaultToolsAdd(toolProvider)
 *                 .build();
 *
 * ChatResponse resp = chatModel.prompt("杭州天气和北京降雨量如何？")
 *                 .call();
 * }</pre>
 *
 * @author noear
 * @since 3.1
 */
public class McpClientProvider implements ToolProvider, ResourceProvider, PromptProvider, Closeable {
    static final Logger log = LoggerFactory.getLogger(McpClientProvider.class);

    private final ReentrantLock LOCKER = new ReentrantLock();


    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final AtomicBoolean isStarted = new AtomicBoolean(false);

    private final McpClientProperties clientProps;

    private ScheduledExecutorService heartbeatExecutor;
    private McpAsyncClient client;
    private McpSchema.LoggingLevel loggingLevel = McpSchema.LoggingLevel.INFO;

    private static final String cache_prefix_tools = "getTools:";
    private static final String cache_prefix_resource = "getResources:";
    private static final String cache_prefix_resource_templates = "getResourceTemplates:";
    private static final String cache_prefix_prompts = "getPrompts:";

    private LocalCacheService cacheService = new LocalCacheService();
    private final StringMutexLock cacheLocker = new StringMutexLock();


    /**
     * 用于支持注入
     */
    public McpClientProvider(Properties clientProps) {
        this(Props.from(clientProps).bindTo(new McpClientProperties()));
    }

    /**
     * 用于简单构建
     */
    public McpClientProvider(String apiUrl) {
        this(new McpClientProperties(apiUrl));
    }

    public McpClientProvider(McpClientProperties clientProps) {
        if (Utils.isEmpty(clientProps.getChannel())) {
            throw new IllegalArgumentException("The channel is required");
        }

        //预备（对超时做检测）
        clientProps.prepare();

        if (McpChannel.STDIO.equals(clientProps.getChannel())) {
            //stdio 通道
            if (clientProps.getCommand() == null) {
                throw new IllegalArgumentException("Command is null!");
            }
        } else {
            //sse 通道
            if (Utils.isEmpty(clientProps.getUrl())) {
                throw new IllegalArgumentException("Url is empty!");
            }
        }

        this.clientProps = clientProps;

        //开始心跳
        this.heartbeatHandle();
    }

    /**
     * 清理缓存
     */
    public void clearCache() {
        cacheService.clear();
    }

    /**
     * 构建同步客户端
     */
    private McpAsyncClient buildClient() {
        McpClientTransport clientTransport;

        if (McpChannel.STDIO.equals(clientProps.getChannel())) {
            //stdio 通道
            clientTransport = new StdioClientTransport(ServerParameters.builder(clientProps.getCommand())
                    .args(clientProps.getArgs())
                    .env(clientProps.getEnv())
                    .build(), McpJsonMapper.getDefault());
        } else {
            //sse 通道
            URI url = URI.create(clientProps.getUrl());
            String baseUri = url.getScheme() + "://" + url.getAuthority();

            String endpoint = null;
            if (Utils.isEmpty(url.getRawQuery())) {
                endpoint = url.getRawPath();
            } else {
                endpoint = url.getRawPath() + "?" + url.getRawQuery();
            }


            if (Utils.isEmpty(endpoint)) {
                throw new IllegalArgumentException("SseEndpoint is empty!");
            }

            //超时
            HttpUtilsBuilder webBuilder = new HttpUtilsBuilder();

            webBuilder.baseUri(baseUri);
            webBuilder.factory(clientProps.getHttpFactory());

            if (Utils.isNotEmpty(clientProps.getApiKey())) {
                webBuilder.headerSet("Authorization", "Bearer " + clientProps.getApiKey());
            }

            clientProps.getHeaders().forEach((k, v) -> {
                webBuilder.headerSet(k, v);
            });

            if (clientProps.getHttpTimeout() != null) {
                webBuilder.timeout(clientProps.getHttpTimeout());
            }

            if (clientProps.getHttpProxy() != null) {
                webBuilder.proxy(clientProps.getHttpProxy());
            }

            if (clientProps.getHttpSsl() != null) {
                webBuilder.ssl(clientProps.getHttpSsl());
            }

            if (McpChannel.SSE.equals(clientProps.getChannel())) {
                clientTransport = WebRxSseClientTransport.builder(webBuilder)
                        .sseEndpoint(endpoint)
                        .build();
            } else {
                //streamable || streamable_stateless
                clientTransport = WebRxStreamableHttpTransport.builder(webBuilder)
                        .endpoint(endpoint)
                        .build();
            }
        }


        //spec
        McpClient.AsyncSpec clientSpec = McpClient.async(clientTransport)
                .clientInfo(new McpSchema.Implementation(clientProps.getName(), clientProps.getVersion()))
                .requestTimeout(clientProps.getRequestTimeout())
                .initializationTimeout(clientProps.getInitializationTimeout())
                .toolsChangeConsumer(this::onToolsChange)
                .resourcesChangeConsumer(this::onResourcesChange)
                .resourcesUpdateConsumer(this::onResourcesUpdate)
                .promptsChangeConsumer(this::onPromptsChange);
        //.withConnectOnInit(false) //初始化放到后面（更可控）

        if (clientProps.getClientCustomizer() != null) {
            clientProps.getClientCustomizer().accept(clientSpec);
        }

        //build
        McpAsyncClient clientRaw = clientSpec.build();
        clientRaw.setLoggingLevel(loggingLevel);

        return clientRaw;
    }

    private Mono<Void> onToolsChange(List<McpSchema.Tool> tools) {
        cacheService.remove(cache_prefix_tools + null);

        if (clientProps.getToolsChangeConsumer() != null) {
            return clientProps.getToolsChangeConsumer().apply(tools);
        } else {
            return Mono.empty();
        }
    }

    private Mono<Void> onResourcesChange(List<McpSchema.Resource> resources) {
        cacheService.remove(cache_prefix_resource + null);
        cacheService.remove(cache_prefix_resource_templates + null);

        if (clientProps.getResourcesChangeConsumer() != null) {
            return clientProps.getResourcesChangeConsumer().apply(resources);
        } else {
            return Mono.empty();
        }
    }

    private Mono<Void> onResourcesUpdate(List<McpSchema.ResourceContents> resourceContents) {
        cacheService.remove(cache_prefix_resource + null);
        cacheService.remove(cache_prefix_resource_templates + null);

        if (clientProps.getResourcesUpdateConsumer() != null) {
            return clientProps.getResourcesUpdateConsumer().apply(resourceContents);
        } else {
            return Mono.empty();
        }
    }

    private Mono<Void> onPromptsChange(List<McpSchema.Prompt> prompts) {
        cacheService.remove(cache_prefix_prompts + null);

        if (clientProps.getPromptsChangeConsumer() != null) {
            return clientProps.getPromptsChangeConsumer().apply(prompts);
        } else {
            return Mono.empty();
        }
    }


    /**
     * 获取客户端
     */
    public McpAsyncClient getClient() {
        if (isClosed.get()) {
            throw new IllegalStateException("The current status has been closed.");
        }

        if (client != null && isStarted.get()) {
            return client;
        }

        LOCKER.lock();

        try {
            if (isClosed.get()) {
                //如果已关闭
                throw new IllegalStateException("The current status has been closed.");
            }

            //设为开始
            isStarted.set(true);

            if (client == null) {
                client = buildClient();
            }

            if (client.isInitialized() == false) {
                try {
                    client.initialize().block();
                } catch (Throwable e) {
                    this.reset();
                    throw e;
                }
            }

            return client;
        } finally {
            LOCKER.unlock();
        }
    }

    public <T> T executeWithRetry(Function<McpAsyncClient, Mono<T>> action) {
        try {
            return action.apply(getClient()).block();
        } catch (Throwable ex) {
            if (isTransportError(ex)) {
                log.warn("MCP transmission is abnormal, attempt to reconnect...");
                this.reset();

                try {
                    return action.apply(getClient()).block();
                } catch (Throwable ex2) {
                    log.error("Retry still fails after reconnecting: {}", ex2.getMessage());
                    throw ex2;
                }
            }

            throw ex;
        }
    }

    /**
     * 判断是否为传输层错误（需要重连的错误）
     */
    private boolean isTransportError(Throwable ex) {
        String msg = ex.toString();
        return ex instanceof io.modelcontextprotocol.spec.McpTransportException ||
                msg.contains("HttpResponseException") ||
                msg.contains("Connection refused") ||
                msg.contains("500 Internal Server Error");
    }

    /**
     * 设置日志级别
     */
    public void setLoggingLevel(McpSchema.LoggingLevel loggingLevel) {
        if (loggingLevel != null) {
            this.loggingLevel = loggingLevel;
        }
    }

    private void heartbeatHandle() {
        if (clientProps.getHeartbeatInterval() == null) {
            return;
        }

        if (clientProps.getHeartbeatInterval().getSeconds() < 5L) {
            return;
        }

        if (heartbeatExecutor == null) {
            heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        }

        heartbeatHandleDo();
    }

    /**
     * 心跳处理
     */
    private long currentDelay = -1; // 动态延迟时间

    private void heartbeatHandleDo() {
        if (heartbeatExecutor == null) {
            return;
        }

        // 首次运行时初始化延迟
        if (currentDelay == -1) {
            currentDelay = clientProps.getHeartbeatInterval().toMillis();
        }

        //单次延后执行
        heartbeatExecutor.schedule(() -> {
            if (Thread.currentThread().isInterrupted()) {
                //如果中断
                return;
            }


            if (isClosed.get()) return;

            boolean success = false;
            try {
                if (isStarted.get()) {
                    getClient().ping().block();
                    success = true;
                }
            } catch (Throwable ex) {
                log.warn("MCP Heartbeat failed, resetting client...");
                this.reset();
            }

            if (success) {
                // 成功：重置延迟时间为初始值
                currentDelay = clientProps.getHeartbeatInterval().toMillis();
            } else {
                // 失败：延迟翻倍，最大不超过 10 分钟
                currentDelay = Math.min(currentDelay * 2, TimeUnit.MINUTES.toMillis(10));
            }

            // 递归下一次任务
            heartbeatHandleDo();
        }, currentDelay, TimeUnit.MILLISECONDS);
    }

    /**
     * 关闭
     */
    @Override
    public void close() {
        LOCKER.lock();
        try {
            if (isClosed.get() == false) {
                //如果未关闭
                isClosed.set(true);
                isStarted.set(false);

                if (heartbeatExecutor != null) {
                    heartbeatExecutor.shutdownNow();
                    heartbeatExecutor = null;
                }

                this.reset();
            }
        } finally {
            LOCKER.unlock();
        }
    }

    /**
     * 重新打开
     */
    public void reopen() {
        LOCKER.lock();
        try {
            if (isClosed.get()) {
                //如果已关闭
                isClosed.set(false);
                // 重置动态延迟，让心跳立刻以初始频率尝试
                currentDelay = -1;
                getClient();
                heartbeatHandle();
            }
        } finally {
            LOCKER.unlock();
        }
    }

    /**
     * 重置
     */
    private void reset() {
        LOCKER.lock();
        try {
            if (client != null) {
                RunUtil.runAndTry(client::close);
                client = null;
                isStarted.set(false);
            }
        } finally {
            LOCKER.unlock();
        }
    }

    /// /////////////////////////////

    private ContentBlock toAiMedia(McpSchema.Content content, Boolean error) {
        if (content instanceof McpSchema.TextContent) {
            String text = ((McpSchema.TextContent) content).text();
            if (error != null && error && text.startsWith("Error: ") == false) {
                text = "Error: " + text;
            }

            return TextBlock.of(text);
        } else if (content instanceof McpSchema.ImageContent) {
            McpSchema.ImageContent image = (McpSchema.ImageContent) content;
            if (image.data().contains("://")) {
                return ImageBlock.ofUrl(image.data());
            } else {
                return ImageBlock.ofBase64(image.data(), image.mimeType());
            }
        } else if (content instanceof McpSchema.AudioContent) {
            McpSchema.AudioContent audio = (McpSchema.AudioContent) content;
            if (audio.data().contains("://")) {
                return AudioBlock.ofUrl(audio.data());
            } else {
                return AudioBlock.ofBase64(audio.data(), audio.mimeType());
            }
        }

        return null;
    }

    /// ////////////////////////////

    /**
     * 调用工具并转为文本
     *
     * @param name 工具名
     * @param args 调用参数
     */
    public ToolResult callTool(String name, Map<String, Object> args) {
        McpSchema.CallToolResult mcpResult = callToolRequest(name, args);

        ToolResult result = new ToolResult();
        result.setError(mcpResult.isError() != null && mcpResult.isError());

        if (mcpResult.content() != null) {
            for (McpSchema.Content c : mcpResult.content()) {
                ContentBlock media = toAiMedia(c, mcpResult.isError());
                if (media != null) {
                    result.addBlock(media);
                }
            }
        }

        if (mcpResult.meta() != null) {
            result.getMetadata().putAll(mcpResult.meta());
        }

        return result;
    }

    /**
     * 调用工具
     *
     * @param name 工具名
     * @param args 调用参数
     */
    public McpSchema.CallToolResult callToolRequest(String name, Map<String, Object> args) {
        McpSchema.CallToolRequest callToolRequest = new McpSchema.CallToolRequest(name, args);
        McpSchema.CallToolResult result = executeWithRetry(c -> c.callTool(callToolRequest));

        if (result.isError() != null && result.isError()) {
            log.warn("The tool result is error: {}", result);
        }

        //方便调试看变量
        return result;
    }

    /// /////////////////////////////

    /**
     * 读取资源
     *
     * @param uri 资源地址
     */
    public ResourceResult readResource(String uri) {
        McpSchema.ReadResourceResult mcpResult = readResourceRequest(uri);
        List<ResourceBlock> resourceList = new ArrayList<>();

        if (mcpResult.contents() != null) {
            for (McpSchema.ResourceContents c : mcpResult.contents()) {
                if (c instanceof McpSchema.TextResourceContents) {
                    McpSchema.TextResourceContents tc = (McpSchema.TextResourceContents) c;

                    TextBlock textBlock = TextBlock.of(tc.text(), tc.mimeType());
                    if (Utils.isNotEmpty(tc.meta())) {
                        textBlock.metas().putAll(tc.meta());
                    }

                    resourceList.add(textBlock);
                } else if (c instanceof McpSchema.BlobResourceContents) {
                    McpSchema.BlobResourceContents bc = (McpSchema.BlobResourceContents) c;

                    BlobBlock blobBlock = BlobBlock.of(bc.blob(), bc.uri(), bc.mimeType());
                    if (Utils.isNotEmpty(bc.meta())) {
                        blobBlock.metas().putAll(bc.meta());
                    }
                    resourceList.add(blobBlock);
                }
            }
        }

        return new ResourceResult(resourceList);
    }

    /**
     * 读取资源
     *
     * @param uri 资源地址
     */
    public McpSchema.ReadResourceResult readResourceRequest(String uri) {
        McpSchema.ReadResourceRequest callToolRequest = new McpSchema.ReadResourceRequest(uri);
        McpSchema.ReadResourceResult result = executeWithRetry(c -> c.readResource(callToolRequest));

        //方便调试看变量
        return result;
    }

    /// /////////////////////////////

    /**
     * 获取提示语
     *
     * @param name 名字
     * @param args 参数
     */
    public PromptResult getPrompt(String name, Map<String, Object> args) {
        McpSchema.GetPromptResult mcpResult = getPromptRequest(name, args);
        List<ChatMessage> messages = new ArrayList<>();

        if (Utils.isNotEmpty(mcpResult.messages())) {
            for (McpSchema.PromptMessage pm : mcpResult.messages()) {
                if (pm.role() == McpSchema.Role.ASSISTANT) {
                    if (pm.content() instanceof McpSchema.TextContent) {
                        AssistantMessage msg = ChatMessage.ofAssistant(((McpSchema.TextContent) pm.content()).text());
                        messages.add(msg);
                    }
                } else if (pm.role() == McpSchema.Role.USER) {
                    if (pm.content() instanceof McpSchema.TextContent) {
                        UserMessage msg = ChatMessage.ofUser(((McpSchema.TextContent) pm.content()).text());
                        messages.add(msg);
                    } else {
                        ContentBlock aiMedia = toAiMedia(pm.content(), false);
                        if (aiMedia != null) {
                            UserMessage msg = ChatMessage.ofUser(aiMedia);
                            messages.add(msg);
                        }
                    }
                }
            }
        }

        return new PromptResult(messages);
    }

    /**
     * 获取提示语
     *
     * @param name 名字
     * @param args 参数
     */
    public McpSchema.GetPromptResult getPromptRequest(String name, Map<String, Object> args) {
        McpSchema.GetPromptRequest callToolRequest = new McpSchema.GetPromptRequest(name, args);
        McpSchema.GetPromptResult result = executeWithRetry(c -> c.getPrompt(callToolRequest));

        //方便调试看变量
        return result;
    }

    /// ///////////

    /**
     * 支持缓存获取
     */
    private <T> T getByCache(String key, Type type, Supplier<T> supplier) {
        if (clientProps.getCacheSeconds() > 0) {
            cacheLocker.lock(key);
            try {
                return cacheService.getOrStore(key, type, clientProps.getCacheSeconds(), supplier);
            } finally {
                cacheLocker.unlock(key);
            }
        } else {
            return supplier.get();
        }
    }

    /**
     * 获取函数工具（可用于模型绑定）
     */
    @Override
    public Collection<FunctionTool> getTools() {
        return getTools(null);
    }


    /**
     * 获取函数工具（可用于模型绑定）
     *
     * @param cursor 游标
     */
    public Collection<FunctionTool> getTools(String cursor) {
        return getByCache(cache_prefix_tools + cursor,
                Collection.class,
                () -> getToolsDo(cursor));
    }

    private Collection<FunctionTool> getToolsDo(String cursor) {
        List<FunctionTool> toolList = new ArrayList<>();

        McpSchema.ListToolsResult result = null;
        if (cursor == null) {
            result = executeWithRetry(c -> c.listTools());
        } else {
            result = executeWithRetry(c -> c.listTools(cursor));
        }

        for (McpSchema.Tool tool : result.tools()) {
            String name = tool.name();
            String title = tool.title();
            String description = tool.description();

            Boolean returnDirect = (tool.annotations() == null ? null : tool.annotations().returnDirect());
            if (returnDirect == null) {
                returnDirect = false;
            }

            String inputSchema = ONode.serialize(tool.inputSchema());
            String outputSchema = (tool.outputSchema() == null ? null : ONode.serialize(tool.outputSchema()));

            FunctionToolDesc functionDesc = new FunctionToolDesc(name);
            functionDesc.title(title);
            functionDesc.description(description);
            functionDesc.returnDirect(returnDirect);
            functionDesc.inputSchema(inputSchema);
            functionDesc.outputSchema(outputSchema);
            functionDesc.doHandle((args) -> callTool(name, args));

            if (Assert.isNotEmpty(tool.meta())) {
                functionDesc.meta().putAll(tool.meta());
            }

            toolList.add(functionDesc);
        }

        return toolList;
    }


    @Override
    public Collection<FunctionResource> getResources() {
        return getResources(null);
    }


    public Collection<FunctionResource> getResources(String cursor) {
        return getByCache(cache_prefix_resource + cursor,
                Collection.class,
                () -> getResourcesDo(cursor));
    }

    private Collection<FunctionResource> getResourcesDo(String cursor) {
        List<FunctionResource> resourceList = new ArrayList<>();

        McpSchema.ListResourcesResult result = null;
        if (cursor == null) {
            result = executeWithRetry(c -> c.listResources());
        } else {
            result = executeWithRetry(c -> c.listResources(cursor));
        }

        for (McpSchema.Resource resource : result.resources()) {
            String name = resource.name();
            String uri = resource.uri();
            String description = resource.description();

            FunctionResourceDesc functionDesc = new FunctionResourceDesc(name);
            functionDesc.description(description);
            functionDesc.uri(uri);
            functionDesc.mimeType(resource.mimeType());
            functionDesc.doHandle((reqUri) -> readResource(reqUri));

            if (Assert.isNotEmpty(resource.meta())) {
                functionDesc.meta().putAll(resource.meta());
            }

            resourceList.add(functionDesc);
        }

        return resourceList;
    }

    public Collection<FunctionResource> getResourceTemplates() {
        return getResourceTemplates(null);
    }


    public Collection<FunctionResource> getResourceTemplates(String cursor) {
        return getByCache(cache_prefix_resource_templates + cursor,
                Collection.class,
                () -> getResourceTemplatesDo(cursor));
    }

    private Collection<FunctionResource> getResourceTemplatesDo(String cursor) {
        List<FunctionResource> resourceList = new ArrayList<>();

        McpSchema.ListResourceTemplatesResult result = null;
        if (cursor == null) {
            result = executeWithRetry(c -> c.listResourceTemplates());
        } else {
            result = executeWithRetry(c -> c.listResourceTemplates(cursor));
        }

        for (McpSchema.ResourceTemplate resource : result.resourceTemplates()) {
            String name = resource.name();
            String uriTemplate = resource.uriTemplate();
            String description = resource.description();

            FunctionResourceDesc functionDesc = new FunctionResourceDesc(name);
            functionDesc.description(description);
            functionDesc.uri(uriTemplate);
            functionDesc.mimeType(resource.mimeType());
            functionDesc.doHandle((reqUri) -> readResource(reqUri));

            if (Assert.isNotEmpty(resource.meta())) {
                functionDesc.meta().putAll(resource.meta());
            }

            resourceList.add(functionDesc);
        }

        return resourceList;
    }

    @Override
    public Collection<FunctionPrompt> getPrompts() {
        return getPrompts(null);
    }


    public Collection<FunctionPrompt> getPrompts(String cursor) {
        return getByCache(cache_prefix_prompts + cursor,
                Collection.class,
                () -> getPromptsDo(cursor));
    }

    private Collection<FunctionPrompt> getPromptsDo(String cursor) {
        List<FunctionPrompt> promptList = new ArrayList<>();

        McpSchema.ListPromptsResult result = null;
        if (cursor == null) {
            result = executeWithRetry(c -> c.listPrompts());
        } else {
            result = executeWithRetry(c -> c.listPrompts(cursor));
        }

        for (McpSchema.Prompt prompt : result.prompts()) {
            String name = prompt.name();
            String description = prompt.description();

            FunctionPromptDesc functionDesc = new FunctionPromptDesc(name);
            functionDesc.description(description);
            for (McpSchema.PromptArgument p1 : prompt.arguments()) {
                functionDesc.paramAdd(p1.name(), p1.required(), p1.description());
            }

            functionDesc.doHandle((args) -> getPrompt(name, args));

            if (Assert.isNotEmpty(prompt.meta())) {
                functionDesc.meta().putAll(prompt.meta());
            }

            promptList.add(functionDesc);
        }

        return promptList;
    }

    /// /////////////////////////////


    /**
     * 根据 mcpServers 配置加载客户端
     *
     * @param uri 配置资源地址
     * @deprecated 3.3 {@link McpProviders#fromMcpServers(String)}
     */
    @Deprecated
    public static Map<String, McpClientProvider> fromMcpServers(String uri) throws IOException {
        return McpProviders.fromMcpServers(uri).getProviders();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private McpClientProperties props = new McpClientProperties();

        // for mcp

        public Builder name(String name) {
            props.setName(name);
            return this;
        }

        public Builder version(String version) {
            props.setVersion(version);
            return this;
        }

        public Builder channel(String channel) {
            props.setChannel(channel);
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            props.setRequestTimeout(requestTimeout);
            return this;
        }

        public Builder initializationTimeout(Duration initializationTimeout) {
            props.setInitializationTimeout(initializationTimeout);
            return this;
        }

        public Builder heartbeatInterval(Duration heartbeatInterval) {
            props.setHeartbeatInterval(heartbeatInterval);
            return this;
        }

        public Builder cacheSeconds(int cacheSeconds) {
            props.setCacheSeconds(cacheSeconds);
            return this;
        }

        public Builder customize(Consumer<McpClient.AsyncSpec> clientCustomizer) {
            props.setClientCustomizer(clientCustomizer);
            return this;
        }

        // for http

        public Builder url(String url) {
            props.setApiUrl(url);
            return this;
        }


        public Builder header(String name, String value) {
            props.getHeaders().put(name, value);
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            if (Utils.isNotEmpty(headers)) {
                props.getHeaders().putAll(headers);
            }
            return this;
        }

        public Builder timeout(Duration duration) {
            props.setTimeout(duration);
            return this;
        }

        public Builder httpTimeout(HttpTimeout httpTimeout) {
            props.setHttpTimeout(httpTimeout);
            return this;
        }

        public Builder httpProxy(Proxy httpProxy) {
            props.setHttpProxy(httpProxy);
            return this;
        }

        public Builder httpProxy(String host, int port) {
            return httpProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
        }

        public Builder httpSsl(HttpSslSupplier httpSslSupplier) {
            props.setHttpSsl(httpSslSupplier);
            return this;
        }

        public Builder httpFactory(HttpUtilsFactory httpUtilsFactory) {
            props.setHttpFactory(httpUtilsFactory);
            return this;
        }

        //for studio

        public Builder command(String command) {
            Assert.notNull(command, "The command can not be null");
            props.setCommand(command);
            return this;
        }

        public Builder args(String... args) {
            Assert.notNull(args, "The args can not be null");
            props.setArgs(Arrays.asList(args));
            return this;
        }

        public Builder args(List<String> args) {
            Assert.notNull(args, "The args can not be null");
            props.setArgs(new ArrayList<>(args));
            return this;
        }

        public Builder arg(String arg) {
            Assert.notNull(arg, "The arg can not be null");
            props.getArgs().add(arg);
            return this;
        }

        public Builder env(Map<String, String> env) {
            if (Utils.isNotEmpty(env)) {
                props.getEnv().putAll(env);
            }
            return this;
        }

        public Builder addEnvVar(String key, String value) {
            Assert.notNull(key, "The key can not be null");
            Assert.notNull(value, "The value can not be null");
            props.getEnv().put(key, value);
            return this;
        }

        /// ////////////

        /**
         * @deprecated 3.5 {@link #url(String)}
         *
         */
        @Deprecated
        public Builder apiUrl(String apiUrl) {
            props.setApiUrl(apiUrl);
            return this;
        }

        /**
         * @deprecated 3.5 {@link #header(String, String)}
         */
        @Deprecated
        public Builder apiKey(String apiKey) {
            props.setApiKey(apiKey);
            return this;
        }

        /***
         * @deprecated 3.5 {@link #header(String, String)}
         * */
        @Deprecated
        public Builder headerSet(String name, String value) {
            props.getHeaders().put(name, value);
            return this;
        }

        /***
         * @deprecated 3.5 {@link #headers(Map)}
         * */
        @Deprecated
        public Builder headerSet(Map<String, String> headers) {
            if (Utils.isNotEmpty(headers)) {
                props.getHeaders().putAll(headers);
            }
            return this;
        }

        /**
         * 服务端参数（用于 stdio）
         *
         * @deprecated 3.5 {@link #command(String)}
         */
        @Deprecated
        public Builder serverParameters(McpServerParameters serverParameters) {
            Assert.notNull(serverParameters, "The serverParameters can not be null");

            props.setCommand(serverParameters.getCommand());
            props.setArgs(serverParameters.getArgs());
            props.setEnv(serverParameters.getEnv());
            return this;
        }

        /**
         * 工具变更消费者
         */
        public Builder toolsChangeConsumer(Function<List<McpSchema.Tool>, Mono<Void>> toolsChangeConsumer) {
            props.setToolsChangeConsumer(toolsChangeConsumer);
            return this;
        }

        /**
         * 资源变更消费者
         */
        public Builder resourcesChangeConsumer(Function<List<McpSchema.Resource>, Mono<Void>> resourcesChangeConsumer) {
            props.setResourcesChangeConsumer(resourcesChangeConsumer);
            return this;
        }

        /**
         * 资源更新消费者
         */
        public Builder resourcesUpdateConsumer(Function<List<McpSchema.ResourceContents>, Mono<Void>> resourcesUpdateConsumer) {
            props.setResourcesUpdateConsumer(resourcesUpdateConsumer);
            return this;
        }

        /**
         * 提示语变更消费者
         */
        public Builder promptsChangeConsumer(Function<List<McpSchema.Prompt>, Mono<Void>> promptsChangeConsumer) {
            props.setPromptsChangeConsumer(promptsChangeConsumer);
            return this;
        }

        public McpClientProvider build() {
            return new McpClientProvider(props);
        }
    }
}