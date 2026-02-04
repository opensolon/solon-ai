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
        Solon.start(ClaudeCodeCLIDemo.class, new String[]{"--cfg=cli.yml"});


        String sharedDir = System.getProperty("user.home") + "/WORK/work_github/solonlab/opencode-skills";

        ClaudeCodeCLI claudeCodeCLI = new ClaudeCodeCLI(LlmUtil.getChatModel());
        claudeCodeCLI.sharedSkillsDir(sharedDir);

        claudeCodeCLI.start();
    }
}