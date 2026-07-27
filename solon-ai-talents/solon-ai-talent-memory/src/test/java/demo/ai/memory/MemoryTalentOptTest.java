package demo.ai.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.memory.MemorySearcher;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.talents.memory.MemoryStorer;
import org.noear.solon.ai.talents.memory.MemoryTalent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryTalent 优化项回归测试（基于纯 MD 方案，零外部依赖）。
 *
 * 覆盖：
 * - M1.1：memory_search('*') 列全部能列出低重要度(Imp<5)条目
 * - M1.3：search 的 topK 参数可调
 * - M3.1：extract 近似 Key 探测提示
 * - M4.1：碎片密度检测提示
 */
public class MemoryTalentOptTest {
    private Path tmpDir;
    private MemorySolutionMdImpl solution;
    private MemoryTalent talent;

    private static final String CWD = ".";
    private static final String SID = "s1";

    @BeforeEach
    public void setup() throws IOException {
        tmpDir = Files.createTempDirectory("mem_talent_opt_");
        solution = new MemorySolutionMdImpl(tmpDir.toString());
        MemorySolutionProvider provider = new MemorySolutionProvider() {
            @Override
            public MemorySolution get(String __cwd) {
                return solution;
            }

            @Override
            public String getScopesDefault() {
                return MemorySolutionProvider.SCOPE_USER;
            }
        };
        talent = new MemoryTalent(provider);
    }

