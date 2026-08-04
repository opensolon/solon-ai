package demo.ai.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.talents.memory.MemorySearchResult;
import org.noear.solon.ai.talents.memory.md.MemoryMdData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryMdData 单元测试（零外部依赖，黑盒验证公开契约）。
 *
 * <p>重点覆盖搜索能力：分词、IDF 相关性排序、确定性 tie-break、
 * 子串兜底降权、TTL 过滤、用户隔离、多语言。
 * 其余覆盖存取往返、多作用域优先级、持久化重启、过期清理、旧格式兼容。
 *
 * @author noear
 */
public class MemoryMdDataTest {
    private static final String U1 = "u1";
    private static final String U2 = "u2";
    private static final String SCOPE_USER = "user";
    private static final String SCOPE_WS = "workspace";

    /** 固定时间常量，避免依赖当前时钟导致排序断言不稳定 */
    private static final String T_OLD = "2026-01-01 10:00:00";
    private static final String T_MID = "2026-02-01 10:00:00";
    private static final String T_NEW = "2026-03-01 10:00:00";

    private Path tmpRoot;
    private Path userDir;
    private Path wsDir;
    private MemoryMdData data;

    @BeforeEach
    public void setup() throws IOException {
        tmpRoot = Files.createTempDirectory("mem_md_data_");
        userDir = tmpRoot.resolve("user");
        wsDir = tmpRoot.resolve("workspace");
        data = newData();
    }

    @AfterEach
    public void teardown() throws IOException {
        if (data != null) {
            data.close();
        }
        deleteRecursively(tmpRoot);
    }

    // ==================== 搜索：分词与命中 ====================

    @Nested
    @DisplayName("搜索-分词与命中")
    class TokenizeAndHit {

        @Test
        @DisplayName("英文单词精确命中")
        public void english_word_hit() {
            save(U1, "k1", "Solon framework is lightweight", 5, T_NEW);
            save(U1, "k2", "Redis cache configuration", 5, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "framework", 10);

            assertEquals(1, rs.size());
            assertEquals("k1", rs.get(0).getKey());
        }

        @Test
        @DisplayName("查询大小写不敏感")
        public void query_is_case_insensitive() {
            save(U1, "k1", "Solon Framework", 5, T_NEW);

            assertEquals(1, data.search(U1, "SOLON", 10).size());
            assertEquals(1, data.search(U1, "solon", 10).size());
            assertEquals(1, data.search(U1, "SoLoN", 10).size());
        }

        @Test
        @DisplayName("中文完整短语命中")
        public void chinese_full_phrase_hit() {
            save(U1, "k1", "用户偏好使用响应式编程", 5, T_NEW);
            save(U1, "k2", "项目部署在容器环境", 5, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "响应式编程", 10);

            assertEquals(1, rs.size());
            assertEquals("k1", rs.get(0).getKey());
        }

        @Test
        @DisplayName("中文 bi-gram 部分命中（查询词非原文完整短语）")
        public void chinese_bigram_partial_hit() {
            save(U1, "k1", "用户偏好使用响应式编程", 5, T_NEW);

            // “编程”是原文 bi-gram 之一，不是完整短语，仍应命中
            List<MemorySearchResult> rs = data.search(U1, "编程", 10);

            assertEquals(1, rs.size());
            assertEquals("k1", rs.get(0).getKey());
        }

        @Test
        @DisplayName("中英混合内容可被中文或英文任一侧命中")
        public void mixed_language_content_hit() {
            save(U1, "k1", "用户偏好使用Solon框架", 5, T_NEW);

            assertEquals(1, data.search(U1, "solon", 10).size());
            assertEquals(1, data.search(U1, "框架", 10).size());
        }

        @Test
        @DisplayName("数字与字母数字混合 token 命中")
        public void alphanumeric_token_hit() {
            save(U1, "k1", "升级到 Java21 与 Solon 4.0", 5, T_NEW);

            assertEquals(1, data.search(U1, "java21", 10).size());
        }

        @Test
        @DisplayName("日文/韩文内容可命中（语言无关，不依赖停用词表）")
        public void non_cjk_unified_language_hit() {
            save(U1, "k1", "ユーザーはメモリ機能を使う", 5, T_NEW);
            save(U1, "k2", "사용자는 메모리 기능을 사용한다", 5, T_NEW);

            // 假名/韩文按连续字母段整体成词
            assertFalse(data.search(U1, "メモリ", 10).isEmpty(), "日文 token 应可命中");
            assertFalse(data.search(U1, "메모리", 10).isEmpty(), "韩文 token 应可命中");
        }

        @Test
        @DisplayName("完全无关查询返回空")
        public void no_match_returns_empty() {
            save(U1, "k1", "Solon framework", 5, T_NEW);

            assertTrue(data.search(U1, "kubernetes", 10).isEmpty());
        }

        @Test
        @DisplayName("纯符号查询不抛异常")
        public void punctuation_only_query_is_safe() {
            save(U1, "k1", "Solon framework", 5, T_NEW);

            // 分词后为空集合，不得抛异常
            assertNotNull(data.search(U1, "!!! ??? ...", 10));
        }

        @Test
        @DisplayName("英文单字母 token 被丢弃，多字母词正常命中")
        public void english_single_letter_dropped() {
            save(U1, "k1", "I use Solon", 5, T_NEW);

            assertTrue(data.search(U1, "i", 10).isEmpty(),
                    "单字母查询词分词后为空集合，不应产生命中");
            assertEquals(1, data.search(U1, "use", 10).size());
        }

        @Test
        @DisplayName("中文单字内容可被检索")
        public void chinese_single_char_content_searchable() {
            save(U1, "k1", "好", 5, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "好", 10);

            assertEquals(1, rs.size());
            assertEquals("k1", rs.get(0).getKey());
        }

        @Test
        @DisplayName("中文标点分隔的内容片段可被命中")
        public void chinese_punctuation_split_content_hit() {
            save(U1, "k1", "响应式，编程风格", 5, T_NEW);

            assertEquals(1, data.search(U1, "编程", 10).size());
        }

