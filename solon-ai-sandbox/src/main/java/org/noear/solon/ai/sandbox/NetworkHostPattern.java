package org.noear.solon.ai.sandbox;

/**
 * Represents a host:port pattern for network filtering.
 */
public class NetworkHostPattern {
    private final String host;
    private final Integer port;

    public NetworkHostPattern(String host, Integer port) {
        this.host = host;
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public Integer getPort() {
        return port;
    }
}
