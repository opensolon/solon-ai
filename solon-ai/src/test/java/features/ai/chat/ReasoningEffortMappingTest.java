package features.ai.chat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.llm.dialect.anthropic.AnthropicRequestBuilder;
import org.noear.solon.ai.llm.dialect.gemini.interactions.GeminiInteractionsRequestBuilder;
import org.noear.solon.ai.llm.dialect.gemini.models.GeminiRequestBuilder;
import org.noear.solon.ai.llm.dialect.openai.OpenaiChatDialect;
import org.noear.solon.ai.llm.dialect.openai.OpenaiResponsesRequestBuilder;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 统一 reasoning_effort / thinking 在各方言请求构建中的映射测试
 *
 * @since 4.0.4
 */
public class ReasoningEffortMappingTest {

    private static ChatConfig config(String model) {
        ChatConfig c = new ChatConfig();
        c.setModel(model);
        return c;
    }

    private static List<ChatMessage> userMsg() {
        return Collections.singletonList(ChatMessage.ofUser("hello"));
    }

    @Test
    @DisplayName("Anthropic: reasoning_effort → thinking.budget_tokens")
    public void testAnthropicMapping() {
        AnthropicRequestBuilder builder = new AnthropicRequestBuilder();

        ChatOptions o = ChatOptions.of().reasoning_effort("medium");
        ONode root = builder.build(config("claude-sonnet"), o, userMsg(), false);
        Assertions.assertEquals("enabled", root.get("thinking").get("type").getString());
        Assertions.assertEquals(10000, root.get("thinking").get("budget_tokens").getInt());
        Assertions.assertFalse(root.hasKey("reasoning_effort"));
    }

    @Test
    @DisplayName("Anthropic: budget 必须小于 max_tokens")
    public void testAnthropicBudgetClamp() {
        AnthropicRequestBuilder builder = new AnthropicRequestBuilder();
        ChatOptions o = ChatOptions.of().max_tokens(2048).reasoning_effort("medium");
        ONode root = builder.build(config("claude-sonnet"), o, userMsg(), false);
        int budget = root.get("thinking").get("budget_tokens").getInt();
        Assertions.assertTrue(budget < 2048);
        Assertions.assertEquals(2047, budget);
    }

    @Test
    @DisplayName("Anthropic: max_tokens 很小（<=1024）时 budget 仍严格小于 max_tokens")
    public void testAnthropicBudgetClampTinyMaxTokens() {
        AnthropicRequestBuilder builder = new AnthropicRequestBuilder();

        ChatOptions o500 = ChatOptions.of().max_tokens(500).reasoning_effort("medium");
        ONode root500 = builder.build(config("claude-sonnet"), o500, userMsg(), false);
        int budget500 = root500.get("thinking").get("budget_tokens").getInt();
        Assertions.assertTrue(budget500 < 500);
        Assertions.assertEquals(499, budget500);

        ChatOptions o1 = ChatOptions.of().max_tokens(1).reasoning_effort("high");
        ONode root1 = builder.build(config("claude-sonnet"), o1, userMsg(), false);
        Assertions.assertFalse(root1.hasKey("thinking"));
    }

    @Test
    @DisplayName("Anthropic: 显式 thinking 不被 reasoning_effort 覆盖")
    public void testAnthropicExplicitThinkingWins() {
        AnthropicRequestBuilder builder = new AnthropicRequestBuilder();
        ChatOptions o = ChatOptions.of()
                .reasoning_effort("max")
                .optionSet("thinking", Utils.asMap("type", "enabled", "budget_tokens", 5000));
        ONode root = builder.build(config("claude-sonnet"), o, userMsg(), false);
        Assertions.assertEquals(5000, root.get("thinking").get("budget_tokens").getInt());
    }

    @Test
    @DisplayName("OpenAI Responses: reasoning_effort → reasoning.effort，max→xhigh")
    public void testOpenaiResponsesMapping() {
        OpenaiResponsesRequestBuilder builder = new OpenaiResponsesRequestBuilder();
        ChatOptions o = ChatOptions.of().reasoning_effort("max");
        ONode root = builder.build(config("gpt-5"), o, userMsg(), false);
        Assertions.assertEquals("xhigh", root.get("reasoning").get("effort").getString());
        Assertions.assertFalse(root.hasKey("reasoning_effort"));
    }

