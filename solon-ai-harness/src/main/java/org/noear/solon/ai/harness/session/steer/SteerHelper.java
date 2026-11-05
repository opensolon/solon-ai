/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.steer;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.session.queue.QueueHelper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/**
 * 插话（Steering）静态内核：邮箱契约、投递校验与回滚、残留转排队的唯一实现。
 *
 * <p>与 {@link QueueHelper} 同构的内核层：宿主（CLI/Web/IM 入口）应优先使用
 * {@link SteerExtension} 门面（经 {@code engine.getExtension(SteerExtension.class)} 获取），
 * 本类的静态方法属于内核实现，仅供门面、{@link SteerInterceptor} 与测试使用，
 * 新宿主代码不应直接依赖。</p>
 *
 * <p>参考 Codex 的 steering 设计：用户在任务运行中插入的纠偏/补充消息存放在
 * {@link AgentSession#attrs()} 的邮箱队列（transient，不落 snapshot.json，任务级易失），
 * 由拦截器在 {@code onReasonStart}（消息组装前的采样边界）排空并注入工作记忆。
 * 不打断进行中的模型流与工具调用；注入的消息只进当前任务的工作记忆，任务结束即失效。</p>
 *
 * <p><b>零持久化</b>：插话完全不写入 *.messages.ndjson，也不经 {@code session.addMessage}。
 * 因此它不占用 sessionWindowSize 名额、不会在后续任务中被当作初心（META_FIRST）重新入场，
 * 刷新页面后插话气泡即消失（刻意取舍）。展示层由前端在收到 steer_applied 时就地渲染进
 * 当前 AI 流式气泡内部，属纯客户端呈现。与上游 Codex 的差异：Codex 双写
 * （response_item + event_msg）且进模型上下文、重启后仍保留，我们不做。</p>
 *
 * <p>与宿主之间的运行时契约是 {@link AgentSession#attrs()} 中的 key
 * （一字不改，任何一侧不得私自重命名）：{@code harness.steerBox}（插话邮箱）、
 * {@code harness.activeRunId}（当前运行任务 runId，供 steer API 比对，防跨任务错投）。</p>
 *
 * <p>这两个 key 原先叫 {@code web.steerBox} / {@code web.activeRunId}，属历史包袱：本包不依赖任何
 * Web 宿主，该前缀违反 {@code HarnessAttrs} 的命名空间规约（{@code web.*} 归宿主私有）。
 * 因 attrs 是 transient、不落 snapshot.json，同一 JVM 内读写同源，不存在跳版兼容问题，故直接更名。</p>
 *
 * <p><b>邮箱契约</b>：容量上限由 {@link SteerLimits} 给出（缺省 {@link #MAX_BOX_SIZE}；频繁 steer 加速上下文膨胀，
 * 高频场景应改用排队）；容量校验、ID 去重与入队在同一邮箱的短临界区内完成；
 * 投递后复查任务与邮箱身份，异常时回滚本次投递（但不清空仍有内容的邮箱）；
 * 残留兜底绝不静默丢弃（Codex issue #15842 教训）——转入失败时回填易失邮箱待下次开流重试。</p>
 *
 * @author noear
 * @since 2026
 */
public final class SteerHelper {
    private static final Logger LOG = LoggerFactory.getLogger(SteerHelper.class);

    /** 会话级插话邮箱 key（attrs 为 transient Map，不落 snapshot.json） */
    public static final String ATTR_STEER_BOX = "harness.steerBox";
    /** 当前运行任务 runId（供 steer API 比对，防跨任务错投） */
    public static final String ATTR_ACTIVE_RUN_ID = "harness.activeRunId";
    /** 缺省邮箱容量上限（Codex 建议：频繁 steer 加速上下文膨胀，高频场景应改用排队）。可经 {@link SteerLimits} 覆盖 */
    public static final int MAX_BOX_SIZE = 5;
    /** 缺省单条插话长度上限。可经 {@link SteerLimits} 覆盖 */
    public static final int MAX_TEXT_LENGTH = 4096;
    /** 缺省插话 ID 长度上限。可经 {@link SteerLimits} 覆盖 */
    public static final int MAX_ID_LENGTH = 128;

