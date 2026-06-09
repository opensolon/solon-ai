package org.noear.solon.ai.sandbox.platform;

import org.noear.solon.ai.sandbox.SandboxException;
import org.noear.solon.ai.sandbox.SandboxLog;
import org.noear.solon.ai.sandbox.util.ProxyEnvUtils;
import org.noear.solon.ai.sandbox.windows.WindowsGroupStatus;
import org.noear.solon.ai.sandbox.windows.WindowsGroupStatusResult;
import org.noear.solon.ai.sandbox.windows.WindowsInstallOptions;
import org.noear.solon.ai.sandbox.windows.WindowsInstallResult;
import org.noear.solon.ai.sandbox.windows.WindowsWfpStatus;
import org.noear.solon.ai.sandbox.windows.WindowsWfpStatusResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Windows sandbox backend.
 *
 * <p>Network isolation is enforced by {@code srt-win.exe} — a Rust helper that
 * manages a local discriminator group, a machine-wide WFP filter set keyed on
 * that group's SID, and an {@code exec} subcommand that spawns the target under
 * a restricted token (group flipped deny-only) inside a hardened job.
 *
 * <p>This module is a thin wrapper around the {@code srt-win} CLI. All status
 * comes from live enumeration (group via {@code LookupAccountNameW} + token-membership
 * check; WFP via providerData-tag enumeration under the configured sublayer).
 *
 * <p>Port of windows-sandbox-utils.ts.
 */
public class WindowsSandboxBackend {

    public static final String DEFAULT_WINDOWS_GROUP_NAME = "sandbox-runtime-net";
    public static final int[] DEFAULT_WINDOWS_PROXY_PORT_RANGE = {60080, 60089};

    // ========================================================================
    // Inner types: WindowsGroupRef, WindowsSandboxParams, WindowsSandboxResult
    // ========================================================================

    /** Identifies the discriminator group either by name or by SID. */
    public static class WindowsGroupRef {
        public String groupName;
        public String groupSid;

        public WindowsGroupRef(String groupName, String groupSid) {
            this.groupName = groupName;
            this.groupSid = groupSid;
        }
    }

    /** Parameters for building the sandbox spawn descriptor. */
    public static class WindowsSandboxParams {
        public String command;
        public WindowsGroupRef group;
        public Integer httpProxyPort;
        public Integer socksProxyPort;
        public String binShell;
    }

    /** Spawn descriptor: argv array plus env map. */
    public static class WindowsSandboxResult {
        public final List<String> argv;
        public final Map<String, String> env;

        public WindowsSandboxResult(List<String> argv, Map<String, String> env) {
            this.argv = argv;
            this.env = env;
        }
    }

    // ========================================================================
    // Inner type: RunResult
    // ========================================================================

    /** Result of running an srt-win subprocess. */
    private static class RunResult {
        final int status;
        final String stdout;
        final String stderr;

