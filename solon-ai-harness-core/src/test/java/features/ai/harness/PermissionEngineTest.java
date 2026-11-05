package features.ai.harness;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.permission.PermissionBehavior;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionDecision;
import org.noear.solon.ai.harness.permission.PermissionEngine;

import org.noear.solon.ai.harness.permission.PermissionRule;

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
            .addRule(PermissionRule.allow("bash"))
            .addRule(PermissionRule.deny("bash"));

        Assertions.assertEquals(PermissionDecision.DENY,
            engine.evaluate("bash", args("ls"), ctx));
    }

    @Test
    public void testDenyOverridesAsk() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.ask("bash"))
            .addRule(PermissionRule.deny("bash"));

        Assertions.assertEquals(PermissionDecision.DENY,
            engine.evaluate("bash", args("ls"), ctx));
    }

    @Test
    public void testAllowOverridesAsk() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.ask("bash"))
            .addRule(PermissionRule.allow("bash"));

        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("bash", args("ls"), ctx));
    }

    @Test
    public void testAllowBeforeAsk() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("bash"))
            .addRule(PermissionRule.ask("bash"));

        // allow 在前 -> 先匹配到 ALLOW
        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("bash", args("ls"), ctx));
    }



    // ========== 通配符匹配 ==========

    @Test
    public void testWildcardToolName() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("*"));

        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("bash", args("ls"), ctx));
        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("write", args("test.txt"), ctx));
    }

    // ========== glob 模式匹配 ==========

    @Test
    public void testGlobPatternMatch() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("bash", "git *"));

        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("bash", args("git log --oneline"), ctx));
        Assertions.assertEquals(PermissionDecision.ASK,
            engine.evaluate("bash", args("rm -rf /"), ctx));
    }

    @Test
    public void testGlobPatternExactMatch() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.deny("bash", "rm -rf *"));

        Assertions.assertEquals(PermissionDecision.DENY,
            engine.evaluate("bash", args("rm -rf /tmp"), ctx));
        Assertions.assertEquals(PermissionDecision.ASK,
            engine.evaluate("bash", args("rm /tmp/test"), ctx));
    }

    // ========== 默认规则 ==========

    @Test
    public void testDefaultAllowRule() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("*").priority(-100));
        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("bash", args("rm -rf /"), ctx));
    }

    @Test
    public void testPriorityRuleOverridesDefault() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("*").priority(-100))
            .addRule(PermissionRule.deny("bash", "rm -rf *").priority(10));
        Assertions.assertEquals(PermissionDecision.DENY,
            engine.evaluate("bash", args("rm -rf /tmp"), ctx));
        Assertions.assertEquals(PermissionDecision.ALLOW,
            engine.evaluate("write", args("test.txt"), ctx));
    }

    @Test
    public void testNoRuleDefaultAsk() {
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
