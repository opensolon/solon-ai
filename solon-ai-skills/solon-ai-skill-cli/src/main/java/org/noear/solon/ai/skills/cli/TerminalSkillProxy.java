package org.noear.solon.ai.skills.cli;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.skill.SkillMetadata;
import org.noear.solon.ai.chat.tool.FunctionTool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * TerminalSkill 代理
 *
 * @author noear 2026/3/20 created
 */
public class TerminalSkillProxy implements Skill {
    private final TerminalSkill terminalSkill;
    private final List<FunctionTool> toolList = new ArrayList<>();

    public TerminalSkillProxy(TerminalSkill terminalSkill) {
        this.terminalSkill = terminalSkill;
    }

    public boolean isEmpty() {
        return toolList.isEmpty();
    }

    public void addTools(String... names) {
        toolList.addAll(terminalSkill.getToolAry(names));
    }

    @Override
    public String name() {
        return terminalSkill.name();
    }

    @Override
    public String description() {
        return terminalSkill.description();
    }

    @Override
    public SkillMetadata metadata() {
        return terminalSkill.metadata();
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return terminalSkill.isSupported(prompt);
    }

    @Override
    public void onAttach(Prompt prompt) {
        terminalSkill.onAttach(prompt);
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return terminalSkill.getInstruction(prompt);
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        return toolList;
    }
}