        RunResult(int status, String stdout, String stderr) {
            this.status = status;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    // ========================================================================
    // Binary resolution
    // ========================================================================

    /**
     * Locate {@code srt-win.exe}. Resolution order:
     * <ol>
     *   <li>{@code SRT_WIN_PATH} env var (CI sets this to the freshly-built binary).</li>
     *   <li>{@code <app_root>/vendor/srt-win/target/release/srt-win.exe} (local cargo build).</li>
     *   <li>{@code <app_root>/dist/vendor/srt-win/target/release/srt-win.exe}
     *       (post-build shape, when running from compiled output).</li>
     * </ol>
     *
     * @return the absolute path to srt-win.exe
     * @throws SandboxException if none exist
     */
    public static String getSrtWinPath() {
        // 1. Environment variable override
        String envPath = System.getenv("SRT_WIN_PATH");
        if (envPath != null && new File(envPath).exists()) {
            return envPath;
        }

        // 2. Candidate paths relative to the application working directory
        String root = detectAppRoot();
        String[] candidates;
        if (root != null) {
            candidates = new String[]{
                new File(root, "vendor/srt-win/target/release/srt-win.exe").getAbsolutePath(),
                new File(root, "dist/vendor/srt-win/target/release/srt-win.exe").getAbsolutePath(),
            };
        } else {
            // Fallback: try relative to CWD
            String cwd = System.getProperty("user.dir");
            candidates = new String[]{
                new File(cwd, "vendor/srt-win/target/release/srt-win.exe").getAbsolutePath(),
                new File(cwd, "dist/vendor/srt-win/target/release/srt-win.exe").getAbsolutePath(),
            };
        }

        for (String candidate : candidates) {
            if (new File(candidate).exists()) {
                SandboxLog.debug("[Sandbox Windows] using srt-win at " + candidate);
                return candidate;
            }
        }

        // Build a helpful error message listing what was searched
        StringBuilder searched = new StringBuilder();
        if (envPath != null) {
            searched.append(envPath);
        }
        for (String c : candidates) {
            if (searched.length() > 0) searched.append(", ");
            searched.append(c);
        }

        throw new SandboxException(
            "srt-win.exe not found. Set SRT_WIN_PATH or build with " +
            "`cargo build --release --manifest-path vendor/srt-win/Cargo.toml`. " +
            "Looked in: " + searched.toString());
    }

    /**
     * Detect the application/repository root directory.
     * Walks up from the CWD looking for a marker that identifies the root.
     *
     * @return the root directory path, or null if not detected
     */
    private static String detectAppRoot() {
        // Try the current working directory first
        String dir = System.getProperty("user.dir");
        if (dir != null) {
            File f = new File(dir);
            // Walk up at most 5 levels looking for vendor/ or package.json
            for (int i = 0; i < 5 && f != null; i++) {
                if (new File(f, "vendor").isDirectory() || new File(f, "package.json").exists()) {
                    return f.getAbsolutePath();
                }
                f = f.getParentFile();
            }
        }
        return dir; // fallback to CWD
    }

    // ========================================================================
    // Internal: spawn helpers
    // ========================================================================

    /**
     * Build the CLI arguments that identify a group (--group-sid or --name).
     * Mirrors {@code groupRefArgs()} from the TS source.
     *
     * @param ref group reference (may have groupSid, groupName, or both)
     * @return array of CLI arguments
     */
    static String[] groupRefArgs(WindowsGroupRef ref) {
        if (ref != null && ref.groupSid != null && !ref.groupSid.isEmpty()) {
            return new String[]{"--group-sid", ref.groupSid};
        }
        String name = (ref != null && ref.groupName != null) ? ref.groupName : DEFAULT_WINDOWS_GROUP_NAME;
        return new String[]{"--name", name};
    }

    /**
     * Run an srt-win subprocess and capture its output.
     * Mirrors {@code runSrtWin()} from the TS source.
     *
     * @param args arguments to pass to srt-win
     * @return the run result with exit code, stdout, and stderr
     * @throws SandboxException if the process cannot be started
     */
    static RunResult runSrtWin(String[] args) {
        String exe;
        try {
            exe = getSrtWinPath();
        } catch (SandboxException e) {
            throw new SandboxException("srt-win " + (args.length > 0 ? args[0] : "") + ": " + e.getMessage());
        }

        String[] cmd = new String[1 + args.length];
        cmd[0] = exe;
        System.arraycopy(args, 0, cmd, 1, args.length);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process p = pb.start();

            // Read stdout
            String stdout = readStreamFully(p.getInputStream());

            // Read stderr
            String stderr = readStreamFully(p.getErrorStream());

            boolean finished = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            int exitCode;
            if (!finished) {
                p.destroyForcibly();
                exitCode = -1;
            } else {
                exitCode = p.exitValue();
            }

            return new RunResult(exitCode, stdout.trim(), stderr.trim());
        } catch (IOException e) {
            throw new SandboxException(
                "srt-win " + (args.length > 0 ? args[0] : "") + ": spawn failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxException(
                "srt-win " + (args.length > 0 ? args[0] : "") + ": interrupted", e);
        }
    }

    /**
     * Read an input stream fully into a String. Closes the stream.
     */
    private static String readStreamFully(java.io.InputStream is) throws IOException {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            is.close();
        }
    }

