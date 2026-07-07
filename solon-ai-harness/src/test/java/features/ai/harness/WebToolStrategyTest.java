package features.ai.harness;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.hitl.WebToolStrategy;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionMode;
import org.noear.solon.ai.harness.permission.PermissionRule;

import java.util.HashMap;
import java.util.Map;

/**
 * WebToolStrategy 单元测试
 *
 * <p>覆盖高风险域名黑名单、域名子域名匹配、PermissionEngine 委托。</p>
 *
 * @author noear 2026/7/4 created
 */
public class WebToolStrategyTest {

    private WebToolStrategy strategy(String toolName, PermissionContext ctx) {
        return new WebToolStrategy(toolName)
                .permissionContextSupplier(() -> ctx);
    }

    private Map<String, Object> args(String url) {
        Map<String, Object> map = new HashMap<>();
        if (url != null) {
            map.put("url", url);
        }
        return map;
    }

    // ========== 高风险域名黑名单 ==========

    @Test
    public void testFacebook_Block() {
        WebToolStrategy s = strategy("webfetch", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("https://facebook.com/page")));
    }

    @Test
    public void testTwitter_Block() {
        WebToolStrategy s = strategy("webfetch", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("https://twitter.com/user")));
    }

    @Test
    public void testXCom_Block() {
        WebToolStrategy s = strategy("webfetch", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("https://x.com/user")));
    }

    @Test
    public void testTiktok_Block() {
        WebToolStrategy s = strategy("webfetch", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("https://tiktok.com/@user")));
    }

    @Test
    public void testReddit_Block() {
        WebToolStrategy s = strategy("webfetch", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("https://reddit.com/r/test")));
    }

    @Test
    public void testLinkedin_Block() {
        WebToolStrategy s = strategy("webfetch", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("https://linkedin.com/in/user")));
    }

    // ========== 子域名匹配 ==========

    @Test
    public void testFacebookSubdomain_Block() {
        WebToolStrategy s = strategy("webfetch", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("https://www.facebook.com/page")));
    }

    @Test
    public void testTwitterSubdomain_Block() {
        WebToolStrategy s = strategy("webfetch", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("https://api.twitter.com/1.1")));
    }

    // ========== 安全域名 — DEFAULT 模式 ASK ==========

    @Test
    public void testSafeUrl_Default_Ask() {
        WebToolStrategy s = strategy("webfetch", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, args("https://example.com")));
    }



    @Test
    public void testSafeUrl_Unlimited_Pass() {
        WebToolStrategy s = strategy("webfetch",
                PermissionContext.create().withMode(PermissionMode.UNLIMITED));
        Assertions.assertNull(s.evaluate(null, args("https://example.com")));
    }

    // ========== 高风险域名即使在 UNLIMITED 模式也被拦截 ==========

    @Test
    public void testRiskyUrl_Unlimited_StillBlock() {
        WebToolStrategy s = strategy("webfetch",
                PermissionContext.create().withMode(PermissionMode.UNLIMITED));
        // P0 黑名单优先于模式，始终拦截
        Assertions.assertNotNull(s.evaluate(null, args("https://facebook.com")));
    }

    // ========== 规则匹配 ==========

    @Test
    public void testRuleAllow_Pass() {
        PermissionContext ctx = PermissionContext.create()
                .addRule(PermissionRule.allow("webfetch", "https://example.com*"));
        WebToolStrategy s = strategy("webfetch", ctx);
        Assertions.assertNull(s.evaluate(null, args("https://example.com/page")));
    }

    @Test
    public void testRuleDeny_Block() {
        PermissionContext ctx = PermissionContext.create()
                .addRule(PermissionRule.deny("webfetch", "*evil.com*"));
        WebToolStrategy s = strategy("webfetch", ctx);
        Assertions.assertNotNull(s.evaluate(null, args("https://evil.com")));
    }

    // ========== websearch 工具 ==========

    @Test
    public void testWebsearch_Safe_Ask() {
        WebToolStrategy s = strategy("websearch", PermissionContext.create());
        Map<String, Object> map = new HashMap<>();
        map.put("query", "test query");
        Assertions.assertNotNull(s.evaluate(null, map));
    }

    // ========== link 参数别名 ==========

    @Test
    public void testLinkArg_Block() {
        WebToolStrategy s = strategy("webfetch", PermissionContext.create());
        Map<String, Object> map = new HashMap<>();
        map.put("link", "https://facebook.com");
        Assertions.assertNotNull(s.evaluate(null, map));
    }

    // ========== 空参数 ==========

    @Test
    public void testNullArgs_Default_Ask() {
        WebToolStrategy s = strategy("webfetch", PermissionContext.create());
        Assertions.assertNotNull(s.evaluate(null, null));
    }
}
