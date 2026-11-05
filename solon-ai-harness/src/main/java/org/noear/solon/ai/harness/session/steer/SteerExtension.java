/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.steer;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.input.InputOrigin;

/**
 * Steer 一键装配扩展 —— steer 能力从头到尾只接触这一个类。
 *
 * <p>{@code engine.addExtension(new SteerExtension())} 一步完成：
 * 注册 /steer 命令（幂等）+ 在<b>每次顶层请求</b>装配时挂上 {@link SteerInterceptor}。
 * 宿主不再需要每请求 {@code new SteerInterceptor(...) + interceptorAdd(...)}。</p>
 *
 * <p><b>为何走请求级挂载（而非默认拦截器）</b>：面板/提及选中的子代理也是一个顶层请求，
 * 此时目标代理不是主代理，挂在主代理默认拦截器上的组件根本不会生效，就会出现
 * 「选了子代理就不能插话」（steer 回 TURN_CHANGED 后被降级为排队）。
 * 挂到请求级 options 后，无论目标代理是主代理还是面板子代理都能命中；
 * 同时按 {@code isDelegated} 排除 {@code task}/{@code multitask} 委派任务——
 * 那些任务跑在临时会话上，没有前端邮箱语义，不应挂。</p>
 *
 * <p><b>挂载顺序安全</b>：请求级 options 的拦截器在 AgentFactory 的代理默认拦截器
 * （压缩/StopLoop）之后追加，因此 steer 拦截器天然排在上下文压缩之后——注入是向压缩产物
 * 尾部追加，不破坏消息序列。同类型按 Class 去重，重复挂载无双份。</p>
 *
 * <p><b>输出走 AgentEvent</b>：{@code steer_applied} / {@code steer_dropped} 以
 * {@link SteerAppliedEvent} / {@link SteerDroppedEvent} 推入流，与 HITL、上下文压缩
 * 同一范式。宿主按事件类型映射为自身协议（Web 映射为 {@code system.steer_applied} /
 * {@code system.steer_dropped}），不认识的宿主零成本忽略。因此本扩展不需要任何
 * 端口注入、不需要每请求 {@code new SteerInterceptor(...)}，{@code new SteerExtension()}
 * 一个构造器适配全部宿主。</p>
 *
 * <p><b>未消费插话的持久化在 harness 内完成</b>（转排队，QueueHelper），
 * 与有没有订阅者无关：CLI / Desktop 也能把残留插话转为排队任务，不会丢。</p>
 *
 * <p>插话投递（Web API / IM 命令共用）与取消直接在本类上中转。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public class SteerExtension extends AbsHarnessExtension {

    private final SteerInterceptor interceptor = new SteerInterceptor();

    private final SteerCommand steerCommand = new SteerCommand();

    /** 容量 / 长度约束（缺省 {@link SteerLimits#DEFAULT}，可经 {@link #builder()} 覆盖） */
    private final SteerLimits limits;

    /**
     * 宿主引擎（{@link #onAttach} 时注入）。
     *
     * <p>持有点的作用是让「忙判定」有唯一权威口径：此前 {@code steer(..., busyRecheck)}
     * 把回调外包给宿主，而答案明明就在 {@code engine.status(session).isBusy()}
     * （{@link org.noear.solon.ai.harness.session.SessionStatus}）。宿主因此要传一个
     * 与自己无关的 lambda，且两处宿主的口径还可能漂移。现改为缺省自算，
     * 回调保留给「口径确实不同」的高级场景。</p>
     *
     * <p>未 attach 时为 null（纯单测直接 new 出来的门面），此时忙判定不过滤。</p>
     */
    private HarnessEngine engine;

    /**
     * 缺省构造器：输出走 AgentEvent，无需注入任何宿主端口。
     */
    public SteerExtension() {
        this(SteerLimits.DEFAULT);
    }

    private SteerExtension(SteerLimits limits) {
        this.limits = limits == null ? SteerLimits.DEFAULT : limits;
    }

    /** 构建器：仅用于覆盖容量 / 长度约束，其余全有缺省。 */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void onAttach(HarnessEngine engine) {
        this.engine = engine;
        engine.getCommandRegistry().register(steerCommand);
    }

    /** 本扩展生效的容量 / 长度约束 */
    public SteerLimits getLimits() {
        return limits;
    }

    @Override
    public void onRequestAssemble(HarnessEngine engine, boolean isDelegated, String agentName, ReActOptionsAmend optionsAmend) {
        if (isDelegated == false) {
            optionsAmend.interceptorAdd(interceptor);
        }
    }

    @Override
    public void setEnabled(Boolean enabled) {
        super.setEnabled(enabled);
        steerCommand.setEnabled(enabled);
        interceptor.setEnabled(enabled);
    }

    /**
     * 开关语义（与命令层一致）：{@link #steer} 在停用时返回
     * {@link SteerStatus#DISABLED}；{@link #cancel} 等清理类接口不受控（不得阻止取消）。
     */

    // ==================== 操作门面（中转静态实现） ====================

    /** 组装好的拦截器（挂载已由扩展完成；仅供确需细粒度访问的宿主）。 */
    public SteerInterceptor getInterceptor() {
        return interceptor;
    }

    /**
     * 插话入队（唯一实现的中转，与 Web /web/chat/steer、/steer 命令同源）。
     *
     * <p><b>推荐宿主使用 {@link #steer(AgentSession, String, String, String, InputOrigin)}
     * 这个 5 参重载</b>：忙判定由内核按 {@code engine.status(session).isBusy()} 自算。
     * 本重载保留给「忙判定口径与引擎不一致」的高级场景（例如宿主还想把「输入正在受理窗口」
     * 也算作忙），传 null 表示不做 offer 后复查。</p>
     *
     * @see SteerHelper#steer(AgentSession, String, String, String, InputOrigin, java.util.function.BooleanSupplier)
     */
    public SteerResult steer(AgentSession session, String runId, String steerId,
                                              String text, InputOrigin origin,
                                              java.util.function.BooleanSupplier busyRecheck) {
        return steer(session, runId, steerId, text, origin, busyRecheck, false);
    }

    /**
     * 插话入队（忙判定用内核缺省口径）。
     *
     * <p>{@code busyRecheck} 从「必填的宿主 lambda」降级为可选：不传时用
     * {@code engine.status(session).isBusy()}——这是 {@link org.noear.solon.ai.harness.session.SessionStatus}
     * 定义的权威口径，宿主再拼一遍只会产生漂移。</p>
     */
    public SteerResult steer(AgentSession session, String runId, String steerId,
                             String text, InputOrigin origin) {
        return steer(session, runId, steerId, text, origin, null, false);
    }

    /**
     * 严格轮次绑定投递：{@code runId} 必填，空即 {@link SteerStatus#TURN_CHANGED}。
     *
     * <p>Web 场景首选。任务的首个事件到达前无法确认归属，此时放行会让插话落入
     * 「任务结束 / 轮次切换」的窄窗，成为无人消费的孤儿。该语义此前由宿主机拼
     * （{@code ChatWebController} 里一条 if），既无类型提示也无从复用，故收归门面。</p>
     */
    public SteerResult steerOnRun(AgentSession session, String runId, String steerId,
                                  String text, InputOrigin origin) {
        return steer(session, runId, steerId, text, origin, null, true);
    }

    /**
     * 向当前活跃任务插话（省略 runId / steerId / busyRecheck 三个高级参数）。
     *
     * <p>「投给现在这条任务」是最常见语义：runId、steerId 均传 null 表示不比对轮次、
     * 由框架生成 id。</p>
     *
     * <p>来源缺省为 {@link InputOrigin#NONE}；需保留渠道身份时用三参重载。
     * 结果仍用 {@code isSteered/isBoxFull/isNotRunning()} 判断，本方法不做降级。</p>
     */
    public SteerResult steer(AgentSession session, String text) {
        return steer(session, text, InputOrigin.NONE);
    }

    /** 向当前活跃任务插话，携带来源身份（runId / steerId / busyRecheck 省略）。 */
    public SteerResult steer(AgentSession session, String text, InputOrigin origin) {
        return steer(session, null, null, text, origin == null ? InputOrigin.NONE : origin);
    }

    private SteerResult steer(AgentSession session, String runId, String steerId, String text,
                              InputOrigin origin, java.util.function.BooleanSupplier busyRecheck,
                              boolean requireRunId) {
        if (isEnabled() == false) {
            // 停用后仍投递会一直积压在邮箱（消费方拦截器已停用），必须当场拒绝。
            return new SteerResult(SteerStatus.DISABLED, steerId, 0);
        }

        java.util.function.BooleanSupplier busy = busyRecheck == null
                ? () -> defaultBusy(session) : busyRecheck;
        return SteerHelper.steer(session, runId, steerId, text, origin, busy, limits, requireRunId);
    }

    /**
     * 缺省忙判定：与 {@code SessionActivity.isBusy} 同口径（活跃流 或 输入受理窗口）。
     *
     * <p>未 attach（无 engine）时一律放行——纯单测 / 手工 new 的门面没有引擎可查，
     * 此时若返回 true 会把所有投递判成 NOT_RUNNING，与「无引擎即无忙概念」相悖。</p>
     */
    private boolean defaultBusy(AgentSession session) {
        if (engine == null) {
            return true;
        }
        return engine.status(session).isBusy() || engine.status(session).isAdmitting();
    }

    /** 取消仍留在邮箱中的插话（消费线程已 poll 出则返回 false，不得宣称取消成功）。 */
    public boolean cancel(AgentSession session, String steerId) {
        return SteerHelper.cancel(SteerHelper.steerBox(session), steerId);
    }

    /**
     * 批量取消仍留在邮箱中的插话，返回实际取消条数。
     *
     * <p>前端「清空待生效插话」此前要逐条发 {@code /web/chat/steer/cancel}，
     * 既多轮往返，也在中途失败时留下半截状态。已被消费的（不在邮箱中）不计入返回值。</p>
     */
    public int cancelAll(AgentSession session, java.util.Collection<String> steerIds) {
        java.util.Queue<SteerMessage> box = SteerHelper.steerBox(session);
        if (box == null || steerIds == null || steerIds.isEmpty()) {
            return 0;
        }

        int canceled = 0;
        for (String steerId : steerIds) {
            if (SteerHelper.cancel(box, steerId)) {
                canceled++;
            }
        }
        return canceled;
    }

    /**
     * 清空全部待生效插话，返回被移除的条目。
     *
     * <p>与 {@link #cancelAll} 的差别是语义：本方法不做任何「降级到哪里」的决定，
     * 只把邮箱交还调用方。调用方可自行决定转排队 / 丢弃 / 提示，避免框架替宿主做取舍。</p>
     */
    public java.util.List<SteerMessage> clearPending(AgentSession session) {
        java.util.Queue<SteerMessage> box = SteerHelper.steerBox(session);
        if (box == null) {
            return java.util.Collections.emptyList();
        }

        synchronized (box) {
            return SteerHelper.drain(box);
        }
    }

    /**
     * 待生效的插话快照（按投递顺序；无邮箱时返回空集合）
     * <p>
     * 与 {@link #cancel} 配对：宿主刷新/重连后靠它重建「可取消」列表，
     * 否则 steerId 只存在于投递返回值中，页面一刷就永远取消不了。
     */
    public java.util.List<SteerMessage> pending(AgentSession session) {
        return SteerHelper.pending(session);
    }

    /**
     * 当前活跃轮次标识（由拦截器在每次请求装配时写入会话）。
     *
     * <p>宿主的「取消插话」需要先校验请求里的 runId 是否仍是当前轮次：
     * 轮次已切换时旧 steerId 已无意义，必须回 TURN_CHANGED 而不是默默成功。
     * 该值原本只能读 {@code session.attrs()} 里的内部 key，字符串写错不会报错、
     * 只会静默失效，故收回到门面。</p>
     *
     * @return 当前轮次标识；无活跃轮次时为 null
     */
    public String activeRunId(AgentSession session) {
        if (session == null) {
            return null;
        }

        synchronized (session.attrs()) {
            Object value = session.attrs().get(SteerHelper.ATTR_ACTIVE_RUN_ID);
            return value instanceof String ? (String) value : null;
        }
    }

    /**
     * 插话交接：清理上一任务的轮次标识与邮箱，并把未消费的插话转入排队。
     *
     * <p><b>宿主义务</b>：每次「新任务开流」前必须调用一次（Web 侧在
     * {@code WebSessionRunner.beginStreamTurn}）。否则上一轮未消费的插话会在新任务
     * 第二轮被注入，破坏任务级隔离。框架无法可靠代办：{@code onAgentEnd} 在
     * interrupt / 异常路径不触发，正是这些路径最需要交接。</p>
     *
     * <p>为保证这条义务不被漏掉，{@link #beginTurn} 是本方法的显式命名别名——
     * 宿主只需记住「开流前 beginTurn」。</p>
     *
     * @return 迁移结果（永不为 null）；调用方无须判空
     */
    @SuppressWarnings("unchecked")
    public SteerHandover handover(AgentSession session) {
        if (session == null) {
            return SteerHandover.EMPTY;
        }

        java.util.Queue<SteerMessage> staleBox;
        synchronized (session.attrs()) {
            session.attrs().remove(SteerHelper.ATTR_ACTIVE_RUN_ID);
            Object box = session.attrs().remove(SteerHelper.ATTR_STEER_BOX);
            staleBox = box instanceof java.util.Queue ? (java.util.Queue<SteerMessage>) box : null;
        }

        if (staleBox == null || staleBox.isEmpty()) {
            return SteerHandover.EMPTY;
        }

        java.util.List<SteerMessage> stale = new java.util.ArrayList<>(staleBox);
        java.util.List<SteerMessage> failed = SteerHelper.enqueueIntoSessionQueue(session, stale);
        java.util.List<SteerMessage> queued = new java.util.ArrayList<>(stale);
        queued.removeAll(failed);

        return new SteerHandover(queued, failed);
    }

    /**
     * 开流前交接（{@link #handover} 的语义化别名）。
     *
     * <p>两次命名并存是刻意的：{@code handover} 描述「做什么」，{@code beginTurn}
     * 描述「何时做」。宿主接入文档只需说明「每次新任务开流前调 beginTurn」。</p>
     */
    public SteerHandover beginTurn(AgentSession session) {
        return handover(session);
    }

    /**
     * 插话交接结果（已成功转排队的 + 因队列满/超长而暂存待重试的）
     *
     * <p>两个列表都不为 null；均空时用 {@link #EMPTY} 表示「什么也没发生」。
     */
    public static final class SteerHandover {
        private static final SteerHandover EMPTY =
                new SteerHandover(java.util.Collections.emptyList(), java.util.Collections.emptyList());

        private final java.util.List<SteerMessage> queued;
        private final java.util.List<SteerMessage> failed;

        public SteerHandover(java.util.List<SteerMessage> queued, java.util.List<SteerMessage> failed) {
            this.queued = queued == null ? java.util.Collections.emptyList() : queued;
            this.failed = failed == null ? java.util.Collections.emptyList() : failed;
        }

        /** 已成功转为排队任务的插话 */
        public java.util.List<SteerMessage> getQueued() {
            return queued;
        }

        /** 转入排队失败的插话（已回填易失邮箱，下次开流会重试） */
        public java.util.List<SteerMessage> getFailed() {
            return failed;
        }

        public boolean isEmpty() {
            return queued.isEmpty() && failed.isEmpty();
        }
    }

    // ==================== 常量（避免宿主引用内部实现常量） ====================

    /**
     * 缺省的单条插话最大字符数。
     *
     * <p>宿主<b>不需要</b>用它做入参校验：投递内核会以同一份约束返回
     * {@link SteerStatus#TEXT_TOO_LONG}，宿主只映射应答码即可。本方法保留给
     * 「前端先行提示字数」这类展示用途，实际约束请读 {@link #getLimits()}。</p>
     */
    public static int maxTextLength() {
        return SteerHelper.MAX_TEXT_LENGTH;
    }

    /** 缺省的 steerId 最大字符数（实际约束请读 {@link #getLimits()}） */
    public static int maxIdLength() {
        return SteerHelper.MAX_ID_LENGTH;
    }

    /**
     * 该消息是否为插话产物（据元数据/前缀判定）。
     *
     * <p>宿主展示历史时需从消息里反推「这条是插话」，此前只能自己拼
     * {@code source=="steer"} + {@code "[用户实时补充] "} 前缀两个判断，
     * 任一常量改名就会静默判错。</p>
     */
    public static boolean isSteerMessage(org.noear.solon.ai.chat.message.ChatMessage message) {
        if (message == null) {
            return false;
        }

        String content = message.getContent();
        boolean marked = SteerHelper.STEER_SOURCE.equals(message.getMetadataAs("source"));
        boolean prefixed = content != null && content.startsWith(SteerHelper.STEER_PREFIX);
        return marked || prefixed;
    }

    /**
     * 剥掉插话前缀（无前缀时原样返回）。
     *
     * <p>与 {@link #isSteerMessage} 配套：宿主展示时要去掉注入用的前缀，
     * 但只对真有前缀的消息去——无条件按长度截断会吃掉正文。</p>
     */
    public static String stripSteerPrefix(String content) {
        if (content == null) {
            return null;
        }

        if (content.startsWith(SteerHelper.STEER_PREFIX)) {
            return content.substring(SteerHelper.STEER_PREFIX.length());
        }

        return content;
    }

    // ==================== Builder ====================

    /** 构建器：只覆盖容量 / 长度约束，其余全有缺省（等价于 {@code new SteerExtension()}）。 */
    public static final class Builder {
        private int maxBoxSize = SteerHelper.MAX_BOX_SIZE;
        private int maxTextLength = SteerHelper.MAX_TEXT_LENGTH;
        private int maxIdLength = SteerHelper.MAX_ID_LENGTH;

        private Builder() {
        }

        /** 邮箱容量上限（缺省 5）。频繁 steer 会加速上下文膨胀，高频场景应改用排队。 */
        public Builder maxBoxSize(int size) {
            this.maxBoxSize = size;
            return this;
        }

        /** 单条插话文本长度上限（缺省 4096）。 */
        public Builder maxTextLength(int length) {
            this.maxTextLength = length;
            return this;
        }

        /** steerId 长度上限（缺省 128）。 */
        public Builder maxIdLength(int length) {
            this.maxIdLength = length;
            return this;
        }

        public SteerExtension build() {
            return new SteerExtension(new SteerLimits(maxBoxSize, maxTextLength, maxIdLength));
        }
    }
}
