package features.ai.harness;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.hitl.WriteToolStrategy;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionMode;
import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.harness.permission.RuleSource;

import java.util.HashMap;
import java.util.Map;

/**
 * WriteToolStrategy 单元测试
 *
 * <p>覆盖 P0 路径回溯防御、系统敏感路径检测、PermissionEngine 委托。</p>
 *
 * @author noear 2026/7/4 created
 */
public class WriteToolStrategyTest {

    private WriteToolStrategy strategy(String toolName, PermissionContext ctx) {
        return new WriteToolStrategy(toolName)
                .permissionContextSupplier(() -> ctx);
    }

    private Map<String, Object> args(String filePath) {
        Map<String, Object> map = new HashMap<>();
        if (filePath != null) {
            map.put("file_path", filePath);
        }
        return map;
    }

    // ========== P0 路径回溯防御 ==========

    @Test
    public void testPathTraversalForwardSlash_Block() {
        WriteToolStrategy s = strategy("write", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("../../etc/passwd")));
    }

    @Test
    public void testPathTraversalBackslash_Block() {
        WriteToolStrategy s = strategy("write", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("..\\..\\config")));
    }

    @Test
    public void testPathTraversalNested_Block() {
        WriteToolStrategy s = strategy("write", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("src/../../../etc/shadow")));
    }

    // ========== P0 系统敏感路径检测 ==========

    @Test
    public void testEtcPath_Block() {
        WriteToolStrategy s = strategy("write", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("/etc/passwd")));
    }

    @Test
    public void testVarPath_Block() {
        WriteToolStrategy s = strategy("write", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("/var/log/test.log")));
    }

    @Test
    public void testRootPath_Block() {
        WriteToolStrategy s = strategy("write", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("/root/.bashrc")));
    }

    @Test
    public void testSshPath_Block() {
        WriteToolStrategy s = strategy("write", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("~/.ssh/authorized_keys")));
    }

    @Test
    public void testBashrcPath_Block() {
        WriteToolStrategy s = strategy("write", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("~/.bashrc")));
    }

    // ========== 正常路径 — DEFAULT 模式 ASK ==========

    @Test
    public void testNormalPath_Default_Ask() {
        WriteToolStrategy s = strategy("write", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("src/main/java/App.java")));
    }

    // ========== BYPASS 模式放行 ==========

    @Test
    public void testNormalPath_Bypass_Pass() {
        WriteToolStrategy s = strategy("write",
                PermissionContext.create().withMode(PermissionMode.BYPASS));
        Assertions.assertNull(s.evaluate(null, args("src/test.java")));
    }

    // ========== ACCEPT_EDITS 模式放行 ==========

    @Test
    public void testNormalPath_AcceptEdits_Pass() {
        WriteToolStrategy s = strategy("write",
                PermissionContext.create().withMode(PermissionMode.ACCEPT_EDITS));
        Assertions.assertNull(s.evaluate(null, args("src/test.java")));
    }

    // ========== PLAN 模式拒绝 ==========

    @Test
    public void testNormalPath_Plan_Block() {
        WriteToolStrategy s = strategy("write",
                PermissionContext.create().withMode(PermissionMode.READ_ONLY));
        Assertions.assertNotNull(s.evaluate(null, args("src/test.java")));
    }

    // ========== 规则匹配 — 用户自定义放行 ==========

    @Test
    public void testRuleAllow_Pass() {
        PermissionContext ctx = PermissionContext.create()
                .addRule(PermissionRule.allow("write", "src/*", RuleSource.SESSION));
        WriteToolStrategy s = strategy("write", ctx);
        Assertions.assertNull(s.evaluate(null, args("src/App.java")));
    }

    @Test
    public void testRuleDeny_Block() {
        PermissionContext ctx = PermissionContext.create()
                .addRule(PermissionRule.deny("write", "*.sh", RuleSource.SESSION));
        WriteToolStrategy s = strategy("write", ctx);
        Assertions.assertNotNull(s.evaluate(null, args("deploy.sh")));
    }

    // ========== edit 工具 ==========

    @Test
    public void testEditTool_PathTraversal_Block() {
        WriteToolStrategy s = strategy("edit", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("../../etc/passwd")));
    }

    @Test
    public void testEditTool_NormalPath_Ask() {
        WriteToolStrategy s = strategy("edit", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("src/App.java")));
    }

    // ========== 空参数 ==========

    @Test
    public void testNullFilePath_Default_Ask() {
        WriteToolStrategy s = strategy("write", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, null));
    }
}