    @AfterEach
    public void teardown() throws IOException {
        if (solution != null) {
            solution.close();
        }
        // 清理临时目录
        if (tmpDir != null && Files.exists(tmpDir)) {
            Files.walk(tmpDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    public void listAll_should_include_low_importance_entries() {
        talent.extract("trivial_note", "当前正在处理 foo.java 文件", 2, CWD, SID);
        talent.extract("core_stack", "项目长期技术栈为 Solon", 8, CWD, SID);

        String listing = talent.search("*", null, CWD, SID);

        // M1.1：Imp<5 的条目必须出现（旧实现会被 Imp>=5 过滤掉）
        assertTrue(listing.contains("trivial_note"), "低分条目应被列出: " + listing);
        assertTrue(listing.contains("core_stack"), "高分条目应被列出: " + listing);
    }

    @Test
    public void search_topK_should_be_adjustable() {
        for (int i = 0; i < 6; i++) {
            talent.extract("note_solon_" + i, "Solon 框架相关笔记 " + i, 6, CWD, SID);
        }

        String top2 = talent.search("Solon", 2, CWD, SID);
        int lines2 = countItemLines(top2);
        assertTrue(lines2 <= 2, "topK=2 应至多返回 2 条: " + top2);

        String top5 = talent.search("Solon", 5, CWD, SID);
        int lines5 = countItemLines(top5);
        assertTrue(lines5 > lines2, "topK=5 应比 topK=2 返回更多: " + top5);
    }

    @Test
    public void extract_should_hint_near_key_on_similar_content() {
        talent.extract("user_tech_stack", "用户技术栈是 Solon 与 Java", 7, CWD, SID);

        // 不同 Key 但语义高度相似，应触发近似 Key 提示
        String feedback = talent.extract("tech_preference", "用户技术栈是 Solon 与 Java", 7, CWD, SID);

        assertTrue(feedback.contains("Key 治理") || feedback.contains("user_tech_stack"),
                "应提示疑似已存 Key: " + feedback);
    }

    @Test
    public void extract_should_hint_consolidate_when_fragments_pile_up() {
        String last = "";
        for (int i = 0; i < 6; i++) {
            last = talent.extract("frag_" + i, "零散碎片信息 " + i, 2, CWD, SID);
        }
        // 第 6 条写入后，低分碎片数(6) >= 阈值(5)，应出现整合建议
        assertTrue(last.contains("memory_consolidate"), "应提示整合碎片: " + last);
    }

    @Test
    public void consolidate_should_keep_new_key_when_it_is_reused_from_old_keys() {
        // 先写入两条碎片，其中一个 Key 将被复用为 newKey
        talent.extract("user_pref", "用户偏好 A", 4, CWD, SID);
        talent.extract("user_pref_tmp", "用户偏好 B", 4, CWD, SID);

        // newKey 恰好包含在 oldKeys 中（同名合并回主键）
        String result = talent.consolidate(
                java.util.Arrays.asList("user_pref", "user_pref_tmp"),
                "user_pref",
                "用户的综合偏好洞察",
                CWD, SID);
        assertTrue(result.contains("进化成功"), "合并应成功: " + result);

        // 新洞察必须仍可召回（不得因自删而丢失）
        String recalled = talent.recall("user_pref", CWD, SID);
        assertTrue(recalled.contains("综合偏好洞察"), "newKey 不得被误删: " + recalled);
    }

    @Test
    public void search_topK_should_be_capped_at_upper_bound() {
        for (int i = 0; i < 8; i++) {
            talent.extract("cap_note_" + i, "Solon 缓存相关 " + i, 6, CWD, SID);
        }
        // 传入超大 topK，不应因无上限而崩溃，且不超过实际条数
        String r = talent.search("Solon", 100000, CWD, SID);
        int lines = countItemLines(r);
        assertTrue(lines <= 100, "topK 应被上限约束: " + lines);
    }

    @Test
    public void search_should_guard_empty_query() {
        String r = talent.search(null, 5, CWD, SID);
        assertTrue(r.contains("query") || r.contains("为空"), "空 query 应得到友好提示: " + r);
    }

    @Test
    public void consolidate_should_keep_old_fragments_when_new_write_fails() {
        // 先用正常 talent 写入两条碎片
        talent.extract("frag_a", "碎片 A", 4, CWD, SID);
        talent.extract("frag_b", "碎片 B", 4, CWD, SID);

        // 构造一个写入被丢弃（模拟底层存储失败，extract 会吞掉异常）的 storer 包装
        MemoryStorer realStorer = solution.getStorer();
        MemorySearcher realSearcher = solution.getSearcher();
        MemoryStorer droppingStorer = new MemoryStorer() {
            @Override
            public void put(String userId, String key, String val, int ttl, String scope) {
                // 丢弃新写入（仅对新 newKey），旧碎片保持可读
                if (!"merged_insight".equals(key)) {
                    realStorer.put(userId, key, val, ttl, scope);
                }
            }

            @Override
            public String get(String userId, String key) {
                return realStorer.get(userId, key);
            }

            @Override
            public void remove(String userId, String key) {
                realStorer.remove(userId, key);
            }
        };
        MemorySolution droppingSolution = new MemorySolution() {
            @Override
            public MemorySearcher getSearcher() {
                return realSearcher;
            }

            @Override
            public MemoryStorer getStorer() {
                return droppingStorer;
            }
        };
        MemoryTalent guarded = new MemoryTalent(new MemorySolutionProvider() {
            @Override
            public MemorySolution get(String __cwd) {
                return droppingSolution;
            }

            @Override
            public String getScopesDefault() {
                return MemorySolutionProvider.SCOPE_USER;
            }
        });

        String result = guarded.consolidate(
                java.util.Arrays.asList("frag_a", "frag_b"),
                "merged_insight",
                "综合洞察",
                CWD, SID);

        // 写入校验失败，必须报异常且不得删旧碎片
        assertTrue(result.contains("合并异常") || result.contains("未做任何清理"),
                "写入校验失败应提示异常: " + result);
        assertTrue(guarded.recall("frag_a", CWD, SID).contains("碎片 A"), "frag_a 不得被删");
        assertTrue(guarded.recall("frag_b", CWD, SID).contains("碎片 B"), "frag_b 不得被删");
    }

    @Test
    public void consolidate_should_report_partial_failure_when_prune_fails() {
        // 写入两条碎片
        talent.extract("pf_a", "碎片 A", 4, CWD, SID);
        talent.extract("pf_b", "碎片 B", 4, CWD, SID);

        MemoryStorer realStorer = solution.getStorer();
        MemorySearcher realSearcher = solution.getSearcher();
        // 对 pf_b 的删除故意抛异常，模拟底层删除失败
        MemoryStorer failingRemoveStorer = new MemoryStorer() {
            @Override
            public void put(String userId, String key, String val, int ttl, String scope) {
                realStorer.put(userId, key, val, ttl, scope);
            }

            @Override
            public String get(String userId, String key) {
                return realStorer.get(userId, key);
            }

            @Override
            public void remove(String userId, String key) {
                if ("pf_b".equals(key)) {
                    throw new RuntimeException("mock remove failure");
                }
                realStorer.remove(userId, key);
            }
        };
        MemorySolution wrapped = new MemorySolution() {
            @Override
            public MemorySearcher getSearcher() {
                return realSearcher;
            }

            @Override
            public MemoryStorer getStorer() {
                return failingRemoveStorer;
            }
        };
        MemoryTalent t = new MemoryTalent(new MemorySolutionProvider() {
            @Override
            public MemorySolution get(String __cwd) {
                return wrapped;
            }

            @Override
            public String getScopesDefault() {
                return MemorySolutionProvider.SCOPE_USER;
            }
        });

        String result = t.consolidate(
                java.util.Arrays.asList("pf_a", "pf_b"),
                "pf_merged",
                "综合洞察",
                CWD, SID);

        // 删除失败必须被如实上报为“部分成功”，且指名 pf_b；不得误报为完全成功
        assertTrue(result.contains("部分成功"), "删除失败应报部分成功: " + result);
        assertTrue(result.contains("pf_b"), "应指名失败的碎片: " + result);
        // pf_a 删除成功、pf_b 因失败保留
        assertTrue(t.recall("pf_a", CWD, SID).contains("未找到"), "pf_a 应已删除");
        assertTrue(t.recall("pf_b", CWD, SID).contains("碎片 B"), "pf_b 删除失败应保留");
    }

    @Test
    public void consolidate_should_guard_empty_insight() {
        talent.extract("gi_a", "碎片 A", 4, CWD, SID);

        // insight 为空：不得写入字面量 "[Evolved Insight] null"，也不得删旧碎片
        String result = talent.consolidate(
                java.util.Arrays.asList("gi_a"),
                "gi_merged",
                null,
                CWD, SID);

        assertTrue(result.contains("合并异常") || result.contains("evolved_insight"),
                "空 insight 应提示异常: " + result);
        assertTrue(talent.recall("gi_a", CWD, SID).contains("碎片 A"), "旧碎片不得被删");
        assertTrue(talent.recall("gi_merged", CWD, SID).contains("未找到"), "不得写入空洞察");
    }

    @Test
    public void extract_should_clamp_importance_out_of_range() {
        // 超上界：11 应被夹为 10（而非任意升为永久或造成分档异常）
        talent.extract("clamp_hi", "超高重要度条目", 11, CWD, SID);
        String hi = talent.recall("clamp_hi", CWD, SID);
        assertTrue(hi.contains("重要度：10"), "11 应被夹为 10: " + hi);

        // 下界：0 应被夹为 1
        talent.extract("clamp_lo", "零重要度条目", 0, CWD, SID);
        String lo = talent.recall("clamp_lo", CWD, SID);
        assertTrue(lo.contains("重要度：1"), "0 应被夹为 1: " + lo);
    }

    @Test
    public void consolidate_should_not_npe_when_old_keys_null() {
        // 仅写入新洞察，不传 keys_to_merge
        String result = talent.consolidate(null, "solo_insight", "独立洞察", CWD, SID);

        assertTrue(result.contains("进化成功"), "oldKeys 为 null 不应抛异常: " + result);
        assertTrue(result.contains("无冗余碎片"), "无碎片应给出对应措辞: " + result);
        assertTrue(talent.recall("solo_insight", CWD, SID).contains("独立洞察"), "新洞察应写入");
    }

    private static int countItemLines(String text) {
        int count = 0;
        for (String line : text.split("\n")) {
            if (line.startsWith("- ")) {
                count++;
            }
        }
        return count;
    }
}
