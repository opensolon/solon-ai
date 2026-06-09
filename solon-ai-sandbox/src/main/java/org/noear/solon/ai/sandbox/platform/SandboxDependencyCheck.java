package org.noear.solon.ai.sandbox.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SandboxDependencyCheck {
    private final List<String> errors;
    private final List<String> warnings;

    public SandboxDependencyCheck(List<String> errors, List<String> warnings) {
        this.errors = errors != null ? errors : Collections.<String>emptyList();
        this.warnings = warnings != null ? warnings : Collections.<String>emptyList();
    }

    public List<String> getErrors() { return errors; }
    public List<String> getWarnings() { return warnings; }
    public boolean hasErrors() { return !errors.isEmpty(); }
}
