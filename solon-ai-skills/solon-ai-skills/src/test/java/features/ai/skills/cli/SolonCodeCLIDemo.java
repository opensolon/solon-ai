package features.ai.skills.cli;

import demo.ai.skills.LlmUtil;
import org.noear.solon.Solon;
import org.noear.solon.ai.skills.claudecode.SolonCodeCLI;

/**
 *
 * @author noear 2026/2/4 created
 *
 */
public class SolonCodeCLIDemo {
    public static void main(String[] args) {
        //主要控制日志等级
        Solon.start(SolonCodeCLIDemo.class, new String[]{"--cfg=cli.yml"});

        // 下载 skills： https://github.com/solonlab/opencode-skills
        String sharedDir = System.getProperty("user.home") + "/WORK/work_github/solonlab/opencode-skills";

        SolonCodeCLI solonCodeCLI = new SolonCodeCLI(LlmUtil.getChatModel())
                .name("小花")
                .workDir("./app")
                .mountPool("@shared", sharedDir)
                .config(agent -> {
                    agent.maxSteps(100);
                });

        solonCodeCLI.start();
    }
}