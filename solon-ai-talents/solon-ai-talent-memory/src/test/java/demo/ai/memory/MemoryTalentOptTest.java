package demo.ai.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
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
