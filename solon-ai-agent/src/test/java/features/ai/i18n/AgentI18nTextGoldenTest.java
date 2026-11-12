package features.ai.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.compress.HierarchicalCompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.KeyInfoExtractionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.LLMCompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.compress.VectorStoreCompressionStrategy;
import org.noear.solon.ai.agent.react.task.PlanTalent;
import org.noear.solon.ai.agent.team.TeamProtocolFactory;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.util.FeedbackTool;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 补充渲染快照：其它用例只检查了“不含中文”或现读资源比对、没有逐字校验的输出
 *
 * <p>包括工具对模型可见的全部信息（含 title）、计划工具的返回、向量回溯工具、反馈工具、HITL 敏感理由、
 * 压缩指令与摘要前缀、合同网运行时反馈。中英各一份（{@code text_*_zh/en.txt}）。</p>
 */
public class AgentI18nTextGoldenTest {
    @Test
    public void testZh() throws Throwable {
        GoldenFiles.verify(render(Locale.CHINESE, "_zh"));
    }

    @Test
    public void testEn() throws Throwable {
        GoldenFiles.verify(render(Locale.ENGLISH, "_en"));
    }

    static Map<String, String> render(Locale l, String s) throws Throwable {
        Map<String, String> out = new LinkedHashMap<>();
        String p = "text_";

        //团队协议工具
        for (Object[] kv : new Object[][]{{"a2a", TeamProtocols.A2A}, {"swarm", TeamProtocols.SWARM}}) {
            TeamBaseI18nTest.Ctx c = TeamBaseI18nTest.build((TeamProtocolFactory) kv[1], l);
            out.put(p + "tools_" + kv[0] + s, dump(TeamBaseI18nTest.tools(c, c.agent("coder"))));
        }
        for (Object[] kv : new Object[][]{{"blackboard", TeamProtocols.BLACKBOARD}, {"contractnet", TeamProtocols.CONTRACT_NET}}) {
            TeamProtocolI18nTest.Ctx c = TeamProtocolI18nTest.build((TeamProtocolFactory) kv[1], l);
            List<FunctionTool> tools = new ArrayList<>(TeamProtocolI18nTest.supervisorTools(c));
            tools.addAll(TeamProtocolI18nTest.agentTools(c, c.coder));
            out.put(p + "tools_" + kv[0] + s, dump(tools));
        }
        out.put(p + "contractnet_runtime" + s, contractNetRuntime(l));

        //计划
        PlanTalent plan = ReActRuntimeI18nTest.planTalent(l);
        out.put(p + "plan_talent" + s, "description: " + plan.description()
                + "\n--- tools ---\n" + dump(plan.getToolAry()));
        out.put(p + "plan_results" + s, String.join("\n---\n",
                plan.update_plan_progress(1),
                plan.revise_plan(Collections.singletonList("x"), 1),
                plan.create_plan(Collections.singletonList("only")),
                plan.create_plan(Arrays.asList("a", "b")),
                plan.update_plan_progress(2),
                plan.update_plan_progress(3),
                plan.revise_plan(Collections.emptyList(), 1),
                plan.revise_plan(Collections.singletonList("c"), 2)));

        //反馈工具与 HITL
        out.put(p + "feedback_tool" + s, dump(Collections.singletonList(FeedbackTool.getTool(null, null, l))));
        ReActTrace trace = mock(ReActTrace.class);
        ReActOptions options = ReActRuntimeI18nTest.options(mock(ChatModel.class), l, false);
        when(trace.getOptions()).thenReturn(options);
        out.put(p + "hitl_sensitive" + s, new HITLInterceptor.HITLSensitiveStrategy().evaluate(trace, Collections.emptyMap()));

        //压缩
        out.put(p + "compress_system" + s, "## llm\n" + new LLMCompressionStrategy().getSystemInstruction(l)
                + "\n## keyinfo\n" + new KeyInfoExtractionStrategy().getSystemInstruction(l)
                + "\n## hierarchical\n" + new HierarchicalCompressionStrategy().getSystemInstruction(l)
                + "\n## hierarchical.prefix\n" + HierarchicalCompressionStrategy.getSummaryPrefix(l));
        out.put(p + "compress_result_llm" + s, compressResult(new LLMCompressionStrategy()::compress, l));
        out.put(p + "compress_result_keyinfo" + s, compressResult(new KeyInfoExtractionStrategy()::compress, l));

        VectorStoreCompressionStrategy vs = new VectorStoreCompressionStrategy(null).locale(l);
        out.put(p + "vector" + s, "description: " + vs.description()
                + "\n--- recall(empty) ---\n" + vs.recall("", 3, "s1")
                + "\n--- recall(bad session) ---\n" + vs.recall("q", 3, "bad id")
                + "\n--- tools ---\n" + dump(vs.getTools(Prompt.of("x"))));
        return out;
    }

