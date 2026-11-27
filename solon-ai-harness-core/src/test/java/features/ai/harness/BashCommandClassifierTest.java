package features.ai.harness;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.hitl.BashCommandClassifier;
import org.noear.solon.ai.harness.permission.PermissionBehavior;

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
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("ls -la"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("cat README.md"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("grep -r 'pattern' ."));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("find . -name '*.java'"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("pwd"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("echo hello"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("date"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("echo hello"));
    }

    @Test
    public void testGitReadCommand_Allow() {
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git log --oneline"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git status"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git diff HEAD~1"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git show HEAD"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git branch -a"));
    }

    // ========== 管道命令 ==========

    @Test
    public void testPipeline_AllRead_Allow() {
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("cat file.txt | grep pattern"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("ls -la | grep .java"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("echo hello | wc -l"));
    }

    @Test
    public void testPipeline_MixedWrite_Ask() {
        // 管道中包含写命令，应交由人工确认
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("cat file.txt | sed 's/a/b/' > out.txt"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("ls | rm -rf /tmp/test"));
    }

    // ========== 不完整命令 ==========

    @Test
    public void testIncompleteCommand_Deny() {
        Assertions.assertEquals(PermissionBehavior.DENY, classifier.classify("cat file.txt |"));
        Assertions.assertEquals(PermissionBehavior.DENY, classifier.classify("ls &&"));
        Assertions.assertEquals(PermissionBehavior.DENY, classifier.classify("grep pattern ||"));
        Assertions.assertEquals(PermissionBehavior.DENY, classifier.classify("echo hello ;"));
    }

    @Test
    public void testEmptyCommand_Deny() {
        Assertions.assertEquals(PermissionBehavior.DENY, classifier.classify(""));
        Assertions.assertEquals(PermissionBehavior.DENY, classifier.classify("   "));
        Assertions.assertEquals(PermissionBehavior.DENY, classifier.classify(null));
    }

    // ========== sed 安全盲点 ==========

    @Test
    public void testSedInPlace_Ask() {
        // sed -i 原地修改文件，不算只读
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("sed -i 's/old/new/g' file.txt"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("sed --in-place 's/old/new/' file.txt"));
    }

    @Test
    public void testSedReadOnly_Allow() {
        // 不带 -i 的 sed 是只读
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("sed 's/old/new/g' file.txt"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("sed -n '1,10p' file.txt"));
    }

    // ========== awk 安全盲点 ==========

    @Test
    public void testAwkWithRedirect_Ask() {
        // awk 带 > 重定向写入文件，不算只读
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("awk '{print $1}' file.txt > out.txt"));
    }

    @Test
    public void testAwkReadOnly_Allow() {
        // 不带重定向的 awk 是只读
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("awk '{print $1}' file.txt"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("awk -F: '{print $2}' /etc/passwd"));
    }

    // ========== 写命令 ==========

    @Test
    public void testWriteCommand_Ask() {
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("rm -rf /tmp/test"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("mkdir build"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("npm install"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("docker build ."));
    }

    @Test
    public void testGitWriteCommand_Ask() {
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git commit -m 'test'"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git push origin main"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git reset --hard HEAD~1"));
    }

    @Test
    public void testGitBranchDeleteFlag_Ask() {
        // git branch -d / -D 是删除分支的写操作，不算只读
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git branch -D feature"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git branch -d feature"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git branch --delete feature"));
        // 合并短标志 -Dv
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git branch -Dv feature"));
    }

    @Test
    public void testGitBranchReadOnly_Allow() {
        // git branch / git branch -a / git branch -v 是只读
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git branch"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git branch -a"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git branch -v"));
    }

    // ========== 重定向守卫修复 ==========

    @Test
    public void testRedirectWithStderrAndStdout_Ask() {
        // 修复前：外层守卫 !cmd.contains("2>") 导致此命令跳过重定向检查
        // 修复后：逐字符扫描，识别到 >out.txt 为输出重定向
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("cat file 2>err.txt >out.txt"));
    }

    @Test
    public void testRedirectStderrToDevNullOrDup_Allow() {
        // 丢弃或合并输出流不写文件
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("cat file 2>/dev/null"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("ls missing 2>&1 | head -5"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("cat file >/dev/null"));
    }

    @Test
    public void testRedirectStderrToFile_Ask() {
        // 2>file 同样会创建或截断文件（如 ls x 2>src/Main.java 会清空源文件），不算只读
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("cat file 2>err.txt"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("ls &>out.txt"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("ls >&out.txt"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("ls >|out.txt"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("cat <>file"));
    }

    // ========== 命令串联：每一段都必须是只读 ==========

    @Test
    public void testChainedWrite_Ask() {
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("ls && rm -rf src"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("ls; rm -rf src"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("ls || rm -rf src"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("ls & rm -rf src"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("ls\nrm -rf src"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("(rm -rf src)"));
    }

    @Test
    public void testChainedRead_Allow() {
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("ls; pwd"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("ls && git status"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("find . -type f | wc -l"));
    }

    @Test
    public void testQuotedOperators_Allow() {
        // 引号内的 | ; > 是普通字符，不是管道、分隔符或重定向
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("grep 'a|b' file"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("grep -n \"->\" file"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("echo \"a;b\""));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("sed 's|a|b|g' file"));
    }

    @Test
    public void testUnterminatedQuote_Ask() {
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("echo 'abc"));
    }

    // ========== 命令替换与进程替换 ==========

    @Test
    public void testSubstitution_Ask() {
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("echo $(rm -rf src)"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("echo `rm -rf src`"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("echo \"$(rm -rf src)\""));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("cat <(rm -rf src)"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("ls >(rm -rf src)"));
    }

    @Test
    public void testSingleQuotedSubstitution_Allow() {
        // 单引号内不展开
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("echo '$(rm -rf src)'"));
    }

    // ========== 白名单命令自带的写文件或执行能力 ==========

    @Test
    public void testFindActions_Ask() {
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("find . -name '*.tmp' -delete"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("find . -name x -exec rm {} \\;"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("find . -execdir rm {} +"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("find . -ok rm {} \\;"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("find . -fprint out.txt"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("find . -fls out.txt"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("find . -name '*.java' -print"));
    }

    @Test
    public void testSearchToolExec_Ask() {
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("fd -e tmp -x rm"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("fd -e tmp --exec rm"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("rg --pre ./run.sh x"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("rg --pre=./run.sh x"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("ack --pager=sh x"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("bat --pager 'sh -c id' f"));
    }

    @Test
    public void testAwkWriteOrExec_Ask() {
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("awk 'BEGIN{system(\"rm -rf src\")}'"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("awk '{print > \"out.txt\"}' f"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("awk '{print | \"sh\"}' f"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("awk -i inplace '{print}' f"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("awk -f prog.awk f"));
    }

    @Test
    public void testSedWriteOrExec_Ask() {
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("sed -ni 's/a/b/' f"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("sed -Ei 's/a/b/' f"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("sed 's/a/b/w out.txt' f"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("sed 's/a/b/e' f"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("sed -n 'w out.txt' f"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("sed '1e rm -rf src' f"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("sed -e p -e 'w out.txt' f"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("sed -f script.sed f"));
    }

    @Test
    public void testSedReadScripts_Allow() {
        // 正则与替换文本里的 w/e 不是命令
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("sed -e 's/x/y/' -e 's/w/e/' f"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("sed -n '/error/p' f"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("sed -n '1,10p;20q' f"));
    }

    @Test
    public void testTextToolOutputFile_Ask() {
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("sort -o out.txt f"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("sort -ro out.txt f"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("sort --output=out.txt f"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("sort --compress-program=sh f"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("uniq in.txt out.txt"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("tree -o out.txt"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("yq -i '.a = 1' f.yml"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("xmllint --output out.xml f.xml"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("file -C -m magic"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("less -o log.txt f"));
    }

    @Test
    public void testTextToolRead_Allow() {
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("sort -k2 -t, f"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("sort -u f | uniq -c"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("uniq -c f"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("tree -L 2"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("yq '.a' f.yml"));
    }

    @Test
    public void testSystemInfoSideEffects_Ask() {
        // env 带命令时会执行它
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("env rm -rf src"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("env FOO=1 rm -rf src"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("date -s '2020-01-01'"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("hostname newname"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("env | grep PATH"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("date +%Y-%m-%d"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("hostname"));
    }

    @Test
    public void testGitSubcommandWrites_Ask() {
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git branch newbranch"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git branch -m old new"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git branch -f main HEAD~1"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git branch --set-upstream-to=origin/main"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git tag v1.0"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git tag -a v1.0 -m x"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git tag -d v1.0"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git remote add up https://x"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git remote remove up"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git remote set-url origin https://x"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git diff --output=out.txt"));
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git log -p --output out.txt"));
    }

    @Test
    public void testGitSubcommandReads_Allow() {
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git branch --list 'feat*'"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git branch -vv"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git branch --show-current"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git tag"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git tag -l 'v*'"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git remote -v"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git remote show origin"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git remote get-url origin"));
        Assertions.assertEquals(PermissionBehavior.ALLOW, classifier.classify("git --no-pager log -n 3"));
        // git -c 可改 pager、外部 diff 等配置去执行任意程序
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("git -c core.pager=sh log"));
    }

    @Test
    public void testRedirectAppend_Ask() {
        // >> 追加也是写操作
        Assertions.assertEquals(PermissionBehavior.ASK, classifier.classify("echo hello >> out.txt"));
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
        // 转义的分号是 find -exec 的结束符，不是命令分隔
        Assertions.assertFalse(classifier.isIncompleteCommand("find . -name x -exec rm {} \\;"));
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
