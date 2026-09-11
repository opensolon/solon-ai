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
package org.noear.solon.ai.llm.dialect.anthropic;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.chat.tool.ToolCallJsonSanitizer;
import org.noear.solon.ai.chat.content.BlobBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude 请求构建器
 * @author oisin lu
 * @date 2026年1月27日
 */
public class AnthropicRequestBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(AnthropicRequestBuilder.class);

    /**
     * thinking=true / Map 简化入口未给预算时的默认思考预算。
     * <p>必须始终小于 max_tokens（见 {@link #clampThinkingBudgetToMaxTokens}）。</p>
     *
     * @since 4.1
     */
    private static final int DEFAULT_THINKING_BUDGET = 10000;

    /**
     * Anthropic 每请求的 cache_control 断点上限。
     * <p>静态前缀（system 或 tools）占 1 个，其余全部让给对话历史滚动断点。</p>
     *
     * @since 4.0.4
     */
    private static final int CACHE_BREAKPOINT_LIMIT = 4;

    /**
     * 不能承载 cache_control 的内容块类型。
     *
     * <p>协议上 {@code ThinkingBlockParam} 与 {@code RedactedThinkingBlockParam} 没有 cache_control 字段
     * （而 {@code TextBlockParam} / {@code ToolUseBlockParam} / {@code ToolResultBlockParam} 等都有），
     * 挂上去会被服务端按 schema 直接拒掉。触发路径：只有 thinking（无正文、无 tool_calls）的
     * assistant 消息落在滚动窗口内。</p>
     *
     * @since 4.1
     */
    private static final Set<String> CACHE_CONTROL_UNSUPPORTED_BLOCKS = new HashSet<>(
            Arrays.asList("thinking", "redacted_thinking"));

    /**
     * Anthropic 允许的 {@code cache_control.ttl} 取值（协议 CacheControlEphemeral.Ttl）。
     *
     * @since 4.1
     */
    private static final Set<String> CACHE_TTL_ALLOWED = new HashSet<>(Arrays.asList("5m", "1h"));

    /**
     * 工具定义里不允许被旁路配置改写的字段。
     *
     * <p>前三个由 {@link FunctionTool} 自身决定（改写会让工具名与实际可调用的函数错位）；
     * {@code cache_control} 由缓存断点预算统一协调（超预算会整条 400）。</p>
     *
     * @since 4.1
     */
    private static final Set<String> TOOL_RESERVED_FIELDS = new HashSet<>(
            Arrays.asList("name", "description", "input_schema", "cache_control"));

    /**
     * OpenAI 风格但 Anthropic Messages API 不接受的顶层选项键。
     *
     * <p>协议 {@code MessageCreateParams} 顶层只接受 model / messages / max_tokens / cache_control /
     * container / inference_geo / metadata / output_config / service_tier / stop_sequences / system /
     * temperature / thinking / tool_choice / tools / top_k / top_p。而统一 API（{@code ModelOptionsAmend}）
     * 对外暴露了 {@code frequency_penalty()} / {@code presence_penalty()} / {@code response_format()} 等
     * OpenAI 风格 setter，任一被调用就会透传成非法字段导致 400。</p>
     *
     * <p>用黑名单而非白名单：白名单会把 Anthropic 后续新增的合法字段一并拦掉，不利于向前兼容。</p>
     *
     * @since 4.1
     */
    private static final Set<String> UNSUPPORTED_OPTION_KEYS = new HashSet<>(Arrays.asList(
            "frequency_penalty", "presence_penalty", "logit_bias", "logprobs", "top_logprobs",
            "n", "seed", "response_format", "stream_options",
            "prompt_cache_key", "max_completion_tokens"));

    /**
     * 由方言自身消费、不进请求体的合成选项键。
     *
     * <p>{@code structured_outputs} / {@code strict_tools} / {@code anthropic_tools} 是本方言给出的开关
     * （协议无同名字段）；{@code anthropic_beta} / {@code betas} 在协议上走请求头协商——GA 的
     * {@code MessageCreateParams} 并没有 betas 字段（只有 beta 客户端的 {@code BetaMessageCreateParams}
     * 才有，且同样落在头上），原样透传会变成非法顶层字段。</p>
     *
     * @since 4.1
     */
    private static final Set<String> DIALECT_ONLY_OPTION_KEYS = new HashSet<>(Arrays.asList(
            "structured_outputs", "strict_tools", "anthropic_tools", "anthropic_beta", "betas"));

    /**
     * 逐工具的 GA 协议字段配置入口（{@code Map<toolName, Map<field, value>>}）。
     *
     * <p>协议 {@code Tool} 除 name/description/input_schema 外还有 {@code defer_loading} /
     * {@code eager_input_streaming} / {@code allowed_callers} / {@code input_examples} 四个 GA 字段，
     * 而统一 API 的 {@link FunctionTool} 没有对应载体。</p>
     *
     * <p><b>为什么不用 {@code FunctionTool.meta()}</b>：{@code descriptionAndMeta()} 会把 meta 里的每一项
     * 拼成 {@code [key:value]} 前缀推给模型，把协议字段放进去等于污染工具描述。</p>
     *
     * <p><b>为什么不做全局开关</b>：{@code defer_loading=true} 让工具定义不进初始 prompt
     * （靠 tool_search 再拉取），一刷全开会直接弄丢工具可见性；{@code input_examples} 本质上就是
     * per-tool 的。因此只提供按工具名寻址的单一入口，且值直接透传（新增协议字段无需改代码）。</p>
     *
     * @since 4.1
     */
    private static final String TOOL_CONFIG_OPTION_KEY = "anthropic_tools";

    /**
     * 声明 beta 能力的选项键（值可为逗号分隔串、集合或数组）。
     *
     * @since 4.1
     */
    private static final String[] BETA_OPTION_KEYS = {"anthropic_beta", "betas"};

    /**
     * 原生结构化输出（{@code output_config.format}）的最低模型代次：Claude 4.5。
     *
     * <p>官方支持列表为 sonnet-4-5 / opus-4-5 / haiku-4-5 及其后的 4.6+ / 5+ / fable-5 / mythos 系列。
     * 更早的模型（claude-3-5-sonnet、claude-sonnet-4、opus-4-1 等）传 {@code output_config.format} 会被拒，
     * 需退回“把 schema 写进 system 提示词”的兜底路径。</p>
     *
     * @since 4.1
     */
    private static final int STRUCTURED_OUTPUT_MIN_VERSION = 405;

    /**
     * 正序命名的模型代次：{@code claude-sonnet-4-5-20250929} / {@code claude-opus-4.7}。
     *
     * <p>主次版本都限定 1-2 位并加数字负向预查，否则 {@code claude-sonnet-4-20250514} 的日期串
     * 会被当成次版本号（20250514），把 Sonnet 4 误判为 4.5+。</p>
     *
     * @since 4.1
     */
    private static final java.util.regex.Pattern CLAUDE_VERSION_PATTERN = java.util.regex.Pattern.compile(
            "(?:opus|sonnet|haiku)[.-](\\d{1,2})(?![\\d])(?:[.-](\\d{1,2})(?![\\d.]))?",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 倒置命名的模型代次（SAP / 部分网关）：{@code claude-4.7-opus} / {@code claude-3-5-sonnet}。
     *
     * @since 4.1
     */
    private static final Pattern CLAUDE_VERSION_INVERTED_PATTERN = java.util.regex.Pattern.compile(
            "claude[.-](\\d{1,2})(?![\\d])(?:[.-](\\d{1,2})(?![\\d.]))?[.-](?:opus|sonnet|haiku)",
            Pattern.CASE_INSENSITIVE);

    /** opus-4.7+ 正序命名（含 opus-4-7 / opus-4.8 等后续代次）。 @since 4.0.4 */
    private static final Pattern OPUS_VERSION_PATTERN = Pattern.compile(
            "opus-(\\d+)[.-](\\d+)(?:[.@-]|$)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** claude-4.7-opus 倒置命名（SAP 等网关）。 @since 4.0.4 */
    private static final Pattern OPUS_VERSION_INVERTED_PATTERN = Pattern.compile(
            "claude-(\\d+)[.-](\\d+)-opus(?:[.@-]|$)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** sonnet-5+ 正序命名。 @since 4.0.4 */
    private static final Pattern SONNET_VERSION_PATTERN = Pattern.compile(
            "sonnet-(\\d+)(?:[.@-]|$)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** claude-5-sonnet 倒置命名。 @since 4.0.4 */
    private static final Pattern SONNET_VERSION_INVERTED_PATTERN = Pattern.compile(
            "claude-(\\d+)-sonnet(?:[.@-]|$)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 仅过滤没有正文、工具、媒体或 Anthropic 回放状态的纯思考历史。 */
    private boolean isSkippableThinkingOnlyMessage(ChatMessage message) {
        if (message instanceof AssistantMessage == false) {
            return false;
        }

        AssistantMessage assistant = (AssistantMessage) message;
        if (assistant.getText() != null && !assistant.getText().isEmpty()) {
            return false;
        }
        if (assistant.isThinkingOnly() == false) {
            return false;
        }

        // 应用 metadata 与其他方言状态都不能让空 Anthropic assistant 消息进入请求。
        return AnthropicMessageStateSupport.hasReplayableState(assistant) == false;
    }

    /**
     * 构建请求 JSON
     * @author oisin lu
     * @param config   聊天配置
     * @param options  聊天选项
     * @param messages 对话消息列表
     * @param isStream 是否使用流式模式
     * @return 符合 Messages API 规范的 JSON 字符串
     */
    public ONode build(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        ONode root = new ONode();

        if (Utils.isNotEmpty(config.getModel())) {
            root.set("model", config.getModel());
        }

        // Claude max_tokens 是必要参数
        // 默认最大输出token数，AWS 32000，ANTHROPIC 64000
        // 统一 API 的 max_completion_tokens（OpenAI 风格别名）在此并入，否则它会被当作非法顶层字段透传
        Object maxTokensOpt = options.options().get("max_tokens");
        if (maxTokensOpt == null) {
            maxTokensOpt = options.options().get("max_completion_tokens");
        }
        root.set("max_tokens", maxTokensOpt == null ? 32000 : maxTokensOpt);

        // 缓存控制：仅 Anthropic 风格（type 非空）才在请求体上打 cache_control 断点；
        // prompt_cache_key（OpenAI/DeepSeek 风格）不适用于 Claude，忽略之。
        CacheControl cacheControl = options.cacheControl();
        // 用户直接在 options 里给了 Anthropic 原生顶层 cache_control（协议 MessageCreateParams.cache_control：
        // 服务端自动给请求里最后一个可缓存块加断点）。此时必须让出方言自己的块级断点：
        // 两套机制叠加会超出每请求 CACHE_BREAKPOINT_LIMIT 个断点的上限而整条 400，
        // 且两条通道互不知情，实际生效位置不可控。顶层字段本身仍按合法 GA 字段透传
        boolean nativeAutoCache = options.options().get("cache_control") != null;
        boolean cacheEnabled = nativeAutoCache == false
                && (cacheControl != null && Utils.isNotEmpty(cacheControl.getType()));

        // 提取系统消息（供缓存预算判断与下方 system 节点构建复用，避免重复遍历）
        String systemMessage = extractSystemMessage(messages);
        boolean hasSystem = Utils.isNotEmpty(systemMessage);
        boolean hasTools = !Utils.isEmpty(options.tools()) || hasNativeTools(options);

        // 缓存断点预算分配（Anthropic 每请求上限 CACHE_BREAKPOINT_LIMIT 个）：
        // 渲染顺序为 tools -> system -> messages，打在 system 上的断点会连带缓存 tools。
        // 因此静态前缀只需 1 个断点：有 system 就打 system（自动覆盖 tools），
        // 没有 system 才退化为打最后一个 tool。省下的预算全部让给滚动历史断点，
        // 增强对 20-block 回溯窗口的抗性（工具密集的 agent 循环里尤为关键）。
        boolean cacheOnTools = cacheEnabled && !hasSystem && hasTools;
        // 静态断点数：有 system 则落在 system；否则若有 tools 则落在 tools；两者皆无则为 0
        int staticBreakpoints = (cacheEnabled && (hasSystem || hasTools)) ? 1 : 0;

        if (Utils.isNotEmpty(systemMessage)) {
            if (cacheEnabled) {
                // 启用 Prompt Caching：system prompt 作为 content blocks 数组，最后一块添加 cache_control
                ONode systemNode = new ONode();
                systemNode.addNew().then(contentBlock->{
                    contentBlock.set("type", "text");
                    contentBlock.set("text", systemMessage);
                    writeCacheControl(contentBlock, cacheControl);
                });

                root.set("system", systemNode);
            } else {
                root.set("system", systemMessage);
            }
        }

        // 构建消息数组，过滤掉系统消息
        ONode messagesNode = root.getOrNew("messages").asArray();
        ONode pendingToolResultNode = null; // 用于合并连续的 ToolMessage
        for (ChatMessage message : messages) {
            // 历史通常是完整消息：只过滤无正文/工具/媒体且无回放载体的纯思考消息。
            if (message instanceof SystemMessage || isSkippableThinkingOnlyMessage(message)) {
                continue;
            }

            if (message instanceof ToolMessage) {
                if (pendingToolResultNode == null) {
                    pendingToolResultNode = new ONode();
                    pendingToolResultNode.set("role", "user");
                    pendingToolResultNode.getOrNew("content").asArray();
                }
                ToolMessage toolMessage = (ToolMessage) message;
                pendingToolResultNode.get("content").add(buildToolResultBlock(toolMessage));
            } else {
                if (pendingToolResultNode != null) {
                    messagesNode.add(pendingToolResultNode);
                    pendingToolResultNode = null;
                }
                messagesNode.add(buildMessageNode(message));
            }
        }
        if (pendingToolResultNode != null) {
            messagesNode.add(pendingToolResultNode);
        }

        // 在最后若干条消息末尾补断点，让历史前缀随对话增量进入缓存。
        // 配额 = 4 - 静态断点数，把预算尽量倾斜给历史，增强 20-block 窗口抗性。
        if (cacheEnabled) {
            int rollingQuota = CACHE_BREAKPOINT_LIMIT - staticBreakpoints;
            applyMessageCacheBreakpoints(messagesNode, cacheControl, rollingQuota);
        }

        // 设置流式模式参数
        if (isStream) {
            root.set("stream", true);
        }

        // 添加其他选项
        Object thinkingSwitch = null;
        for (Map.Entry<String, Object> kv : options.options().entrySet()) {
            String key = kv.getKey();
            // 跳过Claude特有的字段，或已处理的字段
            if ("stream".equals(key) || "max_tokens".equals(key)) {
                continue;
            }
            
            // 统一思考开关（Boolean）延后与 reasoning_effort 一起处理
            if ("thinking".equals(key) && kv.getValue() instanceof Boolean) {
                thinkingSwitch = kv.getValue();
                continue;
            }
            
            // 统一推理水平 → thinking.budget_tokens（若尚未显式配置 thinking）
            if ("reasoning_effort".equals(key)) {
                // 与 Boolean thinking 一起在循环后处理，避免顺序依赖
                continue;
            }
            
            // 处理思考模式配置（Map / Number 等供应商原生形态）
            if ("thinking".equals(key)) {
                buildThinkingNode(root, kv.getValue());
                continue;
            }
            
            // 处理tool_choice（需要从OpenAI格式转换为Anthropic格式）
            if ("tool_choice".equals(key)) {
                buildToolChoiceNode(root, kv.getValue());
                continue;
            }

            // 命名差异：统一 API 的 stop → Anthropic stop_sequences（协议只认后者）
            if ("stop".equals(key)) {
                writeStopSequences(root, kv.getValue());
                continue;
            }

            // 命名差异：统一 API 的 user → Anthropic metadata.user_id（顶层无 user 字段）
            if ("user".equals(key)) {
                if (kv.getValue() instanceof String && Utils.isNotEmpty((String) kv.getValue())) {
                    root.getOrNew("metadata").set("user_id", (String) kv.getValue());
                }
                continue;
            }

            // 结构差异：统一 API 的 parallel_tool_calls → 协议 tool_choice.disable_parallel_tool_use
            // （循环后统一处理：它与 tool_choice 的出现顺序不定，必须等 tool_choice 建好再挂上去）
            if ("parallel_tool_calls".equals(key)) {
                continue;
            }

            if ("tools".equals(key) || "server_tools".equals(key) || "anthropic_server_tools".equals(key)) {
                if (!root.hasKey("tools")) root.set("tools", ONode.ofBean(kv.getValue()));
                continue;
            }

            // OpenAI 风格但 Anthropic 不接受的字段：在出站前剔除，否则整条请求 400
            if (UNSUPPORTED_OPTION_KEYS.contains(key)) {
                continue;
            }

            // 方言自身消费的合成选项（含走请求头的 beta 声明）不进请求体
            if (DIALECT_ONLY_OPTION_KEYS.contains(key)) {
                continue;
            }

            root.set(key, ONode.ofBean(kv.getValue()));
        }
        
        // 统一 thinking 开关 + reasoning_effort（显式 Map/Number thinking 优先）
        applyUnifiedThinkingOptions(root, config, options, thinkingSwitch);
        // 所有经典 thinking 入口统一执行协议约束：budget_tokens >= 1024 且严格小于 max_tokens。
        // adaptive / disabled 不带 budget_tokens，会自然跳过。
        clampThinkingBudgetToMaxTokens(root, options);
        
        // 如果用户未显式设置 tool_choice 但有 tools，默认使用 auto 语义（由API自行决定）
        if (!options.options().containsKey("tool_choice") && Utils.isEmpty(options.tools()) && hasNativeTools(options) == false) {
            // Anthropic 默认行为等同于 auto，无需显式设置
        }

        buildToolsNode(root, options, cacheOnTools);

        // 统一 API 的 parallel_tool_calls → tool_choice.disable_parallel_tool_use（需 tool_choice 已建）
        applyParallelToolUse(root, options);

        // 代码执行容器复用：从历史里取回上一轮的 container 回填顶层
        applyContainer(root, messages);

        // 原生结构化输出（output_config.format）：放在 thinking 之后，getOrNew 与 adaptive 的 effort 合并共存
        applyStructuredOutput(root, config, options);

        return root;
    }

    /**
     * 统一 API 的 {@code parallel_tool_calls=false} → 协议 {@code tool_choice.disable_parallel_tool_use=true}。
     *
     * <p>旧实现把 {@code parallel_tool_calls} 归入不支持选项直接丢弃，但协议上
     * {@code ToolChoiceAuto} / {@code ToolChoiceAny} / {@code ToolChoiceTool} 三个变体都带
     * {@code disable_parallel_tool_use}，语义可直接映射——丢弃等于让用户的「禁止并行」诉求静默失效。</p>
     *
     * <p>三条边界：{@code true} 不写出（并行本就是默认）；无 tools 且无 tool_choice 时不写（字段无意义，
     * 且凭空冒出一个 tool_choice 会改变请求语义）；{@code type=none} 不写（协议 {@code ToolChoiceNone}
     * 没有该字段，本轮不调工具也无并行可言，写上去会被 schema 拒掉）。</p>
     *
     * @since 4.1
     */
    private void applyParallelToolUse(ONode root, ChatOptions options) {
        Object flag = options.options().get("parallel_tool_calls");
        if (flag == null) {
            return;
        }
        if (Boolean.FALSE.equals(flag) == false && "false".equalsIgnoreCase(String.valueOf(flag)) == false) {
            return;
        }

        boolean hasToolChoice = root.hasKey("tool_choice");
        if (hasToolChoice == false && Utils.isEmpty(options.tools()) && hasNativeTools(options) == false) {
            return;
        }

        ONode tcNode = root.getOrNew("tool_choice");
        if (tcNode.hasKey("type") == false) {
            //未显式指定时 Anthropic 默认行为等同 auto，这里补写出来以承载开关
            tcNode.set("type", "auto");
        }
        if ("none".equals(tcNode.get("type").getString())) {
            return;
        }
        tcNode.set("disable_parallel_tool_use", true);
    }

    /**
     * 从对话历史里取回代码执行容器并回填顶层 {@code container}。
     *
     * <p>协议 {@code MessageCreateParams.container} 接受容器 id 字符串（或 {@code ContainerParams} 对象），
     * 回传后下一轮代码执行才能复用同一个沙盒（文件、已安装依赖、工作目录全都在里面）。
     * 不回传就是每轮开新容器，上一轮的产物全丢。</p>
     *
     * <p>取值链路：解析器把响应的 {@code container} 写进 {@code AssistantMessage.contentRaw}
     * （见 {@code AnthropicResponseParser.CONTAINER_RAW_KEY}），此处从最近一条 assistant 消息取回。
     * 用户已显式给顶层 {@code container}（原生形态透传）时不覆盖。</p>
     *
     * @since 4.1
     */
    private void applyContainer(ONode root, List<ChatMessage> messages) {
        if (root.hasKey("container") || Utils.isEmpty(messages)) {
            return;
        }

        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message instanceof AssistantMessage == false) {
                continue;
            }
            Map<String, Object> stateData = AnthropicMessageStateSupport.resolveData((AssistantMessage) message);
            if (stateData == null) {
                continue;
            }
            Object value = stateData.get(AnthropicResponseParser.CONTAINER_RAW_KEY);
            String containerId = resolveContainerId(value);
            if (Utils.isEmpty(containerId)) {
                continue;
            }

            root.set("container", containerId);
            return;
        }
    }

    /**
     * 从响应的 {@code Container} JSON 里取容器 id（兼容网关直接给字符串 id 的形态）。
     *
     * <p>只回传 id 而不把整个对象原样送回：响应的 {@code Container} 带 {@code expires_at}，
     * 而请求侧的 {@code ContainerParams} 只有 {@code id} / {@code skills}，多余字段会被 schema 拒掉。</p>
     *
     * @since 4.1
     */
    private static String resolveContainerId(Object containerValue) {
        if (containerValue == null) {
            return null;
        }
        if (containerValue instanceof Map) {
            Object id = ((Map<?, ?>) containerValue).get("id");
            return id == null ? null : String.valueOf(id);
        }
        String trimmed = String.valueOf(containerValue).trim();
        if (trimmed.startsWith("{") == false) {
            return trimmed;
        }
        try {
            ONode node = ONode.ofJson(trimmed);
            return node == null ? null : node.get("id").getString();
        } catch (Exception e) {
            //不能因为一个可选的容器复用字段把整条请求打挂
            return null;
        }
    }

    /**
     * 原生结构化输出：{@code output_config.format = {type:"json_schema", schema:...}}。
     *
     * <p>这是 GA 形态（对齐 {@code OutputConfig.format} / {@code JsonOutputFormat}），<b>不需要</b>
     * {@code anthropic-beta} 头；早期 beta 的 {@code output_format} 顶层字段已被取代。</p>
     *
     * <p>门控三层，缺一层都会把原本可用的请求打成 400：</p>
     * <ol>
     *   <li>{@code structured_outputs=false} 显式退回“schema 写进 system 提示词”的兜底路径
     *       （网关不支持该字段时的逃生门）；{@code true} 则跳过模型判定强制启用；</li>
     *   <li>未显式指定时按模型代次判定（见 {@link #STRUCTURED_OUTPUT_MIN_VERSION}）；</li>
     *   <li>用户已自带 {@code output_config.format}（原生形态透传）时不覆盖。</li>
     * </ol>
     *
     * <p><b>兜底提示词仍会保留</b>：核心在调用本方言前已把 schema 追加进 system
     * （{@code prepareOutputSchemaInstruction} 先于 {@code prepareOutputFormatOptions} 执行，
     * 且方言拿不到那个 StringBuilder），无法回收。原生约束与提示词描述语义一致，只是多花些输入 token。</p>
     *
     * @since 4.1
     */
    private void applyStructuredOutput(ONode root, ChatConfig config, ChatOptions options) {
        if (options == null || Utils.isEmpty(options.outputSchema())) {
            return;
        }

        Object toggle = options.options().get("structured_outputs");
        boolean forced = false;
        if (toggle != null) {
            if (Boolean.FALSE.equals(toggle) || "false".equalsIgnoreCase(String.valueOf(toggle))) {
                return;
            }
            forced = Boolean.TRUE.equals(toggle) || "true".equalsIgnoreCase(String.valueOf(toggle));
        }

        if (forced == false && supportsStructuredOutputs(config) == false) {
            return;
        }

        ONode outputConfig = root.getOrNull("output_config");
        if (outputConfig != null && outputConfig.hasKey("format")) {
            return;
        }

        ONode schemaNode;
        try {
            schemaNode = ONode.ofJson(options.outputSchema());
        } catch (Exception e) {
            // schema 不是合法 JSON：静默退回提示词兜底，不能让结构化诉求把整条请求打挂
            return;
        }
        if (schemaNode == null || schemaNode.isObject() == false) {
            return;
        }

        normalizeStructuredSchema(schemaNode);

        root.getOrNew("output_config").getOrNew("format")
                .set("type", "json_schema")
                .set("schema", schemaNode);
    }

    /**
     * 把 schema 就地补齐为 Anthropic strict JSON Schema 要求的形态。
     *
     * <p>同时用于 {@code output_config.format.schema} 与 {@code tools[].input_schema + strict=true}。
     * 对象必须显式 {@code additionalProperties:false}，且 {@code required} 必须列全所有属性；
     * 可选字段应通过 nullable 类型表达，而不是从 required 中省略。</p>
     *
     * <p>不动数值/字符串长度约束（{@code minimum} / {@code maxLength} 等）：协议声明不支持，
     * 但删掉会静默削弱用户 schema 的语义，宁可让服务端报错、由 {@code structured_outputs=false} 兜住。</p>
     *
     * @since 4.1
     */
    private static void normalizeStructuredSchema(ONode node) {
        if (node == null) {
            return;
        }

        if (node.isArray()) {
            for (ONode item : node.getArray()) {
                normalizeStructuredSchema(item);
            }
            return;
        }
        if (node.isObject() == false) {
            return;
        }

        ONode properties = node.getOrNull("properties");
        boolean hasProperties = properties != null && properties.isObject();
        ONode typeNode = node.getOrNull("type");
        boolean typedObject = typeNode != null && typeNode.isString() && "object".equals(typeNode.getString());

        if (hasProperties || typedObject) {
            // strict 模式不允许额外属性；即使用户原 schema 显式为 true 也必须收紧。
            node.set("additionalProperties", false);
            ONode required = new ONode().asArray();
            if (hasProperties) {
                for (String name : properties.getObject().keySet()) {
                    required.add(name);
                }
            }
            // 空对象同样显式给出 required:[]，与官方 strict schema helper 保持一致。
            node.set("required", required);
        }

        if (hasProperties) {
            for (ONode child : properties.getObject().values()) {
                normalizeStructuredSchema(child);
            }
        }
        for (String key : new String[]{"items", "additionalItems", "not", "anyOf", "oneOf", "allOf", "prefixItems"}) {
            normalizeStructuredSchema(node.getOrNull(key));
        }
        for (String key : new String[]{"$defs", "definitions"}) {
            ONode defs = node.getOrNull(key);
            if (defs != null && defs.isObject()) {
                for (ONode child : defs.getObject().values()) {
                    normalizeStructuredSchema(child);
                }
            }
        }
    }

    /**
     * 模型是否支持原生结构化输出与严格工具（官方支持列表：Claude 4.5 及以后）。
     *
     * @since 4.1
     */
    private boolean supportsStructuredOutputs(ChatConfig config) {
        if (config == null || Utils.isEmpty(config.getModel())) {
            return false;
        }
        String model = config.getModel().toLowerCase();
        // fable / mythos 都是 5.x 代且在支持列表内；claude-mythos-preview 无版本号，只能按族判定
        if (model.contains("fable-5") || model.contains("mythos")) {
            return true;
        }
        int version = resolveClaudeVersionScore(model);
        return version >= STRUCTURED_OUTPUT_MIN_VERSION;
    }

    /**
     * 解析模型代次为可比较分值（{@code major * 100 + minor}），无法识别返回 -1。
     *
     * @since 4.1
     */
    private static int resolveClaudeVersionScore(String model) {
        java.util.regex.Matcher matcher = CLAUDE_VERSION_PATTERN.matcher(model);
        if (matcher.find() == false) {
            matcher = CLAUDE_VERSION_INVERTED_PATTERN.matcher(model);
            if (matcher.find() == false) {
                return -1;
            }
        }
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
            return major * 100 + minor;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 汇总 beta 声明为 {@code anthropic-beta} 头值（逗号分隔、按声明序去重）。
     *
     * <p>协议上 beta 能力经请求头协商，GA 请求体里没有对应字段，因此由
     * {@code AnthropicChatDialect#createHttpUtils} 在建连时取用。</p>
     *
     * @since 4.1
     */
    static String resolveBetaHeader(ChatOptions options) {
        if (options == null) {
            return null;
        }
        Set<String> betas = new java.util.LinkedHashSet<>();
        for (String key : BETA_OPTION_KEYS) {
            collectBetas(options.options().get(key), betas);
        }
        return betas.isEmpty() ? null : String.join(",", betas);
    }

    /**
     * 展开 beta 声明：支持逗号分隔串、集合与数组。
     *
     * @since 4.1
     */
    static void collectBetas(Object value, Set<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof String) {
            for (String part : ((String) value).split(",")) {
                String beta = part.trim();
                if (beta.isEmpty() == false) {
                    out.add(beta);
                }
            }
        } else if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                collectBetas(item, out);
            }
        } else if (value instanceof Object[]) {
            for (Object item : (Object[]) value) {
                collectBetas(item, out);
            }
        }
    }

    /**
     * 是否开启严格工具（{@code tools[].strict}）。
     *
     * <p>不做“模型支持就自动开”：开启后服务端会校验每个 {@code input_schema}，
     * 已有工具只要有一个不合规就会令整条请求失败，不能默默改变现有行为。</p>
     *
     * @since 4.1
     */
    private boolean isStrictToolsEnabled(ChatOptions options) {
        if (options == null) {
            return false;
        }
        Object strict = options.options().get("strict_tools");
        return Boolean.TRUE.equals(strict) || "true".equalsIgnoreCase(String.valueOf(strict));
    }

    /**
     * 统一 API 的 {@code stop} → Anthropic {@code stop_sequences}。
     *
     * @since 4.1
     */
    private void writeStopSequences(ONode root, Object stop) {
        if (stop == null) {
            return;
        }
        if (stop instanceof String) {
            if (Utils.isNotEmpty((String) stop)) {
                root.set("stop_sequences", ONode.ofBean(java.util.Collections.singletonList(stop)));
            }
        } else if (stop instanceof Collection) {
            if (Utils.isNotEmpty((Collection<?>) stop)) {
                root.set("stop_sequences", ONode.ofBean(stop));
            }
        } else if (stop instanceof String[]) {
            if (((String[]) stop).length > 0) {
                root.set("stop_sequences", ONode.ofBean(stop));
            }
        }
    }

    /**
     * 构建 tool_choice 节点（转换为Anthropic格式）
     *
     * OpenAI 格式 → Anthropic 格式：
     *   "auto"       → {"type": "auto"}
     *   "required"   → {"type": "any"}
     *   "none"       → {"type": "none"}  (仅 Anthropic 特定客户端支持)
     *   {"type":"function",...} → {"type":"tool","name":"..."}
     */
    @SuppressWarnings("unchecked")
    private void buildToolChoiceNode(ONode root, Object toolChoice) {
        ONode tcNode = root.getOrNew("tool_choice");
        if (toolChoice instanceof Map) {
            Map<String, Object> choiceMap = (Map<String, Object>) toolChoice;
            String type = (String) choiceMap.get("type");
            if ("function".equals(type) && choiceMap.containsKey("function")) {
                Map<String, Object> funcMap = (Map<String, Object>) choiceMap.get("function");
                String name = (String) funcMap.get("name");
                tcNode.set("type", "tool");
                if (Utils.isNotEmpty(name)) {
                    tcNode.set("name", name);
                }
            } else if ("auto".equals(type) || "any".equals(type) || "none".equals(type) || "tool".equals(type)) {
                // Anthropic 原生形态直接透传（协议 ToolChoice：auto/any/none/tool + name）
                tcNode.set("type", type);
                Object name = choiceMap.get("name");
                if ("tool".equals(type) && name instanceof String && Utils.isNotEmpty((String) name)) {
                    tcNode.set("name", (String) name);
                }
            } else {
                tcNode.set("type", "auto");
            }

            // 原生形态里的 disable_parallel_tool_use：协议上 auto/any/tool 三个变体都带这个字段，
            // 旧实现只拷 type 与 name，走 Map 原生形态的用户同样丢掉该开关
            Object disableParallel = choiceMap.get("disable_parallel_tool_use");
            if (disableParallel != null && "none".equals(tcNode.get("type").getString()) == false) {
                if (Boolean.TRUE.equals(disableParallel)
                        || "true".equalsIgnoreCase(String.valueOf(disableParallel))) {
                    tcNode.set("disable_parallel_tool_use", true);
                }
            }
        } else if (toolChoice instanceof String) {
            String choice = (String) toolChoice;
            switch (choice) {
                case "required":
                    tcNode.set("type", "any");
                    break;
                case "none":
                    tcNode.set("type", "none");
                    break;
                case "auto":
                default:
                    tcNode.set("type", "auto");
                    break;
            }
        }
    }

    /**
     * 构建思考模式配置
     * @author oisin lu
     * @date 2026年1月27日
     * Claude Extended Thinking 配置格式：
     * {
     *   "thinking": {
     *     "type": "enabled",
     *     "budget_tokens": 10000
     *   }
     * }
     * @param root 根节点
     * @param value 思考配置值
     */
    private void buildThinkingNode(ONode root, Object value) {
        if (value == null) {
            return;
        }

        ONode thinkingNode = root.getOrNew("thinking");

        if (value instanceof Map) {
            Map<String, Object> thinkingMap = (Map<String, Object>) value;

            // enabled=false 与 type=disabled 都表示显式关闭。关闭变体只允许 type 字段，
            // 不能继续携带 budget_tokens / display 等 enabled 专属字段。
            Object enabled = thinkingMap.get("enabled");
            Object configuredType = thinkingMap.get("type");
            if (Boolean.FALSE.equals(enabled) || "disabled".equals(String.valueOf(configuredType))) {
                thinkingNode.set("type", "disabled");
                return;
            }

            if (Boolean.TRUE.equals(enabled)) {
                thinkingNode.set("type", "enabled");
            } else if (configuredType != null) {
                thinkingNode.set("type", configuredType);
            } else {
                thinkingNode.set("type", "enabled");
            }

            // 设置思考预算
            Object budgetTokens = thinkingMap.get("budget_tokens");
            if (budgetTokens == null) {
                budgetTokens = thinkingMap.get("budgetTokens");
            }
            if (budgetTokens == null) {
                budgetTokens = thinkingMap.get("thinkingBudget");
            }
            if (budgetTokens instanceof Number) {
                thinkingNode.set("budget_tokens", ((Number) budgetTokens).intValue());
            } else if ("enabled".equals(thinkingNode.get("type").getString())) {
                // Map 简化入口声明 enabled 但没给预算时，与 thinking=true 使用相同默认值，
                // 避免生成缺少必填 budget_tokens 的非法请求。
                thinkingNode.set("budget_tokens", DEFAULT_THINKING_BUDGET);
            }

            // 思考摘要可见性（协议 ThinkingConfigEnabled.display：summarized | omitted）：
            // 旧实现只在 adaptive 路径写 display，经典 type=enabled 路径整块不写，
            // 经典模型用户无法关掉思考摘要（比如为了省输出带宽只要结论）。
            // 仅在用户显式指定时透出，不提供默认值，避免改变现有请求行为
            Object display = thinkingMap.get("display");
            if (display instanceof String && Utils.isNotEmpty((String) display)) {
                thinkingNode.set("display", display);
            }
        } else if (value instanceof Boolean) {
            // 统一开关 / 简化配置：thinking: true|false
            if (Boolean.TRUE.equals(value)) {
                thinkingNode.set("type", "enabled");
                thinkingNode.set("budget_tokens", DEFAULT_THINKING_BUDGET); // 默认预算必须小于等于 max_tokens
            } else {
                thinkingNode.set("type", "disabled");
            }
        } else if (value instanceof Number) {
            // 简化配置：thinking: 10000 (直接指定预算)
            thinkingNode.set("type", "enabled");
            thinkingNode.set("budget_tokens", ((Number) value).intValue());
        }
    }
     
    /**
     * 统一 thinking 开关 + reasoning_effort 映射。
     * <p>优先级：供应商原生 Map/Number thinking &gt; Boolean thinking(false) 关闭
     * &gt; reasoning_effort 开启并设档 &gt; Boolean thinking(true) 默认开启。</p>
     * <p>Claude adaptive（4.6 / 4.7+ / sonnet-5+）：{@code thinking.type=adaptive} + 顶层 {@code effort}；
     * 经典模型：{@code type=enabled} + {@code budget_tokens}。</p>
     *
     * @since 4.0.4
     */
    private void applyUnifiedThinkingOptions(ONode root, ChatConfig config, ChatOptions options, Object thinkingSwitch) {
        // 已有显式 thinking 节点（Map/Number 路径）则不覆盖
        if (root.hasKey("thinking")) {
            return;
        }

        if (Boolean.FALSE.equals(thinkingSwitch)) {
            buildThinkingNode(root, Boolean.FALSE);
            return;
        }

        Object effortObj = options == null ? null : options.options().get("reasoning_effort");
        if (effortObj != null) {
            buildThinkingFromEffort(root, config, effortObj, options);
            if (root.hasKey("thinking")) {
                return;
            }
        }

        if (Boolean.TRUE.equals(thinkingSwitch)) {
            if (isAnthropicAdaptiveModel(config)) {
                // adaptive 默认 medium effort
                buildAdaptiveThinking(root, "medium", config);
            } else {
                buildThinkingNode(root, Boolean.TRUE);
            }
        }
    }

    /**
     * 将统一 reasoning_effort 映射为 Claude thinking。
     * <p>adaptive 模型 → adaptive + 顶层 effort；经典 → enabled + budget_tokens。</p>
     * <p>Anthropic 经典路径要求 budget_tokens 至少为 1024 且严格小于 max_tokens。
     * 档位预算不小于 max_tokens 时先压到 {@code max_tokens - 1}，最终由统一校验收口；
     * 若二者无法同时满足，则不发送 thinking，避免构造服务端必然拒绝的请求。</p>
     *
     * @since 4.0.4
     */
    private void buildThinkingFromEffort(ONode root, ChatConfig config, Object value, ChatOptions options) {
        if (value == null) {
            return;
        }
        String effort = String.valueOf(value).trim().toLowerCase();

        if (isAnthropicAdaptiveModel(config)) {
            String adaptiveEffort = clampAnthropicAdaptiveEffort(effort, config);
            if (adaptiveEffort == null) {
                return;
            }
            buildAdaptiveThinking(root, adaptiveEffort, config);
            return;
        }

        int budget;
        switch (effort) {
            case "low":
                budget = 4000;
                break;
            case "medium":
                budget = 10000;
                break;
            case "high":
                // 向 OpenCode 靠拢：high≈16k
                budget = 16000;
                break;
            case "max":
                // 取较大预算；若默认 max_tokens=32000 会落到 31999
                budget = 32000;
                break;
            default:
                return;
        }

        int maxTokens = resolveMaxTokens(root, options);
        // 统一语义（与既有测试契约一致）：budget 必须严格小于 max_tokens；
        // 极小 max_tokens 下档位预算退化为 "尽量占满输出预算"（max_tokens - 1），仅 max_tokens<=1 时放弃 thinking
        if (maxTokens <= 1) {
            return;
        }
        if (budget >= maxTokens) {
            budget = maxTokens - 1;
        }

        Map<String, Object> thinking = new HashMap<String, Object>();
        thinking.put("type", "enabled");
        thinking.put("budget_tokens", budget);
        buildThinkingNode(root, thinking);
    }

    /**
     * Claude adaptive thinking：type=adaptive + output_config.effort（无 budget_tokens）。
     * <p>opus-4.7+ / sonnet-5+ 等默认 display=omitted，需强制 summarized 才能拿到思考摘要
     * （对齐 OpenCode anthropicOmitsThinking）。</p>
     * <p>effort 位于 {@code output_config.effort}（Messages API OutputConfig，对齐官方 SDK
     * OutputConfig.Effort：low/medium/high/xhigh/max）；顶层并无该字段。</p>
     *
     * @since 4.0.4
     */
    private void buildAdaptiveThinking(ONode root, String effort, ChatConfig config) {
        ONode thinkingNode = root.getOrNew("thinking");
        thinkingNode.set("type", "adaptive");
        if (isAnthropicOmitsThinkingModel(config)) {
            // 新模型默认 display=omitted 会返回空 thinking 块
            thinkingNode.set("display", "summarized");
        }
        // getOrNew 合并：用户若已显式配置 output_config（如 format），仅追加 effort 不覆盖
        root.getOrNew("output_config").set("effort", effort);
    }

    /**
     * 是否 Anthropic adaptive 模型族（按 model 名启发，对齐 OpenCode）。
     * <p>覆盖：opus/sonnet 4.6；opus-4.7+；sonnet-5+；fable-5；
     * 以及 SAP 等倒置命名 {@code claude-4.7-opus} / {@code claude-5-sonnet}。</p>
     *
     * @since 4.0.4
     */
    private boolean isAnthropicAdaptiveModel(ChatConfig config) {
        if (config == null || config.getModel() == null) {
            return false;
        }
        String model = config.getModel().toLowerCase();
        if (model.contains("fable-5")) {
            return true;
        }
        if (isAnthropicOpus47OrLater(model) || isAnthropicSonnet5OrLater(model)) {
            return true;
        }
        // 4.6 系列（含倒置 4.6-opus / 4.6-sonnet）
        return containsAny(model,
                "opus-4-6", "opus-4.6", "4-6-opus", "4.6-opus",
                "sonnet-4-6", "sonnet-4.6", "4-6-sonnet", "4.6-sonnet");
    }

    /**
     * 新 adaptive 模型默认 display=omitted，需写 summarized（对齐 OpenCode）。
     *
     * @since 4.0.4
     */
    private boolean isAnthropicOmitsThinkingModel(ChatConfig config) {
        if (config == null || config.getModel() == null) {
            return false;
        }
        String model = config.getModel().toLowerCase();
        return model.contains("fable-5")
                || isAnthropicOpus47OrLater(model)
                || isAnthropicSonnet5OrLater(model);
    }

    /**
     * opus-4.7+ 或 claude-4.7-opus 倒置命名（对齐 OpenCode anthropicOpus47OrLater）。
     *
     * @since 4.0.4
     */
    private boolean isAnthropicOpus47OrLater(String model) {
        if (model == null || model.isEmpty()) {
            return false;
        }
        // opus-4.7 / opus-4-7 / opus-4.8 ...
        Matcher m1 = OPUS_VERSION_PATTERN.matcher(model);
        if (m1.find()) {
            int major = Integer.parseInt(m1.group(1));
            int minor = Integer.parseInt(m1.group(2));
            return major > 4 || (major == 4 && minor >= 7);
        }
        // claude-4.7-opus / claude-4-7-opus（SAP 等倒置）
        Matcher m2 = OPUS_VERSION_INVERTED_PATTERN.matcher(model);
        if (m2.find()) {
            int major = Integer.parseInt(m2.group(1));
            int minor = Integer.parseInt(m2.group(2));
            return major > 4 || (major == 4 && minor >= 7);
        }
        return false;
    }

    /**
     * sonnet-5+ 或 claude-5-sonnet 倒置命名（对齐 OpenCode anthropicSonnet5OrLater）。
     *
     * @since 4.0.4
     */
    private boolean isAnthropicSonnet5OrLater(String model) {
        if (model == null || model.isEmpty()) {
            return false;
        }
        Matcher m1 = SONNET_VERSION_PATTERN.matcher(model);
        if (m1.find()) {
            return Integer.parseInt(m1.group(1)) >= 5;
        }
        Matcher m2 = SONNET_VERSION_INVERTED_PATTERN.matcher(model);
        if (m2.find()) {
            return Integer.parseInt(m2.group(1)) >= 5;
        }
        return false;
    }

    private static boolean containsAny(String text, String... tokens) {
        if (text == null || text.isEmpty() || tokens == null) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isEmpty() && text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * adaptive 档位：4.6 为 low/medium/high/max；4.7+/sonnet-5+ 额外支持 xhigh。
     * 统一 API 的 max 保持 max；非法值返回 null。
     *
     * @since 4.0.4
     */
    private String clampAnthropicAdaptiveEffort(String effort, ChatConfig config) {
        if (effort == null || effort.isEmpty() || "auto".equals(effort)) {
            return null;
        }
        String model = config == null || config.getModel() == null ? "" : config.getModel().toLowerCase();
        // 4.7+ / sonnet-5+ / fable-5 支持 xhigh
        boolean supportsXhigh = isAnthropicOmitsThinkingModel(config)
                || model.contains("4-7") || model.contains("4.7");

        if ("low".equals(effort) || "medium".equals(effort) || "high".equals(effort) || "max".equals(effort)) {
            return effort;
        }
        if ("xhigh".equals(effort) || "min".equals(effort) || "minimal".equals(effort)) {
            if ("xhigh".equals(effort)) {
                return supportsXhigh ? "xhigh" : "max";
            }
            // min/minimal → low
            return "low";
        }
        if ("none".equals(effort)) {
            return null;
        }
        return null;
    }

    /**
     * 将已写出的 thinking.budget_tokens 钳制到 max_tokens - 1。
     *
     * @since 4.0.4
     */
    private void clampThinkingBudgetToMaxTokens(ONode root, ChatOptions options) {
        if (root == null || !root.hasKey("thinking")) {
            return;
        }
        ONode thinkingNode = root.get("thinking");
        if (thinkingNode == null || !thinkingNode.hasKey("budget_tokens")) {
            return;
        }
        int maxTokens = resolveMaxTokens(root, options);
        if (maxTokens <= 1) {
            root.remove("thinking");
            return;
        }
        int budget = thinkingNode.get("budget_tokens").getInt();
        if (budget >= maxTokens) {
            budget = maxTokens - 1;
        }
        // 协议下限：budget_tokens >= 1024，否则会被 API 拒绝
        if (budget < 1024) {
            root.remove("thinking");
            return;
        }
        thinkingNode.set("budget_tokens", budget);
    }

    private int resolveMaxTokens(ONode root, ChatOptions options) {
        int maxTokens = 32000;
        Object maxTokensObj = options == null ? null : options.options().get("max_tokens");
        if (maxTokensObj instanceof Number) {
            maxTokens = ((Number) maxTokensObj).intValue();
        } else if (root != null && root.hasKey("max_tokens")) {
            maxTokens = root.get("max_tokens").getInt();
        }
        return maxTokens;
    }

    /**
     * 提取系统消息
     * @author oisin lu
     * @date 2026年1月27日
     * @return 系统消息内容
     */
    private String extractSystemMessage(List<ChatMessage> messages) {
        StringBuilder systemMessage = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage) {
                if (systemMessage.length() > 0) {
                    systemMessage.append("\n");
                }
                systemMessage.append(message.getContent());
            }
        }
        return systemMessage.toString();
    }

    /**
     * 构建消息
     * @author oisin lu
     * @date 2026年1月27日
     * @param message 消息
     * @return 消息节点
     */
    public ONode buildMessageNode(ChatMessage message) {
        ONode node = new ONode();

        ChatRole role = message.getRole();
        String roleStr = "user";
        if (role != null) {
            if (role == ChatRole.ASSISTANT) {
                roleStr = "assistant";
            } else if (role == ChatRole.SYSTEM) {
                roleStr = "user"; // 系统消息已经在顶层处理
            } else if (role == ChatRole.TOOL) {
                roleStr = "user";
            } else {
                roleStr = role.toString().toLowerCase();
            }
        }

        node.set("role", roleStr);

        if (message instanceof ToolMessage) {
            buildToolMessageNode(node, (ToolMessage) message);
        } else if (message instanceof AssistantMessage) {
            buildAssistantToolCallMessageNode(node, (AssistantMessage) message);
        } else {
            buildNormalMessageNode(node, message);
        }

        return node;
    }

    /**
     * 构建工具消息
     * @author oisin lu
     * @date 2026年1月27日
     * @param node 父节点
     * @param toolMessage 工具消息
     */
    private void buildToolMessageNode(ONode node, ToolMessage toolMessage) {
        // Claude使用tool_result格式
        node.getOrNew("content").asArray().add(buildToolResultBlock(toolMessage));
    }

    /**
     * 构建单个 tool_result 块（协议：is_error 标记 + content 可为块数组）。
     *
     * @since 4.0.4
     */
    private ONode buildToolResultBlock(ToolMessage toolMessage) {
        // 协议上 tool_use_id 为必填（ToolResultBlockParam.toolUseId 是 required）：
        // 上游漏带时直接发出会换回服务端晦涩的 400。此处空串仍照发（交给服务端明确拒），
        // null 则降级为 debug 日志，避免把 null 字面量写进请求体。
        String toolUseId = toolMessage.getToolCallId();
        if (toolUseId == null) {
            LOG.debug("Anthropic tool_result is missing tool_use_id, role=tool message: {}", toolMessage.getContent());
        }

        ONode block = new ONode()
                .set("type", "tool_result")
                .set("tool_use_id", toolUseId);

        // 协议：tool_result.is_error 标记失败结果，供模型前置纠错（经 ToolMessage.metadata 透传）
        Object isError = toolMessage.getMetadataAs("is_error");
        if (isError == null) {
            isError = toolMessage.getMetadataAs("isError");
        }
        if (Boolean.TRUE.equals(isError) || "true".equals(String.valueOf(isError))) {
            block.set("is_error", true);
        }

        // 协议：tool_result.content 可为 string 或 content block 数组（支持工具返回图片/多块结果）
        if (toolMessage.isMultiModal() && Utils.isNotEmpty(toolMessage.getBlocks())) {
            ONode resultBlocks = block.getOrNew("content").asArray();
            for (ContentBlock cb : toolMessage.getBlocks()) {
                if (cb instanceof TextBlock) {
                    resultBlocks.addNew()
                            .set("type", "text")
                            .set("text", cb.getContent());
                } else if (cb instanceof ImageBlock) {
                    appendClaudeImageBlock(resultBlocks, (ImageBlock) cb);
                } else if (cb instanceof BlobBlock) {
                    // 协议 ToolResultBlockParam.Content 含 document 变体：工具返回 PDF / 纯文本文档
                    appendClaudeDocumentBlock(resultBlocks, (BlobBlock) cb);
                } else if (Utils.isNotEmpty(cb.getContent())) {
                    // 其余块（音频 / 视频 / 资源）协议上没有对应的 tool_result 内容变体：
                    // 退化为 text 而不是静默丢弃（丢弃会让工具结果凭空少一块，模型无从得知）
                    resultBlocks.addNew()
                            .set("type", "text")
                            .set("text", cb.getContent());
                }
            }
        } else {
            block.set("content", toolMessage.getContent());
        }
        return block;
    }

    private List<ONode> resolveOrderedContentBlocks(AssistantMessage assistantMessage) {
        Map<String, Object> stateData = AnthropicMessageStateSupport.resolveData(assistantMessage);
        if (stateData == null) return null;
        Object blocks = stateData.get(AnthropicResponseParser.CONTENT_BLOCKS_RAW_KEY);
        if (!(blocks instanceof List) || ((List<?>) blocks).isEmpty()) return null;

        List<ONode> out = new java.util.ArrayList<>();
        for (Object item : (List<?>) blocks) {
            ONode block = toProtocolNode(item);
            // 精确回放必须是全有或全无：任一块损坏时整组降级到通用消息语义，
            // 不能把前半段 raw 与后半段通用字段拼成重复或缺失的混合消息。
            if (block == null || block.isObject() == false
                    || Utils.isEmpty(block.get("type").getString())) {
                return null;
            }
            out.add(block);
        }
        return out;
    }

    private boolean appendOrderedContentBlocks(ONode contentArray, AssistantMessage assistantMessage) {
        List<ONode> blocks = resolveOrderedContentBlocks(assistantMessage);
        if (blocks == null) return false;
        for (ONode block : blocks) {
            contentArray.add(block);
        }
        return true;
    }

    private static ONode toProtocolNode(Object value) {
        if (value == null) return null;
        try {
            if (value instanceof ONode) {
                return ONode.ofJson(((ONode) value).toJson());
            }
            if (value instanceof String) {
                String json = (String) value;
                return Utils.isEmpty(json) ? null : ONode.ofJson(json);
            }
            return ONode.ofBean(value);
        } catch (Exception e) {
            return null;
        }
    }
    /**
     * 构建助手消息
     * @param node 父节点
     * @param assistantMessage 助手消息
     */
    private void buildAssistantToolCallMessageNode(ONode node, AssistantMessage assistantMessage) {
        if (appendOrderedContentBlocks(node.getOrNew("content").asArray(), assistantMessage)) {
            return;
        }
        List<ToolCall> toolCalls = ToolCallJsonSanitizer.resolveToolCalls(
                assistantMessage.getToolCalls(), assistantMessage.getToolCallsRaw());
        if (Utils.isNotEmpty(toolCalls)) {
            ONode contentArray = node.getOrNew("content").asArray();

            // 仅当 thinkingSignature 有效时回传 thinking 块。
            // 兼容网关（如 DeepSeek claude_chat）在 tool 多轮回传无 signature 的 thinking，常触发 EMPTY_RESPONSE。
            // 官方 Claude 也要求 thinking 块带有效 signature 才能继续多轮。
            String reasoning = assistantMessage.getThinking();
            String signature = resolveThinkingSignature(assistantMessage);
            if (Utils.isNotEmpty(reasoning) && Utils.isNotEmpty(signature)) {
                contentArray.addNew()
                        .set("type", "thinking")
                        .set("thinking", reasoning)
                        .set("signature", signature);
            }

            // 回传 redacted_thinking 块（安全过滤的推理内容，原样保留供多轮，对齐 Anthropic SDK）
            appendRedactedThinkingBlocks(contentArray, assistantMessage);

            // 回传服务端工具块（位置在 text 之前，与真实响应的块序一致）
            appendServerToolBlocks(contentArray, resolveServerToolBlocks(assistantMessage));

            // 添加文本内容（如果有；兼容旧版带 <think> 标签的消息，出站前剥离）
            String resultContent = emptyToNull(assistantMessage.getText());
            if (resultContent != null) {
                contentArray.addNew()
                    .set("type", "text")
                    .set("text", resultContent);
            }
            
            // 多模态媒体块（若有）
            appendAssistantMediaBlocks(contentArray, assistantMessage);
                    
            // 添加工具调用
            for (ToolCall call : toolCalls) {
                contentArray.addNew()
                    .set("type", "tool_use")
                    .set("id", call.getId())
                    .set("name", call.getName())
                    .set("input", ONode.ofJson(ToolCallJsonSanitizer.sanitizeArguments(call)));
            }
        } else if (assistantMessage.isMultiModal()) {
            // 多模态助手消息：content 数组（text + image）
            ONode contentArray = node.getOrNew("content").asArray();
            //服务端工具块先行（与真实响应的块序一致：工具轮次在前、模型行文在后）
            appendServerToolBlocks(contentArray, resolveServerToolBlocks(assistantMessage));
            boolean hasText = false;
            if (Utils.isNotEmpty(assistantMessage.getBlocks())) {
                for (ContentBlock block : assistantMessage.getBlocks()) {
                    if (block instanceof TextBlock) {
                        String text = emptyToNull(block.getContent());
                        if (text != null) {
                            contentArray.addNew()
                                    .set("type", "text")
                                    .set("text", text);
                            hasText = true;
                        }
                    } else if (block instanceof ImageBlock) {
                        appendClaudeImageBlock(contentArray, (ImageBlock) block);
                    } else if (block instanceof BlobBlock) {
                        appendClaudeDocumentBlock(contentArray, (BlobBlock) block);
                    }
                }
            }
            if (!hasText) {
                String fallbackText = emptyToNull(assistantMessage.getText());
                if (fallbackText != null) {
                    contentArray.addNew()
                            .set("type", "text")
                            .set("text", fallbackText);
                }
            }
        } else {
            List<Object> serverBlocks = resolveServerToolBlocks(assistantMessage);
            String content = emptyToNull(assistantMessage.getText());

            if (Utils.isEmpty(serverBlocks) == false) {
                // 服务端工具轮次（pause_turn 续跑 / 多轮 web_search）：本轮没有本地 tool_use，
                // 但 content 必须是块数组才能把 server_tool_use 与结果块原样带回去。
                // 不带回去的后果：服务端无法认领上一轮的搜索凭证（encrypted_content），
                // pause_turn 续跑退化为从头重跑（搜索与抓取按次重新计费）
                ONode contentArray = node.getOrNew("content").asArray();
                appendThinkingBlock(contentArray, assistantMessage);
                appendRedactedThinkingBlocks(contentArray, assistantMessage);
                appendServerToolBlocks(contentArray, serverBlocks);
                if (content != null) {
                    contentArray.addNew()
                            .set("type", "text")
                            .set("text", content);
                }
            } else if (content != null) {
                // 纯文本按原样回传，保证 assistant prefill 的前后空白不被改变。
                node.set("content", content);
            } else {
                // 若仅有思考内容（无正文、无 tool）：仅在 signature 有效时回传 thinking；
                // 否则只保留空 content，避免兼容网关因无 signature thinking 拒绝下一轮
                ONode contentArray = node.getOrNew("content").asArray(); // Claude 需要 content 字段，即使是空数组
                appendThinkingBlock(contentArray, assistantMessage);
            }
        }
    }

    /**
     * 回传 thinking 块（仅当 signature 有效）。
     *
     * <p>兼容网关（如 DeepSeek claude_chat）在多轮回传无 signature 的 thinking 时常触发
     * EMPTY_RESPONSE；官方 Claude 也要求 thinking 块带有效 signature 才能继续多轮。</p>
     *
     * @since 4.1
     */
    private void appendThinkingBlock(ONode contentArray, AssistantMessage assistantMessage) {
        String reasoning = assistantMessage.getThinking();
        String signature = resolveThinkingSignature(assistantMessage);
        if (Utils.isNotEmpty(reasoning) && Utils.isNotEmpty(signature)) {
            contentArray.addNew()
                    .set("type", "thinking")
                    .set("thinking", reasoning)
                    .set("signature", signature);
        }
    }

    /**
     * 从 Anthropic 协议状态提取 thinking signature（兼容旧 contentRaw）。
     */
    private String resolveThinkingSignature(AssistantMessage assistantMessage) {
        Map<String, Object> stateData = AnthropicMessageStateSupport.resolveData(assistantMessage);
        if (stateData != null) {
            Object sig = stateData.get("thinkingSignature");
            if (sig instanceof String && Utils.isNotEmpty((String) sig)) {
                return (String) sig;
            }
        }
        return null;
    }

    /**
     * 空串返回 null；非空文本按原样保留，避免改变 assistant prefill 的精确续写前缀。
     */
    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }
    
    /**
     * 将 Assistant 媒体块追加到 Claude content 数组（支持 image / document）。
     *
     * @since 3.9
     */
    private void appendAssistantMediaBlocks(ONode contentArray, AssistantMessage assistantMessage) {
        if (!assistantMessage.hasMedia() || Utils.isEmpty(assistantMessage.getBlocks())) {
            return;
        }
        for (ContentBlock block : assistantMessage.getBlocks()) {
            if (block instanceof ImageBlock) {
                appendClaudeImageBlock(contentArray, (ImageBlock) block);
            } else if (block instanceof BlobBlock) {
                appendClaudeDocumentBlock(contentArray, (BlobBlock) block);
            }
        }
    }
    
    /**
     * Claude image source 结构。
     *
     * @since 3.9
     */
    private void appendClaudeImageBlock(ONode contentArray, ImageBlock image) {
        String mediaType = image.getMimeType();
        if (Utils.isEmpty(mediaType)) {
            mediaType = "image/jpeg";
        }

        if (Utils.isNotEmpty(image.getData())) {
            contentArray.addNew()
                    .set("type", "image")
                    .set("source", new ONode()
                            .set("type", "base64")
                            .set("media_type", mediaType)
                            .set("data", image.toDataString(false)));
        } else if (Utils.isNotEmpty(image.getUrl())) {
            String fileId = resolveAnthropicFileId(image.getUrl());
            if (fileId != null) {
                // 协议 FileImageSource：Files API 上传后的引用形态（不重传字节，也不占输入 token）。
                // 旧实现只有 base64 / url 两种 source，file_id 会被当成 url 发出去而被服务端拒掉
                contentArray.addNew()
                        .set("type", "image")
                        .set("source", new ONode()
                                .set("type", "file")
                                .set("file_id", fileId));
            } else {
                // 兼容 url 源（Claude 支持 source.type=url）
                contentArray.addNew()
                        .set("type", "image")
                        .set("source", new ONode()
                                .set("type", "url")
                                .set("url", image.getUrl()));
            }
        }
    }

    /**
     * 识别 Files API 的文件引用。
     *
     * <p>统一 API 的 {@link ImageBlock} 只有 {@code data} / {@code url} 两个载体，因此约定用 url 位置表达
     * file_id：显式前缀 {@code anthropic-file:file_xxx}，或直接写 Anthropic 原样标识 {@code file_xxx}。</p>
     *
     * <p>判定收得很紧（无协议头、无路径分隔）：否则一个真实的
     * {@code https://host/file_1.png} 会被误当成 file_id。</p>
     *
     * @since 4.1
     */
    private static String resolveAnthropicFileId(String url) {
        if (Utils.isEmpty(url)) {
            return null;
        }
        String value = url.trim();
        if (value.startsWith("anthropic-file:")) {
            String fileId = value.substring("anthropic-file:".length()).trim();
            return fileId.isEmpty() ? null : fileId;
        }
        if (value.startsWith("file_") && value.indexOf('/') < 0 && value.indexOf(':') < 0) {
            return value;
        }
        return null;
    }

    /**
     * 构建 {@code document} 块（协议 DocumentBlockParam）。
     *
     * <p>统一 API 的 {@link BlobBlock} 是非图像二进制内容的载体（PDF 最典型），旧实现在所有
     * content 构建处都只认 TextBlock / ImageBlock，它会被静默丢弃——用户以为传了 PDF，
     * 模型却从未看到。</p>
     *
     * <p>{@code text/plain} 走协议的 {@code PlainTextSource}（明文 data）而不是 base64；
     * base64 source 则只允许 {@code application/pdf}。其他 MIME 或损坏的纯文本 base64
     * 直接拒绝（fail-fast 拋 IllegalArgumentException），避免发送必然不符合 DocumentBlockParam
     * schema 的请求——此处是显式的「让用户在本地发现坏数据」策略，与解析侧的静默跳过
     * （如 resolveContainerId）不同：请求构建阶段的坏输入重试也必败，静默跳过会掩盖丢失的内容块。</p>
     *
     * @since 4.1
     */
    private void appendClaudeDocumentBlock(ONode contentArray, BlobBlock blob) {
        String data = blob.getBlob();
        if (Utils.isEmpty(data)) {
            return;
        }

        String mediaType = blob.getMimeType();
        ONode source = new ONode();

        if ("text/plain".equals(mediaType)) {
            String plainText = decodeBase64Text(data);
            if (plainText == null) {
                throw new IllegalArgumentException("Anthropic text document requires valid base64 data");
            }
            source.set("type", "text")
                    .set("media_type", "text/plain")
                    .set("data", plainText);
        } else if (Utils.isEmpty(mediaType) || "application/pdf".equals(mediaType)) {
            source.set("type", "base64")
                    .set("media_type", "application/pdf")
                    .set("data", data);
        } else {
            throw new IllegalArgumentException("Unsupported Anthropic document MIME type: " + mediaType);
        }

        contentArray.addNew()
                .set("type", "document")
                .set("source", source);
    }

    /**
     * base64 → UTF-8 明文（不合法时返回 null）。
     *
     * @since 4.1
     */
    private static String decodeBase64Text(String base64) {
        try {
            return new String(java.util.Base64.getDecoder().decode(base64), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构建普通消息
     * @author oisin lu
     * @date 2026年1月27日
     * @param node 父节点
     * @param message 消息
     */
    private void buildNormalMessageNode(ONode node, ChatMessage message) {
        if (message instanceof UserMessage) {
            UserMessage userMessage = (UserMessage) message;
            if (userMessage.isMultiModal() == false) {
                //单模态
                node.set("content", userMessage.getContent());
            } else {
                //多模态
                ONode contentArray = node.getOrNew("content").asArray();
                // Claude支持图像上传
                for (ContentBlock block1 : userMessage.getBlocks()) {
                    if (block1 instanceof TextBlock) {
                        TextBlock text = (TextBlock) block1;
                        contentArray.addNew()
                                .set("type", "text")
                                .set("text", text.getContent());
                    } else if (block1 instanceof ImageBlock) {
                        // 改走统一的 image 构建：旧实现在此处只写 base64 source，
                        // url 图片会得到 data:null 的非法块（而同一个 ImageBlock 在助手消息里是支持 url 的）
                        appendClaudeImageBlock(contentArray, (ImageBlock) block1);
                    } else if (block1 instanceof BlobBlock) {
                        // PDF / 纯文本附件 → 协议 document 块（旧实现静默丢弃）
                        appendClaudeDocumentBlock(contentArray, (BlobBlock) block1);
                    } else if (Utils.isNotEmpty(block1.getContent())) {
                        // 音频 / 视频 / 资源块：Messages API 的 ContentBlockParam 没有对应变体，
                        // 退化为 text 而不是静默丢弃（至少不会发出一条缺块的请求）
                        contentArray.addNew()
                                .set("type", "text")
                                .set("text", block1.getContent());
                    }
                }
            }
        } else {
            String content = message.getContent();
            if (Utils.isNotEmpty(content)) {
                node.set("content", content);
            } else {
                node.getOrNew("content").asArray();
            }
        }
    }

    private boolean hasNativeTools(ChatOptions options) {
        if (options == null) return false;
        Object value = options.options().get("tools");
        if (value == null) value = options.options().get("server_tools");
        if (value == null) value = options.options().get("anthropic_server_tools");
        if (value instanceof Collection) return !((Collection<?>) value).isEmpty();
        if (value instanceof Object[]) return ((Object[]) value).length > 0;
        return value != null;
    }


    /**
     * 构建工具
     * @date 2026年1月27日
     * @param root 根节点
     * @param options 聊天选项
     */
    public void buildToolsNode(ONode root, ChatOptions options) {
        // 向后兼容签名：默认在最后一个工具上打断点（无上层预算协调时的原有行为）
        buildToolsNode(root, options, true);
    }

    /**
     * 构建工具定义数组。
     * @author oisin lu
     * @param root        根节点
     * @param options     聊天选项
     * @param cacheOnTools 是否在最后一个工具上打 cache_control 断点。
     *                     由 {@link #build} 统一协调：仅当没有 system（静态断点无处可落）时才为 true；
     *                     有 system 时断点打在 system 上并连带缓存 tools，这里不再重复占用预算。
     * @since 4.0.4
     */
    public void buildToolsNode(ONode root, ChatOptions options, boolean cacheOnTools) {
        Collection<FunctionTool> tools = options.tools();
        CacheControl cacheControl = options.cacheControl();
        // 仅 Anthropic 风格（type 非空）且预算允许时，才在工具定义上打 cache_control 断点
        boolean cacheEnabled = cacheOnTools
                && (cacheControl != null && Utils.isNotEmpty(cacheControl.getType()));

        if (Utils.isEmpty(tools)) {
            ONode nativeTools = root.getOrNull("tools");
            if (cacheEnabled && nativeTools != null && nativeTools.isArray() && nativeTools.size() > 0) {
                writeCacheControl(nativeTools.get(nativeTools.size() - 1), cacheControl);
            }
            return;
        }

        // 严格工具（协议 Tool.strict，与结构化输出同期 GA）：保证入参严格符合 input_schema。
        // 只能显式 opt-in：开启后 schema 不合规的工具会直接被拒，且仅 Claude 4.5+ 支持
        final boolean strictTools = isStrictToolsEnabled(options);

        // 逐工具的 GA 协议字段（defer_loading / eager_input_streaming / allowed_callers / input_examples）
        final Map<String, Object> toolConfigs = resolveToolConfigs(options);

        ONode toolsNode = root.getOrNew("tools").asArray();
        int toolCount = 0;
        int totalTools = tools.size();
        for (FunctionTool func : tools) {
            toolCount++;
            final boolean isLast = (toolCount == totalTools);
            toolsNode.addNew().then(toolNode -> {
                toolNode.set("name", func.name());
                toolNode.set("description", func.descriptionAndMeta());
                String inputSchema = func.inputSchema();
                ONode schemaNode = buildToolInputSchema(inputSchema);
                if (strictTools) {
                    normalizeStructuredSchema(schemaNode);
                }
                toolNode.set("input_schema", schemaNode);

                // 严格工具：写在 cache_control 之前，与协议字段顺序无关，仅保持可读
                if (strictTools) {
                    toolNode.set("strict", true);
                }

                // 逐工具 GA 字段：在 cache_control 之前写出（它们属于工具定义的缓存前缀，顶层断点必须是最后一项）
                applyToolProtocolFields(toolNode, toolConfigs, func.name());

                // ⭐ 在最后一个工具定义上添加 cache_control (Anthropic Prompt Caching)
                if (isLast && cacheEnabled) {
                    writeCacheControl(toolNode, cacheControl);
                }
            });
        }
    }

    /**
     * 解析工具输入 schema，并保证根节点是 {@code type=object}。
     * 合法 JSON 但为数组、标量或非 object schema 时同样回退，避免把必然被 API 拒绝的定义发出。
     */
    private static ONode buildToolInputSchema(String inputSchema) {
        ONode schemaNode = null;
        if (Utils.isNotEmpty(inputSchema)) {
            try {
                schemaNode = ONode.ofJson(inputSchema);
            } catch (Exception ignored) {
                // 统一在下方回退为空对象 schema。
            }
        }

        if (schemaNode == null || schemaNode.isObject() == false
                || (Utils.isNotEmpty(schemaNode.get("type").getString())
                && "object".equals(schemaNode.get("type").getString()) == false)) {
            schemaNode = new ONode().asObject();
            schemaNode.set("type", "object");
            schemaNode.getOrNew("properties").asObject();
            return schemaNode;
        }

        if (schemaNode.hasKey("type") == false) {
            schemaNode.set("type", "object");
        }
        if (schemaNode.hasKey("properties") == false) {
            schemaNode.getOrNew("properties").asObject();
        }
        return schemaNode;
    }

    /**
     * 在对话历史上放置滚动 cache_control 断点
     * @author oisin lu
     * @param messagesNode messages 数组节点
     * @param cacheControl 缓存控制（已确保 type 非空）
     * @param quota        滚动断点配额（= 上限 - 静态断点数）
     * @since 4.0.4
     */
    private void applyMessageCacheBreakpoints(ONode messagesNode, CacheControl cacheControl, int quota) {
        if (messagesNode == null || !messagesNode.isArray() || quota <= 0) {
            return;
        }

        int total = messagesNode.size();
        if (total == 0) {
            return;
        }

        // 从最后一条往前放置，直到用满配额。
        // 不能直接按「最后 quota 条消息」计数：仅含 thinking / redacted_thinking 的消息无处挂断点
        // （协议上这两类块没有 cache_control 字段），跳过它继续往前找，才不会白扇配额
        int placed = 0;
        for (int i = total - 1; i >= 0 && placed < quota; i--) {
            if (markMessageCacheBreakpoint(messagesNode.get(i), cacheControl)) {
                placed++;
            }
        }
    }

    /**
     * 给单条消息节点挂 cache_control（从后往前找第一个可承载的 content block）。
     *
     * <p>Claude 的 content 可能是字符串（单模态文本）或 content block 数组。字符串形态无法承载
     * cache_control，需先转成 {@code [{"type":"text","text":<原文>,"cache_control":...}]}。</p>
     *
     * <p><b>不能无条件挂到最后一块</b>：thinking / redacted_thinking 在协议上没有 cache_control
     * 字段（见 {@link #CACHE_CONTROL_UNSUPPORTED_BLOCKS}），挂上去整条请求会被服务端拒掉。</p>
     *
     * @return 是否实际放置了断点
     * @since 4.0.4
     */
    private boolean markMessageCacheBreakpoint(ONode messageNode, CacheControl cacheControl) {
        if (messageNode == null || !messageNode.isObject()) {
            return false;
        }

        ONode contentNode = messageNode.getOrNull("content");
        if (contentNode == null) {
            return false;
        }

        if (contentNode.isArray()) {
            // 从后往前找第一个可承载 cache_control 的块（返回的是元素引用，原地修改即生效）
            for (int i = contentNode.size() - 1; i >= 0; i--) {
                ONode block = contentNode.get(i);
                if (block == null || !block.isObject()) {
                    continue;
                }
                if (CACHE_CONTROL_UNSUPPORTED_BLOCKS.contains(block.get("type").getString())) {
                    continue;
                }
                writeCacheControl(block, cacheControl);
                return true;
            }
            return false;
        } else if (contentNode.isString()) {
            // 字符串 content 转成带断点的 text block 数组（空串不转：空 text 块同样会被服务端拒）
            String text = contentNode.getString();
            if (Utils.isEmpty(text)) {
                return false;
            }
            ONode blocks = new ONode().asArray();
            blocks.addNew().then(block -> {
                block.set("type", "text");
                block.set("text", text);
                writeCacheControl(block, cacheControl);
            });
            messageNode.set("content", blocks);
            return true;
        }

        return false;
    }

    /**
     * 向指定节点写入 cache_control 标记（统一 type 与 ttl）。
     *
     * @param blockNode    要挂载 cache_control 的节点（system/tool/content block）
     * @param cacheControl 缓存控制（type 已确保非空）
     * @since 4.0.4
     */
    private void writeCacheControl(ONode blockNode, CacheControl cacheControl) {
        ONode ccNode = blockNode.getOrNew("cache_control");
        ccNode.set("type", cacheControl.getType());
        // 协议：ttl 仅可取 5m / 1h。CacheControl.getTtl() 是 @NonNull（缺省回落 "5m"），
        // 所以这里不存在“为空不写出”的情形，只需拦掉非法值（如 "10m"）避免透传后换回 400
        String ttl = cacheControl.getTtl();
        if (CACHE_TTL_ALLOWED.contains(ttl)) {
            ccNode.set("ttl", ttl);
        }
    }

    /**
     * 构建助手消息（用于工具调用）
     *
     * <p>合成节点除了 thinking / redacted / 服务端块 / tool_use 外，还必须回写正文 text 块：
     * 同一轮「先行文后调工具」时模型的自述若不落进历史，多轮上下文里这轮就只剩工具调用。
     * 真实响应的有序块优先级更高（appendOrderedContentBlocks 原样回放），本方法只在无回放载体时生效。</p>
     *
     * @author oisin lu
     * @date 2026年1月27日
     * @param toolCallBuilders 工具调用构建器
     * @return 助手消息
     */
    public ONode buildAssistantToolCallMessageNode(ChatAccumulator acc, Map<String, ToolCallBuilder> toolCallBuilders) {
        ONode node = new ONode();
        node.set("role", "assistant");

        ONode contentArray = node.getOrNew("content").asArray();

        // 仅当 thinkingSignature 有效时回传 thinking；无 signature 的 thinking 不回传，
        // 避免 tool 多轮在兼容网关上触发 EMPTY_RESPONSE
        String thinkingContent = acc.getAggregationThinking();
        if (Utils.isNotEmpty(thinkingContent) && Utils.isNotEmpty(acc.thinkingSignature)) {
            contentArray.addNew()
                    .set("type", "thinking")
                    .set("thinking", thinkingContent)
                    .set("signature", acc.thinkingSignature);
        }

        // 回传 redacted_thinking 块（安全过滤的推理内容，原样保留供多轮，对齐 Anthropic SDK）
        appendRedactedThinkingBlocks(contentArray, acc);

        // 回传服务端工具块：同一轮里模型先搜索再调本地工具是常见序列，
        // 不带回去就会出现「结果块缺失但后文引用了它」的不自洽上下文
        appendServerToolBlocks(contentArray, AnthropicResponseParser.getServerToolBlocks(acc, false));

        // 回写本轮正文：位置与真实响应块序一致（行文在 tool_use 之前）
        String resultContent = emptyToNull(acc.getAggregationText());
        if (resultContent != null) {
            contentArray.addNew()
                    .set("type", "text")
                    .set("text", resultContent);
        }

        for (Map.Entry<String, ToolCallBuilder> kv : toolCallBuilders.entrySet()) {
            ToolCallBuilder builder = kv.getValue();

            // 解析参数 JSON 字符串为对象（流式聚合出口净化：截断损坏的 arguments 统一归一为空对象）
            Object inputObject;
            String argsStr = ToolCallJsonSanitizer.sanitizeArguments(
                    builder.argumentsBuilder.toString(), builder.nameBuilder.toString());
            try {
                if (Utils.isNotEmpty(argsStr)) {
                    ONode argsNode = ONode.ofJson(argsStr);
                    inputObject = argsNode.isObject() ? argsNode.toBean(Map.class) : new HashMap<String, Object>();
                } else {
                    inputObject = new HashMap<>();
                }
            } catch (Exception e) {
                // 如果解析失败，使用空对象
                inputObject = new HashMap<>();
            }

            contentArray.addNew()
                .set("type", "tool_use")
                .set("id", builder.idBuilder.toString())
                .set("name", builder.nameBuilder.toString())
                .set("input", ONode.ofBean(inputObject));
        }

        return node;
    }

    /**
     * 将 redacted_thinking 内容块追加到 content 数组。
     * <p>redacted_thinking 是 Anthropic API 对安全过滤推理内容的 opaque 加密块，
     * 必须原样回传，否则多轮对话可能失败（对齐 Anthropic SDK 规范）。</p>
     * <p>两种重载：从 {@link AssistantMessage}（contentRaw 路径）和从 {@link ChatAccumulator}
     * （流式聚合路径）获取 redactedThinkingData。</p>
     *
     * @since 4.0.4
     */
    private void appendRedactedThinkingBlocks(ONode contentArray, AssistantMessage assistantMessage) {
        Map<String, Object> stateData = AnthropicMessageStateSupport.resolveData(assistantMessage);
        if (stateData == null) {
            return;
        }
        // 优先分块列表（协议：redacted_thinking 必须逐块原样回传，拼接会损坏 opaque 数据）
        Object blocks = stateData.get("redactedThinkingBlocks");
        if (blocks instanceof List) {
            for (Object data : (List<?>) blocks) {
                if (data instanceof String && Utils.isNotEmpty((String) data)) {
                    contentArray.addNew()
                            .set("type", "redacted_thinking")
                            .set("data", (String) data);
                }
            }
            return;
        }
        Object redacted = stateData.get("redactedThinkingData");
        if (!(redacted instanceof String) || Utils.isEmpty((String) redacted)) {
            return;
        }
        contentArray.addNew()
                .set("type", "redacted_thinking")
                .set("data", (String) redacted);
    }

    private void appendRedactedThinkingBlocks(ONode contentArray, ChatAccumulator acc) {
        // 优先分块列表（协议：逐块原样回传）
        java.util.List<String> blocks = AnthropicResponseParser.getRedactedBlocks(acc, false);
        if (blocks != null && !blocks.isEmpty()) {
            for (String data : blocks) {
                contentArray.addNew()
                        .set("type", "redacted_thinking")
                        .set("data", data);
            }
            return;
        }

        String data = acc.attrIfAbsent("redactedThinkingData", k->new StringBuilder()).toString();
        if (Utils.isNotEmpty(data)) {
            contentArray.addNew()
                    .set("type", "redacted_thinking")
                    .set("data", data);
        }
    }

    /**
     * 从 {@code contentRaw} 取回服务端工具原始块（解析时留存的 JSON 字符串列表）。
     *
     * @since 4.1
     */
    private static List<Object> resolveServerToolBlocks(AssistantMessage assistantMessage) {
        Map<String, Object> stateData = AnthropicMessageStateSupport.resolveData(assistantMessage);
        if (stateData == null) {
            return null;
        }
        Object blocks = stateData.get(AnthropicResponseParser.SERVER_BLOCKS_RAW_KEY);
        if (blocks instanceof List == false) {
            return null;
        }

        List<Object> out = new java.util.ArrayList<>();
        for (Object item : (List<?>) blocks) {
            if (toProtocolNode(item) != null) {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * 把服务端工具原始块按原序回传到 content 数组。
     *
     * <p><b>为什么必须原样回传而不是用事件重建</b>：{@code web_search_tool_result} 里的
     * {@code encrypted_content} 是服务端签发的 opaque 凭证（本地无法伪造），
     * {@code server_tool_use} 与结果块靠 {@code tool_use_id} 配对，
     * {@code code_execution_tool_result} 又与沙盒容器绑定。任意一环重建不准，
     * {@code stop_reason=pause_turn} 的续跑就会退化成重跑（搜索与抓取按次重新计费）。</p>
     *
     * <p>解析失败的块直接跳过：宁可少一块上文，也不能把脏数据发出去换回整条 400。</p>
     *
     * @since 4.1
     */
    private void appendServerToolBlocks(ONode contentArray, List<?> serverBlocks) {
        if (Utils.isEmpty(serverBlocks)) {
            return;
        }

        for (Object item : serverBlocks) {
            ONode block = toProtocolNode(item);
            if (block != null && block.isObject() && Utils.isNotEmpty(block.get("type").getString())) {
                contentArray.add(block);
            }
        }
    }

    /**
     * 取逐工具的 GA 字段配置（{@code anthropic_tools}）。
     *
     * @since 4.1
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveToolConfigs(ChatOptions options) {
        Object configs = options == null ? null : options.options().get(TOOL_CONFIG_OPTION_KEY);
        return configs instanceof Map ? (Map<String, Object>) configs : null;
    }

    /**
     * 写出单个工具的 GA 协议字段（{@code defer_loading} / {@code eager_input_streaming} /
     * {@code allowed_callers} / {@code input_examples} 等）。
     *
     * <p>不做字段白名单，只拦保留字段：协议 {@code Tool} 的字段面仍在变长，
     * 白名单会把新增字段一并拦掉而需要改代码。</p>
     *
     * @since 4.1
     */
    private static void applyToolProtocolFields(ONode toolNode, Map<String, Object> toolConfigs, String toolName) {
        if (Utils.isEmpty(toolConfigs) || Utils.isEmpty(toolName)) {
            return;
        }
        Object config = toolConfigs.get(toolName);
        if (config instanceof Map == false) {
            return;
        }

        for (Map.Entry<?, ?> kv : ((Map<?, ?>) config).entrySet()) {
            String field = kv.getKey() == null ? null : String.valueOf(kv.getKey());
            if (Utils.isEmpty(field) || kv.getValue() == null || TOOL_RESERVED_FIELDS.contains(field)) {
                continue;
            }
            toolNode.set(field, ONode.ofBean(kv.getValue()));
        }
    }
}
