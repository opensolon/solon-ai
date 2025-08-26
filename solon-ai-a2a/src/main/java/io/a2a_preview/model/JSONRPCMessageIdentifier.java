package io.a2a_preview.model;

import lombok.Data;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@Data
public class JSONRPCMessageIdentifier {

    /**
     * ID is the request identifier. Can be a string, number, or null.
     * Responses must have the same ID as the request they relate to.
     * Notifications (requests without an expected response) should omit the ID or use null.
     */
    Object id;
}
