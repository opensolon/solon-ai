package io.a2a_preview.server;


import io.a2a_preview.model.Message;
import io.a2a_preview.model.Task;

/**
 * TaskHandler is a functional interface for handling tasks
 */
@FunctionalInterface
public interface TaskHandler {
    /**
     * Handle a task
     * 
     * @param task the task to handle
     * @param message the message content
     * @return the processed task
     * @throws Exception exceptions during processing
     */
    Task handle(Task task, Message message) throws Exception;
} 