    @Test
    @DisplayName("OpenAI Responses: 显式 reasoning 不被覆盖")
    public void testOpenaiResponsesExplicitWins() {
        OpenaiResponsesRequestBuilder builder = new OpenaiResponsesRequestBuilder();
        ChatOptions o = ChatOptions.of()
                .reasoning_effort("low")
                .optionSet("reasoning", Utils.asMap("effort", "high", "summary", "auto"));
        ONode root = builder.build(config("gpt-5"), o, userMsg(), false);
        Assertions.assertEquals("high", root.get("reasoning").get("effort").getString());
        Assertions.assertEquals("auto", root.get("reasoning").get("summary").getString());
    }

    @Test
    @DisplayName("OpenAI Responses: 统一路径非法 effort 不写出；显式 reasoning 未知值可透传")
    public void testOpenaiResponsesClampStrictVsPassthrough() {
        OpenaiResponsesRequestBuilder builder = new OpenaiResponsesRequestBuilder();

        // 通过 optionSet 注入非法值（绕过统一 API 校验），统一路径应丢弃
        ChatOptions dirty = ChatOptions.of().optionSet("reasoning_effort", "ultra");
        ONode rootDirty = builder.build(config("gpt-5"), dirty, userMsg(), false);
        Assertions.assertFalse(rootDirty.hasKey("reasoning"));
        Assertions.assertFalse(rootDirty.hasKey("reasoning_effort"));

        // 显式 reasoning 未知档位透传
        ChatOptions explicit = ChatOptions.of()
                .optionSet("reasoning", Utils.asMap("effort", "vendor_custom"));
        ONode rootExplicit = builder.build(config("gpt-5"), explicit, userMsg(), false);
        Assertions.assertEquals("vendor_custom", rootExplicit.get("reasoning").get("effort").getString());
    }

    @Test
    @DisplayName("OpenAI Chat Completions: reasoning_effort 顶层写出，max→xhigh")
    public void testOpenaiChatCompletionsClamp() {
        AbstractChatDialect dialect = OpenaiChatDialect.getInstance();
        ChatOptions o = ChatOptions.of().reasoning_effort("max");
        ONode root = dialect.buildRequestJson(config("o3"), o, userMsg(), false);
        Assertions.assertEquals("xhigh", root.get("reasoning_effort").getString());
    }

    @Test
    @DisplayName("Gemini models 2.5: reasoning_effort → thinkingBudget，且不被 generationConfig 覆盖")
    public void testGeminiModelsMergeOrder() {
        GeminiRequestBuilder builder = new GeminiRequestBuilder();
        ChatOptions o = ChatOptions.of()
                .reasoning_effort("high")
                .optionSet("generationConfig", Utils.asMap("temperature", 0.2));
        ONode root = builder.build(config("gemini-2.5-pro"), o, userMsg(), false);

        ONode gen = root.get("generationConfig");
        Assertions.assertNotNull(gen);
        Assertions.assertEquals(0.2, gen.get("temperature").getDouble(), 0.0001);
        Assertions.assertTrue(gen.hasKey("thinkingConfig"));
        Assertions.assertEquals(16000, gen.get("thinkingConfig").get("thinkingBudget").getInt());
        Assertions.assertTrue(gen.get("thinkingConfig").get("includeThoughts").getBoolean());
        // 不双写 thinkingLevel
        Assertions.assertFalse(gen.get("thinkingConfig").hasKey("thinkingLevel"));
        Assertions.assertFalse(root.hasKey("reasoning_effort"));
    }

