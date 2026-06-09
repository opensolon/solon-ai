package org.noear.solon.ai.sandbox.config;

public class TlsTerminateConfig {
    private final String caCertPath;
    private final String caKeyPath;

    public TlsTerminateConfig(String caCertPath, String caKeyPath) {
        this.caCertPath = caCertPath;
        this.caKeyPath = caKeyPath;
    }

    public String getCaCertPath() { return caCertPath; }
    public String getCaKeyPath() { return caKeyPath; }
}
