package org.noear.solon.ai.codecli;

import org.noear.solon.Solon;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.claudecode.SolonCodeCLI;
import org.noear.solon.core.util.Assert;

import java.util.Map;

public class SolonCodeApp {
    public static void main(String[] args) {
        Solon.start(SolonCodeApp.class, args);

        String nameKey = "solon.code.cli.name";
        String chatModelKey = "solon.code.cli.chatModel";
        String mountPoolKey = "solon.code.cli.mountPool";

        String name = Solon.cfg().get(nameKey);
        Map<String, String> mountPoolMap = Solon.cfg().getMap(mountPoolKey);
        ChatConfig chatConfig = Solon.cfg().toBean(chatModelKey, ChatConfig.class);

        if (chatConfig == null) {
            throw new RuntimeException("ChatModel config not found");
        }

        ChatModel chatModel = ChatModel.of(chatConfig).build();

        SolonCodeCLI solonCodeCLI = new SolonCodeCLI(chatModel)
                .name(name)
                .workDir("./work")
                .config(agent -> {
                    agent.maxSteps(100);
                });

        if (Assert.isNotEmpty(mountPoolMap)) {
            mountPoolMap.forEach((alias, dir) -> {
                solonCodeCLI.mountPool(alias, dir);
            });
        }

        solonCodeCLI.start();
    }
}