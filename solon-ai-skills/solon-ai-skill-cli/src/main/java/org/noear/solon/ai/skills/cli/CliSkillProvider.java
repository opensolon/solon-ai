//package org.noear.solon.ai.skills.cli;
//
//import org.noear.solon.ai.chat.skill.Skill;
//import org.noear.solon.ai.chat.skill.SkillProvider;
//
//import java.util.Arrays;
//import java.util.Collection;
//
///**
// *
// * @author noear
// * @since 3.9.5
// */
//public class CliSkillProvider {
//    private final PoolManager poolManager;
//    private final TerminalSkill terminalSkill;
//    private final ExpertSkill expertSkill;
//
//
//    public CliSkillProvider() {
//        this(null);
//    }
//
//    public CliSkillProvider(String workDir) {
//        this(workDir, null);
//    }
//
//    public CliSkillProvider(String workDir, PoolManager poolManager0) {
//        if(poolManager0 == null) {
//            this.poolManager = new PoolManager();
//        } else {
//            this.poolManager = poolManager0;
//        }
//
//        terminalSkill = new TerminalSkill(this.poolManager);
//        expertSkill = new ExpertSkill(this.poolManager);
//    }
//
//    /**
//     * 沙盒模式
//     */
//    public CliSkillProvider sandboxMode(boolean sandboxMode) {
//        terminalSkill.setSandboxMode(sandboxMode);
//        return this;
//    }
//
//    /**
//     * 异步 Bash 会话模式
//     */
//    public CliSkillProvider bashAsyncEnabled(boolean bashAsyncEnabled) {
//        terminalSkill.setBashAsyncEnabled(bashAsyncEnabled);
//        return this;
//    }
//
//
//    public PoolManager getPoolManager() {
//        return poolManager;
//    }
//
//    public TerminalSkill getTerminalSkill() {
//        return terminalSkill;
//    }
//
//    public ExpertSkill getExpertSkill() {
//        return expertSkill;
//    }
//
//    @Override
//    public Collection<Skill> getSkills() {
//        return Arrays.asList(terminalSkill, expertSkill);
//    }
//}