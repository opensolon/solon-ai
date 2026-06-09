package org.noear.solon.ai.sandbox;

import org.noear.solon.ai.sandbox.config.FilesystemConfig;
import org.noear.solon.ai.sandbox.config.NetworkConfig;
import org.noear.solon.ai.sandbox.config.RipgrepConfig;
import org.noear.solon.ai.sandbox.config.SandboxRuntimeConfig;
import org.noear.solon.ai.sandbox.config.SeccompConfig;
import org.noear.solon.ai.sandbox.config.WindowsConfig;
import org.noear.solon.ai.sandbox.platform.DependencyChecker;
import org.noear.solon.ai.sandbox.platform.LinuxSandboxBackend;
import org.noear.solon.ai.sandbox.platform.MacOSSandboxBackend;
import org.noear.solon.ai.sandbox.platform.Platform;
import org.noear.solon.ai.sandbox.platform.PlatformDetector;
import org.noear.solon.ai.sandbox.platform.SandboxDependencyCheck;
import org.noear.solon.ai.sandbox.platform.WindowsSandboxBackend;
import org.noear.solon.ai.sandbox.net.ParentProxyResolver;
import org.noear.solon.ai.sandbox.util.GlobUtils;
import org.noear.solon.ai.sandbox.util.SandboxPathUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Global sandbox manager that handles both network and filesystem restrictions
 * for this session. This runs outside of the sandbox, on the host machine.
 *
 * <p>Ports sandbox-manager.ts faithfully. Key responsibilities:
 * <ul>
 *   <li>Singleton-pattern lifecycle: initialize(), reset()</li>
 *   <li>Builds read/write configs from user settings for platform backends</li>
 *   <li>Delegates to platform-specific sandbox wrappers</li>
 *   <li>Supports live network rule updates via updateConfig()</li>
 *   <li>Cleans up bwrap mount point artifacts via cleanupAfterCommand()</li>
 * </ul>
 */
public final class SandboxManager {

    // ========================================================================
    // Private Module State
    // ========================================================================

    private static SandboxRuntimeConfig config;
    private static Integer httpProxyPort;
    private static Integer socksProxyPort;
    /** LinuxNetworkBridgeContext when on Linux */
    private static Object linuxBridgeContext;
    private static ParentProxyResolver parentProxyResolver;
    private static boolean initialized;

    /** Cleanup shutdown hook registered (idempotent) */
    private static boolean cleanupRegistered = false;

    // ========================================================================
    // Constructor (prevent instantiation)
    // ========================================================================

    private SandboxManager() {}

    // ========================================================================
    // initialize
    // ========================================================================

    /**
     * Initialize the sandbox manager with the given runtime configuration.
     *
     * <p>Stores the config, checks platform dependencies, starts proxy servers
     * (on real platforms, proxy ports are set externally via setProxyPorts()),
     * and registers a JVM shutdown hook for cleanup.
     *
     * @param runtimeConfig      the sandbox runtime configuration
     * @param sandboxAskCallback optional callback for interactive network permission prompts
     * @throws Exception if initialization fails (missing dependencies, etc.)
     */
    public static synchronized void initialize(SandboxRuntimeConfig runtimeConfig,
                                                SandboxAskCallback sandboxAskCallback) throws Exception {
        if (initialized) {
            return;
        }

        // Store config for use by other functions
        config = runtimeConfig;

        // Resolve parent proxy
        if (config.getNetwork() != null && config.getNetwork().getParentProxy() != null) {
            parentProxyResolver = new ParentProxyResolver(config.getNetwork().getParentProxy());
        } else {
            // Try environment variables
            String httpProxy = System.getenv("HTTP_PROXY");
            if (httpProxy == null) httpProxy = System.getenv("http_proxy");
            String httpsProxy = System.getenv("HTTPS_PROXY");
            if (httpsProxy == null) httpsProxy = System.getenv("https_proxy");
            if (httpProxy != null || httpsProxy != null) {
                parentProxyResolver = new ParentProxyResolver(null);
            } else {
                parentProxyResolver = null;
            }
        }

        // Check dependencies
        SandboxDependencyCheck deps = checkDependencies();
        if (deps.hasErrors()) {
            throw new SandboxException(
                "Sandbox dependencies not available: "
                    + joinStrings(deps.getErrors(), ", "));
        }

        // Register cleanup handlers first time
        registerCleanup();

        // Platform-specific initialization
        Platform platform = PlatformDetector.detect();
        if (platform == Platform.LINUX) {
            // Start network bridge if network config exists and proxy ports are set
            // The actual proxy servers would be started externally, and their ports
            // communicated via setProxyPorts(). The bridge context is created when
            // both proxy ports are available.
            if (httpProxyPort != null && socksProxyPort != null) {
                try {
                    linuxBridgeContext = LinuxSandboxBackend.initializeLinuxNetworkBridge(
                        httpProxyPort, socksProxyPort,
                        config.getSocatPath());
                    SandboxLog.debug("Linux network bridge initialized");
                } catch (Exception e) {
                    SandboxLog.error("Failed to initialize Linux network bridge", e);
                    // Non-fatal: sandboxing still works, just no network bridge
                }
            }
        }

        initialized = true;
        SandboxLog.debug("Sandbox initialized");
    }

