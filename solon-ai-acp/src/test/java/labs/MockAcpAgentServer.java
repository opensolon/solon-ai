/*
 * Copyright 2025-2026 the original author or authors.
 */

package labs;

import com.agentclientprotocol.sdk.agent.transport.WebSocketSolonAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCResponse;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.noear.solon.Solon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Mock ACP Agent 服务端
 * 用于测试 ACP 客户端的完整流程
 *
 * @author test
 */
public class MockAcpAgentServer {

    private static final Logger logger = LoggerFactory.getLogger(MockAcpAgentServer.class);

    private final String host;
    private final int port;
    private final String path;
    private final McpJsonMapper jsonMapper;
    private WebSocketSolonAcpAgentTransport agentTransport;
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    private static boolean solonStarted = false;
    private static int solonPort = -1;

    public MockAcpAgentServer(String host, int port) {
        this(host, port, "/acp");
    }

    public MockAcpAgentServer(String host, int port, String path) {
        this.host = host;
        this.port = port;
        this.path = path;
        this.jsonMapper = McpJsonMapper.getDefault();
    }

    /**
     * 启动 Mock 服务端
     */
    public void start() {
        // 启动 Solon 服务器（如果尚未启动或端口不同）
        if (!solonStarted || solonPort != port) {
            Solon.start(MockAcpAgentServer.class, new String[]{
                    "--server.port=" + port
            }, builder -> {
                builder.enableWebSocket(true);
            });
            solonStarted = true;
            solonPort = port;
            logger.info("Solon 服务器已启动，端口: {}", port);
        }

        // 创建 Agent Transport
        agentTransport = new WebSocketSolonAcpAgentTransport(path, jsonMapper);

        // 设置异常处理器
        agentTransport.setExceptionHandler(ex ->
                logger.error("Agent 处理异常: {}", ex.getMessage(), ex));

        // 启动 Agent 并设置请求处理器
        Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = this::handleRequest;

        agentTransport.start(handler)
                .doOnSuccess(v -> logger.info("Agent Transport 已启动，路径: {}", path))
                .doOnError(e -> logger.error("Agent Transport 启动失败", e))
                .block(Duration.ofSeconds(10));

        // 等待服务完全启动
        sleep(500);
        logger.info("Mock Agent 服务端已就绪: ws://{}:{}{}", host, port, path);
    }

    /**
     * 停止 Mock 服务端
     */
    public void stop() {
        if (agentTransport != null) {
            try {
                agentTransport.closeGracefully().block(Duration.ofSeconds(5));
                logger.info("Agent Transport 已关闭");
            } catch (Exception e) {
                logger.warn("关闭 Agent Transport 时出错: {}", e.getMessage());
            }
            agentTransport = null;
        }
        sessions.clear();
    }

    /**
     * 处理客户端请求
     */
    private Mono<JSONRPCMessage> handleRequest(Mono<JSONRPCMessage> msgMono) {
        return msgMono.flatMap(msg -> {
            if (msg instanceof JSONRPCRequest) {
                JSONRPCRequest request = (JSONRPCRequest) msg;
                int reqNum = requestCounter.incrementAndGet();
                logger.info("收到请求 #{}: method={}, id={}", reqNum, request.method(), request.id());

                return handleRequestByMethod(request);
            } else if (msg instanceof JSONRPCNotification) {
                logger.info("收到通知: {}", msg.getClass().getSimpleName());
                return Mono.empty();
            } else {
                logger.warn("未知消息类型: {}", msg.getClass().getSimpleName());
                return Mono.empty();
            }
        });
    }

    /**
     * 根据方法类型分发处理
     */
    private Mono<JSONRPCMessage> handleRequestByMethod(JSONRPCRequest request) {
        switch (request.method()) {
            case AcpSchema.METHOD_INITIALIZE:
                return handleInitialize(request);
            case AcpSchema.METHOD_SESSION_NEW:
                return handleNewSession(request);
            case AcpSchema.METHOD_SESSION_LOAD:
                return handleLoadSession(request);
            case AcpSchema.METHOD_SESSION_PROMPT:
                return handlePrompt(request);
            case AcpSchema.METHOD_SESSION_CANCEL:
                return handleCancelSession(request);
            default:
                logger.warn("未知方法: {}", request.method());
                return Mono.just(createErrorResponse(request.id(), -32601, "Method not found: " + request.method()));
        }
    }

    /**
     * 处理 initialize 请求
     */
    private Mono<JSONRPCMessage> handleInitialize(JSONRPCRequest request) {
        logger.info("处理 initialize 请求");

        AcpSchema.InitializeResponse response = new AcpSchema.InitializeResponse(
                1, // protocol version
                new AcpSchema.AgentCapabilities(),
                null // authMethods
        );

        return Mono.just(createResponse(request.id(), response));
    }

    /**
     * 处理 newSession 请求
     */
    private Mono<JSONRPCMessage> handleNewSession(JSONRPCRequest request) {
        logger.info("处理 newSession 请求");

        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new SessionInfo(sessionId));

        // NewSessionResponse 需要 sessionId, modes, models 三个参数
        AcpSchema.NewSessionResponse response = new AcpSchema.NewSessionResponse(
                sessionId,
                null, // modes
                null  // models
        );

