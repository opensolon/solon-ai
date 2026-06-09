package org.noear.solon.ai.sandbox.config;

import java.util.List;

public class MitmProxyConfig {
    private final String socketPath;
    private final List<String> domains;

    public MitmProxyConfig(String socketPath, List<String> domains) {
        this.socketPath = socketPath;
        this.domains = domains;
    }

    public String getSocketPath() { return socketPath; }
    public List<String> getDomains() { return domains; }
}