    // ========================================================================
    // reset
    // ========================================================================

    /**
     * Reset the sandbox manager, stopping all proxy servers and cleaning up resources.
     *
     * <p>Cleans up bwrap mount points (forced), kills Linux bridge processes,
     * deletes Unix socket files, and clears all internal state.
     */
    public static synchronized void reset() {
        // Clean up any leftover bwrap mount points. Force past the
        // active-sandbox counter — reset() means the session is over.
        cleanupBwrapMountPoints(true);

        // Stop Linux bridge processes
        if (linuxBridgeContext != null
            && linuxBridgeContext instanceof LinuxSandboxBackend.LinuxNetworkBridgeContext) {
            LinuxSandboxBackend.LinuxNetworkBridgeContext ctx =
                (LinuxSandboxBackend.LinuxNetworkBridgeContext) linuxBridgeContext;

            // Kill bridge processes
            try {
                ctx.httpBridgeProcess.destroyForcibly();
            } catch (Exception e) {
                // ignore
            }
            try {
                ctx.socksBridgeProcess.destroyForcibly();
            } catch (Exception e) {
                // ignore
            }

            // Clean up socket files
            try {
                if (ctx.httpSocketPath != null) {
                    new File(ctx.httpSocketPath).delete();
                    SandboxLog.debug("Cleaned up HTTP socket");
                }
            } catch (Exception e) {
                SandboxLog.error("HTTP socket cleanup error: " + e.getMessage());
            }
            try {
                if (ctx.socksSocketPath != null) {
                    new File(ctx.socksSocketPath).delete();
                    SandboxLog.debug("Cleaned up SOCKS socket");
                }
            } catch (Exception e) {
                SandboxLog.error("SOCKS socket cleanup error: " + e.getMessage());
            }
        }

        // Clear all state
        linuxBridgeContext = null;
        httpProxyPort = null;
        socksProxyPort = null;
        parentProxyResolver = null;
        config = null;
        initialized = false;

        SandboxLog.debug("Sandbox reset");
    }

    // ========================================================================
    // isSupportedPlatform
    // ========================================================================

    /**
     * Check if the current platform supports sandboxing.
     *
     * @return true if the platform is supported (macOS, Linux non-WSL1, or Windows)
     */
    public static boolean isSupportedPlatform() {
        return PlatformDetector.isSupportedPlatform();
    }

    // ========================================================================
    // isSandboxingEnabled
    // ========================================================================

    /**
     * Check if sandboxing is currently enabled (i.e., initialize() has been called).
     *
     * @return true if config has been set via initialize()
     */
    public static boolean isSandboxingEnabled() {
        return config != null;
    }

    // ========================================================================
    // checkDependencies
    // ========================================================================

    /**
     * Check sandbox dependencies for the current platform.
     *
     * @return dependency check result with errors (fatal) and warnings (degraded)
     */
    public static SandboxDependencyCheck checkDependencies() {
        Platform platform = PlatformDetector.detect();
        RipgrepConfig ripgrepConfig = config != null ? config.getRipgrep() : null;
        SeccompConfig seccompConfig = config != null ? config.getSeccomp() : null;
        String bwrapPath = config != null ? config.getBwrapPath() : null;
        String socatPath = config != null ? config.getSocatPath() : null;

        SandboxDependencyCheck result = DependencyChecker.check(
            platform, ripgrepConfig, seccompConfig, bwrapPath, socatPath);

        // Windows: additional checks
        if (platform == Platform.WINDOWS) {
            SandboxDependencyCheck winDeps = checkWindowsDependencies();
            List<String> allErrors = new ArrayList<>(result.getErrors());
            allErrors.addAll(winDeps.getErrors());
            List<String> allWarnings = new ArrayList<>(result.getWarnings());
            allWarnings.addAll(winDeps.getWarnings());
            return new SandboxDependencyCheck(allErrors, allWarnings);
        }

        return result;
    }

    // ========================================================================
    // getFsReadConfig
    // ========================================================================

