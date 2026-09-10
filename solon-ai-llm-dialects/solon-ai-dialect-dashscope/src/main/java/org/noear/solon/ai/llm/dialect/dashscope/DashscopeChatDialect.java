/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.llm.dialect.dashscope;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.source.SearchResult;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.ai.chat.tool.ToolCallJsonSanitizer;
import org.noear.solon.net.http.HttpUtils;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DashScope 聊天模型方言（阿里云产品）
 *
 * @author shoukaiseki
 * @author noear
 * @since 3.1
 */

public class DashscopeChatDialect extends AbstractChatDialect {
    //https://help.aliyun.com/zh/model-studio/developer-reference

    private static final String URL_PREFIX = "https://dashscope.aliyuncs.com/api/v1/services/";

    private static DashscopeChatDialect instance = new DashscopeChatDialect();

    public static DashscopeChatDialect getInstance() {
        return instance;
    }
    /**
     * DashScope 流式输出由请求头控制，见官方文档：
     * <a href="https://help.aliyun.com/zh/model-studio/stream">流式输出</a>
     * cURL 需设置 Header 参数 X-DashScope-SSE 为 enable。
     */
    private static final String HEADER_DASHSCOPE_SSE = "X-DashScope-SSE";

    /**
     * 流式快照归一状态的挂载键（挂在 ChatStreamContext 上，见 {@link #normalizeSnapshotMessage}）
     */
    private static final String SNAPSHOT_STATE_KEY = "DashscopeStreamSnapshotState";

    /**
     * 已发搜索结果身份集合的挂载键。DashScope 可能在多个 SSE 快照中重复携带完整 search_info。
     */
    private static final String SEARCH_RESULT_EVENT_STATE_KEY = "DashscopeEmittedSearchResults";

    /**
     * 流式快照归一状态（按请求隔离，内部再按 choice 序号隔离；正文与思考各自独立判定）
     *
     * <p>方言实例是静态单例（{@link #getInstance()}），跨帧状态<b>不能</b>放实例字段，
     * 否则并发请求会共用同一份累积基准而互相串话。</p>
     */
    private static class SnapshotState {
        private final boolean deterministicSnapshot;
        private final Map<Integer, SnapshotDeltaNormalizer> contents = new HashMap<>();
        private final Map<Integer, SnapshotDeltaNormalizer> reasonings = new HashMap<>();
        private final Map<Integer, Map<String, SnapshotDeltaNormalizer>> toolArguments = new HashMap<>();

        SnapshotState(boolean deterministicSnapshot) {
            this.deterministicSnapshot = deterministicSnapshot;
        }

        SnapshotDeltaNormalizer content(int index) {
            return contents.computeIfAbsent(index, k -> new SnapshotDeltaNormalizer(deterministicSnapshot));
        }

        SnapshotDeltaNormalizer reasoning(int index) {
            return reasonings.computeIfAbsent(index, k -> new SnapshotDeltaNormalizer(deterministicSnapshot));
        }

        SnapshotDeltaNormalizer toolArguments(int choiceIndex, ONode toolCall, int position) {
            Map<String, SnapshotDeltaNormalizer> choiceArguments =
                    toolArguments.computeIfAbsent(choiceIndex, k -> new HashMap<>());
            String positionKey = "position:" + position;
            String index = identityValue(toolCall.getOrNull("index"));
            String id = identityValue(toolCall.getOrNull("id"));
            String indexKey = index == null ? null : "index:" + index;
            String idKey = id == null ? null : "id:" + id;

            SnapshotDeltaNormalizer normalizer = indexKey == null ? null : choiceArguments.get(indexKey);
            if (normalizer == null && idKey != null) {
                normalizer = choiceArguments.get(idKey);
            }
            if (normalizer == null) {
                normalizer = choiceArguments.get(positionKey);
            }
            if (normalizer == null) {
                normalizer = new SnapshotDeltaNormalizer(deterministicSnapshot);
            }

            if (indexKey != null) {
                choiceArguments.put(indexKey, normalizer);
            }
            if (idKey != null) {
                choiceArguments.put(idKey, normalizer);
            }
            // 数组位置是显式身份迟到或后续缺失时的桥接别名，必须始终与 index/id 指向同一状态。
            choiceArguments.put(positionKey, normalizer);

            return normalizer;
        }

