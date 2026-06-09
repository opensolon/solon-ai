package org.noear.solon.ai.sandbox.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Base64Utils {

    public static String encode(String input) {
        return Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String input) {
        return new String(Base64.getDecoder().decode(input), StandardCharsets.UTF_8);
    }

    public static String encodeSandboxedCommand(String command) {
        String truncated = command.length() > 100 ? command.substring(0, 100) : command;
        return encode(truncated);
    }

    public static String decodeSandboxedCommand(String encoded) {
        return decode(encoded);
    }
}
