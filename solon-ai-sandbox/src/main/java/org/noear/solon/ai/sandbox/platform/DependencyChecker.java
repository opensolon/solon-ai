package org.noear.solon.ai.sandbox.platform;

import org.noear.solon.ai.sandbox.SandboxLog;
import org.noear.solon.ai.sandbox.config.RipgrepConfig;
import org.noear.solon.ai.sandbox.config.SeccompConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DependencyChecker {

    public static SandboxDependencyCheck check(Platform platform,
                                                RipgrepConfig ripgrepConfig,
                                                SeccompConfig seccompConfig,
                                                String bwrapPath,
                                                String socatPath) {
        if (!PlatformDetector.isSupportedPlatform()) {
            return new SandboxDependencyCheck(
                Collections.singletonList("Unsupported platform"),
                Collections.<String>emptyList()
            );
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (platform == Platform.LINUX) {
            // ripgrep
            String rgCmd = ripgrepConfig != null ? ripgrepConfig.getCommand() : "rg";
            if (CommandLookup.which(rgCmd) == null) {
                errors.add("ripgrep (" + rgCmd + ") not found");
            }

            // bwrap
            if (bwrapPath != null) {
                if (!CommandLookup.isExecutable(bwrapPath)) {
                    errors.add("bubblewrap (bwrap) not executable at " + bwrapPath);
                }
            } else if (CommandLookup.which("bwrap") == null) {
                errors.add("bubblewrap (bwrap) not installed");
            }

            // socat
            if (socatPath != null) {
                if (!CommandLookup.isExecutable(socatPath)) {
                    errors.add("socat not executable at " + socatPath);
                }
            } else if (CommandLookup.which("socat") == null) {
                errors.add("socat not installed");
            }

            // seccomp (warning only)
            if (seccompConfig == null || (seccompConfig.getApplyPath() == null && seccompConfig.getArgv0() == null)) {
                if (seccompConfig == null || seccompConfig.getArgv0() == null) {
                    // No seccomp binary path available
                    warnings.add("seccomp not available - unix socket access not restricted");
                }
            }
        }

        // macOS: no required external dependencies for sandbox-exec
        // Windows: checked via srt-win.exe at wrap time

        return new SandboxDependencyCheck(errors, warnings);
    }
}
