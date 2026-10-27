package org.noear.solon.ai.harness.session.steer;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/**
 * 运行中插话拦截器（Steering）
 *
 * <p>参考 Codex 的 steering 设计：用户在任务运行中插入的纠偏/补充消息存放在
 * {@link AgentSession#attrs()} 的邮箱队列（transient，不落 snapshot.json，任务级易失），
 * 由本拦截器在 {@code onReasonStart}（消息组装前的采样边界）排空并注入工作记忆。
 * 不打断进行中的模型流与工具调用；注入的消息只进当前任务的工作记忆，
 * 任务结束即失效。</p>
 *
 * <p><b>零持久化</b>：插话完全不写入 *.messages.ndjson，也不经 {@code session.addMessage}。
 * 因此它不占用 sessionWindowSize 名额、不会在后续任务中被当作初心（META_FIRST）重新入场，
 * 刷新页面后插话气泡即消失（刻意取舍）。展示层由前端在收到 steer_applied 时就地渲染进
 * 当前 AI 流式气泡内部，属纯客户端呈现。与上游 Codex 的差异：Codex 双写
 * （response_item + event_msg）且进模型上下文、重启后仍保留，我们不做。</p>
 *
 * <p>与宿主（CLI/Web/IM 入口）之间的运行时契约是 {@link AgentSession#attrs()} 中的
 * 短横线 key（一字不改，任何一侧不得私自重命名）：{@code web.steerBox}（插话邮箱）、
 * {@code web.activeRunId}（当前运行任务 runId，供 steer API 比对，防跨任务错投）。</p>
 *
 * <p>守卫体系（对齐 Codex 已踩过的坑）：</p>
 * <ul>
 *   <li>守卫 1：首轮 reason 不注入，确保本轮原始 prompt 先被采样，不篡改初始意图；</li>
 *   <li>守卫 3：工作记忆尾部存在未闭合 tool_calls（HITL 挂起恢复窄窗）时跳过，
 *       避免 user 消息打断 tool_use/tool_result 配对导致部分模型报错；</li>
 *   <li>压缩共存：请求级挂载（LinkedHashMap 插入序）天然排在默认的
 *       ContextCompressionInterceptor 之后，向压缩产物尾部追加，不破坏消息序列；</li>
 *   <li>无悬挂：{@code onAgentEnd} 检查残留并广播 steer_dropped，前端转为排队消息，
 *       绝不允许"已接受但永不生效"（Codex issue #15842 教训）。</li>
 * </ul>
 *
 * <p>泛型参数 {@code C} 是不透明的路由令牌（典型为宿主的工作区上下文），领域层只透传、
 * 不解释，随 {@link SteerOutputPort} 回到宿主实现。</p>
 *
 * @author noear
 * @since 2026
 */
