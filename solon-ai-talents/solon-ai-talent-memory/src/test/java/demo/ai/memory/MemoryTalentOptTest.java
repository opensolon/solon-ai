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
