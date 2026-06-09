package org.noear.solon.ai.sandbox.proxy;

import org.noear.solon.ai.sandbox.SandboxAskCallback;
import org.noear.solon.ai.sandbox.SandboxLog;
import org.noear.solon.ai.sandbox.config.SandboxRuntimeConfig;
import org.noear.solon.ai.sandbox.net.NetworkFilter;
import org.noear.solon.ai.sandbox.net.ParentProxyResolver;

import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SocksProxyServer {

    private final SandboxRuntimeConfig config;
    private final SandboxAskCallback callback;
    private final ParentProxyResolver parentProxyResolver;
    private ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public SocksProxyServer(SandboxRuntimeConfig config, SandboxAskCallback callback, ParentProxyResolver parentProxyResolver) {
        this.config = config;
        this.callback = callback;
        this.parentProxyResolver = parentProxyResolver;
    }

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
            SandboxLog.error("Error closing SOCKS proxy: " + e.getMessage());
        }
        executor.shutdownNow();
    }

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

    private void handleClient(Socket client) {
        try {
            DataInputStream in = new DataInputStream(client.getInputStream());
            DataOutputStream out = new DataOutputStream(client.getOutputStream());

            // SOCKS5 greeting
            int version = in.readUnsignedByte();
            if (version != 5) { client.close(); return; }
            int numMethods = in.readUnsignedByte();
            for (int i = 0; i < numMethods; i++) in.readUnsignedByte();

            // No auth required
            out.write(new byte[]{0x05, 0x00});
            out.flush();

            // SOCKS5 request
            int ver = in.readUnsignedByte();
            int cmd = in.readUnsignedByte();
            int rsv = in.readUnsignedByte(); // 0x00
            int atyp = in.readUnsignedByte();

            String hostname;
            int port;

            switch (atyp) {
                case 0x01: // IPv4
                    byte[] ipv4 = new byte[4];
                    in.readFully(ipv4);
                    hostname = InetAddress.getByAddress(ipv4).getHostAddress();
                    break;
                case 0x03: // Domain
                    int domainLen = in.readUnsignedByte();
                    byte[] domainBytes = new byte[domainLen];
                    in.readFully(domainBytes);
                    hostname = new String(domainBytes, "UTF-8");
                    break;
                case 0x04: // IPv6
                    byte[] ipv6 = new byte[16];
                    in.readFully(ipv6);
                    hostname = InetAddress.getByAddress(ipv6).getHostAddress();
                    break;
                default:
                    sendReply(out, 0x08, atyp, null, 0); // address type not supported
                    client.close();
                    return;
            }
            port = in.readUnsignedShort();

            // Validate hostname
            if (!org.noear.solon.ai.sandbox.net.HostUtils.isValidHost(hostname)) {
                SandboxLog.error("Denying SOCKS connection to malformed host: " + hostname);
                sendReply(out, 0x04, atyp, hostname, port); // host unreachable
                try { client.close(); } catch (IOException e) { /* ignore */ }
                return;
            }

            // Filter
            if (!NetworkFilter.filter(port, hostname, config, callback)) {
                sendReply(out, 0x02, atyp, hostname, port); // connection not allowed
                client.close();
                return;
            }

            // Connect to target (optionally through parent proxy)
            try {
                Socket upstream;
                if (parentProxyResolver != null && parentProxyResolver.getHttpProxyUrl() != null
                    && !parentProxyResolver.shouldBypassProxy(hostname, port)) {
                    try {
                        java.net.URI proxyUri = new java.net.URI(parentProxyResolver.getHttpProxyUrl());
                        java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                            new java.net.InetSocketAddress(proxyUri.getHost(), proxyUri.getPort()));
                        upstream = new Socket(proxy);
                        upstream.connect(new java.net.InetSocketAddress(hostname, port), 30000);
                    } catch (Exception e) {
                        SandboxLog.error("SOCKS parent proxy connect failed: " + e.getMessage());
                        sendReply(out, 0x04, atyp, hostname, port);
                        client.close();
                        return;
                    }
                } else {
                    upstream = new Socket(hostname, port);
                }
                sendReply(out, 0x00, atyp, hostname, port); // success

                // Pipe
                Thread t1 = new Thread(() -> pipe(client, upstream));
                Thread t2 = new Thread(() -> pipe(upstream, client));
                t1.setDaemon(true);
                t2.setDaemon(true);
                t1.start();
                t2.start();
            } catch (IOException e) {
                sendReply(out, 0x04, atyp, hostname, port); // host unreachable
                client.close();
                SandboxLog.error("SOCKS connect failed: " + e.getMessage());
            }
        } catch (IOException e) {
            SandboxLog.error("SOCKS handler error: " + e.getMessage());
        } finally {
            // client closed in pipe threads
        }
    }

    private void sendReply(DataOutputStream out, int status, int atyp, String host, int port) throws IOException {
        out.write(new byte[]{0x05, (byte) status, 0x00, 0x01});
        out.write(new byte[]{0, 0, 0, 0}); // bind addr (doesn't matter for most replies)
        out.writeShort(port);
        out.flush();
    }

    private void pipe(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        } catch (IOException e) {
            // connection closed
        } finally {
            try { from.close(); } catch (IOException e) { /* ignore */ }
            try { to.close(); } catch (IOException e) { /* ignore */ }
        }
    }
}
