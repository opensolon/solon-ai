package features.ai.talents.memory.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;
import org.noear.solon.ai.talents.memory.MemoryTalent;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MemoryTalent 对模型可见文本的渲染快照（描述、指令、工具 schema、各工具返回值）
 *
 * <p>中文快照由改造前的代码生成，国际化后必须逐字不变；英文快照经人工审校后生成。
 * 记录时间统一替换为 {@code <time>}。</p>
 */
public class MemoryRenderGoldenTest {
    static final String CWD = ".";
    static final String SID = "s1";
    static final String T0 = "2026-01-02 03:04:05";

    @Test
    public void testZh() throws Exception {
        GoldenFiles.verify(renderOf(null, "_zh"));
    }

    /**
     * 请求语言为中文时与默认一致
     */
    @Test
    public void testZhExplicit() throws Exception {
        GoldenFiles.verify(renderOf("zh-CN", "_zh"));
    }

    @Test
    public void testEn() throws Exception {
        GoldenFiles.verify(renderOf("en", "_en"));
    }

    static String norm(String text) {
        return text == null ? null : text.replaceAll("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}", "<time>");
    }

    static Prompt prompt(String tag, String userContent) {
        Prompt prompt = Prompt.of(userContent);
        if (tag != null) {
            prompt.locale(AiI18n.toLocale(tag));
        }
        prompt.attrPut("__cwd", CWD);
        prompt.attrPut(ChatSession.ATTR_SESSIONID, SID);
        return prompt;
    }

    // ---- 工具调用（tag 为工具上下文注入的 __locale；null 表示未注入） ----

    static String ext(MemoryTalent t, String tag, String key, String fact, int imp, String scope) {
        return norm(t.extract(key, fact, imp, scope, CWD, SID, tag));
    }

    static String srch(MemoryTalent t, String tag, String query, Integer topK) {
        return norm(t.search(query, topK, CWD, SID, tag));
    }

    static String rec(MemoryTalent t, String tag, String key) {
        return norm(t.recall(key, CWD, SID, tag));
    }

    static String cons(MemoryTalent t, String tag, String newKey, String insight, String... keys) {
        return norm(t.consolidate(keys == null ? null : Arrays.asList(keys), newKey, insight, null, CWD, SID, tag));
    }

    static String prn(MemoryTalent t, String tag, String key) {
        return norm(t.prune(key, CWD, SID, tag));
    }

    /**
     * @param tag 工具上下文注入的 __locale（null 表示未注入，用默认语言）；请求语言同 tag
     */
    static Map<String, String> renderOf(String tag, String suffix) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();

        FakeMemory mem = new FakeMemory();
        MemoryTalent t = new MemoryTalent(FakeMemory.provider(mem));
        MemoryTalent none = new MemoryTalent(FakeMemory.provider(null));

        if (tag == null) {
            out.put("memory_description" + suffix, t.description(null));
        }
        out.put("memory_tools", GoldenFiles.dumpTools(t.getTools(prompt(tag, "hi"))));

        // ---- 指令 ----
        out.put("memory_instruction_empty" + suffix, norm(t.getInstruction(prompt(tag, "hi"))));

        // ---- extract ----
        out.put("memory_extract_blank_key" + suffix, ext(t, tag, " ", "x", 5, null));
        out.put("memory_extract_blank_fact" + suffix, ext(t, tag, "k", " ", 5, null));
        out.put("memory_extract_no_solution" + suffix, ext(none, tag, "k", "x", 5, null));
        out.put("memory_extract_new" + suffix, ext(t, tag, "user-stack", "项目使用 Solon 框架", 8, null));
        out.put("memory_extract_update" + suffix, ext(t, tag, "user-stack", "项目使用 Solon 4 框架", 8, "user"));
        out.put("memory_extract_near" + suffix, ext(t, tag, "stack-note", "项目使用 Solon 4 框架，Java 8", 6, null));
        mem.putThrows.add("broken");
        out.put("memory_extract_error" + suffix, ext(t, tag, "broken", "x", 5, null));

        out.put("memory_instruction_data" + suffix, norm(t.getInstruction(prompt(tag, "Solon"))));