        return Mono.just(createResponse(request.id(), response));
    }

    /**
     * 处理 loadSession 请求
     */
    private Mono<JSONRPCMessage> handleLoadSession(JSONRPCRequest request) {
        logger.info("处理 loadSession 请求");

        // 简单实现：创建新会话
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new SessionInfo(sessionId));

        // LoadSessionResponse 需要 modes 和 models
        AcpSchema.LoadSessionResponse response = new AcpSchema.LoadSessionResponse(
                null, // modes
                null  // models
        );

        return Mono.just(createResponse(request.id(), response));
    }

    /**
     * 处理 prompt 请求
     */
    private Mono<JSONRPCMessage> handlePrompt(JSONRPCRequest request) {
        logger.info("处理 prompt 请求");

        // 从 params 中提取信息
        Map<String, Object> paramsMap = (Map<String, Object>) request.params();
        String sessionId = (String) paramsMap.get("sessionId");

        SessionInfo session = sessions.get(sessionId);

        if (session == null) {
            logger.warn("会话不存在: {}", sessionId);
            return Mono.just(createErrorResponse(request.id(), -32602, "Session not found: " + sessionId));
        }

        // 提取用户输入
        String userPrompt = extractUserPrompt(paramsMap);
        logger.info("用户输入: {}", userPrompt);

        // 生成响应流
        return generatePromptResponse(request.id(), sessionId, userPrompt);
    }

    /**
     * 处理 cancel 请求
     */
    private Mono<JSONRPCMessage> handleCancelSession(JSONRPCRequest request) {
        logger.info("处理 cancel 请求");

        Map<String, Object> paramsMap = (Map<String, Object>) request.params();
        String sessionId = (String) paramsMap.get("sessionId");

        SessionInfo session = sessions.remove(sessionId);
        if (session != null) {
            session.cancel();
            logger.info("会话已取消: {}", sessionId);
        }

        return Mono.empty(); // cancel 不返回响应
    }

    /**
     * 生成 prompt 响应
     */
    private Mono<JSONRPCMessage> generatePromptResponse(Object requestId, String sessionId, String userPrompt) {
        SessionInfo session = sessions.get(sessionId);

        // 模拟 AI 思考和响应
        String thought = "收到用户消息: \"" + userPrompt + "\"，正在思考...";
        String response = "你好！我是 Mock Agent。我收到你的消息: \"" + userPrompt + "\"。这是一个模拟响应。";

        List<JSONRPCNotification> notifications = new ArrayList<>();

        // 添加思考块通知
        if (session != null) {
            notifications.add(createSessionUpdateNotification(
                    sessionId,
                    new AcpSchema.AgentThoughtChunk(
                            "agent_thought_chunk",
                            new AcpSchema.TextContent(thought)
                    )
            ));
        }

        // 添加消息块通知
        if (session != null) {
            notifications.add(createSessionUpdateNotification(
                    sessionId,
                    new AcpSchema.AgentMessageChunk(
                            "agent_message_chunk",
                            new AcpSchema.TextContent(response)
                    )
            ));
        }

        // 先发送所有通知，然后返回最终响应
        AcpSchema.PromptResponse finalResponse = new AcpSchema.PromptResponse(
                AcpSchema.StopReason.END_TURN
        );

        // 使用 Flux 发送通知序列（广播给所有客户端）
        return Flux.fromIterable(notifications)
                .concatMap(notification -> {
                    logger.info("发送通知: {}", notification.params().getClass().getSimpleName());
                    return agentTransport.sendMessage(notification);
                })
                .then(Mono.just(createResponse(requestId, finalResponse)));
    }

    /**
     * 从 params 中提取用户输入
     */
    private String extractUserPrompt(Map<String, Object> paramsMap) {
        Object promptObj = paramsMap.get("prompt");
        if (promptObj instanceof List) {
            List<?> promptList = (List<?>) promptObj;
            for (Object item : promptList) {
                if (item instanceof Map) {
                    Map<?, ?> itemMap = (Map<?, ?>) item;
                    if ("text".equals(itemMap.get("type"))) {
                        return (String) itemMap.get("text");
                    }
                }
            }
        }
        return "";
    }

    /**
     * 创建 JSON-RPC 响应
     */
    private JSONRPCResponse createResponse(Object id, Object result) {
        return new JSONRPCResponse(
                AcpSchema.JSONRPC_VERSION,
                id,
                result,
                null
        );
    }

    /**
     * 创建 JSON-RPC 错误响应
     */
    private JSONRPCResponse createErrorResponse(Object id, int code, String message) {
        return new JSONRPCResponse(
                AcpSchema.JSONRPC_VERSION,
                id,
                null,
                new AcpSchema.JSONRPCError(code, message, null)
        );
    }

    /**
     * 创建 SessionUpdate 通知
     */
    private JSONRPCNotification createSessionUpdateNotification(String sessionId, AcpSchema.SessionUpdate update) {
        return new JSONRPCNotification(
                AcpSchema.METHOD_SESSION_UPDATE,
                new AcpSchema.SessionNotification(sessionId, update)
        );
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 会话信息
     */
    private static class SessionInfo {
        private final String sessionId;
        private volatile boolean cancelled = false;

        SessionInfo(String sessionId) {
            this.sessionId = sessionId;
        }

        void cancel() {
            this.cancelled = true;
        }

        boolean isCancelled() {
            return cancelled;
        }
    }
}