    /**
     * Build the filesystem read restriction config from the current settings.
     *
     * <p>For Linux, glob patterns are expanded via ripgrep (if available).
     * For macOS, glob patterns are kept as-is (seatbelt supports regex).
     *
     * @return read restriction config, or an empty config if not initialized
     */
    public static FsReadRestrictionConfig getFsReadConfig() {
        if (config == null) {
            return new FsReadRestrictionConfig(
                Collections.<String>emptyList(),
                Collections.<String>emptyList());
        }

        Platform platform = PlatformDetector.detect();

        // Process denyRead paths
        List<String> denyPaths = new ArrayList<>();
        if (config.getFilesystem() != null && config.getFilesystem().getDenyRead() != null) {
            for (String p : config.getFilesystem().getDenyRead()) {
                String stripped = GlobUtils.removeTrailingGlobSuffix(p);
                if (platform == Platform.LINUX && GlobUtils.containsGlobChars(stripped)) {
                    // Expand glob to concrete paths on Linux (bubblewrap doesn't support globs)
                    List<String> expanded = GlobUtils.expandGlobPattern(p);
                    if (expanded.isEmpty()) {
                        // Glob too broad or base dir doesn't exist - keep the stripped pattern
                        denyPaths.add(stripped);
                    } else {
                        denyPaths.addAll(expanded);
                    }
                } else {
                    denyPaths.add(stripped);
                }
            }
        }

        // Process allowRead paths (re-allow within denied regions)
        List<String> allowPaths = new ArrayList<>();
        if (config.getFilesystem() != null && config.getFilesystem().getAllowRead() != null) {
            for (String p : config.getFilesystem().getAllowRead()) {
                String stripped = GlobUtils.removeTrailingGlobSuffix(p);
                if (platform == Platform.LINUX && GlobUtils.containsGlobChars(stripped)) {
                    List<String> expanded = GlobUtils.expandGlobPattern(p);
                    if (expanded.isEmpty()) {
                        // Glob too broad or base dir doesn't exist - keep the stripped pattern
                        allowPaths.add(stripped);
                    } else {
                        allowPaths.addAll(expanded);
                    }
                } else {
                    allowPaths.add(stripped);
                }
            }
        }

        return new FsReadRestrictionConfig(denyPaths, allowPaths);
    }

    // ========================================================================
    // getFsWriteConfig
    // ========================================================================

    /**
     * Build the filesystem write restriction config from the current settings.
     *
     * <p>For Linux, glob patterns are filtered out (bubblewrap doesn't support globs).
     * The allowOnly list always includes default system write paths.
     *
     * @return write restriction config
     */
    public static FsWriteRestrictionConfig getFsWriteConfig() {
        if (config == null) {
            return new FsWriteRestrictionConfig(
                SandboxPathUtils.getDefaultWritePaths(),
                Collections.<String>emptyList());
        }

        Platform platform = PlatformDetector.detect();

        // Filter out glob patterns on Linux for allowWrite
        List<String> allowPaths = new ArrayList<>();
        if (config.getFilesystem() != null && config.getFilesystem().getAllowWrite() != null) {
            for (String p : config.getFilesystem().getAllowWrite()) {
                String stripped = GlobUtils.removeTrailingGlobSuffix(p);
                if (platform == Platform.LINUX && GlobUtils.containsGlobChars(stripped)) {
                    SandboxLog.debug("Skipping glob pattern on Linux: " + p);
                    continue;
                }
                allowPaths.add(stripped);
            }
        }

        // Filter out glob patterns on Linux for denyWrite
        List<String> denyPaths = new ArrayList<>();
        if (config.getFilesystem() != null && config.getFilesystem().getDenyWrite() != null) {
            for (String p : config.getFilesystem().getDenyWrite()) {
                String stripped = GlobUtils.removeTrailingGlobSuffix(p);
                if (platform == Platform.LINUX && GlobUtils.containsGlobChars(stripped)) {
                    SandboxLog.debug("Skipping glob pattern on Linux: " + p);
                    continue;
                }
                denyPaths.add(stripped);
            }
        }

        // Build allowOnly list: default paths + configured allow paths
        List<String> allAllow = new ArrayList<>(SandboxPathUtils.getDefaultWritePaths());
        allAllow.addAll(allowPaths);

        return new FsWriteRestrictionConfig(allAllow, denyPaths);
    }

    // ========================================================================
    // getNetworkRestrictionConfig
    // ========================================================================

    /**
     * Build the network restriction config from the current settings.
     *
     * @return network restriction config with allowed/denied hosts
     */
    public static NetworkRestrictionConfig getNetworkRestrictionConfig() {
        if (config == null) {
            return new NetworkRestrictionConfig(null, null);
        }

        if (config.getNetwork() == null) {
            return new NetworkRestrictionConfig(null, null);
        }

        List<String> allowedHosts = config.getNetwork().getAllowedDomains();
        List<String> deniedHosts = config.getNetwork().getDeniedDomains();

        boolean hasAllowed = allowedHosts != null && !allowedHosts.isEmpty();
        boolean hasDenied = deniedHosts != null && !deniedHosts.isEmpty();

        if (!hasAllowed && !hasDenied) {
            return new NetworkRestrictionConfig(null, null);
        }

        return new NetworkRestrictionConfig(
            hasAllowed ? allowedHosts : null,
            hasDenied ? deniedHosts : null);
    }

