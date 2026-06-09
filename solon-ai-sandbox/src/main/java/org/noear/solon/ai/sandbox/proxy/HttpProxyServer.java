package org.noear.solon.ai.sandbox.proxy;

import org.noear.solon.ai.sandbox.FilterRequestCallback;
import org.noear.solon.ai.sandbox.SandboxAskCallback;
import org.noear.solon.ai.sandbox.SandboxLog;
import org.noear.solon.ai.sandbox.config.SandboxRuntimeConfig;
import org.noear.solon.ai.sandbox.net.NetworkFilter;
import org.noear.solon.ai.sandbox.net.ParentProxyResolver;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HttpProxyServer {

    // ---- Hop-by-hop headers (RFC 7230 §6.1) that must NOT be forwarded ----
    private static final Set<String> HOP_BY_HOP = new HashSet<>(Arrays.asList(
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "proxy-connection",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade"
    ));

    private static final byte[] RESPONSE_403_BLOCKED = (
        "HTTP/1.1 403 Forbidden\r\n" +
        "Content-Type: text/plain\r\n" +
        "X-Proxy-Error: blocked-by-sandbox-runtime\r\n" +
        "\r\n" +
        "Connection blocked by network policy"
    ).getBytes();

    private final SandboxRuntimeConfig config;
    private final SandboxAskCallback callback;
    private final ParentProxyResolver parentProxyResolver;
    private final FilterRequestCallback filterRequestCallback;
    private ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public HttpProxyServer(SandboxRuntimeConfig config, SandboxAskCallback callback,
                           ParentProxyResolver parentProxyResolver,
                           FilterRequestCallback filterRequestCallback) {
        this.config = config;
        this.callback = callback;
        this.parentProxyResolver = parentProxyResolver;
        this.filterRequestCallback = filterRequestCallback;
    }

    /** Backwards-compatible constructor (no request-level filter). */
    public HttpProxyServer(SandboxRuntimeConfig config, SandboxAskCallback callback,
                           ParentProxyResolver parentProxyResolver) {
        this(config, callback, parentProxyResolver, null);
    }

    // ------------------------------------------------------------------ start / stop

    public int start(int port) throws IOException {
        serverSocket = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
        executor.submit(this::acceptLoop);
        return serverSocket.getLocalPort();
    }

    public int start() throws IOException {
        return start(0);
    }

    public void stop() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            SandboxLog.error("Error closing HTTP proxy: " + e.getMessage());
        }
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ accept loop

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                executor.submit(() -> handleClient(client));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    SandboxLog.error("Accept error: " + e.getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------------------ client handler

    private void handleClient(Socket client) {
        try {
            InputStream rawIn = client.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(rawIn));
            OutputStream out = client.getOutputStream();

            String requestLine = reader.readLine();
            if (requestLine == null) { client.close(); return; }

            // Read headers into a map
            Map<String, String> headers = new LinkedHashMap<>();
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                int colonIdx = headerLine.indexOf(':');
                if (colonIdx > 0) {
                    String key = headerLine.substring(0, colonIdx).trim().toLowerCase();
                    String value = headerLine.substring(colonIdx + 1).trim();
                    headers.put(key, value);
                }
            }

            if (requestLine.startsWith("CONNECT")) {
                handleConnect(client, requestLine, out);
            } else {
                handleHttp(client, requestLine, out, headers, rawIn);
            }
        } catch (IOException e) {
            SandboxLog.error("Client handler error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException e) { /* ignore */ }
        }
    }

    // ------------------------------------------------------------------ CONNECT (HTTPS tunnel)

    private void handleConnect(Socket client, String requestLine, OutputStream out) throws IOException {
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
            return;
        }

        String target = parts[1];
        String hostname;
        int port;

        if (target.startsWith("[")) {
            int bracketEnd = target.indexOf(']');
            if (bracketEnd < 0) {
                out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
                return;
            }
            hostname = target.substring(1, bracketEnd);
            String portStr = target.substring(bracketEnd + 1);
            if (portStr.startsWith(":")) {
                try {
                    port = Integer.parseInt(portStr.substring(1));
                } catch (NumberFormatException e) {
                    out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
                    return;
                }
            } else {
                port = 443;
            }
        } else {
            String[] hp = target.split(":");
            hostname = hp[0];
            port = hp.length > 1 ? Integer.parseInt(hp[1]) : 443;
        }

        // Validate host before filtering
        if (!org.noear.solon.ai.sandbox.net.HostUtils.isValidHost(hostname)) {
            SandboxLog.error("Denying CONNECT to malformed host: " + hostname + ":" + port);
            out.write(("HTTP/1.1 403 Forbidden\r\n" +
                "Content-Type: text/plain\r\n" +
                "X-Proxy-Error: blocked-by-sandbox-runtime\r\n" +
                "\r\n" +
                "Connection blocked - invalid host").getBytes());
            return;
        }

        if (!NetworkFilter.filter(port, hostname, config, callback)) {
            out.write(("HTTP/1.1 403 Forbidden\r\n" +
                "Content-Type: text/plain\r\n" +
                "X-Proxy-Error: blocked-by-sandbox-runtime\r\n" +
                "\r\n" +
                "Connection blocked by network policy").getBytes());
            return;
        }

        // filterRequest callback for CONNECT
        if (filterRequestCallback != null) {
            boolean allowed;
            try {
                // For CONNECT we synthesize a URL so the filter callback can inspect it
                String connectUrl = "connect://" + hostname + ":" + port;
                allowed = filterRequestCallback.filter("CONNECT", connectUrl, Collections.emptyMap());
            } catch (Exception e) {
                SandboxLog.error("filterRequest threw for CONNECT " + hostname + ":" + port + ": " + e.getMessage());
                allowed = false;
            }
            if (!allowed) {
                SandboxLog.debug("filterRequest denied CONNECT " + hostname + ":" + port);
                out.write(RESPONSE_403_BLOCKED);
                return;
            }
        }

        Socket upstream = null;
        try {
            // If parent proxy is configured, connect through it
            if (parentProxyResolver != null && parentProxyResolver.getHttpProxyUrl() != null
                && !parentProxyResolver.shouldBypassProxy(hostname, port)) {
                try {
                    java.net.URI proxyUri = new java.net.URI(parentProxyResolver.getHttpProxyUrl());
                    Socket proxySocket = new Socket(proxyUri.getHost(), proxyUri.getPort());
                    OutputStream proxyOut = proxySocket.getOutputStream();
                    // Send CONNECT request to parent proxy
                    proxyOut.write(("CONNECT " + hostname + ":" + port + " HTTP/1.1\r\nHost: " + hostname + ":" + port + "\r\n\r\n").getBytes());
                    proxyOut.flush();
                    // Read proxy response
                    BufferedReader proxyReader = new BufferedReader(new InputStreamReader(proxySocket.getInputStream()));
                    String proxyResponse = proxyReader.readLine();
                    if (proxyResponse != null && proxyResponse.contains("200")) {
                        // Skip remaining headers
                        String line;
                        while ((line = proxyReader.readLine()) != null && !line.isEmpty()) {}
                        upstream = proxySocket;
                    } else {
                        proxySocket.close();
                        out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes());
                        return;
                    }
                } catch (Exception e) {
                    SandboxLog.error("Parent proxy CONNECT failed: " + e.getMessage());
                    out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes());
                    return;
                }
            } else {
                upstream = new Socket();
                upstream.connect(new java.net.InetSocketAddress(hostname, port), 30000);
            }
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
            out.flush();
            pipe(client, upstream);
        } catch (IOException e) {
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes());
            SandboxLog.error("CONNECT tunnel failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Plain HTTP

    private void handleHttp(Socket client, String requestLine, OutputStream out,
                            Map<String, String> headers, InputStream rawIn) throws IOException {
        // Parse HTTP request
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
            return;
        }

        String method = parts[0];
        String urlStr = parts[1];

        try {
            URL url = new URL(urlStr);
            String hostname = url.getHost();
            int port = url.getPort() > 0 ? url.getPort() : url.getDefaultPort();

            if (!NetworkFilter.filter(port, hostname, config, callback)) {
                out.write(RESPONSE_403_BLOCKED);
                return;
            }

            // --- filterRequest callback ---
            if (filterRequestCallback != null) {
                boolean allowed;
                try {
                    allowed = filterRequestCallback.filter(method, urlStr, headers);
                } catch (Exception e) {
                    SandboxLog.error("filterRequest threw for " + method + " " + urlStr + ": " + e.getMessage());
                    allowed = false;
                }
                if (!allowed) {
                    SandboxLog.debug("filterRequest denied " + method + " " + urlStr);
                    out.write(RESPONSE_403_BLOCKED);
                    return;
                }
            }

            // --- Strip hop-by-hop headers before forwarding ---
            Map<String, String> fwdHeaders = stripHopByHop(headers);
            // Set Host header to the target host
            fwdHeaders.put("host", url.getHost() + (url.getPort() > 0 ? ":" + url.getPort() : ""));

            // --- Decide upstream route: parent proxy or direct ---
            boolean useParentProxy = parentProxyResolver != null
                && parentProxyResolver.getHttpProxyUrl() != null
                && !parentProxyResolver.shouldBypassProxy(hostname, port);

            if (useParentProxy) {
                // Route through parent HTTP proxy using raw socket
                URI proxyUri = new URI(parentProxyResolver.getHttpProxyUrl());
                String proxyHost = proxyUri.getHost();
                int proxyPort = proxyUri.getPort() > 0 ? proxyUri.getPort()
                    : (proxyUri.getScheme() != null && proxyUri.getScheme().equals("https") ? 443 : 80);

                Socket proxySocket = new Socket(proxyHost, proxyPort);
                try {
                    OutputStream proxyOut = proxySocket.getOutputStream();

                    // Build the request to send to parent proxy
                    // For a forward proxy the request line uses the absolute URI
                    String path = url.getPath();
                    if (url.getQuery() != null) path += "?" + url.getQuery();
                    if (path == null || path.isEmpty()) path = "/";

                    StringBuilder reqBuilder = new StringBuilder();
                    reqBuilder.append(method).append(" ").append(url.getProtocol())
                        .append("://").append(url.getHost());
                    if (url.getPort() > 0) reqBuilder.append(":").append(url.getPort());
                    reqBuilder.append(path).append(" HTTP/1.1\r\n");

                    // Write forwarded headers
                    for (Map.Entry<String, String> entry : fwdHeaders.entrySet()) {
                        reqBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
                    }
                    reqBuilder.append("\r\n");
                    proxyOut.write(reqBuilder.toString().getBytes());
                    proxyOut.flush();

                    // Forward request body if present
                    forwardRequestBody(rawIn, proxyOut, headers);

                    // Read the response from the parent proxy
                    InputStream proxyIn = proxySocket.getInputStream();
                    BufferedReader proxyReader = new BufferedReader(new InputStreamReader(proxyIn));

                    // Read status line
                    String statusLine = proxyReader.readLine();
                    if (statusLine == null) {
                        proxySocket.close();
                        out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes());
                        return;
                    }
                    out.write((statusLine + "\r\n").getBytes());

                    // Read and forward response headers, stripping hop-by-hop
                    String respHeaderLine;
                    Map<String, String> responseHeaders = new LinkedHashMap<>();
                    while ((respHeaderLine = proxyReader.readLine()) != null && !respHeaderLine.isEmpty()) {
                        int ci = respHeaderLine.indexOf(':');
                        if (ci > 0) {
                            String hk = respHeaderLine.substring(0, ci).trim().toLowerCase();
                            String hv = respHeaderLine.substring(ci + 1).trim();
                            responseHeaders.put(hk, hv);
                        }
                    }
                    Map<String, String> cleanedRespHeaders = stripHopByHop(responseHeaders);
                    for (Map.Entry<String, String> entry : cleanedRespHeaders.entrySet()) {
                        out.write((entry.getKey() + ": " + entry.getValue() + "\r\n").getBytes());
                    }
                    out.write("\r\n".getBytes());
                    out.flush();

                    // Pipe response body
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = proxyIn.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    out.flush();
                } finally {
                    try { proxySocket.close(); } catch (IOException ignored) {}
                }
            } else {
                // Direct connection using HttpURLConnection
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);

                // Set forwarded headers (skip Host — HttpURLConnection sets it)
                for (Map.Entry<String, String> entry : fwdHeaders.entrySet()) {
                    if (!entry.getKey().equals("host")) {
                        conn.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }

                // Forward request body if present
                String contentLengthStr = headers.get("content-length");
                int contentLength = 0;
                if (contentLengthStr != null) {
                    try { contentLength = Integer.parseInt(contentLengthStr.trim()); } catch (NumberFormatException ignored) {}
                }

                boolean hasBody = contentLength > 0;
                String transferEncoding = headers.get("transfer-encoding");

                if (hasBody || (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked"))) {
                    conn.setDoOutput(true);
                    OutputStream connOut = conn.getOutputStream();
                    if (hasBody) {
                        // Read body from the client's buffered reader is unreliable for binary;
                        // we need the raw input stream. But BufferedReader may have already consumed bytes.
                        // We use rawIn which is the socket's InputStream directly.
                        byte[] bodyBuf = new byte[8192];
                        int remaining = contentLength;
                        while (remaining > 0) {
                            int toRead = Math.min(bodyBuf.length, remaining);
                            int read = rawIn.read(bodyBuf, 0, toRead);
                            if (read < 0) break;
                            connOut.write(bodyBuf, 0, read);
                            remaining -= read;
                        }
                    } else {
                        // Chunked transfer encoding — pipe until connection closes
                        byte[] bodyBuf = new byte[8192];
                        int len;
                        while ((len = rawIn.read(bodyBuf)) > 0) {
                            connOut.write(bodyBuf, 0, len);
                        }
                    }
                    connOut.flush();
                }

                // Read and forward the response
                int responseCode = conn.getResponseCode();
                StringBuilder response = new StringBuilder();
                response.append("HTTP/1.1 ").append(responseCode).append(" ").append(conn.getResponseMessage()).append("\r\n");

                // Collect response headers and strip hop-by-hop
                Map<String, String> respHeaders = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
                    if (entry.getKey() != null) {
                        // Take first value for simplicity (matching existing behaviour)
                        respHeaders.put(entry.getKey().toLowerCase(), entry.getValue().get(0));
                    }
                }
                Map<String, String> cleanedRespHeaders = stripHopByHop(respHeaders);
                for (Map.Entry<String, String> entry : cleanedRespHeaders.entrySet()) {
                    response.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
                }
                response.append("\r\n");
                out.write(response.toString().getBytes());

                // Stream response body
                InputStream connIn = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (connIn != null) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = connIn.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }
                out.flush();
            }
        } catch (Exception e) {
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes());
            SandboxLog.error("HTTP proxy error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ request body forwarding

    /**
     * Forward the request body from the client to the upstream output stream,
     * based on Content-Length or Transfer-Encoding headers.
     */
    private void forwardRequestBody(InputStream clientIn, OutputStream upstreamOut,
                                     Map<String, String> headers) throws IOException {
        String contentLengthStr = headers.get("content-length");
        int contentLength = 0;
        if (contentLengthStr != null) {
            try { contentLength = Integer.parseInt(contentLengthStr.trim()); } catch (NumberFormatException ignored) {}
        }

        if (contentLength > 0) {
            byte[] buf = new byte[8192];
            int remaining = contentLength;
            while (remaining > 0) {
                int toRead = Math.min(buf.length, remaining);
                int read = clientIn.read(buf, 0, toRead);
                if (read < 0) break;
                upstreamOut.write(buf, 0, read);
                remaining -= read;
            }
            upstreamOut.flush();
        } else {
            String transferEncoding = headers.get("transfer-encoding");
            if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
                // Chunked — pipe until the connection closes
                byte[] buf = new byte[8192];
                int len;
                while ((len = clientIn.read(buf)) > 0) {
                    upstreamOut.write(buf, 0, len);
                }
                upstreamOut.flush();
            }
            // No body for methods like GET, HEAD, OPTIONS, DELETE without body
        }
    }

    // ------------------------------------------------------------------ pipe (bidirectional)

    private void pipe(Socket client, Socket upstream) {
        Thread t1 = new Thread(() -> {
            try {
                InputStream in = client.getInputStream();
                OutputStream out = upstream.getOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            } catch (IOException e) { /* connection closed */ }
        });
        Thread t2 = new Thread(() -> {
            try {
                InputStream in = upstream.getInputStream();
                OutputStream out = client.getOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            } catch (IOException e) { /* connection closed */ }
        });
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
    }

    // ------------------------------------------------------------------ hop-by-hop header stripping

    /**
     * Strip hop-by-hop headers (RFC 7230 §6.1) and any headers named in the
     * {@code Connection} header. Ports {@code stripHopByHop} from parent-proxy.ts.
     *
     * @param headers input headers (keys are expected to be lowercase)
     * @return a new map without hop-by-hop headers
     */
    private static Map<String, String> stripHopByHop(Map<String, String> headers) {
        // Collect extra header names listed in the Connection header
        Set<String> extra = new HashSet<>();
        String connHeader = headers.get("connection");
        if (connHeader != null) {
            for (String tok : connHeader.split(",")) {
                String trimmed = tok.trim().toLowerCase();
                if (!trimmed.isEmpty()) {
                    extra.add(trimmed);
                }
            }
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey().toLowerCase();
            if (!HOP_BY_HOP.contains(key) && !extra.contains(key)) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }
}
