package io.a2a_preview.server;

import io.a2a_preview.model.*;
import org.noear.snack.ONode;
import org.noear.solon.ai.a2a.model.*;
import org.noear.solon.annotation.*;
import org.noear.solon.web.sse.SseEmitter;
import org.noear.solon.web.sse.SseEvent;

import java.io.IOException;
import java.time.Instant;

/**
 * A2A REST controller for handling JSON-RPC requests
 */
public class A2AServerEndpoint {
    private final A2AServer server;

    public A2AServerEndpoint(A2AServer server){
        this.server = server;
    }

    /**
     * Handle JSON-RPC requests
     */
    @Post
    @Mapping("a2a")
    public JSONRPCResponse handleJsonRpcRequest(@Body JSONRPCRequest request) {
        System.err.println("handleJsonRpcRequest(), request = " + request);

        if (!"2.0".equals(request.getJsonrpc())) {
            JSONRPCError error = new JSONRPCError(
                    ErrorCode.INVALID_REQUEST.getValue(),
                    "Invalid JSON-RPC version",
                    null
            );

            JSONRPCResponse response = new JSONRPCResponse(
                    request.getId(),
                    "2.0",
                    null,
                    error
            );

            return response;
        }

        if ("message/send".equals(request.getMethod())) {
            return server.handleTaskSend(request);
        }

        if ("tasks/get".equals(request.getMethod())) {
            return server.handleTaskGet(request);
        }

        if ("tasks/cancel".equals(request.getMethod())) {
            return server.handleTaskCancel(request);
        }

        return new JSONRPCResponse(
                request.getId(),
                "2.0",
                null,
                new JSONRPCError(
                        ErrorCode.METHOD_NOT_FOUND.getValue(),
                        "Method not found",
                        null
                )
        );

    }

    /**
     * Handle streaming task requests (Server-Sent Events)
     */
    @Post
    @Mapping("a2a/streaming")
    public SseEmitter handleStreamingTask(@Body JSONRPCRequest request) {
        System.err.println("handleStreamingTask(), request = " + request);

        return new SseEmitter(-1).onInited(emitter -> {
            // Process task asynchronously
            try {
                if (!"message/send".equals(request.getMethod())) {
                    sendErrorEvent(emitter, request.getId(), ErrorCode.METHOD_NOT_FOUND, "Method not found");
                    return;
                }

                TaskSendParams params = ONode.load(request.getParams()).toObject(TaskSendParams.class);

                // Create initial status with timestamp
                TaskStatus initialStatus = new TaskStatus(
                        TaskState.WORKING,
                        null,  // No message initially
                        Instant.now().toString()
                );

                // Send initial status update
                TaskStatusUpdateEvent initialEvent = new TaskStatusUpdateEvent(
                        params.getId(),
                        initialStatus,
                        false,  // final
                        null    // metadata
                );

                SendTaskStreamingResponse initialResponse = new SendTaskStreamingResponse(
                        request.getId(),
                        "2.0",
                        initialEvent,
                        null
                );

                emitter.send(new SseEvent().name("task-complete").data(ONode.stringify(initialResponse)));

                // Process task
                JSONRPCResponse taskResponse = server.handleTaskSend(request);

                if (taskResponse.getError() != null) {
                    sendErrorEvent(emitter, request.getId(), ErrorCode.INTERNAL_ERROR, taskResponse.getError().getMessage());
                    return;
                }

                // Send final status update
                Task completedTask = (Task) taskResponse.getResult();
                TaskStatusUpdateEvent finalEvent = new TaskStatusUpdateEvent(
                        completedTask.getId(),
                        completedTask.getStatus(),
                        true,   // final
                        null    // metadata
                );

                SendTaskStreamingResponse finalResponse = new SendTaskStreamingResponse(
                        request.getId(),
                        "2.0",
                        finalEvent,
                        null
                );

                emitter.send(new SseEvent().name("task-update").data(ONode.stringify(finalResponse)));

                emitter.complete();

            } catch (Exception e) {
                sendErrorEvent(emitter, request.getId(), ErrorCode.INTERNAL_ERROR, e.getMessage());
            }
        });
    }

    /**
     * Get agent card information
     */
    @Mapping("/.well-known/agent.json")
    public AgentCard getAgentCard() {

        System.err.println("getAgentCard()");
        AgentCard agentCard = server.getAgentCard();
        return agentCard;
    }

    /**
     * Send error event
     */
    private void sendErrorEvent(SseEmitter emitter, Object requestId, ErrorCode code, String message) {
        try {
            A2AError error = new A2AError(code, message, null);
            SendTaskStreamingResponse errorResponse = new SendTaskStreamingResponse(
                    requestId,
                    "2.0",
                    null,
                    error
            );

            emitter.send(new SseEvent().name("error").data(ONode.stringify(errorResponse)));

            emitter.error(new RuntimeException(message));

        } catch (IOException e) {
            emitter.error(e);
        }
    }
}