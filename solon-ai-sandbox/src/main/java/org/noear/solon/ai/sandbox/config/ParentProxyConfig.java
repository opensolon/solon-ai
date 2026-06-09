package org.noear.solon.ai.sandbox.config;

public class ParentProxyConfig {
    private final String http;
    private final String https;
    private final String noProxy;

    public ParentProxyConfig(String http, String https, String noProxy) {
        this.http = http;
        this.https = https;
        this.noProxy = noProxy;
    }

    public String getHttp() { return http; }
    public String getHttps() { return https; }
    public String getNoProxy() { return noProxy; }
}