        @Test
        @DisplayName("连字符/下划线/URL 分词：符号一律为分隔符")
        public void punctuation_hyphen_underscore_url_tokenize() {
            save(U1, "phrase", "state-of-the-art solution", 5, T_NEW);
            save(U1, "under", "user_name config", 5, T_NEW);
            save(U1, "url", "https://solon.noear.org docs", 5, T_NEW);

            assertEquals("phrase", data.search(U1, "state", 10).get(0).getKey());
            assertEquals("under", data.search(U1, "user_name", 10).get(0).getKey(),
                    "下划线是分隔符，user_name 按 user+name 命中");
            assertEquals("url", data.search(U1, "solon", 10).get(0).getKey(),
                    "URL 中的主机名应可被检索");
            assertEquals("url", data.search(U1, "https", 10).get(0).getKey());
        }

        @Test
        @DisplayName("Markdown 符号与 URL 混排内容可命中关键词")
        public void markdown_symbols_content_hit() {
            save(U1, "k1", "使用 **Solon** 框架，详见 https://solon.noear.org", 5, T_NEW);

            assertEquals(1, data.search(U1, "solon", 10).size());
            assertEquals(1, data.search(U1, "https", 10).size());
            assertEquals(1, data.search(U1, "框架", 10).size());
        }

        @Test
        @DisplayName("全角英文与半角英文不互通（码位不同，语言边界）")
        public void fullwidth_vs_halfwidth_no_cross_match() {
            save(U1, "k1", "Ｓｏｌｏｎ 框架", 5, T_NEW);

            assertTrue(data.search(U1, "solon", 10).isEmpty(),
                    "全角英文按连续字母成词，与半角查询码位不同，不应命中");
            assertEquals(1, data.search(U1, "框架", 10).size());
        }
    }

    // ==================== 搜索：英文复杂用例 ====================

    @Nested
    @DisplayName("搜索-英文复杂用例")
    class EnglishComplex {

        @Test
        @DisplayName("英文词干/复数子串兜底：program 召回 programming，framework 召回 frameworks")
        public void english_stem_and_plural_substring_fallback() {
            save(U1, "stem", "Java programming guide", 5, T_NEW);
            save(U1, "plural", "Solon frameworks", 5, T_NEW);
            save(U1, "exact", "program skills", 5, T_NEW);

            // “program” 是 “programming” 的词干子串 → 子串兜底
            List<MemorySearchResult> rs = data.search(U1, "program", 10);
            assertEquals(2, rs.size());
            assertEquals("exact", rs.get(0).getKey(), "精确命中应排在词干子串之前");
            assertEquals("stem", rs.get(1).getKey(), "词干子串应兜底召回");

            // “framework” 是 “frameworks” 的复数前缀子串 → 子串兜底
            List<MemorySearchResult> rs2 = data.search(U1, "framework", 10);
            assertEquals(1, rs2.size());
            assertEquals("plural", rs2.get(0).getKey());
        }

        @Test
        @DisplayName("英文多词命中数主导排序（忽略重要度）")
        public void english_multi_word_hit_count_ranking() {
            save(U1, "two", "solon cache design", 1, T_NEW);
            save(U1, "one", "solon intro", 10, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "solon cache", 10);

            assertEquals("two", rs.get(0).getKey(),
                    "命中 2 词的条目应排在命中 1 词的高重要度条目之前");
        }

        @Test
        @DisplayName("查询含多词时：两词精确 > 一词精确 > 纯子串")
        public void mixed_exact_and_substring_ranking() {
            save(U1, "allExact", "solon framework", 5, T_NEW);
            save(U1, "partExact", "solonx framework", 5, T_NEW);
            save(U1, "allSub", "solonx frameworkx", 5, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "solon framework", 10);

            assertEquals(java.util.Arrays.asList("allExact", "partExact", "allSub"),
                    keysOf(rs), "全精确 > 部分精确 > 纯子串");
        }
    }

    // ==================== 搜索：中文复杂用例 ====================

    @Nested
    @DisplayName("搜索-中文复杂用例")
    class ChineseComplex {

        @Test
        @DisplayName("中文单字查询经子串兜底命中")
        public void chinese_single_char_query_substring_fallback() {
            save(U1, "k1", "响应式编程", 5, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "编", 10);

            assertEquals(1, rs.size(), "单字查询应经子串兜底召回");
            assertEquals("k1", rs.get(0).getKey());
        }

        @Test
        @DisplayName("中文近似词不误命中（bi-gram 无假阳性）")
        public void chinese_similar_word_not_false_positive() {
            save(U1, "k1", "编程语言入门", 5, T_NEW);

            assertTrue(data.search(U1, "编成", 10).isEmpty(),
                    "“编成”不是“编程语言入门”的任何 bi-gram，也不是其子串，不应命中");
        }

        @Test
        @DisplayName("中文查询子短语经 bi-gram 精确命中")
        public void chinese_sub_phrase_hit_via_bigram() {
            save(U1, "k1", "响应式编程实践", 5, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "式编程", 10);

            assertEquals(1, rs.size(), "“式编程”的 bi-gram 子集应命中原文");
            assertEquals("k1", rs.get(0).getKey());
        }

        @Test
        @DisplayName("中文重叠词 bi-gram 方向敏感：心相命中，相心不命中")
        public void chinese_bigram_direction_sensitive() {
            save(U1, "k1", "心心相印", 5, T_NEW);

            assertEquals(1, data.search(U1, "心相", 10).size(),
                    "“心相”是“心心相印”的 bi-gram，应精确命中");
            assertTrue(data.search(U1, "相心", 10).isEmpty(),
                    "“相心”不是任何 bi-gram，也不含子串，不应命中");
        }
    }

    // ==================== 搜索：相关性与排序 ====================

    @Nested
    @DisplayName("搜索-相关性与排序")
    class Relevance {

        @Test
        @DisplayName("相关性主导排序：多词命中优先于高重要度的少词命中")
        public void relevance_beats_importance() {
            // A 命中 2 个查询词但重要度最低；B 只命中 1 个词却重要度最高
            save(U1, "a", "Solon 缓存优化实践", 1, T_NEW);
            save(U1, "b", "Solon 入门简介", 10, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "Solon 缓存", 10);

            assertEquals(2, rs.size());
            assertEquals("a", rs.get(0).getKey(), "相关性应主导排序，而非重要度");
        }

