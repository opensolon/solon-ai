package org.noear.solon.ai.sandbox.platform;

import org.noear.solon.ai.sandbox.FsReadRestrictionConfig;
import org.noear.solon.ai.sandbox.FsWriteRestrictionConfig;
import org.noear.solon.ai.sandbox.SandboxException;
import org.noear.solon.ai.sandbox.SandboxLog;
import org.noear.solon.ai.sandbox.config.RipgrepConfig;
import org.noear.solon.ai.sandbox.config.SeccompConfig;
import org.noear.solon.ai.sandbox.util.ProxyEnvUtils;
import org.noear.solon.ai.sandbox.util.SandboxPathUtils;
import org.noear.solon.ai.sandbox.util.ShellQuote;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Linux sandbox backend using bubblewrap (bwrap) for filesystem, network, and PID isolation.
 *
 * <p>Ports linux-sandbox-utils.ts faithfully. Key architecture:
 * <ul>
 *   <li>Network: --unshare-net + socat Unix socket bridges for filtered proxy access</li>
 *   <li>Filesystem: --ro-bind / / for write restrictions, --bind for allowed writes,
 *       --tmpfs for read denies, --ro-bind /dev/null for file denies</li>
 *   <li>PID namespace: --unshare-pid + --proc /proc (or weaker --unshare-user for containers)</li>
 *   <li>Optional seccomp filter for Unix socket blocking</li>
 * </ul>
 */
public class LinuxSandboxBackend {

    // ========================================================================
    // Constants
    // ========================================================================

    /** Default max depth for searching dangerous files via ripgrep */
    private static final int DEFAULT_MANDATORY_DENY_SEARCH_DEPTH = 3;

    /** Internal HTTP proxy listener port inside the sandbox */
    private static final int INTERNAL_HTTP_PORT = 3128;

    /** Internal SOCKS proxy listener port inside the sandbox */
    private static final int INTERNAL_SOCKS_PORT = 1080;

    // ========================================================================
    // Mutable state (guarded by class monitor for thread safety)
    // ========================================================================

    /**
     * Mount point files created by bwrap for non-existent deny paths.
     * When bwrap does --ro-bind /dev/null /nonexistent/path, it creates an empty
     * file on the host as a mount point. These persist after bwrap exits and must
     * be cleaned up explicitly.
     */
    private static final Set<String> bwrapMountPoints = Collections.synchronizedSet(new HashSet<String>());

    /**
     * Number of wrapped commands that have been generated but whose cleanup has
     * not yet run. cleanupBwrapMountPoints() defers file deletion while this is
     * positive, because deleting a mount point file on the host while another
     * bwrap instance is still running detaches that instance's bind mount and
     * the deny rule stops applying inside it.
     */
    private static final AtomicInteger activeSandboxCount = new AtomicInteger(0);

    private static volatile boolean exitHandlerRegistered = false;

    // ========================================================================
    // LinuxNetworkBridgeContext
    // ========================================================================

    /**
     * Context returned by {@link #initializeLinuxNetworkBridge}, holding the
     * Unix socket paths and the host-side socat bridge processes.
     */
    public static class LinuxNetworkBridgeContext {
        public final String httpSocketPath;
        public final String socksSocketPath;
        public final Process httpBridgeProcess;
        public final Process socksBridgeProcess;
        public final int httpProxyPort;
        public final int socksProxyPort;

        public LinuxNetworkBridgeContext(
                String httpSocketPath,
                String socksSocketPath,
                Process httpBridgeProcess,
                Process socksBridgeProcess,
                int httpProxyPort,
                int socksProxyPort) {
            this.httpSocketPath = httpSocketPath;
            this.socksSocketPath = socksSocketPath;
            this.httpBridgeProcess = httpBridgeProcess;
            this.socksBridgeProcess = socksBridgeProcess;
            this.httpProxyPort = httpProxyPort;
            this.socksProxyPort = socksProxyPort;
        }
    }

    // ========================================================================
    // LinuxSandboxParams
    // ========================================================================

    public static class LinuxSandboxParams {
        public String command;
        public boolean needsNetworkRestriction;
        public String httpSocketPath;
        public String socksSocketPath;
        public Integer httpProxyPort;
        public Integer socksProxyPort;
        /** Path to the TLS-termination CA cert; injected as trust env vars. */
        public String caCertPath;
        public FsReadRestrictionConfig readConfig;
        public FsWriteRestrictionConfig writeConfig;
        public Boolean enableWeakerNestedSandbox;
        public Boolean allowAllUnixSockets;
        public String binShell;
        public RipgrepConfig ripgrepConfig;
        /** Maximum directory depth to search for dangerous files (default: 3) */
        public Integer mandatoryDenySearchDepth;
        /** Allow writes to .git/config files (default: false) */
        public Boolean allowGitConfig;
        /** Custom seccomp binary paths */
        public SeccompConfig seccompConfig;
        /** Absolute path to the bwrap binary (default: resolve "bwrap" via PATH) */
        public String bwrapPath;
        /** Absolute path to the socat binary (default: resolve "socat" via PATH) */
        public String socatPath;
    }

    // ========================================================================
    // Exit cleanup handler registration
    // ========================================================================

