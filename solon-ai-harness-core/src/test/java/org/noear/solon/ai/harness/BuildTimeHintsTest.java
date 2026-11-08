package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.agent.ToolName;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 构建期提示：「缺配置不报错、只是不生效」的两处，在 build() 时告警。
 */
class BuildTimeHintsTest {

    private static HarnessOptions fresh() {
        return new HarnessOptions("work", null);
    }

    @Test
    void hitlWarnsWhenToolNotListed() {
        HarnessOptions o = fresh();
        assertFalse(HarnessEngine.Builder.warnHitlMisconfigured(o), "HITL 未开启时不告警");

        o.setHitlEnabled(true);
        assertTrue(HarnessEngine.Builder.warnHitlMisconfigured(o), "开了 HITL 但工具集没有 hitl");

        o.getTools().add(ToolName.TOOL_ALL_FULL.getName());
        assertFalse(HarnessEngine.Builder.warnHitlMisconfigured(o), "** 含 hitl");

        o.getDisallowedTools().add(ToolName.TOOL_HITL.getName());
        assertTrue(HarnessEngine.Builder.warnHitlMisconfigured(o), "hitl 被禁用");
    }

    @Test
    void hitlAcceptsExplicitToolName() {
        HarnessOptions o = fresh();
        o.setHitlEnabled(true);
        o.getTools().add(ToolName.TOOL_HITL.getName());
        assertFalse(HarnessEngine.Builder.warnHitlMisconfigured(o));
    }

    @Test
    void standaloneWarnsWithoutModel() {
        HarnessOptions o = fresh();
        assertTrue(HarnessEngine.Builder.warnStandaloneMode(o), "没有模型");

        ChatConfig model = new ChatConfig();
        model.setModel("m1");
        o.addModel(model);
        assertFalse(HarnessEngine.Builder.warnStandaloneMode(o));

        o.setDefaultModel("missing");
        assertTrue(HarnessEngine.Builder.warnStandaloneMode(o), "defaultModel 不在模型表里");
    }
}
