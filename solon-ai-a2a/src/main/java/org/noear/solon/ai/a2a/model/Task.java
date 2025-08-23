package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@AllArgsConstructor
@Data
public class Task {


    /**
     * ID is the unique identifier for the task
     */
    String id;

    /**
     * ContextId is the server-generated id for contextual alignment across interactions
     */
    String contextId;

    /**
     * Kind is the event type - task for Tasks
     */
    String kind;

    /**
     * Status is the current status of the task
     */
    TaskStatus status;

    /**
     * Artifacts is the collection of artifacts created by the agent
     */
    List<Artifact> artifacts;

    /**
     * History is the message history for the task
     */
    List<Message> history;

    /**
     * Metadata is extension metadata
     */
    Map<String, Object> metadata;
}
