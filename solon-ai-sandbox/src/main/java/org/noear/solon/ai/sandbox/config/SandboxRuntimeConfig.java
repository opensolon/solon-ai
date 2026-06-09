package org.noear.solon.ai.sandbox.config;

import java.util.List;
import java.util.Map;

public class SandboxRuntimeConfig {
    private final NetworkConfig network;
    private final FilesystemConfig filesystem;
    private final Map<String, List<String>> ignoreViolations;
    private final Boolean enableWeakerNestedSandbox;
    private final Boolean enableWeakerNetworkIsolation;
    private final Boolean allowAppleEvents;
    private final RipgrepConfig ripgrep;
    private final Integer mandatoryDenySearchDepth;
    private final Boolean allowPty;
    private final SeccompConfig seccomp;
    private final String bwrapPath;
    private final String socatPath;
    private final WindowsConfig windows;

    public SandboxRuntimeConfig(NetworkConfig network, FilesystemConfig filesystem,
                                Map<String, List<String>> ignoreViolations,
                                Boolean enableWeakerNestedSandbox, Boolean enableWeakerNetworkIsolation,
                                Boolean allowAppleEvents, RipgrepConfig ripgrep,
                                Integer mandatoryDenySearchDepth, Boolean allowPty,
                                SeccompConfig seccomp, String bwrapPath, String socatPath,
                                WindowsConfig windows) {
        this.network = network;
        this.filesystem = filesystem;
        this.ignoreViolations = ignoreViolations;
        this.enableWeakerNestedSandbox = enableWeakerNestedSandbox;
        this.enableWeakerNetworkIsolation = enableWeakerNetworkIsolation;
        this.allowAppleEvents = allowAppleEvents;
        this.ripgrep = ripgrep;
        this.mandatoryDenySearchDepth = mandatoryDenySearchDepth;
        this.allowPty = allowPty;
        this.seccomp = seccomp;
        this.bwrapPath = bwrapPath;
        this.socatPath = socatPath;
        this.windows = windows;
    }

    public NetworkConfig getNetwork() { return network; }
    public FilesystemConfig getFilesystem() { return filesystem; }
    public Map<String, List<String>> getIgnoreViolations() { return ignoreViolations; }
    public Boolean getEnableWeakerNestedSandbox() { return enableWeakerNestedSandbox; }
    public Boolean getEnableWeakerNetworkIsolation() { return enableWeakerNetworkIsolation; }
    public Boolean getAllowAppleEvents() { return allowAppleEvents; }
    public RipgrepConfig getRipgrep() { return ripgrep; }
    public Integer getMandatoryDenySearchDepth() { return mandatoryDenySearchDepth; }
    public Boolean getAllowPty() { return allowPty; }
    public SeccompConfig getSeccomp() { return seccomp; }
    public String getBwrapPath() { return bwrapPath; }
    public String getSocatPath() { return socatPath; }
    public WindowsConfig getWindows() { return windows; }
}