        private String identityValue(ONode node) {
            if (node == null || node.isValue() == false) {
                return null;
            }
            String value = node.getString();
            return Utils.isEmpty(value) ? null : value;
        }
    }

    @Override
    public HttpUtils createHttpUtils(ChatConfig config, boolean isStream) {
        HttpUtils httpUtils = super.createHttpUtils(config, isStream);
        if (isStream) {
            httpUtils.header(HEADER_DASHSCOPE_SSE, "enable");
        }
        return httpUtils;
    }
    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        String standard = config.getStandardOrProvider();

        if ("dashscope".equalsIgnoreCase(standard)) {
            return true;
        } else {
            return config.getApiUrl().startsWith(URL_PREFIX);
        }
    }

    @Override
    public ONode buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        return new ONode().then(n -> {
            if (Utils.isNotEmpty(config.getModel())) {
                n.set("model", config.getModel());
            }

            n.getOrNew("input").getOrNew("messages").then(n1 -> {
                for (ChatMessage m1 : messages) {
                    ONode messageNode = buildChatMessageNode(config, m1);
                    if (!isEmptyAssistantReplay(m1, messageNode)) {
                        n1.add(messageNode);
                    }
                }
            });

            n.set("stream", isStream);

            n.getOrNew("parameters").then(n1 -> {
                Object thinkingSwitch = null;
                boolean hasReasoningEffort = false;
                    
                for (Map.Entry<String, Object> kv : options.options().entrySet()) {
                    String key = kv.getKey();
                    Object value = kv.getValue();
                    // 统一 thinking 开关 → DashScope 原生 parameters.enable_thinking
                    if ("thinking".equals(key) && value instanceof Boolean) {
                        thinkingSwitch = value;
                        n1.set("enable_thinking", value);
                        continue;
                    }
                    // 统一 reasoning_effort 不透传为 parameters 字段；仅作“开启思考”信号
                    if ("reasoning_effort".equals(key)) {
                        if (value != null) {
                            String e = String.valueOf(value).trim().toLowerCase();
                            if (!e.isEmpty() && !"auto".equals(e) && !"none".equals(e)) {
                                hasReasoningEffort = true;
                            }
                        }
                        continue;
                    }
                    n1.set(key, ONode.ofBean(value));
                }
            
                // 对齐 OpenCode：仅设 reasoning_effort 时隐式开启 enable_thinking；
                // thinking(false) 优先；已写出 enable_thinking 不覆盖
                if (hasReasoningEffort
                        && !Boolean.FALSE.equals(thinkingSwitch)
                        && !n1.hasKey("enable_thinking")) {
                    n1.set("enable_thinking", true);
                }
            
                n1.set("result_format", "message");

                // 流式增量输出：原生 DashScope 协议的 parameters.incremental_output 缺省为 false，
                // 即“每帧返回从头开始的全量快照”；而框架核心的累积器按流式协议只接受增量
                // （逐帧追加），两者叠加会使正文与 reasoning_content 逐帧重复累加。
                // 因此流式时显式开启，让服务端直接下发增量（工具调用的 arguments 同理，
                // 核心 ToolCallBuilder 也是按分片追加）。
                // 只在流式下写：该参数仅在流式模式生效，非流式带上无意义。
                // 用户已在 options 里显式指定时以用户值为准（上方 options 遍历已写入，此处不覆盖）。
                if (isStream && n1.hasKey("incremental_output") == false) {
                    n1.set("incremental_output", true);
                }

                //buildReqToolsNodeDo(n1, config.getDefaultTools());
                buildReqToolsNodeDo(n1, options.tools());
            });
        });
    }


    /**
     * 解析响应（事件形态）
     *
     * <p>DashScope 原生协议（{@code output.choices[].message}）的流式帧只承载内容分片
     * （正文 / 思考 / 工具调用分片），没有独立的生命周期或服务端工具事件，因此内容主干统一交由核心
     * <p>正文、思考与工具调用由方言直接翻译为 TEXT_DELTA / THINKING_DELTA / TOOL_CALL_*，
     * 并由 ChatAccumulator 统一聚合；错误事件同样通过 ChatStreamContext 发射。</p>
     *
     * <p><b>增量前提</b>：核心累积器按流式协议只接受增量（逐帧追加）。原生协议的
     * {@code parameters.incremental_output} 缺省为 false（每帧是从头开始的全量快照），
     * 所以请求侧已在流式时显式开启该参数（见 {@link #buildRequestJson}）；
     * 万一端点忽略该参数、或用户显式关掉它，{@link #normalizeSnapshotMessage} 会在方言边界
     * 把快照兜底转成增量，避免正文与 reasoning_content 逐帧重复累加。</p>
     *
     * @since 4.1
     */
    @Override
    public void parseResponseJson(ChatStreamContext ctx, String data) {
        ChatAccumulator acc = ctx.getAccumulator();

        if ("[DONE]".equals(data)) { //不是数据结构
            ctx.attrRemove(SNAPSHOT_STATE_KEY); //本步结束，释放跨帧累积基准
            ctx.attrRemove(SEARCH_RESULT_EVENT_STATE_KEY); //本步结束，释放搜索事件去重状态
            if (acc.isFinished() == false) {
                // 终态由事件归一化与 accumulator 快照表达，不再追加空 AssistantMessage。
                acc.setFinished(true);
            }
            return;
        }

        //解析（每帧只解析一次 JSON：正文解析与错误事件共用同一份节点）
        ONode oResp = ONode.ofJson(data);

        if (oResp.isObject() == false) {
            return;
        }

        parseFrameNode(ctx, acc, oResp);

        if (acc.getError() != null) {
            ctx.emit(ctx.event(org.noear.solon.ai.chat.event.ChatEventType.ERROR)
                    .rawType("error")
                    .error(acc.getError())
                    .raw(oResp)
                    .build());
        }
    }

    /**
     * 解析一帧（已解析好的 JSON 节点）
     *
     * @since 4.1
     */
    private void parseFrameNode(ChatStreamContext ctx, ChatAccumulator acc, ONode oResp) {
        if (oResp.hasKey("code") && !Utils.isEmpty(oResp.get("code").getString())) {
            acc.setError(new ChatException(oResp.get("code").getString() + ": " + oResp.get("message").getString()));
        } else {
            acc.setModel(ctx.getConfig().getModel());
            // 供应商响应标识（req-xxx）：百莲每个流式帧都携带，记录后本步事件自动预填
            ctx.setProviderResponseId(oResp.get("request_id").getString());

            ONode oOutput = oResp.get("output");
            // 百炼联网搜索：search_info 在 output 层级，需注入到 message 供 AbstractChatDialect 解析
            ONode oSearchInfo = oOutput != null ? oOutput.getOrNull("search_info") : null;
            ONode oSearchResults = (oSearchInfo != null && oSearchInfo.hasKey("search_results"))
                    ? oSearchInfo.get("search_results") : null;
            if (oOutput != null) {
                int choiceIndex = -1;
                for (ONode oChoice1 : oOutput.get("choices").getArray()) {
                    choiceIndex++;
                    String finish_reason = oChoice1.get("finish_reason").getString();
                    ONode oMessage = oChoice1.get("message");
                    if (oSearchResults != null) {
                        oMessage.set("search_results", oSearchResults);
                    }
                    //兜底：incremental_output 未生效时把全量快照转成增量（详见方法注释）
                    normalizeSnapshotMessage(ctx, choiceIndex, oMessage);

                    List<AssistantMessage> messageList = parseAssistantMessage(acc, oMessage);

                    for (AssistantMessage msg1 : messageList) {
                        if (acc.isStream()) {
                            // 流式主干直接进入事件通道，聚合由 ChatAccumulator.acceptEvent 统一完成。
                            publishAssistantMessageEvents(ctx, msg1);
                            publishSearchResultEvents(ctx, msg1, oSearchResults, choiceIndex);
                        } else {
                            acc.setTerminalMessage(msg1);
                        }
                    }

                    if (Utils.isNotEmpty(finish_reason)) {
                        acc.setFinished(true);
                        acc.lastFinishReason = finish_reason;
                    }

                    // ChatResponse 是单结果模型：只消费首个 choice，避免多候选混入同一累积器。
                    break;
                }
            }

            if (acc.isFinished()) {
                // 流式事件已由 ChatEventNormalizer 在终态补齐边界；不再用空消息制造结束信号。
                if (acc.isStream() == false && acc.isTerminalMessagePresent() == false) {
                    acc.setTerminalMessage(new AssistantMessage(""));
                }
            }

            ONode oUsage = oResp.getOrNull("usage");
            if (oUsage != null) {
                long promptTokens = oUsage.get("input_tokens").getLong();
                long thinkTokens = oUsage.get("think_tokens").getLong();
                long completionTokens = oUsage.get("output_tokens").getLong();
                long totalTokens = oUsage.get("total_tokens").getLong();

                acc.setUsage(new AiUsage(promptTokens, thinkTokens, completionTokens, totalTokens, oUsage));
            }
        }
    }

    /**
     * 将 message 上已由核心解析的类型化搜索结果投影为流式事件。
     *
     * <p>{@code search_info.search_results} 在累计快照和完成帧中可能重复出现；去重状态挂在
     * 当前 {@link ChatStreamContext}，按 choice 与结果稳定身份只发一次。事件经
     * {@link ChatStreamContext#emit} 进入累积器，最终消息优先使用事件聚合结果，且不会回写旧
     * {@code searchResultsRaw}。</p>
     */
    private void publishSearchResultEvents(ChatStreamContext ctx, AssistantMessage message,
                                           ONode rawResults, int choiceIndex) {
        List<SearchResult> results = message.getSearchResults();
        if (Utils.isEmpty(results)) {
            return;
        }

        Set<String> emitted = ctx.attrIfAbsent(SEARCH_RESULT_EVENT_STATE_KEY,
                k -> new LinkedHashSet<String>());
        int ordinal = -1;
        for (SearchResult result : results) {
            ordinal++;
            if (result == null || emitted.add(searchResultEventKey(choiceIndex, ordinal, result)) == false) {
                continue;
            }

            ChatEventDefault.Builder event = ctx.event(ChatEventType.SEARCH_RESULT)
                    .rawType("search_info.search_results")
                    .itemId(result.getId())
                    .text(result.getTitle())
                    .searchResult(result);
            if (result.getIndex() != null) {
                event.index(result.getIndex());
            }
            if (rawResults != null && rawResults.isArray() && ordinal < rawResults.size()) {
                event.raw(rawResults.get(ordinal));
            }
            ctx.emit(event.build());
        }
    }

    /**
     * 搜索结果的流内稳定身份：供应商 id、index 优先；均缺失时按公共语义字段去重。
     */
    private String searchResultEventKey(int choiceIndex, int ordinal, SearchResult result) {
        String prefix = "choice:" + choiceIndex + ':';
        if (Utils.isNotEmpty(result.getId())) {
            return prefix + "id:" + result.getId();
        }
        if (result.getIndex() != null) {
            return prefix + "index:" + result.getIndex();
        }
        return prefix + "semantic:" + ordinal + '\u0000'
                + String.valueOf(result.getTitle()) + '\u0000'
                + String.valueOf(result.getUrl()) + '\u0000'
                + String.valueOf(result.getSnippet());
    }

    /**
     * 兜底：把“累计快照”帧归一为真正的流式增量（原地改写 message 节点）。
     *
     * <p>为何需要：原生 DashScope 协议的 {@code parameters.incremental_output} 缺省为 false，
     * 此时服务端每帧下发“从头开始的全量快照”（第 N 帧包含前 N-1 帧的全部内容），
     * 而核心累积器按协议无条件追加，两者叠加会使正文与 reasoning_content 逐帧重复累加。
     * 主修在请求侧（流式时显式 {@code incremental_output=true}），本方法只处理“参数未生效”的残局：
     * 用户显式将其设为 false（尊重用户值）、或中转/旧版端点忽略了该参数。</p>
     *
     * <p>不会双重处理：{@link SnapshotDeltaNormalizer} 只在“本帧是已交付文本的前缀延伸”时才截前缀，
     * 服务端已给增量（本帧不以累积文本开头）时原文原样返回，因此与方案 A 可以共存。</p>
     *
     * <p>跨帧状态挂在 {@link ChatStreamContext#attrIfAbsent} 上（每步一个上下文），
     * 而不是方言实例字段：方言是静态单例，实例字段会让并发请求串话。</p>
     *
     * <p>覆盖范围：string 形态的 content、reasoning_content/reasoning，以及
     * message.tool_calls[].function.arguments。工具参数状态按 choice 与调用身份双重隔离；
     * 调用身份只使用 index、稳定 id 或数组位置，绝不使用可能相同的函数名。</p>
     *
     * <p>用户显式设置 {@code incremental_output=false} 时，服务端语义是确定的累计快照，
     * 因而从首帧开始使用确定性模式，可正确处理很短的首快照并丢弃重复终帧；未显式关闭时
     * 继续使用保守阈值启发式，避免把真正的 arguments 增量误截为快照。</p>
     *
     * @param index choice 序号（n&gt;1 时各路 choice 的累积基准必须隔离）
     * @since 4.1
     */
    private void normalizeSnapshotMessage(ChatStreamContext ctx, int index, ONode oMessage) {
        if (ctx.isStream() == false) {
            return; //非流式：一帧即全部，截前缀只会丢内容
        }

        if (oMessage == null || oMessage.isObject() == false) {
            return;
        }

        ONode oContent = oMessage.getOrNull("content");
        //只对 string 形态的 content 做判定（多模态数组不是文本追加语义）
        String contentRaw = (oContent != null && oContent.isValue()) ? oContent.getString() : null;

        //推理字段名与核心解析保持同一优先级：reasoning_content 优先，其次 reasoning
        String reasoningKey = oMessage.hasKey("reasoning_content") ? "reasoning_content"
                : (oMessage.hasKey("reasoning") ? "reasoning" : null);
        String reasoningRaw = reasoningKey == null ? null : oMessage.get(reasoningKey).getString();

        ONode oToolCalls = oMessage.getOrNull("tool_calls");
        boolean hasToolCalls = oToolCalls != null && oToolCalls.isArray() && oToolCalls.getArray().isEmpty() == false;
        if (Utils.isEmpty(contentRaw) && Utils.isEmpty(reasoningRaw) && hasToolCalls == false) {
            return; //无可归一化字段
        }

        SnapshotState state = ctx.attrIfAbsent(SNAPSHOT_STATE_KEY,
                k -> new SnapshotState(isExplicitSnapshotMode(ctx)));

        if (Utils.isNotEmpty(contentRaw)) {
            String contentDelta = state.content(index).normalize(contentRaw);
            if (contentRaw.equals(contentDelta) == false) {
                //原地改写：该节点本轮解析完即弃，无需深拷贝
                oMessage.set("content", contentDelta);
            }
        }

        if (Utils.isNotEmpty(reasoningRaw)) {
            String reasoningDelta = state.reasoning(index).normalize(reasoningRaw);
            if (reasoningRaw.equals(reasoningDelta) == false) {
                oMessage.set(reasoningKey, reasoningDelta);
            }
        }

        if (hasToolCalls) {
            int callPosition = -1;
            for (ONode oToolCall : oToolCalls.getArray()) {
                callPosition++;
                ONode oFunction = oToolCall.getOrNull("function");
                if (oFunction == null || oFunction.isObject() == false) {
                    continue;
                }

                ONode oArguments = oFunction.getOrNull("arguments");
                if (oArguments == null || oArguments.isString() == false) {
                    continue; //只处理字符串参数；对象形态不是流式字符串追加语义
                }

                String argumentsRaw = oArguments.getString();
                if (Utils.isEmpty(argumentsRaw)) {
                    continue;
                }

                String argumentsDelta = state.toolArguments(index, oToolCall, callPosition).normalize(argumentsRaw);
                if (argumentsRaw.equals(argumentsDelta) == false) {
                    oFunction.set("arguments", argumentsDelta);
                }
            }
        }
    }

    /**
     * 用户显式关闭增量输出时，响应必按累计快照解释；缺省或 true 则保持保守启发式。
     */
    private boolean isExplicitSnapshotMode(ChatStreamContext ctx) {
        return ctx.getRequest() != null
                && ctx.getRequest().getOptions() != null
                && Boolean.FALSE.equals(ctx.getRequest().getOptions().option("incremental_output"));
    }


    @Override
    protected void buildUserMessageNodeDo(ChatConfig config, ONode oNode, UserMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());

        // 与 Assistant 对齐：多模态才用原生 content 数组；纯文本保持 string（兼容文本模型）
        if (msg.isMultiModal()) {
            ONode contentNode = new ONode().then(n -> {
                appendDashscopeContentBlocks(n, msg.getBlocks(), msg.getContent());
            });
            // Session 截断后媒体全不可播时，避免写出空 content 数组
            if (contentNode.getArray() != null && contentNode.getArray().isEmpty()) {
                oNode.set("content", msg.getContent() != null ? msg.getContent() : "");
            } else {
                oNode.set("content", contentNode);
            }
        } else {
            oNode.set("content", msg.getContent() != null ? msg.getContent() : "");
        }
    }

    /**
     * Assistant 回传对齐 DashScope 原生 content：
     * 多模态用 [{image|audio|video|text}]；单模态保持 string（兼容文本模型）。
     *
     * @since 3.9
     */
    @Override
    protected void buildAssistantMessageNodeDo(ChatConfig config, ONode oNode, AssistantMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());

        if (msg.isMultiModal()) {
            ONode contentNode = new ONode().then(n -> {
                appendDashscopeContentBlocks(n, msg.getBlocks(), msg.getText());
                // 若块全为空，补文本投影避免空 content
                if (n.getArray() != null && n.getArray().isEmpty()
                        && Utils.isNotEmpty(msg.getText())) {
                    n.addNew().set("text", msg.getText());
                }
            });
            if (contentNode.getArray() != null && contentNode.getArray().isEmpty() == false) {
                oNode.set("content", contentNode);
            }
        } else {
            // 单模态：保持原 string 行为
            if (Utils.isNotEmpty(msg.getText())) {
                oNode.set("content", msg.getText());
            }
        }

        // 默认不回传 reasoning_content（百炼多轮建议）；
        // 仅当 options 显式需要时由上层注入到消息 metadata。
        // 这里不写 reasoning，与父类 OpenAI 风格区分。

        List<Map> outboundToolCalls = ToolCallJsonSanitizer.buildOpenAiCompatibleToolCalls(
                msg.getToolCalls(), msg.getToolCallsRaw());
        if (Utils.isNotEmpty(outboundToolCalls)) {
            oNode.set("tool_calls", ONode.ofBean(outboundToolCalls));
        }
    }

    /**
     * DashScope 原生历史不接收 assistant thinking。按最终线报文判断：移除 thinking 与截断媒体后，
     * 仅剩 role 的 assistant 必须过滤；仍有正文或工具调用的消息继续保留。
     */
    private boolean isEmptyAssistantReplay(ChatMessage message, ONode messageNode) {
        return message instanceof AssistantMessage
                && messageNode.hasKey("content") == false
                && messageNode.hasKey("tool_calls") == false;
    }

    /**
     * 按 DashScope 原生结构追加 content 块（image/audio/video/text）。
     */
    private void appendDashscopeContentBlocks(ONode contentArray, List<ContentBlock> blocks, String textFallback) {
        boolean hasTextBlock = false;

        if (Utils.isNotEmpty(blocks)) {
            for (ContentBlock block1 : blocks) {
                if (block1 instanceof ImageBlock) {
                    if (!isMediaBlockPlayable(block1)) {
                        continue;
                    }
                    String image = block1.toDataString(true);
                    if (Utils.isNotEmpty(image)) {
                        contentArray.addNew().set("image", image);
                    }
                } else if (block1 instanceof AudioBlock) {
                    if (!isMediaBlockPlayable(block1)) {
                        continue;
                    }
                    String audio = block1.toDataString(true);
                    if (Utils.isNotEmpty(audio)) {
                        contentArray.addNew().set("audio", audio);
                    }
                } else if (block1 instanceof VideoBlock) {
                    if (!isMediaBlockPlayable(block1)) {
                        continue;
                    }
                    String video = block1.toDataString(true);
                    if (Utils.isEmpty(video)) {
                        continue;
                    }
                    ONode videoNode = contentArray.addNew();
                    videoNode.set("video", video);
                    // 可选 VL 参数
                    if (block1.metas() != null) {
                        Object fps = block1.metas().get("fps");
                        if (fps != null) {
                            videoNode.set("fps", ONode.ofBean(fps));
                        }
                        Object maxFrames = block1.metas().get("max_frames");
                        if (maxFrames != null) {
                            videoNode.set("max_frames", ONode.ofBean(maxFrames));
                        }
                    }
                } else if (block1 instanceof TextBlock) {
                    String text = block1.getContent();
                    if (Utils.isNotEmpty(text)) {
                        contentArray.addNew().set("text", text);
                        hasTextBlock = true;
                    }
                }
            }
        }

        // 文本投影：若 blocks 中无 TextBlock，补 fallback
        if (!hasTextBlock && Utils.isNotEmpty(textFallback)) {
            contentArray.addNew().set("text", textFallback);
        }
    }
}