    @Test
    @DisplayName("Gemini models 3.x: reasoning_effort → thinkingLevel（非 budget）")
    public void testGeminiModels3ThinkingLevel() {
        GeminiRequestBuilder builder = new GeminiRequestBuilder();

        ChatOptions high = ChatOptions.of().reasoning_effort("high");
        ONode rootHigh = builder.build(config("gemini-3-pro"), high, userMsg(), false);
        ONode tcHigh = rootHigh.get("generationConfig").get("thinkingConfig");
        Assertions.assertEquals("high", tcHigh.get("thinkingLevel").getString());
        Assertions.assertTrue(tcHigh.get("includeThoughts").getBoolean());
        Assertions.assertFalse(tcHigh.hasKey("thinkingBudget"));

        // Gemini 3 pro 支持 medium（对齐 OpenCode）
        ChatOptions med = ChatOptions.of().reasoning_effort("medium");
        ONode rootMed = builder.build(config("gemini-3-pro"), med, userMsg(), false);
        Assertions.assertEquals("medium", rootMed.get("generationConfig").get("thinkingConfig").get("thinkingLevel").getString());

        // 3.1 同样支持 medium
        ONode root31 = builder.build(config("gemini-3.1-pro"), med, userMsg(), false);
        Assertions.assertEquals("medium", root31.get("generationConfig").get("thinkingConfig").get("thinkingLevel").getString());

        // flash 支持 minimal
        ChatOptions low = ChatOptions.of().reasoning_effort("low");
        ONode rootFlash = builder.build(config("gemini-3-flash"), low, userMsg(), false);
        Assertions.assertEquals("low", rootFlash.get("generationConfig").get("thinkingConfig").get("thinkingLevel").getString());
    }

    @Test
    @DisplayName("Gemini models 2.5 pro: max budget=32768；flash max=24576")
    public void testGeminiModels25ProMaxBudget() {
        GeminiRequestBuilder builder = new GeminiRequestBuilder();
        ChatOptions max = ChatOptions.of().reasoning_effort("max");

        ONode pro = builder.build(config("gemini-2.5-pro"), max, userMsg(), false);
        Assertions.assertEquals(32768, pro.get("generationConfig").get("thinkingConfig").get("thinkingBudget").getInt());

        ONode flash = builder.build(config("gemini-2.5-flash"), max, userMsg(), false);
        Assertions.assertEquals(24576, flash.get("generationConfig").get("thinkingConfig").get("thinkingBudget").getInt());
    }

    @Test
    @DisplayName("Gemini models: 显式 thinkingConfig 不被覆盖")
    public void testGeminiModelsExplicitThinkingWins() {
        GeminiRequestBuilder builder = new GeminiRequestBuilder();
        Map thinking = Utils.asMap("includeThoughts", true, "thinkingBudget", 777);
        ChatOptions o = ChatOptions.of()
                .reasoning_effort("max")
                .optionSet("generationConfig", Utils.asMap("temperature", 0.5, "thinkingConfig", thinking));
        ONode root = builder.build(config("gemini-2.5-pro"), o, userMsg(), false);
        Assertions.assertEquals(777, root.get("generationConfig").get("thinkingConfig").get("thinkingBudget").getInt());
    }

    @Test
    @DisplayName("Gemini interactions: reasoning_effort → thinking_level")
    public void testGeminiInteractionsMapping() {
        GeminiInteractionsRequestBuilder builder = new GeminiInteractionsRequestBuilder();
        ChatOptions o = ChatOptions.of().reasoning_effort("medium");
        ONode root = builder.build(config("gemini-3-pro"), o, userMsg(), false);
        ONode cfg = root.get("config");
        Assertions.assertNotNull(cfg);
        Assertions.assertEquals("medium", cfg.get("thinking_level").getString());
        Assertions.assertTrue(cfg.get("thinking_summaries").getBoolean());
    }

    @Test
    @DisplayName("Gemini interactions: 显式 thinkingConfig 优先")
    public void testGeminiInteractionsExplicitWins() {
        GeminiInteractionsRequestBuilder builder = new GeminiInteractionsRequestBuilder();
        Map thinking = Utils.asMap("thinkingLevel", "LOW", "includeThoughts", false);
        ChatOptions o = ChatOptions.of()
                .reasoning_effort("max")
                .optionSet("generationConfig", Utils.asMap("thinkingConfig", thinking));
        ONode root = builder.build(config("gemini-3-pro"), o, userMsg(), false);
        Assertions.assertEquals("low", root.get("config").get("thinking_level").getString());
        Assertions.assertFalse(root.get("config").get("thinking_summaries").getBoolean());
    }

    // ---------- thinking 开关 ----------