    // ========================================================================
    // Network config accessors
    // ========================================================================

    /**
     * Get the list of allowed Unix socket paths from config.
     */
    public static List<String> getAllowUnixSockets() {
        if (config == null || config.getNetwork() == null) return null;
        return config.getNetwork().getAllowUnixSockets();
    }

    /**
     * Get whether all Unix sockets should be allowed.
     */
    public static Boolean getAllowAllUnixSockets() {
        if (config == null || config.getNetwork() == null) return null;
        return config.getNetwork().getAllowAllUnixSockets();
    }

    /**
     * Get whether local network binding is allowed.
     */
    public static Boolean getAllowLocalBinding() {
        if (config == null || config.getNetwork() == null) return null;
        return config.getNetwork().getAllowLocalBinding();
    }

    /**
     * Get the list of allowed Mach lookup service names.
     */
    public static List<String> getAllowMachLookup() {
        if (config == null || config.getNetwork() == null) return null;
        return config.getNetwork().getAllowMachLookup();
    }

    // ========================================================================
    // Config accessor helpers
    // ========================================================================

    /**
     * Get the ignoreViolations map from config.
     */
    public static Map<String, List<String>> getIgnoreViolations() {
        return config != null ? config.getIgnoreViolations() : null;
    }

    /**
     * Get the enableWeakerNestedSandbox flag from config.
     */
    public static Boolean getEnableWeakerNestedSandbox() {
        return config != null ? config.getEnableWeakerNestedSandbox() : null;
    }

    /**
     * Get the enableWeakerNetworkIsolation flag from config.
     */
    public static Boolean getEnableWeakerNetworkIsolation() {
        return config != null ? config.getEnableWeakerNetworkIsolation() : null;
    }

    /**
     * Get the allowAppleEvents flag from config.
     */
    public static Boolean getAllowAppleEvents() {
        return config != null ? config.getAllowAppleEvents() : null;
    }

    /**
     * Get the ripgrep configuration, falling back to defaults.
     */
    public static RipgrepConfig getRipgrepConfig() {
        if (config != null && config.getRipgrep() != null) {
            return config.getRipgrep();
        }
        return new RipgrepConfig("rg", null, null);
    }

    /**
     * Get the mandatory deny search depth, falling back to default of 3.
     */
    public static int getMandatoryDenySearchDepth() {
        if (config != null && config.getMandatoryDenySearchDepth() != null) {
            return config.getMandatoryDenySearchDepth();
        }
        return 3;
    }

    /**
     * Get whether writing to .git/config is allowed.
     */
    public static boolean getAllowGitConfig() {
        if (config != null && config.getFilesystem() != null
            && config.getFilesystem().getAllowGitConfig() != null) {
            return config.getFilesystem().getAllowGitConfig();
        }
        return false;
    }

    /**
     * Get the seccomp configuration.
     */
    public static SeccompConfig getSeccompConfig() {
        return config != null ? config.getSeccomp() : null;
    }

    // ========================================================================
    // wrapWithSandbox (string-based)
    // ========================================================================

    /**
     * Wrap a command with sandbox restrictions.
     *
     * <p>On macOS and Linux, returns a shell string that can be executed.
     * On Windows, throws an exception — use {@link #wrapWithSandboxArgv} instead.
     *
     * @param command the shell command to wrap
     * @return the wrapped command string
     * @throws Exception if wrapping fails or platform is unsupported
     */
    public static String wrapWithSandbox(String command) throws Exception {
        return wrapWithSandbox(command, null, null);
    }