        @Test
        @DisplayName("IDF 加权：命中高区分度词优先于命中高频词")
        public void idf_weights_rare_token_higher() {
            // 制造 “用户” 的高 df（5 条），“solon” 的低 df（1 条）
            for (int i = 0; i < 5; i++) {
                save(U1, "noise" + i, "用户记录条目", 5, T_NEW);
            }
            save(U1, "rare", "solon 深度实践", 5, T_NEW);

            // 查询同时含高频词与稀有词；两侧重要度、时间相同，排序只由 IDF 决定
            List<MemorySearchResult> rs = data.search(U1, "用户 solon", 10);

            assertFalse(rs.isEmpty());
            assertEquals("rare", rs.get(0).getKey(),
                    "低 df 的高区分度词应主导排序，高频词命中被压制");
        }

        @Test
        @DisplayName("自然语言整句查询能命中目标（IDF 平方加权抗噪，无需停用词表）")
        public void natural_language_sentence_query() {
            // 前提：IDF 是数据驱动的——虚词只有在语料中真正高频（df 大）才会被压制。
            // 真实记忆库中“用户的 / 是什 / 什么”这类片段广泛出现，此处构造多条同句式条目以还原这一分布。
            String[] noises = {
                    "用户的日程安排是什么样的", "用户的口味偏好是什么样的",
                    "用户的作息时间是什么样的", "用户的沟通风格是什么样的",
                    "用户的阅读习惯是什么样的", "用户的出行方式是什么样的"
            };
            for (int i = 0; i < noises.length; i++) {
                save(U1, "noise" + i, noises[i], 7, T_NEW);
            }
            save(U1, "target", "用户技术栈是 Solon 与 Java", 7, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "用户的 Solon 框架版本是什么", 10);

            assertFalse(rs.isEmpty());
            assertEquals("target", rs.get(0).getKey(),
                    "高频虚词命中数量再多，也不应盖过命中稀有专有名词的目标条目");
        }

        @Test
        @DisplayName("同分按重要度降序 tie-break")
        public void tie_break_by_importance() {
            // 内容完全相同 → 得分相同；仅重要度不同
            save(U1, "low", "Solon 框架笔记", 3, T_NEW);
            save(U1, "high", "Solon 框架笔记", 9, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "Solon", 10);

            assertEquals(2, rs.size());
            assertEquals("high", rs.get(0).getKey(), "同分应按重要度降序");
        }

        @Test
        @DisplayName("同分同重要度按时间倒序 tie-break（新→旧）")
        public void tie_break_by_time_desc() {
            save(U1, "older", "Solon 框架笔记", 5, T_OLD);
            save(U1, "newer", "Solon 框架笔记", 5, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "Solon", 10);

            assertEquals(2, rs.size());
            assertEquals("newer", rs.get(0).getKey(), "同分同重要度应按时间倒序");
        }

        @Test
        @DisplayName("排序稳定可重复（不依赖 map 遍历序）")
        public void ordering_is_deterministic() {
            for (int i = 0; i < 12; i++) {
                save(U1, "k" + i, "Solon 条目 " + i, i % 5 + 1, T_MID);
            }

            List<String> first = keysOf(data.search(U1, "Solon", 12));
            for (int round = 0; round < 5; round++) {
                assertEquals(first, keysOf(data.search(U1, "Solon", 12)),
                        "同一查询多次执行的排序必须一致");
            }
        }

        @Test
        @DisplayName("子串兜底命中：非完整 token 也能兜底召回")
        public void substring_fallback_hit() {
            // “solonx” 是一个整词 token，不含 “solon” 这个 token，只能靠子串兜底
            save(U1, "sub", "solonx-plugin 说明", 5, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "solon", 10);

            assertEquals(1, rs.size());
            assertEquals("sub", rs.get(0).getKey());
        }

        @Test
        @DisplayName("子串兜底必须降权：精确 token 命中优先于子串命中")
        public void substring_fallback_is_down_weighted() {
            save(U1, "exact", "solon framework", 5, T_NEW);
            save(U1, "sub", "solonx-plugin 说明", 5, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "solon", 10);

            assertEquals(2, rs.size());
            assertEquals("exact", rs.get(0).getKey(),
                    "精确 token 命中必须排在子串兜底命中之前");
        }

        @Test
        @DisplayName("子串兜底内部按命中词数排序")
        public void substring_fallback_ranks_by_hits() {
            save(U1, "two", "solonx redisx 双插件", 5, T_NEW);
            save(U1, "one", "solonx 单插件", 5, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "solon redis", 10);

            assertEquals(2, rs.size());
            assertEquals("two", rs.get(0).getKey(), "子串命中词更多的应更靠前");
        }

        @Test
        @DisplayName("完整排序链：score > importance > time 三级综合")
        public void full_sort_chain_score_importance_time() {
            save(U1, "e1", "Solon 缓存优化", 3, T_OLD);
            save(U1, "e2", "Solon 缓存优化", 9, T_OLD);
            save(U1, "e3", "Solon 缓存优化", 9, T_NEW);
            save(U1, "e4", "Solon 介绍", 10, T_NEW);

            List<String> keys = keysOf(data.search(U1, "Solon 缓存", 10));

            assertEquals(java.util.Arrays.asList("e3", "e2", "e1", "e4"), keys,
                    "score 主导 → importance → time；e4 重要度最高但得分低，必须垫底");
        }

        @Test
        @DisplayName("平方 IDF：命中稀有专有名词胜过命中多个高频词")
        public void rare_token_beats_many_common_tokens() {
            // 10 条噪声让“用户/缓存”成为高频词（df=11），solon 保持稀有（df=1）
            for (int i = 0; i < 10; i++) {
                save(U1, "noise" + i, "用户缓存记录" + i, 5, T_NEW);
            }
            save(U1, "common", "用户缓存优化笔记", 5, T_NEW);
            save(U1, "rare", "solon 深度实践", 5, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "用户 缓存 solon", 10);

            assertFalse(rs.isEmpty());
            assertEquals("rare", rs.get(0).getKey(),
                    "solon 的平方 IDF 应压倒“用户+缓存”两个高频词的叠加");
        }

        @Test
        @DisplayName("查询含库内不存在的词：不稀释相关命中，也不产生无关命中（求和式）")
        public void unknown_token_does_not_dilute_results() {
            save(U1, "solon", "Solon 框架", 5, T_NEW);
            save(U1, "redis", "Redis 缓存", 5, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "Solon kubernetes", 10);

            assertEquals(1, rs.size(), "未命中词不应稀释相关条目或引入无关结果");
            assertEquals("solon", rs.get(0).getKey());
        }

