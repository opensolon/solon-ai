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
