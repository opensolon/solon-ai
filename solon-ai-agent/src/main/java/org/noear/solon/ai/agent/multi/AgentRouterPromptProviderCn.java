package org.noear.solon.ai.agent.multi;


import java.util.List;

public class AgentRouterPromptProviderCn implements AgentRouterPromptProvider {
    private static final AgentRouterPromptProvider instance = new AgentRouterPromptProviderCn();

    public static AgentRouterPromptProvider getInstance() {
        return instance;
    }

    @Override
    public String getSystemPrompt(String prompt, List<String> agentNames) {
        return "你是一个团队负责人（Supervisor）。\n" +
                "全局任务: " + prompt + "\n" +
                "团队成员: " + agentNames + "。\n" +
                "指令：根据协作历史决定下一步由谁执行。\n" +
                "- 如果任务已圆满完成，请仅回复 'FINISH'。\n" +
                "- 否则，请仅回复成员的名字（例如：'Coder' 或 'Reviewer'）。";
    }
}