package features.ai.harness;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.hitl.BashToolStrategy;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionMode;
import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.harness.permission.RuleSource;

import java.util.HashMap;
import java.util.Map;

/**
 * BashToolStrategy 单元测试
 *
 * <p>覆盖 P0 硬编码防御、内置只读命令分类、PermissionEngine 委托。</p>
 *
 * @author noear 2026/7/4 created
 */
public class BashToolStrategyTest {

    private BashToolStrategy strategy() {
        return new BashToolStrategy()
                .permissionContextSupplier(() -> PermissionContext.create());
    }

    private BashToolStrategy strategy(PermissionContext ctx) {
        return new BashToolStrategy()
                .permissionContextSupplier(() -> ctx);
    }

    private Map<String, Object> args(String command) {
        Map<String, Object> map = new HashMap<>();
        if (command != null) {
            map.put("command", command);
        }
        return map;
    }

    // ========== P0 注入与子 Shell 防御 ==========

    @Test
    public void testInjectionBacktick_Block() {
        BashToolStrategy s = strategy();
        Assertions.assertNotNull(s.evaluate(null, args("echo `whoami`")));
    }

    @Test
    public void testInjectionDollarParen_Block() {
        BashToolStrategy s = strategy();
        Assertions.assertNotNull(s.evaluate(null, args("echo $(whoami)")));
    }

    @Test
    public void testDevRedirect_Block() {
        BashToolStrategy s = strategy();
        Assertions.assertNotNull(s.evaluate(null, args("cat /dev/null")));
    }

    // ========== P0 系统特权命令 ==========

    @Test
    public void testSudo_Block() {
        BashToolStrategy s = strategy();
        Assertions.assertNotNull(s.evaluate(null, args("sudo ls")));
    }

    @Test
    public void testSu_Block() {
        BashToolStrategy s = strategy();
        Assertions.assertNotNull(s.evaluate(null, args("su root")));
    }

    @Test
    public void testChmod_Block() {
        BashToolStrategy s = strategy();
        Assertions.assertNotNull(s.evaluate(null, args("chmod 755 file.sh")));
    }

    // ========== P0 路径回溯检测 ==========

    @Test
    public void testPathTraversal_Backslash_Block() {
        BashToolStrategy s = strategy();
        Assertions.assertNotNull(s.evaluate(null, args("cat ../../etc/passwd")));
    }

    @Test
    public void testPathTraversalBackslash_Block() {
        BashToolStrategy s = strategy();
        Assertions.assertNotNull(s.evaluate(null, args("cat ..\\..\\config")));
    }

    // ========== P0 系统敏感路径检测（contains 修复验证） ==========

    @Test
    public void testEtcPath_Block() {
        BashToolStrategy s = strategy();
        // 修复前：\b 在 / 前不匹配，此命令不会被拦截
        // 修复后：使用 contains 检测，此命令应被拦截
        Assertions.assertNotNull(s.evaluate(null, args("cat /etc/passwd")));
    }

    @Test
    public void testVarPath_Block() {
        BashToolStrategy s = strategy();
        Assertions.assertNotNull(s.evaluate(null, args("cat /var/log/syslog")));
    }

    @Test
    public void testRootPath_Block() {
        BashToolStrategy s = strategy();
        Assertions.assertNotNull(s.evaluate(null, args("ls /root/.bashrc")));
    }

    @Test
    public void testSshPath_Block() {
        BashToolStrategy s = strategy();
        Assertions.assertNotNull(s.evaluate(null, args("cat ~/.ssh/id_rsa")));
    }

    @Test
    public void testBashrcPath_Block() {
        BashToolStrategy s = strategy();
        Assertions.assertNotNull(s.evaluate(null, args("cat ~/.bashrc")));
    }

    @Test
    public void testZshrcPath_Block() {
        BashToolStrategy s = strategy();
        Assertions.assertNotNull(s.evaluate(null, args("cat ~/.zshrc")));
    }

    // ========== 内置只读命令分类（自动放行） ==========

    @Test
    public void testReadOnlyCommand_Pass() {
        BashToolStrategy s = strategy();
        Assertions.assertNull(s.evaluate(null, args("ls -la")));
        Assertions.assertNull(s.evaluate(null, args("cat README.md")));
        Assertions.assertNull(s.evaluate(null, args("grep -r pattern .")));
        Assertions.assertNull(s.evaluate(null, args("pwd")));
        Assertions.assertNull(s.evaluate(null, args("git log --oneline")));
    }

    @Test
    public void testPipelineReadOnly_Pass() {
        BashToolStrategy s = strategy();
        Assertions.assertNull(s.evaluate(null, args("cat file.txt | grep pattern")));
    }

    @Test
    public void testWriteCommand_Ask() {
        BashToolStrategy s = strategy();
        String result = s.evaluate(null, args("rm -rf /tmp/test"));
        Assertions.assertNotNull(result);
    }

    // ========== PermissionEngine 规则匹配 ==========

    @Test
    public void testRuleAllow_Pass() {
        PermissionContext ctx = PermissionContext.create()
                .addRule(PermissionRule.allow("bash", "git push *", RuleSource.SESSION));
        BashToolStrategy s = strategy(ctx);
        Assertions.assertNull(s.evaluate(null, args("git push origin main")));
    }

    @Test
    public void testRuleDeny_Block() {
        PermissionContext ctx = PermissionContext.create()
                .addRule(PermissionRule.deny("bash", "rm *", RuleSource.SESSION));
        BashToolStrategy s = strategy(ctx);
        Assertions.assertNotNull(s.evaluate(null, args("rm -rf /tmp")));
    }

    @Test
    public void testBypassMode_Pass() {
        PermissionContext ctx = PermissionContext.create().withMode(PermissionMode.BYPASS);
        BashToolStrategy s = strategy(ctx);
        Assertions.assertNull(s.evaluate(null, args("rm -rf /tmp/test")));
    }

    // ========== 空命令与不完整命令 ==========

    @Test
    public void testEmptyCommand_Pass() {
        BashToolStrategy s = strategy();
        // 空命令在 BashToolStrategy 中 Assert.isEmpty 判断后返回 null（不拦截）
        Assertions.assertNull(s.evaluate(null, args("")));
    }

    @Test
    public void testIncompleteCommand_Block() {
        BashToolStrategy s = strategy();
        Assertions.assertNotNull(s.evaluate(null, args("cat file.txt |")));
    }
}
