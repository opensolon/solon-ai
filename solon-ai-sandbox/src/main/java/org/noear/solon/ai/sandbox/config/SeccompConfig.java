package org.noear.solon.ai.sandbox.config;

public class SeccompConfig {
    private final String applyPath;
    private final String argv0;

    public SeccompConfig(String applyPath, String argv0) {
        this.applyPath = applyPath;
        this.argv0 = argv0;
    }

    public String getApplyPath() { return applyPath; }
    public String getArgv0() { return argv0; }
}
