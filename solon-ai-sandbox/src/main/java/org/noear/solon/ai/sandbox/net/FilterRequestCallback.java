package org.noear.solon.ai.sandbox.net;

/**
 * Callback interface for filtering HTTP requests before they are forwarded.
 * Ports the filterRequest concept from sandbox-manager.ts / request-filter.ts.
 *
 * Implementations can inspect the request method, URL, and headers to decide
 * whether to allow or deny the request. Body filtering is optional and
 * depends on the implementation.
 */
public interface FilterRequestCallback {

    /**
     * Called before forwarding an HTTP/HTTPS request.
     *
     * @param method  the HTTP method (GET, POST, etc.)
     * @param url     the full URL of the request
     * @param headers the request headers (may be empty, never null)
     * @return true to allow the request, false to deny
     */
    boolean filter(String method, String url, java.util.Map<String, String> headers);
}
