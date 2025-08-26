package io.a2a_preview.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@AllArgsConstructor
@Data
public class SendTaskStreamingResponse {

    /**
     * ID is the request identifier. Can be a string, number, or null.
     * Responses must have the same ID as the request they relate to.
     * Notifications (requests without an expected response) should omit the ID or use null.
     */
    Object id;

    /**
     * JSONRPC specifies the JSON-RPC version. Must be "2.0"
     */
    String jsonrpc;

    /**
     * Result is the result of the method invocation. Can be TaskStatusUpdateEvent or TaskArtifactUpdateEvent
     * Should be null or omitted if an error occurred.
     */
    Object result;

    /**
     * Error is an error object if an error occurred during the request.
     * Required on failure. Should be null or omitted if the request was successful.
     */
    A2AError error;
}
