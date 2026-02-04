package features.ai.skills.claudecode;

import demo.ai.skills.LlmUtil;
import org.noear.solon.Solon;
import org.noear.solon.ai.skills.claudecode.ClaudeCodeCLI;

/**
 *
 * @author noear 2026/2/4 created
 *
 */
public class ClaudeCodeCLIDemo {
    public static void main(String[] args) {
        //主要控制日志等级
        Solon.start(ClaudeCodeCLIDemo.class, new String[]{"--cfg=cli.yml"});

        // 下载 skills： https://github.com/solonlab/opencode-skills
        String sharedDir = System.getProperty("user.home") + "/WORK/work_github/solonlab/opencode-skills";

        ClaudeCodeCLI claudeCodeCLI = new ClaudeCodeCLI(LlmUtil.getChatModel());
        claudeCodeCLI.sharedSkillsDir(sharedDir).maxSteps(100);

        claudeCodeCLI.start();
    }
}