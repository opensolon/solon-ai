package org.noear.solon.ai.sandbox.util;

import java.util.ArrayList;
import java.util.List;

public final class ShellQuote {

    /**
     * Quote an array of arguments for POSIX shell.
     * Each argument is single-quoted, with embedded single quotes escaped.
     * Null or empty arg list returns empty string.
     */
    public static String quote(List<String> args) {
        if (args == null || args.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(quoteArg(args.get(i)));
        }
        return sb.toString();
    }

    public static String quoteArg(String arg) {
        if (arg == null || arg.isEmpty()) return "''";
        // If safe characters only (letters, digits, and a set of safe chars), no quoting needed
        if (arg.matches("^[a-zA-Z0-9_\\-./:,+=@%]+$")) {
            return arg;
        }
        // Single-quote the argument, escaping embedded single quotes
        return "'" + arg.replace("'", "'\"'\"'") + "'";
    }
}
