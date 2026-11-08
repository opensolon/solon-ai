/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness.input;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.command.BufferedCommandContextFactory;
import org.noear.solon.ai.harness.command.CommandContextFactory;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.chat.content.MediaBlock;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.lang.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 一次输入的完整上下文
 * <p>
 * 这是「完整输入体验」的专用参数对象：宿主持有原始输入与端特性（模型选择、附件、回复路由），
 * 引擎据此完成命令识别、子代理解析、附件装配与 Prompt 构建。
 *
 * <p>示例：</p>
 * <pre>{@code
 * InputAttachments atts = InputAttachments.of()
 *         .media(ImageBlock.ofBase64(base64, "image/png").filePath(".uploads/a.png"))
 *         .reference(".uploads/report.pdf");
 *
 * InputOutcome outcome = engine.handle(HarnessInput.of(session, "帮我看看这个 @coder")
 *         .selectedModel("gpt-4o")
 *         .attachments(atts));
 *
 * // 经宿主骨架提交时不带会话（由 HarnessHost 查归属、加载会话后绑定，见 copyFor）：
 * host.submit(tenant, sid, HarnessInput.of("帮我看看这个")
 *         .principal(userId)                // 校验会话主体
 *         .whenBusy(BusyPolicy.STEER)       // 忙时先插话，插不进再排队
 *         .selectedModel("gpt-4o"));
 *
 * switch (outcome.getKind()) {
 *     case TASK:   // 有任务：先渲染回执（若有），再执行
 *         if (outcome.hasReply()) {
 *             render(outcome.getCommandOutput());
 *         }
 *         outcome.getRequest().stream().subscribe(event -> render(event));
 *         break;
 *     case REPLY:  // 只有回执（命令已执行，或命令在本渠道不可用）
 *         render(outcome.getCommandOutput());
 *         break;
 *     default:     // HITL / IGNORED
 *         break;
 * }
 * }</pre>
 *
 * @author noear
 * @since 4.2
 */
public final class HarnessInput {
    /** 目标会话；为空时请求装配走临时会话（HITL 决策写入等会话侧操作为空操作） */
    private final AgentSession session;
    /** 原始输入文本：命令识别、提及解析与 Prompt 构建的数据源；HITL 决策与续流路径不消费 */
    private final String text;

    /** 附件装配结果，永不为 null（构造器初始化，attachments(null) 不覆盖）；有附件时不作命令处理 */
    private InputAttachments attachments;
    /** 显式指定的模型名；为空时回退会话级选择，再回退默认模型（resolveSessionModelName） */
    private String selectedModel;
    /**
     * 选择器传入的子代理名，优先级随路径不同：HITL 决策与续流路径最高
     * （resolveHitlAgent / handle 第 2 步），消息路径仅作提及未命中时的兜底。
     */
    private String selectedAgent;
    /**
     * 本次请求显式选中的技能（标识支持 aliasPath / {@code @aliasPath} / 唯一短名）。
     * <p>
     * null 表示不限制（沿用全量技能目录）。空数组归一为 null：
     * 「传空数组」与「不传」等价，避免误把全部技能关掉；要禁用技能请直接禁用技能 Talent。
     */
    private List<String> selectedSkills;
    /** 本次请求显式选中的 MCP 服务名；null 表示不限制（沿用全部已注册服务） */
    private List<String> selectedMcps;

    private InputScope scope;
    /** scope 是解析器回写的（非显式） */
    private boolean scopeResolved;
    /** scope 是从积压还原的显式作用域 */
    private boolean scopeRestored;
    /** 命令上下文工厂；为空时 handle 内部落为 BufferedCommandContextFactory（回执缓冲进 commandOutput） */
    private CommandContextFactory commandContextFactory;
    /** 渠道来源身份；消费于命令分发（回执路由）与 Prompt 的 source 元数据；null 归一化为 {@link InputOrigin#NONE} */
    private InputOrigin origin = InputOrigin.NONE;
    /** HITL 决策动作：非空即进入决策分支（优先于空输入判空）；线上字符串到枚举的解析由宿主完成（{@link HITLAction#of}） */
    private HITLAction hitlAction;
    /** HITL 决策目标任务标识；非空时按 callId 精确取挂起任务，为空时取当前第一个挂起任务 */
    private String hitlCallId;
    /** 工具返回值改写文本；非空时作为决策备注写入，由拦截器覆写工具返回（见 {@link #getHitlOutput()}） */
    private String hitlOutput;

