package org.noear.solon.ai.sandbox.util;

import org.noear.solon.ai.sandbox.platform.PlatformDetector;
import org.noear.solon.ai.sandbox.platform.Platform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ProxyEnvUtils {

    public static final List<String> CA_TRUST_VARS = Collections.unmodifiableList(Arrays.asList(
        "NODE_EXTRA_CA_CERTS", "SSL_CERT_FILE", "CURL_CA_BUNDLE",
        "REQUESTS_CA_BUNDLE", "PIP_CERT", "GIT_SSL_CAINFO",
        "AWS_CA_BUNDLE", "CARGO_HTTP_CAINFO", "DENO_CERT"
    ));

    public static List<String> generateProxyEnvVars(Integer httpProxyPort, Integer socksProxyPort, String caCertPath) {
        String tmpdir = getEnvOr("CLAUDE_CODE_TMPDIR", getEnvOr("CLAUDE_TMPDIR", "/tmp/claude"));
        List<String> envVars = new ArrayList<>();
        envVars.add("SANDBOX_RUNTIME=1");
        envVars.add("TMPDIR=" + tmpdir);

        if (caCertPath != null && !caCertPath.isEmpty()) {
            for (String v : CA_TRUST_VARS) {
                envVars.add(v + "=" + caCertPath);
            }
        }

        if (httpProxyPort == null && socksProxyPort == null) {
            return envVars;
        }

        String noProxyAddresses = String.join(",",
            "localhost", "127.0.0.1", "::1", "*.local", ".local",
            "169.254.0.0/16", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"
        );
        envVars.add("NO_PROXY=" + noProxyAddresses);
        envVars.add("no_proxy=" + noProxyAddresses);

        if (httpProxyPort != null) {
            String httpUrl = "http://localhost:" + httpProxyPort;
            envVars.add("HTTP_PROXY=" + httpUrl);
            envVars.add("HTTPS_PROXY=" + httpUrl);
            envVars.add("http_proxy=" + httpUrl);
            envVars.add("https_proxy=" + httpUrl);
        }

        if (socksProxyPort != null) {
            String socksUrl = "socks5h://localhost:" + socksProxyPort;
            envVars.add("ALL_PROXY=" + socksUrl);
            envVars.add("all_proxy=" + socksUrl);

            String sshMuxOverride = "-o ControlMaster=no -o ControlPath=none";
            Platform platform = PlatformDetector.detect();
            if (platform == Platform.MACOS) {
                envVars.add("GIT_SSH_COMMAND=ssh " + sshMuxOverride
                    + " -o ProxyCommand='nc -X 5 -x localhost:" + socksProxyPort + " %h %p'");
            } else if (platform == Platform.LINUX && httpProxyPort != null) {
                envVars.add("GIT_SSH_COMMAND=ssh " + sshMuxOverride
                    + " -o ProxyCommand='socat - PROXY:localhost:%h:%p,proxyport=" + httpProxyPort + "'");
            }

            envVars.add("FTP_PROXY=" + socksUrl);
            envVars.add("ftp_proxy=" + socksUrl);
            envVars.add("RSYNC_PROXY=localhost:" + socksProxyPort);

            int dockerPort = httpProxyPort != null ? httpProxyPort : socksProxyPort;
            envVars.add("DOCKER_HTTP_PROXY=http://localhost:" + dockerPort);
            envVars.add("DOCKER_HTTPS_PROXY=http://localhost:" + dockerPort);

            if (httpProxyPort != null) {
                envVars.add("CLOUDSDK_PROXY_TYPE=http");
                envVars.add("CLOUDSDK_PROXY_ADDRESS=localhost");
                envVars.add("CLOUDSDK_PROXY_PORT=" + httpProxyPort);
            }

            envVars.add("GRPC_PROXY=" + socksUrl);
            envVars.add("grpc_proxy=" + socksUrl);
        }

        return envVars;
    }

    private static String getEnvOr(String key, String defaultValue) {
        String val = System.getenv(key);
        return val != null && !val.isEmpty() ? val : defaultValue;
    }
}