    /**
     * Wrap a command with sandbox restrictions, optionally with custom config.
     *
     * <p>Builds the read/write configs by merging customConfig with the main config,
     * then delegates to the platform-specific backend.
     *
     * @param command      the shell command to wrap
     * @param binShell     optional shell binary name (e.g. "bash", "zsh")
     * @param customConfig optional custom config overrides (takes precedence over main config)
     * @return the wrapped command string
     * @throws Exception if wrapping fails or platform is unsupported
     */
    public static String wrapWithSandbox(String command, String binShell,
                                          SandboxRuntimeConfig customConfig) throws Exception {
        Platform platform = PlatformDetector.detect();

        // Build configs - merge custom with main
        // If custom is provided, use it; otherwise fall back to main config.
        // If neither exists, defaults to empty arrays (most restrictive).
        // Always include default system write paths (like /dev/null, /tmp/claude).
        //
        // Strip trailing /** and filter remaining globs on Linux (bwrap needs
        // real paths, not globs; macOS subpath matching is also recursive so
        // stripping is harmless there).
        FsWriteRestrictionConfig writeConfig = buildWriteConfig(customConfig);
        FsReadRestrictionConfig readConfig = buildReadConfig(customConfig);

        // Check if network config is specified - this determines if we need network restrictions
        // Network restriction is needed when:
        // 1. customConfig has network.allowedDomains defined (even if empty array = block all)
        // 2. OR config has network.allowedDomains defined (even if empty array = block all)
        // An empty allowedDomains array means "no domains allowed" = block all network access
        boolean hasNetworkConfig = hasNetworkConfig(customConfig);

        // Network RESTRICTION is needed whenever network config is specified
        // This includes empty allowedDomains which means "block all network"
        boolean needsNetworkRestriction = hasNetworkConfig;

        // Network PROXY is needed whenever network config is specified
        // Even with empty allowedDomains, we route through proxy so that:
        // 1. updateConfig() can enable network access for already-running processes
        // 2. The proxy blocks all requests when allowlist is empty
        boolean needsNetworkProxy = hasNetworkConfig;

        // Check custom config to allow pseudo-terminal (can be applied dynamically)
        Boolean allowPty = resolveBoolean(
            customConfig != null ? customConfig.getAllowPty() : null,
            config != null ? config.getAllowPty() : null);

        Boolean allowGitConfig = resolveBoolean(
            customConfig != null && customConfig.getFilesystem() != null
                ? customConfig.getFilesystem().getAllowGitConfig() : null,
            config != null && config.getFilesystem() != null
                ? config.getFilesystem().getAllowGitConfig() : null);

        switch (platform) {
            case MACOS: {
                MacOSSandboxBackend.MacOSSandboxParams params = new MacOSSandboxBackend.MacOSSandboxParams();
                params.command = command;
                params.needsNetworkRestriction = needsNetworkRestriction;
                // Only pass proxy ports if proxy is running (when there are domains to filter)
                params.httpProxyPort = needsNetworkProxy ? httpProxyPort : null;
                params.socksProxyPort = needsNetworkProxy ? socksProxyPort : null;
                params.readConfig = readConfig;
                params.writeConfig = writeConfig;
                params.allowUnixSockets = getAllowUnixSockets();
                params.allowAllUnixSockets = getAllowAllUnixSockets();
                params.allowLocalBinding = getAllowLocalBinding();
                params.allowMachLookup = getAllowMachLookup();
                params.allowPty = allowPty;
                params.allowGitConfig = allowGitConfig;
                params.enableWeakerNetworkIsolation = getEnableWeakerNetworkIsolation();
                params.allowAppleEvents = getAllowAppleEvents();
                params.binShell = binShell;
                return MacOSSandboxBackend.wrapCommandWithSandbox(params);
            }

            case LINUX: {
                LinuxSandboxBackend.LinuxSandboxParams params = new LinuxSandboxBackend.LinuxSandboxParams();
                params.command = command;
                params.needsNetworkRestriction = needsNetworkRestriction;
                // Only pass socket paths if proxy is running (when there are domains to filter)
                params.readConfig = readConfig;
                params.writeConfig = writeConfig;
                params.enableWeakerNestedSandbox = getEnableWeakerNestedSandbox();
                params.allowAllUnixSockets = getAllowAllUnixSockets();
                params.binShell = binShell;
                params.ripgrepConfig = getRipgrepConfig();
                params.mandatoryDenySearchDepth = getMandatoryDenySearchDepth();
                params.allowGitConfig = allowGitConfig;
                params.seccompConfig = getSeccompConfig();
                params.bwrapPath = config != null ? config.getBwrapPath() : null;
                params.socatPath = config != null ? config.getSocatPath() : null;

                if (needsNetworkProxy && linuxBridgeContext != null
                    && linuxBridgeContext instanceof LinuxSandboxBackend.LinuxNetworkBridgeContext) {
                    LinuxSandboxBackend.LinuxNetworkBridgeContext ctx =
                        (LinuxSandboxBackend.LinuxNetworkBridgeContext) linuxBridgeContext;
                    params.httpSocketPath = ctx.httpSocketPath;
                    params.socksSocketPath = ctx.socksSocketPath;
                    params.httpProxyPort = ctx.httpProxyPort;
                    params.socksProxyPort = ctx.socksProxyPort;
                }

                return LinuxSandboxBackend.wrapCommandWithSandboxLinux(params);
            }

            case WINDOWS:
                throw new SandboxException(
                    "wrapWithSandbox() returns a shell string and is not supported " +
                        "on Windows. Use SandboxManager.wrapWithSandboxArgv() and " +
                        "spawn the result with {shell: false}.");

            default:
                throw new SandboxException(
                    "Sandbox configuration is not supported on platform: " + platform);
        }
    }

    // ========================================================================
    // wrapWithSandboxArgv
    // ========================================================================