    /**
     * Register a JVM shutdown hook for bwrap mount point cleanup.
     * Idempotent — only registers once.
     */
    private static void registerExitCleanupHandler() {
        if (exitHandlerRegistered) {
            return;
        }
        synchronized (LinuxSandboxBackend.class) {
            if (exitHandlerRegistered) {
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    cleanupBwrapMountPoints(true);
                }
            }));
            exitHandlerRegistered = true;
        }
    }

    // ========================================================================
    // initializeLinuxNetworkBridge
    // ========================================================================

    /**
     * Initialize the Linux network bridge for sandbox networking.
     *
     * <p>ARCHITECTURE NOTE:
     * Linux network sandboxing uses bwrap --unshare-net which creates a completely isolated
     * network namespace with NO network access. To enable network access, we:
     *
     * <ol>
     *   <li>Host side: Run socat bridges that listen on Unix sockets and forward to host proxy servers
     *       - HTTP bridge: Unix socket -&gt; host HTTP proxy (for HTTP/HTTPS traffic)
     *       - SOCKS bridge: Unix socket -&gt; host SOCKS5 proxy (for SSH/git traffic)</li>
     *   <li>Sandbox side: Bind the Unix sockets into the isolated namespace and run socat listeners
     *       - HTTP listener on port 3128 -&gt; HTTP Unix socket -&gt; host HTTP proxy
     *       - SOCKS listener on port 1080 -&gt; SOCKS Unix socket -&gt; host SOCKS5 proxy</li>
     *   <li>Configure environment:
     *       - HTTP_PROXY=http://localhost:3128 for HTTP/HTTPS tools
     *       - GIT_SSH_COMMAND with socat for SSH through SOCKS5</li>
     * </ol>
     *
     * @param httpProxyPort the host-side HTTP proxy port
     * @param socksProxyPort the host-side SOCKS proxy port
     * @param socatPath optional explicit path to the socat binary
     * @return bridge context with socket paths and processes
     * @throws IOException if bridge processes fail to start
     */
    public static LinuxNetworkBridgeContext initializeLinuxNetworkBridge(
            int httpProxyPort, int socksProxyPort, String socatPath) throws IOException {
        String socat = socatPath != null ? socatPath : "socat";
        String socketId = generateHexId();
        String tmpDir = System.getProperty("java.io.tmpdir");
        String httpSocketPath = new File(tmpDir, "claude-http-" + socketId + ".sock").getAbsolutePath();
        String socksSocketPath = new File(tmpDir, "claude-socks-" + socketId + ".sock").getAbsolutePath();

        // ---- Start HTTP bridge ----
        List<String> httpSocatArgs = Arrays.asList(
            "UNIX-LISTEN:" + httpSocketPath + ",fork,reuseaddr",
            "TCP:localhost:" + httpProxyPort + ",keepalive,keepidle=10,keepintvl=5,keepcnt=3"
        );
        SandboxLog.debug("Starting HTTP bridge: " + socat + " " + joinArgs(httpSocatArgs));

        Process httpBridgeProcess = new ProcessBuilder(buildSocatCmd(socat, httpSocatArgs))
            .redirectErrorStream(true)
            .start();

        if (!httpBridgeProcess.isAlive()) {
            throw new IOException("Failed to start HTTP bridge process");
        }

        // ---- Start SOCKS bridge ----
        List<String> socksSocatArgs = Arrays.asList(
            "UNIX-LISTEN:" + socksSocketPath + ",fork,reuseaddr",
            "TCP:localhost:" + socksProxyPort + ",keepalive,keepidle=10,keepintvl=5,keepcnt=3"
        );
        SandboxLog.debug("Starting SOCKS bridge: " + socat + " " + joinArgs(socksSocatArgs));

        Process socksBridgeProcess = new ProcessBuilder(buildSocatCmd(socat, socksSocatArgs))
            .redirectErrorStream(true)
            .start();

        if (!socksBridgeProcess.isAlive()) {
            // Clean up HTTP bridge
            httpBridgeProcess.destroyForcibly();
            throw new IOException("Failed to start SOCKS bridge process");
        }

        // ---- Wait for both sockets to be ready ----
        int maxAttempts = 5;
        for (int i = 0; i < maxAttempts; i++) {
            if (!httpBridgeProcess.isAlive() || !socksBridgeProcess.isAlive()) {
                httpBridgeProcess.destroyForcibly();
                socksBridgeProcess.destroyForcibly();
                throw new IOException("Linux bridge process died unexpectedly");
            }

            File httpSock = new File(httpSocketPath);
            File socksSock = new File(socksSocketPath);
            if (httpSock.exists() && socksSock.exists()) {
                SandboxLog.debug("Linux bridges ready after " + (i + 1) + " attempts");
                return new LinuxNetworkBridgeContext(
                    httpSocketPath, socksSocketPath,
                    httpBridgeProcess, socksBridgeProcess,
                    httpProxyPort, socksProxyPort);
            }

            if (i == maxAttempts - 1) {
                // Clean up both processes
                httpBridgeProcess.destroyForcibly();
                socksBridgeProcess.destroyForcibly();
                throw new IOException("Failed to create bridge sockets after " + maxAttempts + " attempts");
            }

            try {
                Thread.sleep(i * 100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                httpBridgeProcess.destroyForcibly();
                socksBridgeProcess.destroyForcibly();
                throw new IOException("Interrupted while waiting for bridge sockets", e);
            }
        }

        // Should not reach here, but just in case
        httpBridgeProcess.destroyForcibly();
        socksBridgeProcess.destroyForcibly();
        throw new IOException("Failed to create bridge sockets");
    }

    private static String[] buildSocatCmd(String socat, List<String> args) {
        String[] cmd = new String[1 + args.size()];
        cmd[0] = socat;
        for (int i = 0; i < args.size(); i++) {
            cmd[i + 1] = args.get(i);
        }
        return cmd;
    }

    private static String joinArgs(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(args.get(i));
        }
        return sb.toString();
    }

    private static String generateHexId() {
        byte[] bytes = new byte[8];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    // ========================================================================
    // wrapCommandWithSandboxLinux (main entry point)
    // ========================================================================

    /**
     * Wrap a command with sandbox restrictions on Linux using bubblewrap (bwrap).
     *
     * <p>UNIX SOCKET BLOCKING (APPLY-SECCOMP):
     * This implementation uses a custom apply-seccomp binary to block Unix domain socket
     * creation for user commands while allowing network infrastructure:
     *
     * <p>Stage 1: Outer bwrap - Network and filesystem isolation (NO seccomp)
     *   - Bubblewrap starts with isolated network namespace (--unshare-net)
     *   - Bubblewrap applies PID namespace isolation (--unshare-pid and --proc)
     *   - Filesystem restrictions are applied (read-only mounts, bind mounts, etc.)
     *   - Socat processes start and connect to Unix socket bridges (can use socket(AF_UNIX, ...))
     *
     * <p>Stage 2: apply-seccomp - Nested PID namespace + seccomp filter
     *   - apply-seccomp creates a nested user+PID+mount namespace and remounts /proc
     *   - Inside, apply-seccomp becomes PID 1 (non-dumpable init/reaper)
     *   - Forks, sets PR_SET_NO_NEW_PRIVS, applies seccomp via prctl(PR_SET_SECCOMP)
     *   - Execs user command with seccomp active (cannot create new Unix sockets)
     *   - User command cannot see or ptrace bwrap/bash/socat (separate PID namespace)
     *
     * @param params sandbox parameters
     * @return the wrapped command string suitable for shell execution
     * @throws SandboxException if wrapping fails
     */
    public static String wrapCommandWithSandboxLinux(LinuxSandboxParams params) {
        String command = params.command;
        boolean needsNetworkRestriction = params.needsNetworkRestriction;
        String httpSocketPath = params.httpSocketPath;
        String socksSocketPath = params.socksSocketPath;
        Integer httpProxyPort = params.httpProxyPort;
        Integer socksProxyPort = params.socksProxyPort;
        String caCertPath = params.caCertPath;
        FsReadRestrictionConfig readConfig = params.readConfig;
        FsWriteRestrictionConfig writeConfig = params.writeConfig;
        Boolean enableWeakerNestedSandbox = params.enableWeakerNestedSandbox;
        Boolean allowAllUnixSockets = params.allowAllUnixSockets;
        String binShell = params.binShell;
        RipgrepConfig ripgrepConfig = params.ripgrepConfig;
        int mandatoryDenySearchDepth = params.mandatoryDenySearchDepth != null
            ? params.mandatoryDenySearchDepth : DEFAULT_MANDATORY_DENY_SEARCH_DEPTH;
        boolean allowGitConfig = Boolean.TRUE.equals(params.allowGitConfig);
        SeccompConfig seccompConfig = params.seccompConfig;
        String bwrapPath = params.bwrapPath;
        String socatPath = params.socatPath;

        // Determine if we have restrictions to apply
        // Read: denyOnly pattern - empty array means no restrictions
        // Write: allowOnly pattern - undefined means no restrictions, any config means restrictions
        boolean hasReadRestrictions = readConfig != null && !readConfig.getDenyOnly().isEmpty();
        boolean hasWriteRestrictions = writeConfig != null;

        // Check if we need any sandboxing
        if (!needsNetworkRestriction && !hasReadRestrictions && !hasWriteRestrictions) {
            return command;
        }

        // Mark this sandbox invocation as active. cleanupBwrapMountPoints() will
        // defer file deletion until this (and every other concurrent) invocation
        // has been cleaned up.
        activeSandboxCount.incrementAndGet();

        List<String> bwrapArgs = new ArrayList<>();
        bwrapArgs.add("--new-session");
        bwrapArgs.add("--die-with-parent");
        String applySeccompPrefix = null;

        try {
            // ========== SECCOMP FILTER (Unix Socket Blocking) ==========
            if (!Boolean.TRUE.equals(allowAllUnixSockets)) {
                applySeccompPrefix = resolveApplySeccompPrefix(
                    seccompConfig != null ? seccompConfig.getApplyPath() : null,
                    seccompConfig != null ? seccompConfig.getArgv0() : null
                );

                if (applySeccompPrefix == null) {
                    SandboxLog.debug(
                        "[Sandbox Linux] apply-seccomp binary not available - unix socket blocking disabled. " +
                        "Install @anthropic-ai/sandbox-runtime globally for full protection.");
                } else {
                    SandboxLog.debug("[Sandbox Linux] Applying seccomp filter for Unix socket blocking");
                }
            } else {
                SandboxLog.debug("[Sandbox Linux] Skipping seccomp filter - allowAllUnixSockets is enabled");
            }

            // ========== NETWORK RESTRICTIONS ==========
            if (needsNetworkRestriction) {
                // Always unshare network namespace to isolate network access
                bwrapArgs.add("--unshare-net");

                // If proxy sockets are provided, bind them into the sandbox to allow
                // filtered network access through the proxy.
                if (httpSocketPath != null && socksSocketPath != null) {
                    // Verify socket files still exist before trying to bind them
                    if (!new File(httpSocketPath).exists()) {
                        throw new SandboxException(
                            "Linux HTTP bridge socket does not exist: " + httpSocketPath + ". " +
                            "The bridge process may have died. Try reinitializing the sandbox.");
                    }
                    if (!new File(socksSocketPath).exists()) {
                        throw new SandboxException(
                            "Linux SOCKS bridge socket does not exist: " + socksSocketPath + ". " +
                            "The bridge process may have died. Try reinitializing the sandbox.");
                    }

                    // Bind both sockets into the sandbox
                    bwrapArgs.add("--bind");
                    bwrapArgs.add(httpSocketPath);
                    bwrapArgs.add(httpSocketPath);
                    bwrapArgs.add("--bind");
                    bwrapArgs.add(socksSocketPath);
                    bwrapArgs.add(socksSocketPath);

                    // Add proxy environment variables
                    // HTTP_PROXY points to the socat listener inside the sandbox (port 3128)
                    // which forwards to the Unix socket that bridges to the host's proxy server
                    List<String> proxyEnv = ProxyEnvUtils.generateProxyEnvVars(
                        INTERNAL_HTTP_PORT, INTERNAL_SOCKS_PORT, caCertPath);
                    for (String env : proxyEnv) {
                        int firstEq = env.indexOf('=');
                        if (firstEq > 0) {
                            String key = env.substring(0, firstEq);
                            String value = env.substring(firstEq + 1);
                            bwrapArgs.add("--setenv");
                            bwrapArgs.add(key);
                            bwrapArgs.add(value);
                        }
                    }

                    // Add host proxy port environment variables for debugging/transparency
                    if (httpProxyPort != null) {
                        bwrapArgs.add("--setenv");
                        bwrapArgs.add("CLAUDE_CODE_HOST_HTTP_PROXY_PORT");
                        bwrapArgs.add(String.valueOf(httpProxyPort));
                    }
                    if (socksProxyPort != null) {
                        bwrapArgs.add("--setenv");
                        bwrapArgs.add("CLAUDE_CODE_HOST_SOCKS_PROXY_PORT");
                        bwrapArgs.add(String.valueOf(socksProxyPort));
                    }
                }
                // If no sockets provided, network is completely blocked (--unshare-net without proxy)
            }

            // ========== FILESYSTEM RESTRICTIONS ==========
            List<String> fsArgs = generateFilesystemArgs(
                readConfig, writeConfig, ripgrepConfig,
                mandatoryDenySearchDepth, allowGitConfig);
            bwrapArgs.addAll(fsArgs);

            // Always bind /dev
            bwrapArgs.add("--dev");
            bwrapArgs.add("/dev");

            // ========== PID NAMESPACE ISOLATION ==========
            // IMPORTANT: These must come AFTER filesystem binds for nested bwrap to work
            bwrapArgs.add("--unshare-pid");
            if (!Boolean.TRUE.equals(enableWeakerNestedSandbox)) {
                // Mount fresh /proc if PID namespace is isolated (secure mode)
                bwrapArgs.add("--proc");
                bwrapArgs.add("/proc");
            } else {
                // --unshare-user: bwrap only auto-adds this when EUID != 0. In an
                // unprivileged container (Docker's default: EUID=0 without
                // CAP_SYS_ADMIN), bwrap assumes it has caps, tries direct clone,
                // and EPERMs. Force the userns path so bwrap starts at all.
                //
                // --bind /proc /proc: apply-seccomp's nested-userns path writes
                // /proc/self/setgroups and uid_map. Without --proc above, the
                // --ro-bind / / leaves /proc read-only and those writes EROFS.
                bwrapArgs.add("--unshare-user");
                bwrapArgs.add("--bind");
                bwrapArgs.add("/proc");
                bwrapArgs.add("/proc");
            }

            // ========== COMMAND ==========
            // Use the user's shell (zsh, bash, etc.) to ensure aliases/snapshots work
            // Resolve the full path to the shell binary since bwrap doesn't use $PATH
            String shellName = binShell != null ? binShell : "bash";
            String shell = CommandLookup.which(shellName);
            if (shell == null) {
                throw new SandboxException("Shell '" + shellName + "' not found in PATH");
            }
            bwrapArgs.add("--");
            bwrapArgs.add(shell);
            bwrapArgs.add("-c");

            // With network restrictions, route the command through buildSandboxCommand
            // so socat starts before seccomp is applied. Otherwise invoke apply-seccomp
            // directly if we have a binary.
            if (needsNetworkRestriction && httpSocketPath != null && socksSocketPath != null) {
                String sandboxCommand = buildSandboxCommand(
                    httpSocketPath, socksSocketPath, command,
                    applySeccompPrefix, shell, socatPath);
                bwrapArgs.add(sandboxCommand);
            } else if (applySeccompPrefix != null) {
                String applySeccompCmd = applySeccompPrefix +
                    ShellQuote.quote(Arrays.asList(shell, "-c", command));
                bwrapArgs.add(applySeccompCmd);
            } else {
                bwrapArgs.add(command);
            }

            // Build the full command
            String bwrapBin = bwrapPath != null ? bwrapPath : "bwrap";
            List<String> fullCmd = new ArrayList<>();
            fullCmd.add(bwrapBin);
            fullCmd.addAll(bwrapArgs);

            String wrappedCommand = ShellQuote.quote(fullCmd);

            List<String> restrictions = new ArrayList<>();
            if (needsNetworkRestriction) restrictions.add("network");
            if (hasReadRestrictions || hasWriteRestrictions) restrictions.add("filesystem");
            if (applySeccompPrefix != null) restrictions.add("seccomp(unix-block)");

            SandboxLog.debug("[Sandbox Linux] Wrapped command with bwrap (" +
                joinStrings(restrictions, ", ") + " restrictions)");

            return wrappedCommand;
        } catch (Exception e) {
            // Undo the activeSandboxCount increment — the caller won't call
            // cleanupBwrapMountPoints() for a wrap that threw.
            if (activeSandboxCount.get() > 0) {
                activeSandboxCount.decrementAndGet();
            }
            if (e instanceof SandboxException) {
                throw (SandboxException) e;
            }
            throw new SandboxException("Failed to wrap Linux sandbox command", e);
        }
    }

    private static String joinStrings(List<String> parts, String delimiter) {
        if (parts == null || parts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    // ========================================================================
    // buildSandboxCommand
    // ========================================================================

    /**
     * Build the command that runs inside the sandbox.
     * Sets up HTTP proxy on port 3128 and SOCKS proxy on port 1080.
     *
     * @param httpSocketPath  path to the HTTP Unix socket bridge
     * @param socksSocketPath path to the SOCKS Unix socket bridge
     * @param userCommand     the user's command to run
     * @param applySeccompPrefix optional apply-seccomp prefix string (trailing space), or null
     * @param shell           resolved shell path
     * @param socatPath       optional explicit socat binary path
     * @return the inner command string
     */
    static String buildSandboxCommand(
            String httpSocketPath,
            String socksSocketPath,
            String userCommand,
            String applySeccompPrefix,
            String shell,
            String socatPath) {
        // Default to bash for backward compatibility
        String shellPath = shell != null ? shell : "bash";
        // Host filesystem is bind-mounted into the sandbox, so an explicit
        // socatPath resolves to the same binary inside bwrap.
        String socat = ShellQuote.quoteArg(socatPath != null ? socatPath : "socat");

        String socatCmd1 = socat + " TCP-LISTEN:" + INTERNAL_HTTP_PORT +
            ",fork,reuseaddr UNIX-CONNECT:" + httpSocketPath + " >/dev/null 2>&1 &";
        String socatCmd2 = socat + " TCP-LISTEN:" + INTERNAL_SOCKS_PORT +
            ",fork,reuseaddr UNIX-CONNECT:" + socksSocketPath + " >/dev/null 2>&1 &";
        String trapCmd = "trap \"kill %1 %2 2>/dev/null; exit\" EXIT";

        // apply-seccomp runs after socat so socat can still create Unix sockets.
        if (applySeccompPrefix != null) {
            String applySeccompCmd = applySeccompPrefix +
                ShellQuote.quote(Arrays.asList(shellPath, "-c", userCommand));
            String innerScript = socatCmd1 + "\n" + socatCmd2 + "\n" + trapCmd + "\n" + applySeccompCmd;
            return shellPath + " -c " + ShellQuote.quote(Arrays.asList(innerScript));
        } else {
            String innerScript = socatCmd1 + "\n" + socatCmd2 + "\n" + trapCmd + "\n" +
                "eval " + ShellQuote.quoteArg(userCommand);
            return shellPath + " -c " + ShellQuote.quote(Arrays.asList(innerScript));
        }
    }

    // ========================================================================
    // resolveApplySeccompPrefix
    // ========================================================================

    /**
     * Resolve how to invoke apply-seccomp: either a standalone binary path, or a
     * multicall-binary prefix that dispatches on the ARGV0 env var.
     *
     * <p>Returns a shell-ready string ending in a trailing space — callers append
     * shellquote.quote([shell, '-c', cmd]). Returns null when seccomp is
     * unavailable (no argv0, no binary found).
     *
     * <p>When argv0 is set, applyPath is used verbatim (no existence check); the
     * caller is responsible for ensuring it resolves inside the bwrap namespace.
     *
     * @param applyPath explicit path to apply-seccomp binary, or null
     * @param argv0     multicall-binary argv0 name, or null
     * @return shell prefix string with trailing space, or null if unavailable
     */
    static String resolveApplySeccompPrefix(String applyPath, String argv0) {
        if (argv0 != null) {
            if (applyPath == null || applyPath.isEmpty()) {
                throw new SandboxException("seccompConfig.argv0 requires seccompConfig.applyPath");
            }
            return "ARGV0=" + ShellQuote.quoteArg(argv0) + " " +
                ShellQuote.quoteArg(applyPath) + " ";
        }
        String binary = getApplySeccompBinaryPath(applyPath);
        return binary != null ? ShellQuote.quoteArg(binary) + " " : null;
    }

    /**
     * Find the apply-seccomp binary path.
     *
     * @param customPath explicit path, or null to search PATH
     * @return the resolved binary path, or null if not found
     */
    static String getApplySeccompBinaryPath(String customPath) {
        if (customPath != null && !customPath.isEmpty()) {
            if (CommandLookup.isExecutable(customPath)) {
                return customPath;
            }
            return null;
        }
        // Try to find apply-seccomp via PATH
        return CommandLookup.which("apply-seccomp");
    }

    // ========================================================================
    // generateFilesystemArgs
    // ========================================================================

    /**
     * Generate filesystem bind mount arguments for bwrap.
     *
     * <p>Port of generateFilesystemArgs() from linux-sandbox-utils.ts exactly.
     *
     * @param readConfig                read restriction config, or null
     * @param writeConfig               write restriction config, or null
     * @param ripgrepConfig             ripgrep configuration for mandatory deny scanning
     * @param mandatoryDenySearchDepth  max depth for ripgrep search
     * @param allowGitConfig            whether to allow writes to .git/config
     * @return list of bwrap filesystem arguments
     */
    static List<String> generateFilesystemArgs(
            FsReadRestrictionConfig readConfig,
            FsWriteRestrictionConfig writeConfig,
            RipgrepConfig ripgrepConfig,
            int mandatoryDenySearchDepth,
            boolean allowGitConfig) {

        List<String> args = new ArrayList<>();

        // Collect normalized allowed write paths. Populated in the writeConfig
        // block, read again in the denyRead loop to re-bind writes under tmpfs.
        List<String> allowedWritePaths = new ArrayList<>();
        // denyWrite binds are buffered and emitted after denyRead processing so that
        // a denyRead tmpfs over an ancestor directory doesn't wipe them out.
        List<String> denyWriteArgs = new ArrayList<>();

        // Determine initial root mount based on write restrictions
        if (writeConfig != null) {
            // Write restrictions: Start with read-only root, then allow writes to specific paths
            args.add("--ro-bind");
            args.add("/");
            args.add("/");

            // Allow writes to specific paths
            for (String pathPattern : writeConfig.getAllowOnly()) {
                String normalizedPath = SandboxPathUtils.normalizePathForSandbox(pathPattern);

                SandboxLog.debug("[Sandbox Linux] Processing write path: " + pathPattern + " -> " + normalizedPath);

                // Skip /dev/* paths since --dev /dev already handles them
                if (normalizedPath.startsWith("/dev/")) {
                    SandboxLog.debug("[Sandbox Linux] Skipping /dev path: " + normalizedPath);
                    continue;
                }

                if (!new File(normalizedPath).exists()) {
                    SandboxLog.debug("[Sandbox Linux] Skipping non-existent write path: " + normalizedPath);
                    continue;
                }

                // Check if path is a symlink pointing outside expected boundaries
                // bwrap follows symlinks, so --bind on a symlink makes the target writable
                try {
                    String resolvedPath = new File(normalizedPath).getCanonicalPath();
                    // Trim trailing slashes before comparing: getCanonicalPath never returns
                    // a trailing slash, but normalizedPath may have one, which would cause
                    // a false mismatch and incorrectly treat the path as a symlink.
                    String normalizedForComparison = normalizedPath.replaceAll("/+$", "");
                    if (!resolvedPath.equals(normalizedForComparison) &&
                        SandboxPathUtils.isSymlinkOutsideBoundary(normalizedPath, resolvedPath)) {
                        SandboxLog.debug(
                            "[Sandbox Linux] Skipping symlink write path pointing outside expected location: " +
                            pathPattern + " -> " + resolvedPath);
                        continue;
                    }
                } catch (IOException e) {
                    // realpathSync failed - path might not exist or be accessible, skip it
                    SandboxLog.debug("[Sandbox Linux] Skipping write path that could not be resolved: " + normalizedPath);
                    continue;
                }

                args.add("--bind");
                args.add(normalizedPath);
                args.add(normalizedPath);
                allowedWritePaths.add(normalizedPath);
            }

            // Deny writes within allowed paths (user-specified + mandatory denies)
            List<String> denyPaths = new ArrayList<>();
            if (writeConfig.getDenyWithinAllow() != null) {
                denyPaths.addAll(writeConfig.getDenyWithinAllow());
            }
            denyPaths.addAll(linuxGetMandatoryDenyPaths(
                ripgrepConfig, mandatoryDenySearchDepth, allowGitConfig));

            // Dedup post-normalization
            Set<String> seenDenyWrite = new HashSet<>();
            for (String pathPattern : denyPaths) {
                String normalizedPath = SandboxPathUtils.normalizePathForSandbox(pathPattern);
                if (seenDenyWrite.contains(normalizedPath)) continue;
                seenDenyWrite.add(normalizedPath);

                // Skip /dev/* paths since --dev /dev already handles them
                if (normalizedPath.startsWith("/dev/")) {
                    continue;
                }

                // Check for symlinks in the path - if any parent component is a symlink,
                // mount /dev/null there to prevent symlink replacement attacks.
                String symlinkInPath = findSymlinkInPath(normalizedPath, allowedWritePaths);
                if (symlinkInPath != null) {
                    denyWriteArgs.add("--ro-bind");
                    denyWriteArgs.add("/dev/null");
                    denyWriteArgs.add(symlinkInPath);
                    SandboxLog.debug(
                        "[Sandbox Linux] Mounted /dev/null at symlink " + symlinkInPath +
                        " to prevent symlink replacement attack");
                    continue;
                }

                // Handle non-existent paths by mounting /dev/null to block creation.
                if (!new File(normalizedPath).exists()) {
                    // Fix 1 (worktree): If any existing component in the deny path is a
                    // file (not a directory), skip the deny entirely. You can't mkdir
                    // under a file, so the deny path can never be created.
                    if (hasFileAncestor(normalizedPath)) {
                        SandboxLog.debug(
                            "[Sandbox Linux] Skipping deny path with file ancestor " +
                            "(cannot create paths under a file): " + normalizedPath);
                        continue;
                    }

                    // Find the deepest existing ancestor directory
                    String ancestorPath = new File(normalizedPath).getParent();
                    while (ancestorPath != null && !"/".equals(ancestorPath) &&
                           !new File(ancestorPath).exists()) {
                        ancestorPath = new File(ancestorPath).getParent();
                    }

                    // Only protect if the existing ancestor is within an allowed write path.
                    boolean ancestorIsWithinAllowedPath = false;
                    if (ancestorPath != null) {
                        for (String allowedPath : allowedWritePaths) {
                            if (ancestorPath.startsWith(allowedPath + "/") ||
                                ancestorPath.equals(allowedPath) ||
                                normalizedPath.startsWith(allowedPath + "/")) {
                                ancestorIsWithinAllowedPath = true;
                                break;
                            }
                        }
                    }

                    if (ancestorIsWithinAllowedPath) {
                        String firstNonExistent = findFirstNonExistentComponent(normalizedPath);

                        // Fix 2: If firstNonExistent is an intermediate component (not the
                        // leaf deny path itself), mount a read-only empty directory instead
                        // of /dev/null. This prevents the component from appearing as a file
                        // which breaks tools that expect to traverse it as a directory.
                        if (!firstNonExistent.equals(normalizedPath)) {
                            try {
                                File emptyDir = Files.createTempDirectory("claude-empty-").toFile();
                                denyWriteArgs.add("--ro-bind");
                                denyWriteArgs.add(emptyDir.getAbsolutePath());
                                denyWriteArgs.add(firstNonExistent);
                                bwrapMountPoints.add(firstNonExistent);
                                registerExitCleanupHandler();
                                SandboxLog.debug(
                                    "[Sandbox Linux] Mounted empty dir at " + firstNonExistent +
                                    " to block creation of " + normalizedPath);
                            } catch (IOException e) {
                                SandboxLog.debug(
                                    "[Sandbox Linux] Failed to create empty dir for " + firstNonExistent);
                            }
                        } else {
                            denyWriteArgs.add("--ro-bind");
                            denyWriteArgs.add("/dev/null");
                            denyWriteArgs.add(firstNonExistent);
                            bwrapMountPoints.add(firstNonExistent);
                            registerExitCleanupHandler();
                            SandboxLog.debug(
                                "[Sandbox Linux] Mounted /dev/null at " + firstNonExistent +
                                " to block creation of " + normalizedPath);
                        }
                    } else {
                        SandboxLog.debug(
                            "[Sandbox Linux] Skipping non-existent deny path not within allowed paths: " +
                            normalizedPath);
                    }
                    continue;
                }

                // Only add deny binding if this path is within an allowed write path.
                // Otherwise it's already read-only from the initial --ro-bind / /
                boolean isWithinAllowedPath = false;
                for (String allowedPath : allowedWritePaths) {
                    if (normalizedPath.startsWith(allowedPath + "/") ||
                        normalizedPath.equals(allowedPath)) {
                        isWithinAllowedPath = true;
                        break;
                    }
                }

                if (isWithinAllowedPath) {
                    denyWriteArgs.add("--ro-bind");
                    denyWriteArgs.add(normalizedPath);
                    denyWriteArgs.add(normalizedPath);
                } else {
                    SandboxLog.debug(
                        "[Sandbox Linux] Skipping deny path not within allowed paths: " + normalizedPath);
                }
            }
        } else {
            // No write restrictions: Allow all writes
            args.add("--bind");
            args.add("/");
            args.add("/");
        }
        // denyWriteArgs is emitted after the denyRead loop below.

        // Handle read restrictions by mounting tmpfs over denied paths
        List<String> readDenyPaths = new ArrayList<>();
        List<String> readAllowPaths = new ArrayList<>();
        if (readConfig != null && readConfig.getAllowWithinDeny() != null) {
            for (String p : readConfig.getAllowWithinDeny()) {
                readAllowPaths.add(SandboxPathUtils.normalizePathForSandbox(p));
            }
        }
        // Files masked by --ro-bind /dev/null below. Used to filter denyWriteArgs so
        // that --ro-bind <host> <host> doesn't undo the mask.
        Set<String> maskedFiles = new HashSet<>();

        // --tmpfs / would wipe all prior mounts (ro-bind /, write binds, deny binds).
        // Expand a root deny into its direct children so the existing per-dir tmpfs
        // + re-bind logic applies. Skip /proc and /dev: they're remounted by the
        // caller after this function returns. Skip /sys: kernel interface, tmpfs
        // over it breaks tooling and the host /sys is already read-only via ro-bind.
        Set<String> rootSkip = new HashSet<>(Arrays.asList("proc", "dev", "sys"));
        if (readConfig != null) {
            for (String p : readConfig.getDenyOnly()) {
                if (SandboxPathUtils.normalizePathForSandbox(p).equals("/")) {
                    File rootDir = new File("/");
                    String[] children = rootDir.list();
                    if (children != null) {
                        for (String child : children) {
                            if (!rootSkip.contains(child)) {
                                readDenyPaths.add("/" + child);
                            }
                        }
                    }
                } else {
                    readDenyPaths.add(p);
                }
            }
        }

        // Always hide /etc/ssh/ssh_config.d to avoid permission issues with OrbStack
        // SSH is very strict about config file permissions and ownership, and they can
        // appear wrong inside the sandbox causing "Bad owner or permissions" errors
        if (new File("/etc/ssh/ssh_config.d").exists()) {
            readDenyPaths.add("/etc/ssh/ssh_config.d");
        }

        // Normalize then sort shallow-first so tmpfs over ancestor dirs lands before
        // /dev/null masks on descendant files. Otherwise a file-deny listed before
        // a dir-deny in denyRead gets wiped when the ancestor tmpfs is applied.
        List<String> normalizedDenyPaths = new ArrayList<>();
        for (String p : readDenyPaths) {
            normalizedDenyPaths.add(SandboxPathUtils.normalizePathForSandbox(p));
        }
        Collections.sort(normalizedDenyPaths, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.split("/").length - b.split("/").length;
            }
        });

        for (String normalizedPath : normalizedDenyPaths) {
            if (!new File(normalizedPath).exists()) {
                SandboxLog.debug("[Sandbox Linux] Skipping non-existent read deny path: " + normalizedPath);
                continue;
            }

            String denySep = normalizedPath.equals("/") ? "/" : normalizedPath + "/";
            File denyFile = new File(normalizedPath);
            if (denyFile.isDirectory()) {
                args.add("--tmpfs");
                args.add(normalizedPath);

                // tmpfs wiped any earlier write binds under this path — restore them.
                for (String writePath : allowedWritePaths) {
                    if (writePath.startsWith(denySep) || writePath.equals(normalizedPath)) {
                        args.add("--bind");
                        args.add(writePath);
                        args.add(writePath);
                        SandboxLog.debug(
                            "[Sandbox Linux] Re-bound write path wiped by denyRead tmpfs: " + writePath);
                    }
                }

                // Re-allow specific paths within the denied directory (allowRead overrides denyRead).
                // After mounting tmpfs over the denied dir, bind back the allowed subdirectories
                // so they are readable again.
                for (String allowPath : readAllowPaths) {
                    if (allowPath.startsWith(denySep) || allowPath.equals(normalizedPath)) {
                        if (!new File(allowPath).exists()) {
                            SandboxLog.debug(
                                "[Sandbox Linux] Skipping non-existent read allow path: " + allowPath);
                            continue;
                        }
                        // Skip only if a write path was re-bound just above AND covers
                        // allowPath. A write path that's an ancestor of the deny dir isn't
                        // re-bound (it wasn't wiped), so allowPath under it still needs
                        // its own ro-bind here.
                        boolean coveredByWriteBind = false;
                        for (String w : allowedWritePaths) {
                            if ((w.startsWith(denySep) || w.equals(normalizedPath)) &&
                                (allowPath.equals(w) || allowPath.startsWith(w + "/"))) {
                                coveredByWriteBind = true;
                                break;
                            }
                        }
                        if (coveredByWriteBind) {
                            continue;
                        }
                        // Bind the allowed path back over the tmpfs so it's readable
                        args.add("--ro-bind");
                        args.add(allowPath);
                        args.add(allowPath);
                        SandboxLog.debug(
                            "[Sandbox Linux] Re-allowed read access within denied region: " + allowPath);
                    }
                }
            } else {
                // For files, only an exact allowRead match overrides the deny. A
                // directory allowRead does not un-deny a file specifically listed in
                // denyRead — otherwise denyRead: ['.env'] + allowRead: ['.'] silently
                // drops the .env deny.
                if (readAllowPaths.contains(normalizedPath)) {
                    SandboxLog.debug(
                        "[Sandbox Linux] Skipping read deny for re-allowed path: " + normalizedPath);
                    continue;
                }
                // For files, bind /dev/null instead of tmpfs
                args.add("--ro-bind");
                args.add("/dev/null");
                args.add(normalizedPath);
                maskedFiles.add(normalizedPath);
            }
        }

        // Emitting denyWrite last means these ro-binds layer on top of any write
        // paths the denyRead loop just re-bound. But skip any dest already masked
        // by denyRead — --ro-bind <host> <host> for denyWrite would undo
        // --ro-bind /dev/null <host> from denyRead, which landed first.
        for (int i = 0; i < denyWriteArgs.size(); i += 3) {
            String dest = denyWriteArgs.get(i + 2);
            if (maskedFiles.contains(dest)) continue;
            args.add(denyWriteArgs.get(i));
            args.add(denyWriteArgs.get(i + 1));
            args.add(dest);
        }

        return args;
    }

    // ========================================================================
    // Symlink / path helper methods
    // ========================================================================

    /**
     * Find if any component of the path is a symlink within the allowed write paths.
     * Returns the symlink path if found, or null if no symlinks.
     *
     * <p>This is used to detect and block symlink replacement attacks where an attacker
     * could delete a symlink and create a real directory with malicious content.
     *
     * @param targetPath       the path to check
     * @param allowedWritePaths list of allowed write paths
     * @return the symlink path if found within an allowed write path, or null
     */
    static String findSymlinkInPath(String targetPath, List<String> allowedWritePaths) {
        String[] parts = targetPath.split(File.separator);
        String currentPath = "";

        for (String part : parts) {
            if (part.isEmpty()) continue; // Skip empty parts (leading /)
            String nextPath = currentPath + File.separator + part;

            try {
                if (Files.isSymbolicLink(Paths.get(nextPath))) {
                    // Check if this symlink is within an allowed write path
                    for (String allowedPath : allowedWritePaths) {
                        if (nextPath.startsWith(allowedPath + "/") || nextPath.equals(allowedPath)) {
                            return nextPath;
                        }
                    }
                }
            } catch (Exception e) {
                // Path doesn't exist - no symlink issue here
                break;
            }
            currentPath = nextPath;
        }

        return null;
    }

    /**
     * Check if any existing component in the path is a file (not a directory).
     * If so, the target path can never be created because you can't mkdir under a file.
     *
     * <p>This handles the git worktree case: .git is a file, so .git/hooks can never
     * exist and there's nothing to deny.
     *
     * @param targetPath the path to check
     * @return true if any ancestor is a regular file
     */
    static boolean hasFileAncestor(String targetPath) {
        String[] parts = targetPath.split(File.separator);
        String currentPath = "";

        for (String part : parts) {
            if (part.isEmpty()) continue;
            String nextPath = currentPath + File.separator + part;
            File f = new File(nextPath);
            try {
                // Resolve symlinks to get the actual file type
                File resolvedFile = f.exists() ? f.getCanonicalFile() : f;
                if (resolvedFile.exists()) {
                    if (resolvedFile.isFile()) {
                        return true;
                    }
                } else {
                    break;
                }
            } catch (IOException e) {
                break;
            }
            currentPath = nextPath;
        }

        return false;
    }

    /**
     * Find the first non-existent path component.
     * E.g., for "/existing/parent/nonexistent/child/file.txt" where /existing/parent exists,
     * returns "/existing/parent/nonexistent"
     *
     * <p>This is used to block creation of non-existent deny paths by mounting /dev/null
     * at the first missing component, preventing mkdir from creating the parent directories.
     *
     * @param targetPath the path to analyze
     * @return the first non-existent component path
     */
    static String findFirstNonExistentComponent(String targetPath) {
        String[] parts = targetPath.split(File.separator);
        String currentPath = "";

        for (String part : parts) {
            if (part.isEmpty()) continue; // Skip empty parts (leading /)
            String nextPath = currentPath + File.separator + part;
            if (!new File(nextPath).exists()) {
                return nextPath;
            }
            currentPath = nextPath;
        }

        return targetPath; // Shouldn't reach here if called correctly
    }

    // ========================================================================
    // linuxGetMandatoryDenyPaths
    // ========================================================================

    /**
     * Get mandatory deny paths using ripgrep (Linux only).
     * Uses a SINGLE ripgrep call with multiple glob patterns for efficiency.
     * With --max-depth limiting, this is fast enough to run on each command without memoization.
     *
     * @param ripgrepConfig            ripgrep command configuration, or null for default
     * @param maxDepth                 max directory depth for ripgrep search
     * @param allowGitConfig           whether .git/config writes are allowed
     * @return list of absolute paths to deny
     */
    static List<String> linuxGetMandatoryDenyPaths(
            RipgrepConfig ripgrepConfig,
            int maxDepth,
            boolean allowGitConfig) {
        String cwd = System.getProperty("user.dir");
        List<String> dangerousDirs = SandboxPathUtils.getDangerousDirectories();

        // Dangerous files and directories in CWD
        List<String> denyPaths = new ArrayList<>();
        for (String f : SandboxPathUtils.DANGEROUS_FILES) {
            denyPaths.add(new File(cwd, f).getAbsolutePath());
        }
        for (String d : dangerousDirs) {
            denyPaths.add(new File(cwd, d).getAbsolutePath());
        }

        // Git hooks and config are only denied when .git exists as a directory.
        // In git worktrees, .git is a file (e.g., "gitdir: /path/..."), so
        // .git/hooks can never exist — denying it would cause bwrap to fail.
        // When .git doesn't exist at all, mounting at .git would block its
        // creation and break git init.
        File dotGitPath = new File(cwd, ".git");
        boolean dotGitIsDirectory = dotGitPath.isDirectory();

        if (dotGitIsDirectory) {
            // Git hooks always blocked for security
            denyPaths.add(new File(cwd, ".git/hooks").getAbsolutePath());

            // Git config conditionally blocked based on allowGitConfig setting
            if (!allowGitConfig) {
                denyPaths.add(new File(cwd, ".git/config").getAbsolutePath());
            }
        }

        // Build iglob args for all patterns in one ripgrep call
        List<String> iglobArgs = new ArrayList<>();
        for (String fileName : SandboxPathUtils.DANGEROUS_FILES) {
            iglobArgs.add("--iglob");
            iglobArgs.add(fileName);
        }
        for (String dirName : dangerousDirs) {
            iglobArgs.add("--iglob");
            iglobArgs.add("**/" + dirName + "/**");
        }
        // Git hooks always blocked in nested repos
        iglobArgs.add("--iglob");
        iglobArgs.add("**/.git/hooks/**");

        // Git config conditionally blocked in nested repos
        if (!allowGitConfig) {
            iglobArgs.add("--iglob");
            iglobArgs.add("**/.git/config");
        }

        // Single ripgrep call to find all dangerous paths in subdirectories
        String rgCommand = "rg";
        if (ripgrepConfig != null && ripgrepConfig.getCommand() != null) {
            rgCommand = ripgrepConfig.getCommand();
        }

        List<String> rgArgs = new ArrayList<>();
        rgArgs.add(rgCommand);
        rgArgs.add("--files");
        rgArgs.add("--hidden");
        rgArgs.add("--max-depth");
        rgArgs.add(String.valueOf(maxDepth));
        rgArgs.addAll(iglobArgs);
        rgArgs.add("-g");
        rgArgs.add("!**/node_modules/**");

        // Append custom ripgrep args if provided
        if (ripgrepConfig != null && ripgrepConfig.getArgs() != null) {
            rgArgs.addAll(ripgrepConfig.getArgs());
        }

        List<String> matches = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(rgArgs);
            pb.directory(new File(cwd));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                matches.add(line.trim());
            }

            int exitCode = process.waitFor();
            if (exitCode != 0 && exitCode != 1) {
                // exit code 1 means "no matches" which is fine
                SandboxLog.debug("[Sandbox] ripgrep exited with code " + exitCode);
            }
        } catch (IOException e) {
            SandboxLog.debug("[Sandbox] ripgrep scan failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SandboxLog.debug("[Sandbox] ripgrep scan interrupted");
        }

        // Process matches
        for (String match : matches) {
            String absolutePath = new File(cwd, match).getAbsolutePath();

            // File inside a dangerous directory -> add the directory path
            boolean foundDir = false;
            List<String> allDirNames = new ArrayList<>(dangerousDirs);
            allDirNames.add(".git");

            for (String dirName : allDirNames) {
                String normalizedDirName = SandboxPathUtils.normalizeCaseForComparison(dirName);
                String[] segments = absolutePath.split(File.separator);
                int dirIndex = -1;
                for (int i = 0; i < segments.length; i++) {
                    if (SandboxPathUtils.normalizeCaseForComparison(segments[i]).equals(normalizedDirName)) {
                        dirIndex = i;
                        break;
                    }
                }
                if (dirIndex != -1) {
                    // For .git, we want hooks/ or config, not the whole .git dir
                    if (".git".equals(dirName)) {
                        StringBuilder gitDirBuilder = new StringBuilder();
                        for (int i = 0; i <= dirIndex; i++) {
                            gitDirBuilder.append(File.separator).append(segments[i]);
                        }
                        String gitDir = gitDirBuilder.toString();
                        if (match.contains(".git/hooks")) {
                            denyPaths.add(gitDir + File.separator + "hooks");
                        } else if (match.contains(".git/config")) {
                            denyPaths.add(gitDir + File.separator + "config");
                        }
                    } else {
                        StringBuilder dirPathBuilder = new StringBuilder();
                        for (int i = 0; i <= dirIndex; i++) {
                            dirPathBuilder.append(File.separator).append(segments[i]);
                        }
                        denyPaths.add(dirPathBuilder.toString());
                    }
                    foundDir = true;
                    break;
                }
            }

            // Dangerous file match
            if (!foundDir) {
                denyPaths.add(absolutePath);
            }
        }

        // Deduplicate
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String p : denyPaths) {
            if (seen.add(p)) {
                result.add(p);
            }
        }
        return result;
    }

    // ========================================================================
    // cleanupBwrapMountPoints
    // ========================================================================

    /**
     * Clean up mount point files created by bwrap for non-existent deny paths.
     *
     * <p>When protecting non-existent deny paths, bwrap creates empty files on the
     * host filesystem as mount points for --ro-bind. These files persist after
     * bwrap exits. This function removes them.
     *
     * <p>This should be called after each sandboxed command completes to prevent
     * ghost dotfiles (e.g. .bashrc, .gitconfig) from appearing in the working
     * directory. It is also called automatically on process exit as a safety net.
     *
     * <p>Each call decrements the active-sandbox counter that was incremented by
     * wrapCommandWithSandboxLinux(). File deletion is deferred until the counter
     * reaches zero. Deleting a mount point file on the host while another bwrap
     * instance is still running detaches that instance's bind mount (the dentry
     * is unhashed, so path lookup no longer finds the mount) and the deny rule
     * stops applying inside that sandbox.
     *
     * <p>Pass {@code force=true} to delete unconditionally — used by the shutdown
     * hook where deferral is not meaningful.
     *
     * @param force if true, delete unconditionally regardless of active count
     */
    public static void cleanupBwrapMountPoints(boolean force) {
        if (!force) {
            if (activeSandboxCount.get() > 0) {
                activeSandboxCount.decrementAndGet();
            }
            if (activeSandboxCount.get() > 0) {
                SandboxLog.debug(
                    "[Sandbox Linux] Deferring mount point cleanup — " +
                    activeSandboxCount.get() + " sandbox(es) still active");
                return;
            }
        } else {
            activeSandboxCount.set(0);
        }

        synchronized (bwrapMountPoints) {
            for (String mountPoint : bwrapMountPoints) {
                try {
                    File f = new File(mountPoint);
                    if (!f.exists()) continue;

                    if (f.isFile() && f.length() == 0) {
                        Files.delete(f.toPath());
                        SandboxLog.debug(
                            "[Sandbox Linux] Cleaned up bwrap mount point (file): " + mountPoint);
                    } else if (f.isDirectory()) {
                        // Empty directory mount points are created for intermediate
                        // components (Fix 2). Only remove if still empty.
                        String[] entries = f.list();
                        if (entries != null && entries.length == 0) {
                            Files.delete(f.toPath());
                            SandboxLog.debug(
                                "[Sandbox Linux] Cleaned up bwrap mount point (dir): " + mountPoint);
                        }
                    }
                } catch (Exception e) {
                    // Ignore cleanup errors — the file may have already been removed
                }
            }
            bwrapMountPoints.clear();
        }
    }

    /**
     * Non-force cleanup — decrements active count and defers if sandboxes are still running.
     */
    public static void cleanupBwrapMountPoints() {
        cleanupBwrapMountPoints(false);
    }

    // ========================================================================
    // Dependency checking (ported from TS)
    // ========================================================================

    /**
     * Detailed status of Linux sandbox dependencies
     */
    public static class LinuxDependencyStatus {
        public final boolean hasBwrap;
        public final boolean hasSocat;
        public final boolean hasSeccompApply;

        public LinuxDependencyStatus(boolean hasBwrap, boolean hasSocat, boolean hasSeccompApply) {
            this.hasBwrap = hasBwrap;
            this.hasSocat = hasSocat;
            this.hasSeccompApply = hasSeccompApply;
        }
    }

    /**
     * Result of checking sandbox dependencies
     */
    public static class SandboxDependencyCheck {
        public final List<String> warnings;
        public final List<String> errors;

        public SandboxDependencyCheck(List<String> warnings, List<String> errors) {
            this.warnings = warnings;
            this.errors = errors;
        }
    }

    /**
     * Get detailed status of Linux sandbox dependencies
     *
     * @param seccompConfig optional seccomp configuration
     * @param bwrapPath     explicit bwrap path, or null
     * @param socatPath     explicit socat path, or null
     * @return dependency status
     */
    public static LinuxDependencyStatus getLinuxDependencyStatus(
            SeccompConfig seccompConfig, String bwrapPath, String socatPath) {
        // argv0 mode: apply-seccomp is compiled into the caller's binary — skip
        // the on-disk lookup and trust that applyPath resolves inside bwrap.
        boolean hasSeccompApply = false;
        if (seccompConfig != null && seccompConfig.getArgv0() != null) {
            hasSeccompApply = true;
        } else {
            hasSeccompApply = getApplySeccompBinaryPath(
                seccompConfig != null ? seccompConfig.getApplyPath() : null) != null;
        }

        return new LinuxDependencyStatus(
            bwrapPath != null ? CommandLookup.isExecutable(bwrapPath)
                : CommandLookup.which("bwrap") != null,
            socatPath != null ? CommandLookup.isExecutable(socatPath)
                : CommandLookup.which("socat") != null,
            hasSeccompApply
        );
    }

    /**
     * Check sandbox dependencies and return structured result
     *
     * @param seccompConfig optional seccomp configuration
     * @param bwrapPath     explicit bwrap path, or null
     * @param socatPath     explicit socat path, or null
     * @return dependency check result with warnings and errors
     */
    public static SandboxDependencyCheck checkLinuxDependencies(
            SeccompConfig seccompConfig, String bwrapPath, String socatPath) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // An explicit override is a directive, not a hint — if it doesn't exist,
        // surface that rather than silently falling back to PATH.
        if (bwrapPath != null) {
            if (!CommandLookup.isExecutable(bwrapPath)) {
                errors.add("bubblewrap (bwrap) not executable at " + bwrapPath);
            }
        } else if (CommandLookup.which("bwrap") == null) {
            errors.add("bubblewrap (bwrap) not installed");
        }

        if (socatPath != null) {
            if (!CommandLookup.isExecutable(socatPath)) {
                errors.add("socat not executable at " + socatPath);
            }
        } else if (CommandLookup.which("socat") == null) {
            errors.add("socat not installed");
        }

        if (seccompConfig == null || seccompConfig.getArgv0() == null) {
            if (getApplySeccompBinaryPath(
                    seccompConfig != null ? seccompConfig.getApplyPath() : null) == null) {
                warnings.add("seccomp not available - unix socket access not restricted");
            }
        }

        return new SandboxDependencyCheck(warnings, errors);
    }

    // ========================================================================
    // Reset (for testing)
    // ========================================================================

    /**
     * Reset internal state. For testing only.
     */
    public static void reset() {
        cleanupBwrapMountPoints(true);
    }
}
