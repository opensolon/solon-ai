package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@AllArgsConstructor
@Data
public class TaskStatus {

    /**
     * State is the current state of the task
     */
    TaskState state;

    /**
     * Message is an additional status update for the client
     */
    Message message;

    /**
     * Timestamp is the ISO 8601 datetime string when the status was recorded
     */
    String timestamp;
}
