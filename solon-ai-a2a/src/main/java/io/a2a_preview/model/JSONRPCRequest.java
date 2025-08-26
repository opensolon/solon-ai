package io.a2a_preview.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
@Data
public class JSONRPCRequest {

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
     * Method is the name of the method to be invoked
     */
    String method;

    /**
     * Params are the parameters for the method
     */
    Object params;
}
