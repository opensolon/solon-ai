package features.ai.harness;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.permission.PermissionBehavior;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionDecision;
import org.noear.solon.ai.harness.permission.PermissionEngine;
import org.noear.solon.ai.harness.permission.PermissionMode;
import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.harness.permission.RuleSource;

import java.util.HashMap;
import java.util.Map;

/**
 * PermissionEngine 单元测试
 *
 * @author noear 2026/7/4 created
 */
public class PermissionEngineTest {

    private final PermissionEngine engine = new PermissionEngine();

    // ========== 规则优先级 ==========

    @Test
    public void testDenyOverridesAllow() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("bash", RuleSource.SESSION))
            .addRule(PermissionRule.deny("bash", RuleSource.SESSION));

        Assertions.assertEquals(PermissionDecision.DENY,
            engine.evaluate("bash", args("ls"), ctx));
    }

    @Test
    public void testDenyOverridesAsk() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.ask("bash", RuleSource.SESSION))
            .addRule(PermissionRule.deny("bash", RuleSource.SESSION));

        Assertions.assertEquals(PermissionDecision.DENY,
            engine.evaluate("bash", args("ls"), ctx));
    }

    @Test
    public void testAllowOverridesAsk() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.ask("bash", RuleSource.SESSION))
            .addRule(PermissionRule.allow("bash", RuleSource.SESSION));

        // DENY 最高优先级，无 DENY 后按规则顺序扫描
        // ask 在前 -> 先匹配到 ASK
        Assertions.assertEquals(PermissionDecision.ASK,
            engine.evaluate("bash", args("ls"), ctx));
    }

    @Test
    public void testAllowBeforeAsk() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("bash", RuleSource.SESSION))
            .addRule(PermissionRule.ask("bash", RuleSource.SESSION));

        // allow 在前 -> 先匹配到 ALLOW
        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("bash", args("ls"), ctx));
    }

    // ========== PASSTHROUGH ==========

    @Test
    public void testPassthroughSkips() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.withPattern("bash", PermissionBehavior.PASSTHROUGH, RuleSource.SESSION, "rm *"))
            .addRule(PermissionRule.allow("bash", RuleSource.SESSION));

        Map<String, Object> args = args("rm -rf /tmp");
        // PASSTHROUGH 跳过，继续匹配 ALLOW -> 放行
        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("bash", args, ctx));
    }

    @Test
    public void testPassthroughAllSkips() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.withPattern("bash", PermissionBehavior.PASSTHROUGH, RuleSource.SESSION, "rm *"))
            .addRule(PermissionRule.withPattern("bash", PermissionBehavior.PASSTHROUGH, RuleSource.SESSION, "git *"));

        // 全部 PASSTHROUGH -> 无匹配规则 -> 模式降级 -> DEFAULT -> ASK
        Assertions.assertEquals(PermissionDecision.ASK,
            engine.evaluate("bash", args("rm -rf /tmp"), ctx));
    }

    // ========== 通配符匹配 ==========

    @Test
    public void testWildcardToolName() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("*", RuleSource.SESSION));

        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("bash", args("ls"), ctx));
        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("write", args("test.txt"), ctx));
    }

    // ========== glob 模式匹配 ==========

    @Test
    public void testGlobPatternMatch() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("bash", "git *", RuleSource.SESSION));

        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("bash", args("git log --oneline"), ctx));
        Assertions.assertEquals(PermissionDecision.ASK,
            engine.evaluate("bash", args("rm -rf /"), ctx));
    }

    @Test
    public void testGlobPatternExactMatch() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.deny("bash", "rm -rf *", RuleSource.SESSION));

        Assertions.assertEquals(PermissionDecision.DENY,
            engine.evaluate("bash", args("rm -rf /tmp"), ctx));
        Assertions.assertEquals(PermissionDecision.ASK,
            engine.evaluate("bash", args("rm /tmp/test"), ctx));
    }

    // ========== 模式降级 ==========

    @Test
    public void testModeBypass() {
        PermissionContext ctx = PermissionContext.create().withMode(PermissionMode.BYPASS);
        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("bash", args("rm -rf /"), ctx));
    }

    @Test
    public void testModeDontAsk() {
        PermissionContext ctx = PermissionContext.create().withMode(PermissionMode.DONT_ASK);
        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("bash", args("rm -rf /"), ctx));
    }

    @Test
    public void testModeAuto() {
        PermissionContext ctx = PermissionContext.create().withMode(PermissionMode.AUTO);
        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("bash", args("rm -rf /"), ctx));
    }

    @Test
    public void testModePlan_WriteDeny() {
        PermissionContext ctx = PermissionContext.create().withMode(PermissionMode.READ_ONLY);
        Assertions.assertEquals(PermissionDecision.DENY,
            engine.evaluate("bash", args("rm -rf /"), ctx));
        Assertions.assertEquals(PermissionDecision.DENY,
            engine.evaluate("write", args("test.txt"), ctx));
    }

    @Test
    public void testModePlan_ReadAllow() {
        PermissionContext ctx = PermissionContext.create().withMode(PermissionMode.READ_ONLY);
        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("webfetch", args("https://example.com"), ctx));
    }

    @Test
    public void testModeAcceptEdits_WriteAllow() {
        PermissionContext ctx = PermissionContext.create().withMode(PermissionMode.ACCEPT_EDITS);
        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("write", args("test.txt"), ctx));
        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("edit", args("test.txt"), ctx));
    }

    @Test
    public void testModeAcceptEdits_NonWriteAsk() {
        PermissionContext ctx = PermissionContext.create().withMode(PermissionMode.ACCEPT_EDITS);
        Assertions.assertEquals(PermissionDecision.ASK,
            engine.evaluate("webfetch", args("https://example.com"), ctx));
    }

    @Test
    public void testModeDefault_Ask() {
        PermissionContext ctx = PermissionContext.create();
        Assertions.assertEquals(PermissionDecision.ASK,
            engine.evaluate("bash", args("rm -rf /"), ctx));
    }

    // ========== 无参数 ==========

    @Test
    public void testNullArgs() {
        PermissionContext ctx = PermissionContext.create();
        Assertions.assertEquals(PermissionDecision.ASK,
            engine.evaluate("bash", null, ctx));
    }

    @Test
    public void testEmptyArgs() {
        PermissionContext ctx = PermissionContext.create();
        Assertions.assertEquals(PermissionDecision.ASK,
            engine.evaluate("bash", new HashMap<>(), ctx));
    }

    // ========== 工具方法 ==========

    private Map<String, Object> args(String command) {
        Map<String, Object> map = new HashMap<>();
        if (command != null) {
            map.put("command", command);
        }
        return map;
    }
}