        @Test
        @DisplayName("查询词重复不影响结果（token 集合去重）")
        public void query_token_dedup_irrelevant() {
            save(U1, "k1", "Solon 缓存", 5, T_NEW);

            List<String> once = keysOf(data.search(U1, "Solon 缓存", 10));
            List<String> thrice = keysOf(data.search(U1, "Solon Solon Solon 缓存 缓存", 10));

            assertEquals(once, thrice, "查询词重复应被去重，结果一致");
        }

        @Test
        @DisplayName("查询词序无关")
        public void query_word_order_irrelevant() {
            save(U1, "a", "Solon 缓存优化实践", 1, T_NEW);
            save(U1, "b", "Solon 入门简介", 10, T_NEW);

            List<String> forward = keysOf(data.search(U1, "Solon 缓存", 10));
            List<String> reversed = keysOf(data.search(U1, "缓存 Solon", 10));

            assertEquals(forward, reversed, "查询词顺序不应影响结果");
        }
    }

    // ==================== 搜索：边界与隔离 ====================

    @Nested
    @DisplayName("搜索-边界与隔离")
    class SearchBoundary {

        @Test
        @DisplayName("null/空白 query 返回空列表")
        public void blank_query_returns_empty() {
            save(U1, "k1", "Solon framework", 5, T_NEW);

            assertTrue(data.search(U1, null, 10).isEmpty());
            assertTrue(data.search(U1, "", 10).isEmpty());
            assertTrue(data.search(U1, "   ", 10).isEmpty());
        }

        @Test
        @DisplayName("未知 userId 返回空列表")
        public void unknown_user_returns_empty() {
            save(U1, "k1", "Solon framework", 5, T_NEW);

            assertTrue(data.search("nobody", "Solon", 10).isEmpty());
        }

        @Test
        @DisplayName("用户间检索完全隔离")
        public void users_are_isolated() {
            save(U1, "k1", "Solon framework", 5, T_NEW);
            save(U2, "k2", "Solon framework", 5, T_NEW);

            List<MemorySearchResult> rs1 = data.search(U1, "Solon", 10);
            List<MemorySearchResult> rs2 = data.search(U2, "Solon", 10);

            assertEquals(1, rs1.size());
            assertEquals("k1", rs1.get(0).getKey());
            assertEquals(1, rs2.size());
            assertEquals("k2", rs2.get(0).getKey());
        }

        @Test
        @DisplayName("limit 截断生效，limit=0 返回空")
        public void limit_is_applied() {
            for (int i = 0; i < 6; i++) {
                save(U1, "k" + i, "Solon 条目 " + i, 5, T_NEW);
            }

            assertEquals(3, data.search(U1, "Solon", 3).size());
            assertEquals(6, data.search(U1, "Solon", 100).size());
            assertTrue(data.search(U1, "Solon", 0).isEmpty());
        }

        @Test
        @DisplayName("搜索结果携带完整元数据")
        public void result_carries_metadata() {
            save(U1, "k1", "Solon framework", 7, T_MID, -1, SCOPE_WS);

            MemorySearchResult r = data.search(U1, "Solon", 10).get(0);

            assertEquals("k1", r.getKey());
            assertEquals("Solon framework", r.getContent());
            assertEquals(7, r.getImportance(), 0.001);
            assertEquals(T_MID, r.getTime());
            assertEquals(SCOPE_WS, r.getScope());
        }

        @Test
        @DisplayName("removeIndex 后不再被检索到")
        public void remove_index_excludes_entry() {
            save(U1, "k1", "Solon framework", 5, T_NEW);
            assertEquals(1, data.search(U1, "Solon", 10).size());

            data.removeIndex(U1, "k1");

            assertTrue(data.search(U1, "Solon", 10).isEmpty());
        }

        @Test
        @DisplayName("updateIndex 覆盖同 key 且旧内容不再命中")
        public void update_index_overwrites_same_key() {
            save(U1, "k1", "Solon framework", 5, T_NEW);
            data.updateIndex(U1, "k1", "Redis cache", 5, T_NEW, SCOPE_USER);

            assertTrue(data.search(U1, "Solon", 10).isEmpty(), "旧内容不应再命中");
            assertEquals(1, data.search(U1, "Redis", 10).size());
        }

        @Test
        @DisplayName("多主题语料按主题精确检索隔离")
        public void multi_topic_corpus_isolated_search() {
            save(U1, "t1", "Solon 框架开发", 5, T_NEW);
            save(U1, "t2", "Redis 缓存配置", 5, T_NEW);
            save(U1, "t3", "Java 并发编程", 5, T_NEW);
            save(U1, "l1", "周末去爬山", 5, T_NEW);
            save(U1, "l2", "喜欢喝美式咖啡", 5, T_NEW);
            save(U1, "l3", "坚持晨跑锻炼", 5, T_NEW);

            assertEquals("t1", data.search(U1, "Solon", 10).get(0).getKey());
            assertEquals("t2", data.search(U1, "缓存", 10).get(0).getKey());
            assertEquals("l2", data.search(U1, "咖啡", 10).get(0).getKey());
            assertEquals("l3", data.search(U1, "晨跑", 10).get(0).getKey());
        }

        @Test
        @DisplayName("跨作用域不同 key 的搜索结果合并")
        public void cross_scope_search_merged() {
            save(U1, "k1", "Solon 笔记", 5, T_NEW, -1, SCOPE_USER);
            save(U1, "k2", "Solon 部署", 5, T_NEW, -1, SCOPE_WS);

            List<MemorySearchResult> rs = data.search(U1, "Solon", 10);

            assertEquals(2, rs.size(), "不同 key 跨作用域应合并返回");
            Set<String> keys = rs.stream()
                    .map(MemorySearchResult::getKey)
                    .collect(Collectors.toSet());
            assertTrue(keys.containsAll(java.util.Arrays.asList("k1", "k2")),
                    "user 域与 workspace 域的条目都应被检索到");
        }

