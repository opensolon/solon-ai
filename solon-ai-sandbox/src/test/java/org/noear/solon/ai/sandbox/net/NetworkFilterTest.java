package org.noear.solon.ai.sandbox.net;

import org.noear.solon.ai.sandbox.NetworkHostPattern;
import org.noear.solon.ai.sandbox.SandboxAskCallback;
import org.noear.solon.ai.sandbox.config.NetworkConfig;
import org.noear.solon.ai.sandbox.config.SandboxRuntimeConfig;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;

public class NetworkFilterTest {

    private static SandboxRuntimeConfig configWithNetwork(NetworkConfig network) {
        return new SandboxRuntimeConfig(
                network, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static NetworkConfig networkConfig(java.util.List<String> allowedDomains,
                                               java.util.List<String> deniedDomains) {
        return new NetworkConfig(
                allowedDomains, deniedDomains, null, null, null, null, null, null, null, null, null, null);
    }

    // === 1. No config -> deny ===

    @Test
    public void testNoConfig_Deny() {
        assertFalse(NetworkFilter.filter(80, "example.com", null, null));
    }

    @Test
    public void testNullNetworkConfig_Deny() {
        SandboxRuntimeConfig cfg = configWithNetwork(null);
        assertFalse(NetworkFilter.filter(80, "example.com", cfg, null));
    }

    // === 2. Null host -> deny ===

    @Test
    public void testNullHost_Deny() {
        NetworkConfig net = networkConfig(Collections.singletonList("example.com"), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertFalse(NetworkFilter.filter(80, null, cfg, null));
    }

    // === 3. Denied domain takes priority over allowed ===

    @Test
    public void testDeniedTakesPriorityOverAllowed() {
        NetworkConfig net = networkConfig(
                Collections.singletonList("example.com"),
                Collections.singletonList("example.com"));
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertFalse(NetworkFilter.filter(80, "example.com", cfg, null));
    }

    @Test
    public void testDeniedWildcardTakesPriority() {
        NetworkConfig net = networkConfig(
                Collections.singletonList("*.example.com"),
                Collections.singletonList("*.example.com"));
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertFalse(NetworkFilter.filter(80, "sub.example.com", cfg, null));
    }

    // === 4. Allowed domain passes ===

    @Test
    public void testAllowedDomain_Passes() {
        NetworkConfig net = networkConfig(Collections.singletonList("example.com"), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertTrue(NetworkFilter.filter(80, "example.com", cfg, null));
    }

    @Test
    public void testAllowedWildcard_Passes() {
        NetworkConfig net = networkConfig(Collections.singletonList("*.example.com"), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertTrue(NetworkFilter.filter(443, "api.example.com", cfg, null));
    }

    @Test
    public void testAllowedMultipleDomains_Passes() {
        NetworkConfig net = networkConfig(Arrays.asList("other.com", "example.com"), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertTrue(NetworkFilter.filter(80, "example.com", cfg, null));
    }

    // === 5. Unknown domain with no callback -> deny ===

    @Test
    public void testUnknownDomain_NoCallback_Deny() {
        NetworkConfig net = networkConfig(Collections.singletonList("allowed.com"), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertFalse(NetworkFilter.filter(80, "unknown.com", cfg, null));
    }

    @Test
    public void testUnknownDomain_EmptyAllowedList_Deny() {
        NetworkConfig net = networkConfig(Collections.<String>emptyList(), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertFalse(NetworkFilter.filter(80, "unknown.com", cfg, null));
    }

    // === 6. Unknown domain with callback returning true -> allow ===

    @Test
    public void testUnknownDomain_CallbackAllows() {
        NetworkConfig net = networkConfig(Collections.singletonList("allowed.com"), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        SandboxAskCallback callback = new SandboxAskCallback() {
            @Override
            public boolean ask(NetworkHostPattern params) {
                return true;
            }
        };
        assertTrue(NetworkFilter.filter(80, "dynamic.com", cfg, callback));
    }

    @Test
    public void testUnknownDomain_CallbackDenies() {
        NetworkConfig net = networkConfig(Collections.singletonList("allowed.com"), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        SandboxAskCallback callback = new SandboxAskCallback() {
            @Override
            public boolean ask(NetworkHostPattern params) {
                return false;
            }
        };
        assertFalse(NetworkFilter.filter(80, "dynamic.com", cfg, callback));
    }

    @Test
    public void testCallbackReceivesCorrectHostAndPort() {
        NetworkConfig net = networkConfig(Collections.singletonList("allowed.com"), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        final int expectedPort = 8080;
        final String expectedHost = "dynamic.com";
        SandboxAskCallback callback = new SandboxAskCallback() {
            @Override
            public boolean ask(NetworkHostPattern params) {
                assertEquals(expectedHost, params.getHost());
                assertEquals(Integer.valueOf(expectedPort), params.getPort());
                return true;
            }
        };
        assertTrue(NetworkFilter.filter(expectedPort, expectedHost, cfg, callback));
    }

    // === 7. Callback throwing exception -> deny ===

    @Test
    public void testCallbackThrows_Deny() {
        NetworkConfig net = networkConfig(Collections.singletonList("allowed.com"), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        SandboxAskCallback callback = new SandboxAskCallback() {
            @Override
            public boolean ask(NetworkHostPattern params) throws Exception {
                throw new RuntimeException("Simulated failure");
            }
        };
        assertFalse(NetworkFilter.filter(80, "dynamic.com", cfg, callback));
    }

    @Test
    public void testCallbackThrowsChecked_Deny() {
        NetworkConfig net = networkConfig(Collections.singletonList("allowed.com"), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        SandboxAskCallback callback = new SandboxAskCallback() {
            @Override
            public boolean ask(NetworkHostPattern params) throws Exception {
                throw new Exception("Checked exception");
            }
        };
        assertFalse(NetworkFilter.filter(80, "dynamic.com", cfg, callback));
    }

    // === 8. Wildcard deny and allow patterns ===

    @Test
    public void testWildcardDeny_BlocksSubdomain() {
        NetworkConfig net = networkConfig(null, Collections.singletonList("*.evil.com"));
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertFalse(NetworkFilter.filter(80, "sub.evil.com", cfg, null));
    }

    @Test
    public void testWildcardDeny_DoesNotBlockBareDomain() {
        NetworkConfig net = networkConfig(null, Collections.singletonList("*.evil.com"));
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        // *.evil.com does not match evil.com, but no allowed list -> deny by default
        assertFalse(NetworkFilter.filter(80, "evil.com", cfg, null));
    }

    @Test
    public void testWildcardAllow_BlocksUnrelatedDomain() {
        NetworkConfig net = networkConfig(Collections.singletonList("*.example.com"), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertFalse(NetworkFilter.filter(80, "other.com", cfg, null));
    }

    @Test
    public void testWildcardAllow_AllowsDeepSubdomain() {
        NetworkConfig net = networkConfig(Collections.singletonList("*.example.com"), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertTrue(NetworkFilter.filter(443, "a.b.c.example.com", cfg, null));
    }

    @Test
    public void testBothWildcardDenyAndAllow() {
        NetworkConfig net = networkConfig(
                Collections.singletonList("*.example.com"),
                Collections.singletonList("*.example.com"));
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertFalse(NetworkFilter.filter(80, "sub.example.com", cfg, null));
    }

    // === 9. Invalid host ===

    @Test
    public void testInvalidHost_ControlChar_Deny() {
        NetworkConfig net = networkConfig(Collections.singletonList("*"), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertFalse(NetworkFilter.filter(80, "exam\u0001ple.com", cfg, null));
    }

    @Test
    public void testEmptyHost_Deny() {
        NetworkConfig net = networkConfig(Collections.singletonList("example.com"), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertFalse(NetworkFilter.filter(80, "", cfg, null));
    }

    // === 10. Port is ignored in domain matching ===

    @Test
    public void testDifferentPorts_SameDomain() {
        NetworkConfig net = networkConfig(Collections.singletonList("example.com"), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertTrue(NetworkFilter.filter(80, "example.com", cfg, null));
        assertTrue(NetworkFilter.filter(443, "example.com", cfg, null));
        assertTrue(NetworkFilter.filter(8080, "example.com", cfg, null));
    }

    // === 11. Null allowed/denied lists ===

    @Test
    public void testNullAllowedNullDenied_NoCallback_Deny() {
        NetworkConfig net = networkConfig(null, null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertFalse(NetworkFilter.filter(80, "example.com", cfg, null));
    }

    @Test
    public void testNullAllowedNullDenied_CallbackAllows() {
        NetworkConfig net = networkConfig(null, null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        SandboxAskCallback callback = new SandboxAskCallback() {
            @Override
            public boolean ask(NetworkHostPattern params) {
                return true;
            }
        };
        assertTrue(NetworkFilter.filter(80, "example.com", cfg, callback));
    }
}