        //碎片：超过阈值提示整合，超过 8 条折叠计数
        for (int i = 1; i <= 9; i++) {
            ext(t, tag, "frag-" + i, "碎片 " + i, 2, null);
        }
        out.put("memory_extract_fragments" + suffix, ext(t, tag, "frag-10", "碎片 10", 2, null));

        // ---- search ----
        out.put("memory_search_blank" + suffix, srch(t, tag, "", null));
        out.put("memory_search_no_solution" + suffix, srch(none, tag, "x", null));
        out.put("memory_search_none" + suffix, srch(t, tag, "不存在的内容", 3));
        out.put("memory_search_hits" + suffix, srch(t, tag, "Solon", 5));
        mem.seed("no-time", "没有时间的记忆 Solon", 3, null, null);
        out.put("memory_search_list" + suffix, srch(t, tag, "*", null));

        FakeMemory empty = new FakeMemory();
        out.put("memory_search_list_empty" + suffix, srch(new MemoryTalent(FakeMemory.provider(empty)), tag, "*", null));

        FakeMemory big = new FakeMemory();
        for (int i = 0; i < 100; i++) {
            big.seed(String.format("k%03d", i), "内容 " + i, 5, T0, "workspace");
        }
        out.put("memory_search_list_full" + suffix, srch(new MemoryTalent(FakeMemory.provider(big)), tag, "*", null));

        // ---- recall ----
        out.put("memory_recall_no_solution" + suffix, rec(none, tag, "k"));
        out.put("memory_recall_found" + suffix, rec(t, tag, "user-stack"));
        mem.seed("bare", "只有内容", null, null, null);
        out.put("memory_recall_bare" + suffix, rec(t, tag, "bare"));
        out.put("memory_recall_missing" + suffix, rec(t, tag, "nope"));

        // ---- consolidate ----
        out.put("memory_consolidate_blank_key" + suffix, cons(t, tag, " ", "x", "a"));
        out.put("memory_consolidate_blank_insight" + suffix, cons(t, tag, "n", " ", "a"));
        out.put("memory_consolidate_no_keys" + suffix, cons(t, tag, "n", "x", (String[]) null));
        out.put("memory_consolidate_no_solution" + suffix, cons(none, tag, "n", "x", "a"));
        out.put("memory_consolidate_unreadable" + suffix, cons(t, tag, "n", "x", "nope1", "nope2"));

        mem.getThrows.add("busy-target");
        out.put("memory_consolidate_target_error" + suffix, cons(t, tag, "busy-target", "x", "frag-1"));
        out.put("memory_consolidate_target_exists" + suffix, cons(t, tag, "user-stack", "x", "frag-1"));
        mem.putThrows.add("cant-write");
        out.put("memory_consolidate_write_failed" + suffix, cons(t, tag, "cant-write", "x", "frag-1"));
        mem.putDrops.add("lost");
        out.put("memory_consolidate_verify_failed" + suffix, cons(t, tag, "lost", "x", "frag-1"));

        mem.removeThrows.add("frag-2");
        out.put("memory_consolidate_partial" + suffix, cons(t, tag, "insight-a", "碎片整合", "frag-1", "frag-2"));
        mem.seed("rule", "永久定论", 10, T0, "user");
        out.put("memory_consolidate_partial_protected" + suffix, cons(t, tag, "insight-b", "碎片整合", "frag-3", "frag-2", "rule"));
        out.put("memory_consolidate_in_place" + suffix, cons(t, tag, "insight-a", "碎片整合 v2", "insight-a"));
        out.put("memory_consolidate_in_place_protected" + suffix, cons(t, tag, "insight-c", "整合", "rule"));
        out.put("memory_consolidate_ok" + suffix, cons(t, tag, "insight-d", "碎片整合", "frag-4", "frag-5"));
        out.put("memory_consolidate_ok_protected" + suffix, cons(t, tag, "insight-e", "碎片整合", "frag-6", "rule"));

        // ---- prune ----
        out.put("memory_prune_no_solution" + suffix, prn(none, tag, "k"));
        out.put("memory_prune_missing" + suffix, prn(t, tag, "nope"));
        out.put("memory_prune_ok" + suffix, prn(t, tag, "frag-7"));
        out.put("memory_prune_failed" + suffix, prn(t, tag, "frag-2"));

        return out;
    }
}
