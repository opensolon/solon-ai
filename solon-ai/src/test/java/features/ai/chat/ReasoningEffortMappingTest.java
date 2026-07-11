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
 * 统一 reasoning_effort 在各方言请求构建中的映射测试
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
    @DisplayName("Gemini models: reasoning_effort → thinkingConfig.thinkingBudget，且不被 generationConfig 覆盖")
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
        Assertions.assertEquals(8192, gen.get("thinkingConfig").get("thinkingBudget").getInt());
        Assertions.assertTrue(gen.get("thinkingConfig").get("includeThoughts").getBoolean());
        // 不双写 thinkingLevel
        Assertions.assertFalse(gen.get("thinkingConfig").hasKey("thinkingLevel"));
        Assertions.assertFalse(root.hasKey("reasoning_effort"));
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
}
