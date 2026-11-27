package org.noear.solon.ai.harness.agent;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionRule;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 代理权限守卫返回给模型的拒绝说明（deny / ask / bash_wait 写入；中英文）
 */
public class AgentPermissionGoldenTest {
    @Test
    public void testZh() throws Throwable {
        GoldenFiles.verify(renderOf(null, "_zh"));
    }

    @Test
    public void testZhExplicit() throws Throwable {
        GoldenFiles.verify(renderOf(Locale.CHINA, "_zh"));
    }

    @Test
    public void testEn() throws Throwable {
        GoldenFiles.verify(renderOf(Locale.ENGLISH, "_en"));
    }

    static Map<String, String> renderOf(Locale locale, String suffix) {
        AgentPermissionGuard guard = new AgentPermissionGuard(PermissionContext.create()
                .addRule(PermissionRule.deny("bash").priority(-100))
                .addRule(PermissionRule.allow("bash", "git log *"))
                .addRule(PermissionRule.ask("read", "*.env")));

        Map<String, String> out = new LinkedHashMap<>();
        out.put("permission_agent_deny" + suffix, guard.denyReason("bash", args("command", "git log; rm -rf build"), locale));
        out.put("permission_agent_ask" + suffix, guard.denyReason("bash", args("command", "git log > out.txt"), locale) + "\n----\n"
                + guard.denyReason("read", args("file_path", "conf/.env"), locale));

        Map<String, Object> input = args("session_id", "s1");
        input.put("chars", "q");
        out.put("permission_agent_wait_input" + suffix, guard.denyReason("bash_wait", input, locale));
        return out;
    }

    private static Map<String, Object> args(String key, String value) {
        Map<String, Object> args = new HashMap<>();
        args.put(key, value);
        return args;
    }
}
