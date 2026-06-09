package org.noear.solon.ai.sandbox.config;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.sandbox.SandboxException;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class SandboxConfigValidatorTest {

    // --- Helpers ---

    private static SandboxRuntimeConfig allNullConfig() {
        return new SandboxRuntimeConfig(null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static SandboxRuntimeConfig withNetwork(NetworkConfig network) {
        return new SandboxRuntimeConfig(network, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static SandboxRuntimeConfig withWindows(WindowsConfig windows) {
        return new SandboxRuntimeConfig(null, null, null, null, null, null, null,
                null, null, null, null, null, windows);
    }

    private static NetworkConfig netWithDomains(java.util.List<String> allowed) {
        return new NetworkConfig(allowed, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static NetworkConfig netWithProxyPort(Integer port) {
        return new NetworkConfig(null, null, null, null, null, null,
                port, null, null, null, null, null);
    }

    // --- Tests ---

    @Test
    void nullConfig_throws() {
        assertThrows(SandboxException.class, () -> SandboxConfigValidator.validate(null));
    }

    @Test
    void allNullConfig_doesNotThrow() {
        assertDoesNotThrow(() -> SandboxConfigValidator.validate(allNullConfig()));
    }

    @Test
    void validConfig_doesNotThrow() {
        NetworkConfig network = new NetworkConfig(
                Arrays.asList("example.com", "*.example.com"), null, null, null, null, null,
                8080, null, null, null, null, null);
        SandboxRuntimeConfig cfg = new SandboxRuntimeConfig(
                network, null, null, null, null, null, null,
                null, null, null, null, null, null);
        assertDoesNotThrow(() -> SandboxConfigValidator.validate(cfg));
    }

    @Test
    void domainWithProtocol_throws() {
        assertThrows(SandboxException.class, () ->
                SandboxConfigValidator.validate(withNetwork(netWithDomains(
                        Arrays.asList("http://example.com")))));
    }

    @Test
    void domainWithSlash_throws() {
        assertThrows(SandboxException.class, () ->
                SandboxConfigValidator.validate(withNetwork(netWithDomains(
                        Arrays.asList("example.com/path")))));
    }

    @Test
    void domainWithBareWildcard_throws() {
        assertThrows(SandboxException.class, () ->
                SandboxConfigValidator.validate(withNetwork(netWithDomains(
                        Arrays.asList("*")))));
    }

    @Test
    void domainTooBroad_throws() {
        assertThrows(SandboxException.class, () ->
                SandboxConfigValidator.validate(withNetwork(netWithDomains(
                        Arrays.asList("*.com")))));
    }

    @Test
    void domainValidWildcard_doesNotThrow() {
        assertDoesNotThrow(() ->
                SandboxConfigValidator.validate(withNetwork(netWithDomains(
                        Arrays.asList("*.example.com")))));
    }

    @Test
    void proxyPortZero_throws() {
        assertThrows(SandboxException.class, () ->
                SandboxConfigValidator.validate(withNetwork(netWithProxyPort(0))));
    }

    @Test
    void proxyPortTooHigh_throws() {
        assertThrows(SandboxException.class, () ->
                SandboxConfigValidator.validate(withNetwork(netWithProxyPort(70000))));
    }

    @Test
    void tlsTerminateOnlyCert_throws() {
        NetworkConfig net = new NetworkConfig(null, null, null, null, null, null,
                null, null, null, null, new TlsTerminateConfig("/cert", null), null);
        assertThrows(SandboxException.class, () ->
                SandboxConfigValidator.validate(withNetwork(net)));
    }

    @Test
    void tlsTerminateWithBothCertAndKey_doesNotThrow() {
        NetworkConfig net = new NetworkConfig(null, null, null, null, null, null,
                null, null, null, null, new TlsTerminateConfig("/cert", "/key"), null);
        assertDoesNotThrow(() -> SandboxConfigValidator.validate(withNetwork(net)));
    }

    @Test
    void tlsTerminateNullFields_doesNotThrow() {
        NetworkConfig net = new NetworkConfig(null, null, null, null, null, null,
                null, null, null, null, new TlsTerminateConfig(null, null), null);
        assertDoesNotThrow(() -> SandboxConfigValidator.validate(withNetwork(net)));
    }

    @Test
    void tlsTerminateAndMitmProxy_throws() {
        NetworkConfig net = new NetworkConfig(null, null, null, null, null, null,
                null, null,
                new MitmProxyConfig("/tmp/proxy.sock", Arrays.asList("example.com")),
                null, new TlsTerminateConfig("/cert", "/key"), null);
        assertThrows(SandboxException.class, () ->
                SandboxConfigValidator.validate(withNetwork(net)));
    }

    @Test
    void windowsPortRangeLoGreaterThanHi_throws() {
        assertThrows(SandboxException.class, () ->
                SandboxConfigValidator.validate(withWindows(
                        new WindowsConfig(null, null, null, new int[]{200, 100}))));
    }

    @Test
    void windowsPortRangeTooWide_throws() {
        assertThrows(SandboxException.class, () ->
                SandboxConfigValidator.validate(withWindows(
                        new WindowsConfig(null, null, null, new int[]{100, 200}))));
    }

    @Test
    void windowsValidPortRange_doesNotThrow() {
        assertDoesNotThrow(() ->
                SandboxConfigValidator.validate(withWindows(
                        new WindowsConfig(null, null, null, new int[]{60080, 60089}))));
    }

    @Test
    void bwrapPathRelative_throws() {
        SandboxRuntimeConfig cfg = new SandboxRuntimeConfig(
                null, null, null, null, null, null, null,
                null, null, null, "relative/bwrap", null, null);
        assertThrows(SandboxException.class, () -> SandboxConfigValidator.validate(cfg));
    }

    @Test
    void bwrapPathAbsolute_doesNotThrow() {
        SandboxRuntimeConfig cfg = new SandboxRuntimeConfig(
                null, null, null, null, null, null, null,
                null, null, null, "/usr/bin/bwrap", null, null);
        assertDoesNotThrow(() -> SandboxConfigValidator.validate(cfg));
    }

    @Test
    void emptyAllowedDomains_doesNotThrow() {
        assertDoesNotThrow(() ->
                SandboxConfigValidator.validate(withNetwork(netWithDomains(
                        Collections.emptyList()))));
    }

    @Test
    void localhostDomain_doesNotThrow() {
        assertDoesNotThrow(() ->
                SandboxConfigValidator.validate(withNetwork(netWithDomains(
                        Arrays.asList("localhost")))));
    }

    @Test
    void mandatoryDenySearchDepthNegative_throws() {
        SandboxRuntimeConfig cfg = new SandboxRuntimeConfig(
                null, null, null, null, null, null, null,
                -1, null, null, null, null, null);
        assertThrows(SandboxException.class, () -> SandboxConfigValidator.validate(cfg));
    }

    @Test
    void mandatoryDenySearchDepthZero_doesNotThrow() {
        SandboxRuntimeConfig cfg = new SandboxRuntimeConfig(
                null, null, null, null, null, null, null,
                0, null, null, null, null, null);
        assertDoesNotThrow(() -> SandboxConfigValidator.validate(cfg));
    }

    @Test
    void domainWithColon_throws() {
        assertThrows(SandboxException.class, () ->
                SandboxConfigValidator.validate(withNetwork(netWithDomains(
                        Arrays.asList("example.com:8080")))));
    }

    @Test
    void mitmProxyEmptySocketPath_throws() {
        NetworkConfig net = new NetworkConfig(null, null, null, null, null, null,
                null, null,
                new MitmProxyConfig("", Arrays.asList("example.com")),
                null, null, null);
        assertThrows(SandboxException.class, () ->
                SandboxConfigValidator.validate(withNetwork(net)));
    }

    @Test
    void mitmProxyEmptyDomains_throws() {
        NetworkConfig net = new NetworkConfig(null, null, null, null, null, null,
                null, null,
                new MitmProxyConfig("/tmp/proxy.sock", Collections.emptyList()),
                null, null, null);
        assertThrows(SandboxException.class, () ->
                SandboxConfigValidator.validate(withNetwork(net)));
    }

    @Test
    void validSocksProxyPort_doesNotThrow() {
        NetworkConfig net = new NetworkConfig(null, null, null, null, null, null,
                null, 1080, null, null, null, null);
        assertDoesNotThrow(() -> SandboxConfigValidator.validate(withNetwork(net)));
    }

    @Test
    void windowsGroupSidInvalid_throws() {
        assertThrows(SandboxException.class, () ->
                SandboxConfigValidator.validate(withWindows(
                        new WindowsConfig(null, "INVALID-SID", null, null))));
    }

    @Test
    void windowsGroupSidValid_doesNotThrow() {
        assertDoesNotThrow(() ->
                SandboxConfigValidator.validate(withWindows(
                        new WindowsConfig(null, "S-1-5-32-544", null, null))));
    }

    @Test
    void wildcardInMiddle_throws() {
        assertThrows(SandboxException.class, () ->
                SandboxConfigValidator.validate(withNetwork(netWithDomains(
                        Arrays.asList("sub.*.example.com")))));
    }
}
