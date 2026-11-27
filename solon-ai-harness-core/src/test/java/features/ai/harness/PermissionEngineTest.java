package features.ai.harness;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.permission.PermissionBehavior;
import org.noear.solon.ai.harness.permission.PermissionContext;
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

        Assertions.assertEquals(PermissionBehavior.DENY,
            engine.evaluate("bash", args("ls"), ctx));
    }

    @Test
    public void testDenyOverridesAsk() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.ask("bash"))
            .addRule(PermissionRule.deny("bash"));

        Assertions.assertEquals(PermissionBehavior.DENY,
            engine.evaluate("bash", args("ls"), ctx));
    }

    @Test
    public void testAllowOverridesAsk() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.ask("bash"))
            .addRule(PermissionRule.allow("bash"));

        Assertions.assertEquals(PermissionBehavior.ALLOW,
            engine.evaluate("bash", args("ls"), ctx));
    }

    @Test
    public void testAllowBeforeAsk() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("bash"))
            .addRule(PermissionRule.ask("bash"));

        // allow 在前 -> 先匹配到 ALLOW
        Assertions.assertEquals(PermissionBehavior.ALLOW,
            engine.evaluate("bash", args("ls"), ctx));
    }



    // ========== 通配符匹配 ==========

    @Test
    public void testWildcardToolName() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("*"));

        Assertions.assertEquals(PermissionBehavior.ALLOW,
            engine.evaluate("bash", args("ls"), ctx));
        Assertions.assertEquals(PermissionBehavior.ALLOW,
            engine.evaluate("write", args("test.txt"), ctx));
    }

    // ========== glob 模式匹配 ==========

    @Test
    public void testGlobPatternMatch() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("bash", "git *"));

        Assertions.assertEquals(PermissionBehavior.ALLOW,
            engine.evaluate("bash", args("git log --oneline"), ctx));
        Assertions.assertEquals(PermissionBehavior.ASK,
            engine.evaluate("bash", args("rm -rf /"), ctx));
    }

    @Test
    public void testGlobPatternExactMatch() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.deny("bash", "rm -rf *"));

        Assertions.assertEquals(PermissionBehavior.DENY,
            engine.evaluate("bash", args("rm -rf /tmp"), ctx));
        Assertions.assertEquals(PermissionBehavior.ASK,
            engine.evaluate("bash", args("rm /tmp/test"), ctx));
    }

    // ========== 默认规则 ==========

    @Test
    public void testDefaultAllowRule() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("*").priority(-100));
        Assertions.assertEquals(PermissionBehavior.ALLOW,
            engine.evaluate("bash", args("rm -rf /"), ctx));
    }

    @Test
    public void testPriorityRuleOverridesDefault() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("*").priority(-100))
            .addRule(PermissionRule.deny("bash", "rm -rf *").priority(10));
        Assertions.assertEquals(PermissionBehavior.DENY,
            engine.evaluate("bash", args("rm -rf /tmp"), ctx));
        Assertions.assertEquals(PermissionBehavior.ALLOW,
            engine.evaluate("write", args("test.txt"), ctx));
    }

    @Test
    public void testNoRuleDefaultAsk() {
        PermissionContext ctx = PermissionContext.create();
        Assertions.assertEquals(PermissionBehavior.ASK,
            engine.evaluate("bash", args("rm -rf /"), ctx));
    }

    // ========== 无参数 ==========

    @Test
    public void testNullArgs() {
        PermissionContext ctx = PermissionContext.create();
        Assertions.assertEquals(PermissionBehavior.ASK,
            engine.evaluate("bash", null, ctx));
    }

    @Test
    public void testEmptyArgs() {
        PermissionContext ctx = PermissionContext.create();
        Assertions.assertEquals(PermissionBehavior.ASK,
            engine.evaluate("bash", new HashMap<>(), ctx));
    }

    // ========== 结尾 " *" 匹配空 ==========

    @Test
    public void testTrailingSpaceStarMatchesBareCommand() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("bash", "git log *"));

        Assertions.assertEquals(PermissionBehavior.ALLOW, engine.evaluate("bash", args("git log"), ctx));
        Assertions.assertEquals(PermissionBehavior.ALLOW, engine.evaluate("bash", args("git log -5"), ctx));
        Assertions.assertEquals(PermissionBehavior.ASK, engine.evaluate("bash", args("git logx"), ctx), "只是前缀相同的别的命令不算");
    }

    // ========== shell 按段判定 ==========

    /** explore 式配置：兜底拒绝 + 个别放行 */
    private PermissionContext gitOnly() {
        return PermissionContext.create()
            .addRule(PermissionRule.deny("bash").priority(-100))
            .addRule(PermissionRule.allow("bash", "git log *"))
            .addRule(PermissionRule.allow("bash", "head *"));
    }

    @Test
    public void testAllowListDoesNotCoverChainedCommand() {
        PermissionContext ctx = gitOnly();

        Assertions.assertEquals(PermissionBehavior.ALLOW, engine.evaluate("bash", args("git log -5 | head -3"), ctx));
        Assertions.assertEquals(PermissionBehavior.DENY, engine.evaluate("bash", args("git log; rm -rf x"), ctx));
        Assertions.assertEquals(PermissionBehavior.DENY, engine.evaluate("bash", args("git log && rm -rf x"), ctx));
        Assertions.assertEquals(PermissionBehavior.DENY, engine.evaluate("bash", args("git log\nrm -rf x"), ctx));
        Assertions.assertEquals(PermissionBehavior.DENY, engine.evaluate("bash", args("git log | sh"), ctx));
        Assertions.assertEquals(PermissionBehavior.DENY, engine.evaluate("bash", args("rm x"), ctx));
        // bash_start 与 bash 同一套切分（规则按工具名匹配，调用方常以 bash 的名义评估）
        PermissionContext startCtx = PermissionContext.create()
            .addRule(PermissionRule.allow("bash_start", "git log *"));
        Assertions.assertEquals(PermissionBehavior.ASK, engine.evaluate("bash_start", args("git log; rm x"), startCtx));
    }

    @Test
    public void testDenyPatternCannotBeBypassedByChaining() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.allow("bash"))
            .addRule(PermissionRule.deny("bash", "rm *").priority(10));

        Assertions.assertEquals(PermissionBehavior.ALLOW, engine.evaluate("bash", args("ls -la"), ctx));
        Assertions.assertEquals(PermissionBehavior.DENY, engine.evaluate("bash", args("ls && rm -rf x"), ctx));
        Assertions.assertEquals(PermissionBehavior.DENY, engine.evaluate("bash", args("ls; 'rm' -rf x"), ctx), "引号包住命令名也照样命中");
    }

    @Test
    public void testGlobAllowDoesNotVouchForRedirectOrUnparsable() {
        PermissionContext ctx = gitOnly();

        Assertions.assertEquals(PermissionBehavior.ALLOW, engine.evaluate("bash", args("git log 2>/dev/null"), ctx));
        Assertions.assertEquals(PermissionBehavior.ASK, engine.evaluate("bash", args("git log > out.txt"), ctx));
        Assertions.assertEquals(PermissionBehavior.ASK, engine.evaluate("bash", args("git log $(rm -rf x)"), ctx));
        Assertions.assertEquals(PermissionBehavior.ASK, engine.evaluate("bash", args("git log `rm -rf x`"), ctx));
        Assertions.assertEquals(PermissionBehavior.ASK, engine.evaluate("bash", args("git log && (rm -rf x)"), ctx));
        Assertions.assertEquals(PermissionBehavior.ASK, engine.evaluate("bash", args("git log $OPTS"), ctx));
        Assertions.assertEquals(PermissionBehavior.ALLOW, engine.evaluate("bash", args("git log --format='%h $x'"), ctx), "单引号内不展开");
        Assertions.assertEquals(PermissionBehavior.DENY, engine.evaluate("bash", args("rm $(ls)"), ctx), "拒绝照常");
    }

    @Test
    public void testExactApprovalStillMatchesWholeCommand() {
        PermissionContext ctx = gitOnly()
            .addRule(PermissionRule.allowExact("bash", "command", "git log; ls"));

        Assertions.assertEquals(PermissionBehavior.ALLOW, engine.evaluate("bash", args("git log; ls"), ctx));
        Assertions.assertEquals(PermissionBehavior.DENY, engine.evaluate("bash", args("git log; ls -la"), ctx));
    }

    @Test
    public void testFilePathPatternUnaffected() {
        PermissionContext ctx = PermissionContext.create()
            .addRule(PermissionRule.deny("read", "*.env"))
            .addRule(PermissionRule.deny("read", "*.env.*"))
            .addRule(PermissionRule.allow("read", "*.env.example").priority(1));

        Map<String, Object> env = new HashMap<>();
        env.put("file_path", "conf/.env");
        Map<String, Object> local = new HashMap<>();
        local.put("file_path", "conf/.env.local");
        Map<String, Object> example = new HashMap<>();
        example.put("file_path", "conf/.env.example");

        Assertions.assertEquals(PermissionBehavior.DENY, engine.evaluate("read", env, ctx, PermissionBehavior.ALLOW));
        Assertions.assertEquals(PermissionBehavior.DENY, engine.evaluate("read", local, ctx, PermissionBehavior.ALLOW));
        Assertions.assertEquals(PermissionBehavior.ALLOW, engine.evaluate("read", example, ctx, PermissionBehavior.ALLOW));
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
