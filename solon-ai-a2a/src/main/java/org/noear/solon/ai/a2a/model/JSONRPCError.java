package org.noear.solon.ai.a2a.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author by HaiTao.Wang on 2025/8/21.
 */
@AllArgsConstructor
@Data
public class JSONRPCError {

    /**
     * Code is a number indicating the error type that occurred
     */
    int code;

    /**
     * Message is a string providing a short description of the error
     */
    String message;

    /**
     * Data is optional additional data about the error
     */
    Object data;
}
