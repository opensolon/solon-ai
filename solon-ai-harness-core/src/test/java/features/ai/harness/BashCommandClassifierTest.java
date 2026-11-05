package features.ai.harness;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.hitl.BashCommandClassifier;
import org.noear.solon.ai.harness.permission.PermissionDecision;

/**
 * BashCommandClassifier 单元测试
 *
 * @author noear 2026/7/4 created
 */
public class BashCommandClassifierTest {

    private final BashCommandClassifier classifier = new BashCommandClassifier();

    // ========== 只读命令白名单 ==========

    @Test
    public void testReadCommand_Allow() {
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("ls -la"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("cat README.md"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("grep -r 'pattern' ."));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("find . -name '*.java'"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("pwd"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("echo hello"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("date"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("echo hello"));
    }

    @Test
    public void testGitReadCommand_Allow() {
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("git log --oneline"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("git status"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("git diff HEAD~1"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("git show HEAD"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("git branch -a"));
    }

    // ========== 管道命令 ==========

    @Test
    public void testPipeline_AllRead_Allow() {
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("cat file.txt | grep pattern"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("ls -la | grep .java"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("echo hello | wc -l"));
    }

    @Test
    public void testPipeline_MixedWrite_Ask() {
        // 管道中包含写命令，应交由人工确认
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("cat file.txt | sed 's/a/b/' > out.txt"));
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("ls | rm -rf /tmp/test"));
    }

    // ========== 不完整命令 ==========

    @Test
    public void testIncompleteCommand_Deny() {
        Assertions.assertEquals(PermissionDecision.DENY, classifier.classify("cat file.txt |"));
        Assertions.assertEquals(PermissionDecision.DENY, classifier.classify("ls &&"));
        Assertions.assertEquals(PermissionDecision.DENY, classifier.classify("grep pattern ||"));
        Assertions.assertEquals(PermissionDecision.DENY, classifier.classify("echo hello ;"));
    }

    @Test
    public void testEmptyCommand_Deny() {
        Assertions.assertEquals(PermissionDecision.DENY, classifier.classify(""));
        Assertions.assertEquals(PermissionDecision.DENY, classifier.classify("   "));
        Assertions.assertEquals(PermissionDecision.DENY, classifier.classify(null));
    }

    // ========== sed 安全盲点 ==========

    @Test
    public void testSedInPlace_Ask() {
        // sed -i 原地修改文件，不算只读
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("sed -i 's/old/new/g' file.txt"));
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("sed --in-place 's/old/new/' file.txt"));
    }

    @Test
    public void testSedReadOnly_Allow() {
        // 不带 -i 的 sed 是只读
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("sed 's/old/new/g' file.txt"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("sed -n '1,10p' file.txt"));
    }

    // ========== awk 安全盲点 ==========

    @Test
    public void testAwkWithRedirect_Ask() {
        // awk 带 > 重定向写入文件，不算只读
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("awk '{print $1}' file.txt > out.txt"));
    }

    @Test
    public void testAwkReadOnly_Allow() {
        // 不带重定向的 awk 是只读
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("awk '{print $1}' file.txt"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("awk -F: '{print $2}' /etc/passwd"));
    }

    // ========== 写命令 ==========

    @Test
    public void testWriteCommand_Ask() {
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("rm -rf /tmp/test"));
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("mkdir build"));
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("npm install"));
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("docker build ."));
    }

    @Test
    public void testGitWriteCommand_Ask() {
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("git commit -m 'test'"));
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("git push origin main"));
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("git reset --hard HEAD~1"));
    }

    @Test
    public void testGitBranchDeleteFlag_Ask() {
        // git branch -d / -D 是删除分支的写操作，不算只读
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("git branch -D feature"));
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("git branch -d feature"));
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("git branch --delete feature"));
        // 合并短标志 -Dv
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("git branch -Dv feature"));
    }

    @Test
    public void testGitBranchReadOnly_Allow() {
        // git branch / git branch -a / git branch -v 是只读
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("git branch"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("git branch -a"));
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("git branch -v"));
    }

    // ========== 重定向守卫修复 ==========

    @Test
    public void testRedirectWithStderrAndStdout_Ask() {
        // 修复前：外层守卫 !cmd.contains("2>") 导致此命令跳过重定向检查
        // 修复后：逐字符扫描，识别到 >out.txt 为输出重定向
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("cat file 2>err.txt >out.txt"));
    }

    @Test
    public void testRedirectStderrOnly_Allow() {
        // 2> 是 stderr 重定向，不算写文件
        Assertions.assertEquals(PermissionDecision.ALLOW, classifier.classify("cat file 2>err.txt"));
    }

    @Test
    public void testRedirectAppend_Ask() {
        // >> 追加也是写操作
        Assertions.assertEquals(PermissionDecision.ASK, classifier.classify("echo hello >> out.txt"));
    }

    // ========== isSearchOrReadCommand ==========

    @Test
    public void testIsSearchOrReadCommand() {
        Assertions.assertTrue(classifier.isSearchOrReadCommand("ls -la"));
        Assertions.assertTrue(classifier.isSearchOrReadCommand("cat file | grep x"));
        Assertions.assertFalse(classifier.isSearchOrReadCommand("rm -rf /"));
        Assertions.assertFalse(classifier.isSearchOrReadCommand(null));
        Assertions.assertFalse(classifier.isSearchOrReadCommand(""));
    }

    // ========== isIncompleteCommand ==========

    @Test
    public void testIsIncompleteCommand() {
        Assertions.assertTrue(classifier.isIncompleteCommand("ls |"));
        Assertions.assertTrue(classifier.isIncompleteCommand("cat &&"));
        Assertions.assertFalse(classifier.isIncompleteCommand("ls -la"));
        Assertions.assertFalse(classifier.isIncompleteCommand(null));
    }

    // ========== isGitWriteCommand ==========

    @Test
    public void testIsGitWriteCommand() {
        Assertions.assertTrue(classifier.isGitWriteCommand("git commit -m 'test'"));
        Assertions.assertTrue(classifier.isGitWriteCommand("git push origin main"));
        Assertions.assertFalse(classifier.isGitWriteCommand("git log --oneline"));
        Assertions.assertFalse(classifier.isGitWriteCommand("rm -rf /"));
        Assertions.assertFalse(classifier.isGitWriteCommand(null));
    }
}
