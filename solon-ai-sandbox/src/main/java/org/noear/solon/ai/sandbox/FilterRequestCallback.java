package org.noear.solon.ai.sandbox;

import java.util.Map;

/**
 * Per-request filter callback for the forward proxy.
 * <p>
 * Called before forwarding an HTTP request. Return {@code true} to allow the
 * request, {@code false} to deny it. If this method throws, the request is
 * denied (fail-closed).
 * <p>
 * Ports {@code FilterRequestCallback} from request-filter.ts.
 */
@FunctionalInterface
public interface FilterRequestCallback {

    /**
     * Decide whether to allow or deny an outgoing HTTP request.
     *
     * @param method  the HTTP method (e.g. "GET", "POST")
     * @param url     the absolute request URL
     * @param headers the request headers (lowercase keys). Implementations may
     *                inspect but should not modify this map.
     * @return {@code true} to allow the request, {@code false} to deny it
     */
    boolean filter(String method, String url, Map<String, String> headers);
}
