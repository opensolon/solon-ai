package features.ai.i18n;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;

/**
 * AgentI18n 基础设施测试（不启动 Solon）
 */
public class AgentI18nTest {


    @Test
    public void testResolveWithoutSolon() {
        // null -> 默认中文；未内置的语言 -> 英文兜底；zh_CN / zh_TW -> 中文
        Assertions.assertEquals("具备自主行动能力的专业任务解决专家", AgentI18n.get(null, "react.prompt.role.default"));
        Assertions.assertEquals("Professional task expert with autonomous action capabilities",
                AgentI18n.get(Locale.JAPANESE, "react.prompt.role.default"));
        Assertions.assertEquals(AgentI18n.get(Locale.CHINESE, "react.prompt.role.default"),
                AgentI18n.get(Locale.TRADITIONAL_CHINESE, "react.prompt.role.default"));
        Assertions.assertTrue(AgentI18n.isChinese(null));
        Assertions.assertTrue(AgentI18n.isChinese(Locale.CHINA));
        Assertions.assertFalse(AgentI18n.isChinese(Locale.US));
    }

    @Test
    public void testMissingKey() {
        Assertions.assertThrows(MissingResourceException.class, () -> AgentI18n.get(Locale.ENGLISH, "no.such.key"));
        Assertions.assertFalse(AgentI18n.has(Locale.ENGLISH, "no.such.key"));
    }

    @Test
    public void testFormat() {
        // JSON 与未提供的名字原样保留；变量内容不被二次替换
        Assertions.assertEquals("{\"name\": \"x\", \"arguments\": {...}} A",
                AgentI18n.format("{\"name\": \"x\", \"arguments\": {...}} {role}", Collections.singletonMap("role", "A")));
        Assertions.assertEquals("a {b} b", AgentI18n.format("a {a} b", vars("a", "{b}", "b", "Z")));
        Assertions.assertEquals("x {other} y", AgentI18n.format("x {other} y", Collections.singletonMap("role", "A")));
        Assertions.assertEquals("'q' A A", AgentI18n.format("'q' {role} {role}", Collections.singletonMap("role", "A")));
        //旧的序号写法不再有特殊含义
        Assertions.assertEquals("{0}", AgentI18n.format("{0}", Collections.singletonMap("role", "A")));
    }

    /**
     * fmt 的四种重载：1/2/3 个命名变量与 Map，结果必须一致
     */
    @Test
    public void testFmtOverloads() {
        String one = AgentI18n.fmt(Locale.CHINESE, "simple.prompt.role", "role", "R");
        Assertions.assertEquals("## 你的角色\nR。", one);
        Assertions.assertEquals(one, AgentI18n.fmt(Locale.CHINESE, "simple.prompt.role", Collections.singletonMap("role", "R")));

        Assertions.assertEquals("成功：计划进度已更新。下一步是：第 2 步 - D",
                AgentI18n.fmt(Locale.CHINESE, "react.plan.result.progress.next", "step", 2, "desc", "D"));

        String three = AgentI18n.fmt(Locale.ENGLISH, "team.base.supervisor.protocol",
                "protocol", "P", "candidates", "a, b", "finishMarker", "[F]");
        Assertions.assertEquals(three, AgentI18n.fmt(Locale.ENGLISH, "team.base.supervisor.protocol",
                vars("protocol", "P", "candidates", "a, b", "finishMarker", "[F]")));
        Assertions.assertTrue(three.contains("P") && three.contains("[a, b]") && three.contains("[F]"), three);
        Assertions.assertFalse(three.contains("{"), three);
    }

    static Map<String, Object> vars(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    /**
     * 会话恢复：从 Prompt 携带的语言标签登记语言，无需手动 registerLocale
     */
    @Test
    public void testRegisterLocaleOf() {
        Locale other = I18nTestSupport.uniqueLocale();
        Assertions.assertFalse(AgentI18n.knownLocales().contains(other), "precondition");

        //无语言标签：不登记，也不抛异常
        AgentI18n.registerLocaleOf(null);
        AgentI18n.registerLocaleOf(Prompt.of("hi"));
        Assertions.assertFalse(AgentI18n.knownLocales().contains(other), "precondition");

        Prompt prompt = Prompt.of("hi");
        prompt.attrs().put(AgentI18n.ATTR_LOCALE, other.toLanguageTag());
        AgentI18n.registerLocaleOf(prompt);

        Assertions.assertTrue(AgentI18n.knownLocales().contains(other), "locale from prompt should be registered");

        //登记后，该语言的标记参与识别（未内置语言落回英文兜底）
        Assertions.assertFalse(AgentI18n.textsOfAllLocales("compress.vector.archived_tag").isEmpty());
    }
}