    @Test
    @DisplayName("Chat Completions: qwen → enable_thinking（单写，无 thinking 对象）")
    public void testChatCompletionsThinkingSwitchQwen() {
        AbstractChatDialect dialect = OpenaiChatDialect.getInstance();

        ChatOptions on = ChatOptions.of().thinking(true);
        ONode rootOn = dialect.buildRequestJson(config("qwen-plus"), on, userMsg(), false);
        Assertions.assertTrue(rootOn.get("enable_thinking").getBoolean());
        Assertions.assertFalse(rootOn.hasKey("thinking"));

        ChatOptions off = ChatOptions.of().thinking(false);
        ONode rootOff = dialect.buildRequestJson(config("qwen-plus"), off, userMsg(), false);
        Assertions.assertFalse(rootOff.get("enable_thinking").getBoolean());
        Assertions.assertFalse(rootOff.hasKey("thinking"));
    }

    @Test
    @DisplayName("Chat Completions: deepseek → thinking.type（单写，无 enable_thinking）")
    public void testChatCompletionsThinkingSwitchDeepSeek() {
        AbstractChatDialect dialect = OpenaiChatDialect.getInstance();
    
        ChatOptions on = ChatOptions.of().thinking(true);
        ONode rootOn = dialect.buildRequestJson(config("deepseek-chat"), on, userMsg(), false);
        Assertions.assertEquals("enabled", rootOn.get("thinking").get("type").getString());
        Assertions.assertFalse(rootOn.hasKey("enable_thinking"));
    
        ChatOptions off = ChatOptions.of().thinking(false);
        ONode rootOff = dialect.buildRequestJson(config("deepseek-reasoner"), off, userMsg(), false);
        Assertions.assertEquals("disabled", rootOff.get("thinking").get("type").getString());
        Assertions.assertFalse(rootOff.hasKey("enable_thinking"));
    }
    
