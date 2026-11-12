package features.ai.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.prompt.PromptImpl;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.chat.talent.TalentUtil;
import org.noear.solon.ai.i18n.AiI18n;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Prompt 请求语言：序列化往返与才能标题行
 */
public class PromptLocaleTest {

    private static Prompt enPrompt() {
        return Prompt.of("hi").attrPut("k", "v").locale(Locale.ENGLISH);
    }

    @Test
    public void testJavaSerialization() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        new ObjectOutputStream(bos).writeObject(enPrompt());

        Prompt restored = (Prompt) new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray())).readObject();
        Assertions.assertEquals(Locale.ENGLISH, restored.locale());
        Assertions.assertEquals("v", restored.attr("k"));
    }

    @Test
    public void testJsonSnapshot() {
        //不带类名的 JSON 还原不了消息（接口类型），这里只验 attrs；带消息的快照见 testMcpTransfer
        String json = ONode.serialize(new PromptImpl().attrPut("k", "v").locale(Locale.ENGLISH));
        //值只在 attrs 里，不会多出独立的 locale 字段
        Assertions.assertFalse(json.contains("\"locale\""), json);

        Prompt restored = ONode.deserialize(json, PromptImpl.class);
        Assertions.assertEquals(Locale.ENGLISH, restored.locale(), json);
    }

    @Test
    public void testMcpTransfer() {
        //与 McpTalentClient / McpTalentServer 相同的序列化方式
        String json = ONode.serialize(enPrompt(), Feature.Write_ClassName);
        Prompt restored = ONode.deserialize(json, PromptImpl.class, Feature.Read_AutoType);
        Assertions.assertEquals(Locale.ENGLISH, restored.locale(), json);
    }

    @Test
    public void testAttrsOnlyData() {
        //只带 attrs.__locale 的数据（如外部直接写入）也能读出
        String json = "{\"attrs\":{\"__locale\":\"zh_CN\"},\"messages\":[]}";
        Prompt restored = ONode.deserialize(json, PromptImpl.class);
        Assertions.assertEquals(Locale.SIMPLIFIED_CHINESE, restored.locale());
    }

    // ==================== 读写一致性（locale() 始终反映 attrs 的当前值） ====================

    @Test
    public void testSetThenRead() {
        Prompt prompt = Prompt.of("hi").locale(Locale.ENGLISH);
        Assertions.assertEquals(Locale.ENGLISH, prompt.locale());
    }

    @Test
    public void testSetTwiceReadsLatest() {
        Prompt prompt = Prompt.of("hi").locale(Locale.ENGLISH);
        prompt.locale();
        prompt.locale(Locale.SIMPLIFIED_CHINESE);
        Assertions.assertEquals("zh-CN", prompt.attr(AiI18n.ATTR_LOCALE));
        Assertions.assertEquals(Locale.SIMPLIFIED_CHINESE, prompt.locale());
    }

    @Test
    public void testReadThenSet() {
        Prompt prompt = Prompt.of("hi");
        Assertions.assertNull(prompt.locale());
        prompt.locale(Locale.ENGLISH);
        Assertions.assertEquals(Locale.ENGLISH, prompt.locale());
    }

    @Test
    public void testReadThenAttrsPutAll() {
        //与 ReActTrace / TeamTrace 合并工具上下文的写法一致
        Prompt prompt = Prompt.of("hi").locale(Locale.SIMPLIFIED_CHINESE);
        Assertions.assertEquals(Locale.SIMPLIFIED_CHINESE, prompt.locale());

        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(AiI18n.ATTR_LOCALE, "en");
        prompt.attrs().putAll(toolContext);
        Assertions.assertEquals(Locale.ENGLISH, prompt.locale());
    }

    @Test
    public void testSetNullRemoves() {
        Prompt prompt = Prompt.of("hi").locale(Locale.ENGLISH);
        prompt.locale();
        prompt.locale(null);
        Assertions.assertFalse(prompt.attrs().containsKey(AiI18n.ATTR_LOCALE));
        Assertions.assertNull(prompt.locale());
    }

    @Test
    public void testCopyIndependent() {
        Prompt prompt = Prompt.of("hi").locale(Locale.ENGLISH);
        Prompt copy = prompt.copy();
        Assertions.assertEquals(Locale.ENGLISH, copy.locale());

        prompt.locale(null);
        Assertions.assertNull(prompt.locale());
        Assertions.assertEquals(Locale.ENGLISH, copy.locale());

        copy.locale(Locale.SIMPLIFIED_CHINESE);
        Assertions.assertNull(prompt.locale());
        Assertions.assertEquals(Locale.SIMPLIFIED_CHINESE, copy.locale());
    }

    @Test
    public void testTalentTitleFollowsPrompt() {
        Talent talent = new Talent() {
            @Override
            public String name() {
                return "Demo";
            }

            @Override
            public String description(Prompt prompt) {
                return AiI18n.isChinese(AiI18n.localeOf(prompt)) ? "演示才能" : "Demo talent";
            }

            @Override
            public String getInstruction(Prompt prompt) {
                return AiI18n.isChinese(AiI18n.localeOf(prompt)) ? "中文正文" : "English body";
            }
        };

        String en = TalentUtil.activeTalents(new ChatOptions().talentAdd(talent), enPrompt(), new StringBuilder()).toString();
        Assertions.assertTrue(en.contains("# [Talent: Demo] - Demo talent\n"), en);
        Assertions.assertTrue(en.contains("English body"), en);

        String zh = TalentUtil.activeTalents(new ChatOptions().talentAdd(talent), Prompt.of("hi"), new StringBuilder()).toString();
        Assertions.assertTrue(zh.contains("# [Talent: Demo] - 演示才能\n"), zh);

        //prompt 为 null 走默认语言；metadata 也取默认语言
        Assertions.assertEquals("演示才能", talent.description(null));
        Assertions.assertEquals("演示才能", talent.metadata().getDescription());
    }

    /**
     * 兼容桥：只重写了已弃用 description() 的旧实现，仍能出现在标题行与 metadata 里
     * （全仓唯一故意使用已弃用方法的地方，用来锁住 Talent 接口里的转调）
     */
    @Test
    @SuppressWarnings("deprecation")
    public void testLegacyDescriptionBridge() {
        Talent talent = new Talent() {
            @Override
            public String description(Prompt prompt) {
                return "fixed";
            }
        };

        Assertions.assertEquals("fixed", talent.description(enPrompt()));
        Assertions.assertEquals("fixed", talent.description(null));
        Assertions.assertEquals("fixed", talent.metadata().getDescription());
    }

    @Test
    public void testDescriptionDefaultsToNull() {
        Talent talent = new Talent() {
        };

        Assertions.assertNull(talent.description(null));
        Assertions.assertNull(talent.metadata().getDescription());
    }
}
