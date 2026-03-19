package org.noear.solon.ai.skills.cli;

import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.skill.SkillProvider;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;

/**
 *
 * @author noear
 * @since 3.9.5
 */
public class CliSkillProvider implements SkillProvider {
    private final PoolManager poolManager;
    private final TerminalSkill terminalSkill;
    private final ExpertSkill expertSkill;


    public CliSkillProvider() {
        this(null);
    }

    public CliSkillProvider(String workDir) {
        this(workDir, null);
    }

    public CliSkillProvider(String workDir, PoolManager poolManager0) {
        if(poolManager0 == null) {
            this.poolManager = new PoolManager();
        } else {
            this.poolManager = poolManager0;
        }

        terminalSkill = new TerminalSkill(workDir, this.poolManager);
        expertSkill = new ExpertSkill(this.poolManager);
    }

    /**
     * 添加技能池
     */
    public CliSkillProvider skillPool(String alias, Path dir) {
        poolManager.register(alias, dir);
        return this;
    }

    /**
     * 添加技能池
     */
    public CliSkillProvider skillPool(String alias, String dir) {
        poolManager.register(alias, dir);
        return this;
    }

    public PoolManager getPoolManager() {
        return poolManager;
    }

    public TerminalSkill getTerminalSkill() {
        return terminalSkill;
    }

    public ExpertSkill getExpertSkill() {
        return expertSkill;
    }

    @Override
    public Collection<Skill> getSkills() {
        return Arrays.asList(terminalSkill, expertSkill);
    }
}