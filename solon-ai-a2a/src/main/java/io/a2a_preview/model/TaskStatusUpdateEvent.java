package io.a2a_preview.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@AllArgsConstructor
@Data
public class TaskStatusUpdateEvent {

    /**
     * ID is the ID of the task being updated
     */
    String id;

    /**
     * Status is the new status of the task
     */
    TaskStatus status;

    /**
     * Final indicates if this is the final update for the task
     */
    Boolean finalUpdate;

    /**
     * Metadata is optional metadata associated with this update event
     */
    Map<String, Object> metadata;
}