    /**
     * Wrap a command for the sandbox and return a spawn descriptor:
     * {@code { argv, env }}, suitable for
     * {@code Runtime.exec(argv, envArray)}.
     *
     * <p>On Windows this is the ONLY supported wrap method; {@code env}
     * carries the full proxy set that the sandboxed child inherits.
     * On macOS/Linux {@code argv} is {@code [binShell, "-c", <wrapWithSandbox result>]}
     * (proxy env is baked into that command) and {@code env} is the unchanged
     * system environment, so callers can spawn uniformly across platforms.
     *
     * @param command      the shell command to wrap
     * @param binShell     optional shell binary name
     * @param customConfig optional custom config overrides
     * @return sandbox result with argv and env
     * @throws Exception if wrapping fails
     */
    public static WindowsSandboxBackend.WindowsSandboxResult wrapWithSandboxArgv(
            String command, String binShell,
            SandboxRuntimeConfig customConfig) throws Exception {
        Platform platform = PlatformDetector.detect();

        if (platform == Platform.WINDOWS) {
            boolean hasNetwork = hasNetworkConfig(customConfig);
            WindowsSandboxBackend.WindowsSandboxParams params = new WindowsSandboxBackend.WindowsSandboxParams();
            params.command = command;
            params.group = getWindowsGroupRef();
            params.httpProxyPort = hasNetwork ? httpProxyPort : null;
            params.socksProxyPort = hasNetwork ? socksProxyPort : null;
            params.binShell = binShell;
            return WindowsSandboxBackend.wrapCommandWithSandboxWindows(params);
        }

        // macOS/Linux: delegate to the existing string wrapper, then put
        // the result behind `<shell> -c` so the caller's argv-spawn works.
        String wrapped = wrapWithSandbox(command, binShell, customConfig);
        String shell = binShell != null ? binShell : "/bin/bash";
        List<String> argv = new ArrayList<>();
        argv.add(shell);
        argv.add("-c");
        argv.add(wrapped);
        // Convert System.getenv() to Map<String,String>
        Map<String, String> env = new HashMap<String, String>(System.getenv());
        return new WindowsSandboxBackend.WindowsSandboxResult(argv, env);
    }

    // ========================================================================
    // updateConfig
    // ========================================================================

    /**
     * Update the sandbox configuration in place.
     *
     * <p><b>Network/allowlist changes are a live swap</b>: the running
     * http/socks proxies read the config per-request, so reassigning
     * here takes effect on the next connection with no proxy rebind
     * and no port change — on every platform, including Windows.
     *
     * <p>Filesystem changes (denyRead/denyWrite) are NOT applied live:
     * macOS bakes them into the seatbelt profile at wrap time, and
     * Windows will need an explicit re-stamp. To change FS
     * restrictions, reset() then initialize() with the new config.
     *
     * @param newConfig the new configuration to use
     */
    public static void updateConfig(SandboxRuntimeConfig newConfig) {
        config = newConfig;
        SandboxLog.debug("Sandbox configuration updated");
    }

    // ========================================================================
    // cleanupAfterCommand
    // ========================================================================

    /**
     * Lightweight cleanup to call after each sandboxed command completes.
     *
     * <p>On Linux, bwrap creates empty files on the host filesystem as mount points
     * when protecting non-existent deny paths (e.g. ~/.bashrc, ~/.gitconfig).
     * These persist after bwrap exits. This function removes them.
     *
     * <p>Safe to call on any platform — it's a no-op on macOS.
     * Also called automatically by reset() and on process exit as safety nets.
     */
    public static void cleanupAfterCommand() {
        if (PlatformDetector.detect() == Platform.LINUX) {
            LinuxSandboxBackend.cleanupBwrapMountPoints();
        }
    }

    // ========================================================================
    // getLinuxGlobPatternWarnings
    // ========================================================================

    /**
     * Returns glob patterns from Edit/Read permission rules that are not
     * fully supported on Linux. Returns empty array on macOS or when
     * sandboxing is disabled.
     *
     * <p>Patterns ending with /** are excluded since they work as subpaths.
     *
     * @return list of problematic glob patterns
     */
    public static List<String> getLinuxGlobPatternWarnings() {
        // Only warn on Linux/WSL (bubblewrap doesn't support globs)
        // macOS supports glob patterns via regex conversion
        if (PlatformDetector.detect() != Platform.LINUX || config == null) {
            return Collections.emptyList();
        }

        List<String> globPatterns = new ArrayList<>();

        // Check filesystem paths for glob patterns
        // Note: denyRead is excluded because globs are now expanded to concrete paths on Linux
        List<String> allPaths = new ArrayList<>();
        if (config.getFilesystem() != null) {
            if (config.getFilesystem().getAllowWrite() != null) {
                allPaths.addAll(config.getFilesystem().getAllowWrite());
            }
            if (config.getFilesystem().getDenyWrite() != null) {
                allPaths.addAll(config.getFilesystem().getDenyWrite());
            }
        }

        for (String path : allPaths) {
            // Strip trailing /** since that's just a subpath (directory and everything under it)
            String pathWithoutTrailingStar = GlobUtils.removeTrailingGlobSuffix(path);

            // Only warn if there are still glob characters after removing trailing /**
            if (GlobUtils.containsGlobChars(pathWithoutTrailingStar)) {
                globPatterns.add(path);
            }
        }

        return globPatterns;
    }