    /**
     * Run an srt-win subcommand and parse its JSON stdout into a structured result.
     * Mirrors {@code runSrtWinJson()} from the TS source.
     *
     * <p>Status subcommands print exactly one line of JSON to stdout.
     * stderr may carry {@code srt-win:} diagnostics — ignore it for parsing.
     *
     * @param args arguments to pass to srt-win
     * @return the parsed JSON output
     * @throws SandboxException if the process fails or output is not valid JSON
     */
    static String runSrtWinJson(String[] args) {
        RunResult r = runSrtWin(args);
        if (r.status != 0) {
            throw new SandboxException(
                "srt-win " + joinArgs(args) + " exited " + r.status + ": " +
                (r.stderr.length() > 0 ? r.stderr : r.stdout));
        }
        return r.stdout;
    }

    /**
     * Join args for display (e.g. error messages).
     */
    private static String joinArgs(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    // ========================================================================
    // Simple JSON parser for srt-win status output
    // ========================================================================

    /**
     * Parse the JSON output of {@code srt-win group status} into a WindowsGroupStatusResult.
     *
     * <p>Expected format:
     * <pre>{@code
     * {"state":"ready","sid":"S-1-5-...","warning":"...","error":"..."}
     * }</pre>
     * All fields except {@code state} are optional.
     */
    static WindowsGroupStatusResult parseGroupStatusJson(String json) {
        String stateStr = extractJsonStringField(json, "state");
        WindowsGroupStatus state = WindowsGroupStatus.fromJsonValue(stateStr);
        String sid = extractJsonStringFieldOrNull(json, "sid");
        String warning = extractJsonStringFieldOrNull(json, "warning");
        String error = extractJsonStringFieldOrNull(json, "error");
        return new WindowsGroupStatusResult(state, sid, warning, error);
    }

    /**
     * Parse the JSON output of {@code srt-win wfp status} into a WindowsWfpStatusResult.
     *
     * <p>Expected format:
     * <pre>{@code
     * {"state":"installed","filters":3,"port_range":[60080,60089]}
     * }</pre>
     * {@code port_range} is optional. The JSON uses {@code port_range} (snake_case)
     * which maps to {@code portRange} in our Java POJO.
     */
    static WindowsWfpStatusResult parseWfpStatusJson(String json) {
        String stateStr = extractJsonStringField(json, "state");
        WindowsWfpStatus state = WindowsWfpStatus.fromJsonValue(stateStr);
        int filters = extractJsonIntField(json, "filters", 0);
        int[] portRange = extractJsonPortRange(json);
        return new WindowsWfpStatusResult(state, filters, portRange);
    }

    /**
     * Extract a required string field from a simple JSON object.
     *
     * @throws SandboxException if the field is not found
     */
    private static String extractJsonStringField(String json, String fieldName) {
        String value = extractJsonStringFieldOrNull(json, fieldName);
        if (value == null) {
            throw new SandboxException("srt-win JSON output missing required field '" + fieldName + "': " + json);
        }
        return value;
    }

    /**
     * Extract an optional string field from a simple JSON object.
     * Returns null if the field is not present or its value is null.
     */
    private static String extractJsonStringFieldOrNull(String json, String fieldName) {
        // Find "fieldName":"value" or "fieldName":null
        String key = "\"" + fieldName + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(':', keyIdx + key.length());
        if (colonIdx < 0) return null;

        // Skip whitespace after colon
        int valStart = colonIdx + 1;
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;

        if (valStart >= json.length()) return null;

        // Check for null
        if (json.startsWith("null", valStart)) return null;

        // Must be a string value starting with "
        if (json.charAt(valStart) != '"') return null;
        valStart++; // skip opening quote

        // Find closing quote (handle escaped quotes)
        StringBuilder sb = new StringBuilder();
        int i = valStart;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"' || next == '\\') {
                    sb.append(next);
                    i += 2;
                    continue;
                } else if (next == 'n') {
                    sb.append('\n');
                    i += 2;
                    continue;
                } else if (next == 't') {
                    sb.append('\t');
                    i += 2;
                    continue;
                } else if (next == 'r') {
                    sb.append('\r');
                    i += 2;
                    continue;
                }
            } else if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
            i++;
        }
        return sb.toString(); // unterminated string, return what we have
    }

    /**
     * Extract an integer field from a simple JSON object.
     *
     * @param json        the JSON string
     * @param fieldName   the field name
     * @param defaultVal  default value if the field is absent
     * @return the integer value
     */
    private static int extractJsonIntField(String json, String fieldName, int defaultVal) {
        String key = "\"" + fieldName + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return defaultVal;

        int colonIdx = json.indexOf(':', keyIdx + key.length());
        if (colonIdx < 0) return defaultVal;

        int valStart = colonIdx + 1;
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;

        if (valStart >= json.length()) return defaultVal;

        int valEnd = valStart;
        if (json.charAt(valEnd) == '-') valEnd++; // negative sign
        while (valEnd < json.length() && Character.isDigit(json.charAt(valEnd))) {
            valEnd++;
        }

        if (valEnd == valStart) return defaultVal;
        try {
            return Integer.parseInt(json.substring(valStart, valEnd));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    /**
     * Extract the {@code port_range} field as an {@code int[2]} from a JSON object.
     * Expected format: {@code "port_range":[60080,60089]}
     * Returns null if the field is absent.
     */
    private static int[] extractJsonPortRange(String json) {
        String key = "\"port_range\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(':', keyIdx + key.length());
        if (colonIdx < 0) return null;

        int arrStart = json.indexOf('[', colonIdx);
        if (arrStart < 0) return null;

        int arrEnd = json.indexOf(']', arrStart);
        if (arrEnd < 0) return null;

        String inner = json.substring(arrStart + 1, arrEnd);
        String[] parts = inner.split(",");
        if (parts.length < 2) return null;

        try {
            int lo = Integer.parseInt(parts[0].trim());
            int hi = Integer.parseInt(parts[1].trim());
            return new int[]{lo, hi};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ========================================================================
    // Status / install API
    // ========================================================================

    /**
     * Query the discriminator group's state in SAM and in the current process's token.
     *
     * <p>{@code ready} means the group exists AND is enabled in the caller's token
     * (i.e. the logout/login dance has happened). {@code created-not-on-token} means
     * the install step ran but a fresh logon is needed before the sandbox can be used.
     *
     * @param ref group reference (by name or SID)
     * @return the group status result
     * @throws SandboxException if srt-win fails
     */
    public static WindowsGroupStatusResult getWindowsGroupStatus(WindowsGroupRef ref) {
        String[] refArgs = groupRefArgs(ref);
        String[] cmd = new String[2 + refArgs.length];
        cmd[0] = "group";
        cmd[1] = "status";
        System.arraycopy(refArgs, 0, cmd, 2, refArgs.length);

        String json = runSrtWinJson(cmd);
        return parseGroupStatusJson(json);
    }

    /**
     * Query the WFP filter set under the given sublayer.
     *
     * <p>{@code installed} means srt-win-tagged {@code permit-group} AND {@code block}
     * filters are both present under that sublayer. Detection is tag-based
     * (providerData JSON); filters installed by other tooling without the tag are not counted.
     *
     * @param sublayerGuid optional sublayer GUID; null for the compile-time default
     * @return the WFP status result
     * @throws SandboxException if srt-win fails
     */
    public static WindowsWfpStatusResult getWindowsWfpStatus(String sublayerGuid) {
        List<String> argList = new ArrayList<String>();
        argList.add("wfp");
        argList.add("status");
        if (sublayerGuid != null && !sublayerGuid.isEmpty()) {
            argList.add("--sublayer-guid");
            argList.add(sublayerGuid);
        }

        String json = runSrtWinJson(argList.toArray(new String[0]));
        return parseWfpStatusJson(json);
    }

    /**
     * One-shot install: creates the discriminator group, adds the current user
     * (or {@code userSid}), and installs the machine-wide WFP filter set — all in
     * a single self-elevating process (one UAC prompt). Idempotent.
     *
     * <p>Network for the calling user is not disrupted before the required logout:
     * while the group is absent from the token, WFP filter-0 (PERMIT non-members)
     * matches and traffic flows normally. After log-out/log-in, the group is enabled
     * in the token and filter-1 (PERMIT group-enabled) takes over for the broker.
     *
     * <p>Returns the post-call group + WFP state. If the user cancels the UAC prompt,
     * this returns a result with {@code cancelled=true} rather than throwing —
     * cancellation is a user choice, not an error.
     *
     * @param opts install options; may be null for defaults
     * @return the install result with post-install status
     * @throws SandboxException on group/WFP creation failure, or if filters already
     *   exist under {@code sublayerGuid} with different configuration and
     *   {@code force} is not set
     */
    public static WindowsInstallResult installWindowsSandbox(WindowsInstallOptions opts) {
        if (opts == null) {
            opts = new WindowsInstallOptions();
        }

        WindowsGroupRef ref = new WindowsGroupRef(opts.getGroupName(), opts.getGroupSid());
        String[] refArgs = groupRefArgs(ref);

        List<String> argList = new ArrayList<String>();
        argList.add("install");
        for (String a : refArgs) argList.add(a);

        if (opts.getUserSid() != null && !opts.getUserSid().isEmpty()) {
            argList.add("--user-sid");
            argList.add(opts.getUserSid());
        }
        if (opts.getSublayerGuid() != null && !opts.getSublayerGuid().isEmpty()) {
            argList.add("--sublayer-guid");
            argList.add(opts.getSublayerGuid());
        }
        if (opts.getProxyPortRange() != null) {
            argList.add("--proxy-port-range");
            argList.add(opts.getProxyPortRange()[0] + "-" + opts.getProxyPortRange()[1]);
        }
        if (opts.isForce()) {
            argList.add("--force");
        }

        RunResult r = runSrtWin(argList.toArray(new String[0]));
        SandboxLog.debug("[Sandbox Windows] install exit=" + r.status + ": " +
            (r.stderr.length() > 0 ? r.stderr : r.stdout));

        // srt-win install exit-code contract:
        //   0  ok
        //   10 user cancelled UAC elevation
        //   11 group create failed
        //   12 WFP install failed
        //   13 already installed with different config (use --force)
        //   1  other error (stderr has detail)
        String out = r.stderr.length() > 0 ? r.stderr : r.stdout;

        switch (r.status) {
            case 0:
                break; // ok
            case 10:
                return new WindowsInstallResult(
                    getWindowsGroupStatus(ref),
                    getWindowsWfpStatus(opts.getSublayerGuid()),
                    true); // cancelled
            case 11:
                throw new SandboxException("srt-win install: group create failed: " + out);
            case 12:
                throw new SandboxException("srt-win install: WFP filter install failed: " + out);
            case 13:
                throw new SandboxException(
                    "srt-win install: filters already exist under this sublayer with " +
                    "different configuration (group SID or port range). " +
                    "Pass {force: true} to replace, or pick a different sublayerGuid. " +
                    "Output: " + out);
            default:
                throw new SandboxException("srt-win install failed (exit " + r.status + "): " + out);
        }

        return new WindowsInstallResult(
            getWindowsGroupStatus(ref),
            getWindowsWfpStatus(opts.getSublayerGuid()),
            false); // not cancelled
    }

    /**
     * Remove the WFP filter set under {@code sublayerGuid} (one UAC prompt). Idempotent.
     *
     * <p><strong>Does NOT delete the discriminator group</strong> — group membership is
     * persistent user state and removing it would force every user to re-do the logout
     * dance on the next install. Call {@link #deleteWindowsGroup} explicitly if you want
     * full teardown.
     *
     * @param sublayerGuid optional sublayer GUID; null for the compile-time default
     * @return {@code true} if the user dismissed the UAC prompt (cancelled);
     *         {@code false} if uninstall succeeded
     * @throws SandboxException if uninstall fails for a reason other than UAC cancellation
     */
    public static boolean uninstallWindowsSandbox(String sublayerGuid) {
        List<String> argList = new ArrayList<String>();
        argList.add("uninstall");
        if (sublayerGuid != null && !sublayerGuid.isEmpty()) {
            argList.add("--sublayer-guid");
            argList.add(sublayerGuid);
        }

        RunResult r = runSrtWin(argList.toArray(new String[0]));
        SandboxLog.debug("[Sandbox Windows] uninstall exit=" + r.status + ": " +
            (r.stderr.length() > 0 ? r.stderr : r.stdout));

        if (r.status == 10) return true; // UAC cancelled
        if (r.status != 0) {
            throw new SandboxException(
                "srt-win uninstall failed (exit " + r.status + "): " +
                (r.stderr.length() > 0 ? r.stderr : r.stdout));
        }
        return false; // success
    }

    /**
     * Delete the discriminator group. Separate from {@link #uninstallWindowsSandbox}
     * so that uninstall→reinstall doesn't force a fresh logout for every member.
     *
     * <p><strong>Requires elevation.</strong> Idempotent (no-op if the group doesn't exist).
     *
     * @param ref group reference (by name or SID)
     * @throws SandboxException if deletion fails
     */
    public static void deleteWindowsGroup(WindowsGroupRef ref) {
        String[] refArgs = groupRefArgs(ref);
        String[] cmd = new String[2 + refArgs.length];
        cmd[0] = "group";
        cmd[1] = "delete";
        System.arraycopy(refArgs, 0, cmd, 2, refArgs.length);

        RunResult r = runSrtWin(cmd);
        if (r.status != 0) {
            throw new SandboxException(
                "srt-win group delete failed (exit " + r.status + "). " +
                "Requires elevation. Output: " +
                (r.stderr.length() > 0 ? r.stderr : r.stdout));
        }
        SandboxLog.debug("[Sandbox Windows] group delete: " +
            (r.stderr.length() > 0 ? r.stderr : r.stdout));
    }

    /**
     * Granular primitive: create the discriminator group and add the current user
     * (or {@code userSid}). Most callers should use {@link #installWindowsSandbox}
     * instead; this exists for enterprise/CI flows that manage group and WFP separately.
     *
     * <p><strong>Requires elevation.</strong> Idempotent.
     *
     * @param ref     group reference (by name or SID)
     * @param userSid optional user SID to add instead of the current user
     * @throws SandboxException if creation fails
     */
    public static void createWindowsGroup(WindowsGroupRef ref, String userSid) {
        String[] refArgs = groupRefArgs(ref);
        List<String> argList = new ArrayList<String>();
        argList.add("group");
        argList.add("create");
        for (String a : refArgs) argList.add(a);
        if (userSid != null && !userSid.isEmpty()) {
            argList.add("--user-sid");
            argList.add(userSid);
        }

        RunResult r = runSrtWin(argList.toArray(new String[0]));
        if (r.status != 0) {
            throw new SandboxException(
                "srt-win group create failed (exit " + r.status + "). " +
                "This requires elevation — run as administrator. " +
                "Output: " + (r.stderr.length() > 0 ? r.stderr : r.stdout));
        }
        SandboxLog.debug("[Sandbox Windows] group create: " +
            (r.stderr.length() > 0 ? r.stderr : r.stdout));
    }

    /**
     * Granular primitive: install the machine-wide WFP filter set under
     * {@code sublayerGuid} keyed on the group SID. Most callers should use
     * {@link #installWindowsSandbox} instead; this exists for enterprise/CI
     * flows that manage group and WFP separately.
     *
     * <p><strong>Requires elevation.</strong> Idempotent — re-running replaces any
     * existing srt-win-tagged filters under that sublayer.
     *
     * @param ref           group reference (by name or SID)
     * @param sublayerGuid  optional sublayer GUID; null for the compile-time default
     * @param proxyPortRange optional port range {@code [lo, hi]}
     * @throws SandboxException if WFP installation fails
     */
    public static void createWindowsWfp(WindowsGroupRef ref, String sublayerGuid, int[] proxyPortRange) {
        String[] refArgs = groupRefArgs(ref);
        List<String> argList = new ArrayList<String>();
        argList.add("wfp");
        argList.add("install");
        for (String a : refArgs) argList.add(a);
        if (sublayerGuid != null && !sublayerGuid.isEmpty()) {
            argList.add("--sublayer-guid");
            argList.add(sublayerGuid);
        }
        if (proxyPortRange != null) {
            argList.add("--proxy-port-range");
            argList.add(proxyPortRange[0] + "-" + proxyPortRange[1]);
        }

        RunResult r = runSrtWin(argList.toArray(new String[0]));
        if (r.status != 0) {
            throw new SandboxException(
                "srt-win wfp install failed (exit " + r.status + "). " +
                "This requires elevation — run as administrator. " +
                "Output: " + (r.stderr.length() > 0 ? r.stderr : r.stdout));
        }
        SandboxLog.debug("[Sandbox Windows] wfp install: " +
            (r.stderr.length() > 0 ? r.stderr : r.stdout));
    }

    // ========================================================================
    // Wrap command
    // ========================================================================

    /**
     * Build the spawn descriptor for running {@code command} inside the Windows sandbox.
     *
     * <p>Caller MUST spawn the result with {@code shell=false} — that is the security
     * boundary that keeps untrusted bytes off the host's shell. The inner cmd.exe runs
     * INSIDE the sandbox.
     *
     * <p>Proxy configuration is single-sourced by {@link ProxyEnvUtils#generateProxyEnvVars}.
     * {@code srt-win exec} forwards its own environment to the sandboxed child verbatim,
     * so the full proxy set is merged over the broker's environment here.
     *
     * @param params sandbox parameters
     * @return spawn descriptor with argv and env
     */
    public static WindowsSandboxResult wrapCommandWithSandboxWindows(WindowsSandboxParams params) {
        String exe = getSrtWinPath();
        List<String> argv = new ArrayList<>();
        argv.add(exe);
        argv.add("exec");

        // Group ref args
        if (params.group != null && params.group.groupSid != null) {
            argv.add("--group-sid");
            argv.add(params.group.groupSid);
        } else {
            argv.add("--name");
            argv.add(params.group != null && params.group.groupName != null
                ? params.group.groupName : DEFAULT_WINDOWS_GROUP_NAME);
        }
        argv.add("--");

        // Shell selection
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null) systemRoot = "C:\\Windows";
        String shell = params.binShell != null ? params.binShell.toLowerCase() : "cmd";

        if ("pwsh".equals(shell) || shell.contains("powershell")) {
            String psExe = "pwsh".equals(shell) ? "pwsh.exe"
                : systemRoot + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
            argv.add(psExe);
            argv.add("-NoProfile");
            argv.add("-Command");
            argv.add(params.command);
        } else {
            argv.add(systemRoot + "\\System32\\cmd.exe");
            argv.add("/d");
            argv.add("/s");
            argv.add("/c");
            argv.add(params.command);
        }

        // Proxy env vars
        Map<String, String> generated = envListToMap(
            ProxyEnvUtils.generateProxyEnvVars(params.httpProxyPort, params.socksProxyPort, null));
        generated.remove("TMPDIR"); // Not used on Windows

        Map<String, String> env = new HashMap<>(System.getenv());
        env.putAll(generated);

        return new WindowsSandboxResult(argv, env);
    }

    // ========================================================================
    // Install instructions (for error messages)
    // ========================================================================

    /**
     * Build install instructions message, tailored to the observed group state.
     *
     * @param ref          group reference
     * @param sublayerGuid optional sublayer GUID
     * @param groupState   the current group state
     * @return human-readable install instructions
     */
    public static String windowsInstallInstructions(
            WindowsGroupRef ref, String sublayerGuid, WindowsGroupStatus groupState) {
        if (groupState == WindowsGroupStatus.CREATED_NOT_ON_TOKEN) {
            return "The discriminator group exists but is not yet in this session's " +
                "token. LOG OUT and back in to pick up the new group membership " +
                "(it enters TokenGroups at logon). Network is not disrupted " +
                "meanwhile — WFP filter-0 PERMITs traffic while the group is absent " +
                "from your token.";
        }

        String g;
        if (ref != null && ref.groupSid != null) {
            g = "--group-sid " + ref.groupSid;
        } else {
            String name = (ref != null && ref.groupName != null) ? ref.groupName : DEFAULT_WINDOWS_GROUP_NAME;
            g = "--name " + name;
        }
        String sl = (sublayerGuid != null && !sublayerGuid.isEmpty()) ? " --sublayer-guid " + sublayerGuid : "";

        return "Windows sandbox needs a one-time install (one UAC prompt):\n" +
            "  npx sandbox-runtime windows-install\n" +
            "  — or call installWindowsSandbox(), or run " +
            "`srt-win.exe install " + g + sl + "` directly —\n" +
            "then LOG OUT and back in (the group SID enters TokenGroups at logon).\n" +
            "Network is not disrupted before the logout: while the group is absent " +
            "from your token, WFP filter-0 PERMITs all traffic.";
    }

    // ========================================================================
    // Dependency / readiness check
    // ========================================================================

    /**
     * Check the Windows backend is ready to sandbox. Errors block initialization;
     * warnings are informational.
     *
     * @param groupRef     group reference (by name or SID)
     * @param sublayerGuid optional sublayer GUID
     * @return dependency check result
     */
    public static SandboxDependencyCheck checkWindowsDependencies(
            WindowsGroupRef groupRef, String sublayerGuid) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1. Binary present.
        String exe;
        try {
            exe = getSrtWinPath();
        } catch (SandboxException e) {
            errors.add(e.getMessage());
            return new SandboxDependencyCheck(errors, warnings);
        }
        SandboxLog.debug("[Sandbox Windows] using srt-win at " + exe);

        // 2. Group ready (exists AND enabled in the caller's token).
        WindowsGroupStatusResult gs;
        try {
            gs = getWindowsGroupStatus(groupRef);
        } catch (SandboxException e) {
            errors.add("srt-win group status failed: " + e.getMessage());
            return new SandboxDependencyCheck(errors, warnings);
        }

        if (gs.getState() != WindowsGroupStatus.READY) {
            errors.add(
                "Discriminator group is " + gs.getState().getJsonValue() +
                (gs.getSid() != null ? " (sid=" + gs.getSid() + ")" : "") +
                ". " +
                windowsInstallInstructions(groupRef, sublayerGuid, gs.getState()));
        }
        if (gs.getWarning() != null) {
            warnings.add(gs.getWarning());
        }

        // 3. WFP filters installed under the sublayer.
        WindowsWfpStatusResult ws;
        try {
            ws = getWindowsWfpStatus(sublayerGuid);
        } catch (SandboxException e) {
            errors.add("srt-win wfp status failed: " + e.getMessage());
            return new SandboxDependencyCheck(errors, warnings);
        }

        if (ws.getState() != WindowsWfpStatus.INSTALLED) {
            // If the group is also not-ready, the group-state error above already
            // gave the right instruction; don't repeat. Only surface a separate
            // WFP error when group IS ready (i.e. someone uninstalled filters but
            // kept the group).
            if (gs.getState() == WindowsGroupStatus.READY) {
                errors.add(
                    "WFP filters not installed under sublayer " +
                    (sublayerGuid != null ? sublayerGuid : "(default)") + ". " +
                    windowsInstallInstructions(groupRef, sublayerGuid, WindowsGroupStatus.ABSENT));
            }
        } else if (ws.getPortRange() != null) {
            SandboxLog.debug("[Sandbox Windows] WFP installed: " + ws.getFilters() +
                " filters, proxy port range " + ws.getPortRange()[0] + "-" + ws.getPortRange()[1]);
        }

        return new SandboxDependencyCheck(errors, warnings);
    }

    /**
     * Legacy dependency check (no sublayerGuid). Retained for backward compatibility.
     *
     * @param groupRef group reference (by name or SID)
     * @return dependency check result
     */
    public static SandboxDependencyCheck checkWindowsDependencies(WindowsGroupRef groupRef) {
        return checkWindowsDependencies(groupRef, null);
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private static Map<String, String> envListToMap(List<String> list) {
        Map<String, String> out = new HashMap<>();
        for (String entry : list) {
            int eq = entry.indexOf('=');
            if (eq == -1) continue;
            out.put(entry.substring(0, eq), entry.substring(eq + 1));
        }
        return out;
    }
}
