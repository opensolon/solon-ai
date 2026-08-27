package features.ai.dialect;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.llm.dialect.openai.OpenaiResponsesDialect;
import org.noear.solon.ai.llm.dialect.openai.OpenaiResponsesRequestBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Responses 方言思考字段适配单测（mock JSON，不依赖真实 API）
 *
 * <p>覆盖：非流式 reasoning item、流式 reasoning_text.delta、usage.reasoning_tokens、
 * 请求侧 thinking 消息回传为 reasoning item、response.incomplete 结束处理。</p>
 *
 * @since 4.0.5
 */
public class OpenaiResponsesThinkParseTest {
    private static final OpenaiResponsesDialect dialect = OpenaiResponsesDialect.getInstance();

    private static ChatResponseDefault newResp(boolean stream) {
        ChatRequest req = new ChatRequest(
                new ChatConfig(),
                dialect,
                ChatOptions.of(),
                InMemoryChatSession.builder().build(),
                null,
                null,
                stream);
        return new ChatResponseDefault(req, stream);
    }

    /**
     * 非流式：reasoning item + message item → thinking choice + 正文 choice + usage.thinkTokens
     */
    @Test
    public void nonStreamShouldParseReasoningItem() {
        ChatResponseDefault resp = newResp(false);
        String json = "{"
                + "\"id\":\"resp_1\","
                + "\"object\":\"response\","
                + "\"created_at\":1750000000,"
                + "\"status\":\"completed\","
                + "\"model\":\"deepseek-reasoner\","
                + "\"output\":["
                + "  {\"id\":\"r1\",\"type\":\"reasoning\",\"summary\":[],"
                + "   \"content\":[{\"type\":\"reasoning_text\",\"text\":\"让我想想\"},"
                + "              {\"type\":\"reasoning_text\",\"text\":\"再想想\"}]},"
                + "  {\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\","
                + "   \"content\":[{\"type\":\"output_text\",\"text\":\"你好\"}]}"
                + "],"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30,"
                + "  \"output_tokens_details\":{\"reasoning_tokens\":8}}"
                + "}";

        boolean ok = dialect.parseResponseJson(new ChatConfig(), resp, json);

        Assertions.assertTrue(ok);
        Assertions.assertEquals(2, resp.getChoices().size());
        // 第一条为思考消息
        AssistantMessage first = resp.getChoices().get(0).getMessage();
        Assertions.assertTrue(first.isThinking());
        Assertions.assertEquals("让我想想再想想", first.getContent());
        // 第二条为正文消息
        AssistantMessage second = resp.getChoices().get(1).getMessage();
        Assertions.assertFalse(second.isThinking());
        Assertions.assertEquals("你好", second.getContent());
        // usage 思考 token 已解析
        Assertions.assertNotNull(resp.getUsage());
        Assertions.assertEquals(8L, resp.getUsage().thinkTokens());
        Assertions.assertEquals(10L, resp.getUsage().promptTokens());
        Assertions.assertEquals(20L, resp.getUsage().completionTokens());
    }

    /**
     * 非流式：无 output 数组时，顶层 reasoning_text 便捷字段兜底为 thinking 消息
     */
    @Test
    public void nonStreamShouldParseTopLevelReasoningText() {
        ChatResponseDefault resp = newResp(false);
        String json = "{"
                + "\"id\":\"resp_2\","
                + "\"object\":\"response\","
                + "\"status\":\"completed\","
                + "\"model\":\"deepseek-reasoner\","
                + "\"reasoning_text\":\"顶层思考\","
                + "\"output_text\":\"顶层正文\""
                + "}";

        boolean ok = dialect.parseResponseJson(new ChatConfig(), resp, json);

        Assertions.assertTrue(ok);
        Assertions.assertEquals(2, resp.getChoices().size());
        Assertions.assertTrue(resp.getChoices().get(0).getMessage().isThinking());
        Assertions.assertEquals("顶层思考", resp.getChoices().get(0).getMessage().getContent());
        Assertions.assertFalse(resp.getChoices().get(1).getMessage().isThinking());
        Assertions.assertEquals("顶层正文", resp.getChoices().get(1).getMessage().getContent());
    }

    /**
     * 流式：reasoning_text.delta 推送 thinking 消息，completed 正常结束
     */
    @Test
    public void streamShouldParseReasoningDelta() {
        ChatResponseDefault resp = newResp(true);
        String[] sse = new String[]{
                "data: {\"type\":\"response.created\",\"response\":{\"model\":\"deepseek-reasoner\"}}",
                "data: {\"type\":\"response.output_item.added\",\"item\":{\"id\":\"r1\",\"type\":\"reasoning\"}}",
                "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"让我\",\"item_id\":\"r1\",\"output_index\":0,\"content_index\":0}",
                "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"想想\",\"item_id\":\"r1\",\"output_index\":0,\"content_index\":0}",
                "data: {\"type\":\"response.reasoning_text.done\",\"item_id\":\"r1\",\"output_index\":0,\"content_index\":0}",
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"r1\",\"type\":\"reasoning\"}}",
                "data: {\"type\":\"response.output_item.added\",\"item\":{\"id\":\"m1\",\"type\":\"message\"}}",
                "data: {\"type\":\"response.content_part.added\",\"part\":{\"type\":\"output_text\"}}",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好\"}",
                "data: {\"type\":\"response.output_text.done\",\"item_id\":\"m1\"}",
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"m1\",\"type\":\"message\"}}",
                "data: {\"type\":\"response.completed\",\"response\":{\"model\":\"deepseek-reasoner\","
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30,"
                        + "\"output_tokens_details\":{\"reasoning_tokens\":8}}}}"
        };

        for (String line : sse) {
            dialect.parseResponseJson(new ChatConfig(), resp, line);
        }

        Assertions.assertTrue(resp.isFinished());
        // 既有思考消息、也有非思考消息
        Assertions.assertTrue(resp.getChoices().stream().anyMatch(c -> c.getMessage().isThinking()));
        Assertions.assertTrue(resp.getChoices().stream().anyMatch(c -> !c.getMessage().isThinking()));
        // 思考增量内容已按序回传
        List<String> thinkDeltas = new ArrayList<>();
        for (org.noear.solon.ai.chat.ChatChoice choice : resp.getChoices()) {
            if (choice.getMessage().isThinking()) {
                thinkDeltas.add(choice.getMessage().getContent());
            }
        }
        Assertions.assertEquals("让我", thinkDeltas.get(0));
        Assertions.assertEquals("想想", thinkDeltas.get(1));
        // usage 已解析
        Assertions.assertNotNull(resp.getUsage());
        Assertions.assertEquals(8L, resp.getUsage().thinkTokens());
    }