    // ========================================================================
    // Getters
    // ========================================================================

    /**
     * Get the current sandbox configuration.
     *
     * @return the current configuration, or null if not initialized
     */
    public static SandboxRuntimeConfig getConfig() {
        return config;
    }

    /**
     * Get the parent proxy resolver.
     *
     * @return the parent proxy resolver, or null if not configured
     */
    public static ParentProxyResolver getParentProxyResolver() {
        return parentProxyResolver;
    }

    /**
     * Get the HTTP proxy port.
     *
     * @return the HTTP proxy port, or null if not set
     */
    public static Integer getProxyPort() {
        return httpProxyPort;
    }

    /**
     * Get the SOCKS proxy port.
     *
     * @return the SOCKS proxy port, or null if not set
     */
    public static Integer getSocksProxyPort() {
        return socksProxyPort;
    }

    /**
     * Get the Linux HTTP socket path (from bridge context).
     *
     * @return the HTTP socket path, or null if not on Linux or bridge not initialized
     */
    public static String getLinuxHttpSocketPath() {
        if (linuxBridgeContext != null
            && linuxBridgeContext instanceof LinuxSandboxBackend.LinuxNetworkBridgeContext) {
            return ((LinuxSandboxBackend.LinuxNetworkBridgeContext) linuxBridgeContext).httpSocketPath;
        }
        return null;
    }

    /**
     * Get the Linux SOCKS socket path (from bridge context).
     *
     * @return the SOCKS socket path, or null if not on Linux or bridge not initialized
     */
    public static String getLinuxSocksSocketPath() {
        if (linuxBridgeContext != null
            && linuxBridgeContext instanceof LinuxSandboxBackend.LinuxNetworkBridgeContext) {
            return ((LinuxSandboxBackend.LinuxNetworkBridgeContext) linuxBridgeContext).socksSocketPath;
        }
        return null;
    }

    // ========================================================================
    // Setters (for external configuration)
    // ========================================================================

    /**
     * Set the proxy ports. Called by external proxy infrastructure when
     * the HTTP and SOCKS proxy servers have been started.
     *
     * @param http the HTTP proxy port
     * @param socks the SOCKS proxy port
     */
    public static void setProxyPorts(int http, int socks) {
        httpProxyPort = http;
        socksProxyPort = socks;
    }

    /**
     * Set the Linux bridge context. Called after the Linux network bridge
     * has been initialized externally.
     *
     * @param ctx the LinuxNetworkBridgeContext
     */
    public static void setLinuxBridgeContext(Object ctx) {
        linuxBridgeContext = ctx;
    }

    // ========================================================================
    // Private helpers
    // ========================================================================

    /**
     * Build the write restriction config for wrapWithSandbox, merging customConfig
     * with the main config.
     */
    private static FsWriteRestrictionConfig buildWriteConfig(SandboxRuntimeConfig customConfig) {
        List<String> userAllowWrite = new ArrayList<>();
        List<String> userDenyWrite = new ArrayList<>();

        if (customConfig != null && customConfig.getFilesystem() != null) {
            userAllowWrite = stripWriteGlobs(customConfig.getFilesystem().getAllowWrite());
            userDenyWrite = stripWriteGlobs(customConfig.getFilesystem().getDenyWrite());
        } else if (config != null && config.getFilesystem() != null) {
            userAllowWrite = stripWriteGlobs(config.getFilesystem().getAllowWrite());
            userDenyWrite = stripWriteGlobs(config.getFilesystem().getDenyWrite());
        }

        // Build allowOnly list: default paths + configured allow paths
        List<String> allAllow = new ArrayList<>(SandboxPathUtils.getDefaultWritePaths());
        allAllow.addAll(userAllowWrite);

        return new FsWriteRestrictionConfig(allAllow, userDenyWrite);
    }