    /**
     * 合同网：发起招标、提交投标的返回
     */
    static String contractNetRuntime(Locale l) throws Throwable {
        TeamProtocolI18nTest.Ctx c = TeamProtocolI18nTest.build(TeamProtocols.CONTRACT_NET, l);
        Map<String, Object> req = new HashMap<>();
        req.put("requirement", "java api");
        String started = String.valueOf(TeamProtocolI18nTest.supervisorTools(c).get(0).handle(req));

        Map<String, Object> bid = new HashMap<>();
        bid.put("score", 80);
        bid.put("plan", "write it");
        Agent coder = c.coder;
        //投标工具只在招标阶段注入给成员
        List<FunctionTool> bidTools = TeamProtocolI18nTest.agentTools(c, coder);
        String received = String.valueOf(bidTools.get(0).handle(bid));
        return "call: " + started + "\nbid: " + received + "\n--- bid tools ---\n" + dump(bidTools);
    }

    /**
     * 压缩结果消息（摘要前缀 + 模型返回的摘要）
     */
    @SuppressWarnings("unchecked")
    static String compressResult(RuntimeRenderGoldenTest.Compressor compressor, Locale l) {
        ChatModel chatModel = mock(ChatModel.class);
        ChatRequestDesc req = mock(ChatRequestDesc.class);
        ChatResponse resp = mock(ChatResponse.class);
        when(chatModel.prompt(anyString())).thenReturn(req);
        when(req.options(any(Consumer.class))).thenReturn(req);
        try {
            when(req.call()).thenReturn(resp);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(resp.hasContent()).thenReturn(true);
        when(resp.getContent()).thenReturn("read a.txt");

        ReActTrace trace = mock(ReActTrace.class);
        ReActOptions options = ReActRuntimeI18nTest.options(mock(ChatModel.class), l, false);
        when(trace.getOptions()).thenReturn(options);

        ChatMessage msg = compressor.compress(chatModel, 1, trace,
                Arrays.asList(ChatMessage.ofUser("read a.txt"), ChatMessage.ofAssistant("done")));
        return msg == null ? "<null>" : msg.getContent();
    }

    /**
     * 工具对模型可见的全部信息（含 title；按名字排序）
     */
    static String dump(Collection<FunctionTool> tools) {
        List<FunctionTool> sorted = new ArrayList<>(tools);
        sorted.sort(Comparator.comparing(FunctionTool::name));
        StringBuilder sb = new StringBuilder();
        for (FunctionTool t : sorted) {
            if (sb.length() > 0) {
                sb.append("\n\n===\n\n");
            }
            sb.append("name: ").append(t.name())
                    .append("\ntitle: ").append(t.title())
                    .append("\n--- description ---\n").append(t.description())
                    .append("\n--- inputSchema ---\n").append(t.inputSchema());
        }
        return sb.toString();
    }

    static String dump(FunctionTool[] tools) {
        return dump(Arrays.asList(tools));
    }
}
