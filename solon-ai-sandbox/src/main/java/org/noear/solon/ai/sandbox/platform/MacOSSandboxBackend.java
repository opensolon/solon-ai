package org.noear.solon.ai.sandbox.platform;

import org.noear.solon.ai.sandbox.FsReadRestrictionConfig;
import org.noear.solon.ai.sandbox.FsWriteRestrictionConfig;
import org.noear.solon.ai.sandbox.SandboxException;
import org.noear.solon.ai.sandbox.SandboxLog;
import org.noear.solon.ai.sandbox.util.Base64Utils;
import org.noear.solon.ai.sandbox.util.GlobUtils;
import org.noear.solon.ai.sandbox.util.ProxyEnvUtils;
import org.noear.solon.ai.sandbox.util.SandboxPathUtils;
import org.noear.solon.ai.sandbox.util.ShellQuote;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * macOS sandbox-exec backend.
 *
 * Ports macos-sandbox-utils.ts faithfully: generates Seatbelt profiles,
 * wraps commands with /usr/bin/sandbox-exec, and provides mandatory deny
 * patterns for write protection.
 */
public class MacOSSandboxBackend {

    // Session suffix computed once per JVM (matches TS: module-level const)
    private static final String SESSION_SUFFIX;

    static {
        // Equivalent to TS: Math.random().toString(36).slice(2, 11) → 9 random base-36 chars
        String random = Long.toString(
            (long) (Math.random() * Long.MAX_VALUE), 36);
        if (random.length() > 9) {
            random = random.substring(0, 9);
        } else {
            // Pad if short (extremely unlikely)
            while (random.length() < 9) {
                random = random + "0";
            }
        }
        SESSION_SUFFIX = "_" + random + "_SBX";
    }

    // ------------------------------------------------------------------ public API

    /**
     * Get mandatory deny patterns as glob patterns (no filesystem scanning).
     * macOS sandbox profile supports regex/glob matching directly via globToRegex().
     *
     * @param allowGitConfig if true, do not block .git/config
     * @return list of path patterns (absolute + glob) that must always be denied for writes
     */
    public static List<String> macGetMandatoryDenyPatterns(boolean allowGitConfig) {
        String cwd = System.getProperty("user.dir");
        // LinkedHashSet preserves insertion order and deduplicates
        Set<String> denyPaths = new LinkedHashSet<>();

        // Dangerous files - static paths in CWD + glob patterns for subtree
        for (String fileName : SandboxPathUtils.DANGEROUS_FILES) {
            denyPaths.add(new File(cwd, fileName).getAbsolutePath());
            denyPaths.add("**/" + fileName);
        }

        // Dangerous directories
        for (String dirName : SandboxPathUtils.getDangerousDirectories()) {
            denyPaths.add(new File(cwd, dirName).getAbsolutePath());
            denyPaths.add("**/" + dirName + "/**");
        }

        // Git hooks are always blocked for security
        denyPaths.add(new File(cwd, ".git/hooks").getAbsolutePath());
        denyPaths.add("**/.git/hooks/**");

        // Git config - conditionally blocked based on allowGitConfig setting
        if (!allowGitConfig) {
            denyPaths.add(new File(cwd, ".git/config").getAbsolutePath());
            denyPaths.add("**/.git/config");
        }

        return new ArrayList<>(denyPaths);
    }

    /**
     * Wrap the given command with macOS sandbox-exec if any restrictions apply.
     * If no network, read, or write restrictions are configured, returns the
     * original command unchanged.
     *
     * @param params sandbox parameters
     * @return the wrapped command (or original if no restrictions)
     */
    public static String wrapCommandWithSandbox(MacOSSandboxParams params) {
        // Determine if we have restrictions to apply
        // Read: denyOnly pattern - empty array means no restrictions
        // Write: allowOnly pattern - null means no restrictions, any config means restrictions
        boolean hasReadRestrictions =
            params.readConfig != null && !params.readConfig.getDenyOnly().isEmpty();
        boolean hasWriteRestrictions = params.writeConfig != null;

        // No sandboxing needed
        if (!params.needsNetworkRestriction
            && !hasReadRestrictions
            && !hasWriteRestrictions) {
            return params.command;
        }

        String logTag = generateLogTag(params.command);

        String profile = generateSandboxProfile(params, logTag);

        // Generate proxy environment variables using shared utility
        List<String> proxyEnvArgs = ProxyEnvUtils.generateProxyEnvVars(
            params.httpProxyPort, params.socksProxyPort, params.caCertPath);

        // Use the user's shell (zsh, bash, etc.) to ensure aliases/snapshots work
        // Resolve the full path to the shell binary
        String shellName = params.binShell != null ? params.binShell : "bash";
        String shell = CommandLookup.which(shellName);
        if (shell == null) {
            throw new SandboxException("Shell '" + shellName + "' not found in PATH");
        }

        // Use `env` command to set environment variables - each VAR=value is a separate
        // argument that ShellQuote handles properly, avoiding shell quoting issues
        List<String> cmdParts = new ArrayList<>();
        cmdParts.add("env");
        cmdParts.addAll(proxyEnvArgs);
        cmdParts.add("/usr/bin/sandbox-exec");
        cmdParts.add("-p");
        cmdParts.add(profile);
        cmdParts.add(shell);
        cmdParts.add("-c");
        cmdParts.add(params.command);

        String wrappedCommand = ShellQuote.quote(cmdParts);

        SandboxLog.debug("[Sandbox macOS] Applied restrictions - network: "
            + (params.httpProxyPort != null || params.socksProxyPort != null)
            + ", read: " + (params.readConfig != null
                ? "restricted (denyOnly=" + params.readConfig.getDenyOnly().size()
                + ", allowWithinDeny=" + params.readConfig.getAllowWithinDeny().size() + ")"
                : "none")
            + ", write: " + (params.writeConfig != null
                ? "restricted (allowOnly=" + params.writeConfig.getAllowOnly().size()
                + ", denyWithinAllow=" + params.writeConfig.getDenyWithinAllow().size() + ")"
                : "none"));

        return wrappedCommand;
    }