    /** 子代理名格式校验；对提及名与 selectedAgent 兜底名都生效，不通过或表中不存在均判无效 */
    private Predicate<String> agentNameValidator;
    /** 剥离提及后是否去掉剩余文本首尾空白；注意即使未命中提及，只要本值为 true 也会 trim 全文 */
    private boolean trimContent;
    /**
     * 请求体显式指定的子代理，优先级高于输入里的提及；引擎不再查子代理表
     * （有效性由宿主先行校验），仅剥离同名前缀；续流路径中作为 selectedAgent 缺席时的回退。
     */
    private String explicitAgent;
    /** 是否识别命令前缀（默认 true）；有附件时无论本值如何都不作命令处理 */
    private boolean commandAware = true;
    /** 是否解析输入开头的 {@code @} 提及（默认 true）；false 时原文保留，只认 explicitAgent 与 selectedAgent */
    private boolean agentAware = true;
    /** 渠道是否具备终端交互能力（默认 false）；经分发器成为 CommandContext.isTerminal()，控制 terminalOnly 命令 */
    private boolean terminal;
    /** 续流模式：不产生新输入，仅按会话挂起的 tool_calls 续跑；文本与附件均被忽略 */
    private boolean resume;
    /** 程序代替用户发起的一轮（{@link #ofProgram}）：忙时直接拒绝、不进积压 */
    private boolean program;
    /** 会话忙时的处理方式（缺省 QUEUE；程序轮次与续流总是拒绝） */
    private BusyPolicy whenBusy = BusyPolicy.QUEUE;
    /** 提交者主体（登录身份）：非空时必须与会话主体一致，否则拒绝（SCOPE_UNAVAILABLE） */
    private String principal;
    /** 宿主域附加属性（如推理强度、思考模式）：引擎不解读，进积压时原样保存、续发时原样带回 */
    private Map<String, String> attrs;

    private HarnessInput(AgentSession session, String text) {
        this.session = session;
        this.text = text;
        this.attachments = InputAttachments.of();
    }

    /**
     * 创建输入上下文
     *
     * @param session 会话（可为空，由请求使用临时会话）
     * @param text    原始输入（可包含命令前缀与子代理提及前缀）
     */
    public static HarnessInput of(@Nullable AgentSession session, @Nullable String text) {
        return new HarnessInput(session, text);
    }

    /**
     * 创建不带会话的输入（交给 {@code HarnessHost.submit} 的写法：骨架查归属、加载会话后经 {@link #copyFor} 绑定）。
     *
     * <p>直接交给 {@code engine.handle} 时等同 {@code of(null, text)}：走临时会话。</p>
     *
     * @param text 原始输入（可包含命令前缀与子代理提及前缀；只带附件时可为 null）
     * @since 4.2.0
     */
    public static HarnessInput of(@Nullable String text) {
        return new HarnessInput(null, text);
    }

    /**
     * 复制一份并绑定到指定会话（其余参数原样带上；附件与宿主域属性复制一份，互不影响）。
     *
     * <p>宿主骨架用：引擎在受理时会把解析出的作用域写回输入、扩展也可能在 {@code onInput} 里就地修改，
     * 复制后调用方的对象不被改动，同一个输入对象可以被并发复用。</p>
     *
     * @param session 目标会话
     * @since 4.2.0
     */
    public HarnessInput copyFor(@Nullable AgentSession session) {
        HarnessInput c = new HarnessInput(session, text);
        c.attachments = attachments.copy();
        c.selectedModel = selectedModel;
        c.selectedAgent = selectedAgent;
        c.selectedSkills = selectedSkills;
        c.selectedMcps = selectedMcps;
        c.scope = scope;
        c.scopeResolved = scopeResolved;
        c.scopeRestored = scopeRestored;
        c.commandContextFactory = commandContextFactory;
        c.origin = origin;
        c.hitlAction = hitlAction;
        c.hitlCallId = hitlCallId;
        c.hitlOutput = hitlOutput;
        c.agentNameValidator = agentNameValidator;
        c.trimContent = trimContent;
        c.explicitAgent = explicitAgent;
        c.commandAware = commandAware;
        c.agentAware = agentAware;
        c.terminal = terminal;
        c.resume = resume;
        c.program = program;
        c.whenBusy = whenBusy;
        c.principal = principal;
        c.attrs = attrs == null ? null : new LinkedHashMap<>(attrs);
        return c;
    }