    @Test
    @DisplayName("Chat Completions: 中转 deepseek（dashscope apiUrl）→ enable_thinking")
    public void testChatCompletionsThinkingSwitchDeepSeekRelay() {
        AbstractChatDialect dialect = OpenaiChatDialect.getInstance();
        ChatConfig c = config("deepseek-r1");
        c.setApiUrl("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
    
        ChatOptions off = ChatOptions.of().thinking(false);
        ONode root = dialect.buildRequestJson(c, off, userMsg(), false);
        Assertions.assertFalse(root.get("enable_thinking").getBoolean());
        Assertions.assertFalse(root.hasKey("thinking"));
    }
    
    @Test
    @DisplayName("Chat Completions: 智谱 glm → thinking.type + clear_thinking")
    public void testChatCompletionsThinkingSwitchZhipu() {
        AbstractChatDialect dialect = OpenaiChatDialect.getInstance();
    
        ChatOptions on = ChatOptions.of().thinking(true);
        ONode rootOn = dialect.buildRequestJson(config("glm-4.5"), on, userMsg(), false);
        Assertions.assertEquals("enabled", rootOn.get("thinking").get("type").getString());
        Assertions.assertFalse(rootOn.get("thinking").get("clear_thinking").getBoolean());
        Assertions.assertFalse(rootOn.hasKey("enable_thinking"));
    }
    
    @Test
    @DisplayName("Chat Completions: MiniMax → thinking.type adaptive/disabled")
    public void testChatCompletionsThinkingSwitchMiniMax() {
        AbstractChatDialect dialect = OpenaiChatDialect.getInstance();
    
        ChatOptions on = ChatOptions.of().thinking(true);
        ONode rootOn = dialect.buildRequestJson(config("minimax-m1"), on, userMsg(), false);
        Assertions.assertEquals("adaptive", rootOn.get("thinking").get("type").getString());
    
        ChatOptions off = ChatOptions.of().thinking(false);
        ONode rootOff = dialect.buildRequestJson(config("minimax-m1"), off, userMsg(), false);
        Assertions.assertEquals("disabled", rootOff.get("thinking").get("type").getString());
    }
    
    @Test
    @DisplayName("Chat Completions: OpenAI 官方模型 thinking(Boolean) 不写出开关字段")
    public void testChatCompletionsThinkingSwitchOpenAiNone() {
        AbstractChatDialect dialect = OpenaiChatDialect.getInstance();
    
        ChatOptions on = ChatOptions.of().thinking(true);
        ONode rootOn = dialect.buildRequestJson(config("gpt-4o"), on, userMsg(), false);
        Assertions.assertFalse(rootOn.hasKey("enable_thinking"));
        Assertions.assertFalse(rootOn.hasKey("thinking"));
    }
    
    @Test
    @DisplayName("Chat Completions: Map 形态 thinking 仍透传（逃生舱）")
    public void testChatCompletionsThinkingMapPassthrough() {
        AbstractChatDialect dialect = OpenaiChatDialect.getInstance();
        ChatOptions o = ChatOptions.of()
                .optionSet("thinking", Utils.asMap("type", "disabled"));
        ONode root = dialect.buildRequestJson(config("gpt-4o"), o, userMsg(), false);
        Assertions.assertEquals("disabled", root.get("thinking").get("type").getString());
        Assertions.assertFalse(root.hasKey("enable_thinking"));
    }
    
    @Test
    @DisplayName("Chat Completions: DeepSeek 官方 reasoning_effort max 保持 max（不转 xhigh）")
    public void testChatCompletionsDeepSeekEffort() {
        AbstractChatDialect dialect = OpenaiChatDialect.getInstance();
    
        ChatOptions max = ChatOptions.of().reasoning_effort("max");
        ONode rootMax = dialect.buildRequestJson(config("deepseek-chat"), max, userMsg(), false);
        Assertions.assertEquals("max", rootMax.get("reasoning_effort").getString());
    
        ChatOptions low = ChatOptions.of().reasoning_effort("low");
        ONode rootLow = dialect.buildRequestJson(config("deepseek-chat"), low, userMsg(), false);
        Assertions.assertEquals("high", rootLow.get("reasoning_effort").getString());
    }
    
    @Test
    @DisplayName("Anthropic: thinking(true/false) 开关；false 优先于 reasoning_effort")
    public void testAnthropicThinkingSwitch() {
        AnthropicRequestBuilder builder = new AnthropicRequestBuilder();

        ChatOptions on = ChatOptions.of().thinking(true);
        ONode rootOn = builder.build(config("claude-sonnet"), on, userMsg(), false);
        Assertions.assertEquals("enabled", rootOn.get("thinking").get("type").getString());
        Assertions.assertTrue(rootOn.get("thinking").get("budget_tokens").getInt() > 0);

        ChatOptions off = ChatOptions.of().thinking(false).reasoning_effort("high");
        ONode rootOff = builder.build(config("claude-sonnet"), off, userMsg(), false);
        Assertions.assertEquals("disabled", rootOff.get("thinking").get("type").getString());

        // true + effort：用 effort 档位，不用默认 10000
        ChatOptions onEffort = ChatOptions.of().thinking(true).reasoning_effort("low");
        ONode rootOnEffort = builder.build(config("claude-sonnet"), onEffort, userMsg(), false);
        Assertions.assertEquals("enabled", rootOnEffort.get("thinking").get("type").getString());
        Assertions.assertEquals(4000, rootOnEffort.get("thinking").get("budget_tokens").getInt());
    }

    @Test
    @DisplayName("OpenAI Responses: thinking(false) → reasoning.effort=none")
    public void testOpenaiResponsesThinkingOff() {
        OpenaiResponsesRequestBuilder builder = new OpenaiResponsesRequestBuilder();

        ChatOptions off = ChatOptions.of().thinking(false).reasoning_effort("high");
        ONode rootOff = builder.build(config("gpt-5"), off, userMsg(), false);
        Assertions.assertEquals("none", rootOff.get("reasoning").get("effort").getString());

        // true 不强制写 effort；有 reasoning_effort 时仍映射
        ChatOptions onEffort = ChatOptions.of().thinking(true).reasoning_effort("low");
        ONode rootOn = builder.build(config("gpt-5"), onEffort, userMsg(), false);
        Assertions.assertEquals("low", rootOn.get("reasoning").get("effort").getString());
    }

    @Test
    @DisplayName("Gemini models: thinking 开关；2.5 budget=0；3.x level=minimal")
    public void testGeminiModelsThinkingSwitch() {
        GeminiRequestBuilder builder = new GeminiRequestBuilder();

        ChatOptions off25 = ChatOptions.of()
                .thinking(false)
                .reasoning_effort("high")
                .optionSet("generationConfig", Utils.asMap("temperature", 0.1));
        ONode rootOff25 = builder.build(config("gemini-2.5-pro"), off25, userMsg(), false);
        ONode tcOff25 = rootOff25.get("generationConfig").get("thinkingConfig");
        Assertions.assertEquals(0, tcOff25.get("thinkingBudget").getInt());
        Assertions.assertFalse(tcOff25.get("includeThoughts").getBoolean());

        ChatOptions on25 = ChatOptions.of().thinking(true);
        ONode rootOn25 = builder.build(config("gemini-2.5-pro"), on25, userMsg(), false);
        ONode tcOn25 = rootOn25.get("generationConfig").get("thinkingConfig");
        Assertions.assertEquals(4096, tcOn25.get("thinkingBudget").getInt());
        Assertions.assertTrue(tcOn25.get("includeThoughts").getBoolean());

        ChatOptions off3 = ChatOptions.of().thinking(false).reasoning_effort("high");
        ONode rootOff3 = builder.build(config("gemini-3-pro"), off3, userMsg(), false);
        ONode tcOff3 = rootOff3.get("generationConfig").get("thinkingConfig");
        Assertions.assertEquals("minimal", tcOff3.get("thinkingLevel").getString());
        Assertions.assertFalse(tcOff3.hasKey("thinkingBudget"));

        ChatOptions on3 = ChatOptions.of().thinking(true);
        ONode rootOn3 = builder.build(config("gemini-3-pro"), on3, userMsg(), false);
        Assertions.assertEquals("high", rootOn3.get("generationConfig").get("thinkingConfig").get("thinkingLevel").getString());
    }

    @Test
    @DisplayName("Anthropic adaptive(4.6/4.7+/sonnet-5+): type=adaptive + 顶层 effort；display:summarized")
    public void testAnthropicAdaptive() {
        AnthropicRequestBuilder builder = new AnthropicRequestBuilder();

        ChatOptions med = ChatOptions.of().reasoning_effort("medium");
        ONode rootMed = builder.build(config("claude-sonnet-4-6"), med, userMsg(), false);
        Assertions.assertEquals("adaptive", rootMed.get("thinking").get("type").getString());
        Assertions.assertEquals("medium", rootMed.get("effort").getString());
        Assertions.assertFalse(rootMed.get("thinking").hasKey("budget_tokens"));
        // 4.6 不强制 display
        Assertions.assertFalse(rootMed.get("thinking").hasKey("display"));
        Assertions.assertFalse(rootMed.hasKey("reasoning_effort"));

        ChatOptions max47 = ChatOptions.of().reasoning_effort("max");
        ONode root47 = builder.build(config("claude-opus-4-7"), max47, userMsg(), false);
        Assertions.assertEquals("adaptive", root47.get("thinking").get("type").getString());
        Assertions.assertEquals("max", root47.get("effort").getString());
        // 4.7+ 强制 display=summarized
        Assertions.assertEquals("summarized", root47.get("thinking").get("display").getString());

        // sonnet-5+ / 倒置命名
        ONode rootSonnet5 = builder.build(config("claude-sonnet-5"), max47, userMsg(), false);
        Assertions.assertEquals("adaptive", rootSonnet5.get("thinking").get("type").getString());
        Assertions.assertEquals("summarized", rootSonnet5.get("thinking").get("display").getString());

        ONode rootInverted = builder.build(config("claude-4.7-opus"), max47, userMsg(), false);
        Assertions.assertEquals("adaptive", rootInverted.get("thinking").get("type").getString());
        Assertions.assertEquals("summarized", rootInverted.get("thinking").get("display").getString());

        // opus-4.8+
        ONode root48 = builder.build(config("claude-opus-4-8"), max47, userMsg(), false);
        Assertions.assertEquals("adaptive", root48.get("thinking").get("type").getString());
        Assertions.assertEquals("summarized", root48.get("thinking").get("display").getString());

        ChatOptions on = ChatOptions.of().thinking(true);
        ONode rootOn = builder.build(config("claude-sonnet-4.6"), on, userMsg(), false);
        Assertions.assertEquals("adaptive", rootOn.get("thinking").get("type").getString());
        Assertions.assertEquals("medium", rootOn.get("effort").getString());

        ChatOptions off = ChatOptions.of().thinking(false).reasoning_effort("high");
        ONode rootOff = builder.build(config("claude-sonnet-4-6"), off, userMsg(), false);
        Assertions.assertEquals("disabled", rootOff.get("thinking").get("type").getString());
        Assertions.assertFalse(rootOff.hasKey("effort"));
    }

    @Test
    @DisplayName("Chat Completions: GLM-5.2 豁免 effort 抑制，写出 high/max")
    public void testChatCompletionsGlm52Effort() {
        AbstractChatDialect dialect = OpenaiChatDialect.getInstance();

        ChatOptions high = ChatOptions.of().reasoning_effort("high");
        ONode rootHigh = dialect.buildRequestJson(config("glm-5.2"), high, userMsg(), false);
        Assertions.assertEquals("high", rootHigh.get("reasoning_effort").getString());
        Assertions.assertEquals("enabled", rootHigh.get("thinking").get("type").getString());

        ChatOptions max = ChatOptions.of().reasoning_effort("max");
        ONode rootMax = dialect.buildRequestJson(config("glm-5-2"), max, userMsg(), false);
        Assertions.assertEquals("max", rootMax.get("reasoning_effort").getString());

        // 其它 glm 仍抑制
        ONode glm45 = dialect.buildRequestJson(config("glm-4.5"), high, userMsg(), false);
        Assertions.assertFalse(glm45.hasKey("reasoning_effort"));
        Assertions.assertEquals("enabled", glm45.get("thinking").get("type").getString());

        // OpenRouter + glm-5.2：max → xhigh 嵌套
        ChatConfig or = config("glm-5.2");
        or.setApiUrl("https://openrouter.ai/api/v1/chat/completions");
        ONode rootOr = dialect.buildRequestJson(or, max, userMsg(), false);
        Assertions.assertEquals("xhigh", rootOr.get("reasoning").get("effort").getString());
        Assertions.assertFalse(rootOr.hasKey("reasoning_effort"));
    }

    @Test
    @DisplayName("Chat Completions: OpenRouter → reasoning.effort 嵌套")
    public void testChatCompletionsOpenRouterEffort() {
        AbstractChatDialect dialect = OpenaiChatDialect.getInstance();
        ChatConfig c = config("gpt-5");
        c.setApiUrl("https://openrouter.ai/api/v1/chat/completions");

        ChatOptions o = ChatOptions.of().reasoning_effort("high");
        ONode root = dialect.buildRequestJson(c, o, userMsg(), false);
        Assertions.assertEquals("high", root.get("reasoning").get("effort").getString());
        Assertions.assertFalse(root.hasKey("reasoning_effort"));
    }

    @Test
    @DisplayName("Chat Completions: qwen 等国产模型抑制顶层 reasoning_effort")
    public void testChatCompletionsDomesticEffortSuppressed() {
        AbstractChatDialect dialect = OpenaiChatDialect.getInstance();

        ChatOptions o = ChatOptions.of().reasoning_effort("high").thinking(true);
        ONode qwen = dialect.buildRequestJson(config("qwen-plus"), o, userMsg(), false);
        Assertions.assertFalse(qwen.hasKey("reasoning_effort"));
        Assertions.assertTrue(qwen.get("enable_thinking").getBoolean());

        ONode kimi = dialect.buildRequestJson(config("kimi-k2"), o, userMsg(), false);
        Assertions.assertFalse(kimi.hasKey("reasoning_effort"));
        Assertions.assertEquals("enabled", kimi.get("thinking").get("type").getString());

        // DeepSeek 官方仍保留 high/max
        ONode ds = dialect.buildRequestJson(config("deepseek-chat"), o, userMsg(), false);
        Assertions.assertEquals("high", ds.get("reasoning_effort").getString());
    }

    @Test
    @DisplayName("Chat Completions: 仅 reasoning_effort 时隐式开启 thinking（对齐 OpenCode）")
    public void testChatCompletionsEffortImpliesThinkingOn() {
        AbstractChatDialect dialect = OpenaiChatDialect.getInstance();
    
        // qwen：effort 被抑制，但隐式 enable_thinking=true
        ChatOptions qwenOpt = ChatOptions.of().reasoning_effort("high");
        ONode qwen = dialect.buildRequestJson(config("qwen-plus"), qwenOpt, userMsg(), false);
        Assertions.assertFalse(qwen.hasKey("reasoning_effort"));
        Assertions.assertTrue(qwen.get("enable_thinking").getBoolean());
        Assertions.assertFalse(qwen.hasKey("thinking"));
    
        // deepseek：effort + thinking.type=enabled
        ChatOptions dsOpt = ChatOptions.of().reasoning_effort("max");
        ONode ds = dialect.buildRequestJson(config("deepseek-chat"), dsOpt, userMsg(), false);
        Assertions.assertEquals("max", ds.get("reasoning_effort").getString());
        Assertions.assertEquals("enabled", ds.get("thinking").get("type").getString());
        Assertions.assertFalse(ds.hasKey("enable_thinking"));
    
        // 智谱：thinking.type + clear_thinking
        ONode glm = dialect.buildRequestJson(config("glm-4.5"), qwenOpt, userMsg(), false);
        Assertions.assertEquals("enabled", glm.get("thinking").get("type").getString());
        Assertions.assertFalse(glm.get("thinking").get("clear_thinking").getBoolean());
    
        // MiniMax：adaptive
        ONode mm = dialect.buildRequestJson(config("minimax-m1"), qwenOpt, userMsg(), false);
        Assertions.assertEquals("adaptive", mm.get("thinking").get("type").getString());
    
        // OpenAI 官方：仅 effort，无 enable 位
        ChatOptions oaiOpt = ChatOptions.of().reasoning_effort("high");
        ONode oai = dialect.buildRequestJson(config("o3"), oaiOpt, userMsg(), false);
        Assertions.assertEquals("high", oai.get("reasoning_effort").getString());
        Assertions.assertFalse(oai.hasKey("enable_thinking"));
        Assertions.assertFalse(oai.hasKey("thinking"));
    }
    
    @Test
    @DisplayName("Chat Completions: thinking(false) 优先于 reasoning_effort 隐式开启")
    public void testChatCompletionsThinkingFalseBeatsEffortEnable() {
        AbstractChatDialect dialect = OpenaiChatDialect.getInstance();
    
        ChatOptions off = ChatOptions.of().thinking(false).reasoning_effort("high");
        ONode qwen = dialect.buildRequestJson(config("qwen-plus"), off, userMsg(), false);
        Assertions.assertFalse(qwen.get("enable_thinking").getBoolean());
    
        ONode ds = dialect.buildRequestJson(config("deepseek-chat"), off, userMsg(), false);
        Assertions.assertEquals("disabled", ds.get("thinking").get("type").getString());
        Assertions.assertEquals("high", ds.get("reasoning_effort").getString());
    }
    
    @Test
    @DisplayName("Chat Completions: 显式 Map thinking 不被 effort 隐式开启覆盖")
    public void testChatCompletionsMapThinkingBeatsEffortEnable() {
        AbstractChatDialect dialect = OpenaiChatDialect.getInstance();
        ChatOptions o = ChatOptions.of()
                .reasoning_effort("high")
                .optionSet("thinking", Utils.asMap("type", "disabled"));
        ONode root = dialect.buildRequestJson(config("deepseek-chat"), o, userMsg(), false);
        Assertions.assertEquals("disabled", root.get("thinking").get("type").getString());
        Assertions.assertEquals("high", root.get("reasoning_effort").getString());
    }
    
    @Test
    @DisplayName("Gemini interactions: thinking 开关")
    public void testGeminiInteractionsThinkingSwitch() {
        GeminiInteractionsRequestBuilder builder = new GeminiInteractionsRequestBuilder();

        ChatOptions off = ChatOptions.of().thinking(false);
        ONode rootOff = builder.build(config("gemini-3-pro"), off, userMsg(), false);
        Assertions.assertFalse(rootOff.get("config").get("thinking_summaries").getBoolean());

        ChatOptions on = ChatOptions.of().thinking(true).reasoning_effort("high");
        ONode rootOn = builder.build(config("gemini-3-pro"), on, userMsg(), false);
        Assertions.assertEquals("high", rootOn.get("config").get("thinking_level").getString());
        Assertions.assertTrue(rootOn.get("config").get("thinking_summaries").getBoolean());
    }
}
