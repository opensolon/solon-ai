package io.a2a_preview.model;

import lombok.Data;

import java.util.Map;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Data
public class TaskIDParams {

    /**
     * ID is the unique identifier of the task
     */
   String id;

    /**
     * Metadata is optional metadata to include with the operation
     */
    Map<String, Object> metadata;
}
