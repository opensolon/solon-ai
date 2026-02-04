package org.noear.solon.ai.codecli;

import org.noear.solon.Solon;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.claudecode.SolonCodeCLI;
import org.noear.solon.core.util.Assert;

import java.util.Map;

public class SolonCodeApp {
    static final String nameKey = "solon.code.cli.name";
    static final String workDirKey = "solon.code.cli.workDir";
    static final String webPathKey = "solon.code.cli.webPath";
    static final String maxStepsKey = "solon.code.cli.maxSteps";
    static final String enableWebKey = "solon.code.cli.enableWeb";
    static final String enableConsoleKey = "solon.code.cli.enableConsole";
    static final String chatModelKey = "solon.code.cli.chatModel";
    static final String mountPoolKey = "solon.code.cli.mountPool";

    public static void main(String[] args) {
        Solon.start(SolonCodeApp.class, args);

        String name = Solon.cfg().get(nameKey);
        String workDir = Solon.cfg().get(workDirKey, "./work");
        String webPath = Solon.cfg().get(webPathKey, "/cli");
        int maxSteps = Solon.cfg().getInt(maxStepsKey, 100);
        boolean enableWeb = Solon.cfg().getBool(enableWebKey, true);
        boolean enableConsole = Solon.cfg().getBool(enableConsoleKey, true);
        Map<String, String> mountPoolMap = Solon.cfg().getMap(mountPoolKey);
        ChatConfig chatConfig = Solon.cfg().toBean(chatModelKey, ChatConfig.class);


        if (chatConfig == null) {
            throw new RuntimeException("ChatModel config not found");
        }

        ChatModel chatModel = ChatModel.of(chatConfig).build();

        SolonCodeCLI solonCodeCLI = new SolonCodeCLI(chatModel)
                .name(name)
                .workDir(workDir)
                .enableWeb(enableWeb)
                .enableConsole(enableConsole)
                .config(agent -> {
                    agent.maxSteps(maxSteps);
                });

        if (Assert.isNotEmpty(mountPoolMap)) {
            mountPoolMap.forEach((alias, dir) -> {
                solonCodeCLI.mountPool(alias, dir);
            });
        }

        if (enableWeb) {
            Solon.app().router().get(webPath, solonCodeCLI);
        }

        if (enableConsole) {
            new Thread(solonCodeCLI, "CLI-Interactive-Thread").start();
        }

        System.out.println(">>> Solon Code CLI 节点已全面启动。");
    }
}