    /** 注入到工作记忆的消息前缀，向模型标识这是运行中的用户补充。
     * 同时是 LastTraceService 回放时识别插话的兜底判据（metadata 可能在快照序列化中丢失） */
    public static final String STEER_PREFIX = "[用户实时补充] ";

    /** 插话消息的 metadata 标记值（{@code source=steer}） */
    public static final String STEER_SOURCE = "steer";

    /** 插话入队结果。 */
    enum OfferResult {
        /** 已成功加入当前任务的插话邮箱。 */
        OFFERED,
        /** 邮箱已达到容量上限，未加入。 */
        BOX_FULL,
        /** 当前邮箱中已存在相同 steerId，未重复加入。 */
        DUPLICATE_ID
    }

    private SteerHelper() { }

    /**
     * 获取（不创建）会话插话邮箱
     */
    @SuppressWarnings("unchecked")
    public static Queue<SteerMessage> steerBox(AgentSession session) {
        return (Queue<SteerMessage>) session.attrs().get(ATTR_STEER_BOX);
    }

    /**
     * 当前仍留在邮箱中的插话快照（按投递顺序，不创建邮箱）
     * <p>
     * 供宿主渲染「待生效插话」列表与定位可取消项：{@link #cancel(Queue, String)} 需要 steerId，
     * 而 steerId 只出现在投递返回值里——不提供本方法时，宿主重启或刷新后就再也无法取消
     * 一条尚未生效的插话。
     * <p>
     * 返回副本：遍历期间的消费（推理线程 poll）不影响调用方，也不长期持有邮箱。
     */
    public static List<SteerMessage> pending(AgentSession session) {
        Queue<SteerMessage> box = steerBox(session);
        if (box == null || box.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(box);
    }

    /** 容量校验、ID 去重与入队必须在同一邮箱的短临界区内完成（缺省容量上限）。 */
    static OfferResult offer(Queue<SteerMessage> box, SteerMessage steer) {
        return offer(box, steer, SteerLimits.DEFAULT);
    }

    /** 容量校验、ID 去重与入队必须在同一邮箱的短临界区内完成。 */
    static OfferResult offer(Queue<SteerMessage> box, SteerMessage steer, SteerLimits limits) {
        synchronized (box) {
            // 先判重再判容量：满邮箱中的同 ID 重试仍应返回准确的重复结果，
            // 不能误报 BOX_FULL 后被前端降级为普通任务而造成重复执行。
            for (SteerMessage existing : box) {
                if (steer.getId().equals(existing.getId())) {
                    return OfferResult.DUPLICATE_ID;
                }
            }
            if (box.size() >= limits.getMaxBoxSize()) {
                return OfferResult.BOX_FULL;
            }
            return box.offer(steer) ? OfferResult.OFFERED : OfferResult.BOX_FULL;
        }
    }

    /**
     * 插话入队的唯一实现，供 Web（/web/chat/steer）与 IM（SteerCommand / 零前缀默认）复用。
     *
     * <p>封装「activeRunId 绑定校验 + computeIfAbsent 邮箱 + offer + offer 后复查回滚」的完整临界逻辑，
     * 杜绝各调用方二次实现导致的竞态偏差。</p>
     *
     * @param session     目标会话
     * @param runId       调用方所见的当前运行 ID。非空时必须与 activeRunId 相等（Web 场景防跨任务错投）；
     *                    为 null 时表示「绑定当前活跃任务」（IM 场景，直接信任后端权威 activeRunId）。
     * @param steerId     插话 ID，可为空（由后端生成）
     * @param text        插话文本
     * @param origin      来源身份（可空，空同 {@link InputOrigin#NONE}）
     * @param busyRecheck offer 后复查会话是否仍繁忙的回调，可为 null（为 null 时仅依据 activeRunId 判定，
     *                    适用于拿不到繁忙判定器的 IM 命令场景）
     * @return 入队结果（status 与 /web/chat/steer 的应答码同名）
     */
    public static SteerResult steer(AgentSession session, String runId, String steerId, String text,
                                    InputOrigin origin, BooleanSupplier busyRecheck) {
        return steer(session, runId, steerId, text, origin, busyRecheck, SteerLimits.DEFAULT, false);
    }

    /**
     * 插话入队的唯一实现（含容量约束与严格轮次绑定开关）。
     *
     * @param requireRunId 为 true 时 runId 必填：空即 {@link SteerStatus#TURN_CHANGED}。
     *                     Web 端必须绑定明确 runId——任务首个事件到达前尚不能确认归属，
     *                     放行会让请求落入任务结束/切换的窄窗成为无人消费的孤儿插话。
     *                     该语义原由宿主机拼（ChatWebController），收归内核后宿主只剩协议映射。
     */
    public static SteerResult steer(AgentSession session, String runId, String steerId, String text,
                                    InputOrigin origin, BooleanSupplier busyRecheck,
                                    SteerLimits limits, boolean requireRunId) {
        if (session == null) {
            return new SteerResult(SteerStatus.NOT_RUNNING, null, 0);
        }
        if (text == null || text.trim().isEmpty()) {
            return new SteerResult(SteerStatus.EMPTY_TEXT, null, 0);
        }

        text = text.trim();
        if (text.length() > limits.getMaxTextLength()) {
            return new SteerResult(SteerStatus.TEXT_TOO_LONG, null, 0);
        }

        if (requireRunId && (runId == null || runId.trim().isEmpty())) {
            // 严格轮次绑定：runId 是必填参数，先于 id 生成拒绝，避免调用方拿到一个无意义的 steerId
            return new SteerResult(SteerStatus.TURN_CHANGED, null, 0);
        }

        if (steerId == null || steerId.trim().isEmpty()) {
            steerId = "s_" + Utils.uuid();
        } else {
            steerId = steerId.trim();
            if (steerId.length() > limits.getMaxIdLength()) {
                return new SteerResult(SteerStatus.INVALID_STEER_ID, null, 0);
            }
        }

        Queue<SteerMessage> box;
        SteerMessage steer = new SteerMessage(steerId, text, origin);
        OfferResult offerResult;
        String boundRunId;
        synchronized (session.attrs()) {
            String activeRunId = (String) session.attrs().get(ATTR_ACTIVE_RUN_ID);
            if (activeRunId == null) {
                // 任务尚未进入推理边界（activeRunId 未建立）或已结束：Web 端有明确 runId 时按 TURN_CHANGED
                // 由前端决策后续动作；IM 端（runId==null）按 NOT_RUNNING 由调用方如实告知用户，不代为降级。
                return new SteerResult(runId != null ? SteerStatus.TURN_CHANGED : SteerStatus.NOT_RUNNING, steerId, 0);
            }
            if (runId != null && !runId.equals(activeRunId)) {
                return new SteerResult(SteerStatus.TURN_CHANGED, steerId, 0);
            }
            boundRunId = activeRunId;

            @SuppressWarnings("unchecked")
            Queue<SteerMessage> currentBox = (Queue<SteerMessage>) session.attrs()
                    .computeIfAbsent(ATTR_STEER_BOX, k -> new ConcurrentLinkedQueue<SteerMessage>());
            box = currentBox;
            offerResult = offer(box, steer, limits);
        }
        if (offerResult == OfferResult.BOX_FULL) {
            return new SteerResult(SteerStatus.BOX_FULL, steerId, box.size());
        }
        if (offerResult == OfferResult.DUPLICATE_ID) {
            return new SteerResult(SteerStatus.DUPLICATE_STEER_ID, steerId, box.size());
        }

        // offer 后复查任务与邮箱身份。只有本条仍在邮箱中且成功回滚，才能让调用方降级排队；
        // 若已被推理线程取走，再返回 NOT_RUNNING/TURN_CHANGED 会把同一文本排队执行第二次。
        boolean busyAfterOffer = (busyRecheck == null) || busyRecheck.getAsBoolean();
        boolean sameBox;
        boolean sameRun;
        boolean rolledBack = false;
        synchronized (session.attrs()) {
            sameBox = session.attrs().get(ATTR_STEER_BOX) == box;
            sameRun = boundRunId.equals(session.attrs().get(ATTR_ACTIVE_RUN_ID));
            if (!busyAfterOffer || !sameBox || !sameRun) {
                rolledBack = box.remove(steer);
                // 只清理本次创建后仍为空的邮箱；邮箱里若还有别的插话，必须留给任务收尾统一
                // applied/dropped，不能因当前请求回滚而把其它用户消息静默摘掉。
                if (box.isEmpty()) {
                    session.attrs().remove(ATTR_STEER_BOX, box);
                }
            }
        }
        if (rolledBack) {
            return new SteerResult(sameRun ? SteerStatus.NOT_RUNNING : SteerStatus.TURN_CHANGED, steerId, 0);
        }

        return new SteerResult(SteerStatus.STEERED, steerId, box.size());
    }

    /**
     * 仅取消仍留在邮箱中的插话。若消费线程已经 poll 出该项，则返回 false，调用方不得宣称取消成功。
     */
    public static boolean cancel(Queue<SteerMessage> box, String steerId) {
        if (box == null || steerId == null) {
            return false;
        }
        for (SteerMessage item : box) {
            if (steerId.equals(item.getId())) {
                return box.remove(item);
            }
        }
        return false;
    }

    /**
     * 排空邮箱（按投递顺序）。调用方须保证此刻没有并发投递。
     */
    static List<SteerMessage> drain(Queue<SteerMessage> box) {
        List<SteerMessage> messages = new ArrayList<>();
        for (SteerMessage message; (message = box.poll()) != null; ) {
            messages.add(message);
        }
        return messages;
    }

    /**
     * 把未消费插话转入 session 队列（唯一实现，任务收尾与宿主开流清理共用）。
     *
     * <p>只依赖 session 与 {@link QueueHelper}，不看流式 sink、不看宿主类型——
     * 因此「绝不静默丢弃」在任何宿主下都成立（CLI / Desktop 没有订阅者也能排队）。</p>
     *
     * <p>channel 为空的 origin 原样入队，harness 不做归一：空 channel 在队列与视图侧的
     * 呈现语义即「本地 / Web」（见宿主 toSourceLabel），而 harness 不该知道 "WEB" 这种方言值。</p>
     *
     * @return 转入失败的插话（已回填到易失邮箱，待下次开流重试；永不为 null）
     */
    public static List<SteerMessage> enqueueIntoSessionQueue(AgentSession session, List<SteerMessage> items) {
        List<SteerMessage> failed = new ArrayList<>();
        if (session == null || items == null || items.isEmpty()) {
            return failed;
        }

        for (SteerMessage message : items) {
            if (QueueHelper.enqueue(session, message.getText(), message.getOrigin()) < 0) {
                failed.add(message);
            }
        }

        if (failed.isEmpty() == false) {
            // 回填易失邮箱：下一次开流仍可重试，绝不静默丢弃（Codex issue #15842 教训）
            synchronized (session.attrs()) {
                @SuppressWarnings("unchecked")
                Queue<SteerMessage> box = (Queue<SteerMessage>) session.attrs().get(ATTR_STEER_BOX);
                if (box == null) {
                    box = new ConcurrentLinkedQueue<>();
                    session.attrs().put(ATTR_STEER_BOX, box);
                }
                box.addAll(failed);
            }
            LOG.warn("[SteerHelper] {} unconsumed steers could not be queued for session {}",
                    failed.size(), session.getSessionId());
        }

        return failed;
    }
}
