package org.noear.solon.ai.sandbox;

public final class SandboxLog {
    private static final boolean DEBUG = Boolean.getBoolean("sandbox.debug");

    public static void debug(String msg) {
        if (DEBUG) {
            System.out.println("[Sandbox] " + msg);
        }
    }

    public static void error(String msg) {
        System.err.println("[Sandbox] " + msg);
    }

    public static void error(String msg, Throwable t) {
        System.err.println("[Sandbox] " + msg);
        if (t != null) {
            t.printStackTrace(System.err);
        }
    }
}
