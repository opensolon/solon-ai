package features.ai.talents.memory.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.talents.memory.MemoryTalent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 没有请求上下文的入口跟随默认语言；工具描述与语言无关（scope 参数说明由方案提供）
 */
public class MemoryI18nScenarioTest {
    @AfterEach
    public void restore() {
        AiI18n.setDefaultLocale(Locale.CHINESE);
    }

    @Test
    public void testDefaultLocaleEn() throws Exception {
        AiI18n.setDefaultLocale(Locale.ENGLISH);
        FakeMemory mem = new FakeMemory();
        MemoryTalent talent = new MemoryTalent(FakeMemory.provider(mem));

        GoldenFiles.verify("memory_description_en", talent.description(null));
        GoldenFiles.verify("memory_tools", GoldenFiles.dumpTools(talent.getToolAry()));
        //未注入 __locale 时按默认语言（旧签名）
        GoldenFiles.verify("memory_extract_blank_key_en", talent.extract(" ", "x", 5, MemoryRenderGoldenTest.CWD, MemoryRenderGoldenTest.SID));
        GoldenFiles.verify("memory_search_blank_en", talent.search("", null, MemoryRenderGoldenTest.CWD, MemoryRenderGoldenTest.SID));
        GoldenFiles.verify("memory_recall_missing_en", talent.recall("nope", MemoryRenderGoldenTest.CWD, MemoryRenderGoldenTest.SID));
        GoldenFiles.verify("memory_consolidate_blank_key_en", talent.consolidate(null, " ", "x", MemoryRenderGoldenTest.CWD, MemoryRenderGoldenTest.SID));
        GoldenFiles.verify("memory_prune_missing_en", talent.prune("nope", MemoryRenderGoldenTest.CWD, MemoryRenderGoldenTest.SID));
    }

    @Test
    public void testDefaultLocaleZh() throws Exception {
        MemoryTalent talent = new MemoryTalent(FakeMemory.provider(new FakeMemory()));

        GoldenFiles.verify("memory_description_zh", talent.description(null));
        GoldenFiles.verify("memory_tools", GoldenFiles.dumpTools(talent.getToolAry()));
    }

    /**
     * scope 参数说明来自方案（#{ScopesDescription} 绑定），与请求语言无关
     */
    @Test
    public void testScopesDescriptionFromProvider() {
        MemoryTalent talent = new MemoryTalent(new MemorySolutionProvider() {
            @Override
            public MemorySolution get(String __cwd) {
                return null;
            }

            @Override
            public String getScopesDescription() {
                return "custom scopes";
            }
        });

        for (Locale l : new Locale[]{Locale.CHINESE, Locale.ENGLISH}) {
            Assertions.assertEquals("custom scopes", scopeDescOf(talent, l, "memory_extract"));
            Assertions.assertEquals("custom scopes", scopeDescOf(talent, l, "memory_consolidate"));
        }
    }

    static String scopeDescOf(MemoryTalent talent, Locale locale, String toolName) {
        for (FunctionTool t : talent.getTools(MemoryRenderGoldenTest.prompt(AiI18n.toTag(locale), "hi"))) {
            if (toolName.equals(t.name())) {
                return ONode.ofJson(t.inputSchema()).get("properties").get("scope").get("description").getString();
            }
        }
        return null;
    }

    /**
     * 工具调用的返回文案按工具上下文注入的 __locale 输出
     */
    @Test
    public void testLocalizedToolStillWorks() throws Throwable {
        MemoryTalent talent = new MemoryTalent(FakeMemory.provider(new FakeMemory()));

        FunctionTool recall = null;
        for (FunctionTool t : talent.getTools(MemoryRenderGoldenTest.prompt("en", "hi"))) {
            if ("memory_recall".equals(t.name())) {
                recall = t;
            }
        }

        Assertions.assertNotNull(recall);
        Map<String, Object> args = new HashMap<>();
        args.put("key", "nope");
        args.put("__cwd", MemoryRenderGoldenTest.CWD);
        args.put("__sessionId", MemoryRenderGoldenTest.SID);
        args.put(AiI18n.ATTR_LOCALE, "en");
        GoldenFiles.verify("memory_recall_missing_en", String.valueOf(recall.handle(args)));
    }
}
