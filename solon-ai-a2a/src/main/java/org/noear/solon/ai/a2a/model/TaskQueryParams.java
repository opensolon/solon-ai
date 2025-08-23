package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
@Data
public class TaskQueryParams {

    /**
     * ID is the unique identifier of the task
     */
    String id;

    /**
     * Metadata is optional metadata to include with the operation
     */
    Map<String, Object> metadata;

    /**
     * HistoryLength is an optional parameter to specify how much history to retrieve
     */
    Integer historyLength;
}
