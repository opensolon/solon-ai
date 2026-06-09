package org.noear.solon.ai.sandbox.config;

import java.util.List;

public class RipgrepConfig {
    private final String command;
    private final List<String> args;
    private final String argv0;

    public RipgrepConfig(String command, List<String> args, String argv0) {
        this.command = command;
        this.args = args;
        this.argv0 = argv0;
    }

    public String getCommand() { return command; }
    public List<String> getArgs() { return args; }
    public String getArgv0() { return argv0; }
}
