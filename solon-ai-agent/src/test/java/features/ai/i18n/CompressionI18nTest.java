package features.ai.i18n;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.react.intercept.compress.CompressionUtil;
import org.noear.solon.ai.agent.react.intercept.compress.HierarchicalCompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.KeyInfoExtractionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.LLMCompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.VectorStoreCompressionStrategy;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.i18n.testkit.FrozenMessages;
import org.noear.solon.annotation.Param;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 上下文压缩国际化测试（不依赖真实 LLM）
 */
public class CompressionI18nTest {
    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]");

    // ---- 迁移前的原始中文文案（逐字） ----
    private static final String ZH_LLM_SYSTEM = "## 角色定义\n" +
            "你是一个高效的任务进度分析员。请简要总结 AI Agent 的执行历史片段。\n\n" +
            "## 总结要点\n" +
            "1. **操作回顾**：已尝试的主要操作（工具调用及其关键参数）。\n" +
            "2. **关键发现**：获取到的核心信息或结论。\n" +
            "3. **当前进度**：目前处于任务的哪个阶段，还剩什么未完成。\n" +
            "4. **信息保留**：必须保留所有文件路径、函数名和技术细节，这些是后续执行的关键上下文。\n\n" +
            "## 输出规范\n" +
            "- 要求：精炼、准确，不超过 300 字。\n" +
            "- 严禁包含：无关的客套话或自我介绍。\n" +
            "- 若无可总结内容，请回复：(无显著进度)";

    private static final String ZH_KEYINFO_SYSTEM = "## 角色定义\n" +
            "你是一个精密的信息审计专家。你的任务是从杂乱的对话历史中\"脱水\"，仅保留高价值的结构化信息。\n\n" +
            "## 提取维度\n" +
            "1. **业务参数**：用户提及的特定 ID、数值、时间、偏好或硬性约束。\n" +
            "2. **确定性事实**：通过工具调用已证实的真实状态或返回的关键结果。\n" +
            "3. **负面路径**：已验证为无效的尝试（防止 Agent 重复错误）。\n" +
            "4. **技术细节**：必须保留所有文件路径、函数名和技术标识，不可省略。\n\n" +
            "## 输出规范\n" +
            "- 必须以简洁的 **Markdown 列表** 形式输出。\n" +
            "- 严禁包含任何推测、解释或修饰性语句。\n" +
            "- 如果没有发现关键信息，请直接回复：(无关键增量)。";

    private static final String ZH_HIERARCHICAL_SYSTEM = "## 角色定义\n" +
            "你是一个专业的记忆管理专家，负责对 Agent 的执行历史进行层级化压缩。\n\n" +
            "## 处理逻辑\n" +
            "请将『旧的压缩内容』与『新增的过期历史记录』合并，生成精炼的『当前进度摘要』。\n\n" +
            "## 核心要求\n" +
            "1. **信息提取**：重点保留已确认的关键数据、当前的逻辑位置、以及已达成的阶段性结论。\n" +
            "2. **去重降噪**：移除重复的思考过程、已失效的尝试方案、以及无意义的中间状态。\n" +
            "3. **长度约束**：严格控制摘要长度，使用简洁的陈述句。\n" +
            "4. **路径保留**：必须保留所有文件路径、函数名和技术细节，这些是 Agent 后续执行的关键导航信息。\n\n" +
            "## 注意事项\n" +
            "直接输出摘要正文，不要包含\"好的\"、\"明白\"或\"根据您的要求\"等废话。";

    private static final String ZH_VECTOR_INSTRUCTION =
            "- 当你看到内容包含 '--- [历史细节已归档] ---' 标记时，说明部分执行细节已转入冷记忆。\n" +
            "- 如果后续任务需要早前的具体事实、ID 或详细数据，请调用 `recall_history` 工具找回。";

    private static final String ZH_VECTOR_NOTICE = "--- [历史细节已归档，必要时请使用 recall_history 工具回溯] ---";

    private static final String ZH_TRUNCATED = "\n... [内容过大已截断：单条消息超过上下文预算，省略中间部分，仅保留首尾。"
            + "如需完整内容，请用分页方式重新获取] ...\n";

    private static void assertNoChinese(String text, String what) {
        Assertions.assertNotNull(text, what);
        Assertions.assertFalse(CHINESE.matcher(text).find(), what + " contains chinese: " + text);
    }

    @Test
    public void testChineseUnchanged() {
        for (Locale zh : new Locale[]{null, Locale.CHINESE, Locale.CHINA}) {
            Assertions.assertEquals(ZH_LLM_SYSTEM, new LLMCompressionStrategy().getSystemInstruction(zh));
            Assertions.assertEquals(ZH_KEYINFO_SYSTEM, new KeyInfoExtractionStrategy().getSystemInstruction(zh));
            Assertions.assertEquals(ZH_HIERARCHICAL_SYSTEM, new HierarchicalCompressionStrategy().getSystemInstruction(zh));

            //资源只存正文，首尾换行由压缩策略补上（拼装结果见 RuntimeRenderGoldenTest）
            Assertions.assertEquals("### 待压缩历史片段", AgentI18n.get(zh, "compress.llm.user_prefix"));
            Assertions.assertEquals("### 任务指令\n请根据系统指令对上述执行过程进行语义总结：",
                    AgentI18n.get(zh, "compress.llm.user_suffix"));
            Assertions.assertEquals("--- [执行进度总结] ---", AgentI18n.get(zh, "compress.llm.summary_prefix"));
            Assertions.assertEquals("### 待处理历史片段", AgentI18n.get(zh, "compress.keyinfo.user_prefix"));
            Assertions.assertEquals("### 审计要求\n请根据系统指令，提取上述片段中的关键信息。",
                    AgentI18n.get(zh, "compress.keyinfo.user_suffix"));
            Assertions.assertEquals("--- [已确认的关键信息] ---", AgentI18n.get(zh, "compress.keyinfo.summary_prefix"));
            Assertions.assertEquals("--- [全局进度滚动摘要 (层级压缩)] ---", HierarchicalCompressionStrategy.getSummaryPrefix(zh));

            String userData = AgentI18n.fmt(zh, "compress.hierarchical.user_data",
                    "summary", AgentI18n.get(zh, "compress.hierarchical.no_summary"), "history", "H");
            Assertions.assertEquals("### 旧的摘要内容\n" + "（暂无）" + "\n\n" + "### 新增的过期历史记录\n" + "H" + "\n\n" +
                    "### 最终任务要求\n" + "请根据 System Message（系统指令）中的逻辑，输出更新后的『进度摘要』：", userData);

            Assertions.assertEquals(ZH_VECTOR_NOTICE, VectorStoreCompressionStrategy.getArchivedNotice(zh));
            Assertions.assertEquals(ZH_TRUNCATED, "\n" + AgentI18n.get(zh, "compress.interceptor.truncated") + "\n");
            Assertions.assertEquals("[内容已截断]", AgentI18n.get(zh, "compress.interceptor.truncated_short"));
        }

        VectorStoreCompressionStrategy vs = new VectorStoreCompressionStrategy(null);
        Assertions.assertEquals(ZH_VECTOR_INSTRUCTION, vs.getInstruction(Prompt.of("x")));
        Assertions.assertEquals("历史记忆回溯", vs.description(null));
    }

    @Test
    public void testEnglishHasNoChinese() {
        for (Locale en : new Locale[]{Locale.ENGLISH, Locale.US, Locale.JAPANESE}) {
            assertNoChinese(new LLMCompressionStrategy().getSystemInstruction(en), "llm.system");
            assertNoChinese(new KeyInfoExtractionStrategy().getSystemInstruction(en), "keyinfo.system");
            assertNoChinese(new HierarchicalCompressionStrategy().getSystemInstruction(en), "hierarchical.system");
            assertNoChinese(HierarchicalCompressionStrategy.getSummaryPrefix(en), "hierarchical.prefix");
            assertNoChinese(VectorStoreCompressionStrategy.getArchivedNotice(en), "vector.notice");
            assertNoChinese(AgentI18n.get(en, "compress.interceptor.truncated"), "interceptor.truncated");
            assertNoChinese(AgentI18n.get(en, "compress.interceptor.truncated_short"), "interceptor.truncated_short");

            // 指令中的“无增量”标记与识别逻辑来自同一 key
            Assertions.assertTrue(new LLMCompressionStrategy().getSystemInstruction(en)
                    .endsWith(FrozenMessages.of(Locale.ENGLISH).get("compress.llm.empty_marker")));
            Assertions.assertTrue(new KeyInfoExtractionStrategy().getSystemInstruction(en)
                    .contains(FrozenMessages.of(Locale.ENGLISH).get("compress.keyinfo.empty_marker")));
        }

        VectorStoreCompressionStrategy vs = new VectorStoreCompressionStrategy(null);
        Prompt enPrompt = Prompt.of("x").locale(Locale.ENGLISH);
        assertNoChinese(vs.getInstruction(enPrompt), "vector.instruction");
        assertNoChinese(vs.description(enPrompt), "vector.description");
        Assertions.assertTrue(vs.getInstruction(enPrompt).contains("--- [History details archived] ---"));
    }

    @Test
    public void testCustomInstructionWins() {
        Assertions.assertEquals("X", new LLMCompressionStrategy().systemInstruction("X").getSystemInstruction(Locale.ENGLISH));
        Assertions.assertEquals("Y", new KeyInfoExtractionStrategy().systemInstruction("Y").getSystemInstruction(Locale.ENGLISH));
        Assertions.assertEquals("Z", new HierarchicalCompressionStrategy().systemInstruction("Z").getSystemInstruction(Locale.ENGLISH));
    }

    @Test
    public void testEmptySummaryAllLanguages() {
        for (Locale l : new Locale[]{null, Locale.CHINESE, Locale.ENGLISH, Locale.JAPANESE}) {
            Assertions.assertTrue(CompressionUtil.isEmptySummary("(无显著进度)", l));
            Assertions.assertTrue(CompressionUtil.isEmptySummary("(无关键增量)", l));
            Assertions.assertTrue(CompressionUtil.isEmptySummary(" (No significant progress) \n", l));
            Assertions.assertTrue(CompressionUtil.isEmptySummary("(No key delta)", l));
            Assertions.assertTrue(CompressionUtil.isEmptySummary("", l));
            Assertions.assertFalse(CompressionUtil.isEmptySummary("Read file a.txt", l));
            Assertions.assertFalse(CompressionUtil.isEmptySummary("读取了 a.txt (无显著进度)", l));
        }
        // 旧签名（默认语言）
        Assertions.assertTrue(CompressionUtil.isEmptySummary("(无显著进度)"));
        Assertions.assertTrue(CompressionUtil.isEmptySummary("(无关键增量)"));
        Assertions.assertTrue(CompressionUtil.isEmptySummary("(No significant progress)"));
        Assertions.assertTrue(CompressionUtil.isEmptySummary("(No key delta)"));
        Assertions.assertTrue(CompressionUtil.isEmptySummary(null));
    }

    @Test
    public void testSummaryPrefixAndArchivedNoticeDetection() {
        String zhSummary = HierarchicalCompressionStrategy.getSummaryPrefix(Locale.CHINESE) + "\nA";
        String enSummary = HierarchicalCompressionStrategy.getSummaryPrefix(Locale.ENGLISH) + "\nB";
        Assertions.assertTrue(HierarchicalCompressionStrategy.isSummaryContent(zhSummary));
        Assertions.assertTrue(HierarchicalCompressionStrategy.isSummaryContent(enSummary));
        // 混合：先中文后英文
        Assertions.assertTrue(HierarchicalCompressionStrategy.isSummaryContent(zhSummary + "\n\n---\n\n" + enSummary));
        Assertions.assertFalse(HierarchicalCompressionStrategy.isSummaryContent("hello"));
        Assertions.assertFalse(HierarchicalCompressionStrategy.isSummaryContent(null));

        String zhNotice = VectorStoreCompressionStrategy.getArchivedNotice(Locale.CHINESE);
        String enNotice = VectorStoreCompressionStrategy.getArchivedNotice(Locale.ENGLISH);
        Assertions.assertTrue(VectorStoreCompressionStrategy.isArchivedNotice(zhNotice));
        Assertions.assertTrue(VectorStoreCompressionStrategy.isArchivedNotice(enNotice));
        Assertions.assertTrue(VectorStoreCompressionStrategy.isArchivedNotice(zhNotice + "\n\n---\n\n" + enNotice));
        // 指令中提到的标记（来自同一 key）也能被识别
        Assertions.assertTrue(VectorStoreCompressionStrategy.isArchivedNotice("--- [历史细节已归档] ---"));
        Assertions.assertTrue(VectorStoreCompressionStrategy.isArchivedNotice("--- [History details archived] ---"));
        Assertions.assertFalse(VectorStoreCompressionStrategy.isArchivedNotice("--- [执行进度总结] ---"));
        Assertions.assertFalse(VectorStoreCompressionStrategy.isArchivedNotice(null));
    }

    @Test
    public void testFormatMessage() {
        ToolCall tc = new ToolCall("0", "c1", "read", "{\"path\":\"a.txt\"}",
                Collections.singletonMap("path", "a.txt"));
        AssistantMessage am = new AssistantMessage("", "", Collections.singletonList(tc), null);
        String zh = CompressionUtil.formatMessageForCompression(am);
        Assertions.assertEquals("[Action]: 调用工具 read，参数: {\"path\":\"a.txt\"}", zh);
        String en = CompressionUtil.formatMessageForCompression(am, 100, Locale.ENGLISH);
        Assertions.assertEquals("[Action]: Call tool read, arguments: {\"path\":\"a.txt\"}", en);

        ChatMessage tm = ChatMessage.ofTool("ok {0}", "read", "c1");
        Assertions.assertEquals("[Observation]: 得到结果 ok {0}", CompressionUtil.formatMessageForCompression(tm));
        Assertions.assertEquals("[Observation]: Got result ok {0}",
                CompressionUtil.formatMessageForCompression(tm, 100, Locale.ENGLISH));

        java.util.List<ChatMessage> list = new ArrayList<>();
        list.add(am);
        list.add(tm);
        assertNoChinese(CompressionUtil.formatMessages(list, 100, Locale.ENGLISH), "formatMessages(en)");
    }

    @Test
    public void testVectorTools() throws Throwable {
        VectorStoreCompressionStrategy zhVs = new VectorStoreCompressionStrategy(null);
        Collection<FunctionTool> zhTools = zhVs.getTools(Prompt.of("x"));
        Assertions.assertEquals(new ArrayList<>(zhVs.getToolAry()), new ArrayList<>(zhTools));
        FunctionTool zhTool = zhTools.iterator().next();
        Assertions.assertEquals("recall_history", zhTool.name());
        Assertions.assertEquals("回溯本会话中早前已归档的长记忆、历史细节或事实", zhTool.description());
        Assertions.assertTrue(zhTool.inputSchema().contains("检索关键词或核心短语"));

        // 代码构建的中文工具与原 @ToolMapping 定义的 schema 完全一致
        FunctionTool legacy = new MethodToolProvider(new LegacyRecallTool()).getTools().iterator().next();
        Assertions.assertEquals(legacy.name(), zhTool.name());
        Assertions.assertEquals(legacy.description(), zhTool.description());
        Assertions.assertEquals(legacy.inputSchema(), zhTool.inputSchema());

        // 同一个共享实例：语言只走请求
        VectorStoreCompressionStrategy enVs = zhVs;
        Collection<FunctionTool> enTools = enVs.getTools(Prompt.of("x").locale(Locale.ENGLISH));
        Assertions.assertEquals(1, enTools.size());
        FunctionTool enTool = enTools.iterator().next();
        Assertions.assertEquals("recall_history", enTool.name());
        assertNoChinese(enTool.description(), "tool.description");
        assertNoChinese(enTool.inputSchema(), "tool.inputSchema");
        Assertions.assertTrue(enTool.inputSchema().contains("\"query\""));
        Assertions.assertTrue(enTool.inputSchema().contains("\"limit\""));
        Assertions.assertSame(enTools, enVs.getTools(Prompt.of("y").locale(Locale.ENGLISH)));

        // 执行委托给原工具（空 query 直接返回提示，不访问向量库）
        Assertions.assertEquals("请提供具体的关键词以进行历史回溯。", zhVs.recall("", 3, "s1"));
        Assertions.assertEquals(FrozenMessages.of(Locale.ENGLISH).get("compress.vector.recall.empty_query"), enVs.recall(Locale.ENGLISH, "", 3, "s1"));
        Assertions.assertEquals("[系统通知] 当前会话标识异常，无法进行历史回溯。请基于当前已知对话继续执行。",
                zhVs.recall("q", 3, "bad id"));
        assertNoChinese(enVs.recall(Locale.ENGLISH, "q", 3, "bad id"), "recall.invalid_session");
    }

    @Test
    public void testVectorToolResultFollowsRequestLocale() throws Throwable {
        // 策略不保存语言的共享实例：描述与返回值都跟随请求语言
        VectorStoreCompressionStrategy vs = new VectorStoreCompressionStrategy(null);

        Prompt enPrompt = Prompt.of("x").locale(Locale.ENGLISH);
        FunctionTool enTool = vs.getTools(enPrompt).iterator().next();
        assertNoChinese(enTool.description(), "tool.description");

        Map<String, Object> args = new HashMap<>();
        args.put("query", "");
        args.put(ChatSession.ATTR_SESSIONID, "s1");
        Assertions.assertEquals(FrozenMessages.of(Locale.ENGLISH).get("compress.vector.recall.empty_query"),
                String.valueOf(enTool.handle(args)));

        args.put("query", "q");
        args.put(ChatSession.ATTR_SESSIONID, "bad id");
        assertNoChinese(String.valueOf(enTool.handle(args)), "recall.invalid_session");

        // 同一实例并发服务中文请求，互不影响
        FunctionTool zhTool = vs.getTools(Prompt.of("y")).iterator().next();
        args.put("query", "");
        Assertions.assertEquals("请提供具体的关键词以进行历史回溯。", String.valueOf(zhTool.handle(args)));
        Assertions.assertNotSame(enTool, zhTool);
    }

    /**
     * 原注解版工具（仅用于对比 schema）
     */
    public static class LegacyRecallTool {
        @ToolMapping(name = "recall_history", description = "回溯本会话中早前已归档的长记忆、历史细节或事实")
        public String recall(@Param(name = "query", description = "检索关键词或核心短语") String query,
                             @Param(name = "limit", description = "返回记忆片段的数量", defaultValue = "3") int limit,
                             String __sessionId) {
            return "";
        }
    }
}
