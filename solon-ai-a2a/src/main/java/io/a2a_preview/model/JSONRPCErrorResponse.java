package io.a2a_preview.model;

import lombok.Data;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Data
public class JSONRPCErrorResponse {

    /**
     * ID is the request identifier. Can be a string, number, or null.
     * Responses must have the same ID as the request they relate to.
     */
    Object id;
    /**
     * JSONRPC specifies the JSON-RPC version. Must be "2.0"
     */
    String jsonrpc;

    /**
     * Error is the error object
     */
    A2AError error;
}