    // ------------------------------------------------------------- params holder

    /**
     * Parameters for macOS sandbox wrapping.
     * Mirrors the MacOSSandboxParams interface from the TS source.
     */
    public static class MacOSSandboxParams {
        /** The shell command to wrap. */
        public String command;
        /** Whether network access should be restricted. */
        public boolean needsNetworkRestriction;
        /** HTTP proxy port to allow. */
        public Integer httpProxyPort;
        /** SOCKS proxy port to allow. */
        public Integer socksProxyPort;
        /** Path to the TLS-termination CA cert; injected as trust env vars. */
        public String caCertPath;
        /** Specific Unix domain socket paths to allow. */
        public List<String> allowUnixSockets;
        /** Whether to allow all Unix domain sockets. */
        public Boolean allowAllUnixSockets;
        /** Whether to allow local network binding. */
        public Boolean allowLocalBinding;
        /** Additional Mach lookup service names to allow. */
        public List<String> allowMachLookup;
        /** Filesystem read restriction config. null = no restrictions. */
        public FsReadRestrictionConfig readConfig;
        /** Filesystem write restriction config. null = no restrictions. */
        public FsWriteRestrictionConfig writeConfig;
        /** Whether to allow pseudo-terminal access. */
        public Boolean allowPty;
        /** Whether to allow writing to .git/config. */
        public Boolean allowGitConfig;
        /** Whether to enable weaker network isolation (allows trustd.agent). */
        public Boolean enableWeakerNetworkIsolation;
        /** Whether to allow Apple Events (open, osascript, etc.). */
        public Boolean allowAppleEvents;
        /** Shell binary name (e.g. "bash", "zsh"). null defaults to "bash". */
        public String binShell;
    }

    // ------------------------------------------------------------- internal helpers

    /**
     * Generate a unique log tag for sandbox monitoring.
     * Format: CMD64_&lt;base64&gt;_END_&lt;random9&gt;_SBX
     *
     * @param command the command being executed (will be base64 encoded)
     */
    private static String generateLogTag(String command) {
        String encoded = Base64Utils.encodeSandboxedCommand(command);
        return "CMD64_" + encoded + "_END" + SESSION_SUFFIX;
    }

    /**
     * Get all ancestor directories for a path, up to (but not including) root.
     * Example: /private/tmp/test/file.txt -> ["/private/tmp/test", "/private/tmp", "/private"]
     *
     * <p>Ports path.dirname walk from the TS source exactly.</p>
     */
    private static List<String> getAncestorDirectories(String pathStr) {
        List<String> ancestors = new ArrayList<>();
        // Start from the parent of the input path (matches TS: path.dirname(pathStr))
        File current = new File(pathStr).getParentFile();

        // Walk up the directory tree until we reach root
        while (current != null
            && !"/".equals(current.getPath())
            && !".".equals(current.getPath())) {
            ancestors.add(current.getPath());
            File parent = current.getParentFile();
            // Break if we've reached the top (getParentFile returns null or same path for root)
            if (parent == null || parent.getPath().equals(current.getPath())) {
                break;
            }
            current = parent;
        }

        return ancestors;
    }