    /**
     * Build the read restriction config for wrapWithSandbox, merging customConfig
     * with the main config.
     */
    private static FsReadRestrictionConfig buildReadConfig(SandboxRuntimeConfig customConfig) {
        List<String> denyRead = new ArrayList<>();
        List<String> allowRead = new ArrayList<>();

        if (customConfig != null && customConfig.getFilesystem() != null) {
            denyRead = stripReadPaths(customConfig.getFilesystem().getDenyRead());
            allowRead = stripReadPaths(customConfig.getFilesystem().getAllowRead());
        } else if (config != null && config.getFilesystem() != null) {
            denyRead = stripReadPaths(config.getFilesystem().getDenyRead());
            allowRead = stripReadPaths(config.getFilesystem().getAllowRead());
        }

        // If TLS termination is configured, add CA cert path to read allow list
        SandboxRuntimeConfig effectiveConfig = customConfig != null ? customConfig : config;
        if (effectiveConfig != null && effectiveConfig.getNetwork() != null
            && effectiveConfig.getNetwork().getTlsTerminate() != null
            && effectiveConfig.getNetwork().getTlsTerminate().getCaCertPath() != null) {
            allowRead.add(effectiveConfig.getNetwork().getTlsTerminate().getCaCertPath());
        }

        return new FsReadRestrictionConfig(denyRead, allowRead);
    }

    /**
     * Strip trailing glob suffixes from write paths and filter out
     * remaining glob patterns on Linux (bubblewrap doesn't support globs).
     */
    private static List<String> stripWriteGlobs(List<String> paths) {
        if (paths == null) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        Platform platform = PlatformDetector.detect();
        for (String p : paths) {
            String stripped = GlobUtils.removeTrailingGlobSuffix(p);
            if (platform == Platform.LINUX && GlobUtils.containsGlobChars(stripped)) {
                SandboxLog.debug("[Sandbox] Skipping glob write pattern on Linux: " + p);
                continue;
            }
            result.add(stripped);
        }
        return result;
    }

    /**
     * Strip trailing glob suffixes from read paths.
     * Unlike write paths, glob patterns in read paths are kept for macOS (seatbelt supports regex)
     * and are expanded to concrete paths on Linux elsewhere.
     */
    private static List<String> stripReadPaths(List<String> paths) {
        if (paths == null) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (String p : paths) {
            String stripped = GlobUtils.removeTrailingGlobSuffix(p);
            result.add(stripped);
        }
        return result;
    }

    /**
     * Check if network config is specified (in customConfig or main config).
     * Network restriction is needed whenever allowedDomains is defined,
     * even if it's an empty array (which means "block all network").
     */
    private static boolean hasNetworkConfig(SandboxRuntimeConfig customConfig) {
        if (customConfig != null && customConfig.getNetwork() != null
            && customConfig.getNetwork().getAllowedDomains() != null) {
            return true;
        }
        if (config != null && config.getNetwork() != null
            && config.getNetwork().getAllowedDomains() != null) {
            return true;
        }
        return false;
    }

    /**
     * Resolve a Boolean value with fallback.
     */
    private static Boolean resolveBoolean(Boolean primary, Boolean fallback) {
        return primary != null ? primary : fallback;
    }

    /**
     * Get the Windows group reference from config.
     */
    private static WindowsSandboxBackend.WindowsGroupRef getWindowsGroupRef() {
        return new WindowsSandboxBackend.WindowsGroupRef(
            config != null && config.getWindows() != null
                ? config.getWindows().getGroupName() : null,
            config != null && config.getWindows() != null
                ? config.getWindows().getGroupSid() : null);
    }

    /**
     * Check Windows-specific dependencies.
     */
    private static SandboxDependencyCheck checkWindowsDependencies() {
        Platform platform = PlatformDetector.detect();
        if (platform != Platform.WINDOWS) {
            return new SandboxDependencyCheck(
                Collections.<String>emptyList(), Collections.<String>emptyList());
        }

        WindowsSandboxBackend.WindowsGroupRef groupRef = getWindowsGroupRef();
        String wfpSublayerGuid = config != null && config.getWindows() != null
            ? config.getWindows().getWfpSublayerGuid() : null;
        return WindowsSandboxBackend.checkWindowsDependencies(groupRef);
    }

    /**
     * Clean up bwrap mount points, optionally forcing regardless of active count.
     */
    private static void cleanupBwrapMountPoints(boolean force) {
        if (PlatformDetector.detect() == Platform.LINUX) {
            LinuxSandboxBackend.cleanupBwrapMountPoints(force);
        }
    }

    /**
     * Register a JVM shutdown hook for cleanup. Idempotent.
     */
    private static void registerCleanup() {
        if (cleanupRegistered) {
            return;
        }
        synchronized (SandboxManager.class) {
            if (cleanupRegistered) {
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        reset();
                    } catch (Exception e) {
                        SandboxLog.error("Cleanup failed in shutdown hook: " + e.getMessage(), e);
                    }
                }
            }));
            cleanupRegistered = true;
        }
    }

    /**
     * Join a list of strings with a delimiter.
     */
    private static String joinStrings(List<String> parts, String delimiter) {
        if (parts == null || parts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
