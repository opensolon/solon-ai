package org.noear.solon.ai.harness.session.goal;

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.agent.AgentDefinition;

/**
 * Goal 工具扩展 — 注册 GoalTalent 到主 Agent。
 *
 * <p>只依赖 {@link GoalEngine}。</p>
 *
 * @author noear
 * @since 3.9.2
 */
public class GoalExtension implements HarnessExtension {
    private final GoalTalent goalTalent;

    public GoalExtension(GoalEngine goalEngine, HarnessEngine engine) {
        this.goalTalent = new GoalTalent(goalEngine, engine);
    }

    public GoalTalent getGoalTalent() {
        return goalTalent;
    }

    @Override
    public void configure(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder) {
        if (AgentDefinition.AGENT_MAIN.equals(agentName)) {
            agentBuilder.defaultTalentAdd(goalTalent);
        }
    }
}
