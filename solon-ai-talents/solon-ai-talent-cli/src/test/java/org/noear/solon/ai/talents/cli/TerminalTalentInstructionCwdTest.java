package org.noear.solon.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.mount.MountManager;

/**
 * 引导词里的「工作区解析根」必须与文件工具 / bash 实际使用的根同口径。
 *
 * <p>背景：工具侧按 {@code __cwd ?: workDir} 解析相对路径，引导词却固定写 MountManager 的工作区名。
 * 会话 cwd 不在工作区时（如 goal 把会话目录切到目标项目），模型被告知的根与工具真实的根不一致。
 */
public class TerminalTalentInstructionCwdTest {

    @Test
    public void instructionUsesSessionCwdWhenPresent() throws Exception {
        Path workDir = Files.createTempDirectory("ws-root-");
        Path cwd = Files.createTempDirectory("session-cwd-");
        try {
            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            String text = talent.getInstruction(Prompt.of("hi").attrPut("__cwd", cwd.toString()));

            assertTrue(text.contains("目录「" + cwd.getFileName() + "」"), "应以本轮 __cwd 作为解析根: " + text);
            assertFalse(text.contains("目录「" + workDir.getFileName() + "」"), "不应再宣称工作区根");
        } finally {
            Files.deleteIfExists(workDir);
            Files.deleteIfExists(cwd);
        }
    }

    @Test
    public void instructionFallsBackToWorkDirWithoutCwd() throws Exception {
        Path workDir = Files.createTempDirectory("ws-root-");
        try {
            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));

            assertTrue(talent.getInstruction(null).contains("目录「" + workDir.getFileName() + "」"));
            assertTrue(talent.getInstruction(Prompt.of("hi")).contains("目录「" + workDir.getFileName() + "」"));
            assertTrue(talent.getInstruction(Prompt.of("hi").attrPut("__cwd", ""))
                    .contains("目录「" + workDir.getFileName() + "」"), "空 cwd 视为未设置");
        } finally {
            Files.deleteIfExists(workDir);
        }
    }
}
