package org.noear.solon.ai.codecli;

import org.noear.solon.Solon;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.claudecode.SolonCodeCLI;

public class SolonCodeApp {
    public static void main(String[] args) {
        Solon.start(SolonCodeApp.class, args);


        ChatModel chatModel = Solon.context().getBean(ChatModel.class);

        if(chatModel == null){
            throw new RuntimeException("ChatModel config not found");
        }

        // 下载 skills： https://github.com/solonlab/opencode-skills
        String sharedDir = System.getProperty("user.home") + "/WORK/work_github/solonlab/opencode-skills";


        SolonCodeCLI solonCodeCLI = new SolonCodeCLI(chatModel)
                .name("小花")
                .workDir("./app")
                .mountPool("@shared", sharedDir)
                .config(agent -> {
                    agent.maxSteps(100);
                });

        solonCodeCLI.start();
    }
}