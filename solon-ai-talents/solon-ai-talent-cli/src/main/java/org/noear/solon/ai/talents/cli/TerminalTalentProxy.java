package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.chat.talent.TalentMetadata;
import org.noear.solon.ai.chat.tool.FunctionTool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * TerminalTalent 代理
 *
 * @author noear 2026/3/20 created
 */
public class TerminalTalentProxy implements Talent {
    private final TerminalTalent terminalTalent;
    private final List<FunctionTool> toolList = new ArrayList<>();

    public TerminalTalentProxy(TerminalTalent terminalTalent) {
        this.terminalTalent = terminalTalent;
    }

    public boolean isEmpty() {
        return toolList.isEmpty();
    }

    public void addTools(String... names) {
        toolList.addAll(terminalTalent.getToolAry(names));
    }

    @Override
    public String name() {
        return terminalTalent.name();
    }

    @Override
    public String description() {
        return terminalTalent.description();
    }

    @Override
    public TalentMetadata metadata() {
        return terminalTalent.metadata();
    }

    @Override
    public boolean isEnabled() {
        return terminalTalent.isEnabled();
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return terminalTalent.isSupported(prompt);
    }

    @Override
    public void onAttach(Prompt prompt) {
        terminalTalent.onAttach(prompt);
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return terminalTalent.getInstruction(prompt);
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        if (terminalTalent.isBashAsyncEnabled()) {
            return toolList;
        } else {
            return toolList.stream()
                    .filter(t -> terminalTalent.isNotAsyncBash(t.name()))
                    .collect(Collectors.toList());
        }
    }
}