    /**
     * 流式：response.incomplete（如 max_output_tokens 截断）应结束流，避免悬挂
     */
    @Test
    public void streamIncompleteShouldFinish() {
        ChatResponseDefault resp = newResp(true);
        String[] sse = new String[]{
                "data: {\"type\":\"response.created\",\"response\":{\"model\":\"deepseek-reasoner\"}}",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"部分内容\"}",
                "data: {\"type\":\"response.incomplete\",\"response\":{\"model\":\"deepseek-reasoner\","
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30}}}"
        };

        for (String line : sse) {
            dialect.parseResponseJson(new ChatConfig(), resp, line);
        }

        Assertions.assertTrue(resp.isFinished());
        Assertions.assertTrue(resp.hasChoices());
    }

    /**
     * 请求侧：历史 thinking 消息应回传为 reasoning item（而非丢弃）
     */
    @Test
    public void buildShouldEmitReasoningItemForThinkingMessage() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofSystem("你是助手"));
        messages.add(new AssistantMessage("", "让我先想想", true));
        messages.add(ChatMessage.ofUser("你好"));

        ONode node = new OpenaiResponsesRequestBuilder().build(new ChatConfig(), ChatOptions.of(), messages, false);

        ONode input = node.get("input");
        Assertions.assertTrue(input.isArray());
        Assertions.assertEquals(2, input.getArray().size());
        // 第一条为 reasoning item（思考消息回传）
        ONode first = input.get(0);
        Assertions.assertEquals("reasoning", first.get("type").getString());
        Assertions.assertEquals("reasoning_text", first.get("content").get(0).get("type").getString());
        Assertions.assertEquals("让我先想想", first.get("content").get(0).get("text").getString());
        // 第二条为 user 消息
        ONode second = input.get(1);
        Assertions.assertEquals("user", second.get("role").getString());
        Assertions.assertEquals("你好", second.get("content").getString());
    }

    /**
     * 请求侧：带 think 标签的 thinking 消息应剥离标签后回传
     */
    @Test
    public void buildShouldStripThinkTagsForThinkingMessage() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new AssistantMessage("", "内部思考", true));

        ONode node = new OpenaiResponsesRequestBuilder().build(new ChatConfig(), ChatOptions.of(), messages, false);

        ONode first = node.get("input").get(0);
        Assertions.assertEquals("reasoning", first.get("type").getString());
        Assertions.assertEquals("内部思考", first.get("content").get(0).get("text").getString());
    }

    /**
     * 流式：无 reasoning 上下文（缺 output_item.added / content_part.added 前置事件）时，
     * reasoning_text.delta 应被丢弃而非输出为 thinking 消息（防止非思考内容被误标）
     */
    @Test
    public void streamShouldDropReasoningDeltaWithoutContext() {
        ChatResponseDefault resp = newResp(true);
        String[] sse = new String[]{
                "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"孤立增量\"}",
                "data: {\"type\":\"response.completed\",\"response\":{\"model\":\"m\"}}"
        };

        for (String line : sse) {
            dialect.parseResponseJson(new ChatConfig(), resp, line);
        }

        Assertions.assertTrue(resp.isFinished());
        // 不应产生任何 thinking 消息
        Assertions.assertTrue(resp.getChoices().stream().noneMatch(c -> c.getMessage().isThinking()));
    }

    /**
     * 流式：content_part.delta 携带 reasoning_text 类型时，应按 thinking 消息处理
     * （防止思考增量经通用 content_part.delta 事件被当作普通文本输出）
     */
    @Test
    public void streamShouldParseReasoningContentPartDeltaAsThinking() {
        ChatResponseDefault resp = newResp(true);
        String[] sse = new String[]{
                "data: {\"type\":\"response.output_item.added\",\"item\":{\"id\":\"r1\",\"type\":\"reasoning\"}}",
                "data: {\"type\":\"response.content_part.added\",\"part\":{\"type\":\"reasoning_text\"}}",
                "data: {\"type\":\"response.content_part.delta\",\"delta\":{\"type\":\"reasoning_text\",\"text\":\"思考中\"}}",
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"r1\",\"type\":\"reasoning\"}}",
                "data: {\"type\":\"response.completed\",\"response\":{\"model\":\"m\"}}"
        };

        for (String line : sse) {
            dialect.parseResponseJson(new ChatConfig(), resp, line);
        }

        Assertions.assertTrue(resp.isFinished());
        // 应产生 thinking 消息且内容正确
        org.noear.solon.ai.chat.ChatChoice thinking = resp.getChoices().stream()
                .filter(c -> c.getMessage().isThinking())
                .findFirst()
                .orElse(null);
        Assertions.assertNotNull(thinking);
        Assertions.assertEquals("思考中", thinking.getMessage().getContent());
    }
}