    /**
     * 创建「程序代替用户发起一轮」的输入（goal / loop 等调度插件与宿主自动化使用）。
     *
     * <p>等价于 {@code of(session, text).commandAware(false).agentAware(false).origin(InputOrigin.channel(channel))}：
     * 正文不按命令或 {@code @子代理} 解析（程序生成的 prompt 不应被意外解释），并以 {@code channel} 标注来源。</p>
     *
     * @param session 会话
     * @param text    正文（原样作为 prompt）
     * @param channel 来源通道（如 {@code "goal"}、{@code "loop"}；可为 null）
     * @since 4.2.0
     */
    public static HarnessInput ofProgram(@Nullable AgentSession session, @Nullable String text, @Nullable String channel) {
        HarnessInput input = new HarnessInput(session, text)
                .commandAware(false)
                .agentAware(false)
                .origin(InputOrigin.channel(channel));
        input.program = true;
        return input;
    }

    /**
     * 是否为程序代替用户发起的一轮（{@link #ofProgram} 创建）。
     *
     * <p>受理口径：会话忙时直接 {@link InputOutcome.Kind#REJECTED}（{@code "busy"}），不进积压（不论 {@link #getWhenBusy()}）——
     * 程序轮次有自己的重试节奏，入积压反而会抢在用户消息前面或重复执行。</p>
     *
     * @since 4.2.0
     */
    public boolean isProgram() {
        return program;
    }

    /**
     * 会话忙时怎么处理这条输入（缺省 {@link BusyPolicy#QUEUE}；null 视为缺省）。
     *
     * <p>{@code QUEUE} / {@code REJECT} 由引擎在受理的会话锁内执行；{@code STEER} 由宿主骨架在受理前尝试，
     * 直接调 {@code engine.handle} 时按 {@code QUEUE} 处理。程序轮次与续流总是拒绝。</p>
     *
     * @since 4.2.0
     */
    public HarnessInput whenBusy(@Nullable BusyPolicy whenBusy) {
        this.whenBusy = whenBusy == null ? BusyPolicy.QUEUE : whenBusy;
        return this;
    }

    /**
     * 会话忙时的处理方式（永不为 null）
     *
     * @since 4.2.0
     */
    public BusyPolicy getWhenBusy() {
        return whenBusy;
    }

    /**
     * 提交者的主体（登录身份）：非空时必须与会话主体一致（{@code engine.getSessionPrincipal}），
     * 否则受理前拒绝（{@code SCOPE_UNAVAILABLE}，detail 为 {@code "principal mismatch"}）。
     *
     * <p>多用户 SaaS 应当总是传：sessionId 来自客户端，不校验就能用别人的会话。
     * 不传（null / 空）表示不校验；进积压时不保存（受理时已校验过）。</p>
     *
     * @since 4.2.0
     */
    public HarnessInput principal(@Nullable String principal) {
        this.principal = principal;
        return this;
    }

    /**
     * 提交者的主体（未设置时为 null）
     *
     * @since 4.2.0
     */
    @Nullable
    public String getPrincipal() {
        return principal;
    }

    /**
     * 附加一项宿主域属性（引擎不解读；null 值即移除）。
     *
     * <p>用于「忙时进积压、空闲后续发」也要带上的宿主参数（如推理强度、思考模式）：
     * 积压实现原样保存、续发时原样带回。</p>
     *
     * @since 4.2.0
     */
    public HarnessInput attr(String key, @Nullable String value) {
        if (key == null) {
            return this;
        }

        if (value == null) {
            if (attrs != null) {
                attrs.remove(key);
            }
        } else {
            if (attrs == null) {
                attrs = new LinkedHashMap<>();
            }
            attrs.put(key, value);
        }

        return this;
    }

