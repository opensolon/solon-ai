package org.noear.solon.ai.mcp.exception;

import org.noear.solon.exception.SolonException;

/**
 * @author noear
 * @since 3.1
 */
public class McpException extends SolonException {
    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }

    public McpException(Throwable cause) {
        super(cause);
    }
}
