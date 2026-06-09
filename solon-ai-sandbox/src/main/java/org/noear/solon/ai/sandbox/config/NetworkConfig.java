package org.noear.solon.ai.sandbox.config;

import java.util.List;
import java.util.Map;

public class NetworkConfig {
    private final List<String> allowedDomains;
    private final List<String> deniedDomains;
    private final List<String> allowUnixSockets;
    private final Boolean allowAllUnixSockets;
    private final Boolean allowLocalBinding;
    private final List<String> allowMachLookup;
    private final Integer httpProxyPort;
    private final Integer socksProxyPort;
    private final MitmProxyConfig mitmProxy;
    private final ParentProxyConfig parentProxy;
    private final TlsTerminateConfig tlsTerminate;
    private final Map<String, Object> filterRequest;

    public NetworkConfig(List<String> allowedDomains, List<String> deniedDomains,
                         List<String> allowUnixSockets, Boolean allowAllUnixSockets,
                         Boolean allowLocalBinding, List<String> allowMachLookup,
                         Integer httpProxyPort, Integer socksProxyPort,
                         MitmProxyConfig mitmProxy, ParentProxyConfig parentProxy,
                         TlsTerminateConfig tlsTerminate, Map<String, Object> filterRequest) {
        this.allowedDomains = allowedDomains;
        this.deniedDomains = deniedDomains;
        this.allowUnixSockets = allowUnixSockets;
        this.allowAllUnixSockets = allowAllUnixSockets;
        this.allowLocalBinding = allowLocalBinding;
        this.allowMachLookup = allowMachLookup;
        this.httpProxyPort = httpProxyPort;
        this.socksProxyPort = socksProxyPort;
        this.mitmProxy = mitmProxy;
        this.parentProxy = parentProxy;
        this.tlsTerminate = tlsTerminate;
        this.filterRequest = filterRequest;
    }

    public List<String> getAllowedDomains() { return allowedDomains; }
    public List<String> getDeniedDomains() { return deniedDomains; }
    public List<String> getAllowUnixSockets() { return allowUnixSockets; }
    public Boolean getAllowAllUnixSockets() { return allowAllUnixSockets; }
    public Boolean getAllowLocalBinding() { return allowLocalBinding; }
    public List<String> getAllowMachLookup() { return allowMachLookup; }
    public Integer getHttpProxyPort() { return httpProxyPort; }
    public Integer getSocksProxyPort() { return socksProxyPort; }
    public MitmProxyConfig getMitmProxy() { return mitmProxy; }
    public ParentProxyConfig getParentProxy() { return parentProxy; }
    public TlsTerminateConfig getTlsTerminate() { return tlsTerminate; }
    public Map<String, Object> getFilterRequest() { return filterRequest; }
}