@SuppressWarnings("deprecation")
public class SteerInterceptor<C> implements ReActInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(SteerInterceptor.class);
    /** 会话级插话邮箱 key（attrs 为 transient Map，不落 snapshot.json） */
    public static final String ATTR_STEER_BOX = "web.steerBox";
    /** 当前运行任务 runId（供 steer API 比对，防跨任务错投） */
    public static final String ATTR_ACTIVE_RUN_ID = "web.activeRunId";
    /** 邮箱容量上限（Codex 建议：频繁 steer 加速上下文膨胀，高频场景应改用排队） */
    public static final int MAX_BOX_SIZE = 5;

    /** 插话入队结果。 */
    enum OfferResult {
        /** 已成功加入当前任务的插话邮箱。 */
        OFFERED,
        /** 邮箱已达到容量上限，未加入。 */
        BOX_FULL,
        /** 当前邮箱中已存在相同 steerId，未重复加入。 */
        DUPLICATE_ID
    }
    /** 单条插话长度上限 */
    public static final int MAX_TEXT_LENGTH = 4096;
    /** 前端生成的插话 ID 长度上限 */
    public static final int MAX_ID_LENGTH = 128;

    /** 注入到工作记忆的消息前缀，向模型标识这是运行中的用户补充。
     * 同时是 LastTraceService 回放时识别插话的兜底判据（metadata 可能在快照序列化中丢失） */
    public static final String STEER_PREFIX = "[用户实时补充] ";

    /** 插话消息的 metadata 标记值（{@code source=steer}） */
    public static final String STEER_SOURCE = "steer";

    private final SteerOutputPort<C> outputPort;
    private final C wsContext;

    public SteerInterceptor(SteerOutputPort<C> outputPort, C wsContext) {
        this.outputPort = outputPort;
        this.wsContext = wsContext;
    }

    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPromptBuf) {
        AgentSession session = trace.getSession();
        if (session == null) {
            return;
        }

        // 记录本次任务的 runId（getRunId 惰性且幂等，与事件流的 runId 同源）。
        // 与 onAgentEnd 使用同一短临界区，避免旧任务结束时摘走新任务刚建立的邮箱。
        Queue<SteerMessage> box;
        synchronized (session.attrs()) {
            session.attrs().put(ATTR_ACTIVE_RUN_ID, trace.getRunId());
            box = steerBox(session);
        }
        if (box == null || box.isEmpty()) {
            return;
        }

        // 守卫 1：首轮不注入——原始 prompt 必须先被采样
        if (trace.getTurnCount() <= 1) {
            return;
        }

        // 守卫 3：尾部 tool_calls 未闭合不注入（等下一轮，ActionTask 补齐结果后自然恢复）
        if (hasOpenToolCalls(trace)) {
            return;
        }

        List<SteerMessage> messages = drain(box);
        if (messages.isEmpty()) {
            return;
        }

        for (SteerMessage steer : messages) {
            ChatMessage message = ChatMessage.ofUser(STEER_PREFIX + steer.getText());
            message.addMetadata("source", STEER_SOURCE);
            trace.getWorkingMemory().addMessage(message);
        }

        // 不追加 systemPrompt 说明：注入消息自带 STEER_PREFIX 已足够表意，避免重复提示

        // 必须广播 applied：它是「注入已生效」的唯一信号。前端据此清除待生效态并落气泡；
        // 客户端不会用超时猜测终态，避免把已执行过的插话再次入队。
        emitSteerApplied(session, trace.getRunId(), messages);
    }

    @Override
    public void onAgentEnd(ReActTrace trace) {
        AgentSession session = trace.getSession();
        if (session == null) {
            return;
        }

        // 仅结束属于本 trace 的邮箱。旧 trace 的迟到回调不得摘走新任务的 runId 或邮箱。
        Queue<SteerMessage> box;
        synchronized (session.attrs()) {
            if (!trace.getRunId().equals(session.attrs().get(ATTR_ACTIVE_RUN_ID))) {
                return;
            }
            @SuppressWarnings("unchecked")
            Queue<SteerMessage> currentBox = (Queue<SteerMessage>) session.attrs().remove(ATTR_STEER_BOX);
            box = currentBox;
            session.attrs().remove(ATTR_ACTIVE_RUN_ID, trace.getRunId());
        }

        if (box != null && !box.isEmpty()) {
            // 残留兜底：任务已结束（单轮直接回答、守卫持续跳过后 END 等），
            // 未消费的插话绝不能静默丢弃——交由入口端口转排队（Web 广播 dropped，前端转为排队消息发送）
            List<SteerMessage> dropped = drain(box);
            if (!dropped.isEmpty()) {
                if (outputPort != null) {
                    ConcurrentLinkedQueue<SteerMessage> droppedBox = new ConcurrentLinkedQueue<>(dropped);
                    outputPort.handleDroppedSteers(wsContext, session, droppedBox, trace.getRunId());
                }
                // 端口为空（纯 headless 测试场景）时无客户端可通知，仅记日志后丢弃
                else {
                    LOG.warn("[SteerInterceptor] {} unconsumed steers dropped without output port for session {}",
                            dropped.size(), session.getSessionId());
                }
            }
        }
    }

    /**
     * 获取（不创建）会话插话邮箱
     */
    @SuppressWarnings("unchecked")
    public static Queue<SteerMessage> steerBox(AgentSession session) {
        return (Queue<SteerMessage>) session.attrs().get(ATTR_STEER_BOX);
    }

    /** 容量校验、ID 去重与入队必须在同一邮箱的短临界区内完成。 */
    static OfferResult offer(Queue<SteerMessage> box, SteerMessage steer) {
        synchronized (box) {
            // 先判重再判容量：满邮箱中的同 ID 重试仍应返回准确的重复结果，
            // 不能误报 BOX_FULL 后被前端降级为普通任务而造成重复执行。
            for (SteerMessage existing : box) {
                if (steer.getId().equals(existing.getId())) {
                    return OfferResult.DUPLICATE_ID;
                }
            }
            if (box.size() >= MAX_BOX_SIZE) {
                return OfferResult.BOX_FULL;
            }
            return box.offer(steer) ? OfferResult.OFFERED : OfferResult.BOX_FULL;
        }
    }

    /**
     * 插话入队的统一实现，供 Web（/web/chat/steer）与 IM（SteerCommand / 零前缀默认）复用。
     *
     * <p>封装「activeRunId 绑定校验 + computeIfAbsent 邮箱 + offer + offer 后复查回滚」的完整临界逻辑，
     * 杜绝各调用方二次实现导致的竞态偏差。</p>
     *
     * @param session     目标会话
     * @param runId       调用方所见的当前运行 ID。非空时必须与 activeRunId 相等（Web 场景防跨任务错投）；
     *                    为 null 时表示「绑定当前活跃任务」（IM 场景，直接信任后端权威 activeRunId）。
     * @param steerId     插话 ID，可为空（由后端生成）
     * @param text        插话文本
     * @param source      来源通道标识（IM 传 Feishu/DingTalk/WeChat；Web 传 null）
     * @param busyRecheck offer 后复查会话是否仍繁忙的回调，可为 null（为 null 时仅依据 activeRunId 判定，
     *                    适用于拿不到繁忙判定器的 IM 命令场景）
     * @return 入队结果（status 与 /web/chat/steer 的应答码同名）
     */
    public static SteerResult steer(AgentSession session, String runId, String steerId, String text,
                                    String source, BooleanSupplier busyRecheck) {
        return steer(session, runId, steerId, text, source, null, null, null, busyRecheck);
    }

    public static SteerResult steer(AgentSession session, String runId, String steerId, String text,
                                    String source, String sourceUserId, String replyTarget, String messageId,
                                    BooleanSupplier busyRecheck) {
        if (session == null) {
            return new SteerResult(SteerStatus.NOT_RUNNING, null, 0);
        }
        if (text == null || text.trim().isEmpty()) {
            return new SteerResult(SteerStatus.EMPTY_TEXT, null, 0);
        }

        text = text.trim();
        if (text.length() > MAX_TEXT_LENGTH) {
            return new SteerResult(SteerStatus.TEXT_TOO_LONG, null, 0);
        }

        if (steerId == null || steerId.trim().isEmpty()) {
            steerId = "s_" + Utils.uuid();
        } else {
            steerId = steerId.trim();
            if (steerId.length() > MAX_ID_LENGTH) {
                return new SteerResult(SteerStatus.INVALID_STEER_ID, null, 0);
            }
        }

        Queue<SteerMessage> box;
        SteerMessage steer = new SteerMessage(steerId, text, source, sourceUserId, replyTarget, messageId);
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
            offerResult = offer(box, steer);
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
     * 插话入队结果。status 取值与 /web/chat/steer 应答码同名：
     * STEERED / NOT_RUNNING / TURN_CHANGED / BOX_FULL / DUPLICATE_STEER_ID /
     * EMPTY_TEXT / TEXT_TOO_LONG / INVALID_STEER_ID。
     */
    public enum SteerStatus {
        STEERED("STEERED"), NOT_RUNNING("NOT_RUNNING"), TURN_CHANGED("TURN_CHANGED"),
        BOX_FULL("BOX_FULL"), DUPLICATE_STEER_ID("DUPLICATE_STEER_ID"),
        EMPTY_TEXT("EMPTY_TEXT"), TEXT_TOO_LONG("TEXT_TOO_LONG"), INVALID_STEER_ID("INVALID_STEER_ID");

        private final String code;
        SteerStatus(String code) { this.code = code; }
        public String code() { return code; }
    }

    public static final class SteerResult {
        private final SteerStatus status;
        private final String steerId;
        private final int queued;

        public SteerResult(SteerStatus status, String steerId, int queued) {
            this.status = status;
            this.steerId = steerId;
            this.queued = queued;
        }

        public SteerStatus getStatus() { return status; }
        public String getStatusCode() { return status.code(); }
        public String getSteerId() { return steerId; }
        public int getQueued() { return queued; }
        public boolean isSteered() { return status == SteerStatus.STEERED; }
        public boolean isBoxFull() { return status == SteerStatus.BOX_FULL; }
        public boolean isNotRunning() {
            return status == SteerStatus.NOT_RUNNING || status == SteerStatus.TURN_CHANGED;
        }
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

    private static List<SteerMessage> drain(Queue<SteerMessage> box) {
        List<SteerMessage> messages = new ArrayList<>();
        for (SteerMessage message; (message = box.poll()) != null; ) {
            messages.add(message);
        }
        return messages;
    }

    private static boolean hasOpenToolCalls(ReActTrace trace) {
        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        if (messages.isEmpty()) {
            return false;
        }
        ChatMessage last = messages.get(messages.size() - 1);
        return last instanceof AssistantMessage
                && Assert.isNotEmpty(((AssistantMessage) last).getToolCalls());
    }

    /**
     * 推送插话已生效事件（steer_applied），由入口端口负责具体协议呈现。
     */
    private void emitSteerApplied(AgentSession session, String runId, List<SteerMessage> items) {
        try {
            if (outputPort != null && wsContext != null) {
                outputPort.emitSteerApplied(wsContext, session, runId, items);
            }
        } catch (Throwable e) {
            LOG.warn("[SteerInterceptor] emit steer event failed: {}", e.toString());
        }
    }
}