        @Test
        @DisplayName("百条规模：limit 截断、高分优先、结果稳定")
        public void hundred_entries_scale_stability() {
            for (int i = 0; i < 100; i++) {
                save(U1, "k" + i, "Solon 条目 " + i, i % 10, T_MID);
            }
            save(U1, "special", "kubernetes 部署手册", 9, T_NEW);

            List<MemorySearchResult> top10 = data.search(U1, "Solon", 10);
            assertEquals(10, top10.size(), "limit 截断生效");
            assertEquals(9, top10.get(0).getImportance(), 0.001, "最高分条目应排第一");
            assertEquals(9, top10.get(top10.size() - 1).getImportance(), 0.001,
                    "imp=9 的条目共 10 条，应恰好占满前 10");

            // 稳定性：同分条目顺序允许依赖实现，但两次查询的 key 集合必须一致
            Set<String> first = top10.stream()
                    .map(MemorySearchResult::getKey).collect(Collectors.toSet());
            Set<String> second = data.search(U1, "Solon", 10).stream()
                    .map(MemorySearchResult::getKey).collect(Collectors.toSet());
            assertEquals(first, second, "重复查询结果应一致");

            assertEquals(100, data.search(U1, "Solon", 1000).size(),
                    "special 不含 Solon，全量应为 100 条");

            List<MemorySearchResult> specialRs = data.search(U1, "kubernetes", 10);
            assertEquals(1, specialRs.size());
            assertEquals("special", specialRs.get(0).getKey());
        }
    }

    // ==================== 搜索：多语言整句矩阵 ====================

    @Nested
    @DisplayName("搜索-多语言整句矩阵")
    class MultiLangSentence {

        @Test
        @DisplayName("英文整句查询：高频虚词不盖过稀有专有名词")
        public void english_sentence_query() {
            // 句式噪声：the/is/what/we 等虚词跨条高频出现（df 大），IDF 被压制
            String[] noises = {
                    "The user's schedule is what we track",
                    "The user's taste is what we prefer",
                    "The user's sleep routine is what we monitor",
                    "The user's reading habit is what we record"
            };
            for (int i = 0; i < noises.length; i++) {
                save(U1, "noise" + i, noises[i], 7, T_NEW);
            }
            save(U1, "target", "The user's tech stack is Solon with Java", 7, T_NEW);

            List<MemorySearchResult> rs = data.search(U1, "what is the user's Solon framework version", 10);

            assertFalse(rs.isEmpty());
            assertEquals("target", rs.get(0).getKey(),
                    "英文整句里高频虚词（the/is/what）命中再多，也不应盖过命中稀有专有名词的目标");
        }

        @Test
        @DisplayName("日文整句查询：假名噪声被 IDF 压制")
        public void japanese_sentence_query() {
            // 片假名/平假名不在 CJK 统一表意区，按连续字母段整体成词；日文汉字走 bi-gram。
            String[] noises = {
                    "ユーザーのスケジュールは重要です",
                    "ユーザーの好みは重要です",
                    "ユーザーの睡眠時間は重要です",
                    "ユーザーの読書習慣は重要です"
            };
            for (int i = 0; i < noises.length; i++) {
                save(U1, "noise" + i, noises[i], 7, T_NEW);
            }
            save(U1, "target", "ユーザーの技術スタックはSolonとJavaです", 7, T_NEW);

            List<MemorySearchResult> rs = data.search(
                    U1, "ユーザーのSolonフレームワークバージョンは何ですか", 10);

            assertFalse(rs.isEmpty());
            assertEquals("target", rs.get(0).getKey(),
                    "日文整句里高频句式词（ユーザー/です）不应盖过稀有专有名词 Solon");
        }

        @Test
        @DisplayName("韩文整句查询：谚文整体成词，稀有词主导排序")
        public void korean_sentence_query() {
            // 谚文不在 CJK 统一表意区，连续谚文整体成词（等价英文整词）。
            String[] noises = {
                    "사용자의 일정은 무엇입니까",
                    "사용자의 취향은 무엇입니까",
                    "사용자의 수면시간은 중요합니다",
                    "사용자의 독서습관은 중요합니다"
            };
            for (int i = 0; i < noises.length; i++) {
                save(U1, "noise" + i, noises[i], 7, T_NEW);
            }
            save(U1, "target", "사용자의 기술스택은 Solon과 Java입니다", 7, T_NEW);

            List<MemorySearchResult> rs = data.search(
                    U1, "사용자의 Solon 프레임워크 버전은 무엇입니까", 10);

            assertFalse(rs.isEmpty());
            assertEquals("target", rs.get(0).getKey(),
                    "韩文整句里高频句式词（사용자의/무엇입니까）不应盖过稀有专有名词 Solon");
        }
    }

    // ==================== 搜索：前缀与操作符 ====================

    @Nested
    @DisplayName("搜索-前缀与操作符")
    class PrefixAndOperators {

        @Test
        @DisplayName("英文前缀查询经子串兜底召回完整词")
        public void english_prefix_substring_recall() {
            save(U1, "k1", "Solon framework 使用笔记", 5, T_NEW);

            assertFalse(data.search(U1, "sol", 10).isEmpty(), "前缀 sol 应经子串兜底召回 solon");
            assertFalse(data.search(U1, "solo", 10).isEmpty(), "前缀 solo 应经子串兜底召回 solon");
            assertFalse(data.search(U1, "frame", 10).isEmpty(), "前缀 frame 应经子串兜底召回 framework");
            assertEquals("k1", data.search(U1, "sol", 10).get(0).getKey());
        }

        @Test
        @DisplayName("Lucene 特殊字符（+ ^ 引号）在 MD 方案中只是分隔符")
        public void lucene_operators_are_plain_delimiters() {
            save(U1, "k1", "Solon framework", 5, T_NEW);

            // Lucene QueryParser 中 "^" 是加权语法、"+" 是强制运算符、引号是短语语法，
            // 解析含这些字符的输入各有特殊行为。MD 方案无查询语法，一律按分隔符处理。
            assertEquals(1, data.search(U1, "Solon+framework", 10).size());
            assertEquals(1, data.search(U1, "Solon^framework", 10).size());
            assertEquals(1, data.search(U1, "\"Solon framework\"", 10).size());
            assertEquals(1, data.search(U1, "Solon framework", 10).size());

            // 库外词不产生无关命中（求和式不稀释）
            assertEquals(1, data.search(U1, "Solon+kubernetes", 10).size());
        }