    /**
     * 批量附加宿主域属性（null 忽略；null 值的项即移除）。
     *
     * @since 4.2.0
     */
    public HarnessInput attrs(@Nullable Map<String, String> values) {
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                attr(entry.getKey(), entry.getValue());
            }
        }
        return this;
    }

    /**
     * 宿主域属性（未设置时为 null）。
     *
     * @since 4.2.0
     */
    @Nullable
    public String getAttr(String key) {
        return attrs == null || key == null ? null : attrs.get(key);
    }

    /**
     * 全部宿主域属性（只读；未设置时为空表，永不为 null）。
     *
     * @since 4.2.0
     */
    public Map<String, String> getAttrs() {
        return attrs == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(attrs);
    }

    // ========== 读取 ==========

    public AgentSession getSession() {
        return session;
    }

    /**
     * 原始输入
     */
    public String getText() {
        return text;
    }

    /**
     * 附件装配结果（多媒体项 + 文件引用），永不为空对象
     */
    public InputAttachments getAttachments() {
        return attachments;
    }

    public boolean hasAttachments() {
        return attachments.hasAny();
    }

    /**
     * 显式指定的模型名（为空时回退会话级选择，再回退默认模型）
     */
    public String getSelectedModel() {
        return selectedModel;
    }

    /**
     * 选择器传入的子代理名（仅当输入未显式提及时作为兜底）
     */
    public String getSelectedAgent() {
        return selectedAgent;
    }

    /**
     * 本次输入选中的技能清单（未指定时返回空集合，永不返回 null）
     * <p>
     * 空集合的语义是「不限制」，不是「全部禁用」。
     */
    public List<String> getSelectedSkills() {
        return selectedSkills == null ? Collections.emptyList() : selectedSkills;
    }

    /**
     * 本次输入是否显式指定了技能视野（区分「未指定」与「指定了但被归一为空」）
     */
    public boolean hasSkillSelection() {
        return selectedSkills != null;
    }

    /**
     * 本次输入选中的 MCP 服务名清单（未指定时返回空集合，永不返回 null）
     */
    public List<String> getSelectedMcps() {
        return selectedMcps == null ? Collections.emptyList() : selectedMcps;
    }

    /**
     * 本次输入是否显式指定了 MCP 视野
     */
    public boolean hasMcpSelection() {
        return selectedMcps != null;
    }

    /**
     * 本次输入的渠道来源身份（永不返回 null，无来源时为 {@link InputOrigin#NONE}）
     */
    public InputOrigin getOrigin() {
        return origin;
    }

    /**
     * HITL 决策动作，为空表示本次输入不是审批
     */
    public HITLAction getHitlAction() {
        return hitlAction;
    }

    /**
     * HITL 决策目标的任务标识（可为空，为空时回退到当前挂起任务）
     */
    public String getHitlCallId() {
        return hitlCallId;
    }

    /**
     * HITL 决策附带的工具返回值改写文本（可为空）
     * <p>
     * 桌面端「批准并改写工具结果」用它：非空时作为决策备注写入，
     * 由 HITLInterceptor 覆写工具返回（见 {@code HITLDecision.getComment()}）。
     */
    public String getHitlOutput() {
        return hitlOutput;
    }

    /** 决策动作非空即本次输入是审批（枚举时代空字符串不存在，无需 isEmpty 判断） */
    public boolean isHitlDecision() {
        return hitlAction != null;
    }

    // ========== 链式设置 ==========

    public HarnessInput selectedModel(@Nullable String selectedModel) {
        this.selectedModel = selectedModel;
        return this;
    }

    /**
     * 本次请求生效的子代理（宿主已解析的结果，优先于输入里的 {@code @} 提及）。
     * <p>
     * 与 {@link #explicitAgent} 的区别：本方法传入已是「生效名」（含 {@code @} 解析结果），
     * 引擎会再次尝试从正文剥离同名前缀；{@code explicitAgent} 表示宿主已自行剥离正文，
     * 引擎不再改动正文。
     */
    public HarnessInput selectedAgent(@Nullable String selectedAgent) {
        this.selectedAgent = selectedAgent;
        return this;
    }

    /**
     * 本次请求生效的技能视野（宿主选择器 / 前端勾选的结果）
     *
     * <p>传入技能标识，支持三种写法：完整 aliasPath（{@code @user-skills/x}）、
     * 带前缀写法、唯一短名（{@code x}）。引擎不做前缀补全也不做存在性校验，
     * 解析口径统一交给 {@code SkillCatalog}；不存在的名称会被忽略（不抛异常），
     * 以免一个过期技能名打死整轮对话。</p>
     *
     * <p>不调用本方法 = 不限制；调用但传空 = 重置为不限制。</p>
     *
     */
    public HarnessInput selectedSkill(@Nullable String... names) {
        this.selectedSkills = normalizeSelection(names);
        return this;
    }

    /**
     * 本次请求生效的 MCP 服务视野（按 {@code addMcpServer} 时的配置名匹配）
     *
     * <p>不调用本方法 = 不限制（全部已注册服务可见）；调用但传空 = 重置为不限制。</p>
     *
     */
    public HarnessInput selectedMcp(@Nullable String... names) {
        this.selectedMcps = normalizeSelection(names);
        return this;
    }

    /**
     * 本次请求的作用域（只能收窄；强制边界）。未设置时由引擎的 {@code scopeResolver} 解析。
     *
     * @since 4.2.0
     */
    public HarnessInput scope(@Nullable InputScope scope) {
        this.scope = scope;
        this.scopeResolved = false;
        this.scopeRestored = false;
        return this;
    }

    /**
     * 本轮生效的作用域：显式携带的，或受理时由解析器解析并回写的（见 {@link #isScopeExplicit()}）。
     *
     * @since 4.2.0
     */
    @Nullable
    public InputScope getScope() {
        return scope;
    }

    /**
     * 作用域是否由调用方显式携带（含扩展在 {@code onInput} 里设置的、从积压还原的）。
     * 解析器解析回写的不算显式：积压只保存显式作用域，解析出来的续发时重新解析（权限变更立即生效）。
     *
     * @since 4.2.0
     */
    public boolean isScopeExplicit() {
        return scope != null && scopeResolved == false;
    }

    /**
     * 作用域是否从积压还原（续发时与解析器最新结果取交集，只会变窄）
     *
     * @since 4.2.0
     */
    public boolean isScopeRestored() {
        return scope != null && scopeRestored;
    }

    /**
     * 积压实现还原输入时用：设置从积压保存的显式作用域（见 {@code InputScope#fromSpec}）。
     * 引擎续发时会把它与解析器的最新结果取交集。
     *
     * @since 4.2.0
     */
    public HarnessInput restoredScope(@Nullable InputScope scope) {
        this.scope = scope;
        this.scopeResolved = false;
        this.scopeRestored = scope != null;
        return this;
    }

    /**
     * 引擎内部使用：受理时把解析器的结果回写到输入（便于宿主回读），不算显式作用域。
     *
     * @since 4.2.0
     */
    public HarnessInput resolvedScope(@Nullable InputScope scope) {
        this.scope = scope;
        this.scopeResolved = scope != null;
        this.scopeRestored = false;
        return this;
    }

    /**
     * 空数组归一为 null（= 不限制），而不是空集合：
     * 「显式传空」与「不传」等价，避免宿主序列化空列表时把孩子技能全关掉。
     */
    private static List<String> normalizeSelection(@Nullable String[] names) {
        if (names == null || names.length == 0) {
            return null;
        }

        // trim + 丢空 + 去重保序：宿主从前端勾选传上来的值带空格或重复是常态；
        // 返回值必须不可变（Talent 侧多请求共享读取），否则调用方能反手改掉视野。
        Set<String> normalized = new LinkedHashSet<>(names.length);
        for (String name : names) {
            if (name == null) {
                continue;
            }

            String trimmed = name.trim();
            if (trimmed.length() > 0) {
                normalized.add(trimmed);
            }
        }

        return normalized.isEmpty() ? null : Collections.unmodifiableList(new ArrayList<>(normalized));
    }

    /**
     * 命令上下文工厂（由宿主实现）。为空时使用缓冲式默认实现
     * {@link BufferedCommandContextFactory}。
     * <p>
     * 与 {@link #commandAware} 配套：前者决定「是否识别命令」，本方法决定
     * 「识别到命令后回执怎么落地」。两者都为空/默认时，宿主无需关心命令体系。
     */
    public HarnessInput commandContextFactory(@Nullable CommandContextFactory commandContextFactory) {
        this.commandContextFactory = commandContextFactory;
        return this;
    }

    /**
     * 命令上下文工厂；为空表示使用缓冲式默认实现
     */
    public CommandContextFactory getCommandContextFactory() {
        return commandContextFactory;
    }

    /**
     * 附件装配结果（由宿主落盘并构造：多媒体项 + 文件引用）
     * <p>
     * 落盘与路径校验由宿主负责（各端规则不同），引擎只负责提示词装配。
     *
     * @see InputAttachments
     */
    public HarnessInput attachments(@Nullable InputAttachments attachments) {
        if (attachments != null) {
            this.attachments = attachments;
        }
        return this;
    }

    /**
     * 追加一个多媒体块（已落盘时请先把相对路径写到 {@code block.filePath(...)}）
     *
     * @see InputAttachments#media(MediaBlock)
     */
    public HarnessInput media(MediaBlock block) {
        attachments.media(block);
        return this;
    }

    /**
     * 追加一个文件引用（工作区相对路径，以 {@code [附件: path]} 告知模型）
     *
     * @see InputAttachments#reference(String)
     */
    public HarnessInput reference(String path) {
        attachments.reference(path);
        return this;
    }

    /**
     * 渠道来源身份（IM 等远程渠道使用）
     */
    public HarnessInput origin(@Nullable InputOrigin origin) {
        this.origin = origin == null ? InputOrigin.NONE : origin;
        return this;
    }

    /**
     * HITL 审批决策
     * <p>
     * 线上协议是字符串（WS 报文 / HTTP 参数），解析由宿主在交给引擎前完成：
     * {@code .hitl(HITLAction.of(action), callId)}，脏值在边界处显式失败。
     *
     * @param action 决策动作（approve / reject / skip）
     * @param callId 目标任务标识（可为空）
     * @see HITLAction#of(String)
     */
    public HarnessInput hitl(@Nullable HITLAction action, @Nullable String callId) {
        this.hitlAction = action;
        this.hitlCallId = callId;
        return this;
    }

    /**
     * HITL 决策附带的工具返回值改写文本（可为空，为空时按原决策执行）
     *
     * @see #hitl(HITLAction, String)
     */
    public HarnessInput hitlOutput(@Nullable String hitlOutput) {
        this.hitlOutput = hitlOutput;
        return this;
    }

    /**
     * 子代理名格式校验（如宿主端的字符集/长度限制），为空时只按子代理表判断
     */
    public HarnessInput agentNameValidator(@Nullable Predicate<String> validator) {
        this.agentNameValidator = validator;
        return this;
    }

    public Predicate<String> getAgentNameValidator() {
        return agentNameValidator;
    }

    /**
     * 剥离提及后是否去掉首尾空白（桌面端需要，CLI/Web 保持既有口径）
     */
    public HarnessInput trimContent(boolean trimContent) {
        this.trimContent = trimContent;
        return this;
    }

    public boolean isTrimContent() {
        return trimContent;
    }

    /**
     * 请求体显式指定的子代理（优先级高于输入里的提及）。
     * <p>
     * 与 {@link #selectedAgent} 的区别：本方法表示正文已由宿主自行剥离（引擎不再解析 {@code @} 提及），
     * 仅用作生效子代理；有效性由调用方先行校验。
     */
    public HarnessInput explicitAgent(@Nullable String explicitAgent) {
        this.explicitAgent = explicitAgent;
        return this;
    }

    public String getExplicitAgent() {
        return explicitAgent;
    }

    /**
     * 是否把输入当作「可能以命令前缀开头」来识别（默认 true）
     * <p>
     * 置为 false 时引擎跳过命令分发，输入原样按消息处理。适用于「文本来源不是用户手输」的路径
     * （如定时任务提示词、已受理的排队输入）：这些文本即使以命令前缀开头，也不应被当作命令执行。
     */
    public HarnessInput commandAware(boolean commandAware) {
        this.commandAware = commandAware;
        return this;
    }

    public boolean isCommandAware() {
        return commandAware;
    }

    /**
     * 是否解析输入开头的子代理提及（默认 true）
     * <p>
     * 与 {@link #commandAware} 成对：`{@code /}` 前缀表示命令，`{@code @}` 前缀表示子代理。
     * 置为 false 时原文原样保留，子代理只取显式指定值与选择器传入值。适用于「文本由程序生成」
     * 的路径（如 goal objective）：即使以 {@code @xxx} 开头，也不应被当作子代理提及剥离，
     * 而应作为普通提示词交给代理自己识别。
     */
    public HarnessInput agentAware(boolean agentAware) {
        this.agentAware = agentAware;
        return this;
    }

    public boolean isAgentAware() {
        return agentAware;
    }

    /**
     * 按 {@code handle} 的同一口径解析 {@code @} 子代理提及
     *
     * <p>宿主有时必须<b>提前</b>拿到解析结果才能决定后续动作——典型场景是桌面端：
     * 它要在交给引擎之前完成 Goal 拦截、子代理名格式校验与规划模式文本改写，
     * 这三件都作用在「剥离提及之后的正文」上。</p>
     *
     * <p>此前这类宿主只能自己拼一份 {@link AgentMentions.Options}，结果是同一段输入
     * 在宿主与引擎里<b>各解析一次、口径各不相同</b>（一个 trim 一个不 trim）。
     * 本方法存在的意义就是让那份 {@code Options} 只有一处构造：{@code handle} 内部
     * 也走这里，宿主的提前解析因此与最终生效结果<b>逐字一致</b>。</p>
     *
     * <p>返回值可直接用于改写正文：宿主把 {@code mention.getText()} 作为新输入重新构造
     * {@code HarnessInput} 并带上 {@code explicitAgent(mention.getAgentName())}，
     * 引擎二次解析时不会重复剥离（幂等，见 {@code stripMention}）。</p>
     */
    public AgentMentions.Mention parseMention(HarnessEngine engine) {
        return AgentMentions.parse(engine, session, text,
                AgentMentions.Options.builder()
                        .prefix(engine.getSettings().getAgentPrefix())
                        .explicitAgent(explicitAgent)
                        .selectedAgent(selectedAgent)
                        .nameValidator(agentNameValidator)
                        .agentAware(agentAware)
                        .trimContent(trimContent));
    }

    /**
     * 本次输入所在渠道是否具备命令行终端交互能力（默认 false）
     * <p>
     * 决定 {@link org.noear.solon.ai.harness.command.Command#terminalOnly()} 的命令能否可见、可执行：
     * 为 false 时分发器直接拦截并回执统一提示，{@code /help} 也不列出这类命令。
     * CLI 宿主置 true；Web/IM/桌面等缓冲式宿主保持默认。
     * <p>
     * 注意这与 {@link #origin} 无关：桌面端是本地进程但无终端能力，CLI 有渠道标识且具备终端能力。
     * 引擎读取本值后经分发器交给 {@link org.noear.solon.ai.harness.command.CommandContext#isTerminal()}，
     * 保证「拦截判据」与「上下文自述」同源。
     */
    public HarnessInput terminal(boolean terminal) {
        this.terminal = terminal;
        return this;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /**
     * 续流模式（默认 false）：本次不产生新用户输入，仅按会话中挂起的 tool_calls 继续执行
     * <p>
     * 文本与附件均被忽略。两种用法：
     * <ul>
     * <li>与 {@link #hitl(HITLAction, String)} 合用 —— 决策写入后<b>尝试</b>续流，
     * 是否真的续流由引擎自检决定：本批挂起项已齐备时返回 {@link InputOutcome.Kind#TASK}，
     * 否则返回 {@link InputOutcome.Kind#HITL}（剩余未决数读 {@code getPendingHitlCount()}）。
     * 批挂起时宿主应逐张提问，直到拿到 {@code TASK}；</li>
     * <li>单独使用 —— 不写决策，直接按挂起状态续跑（如宿主已批量写完全部决策后统一恢复）。</li>
     * </ul>
     * <p>
     * 注意：{@code resume} 只表达宿主意愿，不保证续流一定发生。判定条件见
     * {@link InputOutcome.Kind#HITL} 的三条说明（决策未命中、本批未齐备、未传 resume）。
     */
    public HarnessInput resume(boolean resume) {
        this.resume = resume;
        return this;
    }

    public boolean isResume() {
        return resume;
    }
}
