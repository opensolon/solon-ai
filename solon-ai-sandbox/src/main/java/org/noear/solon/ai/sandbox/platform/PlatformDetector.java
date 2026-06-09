package org.noear.solon.ai.sandbox.platform;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class PlatformDetector {
    private static Platform cached;
    private static String wslVersion;

    public static Platform detect() {
        if (cached != null) return cached;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            cached = Platform.MACOS;
        } else if (os.contains("win")) {
            cached = Platform.WINDOWS;
        } else if (os.contains("nux") || os.contains("nix")) {
            cached = Platform.LINUX;
        } else {
            cached = Platform.UNKNOWN;
        }
        return cached;
    }

    public static String getWslVersion() {
        if (detect() != Platform.LINUX) return null;
        if (wslVersion != null) return wslVersion.isEmpty() ? null : wslVersion;
        try {
            File procVersion = new File("/proc/version");
            if (!procVersion.exists()) { wslVersion = ""; return null; }
            String content = new String(Files.readAllBytes(procVersion.toPath()), StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("WSL(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(content);
            if (m.find()) {
                wslVersion = m.group(1);
                return wslVersion;
            }
            if (content.toLowerCase().contains("microsoft")) {
                wslVersion = "1";
                return "1";
            }
        } catch (IOException e) {
            // ignore
        }
        wslVersion = "";
        return null;
    }

    public static boolean isSupportedPlatform() {
        Platform p = detect();
        if (p == Platform.LINUX) {
            return !"1".equals(getWslVersion());
        }
        return p == Platform.MACOS || p == Platform.WINDOWS;
    }
}
