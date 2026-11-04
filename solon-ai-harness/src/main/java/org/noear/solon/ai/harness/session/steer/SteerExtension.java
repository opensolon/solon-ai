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

    /**
     * 唯一构造器：输出走 AgentEvent，无需注入任何宿主端口。
     */
    public SteerExtension() {
    }

    @Override
    public void onAttach(HarnessEngine engine) {
        engine.getCommandRegistry().register(steerCommand);
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
     * @see SteerInterceptor#steer(AgentSession, String, String, String, InputOrigin, java.util.function.BooleanSupplier)
     */
    public SteerResult steer(AgentSession session, String runId, String steerId,
                                              String text, InputOrigin origin,
                                              java.util.function.BooleanSupplier busyRecheck) {
        if (isEnabled() == false) {
            // 停用后仍投递会一直积压在邮箱（消费方拦截器已停用），必须当场拒绝。
            return new SteerResult(SteerStatus.DISABLED, steerId, 0);
        }
        return SteerInterceptor.steer(session, runId, steerId, text, origin, busyRecheck);
    }

    /**
     * 向当前活跃任务插话（省略 runId / steerId / busyRecheck 三个高级参数）。
     *
     * <p>「投给现在这条任务」是最常见语义：runId、steerId 均传 null 表示不比对轮次、
     * 由框架生成 id；busyRecheck 供宿主做二次忙判定，绝大多数调用方不需要。</p>
     *
     * <p>来源缺省为 {@link InputOrigin#NONE}；需保留渠道身份时用三参重载。
     * 结果仍用 {@code isSteered/isBoxFull/isNotRunning()} 判断，本方法不做降级。</p>
     */
    public SteerResult steer(AgentSession session, String text) {
        return steer(session, text, InputOrigin.NONE);
    }

    /** 向当前活跃任务插话，携带来源身份（runId / steerId / busyRecheck 省略）。 */
    public SteerResult steer(AgentSession session, String text, InputOrigin origin) {
        return steer(session, null, null, text, origin == null ? InputOrigin.NONE : origin, null);
    }

    /** 取消仍留在邮箱中的插话（消费线程已 poll 出则返回 false，不得宣称取消成功）。 */
    public boolean cancel(AgentSession session, String steerId) {
        return SteerInterceptor.cancel(SteerInterceptor.steerBox(session), steerId);
    }

    /**
     * 待生效的插话快照（按投递顺序；无邮箱时返回空集合）
     * <p>
     * 与 {@link #cancel} 配对：宿主刷新/重连后靠它重建「可取消」列表，
     * 否则 steerId 只存在于投递返回值中，页面一刷就永远取消不了。
     */
    public java.util.List<SteerMessage> pending(AgentSession session) {
        return SteerInterceptor.pending(session);
    }

    /**
     * 当前活跃轮次标识（由拦截器在每次请求装配时写入会话）。
     *
     * <p>宿主的「取消插话」需要先校验请求里的 runId 是否仍是当前轮次：
     * 轮次已切换时旧 steerId 已无意义，必须回 TURN_CHANGED 而不是默默成功。
     * 该值原本只能读 {@code session.attrs()} 里的内部 key（{@code web.activeRunId}），
     * 字符串写错不会报错、只会静默失效，故收回到门面。</p>
     *
     * @return 当前轮次标识；无活跃轮次时为 null
     */
    public String activeRunId(AgentSession session) {
        if (session == null) {
            return null;
        }

        synchronized (session.attrs()) {
            Object value = session.attrs().get(SteerInterceptor.ATTR_ACTIVE_RUN_ID);
            return value instanceof String ? (String) value : null;
        }
    }

    /**
     * 清理上一任务的轮次标识与插话邮箱，并把未消费的插话转入排队（开流前调用）。
     *
     * <p>「新任务开流」必须先切断上一任务的邮箱，否则上一轮未消费的插话会在新任务第二轮被注入，
     * 破坏任务级隔离。但残留不能静默丢弃（包括 HITL 挂起期间提交的插话）——因此本方法把
     * 它们迁到会话队列，返回迁移结果供宿主就地广播。</p>
     *
     * <p>本方法把原本散在宿主里的内部约定收进 harness：此前宿主需要自己 synchronized 清两个
     * attrs key，再调一次静态迁移方法，两处都要对应同一个内部常量。</p>
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
            session.attrs().remove(SteerInterceptor.ATTR_ACTIVE_RUN_ID);
            Object box = session.attrs().remove(SteerInterceptor.ATTR_STEER_BOX);
            staleBox = box instanceof java.util.Queue ? (java.util.Queue<SteerMessage>) box : null;
        }

        if (staleBox == null || staleBox.isEmpty()) {
            return SteerHandover.EMPTY;
        }

        java.util.List<SteerMessage> stale = new java.util.ArrayList<>(staleBox);
        java.util.List<SteerMessage> failed = SteerInterceptor.enqueueIntoSessionQueue(session, stale);
        java.util.List<SteerMessage> queued = new java.util.ArrayList<>(stale);
        queued.removeAll(failed);

        return new SteerHandover(queued, failed);
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

    /** 单条插话的最大字符数（宿主做入参校验用，与拦截器实际截断口径一致） */
    public static int maxTextLength() {
        return SteerInterceptor.MAX_TEXT_LENGTH;
    }

    /** steerId 的最大字符数 */
    public static int maxIdLength() {
        return SteerInterceptor.MAX_ID_LENGTH;
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
        boolean marked = SteerInterceptor.STEER_SOURCE.equals(message.getMetadataAs("source"));
        boolean prefixed = content != null && content.startsWith(SteerInterceptor.STEER_PREFIX);
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

        if (content.startsWith(SteerInterceptor.STEER_PREFIX)) {
            return content.substring(SteerInterceptor.STEER_PREFIX.length());
        }

        return content;
    }
}