    /**
     * Escape a path string for the Seatbelt sandbox profile using JSON-style quoting.
     * Equivalent to the TS {@code JSON.stringify(pathStr)} which wraps in double quotes
     * and escapes backslashes, quotes, and control characters.
     *
     * @param pathStr the raw path string
     * @return the JSON-quoted string, e.g. {@code "hello\/world"}
     */
    private static String escapePath(String pathStr) {
        StringBuilder sb = new StringBuilder(pathStr.length() + 8);
        sb.append('"');
        for (int i = 0; i < pathStr.length(); i++) {
            char c = pathStr.charAt(i);
            switch (c) {
                case '"':
                    sb.append('\\').append('"');
                    break;
                case '\\':
                    sb.append('\\').append('\\');
                    break;
                case '\b':
                    sb.append('\\').append('b');
                    break;
                case '\f':
                    sb.append('\\').append('f');
                    break;
                case '\n':
                    sb.append('\\').append('n');
                    break;
                case '\r':
                    sb.append('\\').append('r');
                    break;
                case '\t':
                    sb.append('\\').append('t');
                    break;
                default:
                    // JSON.stringify also escapes control chars as backslash-u-hex-hex-hex-hex
                    if (c <= '\u001F') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Generate deny rules for file movement (file-write-unlink) and creation
     * (file-write-create) to protect paths. This prevents bypassing read or write
     * restrictions by moving files/directories, and prevents replacing a
     * not-yet-existing protected path (or one of its ancestors) with an
     * attacker-controlled symlink.
     *
     * @param pathPatterns array of path patterns to protect (can include globs)
     * @param logTag       log tag for sandbox violations
     * @return list of sandbox profile rule lines
     */
    private static List<String> generateMoveBlockingRules(
        List<String> pathPatterns, String logTag) {
        List<String> rules = new ArrayList<>();
        String[] ops = {"file-write-unlink", "file-write-create"};

        for (String pathPattern : pathPatterns) {
            String normalizedPath = SandboxPathUtils.normalizePathForSandbox(pathPattern);

            if (GlobUtils.containsGlobChars(normalizedPath)) {
                // Use regex matching for glob patterns
                String regexPattern = GlobUtils.globToRegex(normalizedPath);

                // Block moving/renaming files matching this pattern
                for (String op : ops) {
                    rules.add("(deny " + op);
                    rules.add("  (regex " + escapePath(regexPattern) + ")");
                    rules.add("  (with message \"" + logTag + "\"))");
                }

                // For glob patterns, extract the static prefix and block ancestor moves
                // Remove glob characters to get the directory prefix
                String staticPrefix = normalizedPath.split("[*?\\[\\]]")[0];
                if (staticPrefix != null && !staticPrefix.isEmpty()
                    && !staticPrefix.equals("/")) {
                    // Get the directory containing the glob pattern
                    String baseDir;
                    if (staticPrefix.endsWith("/")) {
                        baseDir = staticPrefix.substring(0, staticPrefix.length() - 1);
                    } else {
                        baseDir = new File(staticPrefix).getParent();
                    }

                    if (baseDir != null) {
                        // Block moves of the base directory itself
                        for (String op : ops) {
                            rules.add("(deny " + op);
                            rules.add("  (literal " + escapePath(baseDir) + ")");
                            rules.add("  (with message \"" + logTag + "\"))");
                        }

                        // Block moves of ancestor directories
                        for (String ancestorDir : getAncestorDirectories(baseDir)) {
                            for (String op : ops) {
                                rules.add("(deny " + op);
                                rules.add("  (literal " + escapePath(ancestorDir) + ")");
                                rules.add("  (with message \"" + logTag + "\"))");
                            }
                        }
                    }
                }
            } else {
                // Use subpath matching for literal paths

                // Block moving/renaming the denied path itself
                for (String op : ops) {
                    rules.add("(deny " + op);
                    rules.add("  (subpath " + escapePath(normalizedPath) + ")");
                    rules.add("  (with message \"" + logTag + "\"))");
                }

                // Block moves of ancestor directories
                for (String ancestorDir : getAncestorDirectories(normalizedPath)) {
                    for (String op : ops) {
                        rules.add("(deny " + op);
                        rules.add("  (literal " + escapePath(ancestorDir) + ")");
                        rules.add("  (with message \"" + logTag + "\"))");
                    }
                }
            }
        }

        return rules;
    }

    /**
     * Generate filesystem read rules for sandbox profile.
     *
     * <p>Supports two layers:</p>
     * <ol>
     *   <li>denyOnly: deny reads from these paths (broad regions like /Users)</li>
     *   <li>allowWithinDeny: re-allow reads within denied regions (like CWD)</li>
     * </ol>
     * <p>allowWithinDeny takes precedence over denyOnly.</p>
     *
     * <p>In Seatbelt profiles, later rules take precedence, so we emit:</p>
     * <pre>
     *   (allow file-read*)        ← default: allow everything
     *   (deny file-read* ...)     ← deny broad regions
     *   (allow file-read* ...)    ← re-allow specific paths within denied regions
     * </pre>
     *
     * @param config         read restriction config; null means no restrictions
     * @param logTag         log tag for sandbox violations
     * @param writeAllowPaths paths that are write-allowed (used to re-allow
     *                        file-write-unlink/create after move blocking)
     * @return list of sandbox profile rule lines
     */
    private static List<String> generateReadRules(
        FsReadRestrictionConfig config, String logTag, List<String> writeAllowPaths) {
        if (config == null) {
            return Collections.singletonList("(allow file-read*)");
        }

        List<String> rules = new ArrayList<>();
        boolean deniesRoot = false;

        // Start by allowing everything
        rules.add("(allow file-read*)");

        // Then deny specific paths
        for (String pathPattern : config.getDenyOnly()) {
            String normalizedPath = SandboxPathUtils.normalizePathForSandbox(pathPattern);

            if (normalizedPath.equals("/")) {
                deniesRoot = true;
            }

            if (GlobUtils.containsGlobChars(normalizedPath)) {
                // Use regex matching for glob patterns
                String regexPattern = GlobUtils.globToRegex(normalizedPath);
                rules.add("(deny file-read*");
                rules.add("  (regex " + escapePath(regexPattern) + ")");
                rules.add("  (with message \"" + logTag + "\"))");
            } else {
                // Use subpath matching for literal paths
                rules.add("(deny file-read*");
                rules.add("  (subpath " + escapePath(normalizedPath) + ")");
                rules.add("  (with message \"" + logTag + "\"))");
            }
        }

        // (subpath "/") denies the root inode itself; allowWithinDeny subpaths don't
        // cover "/", so dyld aborts before exec. Re-allow the literal root so path
        // traversal works. This exposes `ls /` dirent names but no subtree contents.
        if (deniesRoot) {
            rules.add("(allow file-read* (literal \"/\"))");
        }

        // Re-allow specific paths within denied regions (allowWithinDeny takes precedence)
        for (String pathPattern : config.getAllowWithinDeny()) {
            String normalizedPath = SandboxPathUtils.normalizePathForSandbox(pathPattern);

            if (GlobUtils.containsGlobChars(normalizedPath)) {
                String regexPattern = GlobUtils.globToRegex(normalizedPath);
                rules.add("(allow file-read*");
                rules.add("  (regex " + escapePath(regexPattern) + ")");
                rules.add("  (with message \"" + logTag + "\"))");
            } else {
                rules.add("(allow file-read*");
                rules.add("  (subpath " + escapePath(normalizedPath) + ")");
                rules.add("  (with message \"" + logTag + "\"))");
            }
        }

        // Allow stat/lstat on all directories so that realpath() can traverse
        // path components within denied regions. Without this, C realpath() fails
        // when resolving symlinks because it needs to lstat every intermediate
        // directory (e.g. /Users, /Users/chris) even if only a subdirectory like
        // ~/.local is in allowWithinDeny. This only allows metadata reads on
        // directories — not listing contents (readdir) or reading files.
        if (config.getDenyOnly().size() > 0) {
            rules.add("(allow file-read-metadata");
            rules.add("  (vnode-type DIRECTORY))");
        }

        // Block file movement to prevent bypass via mv/rename
        rules.addAll(generateMoveBlockingRules(config.getDenyOnly(), logTag));

        // Re-allow file-write-unlink / file-write-create for paths that are explicitly
        // write-allowed. The move-blocking rules above emit broad
        // (deny file-write-unlink (subpath "/Users")) to prevent bypassing read
        // restrictions by moving files out of denied regions.
        // However, in macOS Seatbelt, a specific (deny file-write-unlink) is not overridden
        // by a later (allow file-write*) wildcard — the specific operation deny wins.
        // This means file deletions are blocked even in write-allowed directories like
        // the project directory. We fix this by explicitly re-allowing file-write-unlink
        // and file-write-create for write-allowed paths after the move-blocking deny rules.
        //
        // Note: denyWithinAllow paths are not excluded here because the write section's
        // generateMoveBlockingRules() runs later in the profile and re-denies
        // file-write-unlink for those paths (Seatbelt uses last-match-wins). This
        // depends on read rules being emitted before write rules in generateSandboxProfile().
        if (writeAllowPaths != null && writeAllowPaths.size() > 0) {
            for (String pathPattern : writeAllowPaths) {
                String normalizedPath = SandboxPathUtils.normalizePathForSandbox(pathPattern);

                String[] ops = {"file-write-unlink", "file-write-create"};
                for (String op : ops) {
                    if (GlobUtils.containsGlobChars(normalizedPath)) {
                        String regexPattern = GlobUtils.globToRegex(normalizedPath);
                        rules.add("(allow " + op);
                        rules.add("  (regex " + escapePath(regexPattern) + ")");
                        rules.add("  (with message \"" + logTag + "\"))");
                    } else {
                        rules.add("(allow " + op);
                        rules.add("  (subpath " + escapePath(normalizedPath) + ")");
                        rules.add("  (with message \"" + logTag + "\"))");
                    }
                }
            }
        }

        return rules;
    }

    /**
     * Generate filesystem write rules for sandbox profile.
     *
     * @param config         write restriction config; null means no restrictions
     * @param logTag         log tag for sandbox violations
     * @param allowGitConfig whether to allow writing to .git/config
     * @return list of sandbox profile rule lines
     */
    private static List<String> generateWriteRules(
        FsWriteRestrictionConfig config, String logTag, boolean allowGitConfig) {
        if (config == null) {
            return Collections.singletonList("(allow file-write*)");
        }

        List<String> rules = new ArrayList<>();

        // Generate allow rules
        for (String pathPattern : config.getAllowOnly()) {
            String normalizedPath = SandboxPathUtils.normalizePathForSandbox(pathPattern);

            if (GlobUtils.containsGlobChars(normalizedPath)) {
                // Use regex matching for glob patterns
                String regexPattern = GlobUtils.globToRegex(normalizedPath);
                rules.add("(allow file-write*");
                rules.add("  (regex " + escapePath(regexPattern) + ")");
                rules.add("  (with message \"" + logTag + "\"))");
            } else {
                // Use subpath matching for literal paths
                rules.add("(allow file-write*");
                rules.add("  (subpath " + escapePath(normalizedPath) + ")");
                rules.add("  (with message \"" + logTag + "\"))");
            }
        }

        // Combine user-specified and mandatory deny patterns (no ripgrep needed on macOS)
        List<String> denyPaths = new ArrayList<>();
        if (config.getDenyWithinAllow() != null) {
            denyPaths.addAll(config.getDenyWithinAllow());
        }
        denyPaths.addAll(macGetMandatoryDenyPatterns(allowGitConfig));

        for (String pathPattern : denyPaths) {
            String normalizedPath = SandboxPathUtils.normalizePathForSandbox(pathPattern);

            if (GlobUtils.containsGlobChars(normalizedPath)) {
                // Use regex matching for glob patterns
                String regexPattern = GlobUtils.globToRegex(normalizedPath);
                rules.add("(deny file-write*");
                rules.add("  (regex " + escapePath(regexPattern) + ")");
                rules.add("  (with message \"" + logTag + "\"))");
            } else {
                // Use subpath matching for literal paths
                rules.add("(deny file-write*");
                rules.add("  (subpath " + escapePath(normalizedPath) + ")");
                rules.add("  (with message \"" + logTag + "\"))");
            }
        }

        // Block file movement to prevent bypass via mv/rename
        rules.addAll(generateMoveBlockingRules(denyPaths, logTag));

        return rules;
    }

    /**
     * Generate the complete macOS Seatbelt sandbox profile string.
     *
     * <p>Emits (version 1) profile with:</p>
     * <ul>
     *   <li>Essential process/IPC permissions</li>
     *   <li>Network rules (restricted or unrestricted)</li>
     *   <li>Filesystem read rules (deny-then-allow-back pattern)</li>
     *   <li>Filesystem write rules (allow-only with deny-within-allow)</li>
     *   <li>Optional pseudo-terminal support</li>
     * </ul>
     *
     * @param params  sandbox parameters
     * @param logTag  pre-generated log tag for violation messages
     * @return the complete profile string suitable for {@code sandbox-exec -p}
     */
    private static String generateSandboxProfile(MacOSSandboxParams params, String logTag) {
        List<String> p = new ArrayList<>();

        // ---- Header ----
        p.add("(version 1)");
        p.add("(deny default (with message \"" + logTag + "\"))");
        p.add("");
        p.add("; LogTag: " + logTag);
        p.add("");

        // ---- Essential permissions ----
        // Based on Chrome sandbox policy
        p.add("; Essential permissions - based on Chrome sandbox policy");
        p.add("; Process permissions");
        p.add("(allow process-exec)");
        p.add("(allow process-fork)");
        p.add("(allow process-info* (target same-sandbox))");
        p.add("(allow signal (target same-sandbox))");
        p.add("(allow mach-priv-task-port (target same-sandbox))");
        p.add("");
        p.add("; User preferences");
        p.add("(allow user-preference-read)");
        p.add("");

        // ---- Mach IPC - specific services only (no wildcard) ----
        p.add("; Mach IPC - specific services only (no wildcard)");
        p.add("(allow mach-lookup");
        p.add("  (global-name \"com.apple.audio.systemsoundserver\")");
        p.add("  (global-name \"com.apple.distributed_notifications@Uv3\")");
        p.add("  (global-name \"com.apple.FontObjectsServer\")");
        p.add("  (global-name \"com.apple.fonts\")");
        p.add("  (global-name \"com.apple.logd\")");
        p.add("  (global-name \"com.apple.lsd.mapdb\")");
        p.add("  (global-name \"com.apple.PowerManagement.control\")");
        p.add("  (global-name \"com.apple.system.logger\")");
        p.add("  (global-name \"com.apple.system.notification_center\")");
        p.add("  (global-name \"com.apple.system.opendirectoryd.libinfo\")");
        p.add("  (global-name \"com.apple.system.opendirectoryd.membership\")");
        p.add("  (global-name \"com.apple.bsd.dirhelper\")");
        p.add("  (global-name \"com.apple.securityd.xpc\")");
        p.add("  (global-name \"com.apple.coreservices.launchservicesd\")");
        p.add(")");
        p.add("");

        // ---- Weaker network isolation (trustd.agent) ----
        if (Boolean.TRUE.equals(params.enableWeakerNetworkIsolation)) {
            p.add("; trustd.agent - needed for Go TLS certificate verification "
                + "(weaker network isolation)");
            p.add("(allow mach-lookup (global-name \"com.apple.trustd.agent\"))");
            p.add("");
        }

        // ---- Apple Events ----
        if (Boolean.TRUE.equals(params.allowAppleEvents)) {
            p.add("; Apple Events - opt-in; needed for open/osascript to talk "
                + "to other apps (appleeventsd)");
            p.add("(allow appleevent-send)");
            p.add("(allow mach-lookup "
                + "(global-name \"com.apple.coreservices.appleevents\"))");
            p.add("; Launch Services open requests need the lsopen operation plus, on");
            p.add("; macOS 14/15, coreservicesd and the quarantine resolver - without");
            p.add("; these open fails with -10822 kLSServerCommunicationErr or -54");
            p.add("(allow lsopen)");
            p.add("(allow mach-lookup "
                + "(global-name \"com.apple.CoreServices.coreservicesd\"))");
            p.add("(allow mach-lookup "
                + "(global-name \"com.apple.coreservices.quarantine-resolver\"))");
            p.add("");
        }

        // ---- User-specified XPC/Mach services ----
        if (params.allowMachLookup != null && !params.allowMachLookup.isEmpty()) {
            p.add("; User-specified XPC/Mach services");
            for (String name : params.allowMachLookup) {
                if (name.endsWith("*")) {
                    p.add("(allow mach-lookup (global-name-prefix "
                        + escapePath(name.substring(0, name.length() - 1)) + "))");
                } else {
                    p.add("(allow mach-lookup (global-name " + escapePath(name) + "))");
                }
            }
            p.add("");
        }

        // ---- POSIX IPC ----
        p.add("; POSIX IPC - shared memory");
        p.add("(allow ipc-posix-shm)");
        p.add("");
        p.add("; POSIX IPC - semaphores for Python multiprocessing");
        p.add("(allow ipc-posix-sem)");
        p.add("");

        // ---- IOKit ----
        p.add("; IOKit - specific operations only");
        p.add("(allow iokit-open");
        p.add("  (iokit-registry-entry-class \"IOSurfaceRootUserClient\")");
        p.add("  (iokit-registry-entry-class \"RootDomainUserClient\")");
        p.add("  (iokit-user-client-class \"IOSurfaceSendRight\")");
        p.add(")");
        p.add("");
        p.add("; IOKit properties");
        p.add("(allow iokit-get-properties)");
        p.add("");

        // ---- System socket (safe, no network access) ----
        p.add("; Specific safe system-sockets, doesn't allow network access");
        p.add("(allow system-socket (require-all (socket-domain AF_SYSTEM) (socket-protocol 2)))");
        p.add("");

        // ---- sysctl (read) ----
        p.add("; sysctl - specific sysctls only");
        p.add("(allow sysctl-read");
        p.add("  (sysctl-name \"hw.activecpu\")");
        p.add("  (sysctl-name \"hw.busfrequency_compat\")");
        p.add("  (sysctl-name \"hw.byteorder\")");
        p.add("  (sysctl-name \"hw.cacheconfig\")");
        p.add("  (sysctl-name \"hw.cachelinesize_compat\")");
        p.add("  (sysctl-name \"hw.cpufamily\")");
        p.add("  (sysctl-name \"hw.cpufrequency\")");
        p.add("  (sysctl-name \"hw.cpufrequency_compat\")");
        p.add("  (sysctl-name \"hw.cputype\")");
        p.add("  (sysctl-name \"hw.l1dcachesize_compat\")");
        p.add("  (sysctl-name \"hw.l1icachesize_compat\")");
        p.add("  (sysctl-name \"hw.l2cachesize_compat\")");
        p.add("  (sysctl-name \"hw.l3cachesize_compat\")");
        p.add("  (sysctl-name \"hw.logicalcpu\")");
        p.add("  (sysctl-name \"hw.logicalcpu_max\")");
        p.add("  (sysctl-name \"hw.machine\")");
        p.add("  (sysctl-name \"hw.memsize\")");
        p.add("  (sysctl-name \"hw.ncpu\")");
        p.add("  (sysctl-name \"hw.nperflevels\")");
        p.add("  (sysctl-name \"hw.packages\")");
        p.add("  (sysctl-name \"hw.pagesize_compat\")");
        p.add("  (sysctl-name \"hw.pagesize\")");
        p.add("  (sysctl-name \"hw.physicalcpu\")");
        p.add("  (sysctl-name \"hw.physicalcpu_max\")");
        p.add("  (sysctl-name \"hw.tbfrequency_compat\")");
        p.add("  (sysctl-name \"hw.vectorunit\")");
        p.add("  (sysctl-name \"kern.argmax\")");
        p.add("  (sysctl-name \"kern.bootargs\")");
        p.add("  (sysctl-name \"kern.hostname\")");
        p.add("  (sysctl-name \"kern.maxfiles\")");
        p.add("  (sysctl-name \"kern.maxfilesperproc\")");
        p.add("  (sysctl-name \"kern.maxproc\")");
        p.add("  (sysctl-name \"kern.ngroups\")");
        p.add("  (sysctl-name \"kern.osproductversion\")");
        p.add("  (sysctl-name \"kern.osrelease\")");
        p.add("  (sysctl-name \"kern.ostype\")");
        p.add("  (sysctl-name \"kern.osvariant_status\")");
        p.add("  (sysctl-name \"kern.osversion\")");
        p.add("  (sysctl-name \"kern.secure_kernel\")");
        p.add("  (sysctl-name \"kern.tcsm_available\")");
        p.add("  (sysctl-name \"kern.tcsm_enable\")");
        p.add("  (sysctl-name \"kern.usrstack64\")");
        p.add("  (sysctl-name \"kern.version\")");
        p.add("  (sysctl-name \"kern.willshutdown\")");
        p.add("  (sysctl-name \"machdep.cpu.brand_string\")");
        p.add("  (sysctl-name \"machdep.ptrauth_enabled\")");
        p.add("  (sysctl-name \"security.mac.lockdown_mode_state\")");
        p.add("  (sysctl-name \"sysctl.proc_cputype\")");
        p.add("  (sysctl-name \"vm.loadavg\")");
        p.add("  (sysctl-name-prefix \"hw.optional.arm\")");
        p.add("  (sysctl-name-prefix \"hw.optional.arm.\")");
        p.add("  (sysctl-name-prefix \"hw.optional.armv8_\")");
        p.add("  (sysctl-name-prefix \"hw.perflevel\")");
        p.add("  (sysctl-name-prefix \"kern.proc.all\")");
        p.add("  (sysctl-name-prefix \"kern.proc.pgrp.\")");
        p.add("  (sysctl-name-prefix \"kern.proc.pid.\")");
        p.add("  (sysctl-name-prefix \"machdep.cpu.\")");
        p.add("  (sysctl-name-prefix \"net.routetable.\")");
        p.add(")");
        p.add("");

        // ---- sysctl (write) ----
        p.add("; V8 thread calculations");
        p.add("(allow sysctl-write");
        p.add("  (sysctl-name \"kern.tcsm_enable\")");
        p.add(")");
        p.add("");

        // ---- Distributed notifications ----
        p.add("; Distributed notifications");
        p.add("(allow distributed-notification-post)");
        p.add("");

        // ---- Security mach-lookup ----
        p.add("; Specific mach-lookup permissions for security operations");
        p.add("(allow mach-lookup (global-name \"com.apple.SecurityServer\"))");
        p.add("");

        // ---- File I/O on device files ----
        p.add("; File I/O on device files");
        p.add("(allow file-ioctl (literal \"/dev/null\"))");
        p.add("(allow file-ioctl (literal \"/dev/zero\"))");
        p.add("(allow file-ioctl (literal \"/dev/random\"))");
        p.add("(allow file-ioctl (literal \"/dev/urandom\"))");
        p.add("(allow file-ioctl (literal \"/dev/dtracehelper\"))");
        p.add("(allow file-ioctl (literal \"/dev/tty\"))");
        p.add("");
        p.add("(allow file-ioctl file-read-data file-write-data");
        p.add("  (require-all");
        p.add("    (literal \"/dev/null\")");
        p.add("    (vnode-type CHARACTER-DEVICE)");
        p.add("  )");
        p.add(")");
        p.add("");

        // ---- Network rules ----
        p.add("; Network");
        if (!params.needsNetworkRestriction) {
            p.add("(allow network*)");
        } else {
            // Allow local binding if requested
            // Use "*:*" instead of "localhost:*" because modern runtimes (Java, etc.) create
            // IPv6 dual-stack sockets by default. When binding such a socket to 127.0.0.1,
            // the kernel represents it as ::ffff:127.0.0.1 (IPv4-mapped IPv6). Seatbelt's
            // "localhost" filter only matches 127.0.0.1 and ::1, NOT ::ffff:127.0.0.1.
            // Using (local ip "*:*") is safe because it only matches the LOCAL endpoint —
            // internet-bound connections originate from non-loopback interfaces, so they
            // remain blocked by (deny default).
            if (Boolean.TRUE.equals(params.allowLocalBinding)) {
                p.add("(allow network-bind (local ip \"*:*\"))");
                p.add("(allow network-inbound (local ip \"*:*\"))");
                p.add("(allow network-outbound (local ip \"*:*\"))");
            }

            // Unix domain sockets for local IPC (SSH agent, Docker, Gradle, etc.)
            // Three separate operations must be allowed:
            // 1. system-socket: socket(AF_UNIX, ...) syscall — creates the socket fd (no path context)
            // 2. network-bind: bind() to a local Unix socket path
            // 3. network-outbound: connect() to a remote Unix socket path
            // Note: (subpath ...) and (path-regex ...) are path-based filters that can only match
            // bind/connect operations — socket() creation has no path, so it requires system-socket.
            if (Boolean.TRUE.equals(params.allowAllUnixSockets)) {
                // Allow creating AF_UNIX sockets and all Unix socket paths
                p.add("(allow system-socket (socket-domain AF_UNIX))");
                p.add("(allow network-bind (local unix-socket (path-regex #\"^/\")))");
                p.add("(allow network-outbound (remote unix-socket (path-regex #\"^/\")))");
            } else if (params.allowUnixSockets != null
                && !params.allowUnixSockets.isEmpty()) {
                // Allow creating AF_UNIX sockets (required for any Unix socket use)
                p.add("(allow system-socket (socket-domain AF_UNIX))");
                // Allow specific Unix socket paths
                for (String socketPath : params.allowUnixSockets) {
                    String normalizedPath = SandboxPathUtils.normalizePathForSandbox(
                        socketPath);
                    p.add("(allow network-bind (local unix-socket (subpath "
                        + escapePath(normalizedPath) + ")))");
                    p.add("(allow network-outbound (remote unix-socket (subpath "
                        + escapePath(normalizedPath) + ")))");
                }
            }
            // If both allowAllUnixSockets and allowUnixSockets are false/undefined/empty,
            // Unix sockets are blocked by default

            // Allow localhost TCP operations for the HTTP proxy
            if (params.httpProxyPort != null) {
                p.add("(allow network-bind (local ip \"localhost:"
                    + params.httpProxyPort + "\"))");
                p.add("(allow network-inbound (local ip \"localhost:"
                    + params.httpProxyPort + "\"))");
                p.add("(allow network-outbound (remote ip \"localhost:"
                    + params.httpProxyPort + "\"))");
            }

            // Allow localhost TCP operations for the SOCKS proxy
            if (params.socksProxyPort != null) {
                p.add("(allow network-bind (local ip \"localhost:"
                    + params.socksProxyPort + "\"))");
                p.add("(allow network-inbound (local ip \"localhost:"
                    + params.socksProxyPort + "\"))");
                p.add("(allow network-outbound (remote ip \"localhost:"
                    + params.socksProxyPort + "\"))");
            }
        }
        p.add("");

        // ---- File read ----
        // Pass write-allowed paths so that move-blocking deny rules in the read section
        // can be overridden for paths where file deletion should be permitted.
        List<String> writeAllowPaths = params.writeConfig != null
            ? params.writeConfig.getAllowOnly() : null;
        p.add("; File read");
        p.addAll(generateReadRules(params.readConfig, logTag, writeAllowPaths));
        p.add("");

        // ---- File write ----
        p.add("; File write");
        boolean allowGitConfig = Boolean.TRUE.equals(params.allowGitConfig);
        p.addAll(generateWriteRules(params.writeConfig, logTag, allowGitConfig));

        // ---- Pseudo-terminal (pty) support ----
        if (Boolean.TRUE.equals(params.allowPty)) {
            p.add("");
            p.add("; Pseudo-terminal (pty) support");
            p.add("(allow pseudo-tty)");
            p.add("(allow file-ioctl");
            p.add("  (literal \"/dev/ptmx\")");
            p.add("  (regex #\"^/dev/ttys\")");
            p.add(")");
            p.add("(allow file-read* file-write*");
            p.add("  (literal \"/dev/ptmx\")");
            p.add("  (regex #\"^/dev/ttys\")");
            p.add(")");
        }

        return String.join("\n", p);
    }
}
