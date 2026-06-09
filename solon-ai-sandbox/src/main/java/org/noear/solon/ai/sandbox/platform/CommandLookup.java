package org.noear.solon.ai.sandbox.platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public final class CommandLookup {

    public static String which(String bin) {
        if (bin == null || bin.isEmpty()) return null;
        Platform platform = PlatformDetector.detect();
        String command = platform == Platform.WINDOWS ? "where" : "which";
        try {
            ProcessBuilder pb = new ProcessBuilder(command, bin);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            if (p.exitValue() == 0 && line != null && !line.isEmpty()) {
                return line.trim();
            }
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return null;
    }

    public static boolean isExecutable(String path) {
        if (path == null) return false;
        java.io.File f = new java.io.File(path);
        return f.exists() && f.canExecute();
    }
}
