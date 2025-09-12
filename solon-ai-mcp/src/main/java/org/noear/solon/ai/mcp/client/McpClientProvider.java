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
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.mcp.server.prompt.FunctionPrompt;
import org.noear.solon.ai.mcp.server.prompt.FunctionPromptDesc;
import org.noear.solon.ai.mcp.server.prompt.PromptProvider;
import org.noear.solon.ai.mcp.server.resource.FunctionResource;
import org.noear.solon.ai.mcp.server.resource.FunctionResourceDesc;
import org.noear.solon.ai.mcp.server.resource.ResourceProvider;
import org.noear.solon.ai.media.Audio;
import org.noear.solon.ai.media.Image;
import org.noear.solon.ai.media.Text;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.exception.McpException;
import org.noear.solon.core.Props;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.data.cache.LocalCacheService;
import org.noear.solon.data.util.StringMutexLock;
import org.noear.solon.net.http.HttpSslSupplier;
import org.noear.solon.net.http.HttpTimeout;
import org.noear.solon.net.http.HttpUtilsBuilder;
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
                    .build());
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
                clientTransport = WebRxStreamableHttpTransport.builder(webBuilder)
                        .endpoint(endpoint)
                        .build();
            }
        }

        return McpClient.async(clientTransport)
                .clientInfo(new McpSchema.Implementation(clientProps.getName(), clientProps.getVersion()))
                .requestTimeout(clientProps.getRequestTimeout())
                .initializationTimeout(clientProps.getInitializationTimeout())
                .loggingConsumer(logging -> {
                    logging.setLevel(loggingLevel);
                    return Mono.empty();
                })
                .toolsChangeConsumer(this::onToolsChange)
                .resourcesChangeConsumer(this::onResourcesChange)
                .resourcesUpdateConsumer(this::onResourcesUpdate)
                .promptsChangeConsumer(this::onPromptsChange)
                //.withConnectOnInit(false) //初始化放到后面（更可控）
                .build();
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
                client.initialize().block();
            }

            return client;
        } finally {
            LOCKER.unlock();
        }
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
    private void heartbeatHandleDo() {
        if (heartbeatExecutor == null) {
            return;
        }

        //单次延后执行
        heartbeatExecutor.schedule(() -> {
            if (Thread.currentThread().isInterrupted()) {
                //如果中断
                return;
            }


            if (isClosed.get() == false) {
                //如果未关闭，尝试心跳
                if (isStarted.get()) {
                    //如果已开始，则心跳发送
                    RunUtil.runAndTry(() -> {
                        try {
                            getClient().ping().block();
                        } catch (Throwable ex) {
                            //如果失败，重置（下次会尝试重连）
                            this.reset();
                        }
                    });
                }

                heartbeatHandleDo();
            }
        }, this.clientProps.getHeartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
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
                client.close();
                client = null;
            }
        } finally {
            LOCKER.unlock();
        }
    }

    /// /////////////////////////////

    /**
     * 调用工具并转为文本
     *
     * @param name 工具名
     * @param args 调用参数
     */
    public Text callToolAsText(String name, Map<String, Object> args) {
        McpSchema.CallToolResult result = callTool(name, args);

        if (Utils.isEmpty(result.getContent())) {
            return null;
        } else {
            McpSchema.Content tmp = result.getContent().get(0);

            if (tmp instanceof McpSchema.TextContent) {
                return Text.of(false, ((McpSchema.TextContent) tmp).getText());
            } else {
                throw new IllegalArgumentException("The tool result content is not a text content.");
            }
        }
    }

    /**
     * 调用工具并转为图像
     *
     * @param name 工具名
     * @param args 调用参数
     */
    public Image callToolAsImage(String name, Map<String, Object> args) {
        McpSchema.CallToolResult result = callTool(name, args);

        if (Utils.isEmpty(result.getContent())) {
            return null;
        } else {
            McpSchema.Content tmp = result.getContent().get(0);

            if (tmp instanceof McpSchema.ImageContent) {
                McpSchema.ImageContent imageContent = (McpSchema.ImageContent) tmp;
                return Image.ofBase64(imageContent.getData(), imageContent.getMimeType());
            } else {
                throw new IllegalArgumentException("The tool result content is not a image content.");
            }
        }
    }

    /**
     * 调用工具并转为音频
     *
     * @param name 工具名
     * @param args 调用参数
     */
    public Audio callToolAsAudio(String name, Map<String, Object> args) {
        McpSchema.CallToolResult result = callTool(name, args);

        if (Utils.isEmpty(result.getContent())) {
            return null;
        } else {
            McpSchema.Content tmp = result.getContent().get(0);

            if (tmp instanceof McpSchema.AudioContent) {
                McpSchema.AudioContent audioContent = (McpSchema.AudioContent) tmp;
                return Audio.ofBase64(audioContent.getData(), audioContent.getMimeType());
            } else {
                throw new IllegalArgumentException("The tool result content is not a audio content.");
            }
        }
    }

    /**
     * 调用工具
     *
     * @param name 工具名
     * @param args 调用参数
     */
    public McpSchema.CallToolResult callTool(String name, Map<String, Object> args) {
        try {
            McpSchema.CallToolRequest callToolRequest = new McpSchema.CallToolRequest(name, args);
            McpSchema.CallToolResult result = getClient().callTool(callToolRequest).block();

            if (result.getIsError() == null || result.getIsError() == false) {
                return result;
            } else {
                if (Utils.isEmpty(result.getContent())) {
                    throw new McpException("Call Toll Failed");
                } else {
                    throw new McpException(result.getContent().get(0).toString());
                }
            }
        } catch (RuntimeException ex) {
            this.reset();
            throw ex;
        }
    }

    /// /////////////////////////////

    /**
     * 读取资源
     *
     * @param uri 资源地址
     */
    public Text readResourceAsText(String uri) {
        McpSchema.ReadResourceResult result = readResource(uri);

        if (Utils.isEmpty(result.getContents())) {
            return null;
        } else {
            McpSchema.ResourceContents tmp = result.getContents().get(0);

            if (tmp instanceof McpSchema.TextResourceContents) {
                McpSchema.TextResourceContents textContents = (McpSchema.TextResourceContents) tmp;
                return Text.of(false, textContents.getText(), textContents.getMimeType());
            } else {
                McpSchema.BlobResourceContents blobContents = (McpSchema.BlobResourceContents) tmp;
                return Text.of(true, blobContents.getBlob(), blobContents.getMimeType());
            }
        }
    }

    /**
     * 读取资源
     *
     * @param uri 资源地址
     */
    public McpSchema.ReadResourceResult readResource(String uri) {
        try {
            McpSchema.ReadResourceRequest callToolRequest = new McpSchema.ReadResourceRequest(uri);
            McpSchema.ReadResourceResult result = getClient().readResource(callToolRequest).block();

            if (Utils.isEmpty(result.getContents())) {
                throw new McpException("Read resource Failed");
            } else {
                return result;
            }
        } catch (RuntimeException ex) {
            this.reset();
            throw ex;
        }
    }

    /// /////////////////////////////

    /**
     * 获取提示语
     *
     * @param name 名字
     * @param args 参数
     */
    public List<ChatMessage> getPromptAsMessages(String name, Map<String, Object> args) {
        List<ChatMessage> tmp = new ArrayList<>();

        McpSchema.GetPromptResult result = getPrompt(name, args);

        for (McpSchema.PromptMessage pm : result.getMessages()) {
            McpSchema.Content content = pm.getContent();
            if (pm.getRole() == McpSchema.Role.ASSISTANT) {
                if (content instanceof McpSchema.TextContent) {
                    tmp.add(ChatMessage.ofAssistant(((McpSchema.TextContent) content).getText()));
                }
            } else {
                if (content instanceof McpSchema.TextContent) {
                    tmp.add(ChatMessage.ofUser(((McpSchema.TextContent) content).getText()));
                } else if (content instanceof McpSchema.ImageContent) {
                    McpSchema.ImageContent imageContent = ((McpSchema.ImageContent) content);
                    String contentData = imageContent.getData();

                    if (contentData.contains("://")) {
                        tmp.add(ChatMessage.ofUser(Image.ofUrl(contentData)));
                    } else {
                        tmp.add(ChatMessage.ofUser(Image.ofBase64(contentData, imageContent.getMimeType())));
                    }
                }
            }
        }

        return tmp;
    }

    /**
     * 获取提示语
     *
     * @param name 名字
     * @param args 参数
     */
    public McpSchema.GetPromptResult getPrompt(String name, Map<String, Object> args) {
        try {
            McpSchema.GetPromptRequest callToolRequest = new McpSchema.GetPromptRequest(name, args);
            McpSchema.GetPromptResult result = getClient().getPrompt(callToolRequest).block();

            if (Utils.isEmpty(result.getMessages())) {
                throw new McpException("Read resource Failed");
            } else {
                return result;
            }
        } catch (RuntimeException ex) {
            this.reset();
            throw ex;
        }
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
            result = getClient().listTools().block();
        } else {
            result = getClient().listTools(cursor).block();
        }

        for (McpSchema.Tool tool : result.getTools()) {
            String name = tool.getName();
            String title = tool.getTitle();
            String description = tool.getDescription();
            Boolean returnDirect = (tool.getAnnotations() == null ? false : tool.getAnnotations().getReturnDirect());
            String inputSchema = ONode.load(tool.getInputSchema()).toJson();
            String outputSchema = (tool.getOutputSchema() == null ? null : ONode.load(tool.getOutputSchema()).toJson());

            FunctionToolDesc functionRefer = new FunctionToolDesc(
                    name,
                    title,
                    description,
                    returnDirect,
                    inputSchema,
                    outputSchema,
                    args -> callToolAsText(name, args).getContent());

            toolList.add(functionRefer);
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
            result = getClient().listResources().block();
        } else {
            result = getClient().listResources(cursor).block();
        }

        for (McpSchema.Resource resource : result.getResources()) {
            String name = resource.getName();
            String uri = resource.getUri();
            String description = resource.getDescription();

            FunctionResourceDesc functionDesc = new FunctionResourceDesc(name);
            functionDesc.description(description);
            functionDesc.uri(uri);
            functionDesc.mimeType(resource.getMimeType());
            functionDesc.doHandle((reqUri) -> readResourceAsText(reqUri));


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
            result = getClient().listResourceTemplates().block();
        } else {
            result = getClient().listResourceTemplates(cursor).block();
        }

        for (McpSchema.ResourceTemplate resource : result.getResourceTemplates()) {
            String name = resource.getName();
            String uriTemplate = resource.getUriTemplate();
            String description = resource.getDescription();

            FunctionResourceDesc functionDesc = new FunctionResourceDesc(name);
            functionDesc.description(description);
            functionDesc.uri(uriTemplate);
            functionDesc.mimeType(resource.getMimeType());
            functionDesc.doHandle((reqUri) -> readResourceAsText(reqUri));


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
            result = getClient().listPrompts().block();
        } else {
            result = getClient().listPrompts(cursor).block();
        }

        for (McpSchema.Prompt prompt : result.getPrompts()) {
            String name = prompt.getName();
            String description = prompt.getDescription();

            FunctionPromptDesc functionDesc = new FunctionPromptDesc(name);
            functionDesc.description(description);
            for (McpSchema.PromptArgument p1 : prompt.getArguments()) {
                functionDesc.paramAdd(p1.getName(), p1.getRequired(), p1.getDescription());
            }

            functionDesc.doHandle((args) -> getPromptAsMessages(name, args));


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
         *
         */
        public Builder toolsChangeConsumer(Function<List<McpSchema.Tool>, Mono<Void>> toolsChangeConsumer) {
            props.setToolsChangeConsumer(toolsChangeConsumer);
            return this;
        }

        /**
         * 资源变更消费者
         *
         */
        public Builder resourcesChangeConsumer(Function<List<McpSchema.Resource>, Mono<Void>> resourcesChangeConsumer) {
            props.setResourcesChangeConsumer(resourcesChangeConsumer);
            return this;
        }

        /**
         * 资源更新消费者
         *
         */
        public Builder resourcesUpdateConsumer(Function<List<McpSchema.ResourceContents>, Mono<Void>> resourcesUpdateConsumer) {
            props.setResourcesUpdateConsumer(resourcesUpdateConsumer);
            return this;
        }

        /**
         * 提示语变更消费者
         *
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