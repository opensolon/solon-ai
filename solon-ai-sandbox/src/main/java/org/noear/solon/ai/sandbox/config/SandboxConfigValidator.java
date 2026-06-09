package org.noear.solon.ai.sandbox.config;

import org.noear.solon.ai.sandbox.SandboxException;

import java.io.File;
import java.util.List;
import java.util.Map;

public class SandboxConfigValidator {

    public static void validate(SandboxRuntimeConfig config) {
        if (config == null) throw new SandboxException("Config cannot be null");
        validateNetwork(config.getNetwork());
        validateFilesystem(config.getFilesystem());
        validateWindows(config.getWindows());
        validateBinaryPaths(config);
        if (config.getMandatoryDenySearchDepth() != null && config.getMandatoryDenySearchDepth() < 0) {
            throw new SandboxException("mandatoryDenySearchDepth must be >= 0");
        }
        validateMutualExclusion(config);
    }

    private static void validateNetwork(NetworkConfig network) {
        if (network == null) return;
        validateDomainList(network.getAllowedDomains(), "network.allowedDomains");
        validateDomainList(network.getDeniedDomains(), "network.deniedDomains");
        if (network.getHttpProxyPort() != null) {
            validatePort(network.getHttpProxyPort(), "network.httpProxyPort");
        }
        if (network.getSocksProxyPort() != null) {
            validatePort(network.getSocksProxyPort(), "network.socksProxyPort");
        }
        if (network.getAllowMachLookup() != null) {
            for (String name : network.getAllowMachLookup()) {
                String prefix = name.endsWith("*") ? name.substring(0, name.length() - 1) : name;
                if (prefix.contains("*")) {
                    throw new SandboxException("network.allowMachLookup: wildcards only allowed as a single trailing '*'. Got: " + name);
                }
            }
        }
        if (network.getMitmProxy() != null) {
            MitmProxyConfig mitm = network.getMitmProxy();
            if (mitm.getSocketPath() == null || mitm.getSocketPath().isEmpty()) {
                throw new SandboxException("network.mitmProxy.socketPath cannot be empty");
            }
            if (mitm.getDomains() == null || mitm.getDomains().isEmpty()) {
                throw new SandboxException("network.mitmProxy.domains cannot be empty");
            }
            validateDomainList(mitm.getDomains(), "network.mitmProxy.domains");
        }
        if (network.getTlsTerminate() != null) {
            TlsTerminateConfig tls = network.getTlsTerminate();
            boolean hasCert = tls.getCaCertPath() != null && !tls.getCaCertPath().isEmpty();
            boolean hasKey = tls.getCaKeyPath() != null && !tls.getCaKeyPath().isEmpty();
            if (hasCert != hasKey) {
                throw new SandboxException("network.tlsTerminate: caCertPath and caKeyPath must be provided together");
            }
        }
    }

    private static void validateDomainList(List<String> domains, String field) {
        if (domains == null) return;
        for (String val : domains) {
            if (val.contains("://") || val.contains("/") || val.contains(":")) {
                throw new SandboxException(field + ": invalid domain pattern '" + val + "'. Must not contain ://, /, or :");
            }
            if ("localhost".equals(val)) continue;
            if (val.startsWith("*.")) {
                String domain = val.substring(2);
                if (!domain.contains(".") || domain.startsWith(".") || domain.endsWith(".")) {
                    throw new SandboxException(field + ": overly broad wildcard '" + val + "'. Must have at least two parts after the wildcard.");
                }
                String[] parts = domain.split("\\.");
                if (parts.length < 2) {
                    throw new SandboxException(field + ": overly broad wildcard '" + val + "'");
                }
                for (String p : parts) {
                    if (p.isEmpty()) {
                        throw new SandboxException(field + ": invalid wildcard '" + val + "'");
                    }
                }
                continue;
            }
            if (val.contains("*")) {
                throw new SandboxException(field + ": wildcards only supported as prefix *.domain. Got: " + val);
            }
            if (!val.contains(".") || val.startsWith(".") || val.endsWith(".")) {
                throw new SandboxException(field + ": invalid domain pattern '" + val + "'. Must be a valid domain like example.com or wildcard like *.example.com");
            }
        }
    }

    private static void validateFilesystem(FilesystemConfig fs) {
        if (fs == null) return;
        validatePathList(fs.getDenyRead(), "filesystem.denyRead");
        validatePathList(fs.getAllowRead(), "filesystem.allowRead");
        validatePathList(fs.getAllowWrite(), "filesystem.allowWrite");
        validatePathList(fs.getDenyWrite(), "filesystem.denyWrite");
    }

    private static void validatePathList(List<String> paths, String field) {
        if (paths == null) return;
        for (String p : paths) {
            if (p == null || p.isEmpty()) {
                throw new SandboxException(field + ": path cannot be empty");
            }
        }
    }

    private static void validateWindows(WindowsConfig win) {
        if (win == null) return;
        if (win.getProxyPortRange() != null) {
            int lo = win.getProxyPortRange()[0];
            int hi = win.getProxyPortRange()[1];
            if (lo < 1 || lo > 65535 || hi < 1 || hi > 65535) {
                throw new SandboxException("windows.proxyPortRange: values must be between 1 and 65535");
            }
            if (lo > hi) {
                throw new SandboxException("windows.proxyPortRange: low must be <= high");
            }
            if (hi - lo > 64) {
                throw new SandboxException("windows.proxyPortRange: range width must be <= 64");
            }
        }
        if (win.getGroupSid() != null && !win.getGroupSid().startsWith("S-1-")) {
            throw new SandboxException("windows.groupSid: must be an S-1-... SID string");
        }
        if (win.getWfpSublayerGuid() != null) {
            try {
                java.util.UUID.fromString(win.getWfpSublayerGuid());
            } catch (IllegalArgumentException e) {
                throw new SandboxException("windows.wfpSublayerGuid: must be a valid UUID");
            }
        }
    }

    private static void validateMutualExclusion(SandboxRuntimeConfig config) {
        NetworkConfig network = config.getNetwork();
        if (network != null && network.getMitmProxy() != null && hasTlsTerminate(network)) {
            throw new SandboxException("network.tlsTerminate and network.mitmProxy are mutually exclusive");
        }
    }

    private static boolean hasTlsTerminate(NetworkConfig network) {
        if (network == null) return false;
        TlsTerminateConfig tls = network.getTlsTerminate();
        if (tls == null) return false;
        return tls.getCaCertPath() != null || tls.getCaKeyPath() != null;
    }

    private static void validateBinaryPaths(SandboxRuntimeConfig config) {
        if (config == null) return;
        SandboxConfigValidator.validateBinaryPath(config.getBwrapPath(), "bwrapPath");
        SandboxConfigValidator.validateBinaryPath(config.getSocatPath(), "socatPath");
    }

    public static void validateBinaryPath(String path, String field) {
        if (path != null && !new File(path).isAbsolute()) {
            throw new SandboxException(field + ": must be an absolute path. Got: " + path);
        }
    }

    public static void validatePort(int port, String field) {
        if (port < 1 || port > 65535) {
            throw new SandboxException(field + ": port must be between 1 and 65535. Got: " + port);
        }
    }
}