        @Test
        @DisplayName("字段语法冒号查询安全（对比 Lucene 会抛 ParseException）")
        public void field_syntax_colon_safe() {
            save(U1, "k1", "Solon framework 版本", 5, T_NEW);

            // Lucene 中 "field:value" 会被解析为字段查询，冒号后接内容再空格可能抛 ParseException；
            // MD 方案冒号只是分隔符，天然免疫。
            assertEquals(1, data.search(U1, "content:Solon", 10).size());
        }
    }

    // ==================== 搜索：并发安全 ====================

    @Nested
    @DisplayName("搜索-并发安全")
    class Concurrency {

        @Test
        @DisplayName("并发搜索：多线程下排序结果与串行完全一致且无异常")
        public void concurrent_search_consistent() throws Exception {
            for (int i = 0; i < 20; i++) {
                save(U1, "k" + i, "Solon 缓存条目 " + i, i % 10, T_MID);
            }

            int threads = 8, rounds = 50;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<List<String>> results = Collections.synchronizedList(new ArrayList<>());
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int r = 0; r < rounds; r++) {
                            results.add(keysOf(data.search(U1, "Solon 缓存", 10)));
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                });
            }

            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发搜索应在限时内完成");
            assertTrue(errors.isEmpty(), "并发搜索不得抛异常: " + errors);

            List<String> expected = keysOf(data.search(U1, "Solon 缓存", 10));
            assertEquals(threads * rounds, results.size());
            for (List<String> r : results) {
                assertEquals(expected, r, "并发下的搜索结果必须与串行一致");
            }
        }

        @Test
        @DisplayName("并发读写混合：不抛异常，写入最终可读")
        public void concurrent_write_and_search_no_crash() throws Exception {
            int writerCount = 3, readerCount = 3;
            ExecutorService pool = Executors.newFixedThreadPool(writerCount + readerCount);
            CountDownLatch start = new CountDownLatch(1);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            for (int w = 0; w < writerCount; w++) {
                final int wi = w;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 200; i++) {
                            String key = "w" + wi + "_" + (i % 20);
                            save(U1, key, "Solon 并发写入 " + wi + " " + i, i % 10 + 1, T_MID);
                            if (i % 50 == 0) {
                                data.removeIndex(U1, key);
                            }
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                });
            }
            for (int r = 0; r < readerCount; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 300; i++) {
                            data.search(U1, "Solon 并发", 10);
                            data.get(U1, "w0_" + (i % 20));
                            data.listAll(U1, 10);
                            data.getHotMemories(U1, 10);
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                });
            }

            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发读写应在限时内完成");
            assertTrue(errors.isEmpty(), "并发读写不得抛异常: " + errors);

            // 最终一致性：写线程落盘后，条目可读
            assertNotNull(data.get(U1, "w0_3"), "写线程的条目最终应可读");
        }

        @Test
        @DisplayName("并发首搜触发 tokens 懒加载（DCL）线程安全")
        public void concurrent_first_search_token_lazy_init() throws Exception {
            // updateIndex 创建的 IndexEntry.tokens 为 null，首次 search 触发双重检查锁初始化
            for (int i = 0; i < 10; i++) {
                save(U1, "k" + i, "Solon 懒加载 " + i, 5, T_MID);
            }

            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<List<String>> results = Collections.synchronizedList(new ArrayList<>());
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int r = 0; r < 20; r++) {
                            results.add(keysOf(data.search(U1, "Solon 懒加载", 10)));
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                });
            }

            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "懒加载并发应在限时内完成");
            assertTrue(errors.isEmpty(), "tokens 懒加载不得抛异常: " + errors);
            assertEquals(10, results.get(0).size(), "懒加载后全部条目应被召回");
        }
    }

    // ==================== 热记忆与列举 ====================

    @Nested
    @DisplayName("热记忆与列举")
    class HotAndList {

        @Test
        @DisplayName("getHotMemories 只取重要度>=5，按重要度与时间倒序")
        public void hot_memories_filter_and_order() {
            save(U1, "trivial", "琐碎条目", 2, T_NEW);
            save(U1, "mid", "中等条目", 5, T_OLD);
            save(U1, "midNewer", "中等条目较新", 5, T_NEW);
            save(U1, "core", "核心条目", 9, T_OLD);

            List<String> keys = keysOf(data.getHotMemories(U1, 10));

            assertEquals(java.util.Arrays.asList("core", "midNewer", "mid"), keys,
                    "应过滤低分并按重要度、时间倒序");
        }

        @Test
        @DisplayName("listAll 包含低重要度条目")
        public void list_all_includes_low_importance() {
            save(U1, "trivial", "琐碎条目", 2, T_NEW);
            save(U1, "core", "核心条目", 9, T_NEW);

            List<String> keys = keysOf(data.listAll(U1, 10));

            assertEquals(2, keys.size());
            assertTrue(keys.contains("trivial"), "listAll 不应过滤低分条目");
            assertEquals("core", keys.get(0), "应按重要度降序");
        }

        @Test
        @DisplayName("listAll / getHotMemories 的 limit 生效，未知用户返回空")
        public void list_limit_and_unknown_user() {
            for (int i = 0; i < 5; i++) {
                save(U1, "k" + i, "条目 " + i, 6, T_NEW);
            }

            assertEquals(2, data.listAll(U1, 2).size());
            assertEquals(2, data.getHotMemories(U1, 2).size());
            assertTrue(data.listAll("nobody", 10).isEmpty());
            assertTrue(data.getHotMemories("nobody", 10).isEmpty());
        }

        @Test
        @DisplayName("keys 列出全部 key，未知用户返回空集")
        public void keys_listing() {
            save(U1, "k1", "条目一", 5, T_NEW);
            save(U1, "k2", "条目二", 5, T_NEW);

            Set<String> keys = data.keys(U1);

            assertEquals(2, keys.size());
            assertTrue(keys.containsAll(java.util.Arrays.asList("k1", "k2")));
            assertTrue(data.keys("nobody").isEmpty());
        }
    }

    // ==================== 存取往返 ====================

    @Nested
    @DisplayName("存取往返")
    class StoreAndGet {

        @Test
        @DisplayName("put/get 往返保持内容、时间、重要度、作用域")
        public void put_get_round_trip() {
            save(U1, "k1", "用户偏好 Solon", 7, T_MID, -1, SCOPE_USER);

            ONode got = ONode.ofJson(data.get(U1, "k1"));

            assertEquals("用户偏好 Solon", got.get("content").getString());
            assertEquals(T_MID, got.get("time").getString());
            assertEquals(7, got.get("importance").getInt());
            assertEquals(SCOPE_USER, got.get("scope").getString());
        }

        @Test
        @DisplayName("get 不存在的 key 返回 null")
        public void get_missing_returns_null() {
            assertNull(data.get(U1, "absent"));
        }

        @Test
        @DisplayName("多行内容与 YAML 特殊字符可安全往返")
        public void special_content_round_trip() {
            String content = "第一行: 含冒号\n第二行 含 \"引号\" 与 \\ 反斜杠\n第三行";
            save(U1, "k1", content, 5, T_NEW);

            ONode got = ONode.ofJson(data.get(U1, "k1"));

            assertEquals(content.trim(), got.get("content").getString().trim());
        }

        @Test
        @DisplayName("同 key 重复 put 覆盖为最新值")
        public void put_same_key_overwrites() {
            save(U1, "k1", "旧内容", 5, T_OLD);
            save(U1, "k1", "新内容", 8, T_NEW);

            ONode got = ONode.ofJson(data.get(U1, "k1"));

            assertEquals("新内容", got.get("content").getString());
            assertEquals(8, got.get("importance").getInt());
        }

        @Test
        @DisplayName("remove 同时清除文件、缓存与索引")
        public void remove_clears_all_layers() {
            save(U1, "k1", "Solon framework", 5, T_NEW);
            assertTrue(Files.exists(userDir.resolve("u1__k1.md")));

            data.remove(U1, "k1");

            assertNull(data.get(U1, "k1"), "缓存与磁盘应已清除");
            assertTrue(data.search(U1, "Solon", 10).isEmpty(), "索引应已清除");
            assertFalse(Files.exists(userDir.resolve("u1__k1.md")), "MD 文件应已删除");
        }

        @Test
        @DisplayName("空 userId 直接抛出参数异常")
        public void empty_user_id_rejected() {
            assertThrows(IllegalArgumentException.class, () -> data.get("", "k1"));
            assertThrows(IllegalArgumentException.class, () -> data.get(null, "k1"));
        }

        @Test
        @DisplayName("非法 JSON 的 put 被吞掉且不产生脏条目")
        public void invalid_json_put_is_swallowed() {
            data.put(U1, "bad", "not-a-json", -1, SCOPE_USER);

            assertNull(data.get(U1, "bad"), "非法输入不应产生可读条目");
        }
    }

    // ==================== 多作用域 ====================

    @Nested
    @DisplayName("多作用域")
    class MultiScope {

        @Test
        @DisplayName("同 key 跨作用域并存，get 返回高优先级作用域")
        public void higher_scope_wins_on_get() {
            save(U1, "k1", "user 域内容", 5, T_NEW, -1, SCOPE_USER);
            save(U1, "k1", "workspace 域内容", 5, T_NEW, -1, SCOPE_WS);

            ONode got = ONode.ofJson(data.get(U1, "k1"));

            // scopeDirMap 迭代序 user → workspace，后者覆盖前者
            assertEquals("workspace 域内容", got.get("content").getString());
            assertEquals(SCOPE_WS, got.get("scope").getString());
            assertTrue(Files.exists(userDir.resolve("u1__k1.md")), "低优先级文件仍应保留");
        }

        @Test
        @DisplayName("remove 清除所有作用域下的同 key 文件")
        public void remove_clears_every_scope() {
            save(U1, "k1", "user 域内容", 5, T_NEW, -1, SCOPE_USER);
            save(U1, "k1", "workspace 域内容", 5, T_NEW, -1, SCOPE_WS);

            data.remove(U1, "k1");

            assertFalse(Files.exists(userDir.resolve("u1__k1.md")));
            assertFalse(Files.exists(wsDir.resolve("u1__k1.md")));
            assertNull(data.get(U1, "k1"));
        }
    }

    // ==================== 持久化与加载 ====================

    @Nested
    @DisplayName("持久化与加载")
    class Persistence {

        @Test
        @DisplayName("重启后从 MD 文件重建缓存与搜索索引")
        public void reload_rebuilds_cache_and_index() throws IOException {
            save(U1, "k1", "用户偏好使用Solon框架", 7, T_MID);
            data.close();

            data = newData();

            assertNotNull(data.get(U1, "k1"), "重启后应可读取");
            List<MemorySearchResult> rs = data.search(U1, "Solon", 10);
            assertEquals(1, rs.size(), "重启后搜索索引不应丢失");
            assertEquals("k1", rs.get(0).getKey());
            assertEquals(7, rs.get(0).getImportance(), 0.001);
        }

        @Test
        @DisplayName("userId 含双下划线时仍能正确还原 userId/key")
        public void store_key_with_double_underscore() throws IOException {
            String trickyUser = "tenant__a";
            save(trickyUser, "k1", "Solon framework", 5, T_NEW);
            data.close();

            data = newData();

            assertTrue(data.keys(trickyUser).contains("k1"), "应按 lastIndexOf 正确拆分");
            assertEquals(1, data.search(trickyUser, "Solon", 10).size());
        }

        @Test
        @DisplayName("旧格式文件（无 name 字段）可兼容加载并进入索引")
        public void legacy_file_without_name_field() throws IOException {
            Files.createDirectories(userDir);
            String md = "---\n"
                    + "time: \"" + T_MID + "\"\n"
                    + "importance: 6\n"
                    + "ttl: -1\n"
                    + "stored_at: \"" + T_MID + "\"\n"
                    + "---\n\n"
                    + "旧格式 Solon 记录\n";
            Files.write(userDir.resolve("u1__legacy.md"), md.getBytes(StandardCharsets.UTF_8));
            data.close();

            data = newData();

            assertTrue(data.keys(U1).contains("legacy"), "应从文件名还原 storeKey");
            assertEquals(1, data.search(U1, "Solon", 10).size());
        }

        @Test
        @DisplayName("正文为空的文件被跳过，不进入索引")
        public void empty_content_file_is_skipped() throws IOException {
            Files.createDirectories(userDir);
            String md = "---\n"
                    + "name: \"u1__blank\"\n"
                    + "time: \"" + T_MID + "\"\n"
                    + "importance: 6\n"
                    + "ttl: -1\n"
                    + "stored_at: \"" + T_MID + "\"\n"
                    + "---\n\n";
            Files.write(userDir.resolve("u1__blank.md"), md.getBytes(StandardCharsets.UTF_8));
            data.close();

            data = newData();

            assertFalse(data.keys(U1).contains("blank"), "空内容条目不应进入索引");
        }

        @Test
        @DisplayName("非 md 文件与残留 tmp 文件被忽略/清理")
        public void non_md_and_tmp_files_handled() throws IOException {
            Files.createDirectories(userDir);
            Files.write(userDir.resolve("note.txt"), "ignore me".getBytes(StandardCharsets.UTF_8));
            Files.write(userDir.resolve("u1__x.md.tmp"), "leftover".getBytes(StandardCharsets.UTF_8));
            data.close();

            data = newData();

            assertTrue(data.keys(U1).isEmpty(), "非 md 文件不应被加载");
            assertFalse(Files.exists(userDir.resolve("u1__x.md.tmp")), "残留 tmp 应被清理");
        }
    }

    // ==================== TTL 过期 ====================

    @Nested
    @DisplayName("TTL 过期")
    class Ttl {

        @Test
        @DisplayName("ttl<0 表示永不过期")
        public void negative_ttl_never_expires() {
            save(U1, "k1", "Solon framework", 5, T_NEW, -1, SCOPE_USER);

            assertNotNull(data.get(U1, "k1"));
            assertEquals(1, data.search(U1, "Solon", 10).size());
        }

        @Test
        @DisplayName("过期条目：get 返回 null，且不出现在 search/listAll/hot 中")
        public void expired_entry_is_invisible() throws Exception {
            save(U1, "fresh", "Solon 新鲜条目", 6, T_NEW, -1, SCOPE_USER);
            save(U1, "stale", "Solon 过期条目", 6, T_NEW, 0, SCOPE_USER);

            // ttl=0 表示 1 秒后即视为过期（isExpired 以秒粒度比较）
            Thread.sleep(1200);

            assertNull(data.get(U1, "stale"), "过期条目 get 应返回 null");
            assertEquals(java.util.Collections.singletonList("fresh"),
                    keysOf(data.search(U1, "Solon", 10)), "过期条目不应参与检索");
            assertEquals(java.util.Collections.singletonList("fresh"),
                    keysOf(data.listAll(U1, 10)), "过期条目不应被列举");
            assertEquals(java.util.Collections.singletonList("fresh"),
                    keysOf(data.getHotMemories(U1, 10)), "过期条目不应出现在热记忆");
        }

        @Test
        @DisplayName("cleanupExpired 清除过期条目及其文件，保留有效条目")
        public void cleanup_expired_removes_files() throws Exception {
            save(U1, "fresh", "有效条目", 6, T_NEW, -1, SCOPE_USER);
            save(U1, "stale", "过期条目", 6, T_NEW, 0, SCOPE_USER);
            Thread.sleep(1200);

            data.cleanupExpired();

            assertFalse(Files.exists(userDir.resolve("u1__stale.md")), "过期文件应被删除");
            assertTrue(Files.exists(userDir.resolve("u1__fresh.md")), "有效文件应保留");
            assertTrue(data.keys(U1).contains("fresh"));
            assertFalse(data.keys(U1).contains("stale"));
        }

        @Test
        @DisplayName("启动加载时清理已过期文件")
        public void expired_file_cleaned_on_load() throws Exception {
            // stored_at 由 put() 内部取当前时针，无法从外部注入历史时间，
            // 故直接写 MD 文件构造“存储于过去且已超 ttl”的场景。
            Files.createDirectories(userDir);
            String md = "---\n"
                    + "name: \"u1__stale\"\n"
                    + "time: \"" + T_OLD + "\"\n"
                    + "importance: 6\n"
                    + "ttl: 60\n"
                    + "stored_at: \"" + T_OLD + "\"\n"
                    + "---\n\n"
                    + "过期条目\n";
            Files.write(userDir.resolve("u1__stale.md"), md.getBytes(StandardCharsets.UTF_8));
            data.close();

            data = newData();

            assertTrue(data.keys(U1).isEmpty(), "过期条目不应被加载");
            assertFalse(Files.exists(userDir.resolve("u1__stale.md")), "过期文件应在加载时删除");
        }
    }

    // ==================== 生命周期 ====================

    @Nested
    @DisplayName("生命周期")
    class Lifecycle {

        @Test
        @DisplayName("enableAutoCleanup 幂等，close 可重复调用")
        public void auto_cleanup_and_close_are_idempotent() {
            assertSame(data, data.enableAutoCleanup(3600));
            assertSame(data, data.enableAutoCleanup(3600));

            data.close();
            data.close();
        }

        @Test
        @DisplayName("构造时自动创建缺失的作用域目录")
        public void scope_dirs_are_created() {
            assertTrue(Files.isDirectory(userDir));
            assertTrue(Files.isDirectory(wsDir));
        }
    }

    // ==================== 辅助方法 ====================

    private MemoryMdData newData() {
        Map<String, Path> scopeDirMap = new LinkedHashMap<>();
        scopeDirMap.put(SCOPE_USER, userDir);
        scopeDirMap.put(SCOPE_WS, wsDir);
        return new MemoryMdData(scopeDirMap);
    }

    /** 写入并同步索引（对齐 MemoryTalent 的 put + updateIndex 调用约定） */
    private void save(String userId, String key, String content, int importance, String time) {
        save(userId, key, content, importance, time, -1, SCOPE_USER);
    }

    private void save(String userId, String key, String content, int importance, String time,
                      int ttl, String scope) {
        ONode node = new ONode();
        node.set("content", content);
        node.set("time", time);
        node.set("importance", importance);

        data.put(userId, key, node.toJson(), ttl, scope);
        data.updateIndex(userId, key, content, importance, time, scope);
    }

    private static List<String> keysOf(List<MemorySearchResult> rs) {
        return rs.stream().map(MemorySearchResult::getKey).collect(Collectors.toList());
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        List<Path> all = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.walk(root)) {
            all = s.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path p : all) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
            }
        }
    }
}
