package org.noear.solon.ai.a2a.model;

import lombok.Data;

import java.util.Map;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
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
