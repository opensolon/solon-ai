package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class TaskArtifactUpdateEvent {

    /**
     * ID is the ID of the task being updated
     */
    String id;

    /**
     * Artifact is the new or updated artifact for the task
     */
    Artifact artifact;

    /**
     * Final indicates if this is the final update for the task
     */
    Boolean finalUpdate;

    /**
     * Metadata is optional metadata associated with this update event
     */
    Map<String, Object> metadata;
}
