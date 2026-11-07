package org.noear.solon.ai.harness.hitl;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionBehavior;
import org.noear.solon.ai.harness.permission.PermissionRule;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三个内置策略的默认决策、可配置项与内置规则开关。
 *
 * <p>锁定一条安全不变量：三个策略的默认决策必须是 {@link PermissionBehavior#ASK}。
 * 内置规则只是白/黑名单，未命中者恰是未知操作，默认放行等于绕过人工确认。</p>
 */
class ToolStrategyDefaultsTest {

    private static Map<String, Object> args(String key, String value) {
        Map<String, Object> map = new HashMap<>();
        if (value != null) {
            map.put(key, value);
        }
        return map;
    }

    // ========== 默认决策（安全不变量） ==========

    @Test
    void bashUnclassifiedCommandAsksByDefault() {
        BashToolStrategy s = new BashToolStrategy();
        // rm -rf 不在任何内置黑名单也不在只读白名单 → 必须人工确认，不得放行
        assertNotNull(s.evaluate(null, args("command", "rm -rf /tmp/x")));
    }

    @Test
    void writeNormalPathAsksByDefault() {
        WriteToolStrategy s = new WriteToolStrategy("write");
        assertNotNull(s.evaluate(null, args("file_path", "src/App.java")));
    }

    @Test
    void webSafeUrlAsksByDefault() {
        WebToolStrategy s = new WebToolStrategy("webfetch");
        assertNotNull(s.evaluate(null, args("url", "https://example.com/doc")));
    }

    @Test
    void defaultDecisionAllowsWhenConfigured() {
        // 宿主可显式放宽（YOLO 模式）；null 表示不改动当前值
        BashToolStrategy s = new BashToolStrategy().defaultDecision(PermissionBehavior.ALLOW);
        assertNull(s.evaluate(null, args("command", "rm -rf /tmp/x")));

        // null 表示不改动：仍保持 ALLOW
        s.defaultDecision(null);
        assertNull(s.evaluate(null, args("command", "rm -rf /tmp/x")));
        // 显式改回 ASK 才恢复拦截
        s.defaultDecision(PermissionBehavior.ASK);
        assertNotNull(s.evaluate(null, args("command", "rm -rf /tmp/x")));
    }

    // ========== internalRulesEnabled 开关 ==========

    @Test
    void bashDisablingInternalRulesLetsBlacklistThrough() {
        BashToolStrategy s = new BashToolStrategy()
                .internalRulesEnabled(false)
                .defaultDecision(PermissionBehavior.ALLOW);
        // 内置规则关闭后，注入类命令不再被 DENY，落到默认决策 ALLOW
        // 注意：不能用 echo 系命令验证，它在只读白名单里，本就放行；
        // 这里用 curl（既非只读白名单也非黑名单）
        assertNull(s.evaluate(null, args("command", "curl $(cat url.txt)")));
    }

    @Test
    void writeDisablingInternalRulesLetsTraversalThrough() {
        WriteToolStrategy s = new WriteToolStrategy("write")
                .internalRulesEnabled(false)
                .defaultDecision(PermissionBehavior.ALLOW);
        assertNull(s.evaluate(null, args("file_path", "../../etc/passwd")));
    }

    @Test
    void webDisablingInternalRulesLetsRiskyHostThrough() {
        WebToolStrategy s = new WebToolStrategy("webfetch")
                .internalRulesEnabled(false)
                .defaultDecision(PermissionBehavior.ALLOW);
        assertNull(s.evaluate(null, args("url", "https://facebook.com/x")));
    }

    // ========== Web 内置域名黑名单的默认内容与可扩展性 ==========

    @Test
    void webBuiltinBlacklistCoversSocialSites() {
        WebToolStrategy s = new WebToolStrategy("webfetch");
        for (String host : Arrays.asList(
                "https://facebook.com/a", "https://twitter.com/a", "https://x.com/a",
                "https://tiktok.com/a", "https://reddit.com/a", "https://linkedin.com/a")) {
            assertNotNull(s.evaluate(null, args("url", host)), "应当拒绝高风险域名: " + host);
        }
    }

    @Test
    void webRiskyHostnameAddsCustomEntry() {
        WebToolStrategy s = new WebToolStrategy("webfetch").riskyHostname("evil.test");
        assertNotNull(s.evaluate(null, args("url", "https://evil.test/p")));
        // 子域名同样命中
        assertNotNull(s.evaluate(null, args("url", "https://sub.evil.test/p")));
    }

    @Test
    void webRiskyHostnamesAddsBatch() {
        WebToolStrategy s = new WebToolStrategy("webfetch")
                .riskyHostnames(Arrays.asList("a.test", "b.test"));
        assertNotNull(s.evaluate(null, args("url", "https://a.test/x")));
        assertNotNull(s.evaluate(null, args("url", "https://b.test/x")));
    }

    @Test
    void webDenyMessageContainsHostname() {
        WebToolStrategy s = new WebToolStrategy("webfetch");
        String msg = s.evaluate(null, args("url", "https://www.facebook.com/page"));
        assertNotNull(msg);
        // 内置规则 prompt 为 null，由 toResult 动态生成含域名的消息；
        // 且 www. 前缀被剥离，消息里出现的是归一化后的主机名
        assertTrue(msg.contains("facebook.com"), "消息应包含域名，实际: " + msg);
    }

    // ========== 委托引擎：用户规则优先于默认决策 ==========

    @Test
    void webUserRuleOverridesDefaultAsk() {
        PermissionContext ctx = PermissionContext.create()
                .addRule(PermissionRule.allow("webfetch", "https://docs.test/*"));
        WebToolStrategy s = new WebToolStrategy("webfetch")
                .permissionContextSupplier(() -> ctx);
        assertNull(s.evaluate(null, args("url", "https://docs.test/guide")));
    }

    @Test
    void writeUserDenyRuleBlocksEvenWithoutBuiltinMatch() {
        PermissionContext ctx = PermissionContext.create()
                .addRule(PermissionRule.deny("write", "src/secret/*"));
        WriteToolStrategy s = new WriteToolStrategy("write")
                .permissionContextSupplier(() -> ctx);
        assertNotNull(s.evaluate(null, args("file_path", "src/secret/key.txt")));
    }

    @Test
    void permissionContextSupplierIgnoresNull() {
        BashToolStrategy s = new BashToolStrategy();
        assertSame(s, s.permissionContextSupplier(null));
        // 仍使用默认空上下文，行为不因传 null 而崩溃
        assertNull(s.evaluate(null, args("command", "cat README.md")));
    }

    // ========== null 参数防御（三个策略都不能因 args 缺字段而 NPE） ==========

    @Test
    void bashNullArgsDoesNotThrow() {
        assertNotNull(new BashToolStrategy().evaluate(null, null));
    }

    @Test
    void writeNullArgsDoesNotThrow() {
        assertNotNull(new WriteToolStrategy("write").evaluate(null, null));
    }

    @Test
    void webNullArgsDoesNotThrow() {
        assertNotNull(new WebToolStrategy("webfetch").evaluate(null, null));
    }

    @Test
    void bashNullCommandValueTreatedAsEmpty() {
        // command 键存在但值为 null，与空串同义（Assert.isEmpty 语义）→ 空命令放行
        Map<String, Object> map = new HashMap<>();
        map.put("command", null);
        assertNull(new BashToolStrategy().evaluate(null, map));
    }

    @Test
    void bashMissingCommandKeyIsNotTreatedAsEmpty() {
        // 键缺失才是"参数不全"，不能当空命令静默放行
        assertNotNull(new BashToolStrategy().evaluate(null, new HashMap<String, Object>()));
    }

    @Test
    void writeNullFilePathValueDoesNotThrow() {
        Map<String, Object> map = new HashMap<>();
        map.put("file_path", null);
        assertNotNull(new WriteToolStrategy("write").evaluate(null, map));
    }

    // ========== 消息文案：DENY 分支的两种来源 ==========

    @Test
    void bashPrivilegeDenyUsesDynamicMessageWithCommand() {
        String msg = new BashToolStrategy().evaluate(null, args("command", "sudo ls"));
        assertNotNull(msg);
        // 该规则 prompt=null，走 toResult 动态拼接命令内容
        assertTrue(msg.contains("sudo"), "消息应包含命令，实际: " + msg);
    }

    @Test
    void bashTraversalDenyUsesStaticPrompt() {
        // 首词是 cat，不再被特权规则误命中，落到回溯规则并给出正确理由
        String msg = new BashToolStrategy().evaluate(null, args("command", "cat ../../etc/passwd"));
        assertEquals("检测到路径回溯操作，禁止访问工作区外目录。", msg);
    }

    @Test
    void bashPasswdAsArgumentIsNotTreatedAsPrivilegeCommand() {
        // 回归：旧实现用 .*\\bpasswd\\b.* 会把参数里的 passwd 当成特权命令
        String msg = new BashToolStrategy().evaluate(null, args("command", "cat /etc/passwd"));
        assertNotNull(msg);
        assertEquals("禁止访问系统敏感配置文件。", msg);
    }

    @Test
    void writeTraversalDenyUsesStaticPrompt() {
        String msg = new WriteToolStrategy("write").evaluate(null, args("file_path", "../x.txt"));
        assertEquals("检测到路径回溯操作，禁止访问工作区外目录。", msg);
    }
}
