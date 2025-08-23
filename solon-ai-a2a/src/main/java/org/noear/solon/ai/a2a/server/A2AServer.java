package org.noear.solon.ai.a2a.server;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.noear.solon.ai.a2a.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A2AServer represents an A2A server instance
 */
public class A2AServer {

    private final AgentCard agentCard;
    private final TaskHandler handler;
    private final Map<String, Task> taskStore;
    private final Map<String, List<Message>> taskHistory;
    private final ObjectMapper objectMapper;

    public A2AServer(AgentCard agentCard, TaskHandler handler) {
        this.agentCard = agentCard;
        this.handler = handler;
        this.taskStore = new ConcurrentHashMap<>();
        this.taskHistory = new ConcurrentHashMap<>();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Handle task send request
     */
    public JSONRPCResponse handleTaskSend(JSONRPCRequest request) {
        try {

            TaskSendParams params = parseParams(request.getParams(), TaskSendParams.class);

            // Generate contextId if not provided
            String contextId = UUID.randomUUID().toString();

            // Create initial task status
            TaskStatus initialStatus = new TaskStatus(
                TaskState.WORKING,
                null,
                Instant.now().toString()
            );

            // Create new task with all required fields
            Task task = new Task(
                params.getId(),
                contextId,
                "task",
                initialStatus,
                null,
                null,
                params.getMetadata()
            );

            // Process task
            Task updatedTask = handler.handle(task, params.getMessage());

            // Store task and history
            taskStore.put(task.getId(), updatedTask);
            taskHistory.computeIfAbsent(task.getId(), k -> new CopyOnWriteArrayList<>())
                      .add(params.getMessage());

            return createSuccessResponse(request.getId(), updatedTask);

        } catch (Exception e) {
            return createErrorResponse(request.getId(), ErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * Handle task query request
     */
    public JSONRPCResponse handleTaskGet(JSONRPCRequest request) {
        try {
            TaskQueryParams params = parseParams(request.getParams(), TaskQueryParams.class);

            Task task = taskStore.get(params.getId());
            if (task == null) {
                return createErrorResponse(request.getId(), ErrorCode.TASK_NOT_FOUND, "Task not found");
            }

            // Include history if requested
            if (params.getHistoryLength() != null && params.getHistoryLength() > 0) {
                List<Message> history = getTaskHistory(params.getId());
                int limit = Math.min(params.getHistoryLength(), history.size());
                List<Message> limitedHistory = history.subList(Math.max(0, history.size() - limit), history.size());
                
                // Create task with history
                Task taskWithHistory = new Task(
                    task.getId(),
                    task.getContextId(),
                    task.getKind(),
                    task.getStatus(),
                    task.getArtifacts(),
                    limitedHistory,
                    task.getMetadata()
                );
                
                return createSuccessResponse(request.getId(), taskWithHistory);
            }

            return createSuccessResponse(request.getId(), task);

        } catch (Exception e) {
            return createErrorResponse(request.getId(), ErrorCode.INVALID_REQUEST, "Invalid parameters");
        }
    }

    /**
     * Handle task cancel request
     */
    public JSONRPCResponse handleTaskCancel(JSONRPCRequest request) {
        try {
            TaskIDParams params = parseParams(request.getParams(), TaskIDParams.class);

            Task task = taskStore.get(params.getId());
            if (task == null) {
                return createErrorResponse(request.getId(), ErrorCode.TASK_NOT_FOUND, "Task not found");
            }

            // Check if task can be canceled
            if (task.getStatus().getState() == TaskState.COMPLETED ||
                task.getStatus().getState() == TaskState.CANCELED ||
                task.getStatus().getState() == TaskState.FAILED) {
                return createErrorResponse(request.getId(), ErrorCode.TASK_NOT_CANCELABLE, "Task cannot be canceled");
            }

            // Create canceled status with timestamp
            TaskStatus canceledStatus = new TaskStatus(
                TaskState.CANCELED,
                null,  // No message
                Instant.now().toString()
            );

            // Update task status to canceled
            Task canceledTask = new Task(
                task.getId(),
                task.getContextId(),
                task.getKind(),
                canceledStatus,
                task.getArtifacts(),
                task.getHistory(),
                task.getMetadata()
            );
            
            taskStore.put(params.getId(), canceledTask);

            return createSuccessResponse(request.getId(), canceledTask);

        } catch (Exception e) {
            return createErrorResponse(request.getId(), ErrorCode.INVALID_REQUEST, "Invalid parameters");
        }
    }

    /**
     * Get agent card information
     */
    public AgentCard getAgentCard() {
        return agentCard;
    }

    /**
     * Get task history
     */
    public List<Message> getTaskHistory(String taskId) {
        return taskHistory.getOrDefault(taskId, new ArrayList<>());
    }

    /**
     * Parse request parameters
     */
//    private <T> T parseParams(Object params, Class<T> clazz) throws Exception {
//
//        String jsonStr = JSONUtil.toJsonStr(params);
//        System.err.println("parseParams(), jsonStr = " + jsonStr);
//        return JSONUtil.toBean(JSONUtil.toJsonStr(params), clazz);
//    }

    private <T> T parseParams(Object params, Class<T> clazz) throws Exception {
        return objectMapper.convertValue(params, clazz);
    }

    /**
     * Create success response
     */
    private JSONRPCResponse createSuccessResponse(Object id, Object result) {
        return new JSONRPCResponse(
            id,
            "2.0",
            result,
            null
        );
    }

    /**
     * Create error response
     */
    private JSONRPCResponse createErrorResponse(Object id, ErrorCode code, String message) {
        JSONRPCError error = new JSONRPCError(code.getValue(), message, null);
        return new JSONRPCResponse(
            id,
            "2.0",
            null,
            error
        );
    }
}
