package features.ai.chat;

import org.junit.jupiter.api.Assertions;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventGroup;
import org.noear.solon.ai.chat.event.ChatEventPhase;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.tool.ToolCall;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 事件流收集器（测试用）
 *
 * <p>集成用例共用的事件消费范式：单入口 {@code onEvent}，switch 在
 * {@link ChatEventGroup} 上并保留 default 分支——新增具体事件类型时不会漏。</p>
 *
 * <p>只做「收集 + 等待 + 不变量校验」，不包装 Reactor：需要验证操作符兼容性（{@code Flux.from} /
 * {@code timeout} / {@code doFinally}）的用例仍应直接写 Reactor 链，再把
 * {@link #onEvent(ChatEvent)} 挂到 {@code doOnNext} 上。</p>
 *
 * <p>{@link #assertInvariants()} 是本次事件模型改造的契约校验器：所有流式用例都应调用它，
 * 从而让「顺序、配平、标识一致」这类不变量在每个方言、每个场景上被自动复验，
 * 而不必在每个用例里重写一遍断言。</p>
 *
 * @author noear
 */
public class ChatEventCollector {
    private final List<ChatEvent> events = new CopyOnWriteArrayList<>();
    private final List<ToolCall> toolCalls = new CopyOnWriteArrayList<>();
    private final List<ToolCall> toolResults = new CopyOnWriteArrayList<>();
    private final List<AiUsage> usages = new CopyOnWriteArrayList<>();
    private final StringBuilder textBuf = new StringBuilder();
    private final StringBuilder thinkingBuf = new StringBuilder();
    private final AtomicReference<ChatResponse> finalResponse = new AtomicReference<>();
    private final AtomicReference<ChatResponse> lastStepResponse = new AtomicReference<>();
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final CountDownLatch doneLatch = new CountDownLatch(1);

    /**
     * 订阅并收集（不阻塞）
     */
    public static ChatEventCollector subscribe(Flux<ChatEvent> flux) {
        ChatEventCollector collector = new ChatEventCollector();

        flux.subscribe(collector::onEvent, collector::onError, collector::onComplete);

        return collector;
    }

    /**
     * 订阅、收集并等待结束
     */
    public static ChatEventCollector collect(Flux<ChatEvent> flux) throws InterruptedException {
        return subscribe(flux).await();
    }

    /// //////////////////////////

    /**
     * 事件单入口
     */
    public void onEvent(ChatEvent event) {
        events.add(event);

        switch (event.getGroup()) {
            case TEXT:
                if (event.is(ChatEventType.TEXT_DELTA) && event.hasText()) {
                    synchronized (textBuf) {
                        textBuf.append(event.getText());
                    }
                }
                break;
            case THINKING:
                if (event.is(ChatEventType.THINKING_DELTA) && event.hasText()) {
                    synchronized (thinkingBuf) {
                        thinkingBuf.append(event.getText());
                    }
                }
                break;
            case TOOL_CALL:
                if (event.is(ChatEventType.TOOL_CALL_END) || event.is(ChatEventType.TOOL_CALL_CHUNK)) {
                    if (event.getToolCall() != null) {
                        toolCalls.add(event.getToolCall());
                    }
                } else if (event.is(ChatEventType.TOOL_RESULT)) {
                    if (event.getToolCall() != null) {
                        toolResults.add(event.getToolCall());
                    }
                }
                break;
            case STEP:
                if (event.is(ChatEventType.STEP_END) && event.getResponse() != null) {
                    lastStepResponse.set(event.getResponse());
                }
                break;
            case LIFECYCLE:
                if (event.is(ChatEventType.RESPONSE_END)) {
                    finalResponse.set(event.getResponse());
                }
                break;
            case META:
                if (event.is(ChatEventType.ERROR) && event.getError() != null) {
                    error.compareAndSet(null, event.getError());
                } else if (event.is(ChatEventType.USAGE) && event.getUsage() != null) {
                    usages.add(event.getUsage());
                }
                break;
            default:
                break;
        }
    }

    public void onError(Throwable err) {
        err.printStackTrace();
        error.compareAndSet(null, err);
        doneLatch.countDown();
    }

    public void onComplete() {
        completed.set(true);
        doneLatch.countDown();
    }

    /**
     * 等待流结束
     */
    public ChatEventCollector await() throws InterruptedException {
        doneLatch.await();
        return this;
    }

    /**
     * 等待流结束（限时）
     */
    public ChatEventCollector await(long timeout, TimeUnit unit) throws InterruptedException {
        doneLatch.await(timeout, unit);
        return this;
    }

    /// //////////////////////////

    /**
     * 全部事件（按到达顺序）
     */
    public List<ChatEvent> events() {
        return new ArrayList<>(events);
    }

    /**
     * 事件类型序列
     */
    public List<ChatEventType> types() {
        List<ChatEventType> list = new ArrayList<>(events.size());
        for (ChatEvent e : events) {
            list.add(e.getType());
        }
        return list;
    }

    /**
     * 指定类型出现次数
     */
    public long countOf(ChatEventType type) {
        return events.stream().filter(e -> e.is(type)).count();
    }

    /**
     * 指定分组出现次数
     */
    public long countOfGroup(ChatEventGroup group) {
        return events.stream().filter(e -> e.getGroup() == group).count();
    }

    /**
     * 是否出现过指定类型
     */
    public boolean has(ChatEventType type) {
        return countOf(type) > 0;
    }

    /**
     * 指定类型首次出现的下标（不存在为 -1）
     */
    public int indexOfFirst(ChatEventType type) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).is(type)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 指定类型最后出现的下标（不存在为 -1）
     */
    public int indexOfLast(ChatEventType type) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).is(type)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 累积正文（仅 TEXT_DELTA，思考与工具内容不混入）
     */
    public String text() {
        synchronized (textBuf) {
            return textBuf.toString();
        }
    }

    /**
     * 累积思考内容
     */
    public String thinking() {
        synchronized (thinkingBuf) {
            return thinkingBuf.toString();
        }
    }

    /**
     * 完成的工具调用（TOOL_CALL_END / TOOL_CALL_CHUNK 携带）
     */
    public List<ToolCall> toolCalls() {
        return new ArrayList<>(toolCalls);
    }

    /**
     * 已执行的工具（TOOL_RESULT 携带）
     */
    public List<ToolCall> toolResults() {
        return new ArrayList<>(toolResults);
    }

    /**
     * 分步用量（USAGE 携带）
     */
    public List<AiUsage> usages() {
        return new ArrayList<>(usages);
    }

    /**
     * 出现过的响应标识（正常情况下应只有一个）
     */
    public Set<String> responseIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (ChatEvent e : events) {
            ids.add(e.getResponseId());
        }
        return ids;
    }

    /**
     * 出现过的方言原始事件名
     */
    public Set<String> rawTypes() {
        Set<String> set = new LinkedHashSet<>();
        for (ChatEvent e : events) {
            if (e.getRawType() != null) {
                set.add(e.getRawType());
            }
        }
        return set;
    }

    /**
     * 终态聚合响应（RESPONSE_END 携带）
     */
    public ChatResponse finalResponse() {
        return finalResponse.get();
    }

    /**
     * 最后一步的分步聚合响应（STEP_END 携带）
     */
    public ChatResponse lastStepResponse() {
        return lastStepResponse.get();
    }

    public Throwable error() {
        return error.get();
    }

    public boolean isCompleted() {
        return completed.get();
    }

    /// //////////////////////////

    /**
     * 校验事件流的通用不变量
     *
     * <p>容忍流被提前取消（此时允许缺少 RESPONSE_END、允许内容块未闭合），
     * 因此可用于取消用例；完整性另见 {@link #assertCompleted()}。</p>
     */
    public ChatEventCollector assertInvariants() {
        List<ChatEvent> list = events();

        Assertions.assertFalse(list.isEmpty(), "事件流不应为空");

        //1. type 与 group / phase 的绑定不漂移
        for (ChatEvent e : list) {
            Assertions.assertNotNull(e.getType(), "事件类型不应为 null");
            Assertions.assertEquals(e.getType().getGroup(), e.getGroup(), "group 应由 type 静态绑定: " + e);
            Assertions.assertEquals(e.getType().getPhase(), e.getPhase(), "phase 应由 type 静态绑定: " + e);
            Assertions.assertNotNull(e.getRaw(), "getRaw() 不应为 null（兜底应为空节点）: " + e);
            Assertions.assertNotNull(e.getAttrs(), "getAttrs() 不应为 null: " + e);
        }

        //2. responseId 全流一致且非空
        Set<String> ids = responseIds();
        Assertions.assertEquals(1, ids.size(), "responseId 应全流一致，实际：" + ids);
        Assertions.assertNotNull(ids.iterator().next(), "responseId 不应为 null");

        //3. RESPONSE_START 恰一次且为首个事件
        Assertions.assertEquals(1, countOf(ChatEventType.RESPONSE_START), "RESPONSE_START 应恰好一次");
        Assertions.assertEquals(ChatEventType.RESPONSE_START, list.get(0).getType(), "RESPONSE_START 应为首个事件");

        //4. RESPONSE_END 至多一次；若有则为末事件且携带终态聚合
        long ends = countOf(ChatEventType.RESPONSE_END);
        Assertions.assertTrue(ends <= 1, "RESPONSE_END 至多一次，实际：" + ends);

        if (ends == 1) {
            Assertions.assertEquals(ChatEventType.RESPONSE_END, list.get(list.size() - 1).getType(),
                    "RESPONSE_END 应为最后一个事件");
            Assertions.assertNotNull(finalResponse(), "RESPONSE_END 应携带终态聚合");
            Assertions.assertEquals(0, countOf(ChatEventType.ABORT), "RESPONSE_END 与 ABORT 不应共存");
        }

        //5. STEP 严格交替配平，且落在响应生命周期内
        assertStepPairing(list);

        //6. 内容块（TEXT / THINKING）边界配平，且同一时刻只打开一个
        assertContentBoundary(list);

        //6b. 工具调用按 toolCallId 配平（不重复 START / 不重复 END / END 后不再有参数增量）
        assertToolCallPairing(list);

        //7. 负载与类型匹配
        for (ChatEvent e : list) {
            switch (e.getType()) {
                case TOOL_CALL_END:
                case TOOL_CALL_CHUNK:
                    Assertions.assertNotNull(e.getToolCall(), "工具调用完成事件应携带 toolCall: " + e);
                    break;
                case TOOL_RESULT:
                    Assertions.assertNotNull(e.getToolCall(), "TOOL_RESULT 应携带 toolCall: " + e);
                    break;
                case USAGE:
                    Assertions.assertNotNull(e.getUsage(), "USAGE 应携带用量: " + e);
                    break;
                case ERROR:
                    Assertions.assertNotNull(e.getError(), "ERROR 应携带异常: " + e);
                    break;
                case STEP_END:
                    Assertions.assertNotNull(e.getResponse(), "STEP_END 应携带分步聚合: " + e);
                    break;
                default:
                    break;
            }
        }

        return this;
    }

    /**
     * 校验流已正常完成（无错误、生命周期闭合）
     */
    public ChatEventCollector assertCompleted() {
        Assertions.assertNull(error(), "不应有错误");
        Assertions.assertEquals(1, countOf(ChatEventType.RESPONSE_END), "正常完成应有一个 RESPONSE_END");
        return this;
    }

    private void assertStepPairing(List<ChatEvent> list) {
        int open = 0;
        int expectStep = 0;
        int lastStep = -1;

        for (ChatEvent e : list) {
            if (e.is(ChatEventType.STEP_START)) {
                Assertions.assertEquals(0, open, "STEP 不应嵌套: " + e);
                Assertions.assertEquals(expectStep, e.getStep(),
                        "STEP_START 的 step 应从 0 起逐次递增: " + e);
                expectStep++;
                open++;
            } else if (e.is(ChatEventType.STEP_END)) {
                Assertions.assertEquals(1, open, "STEP_END 应有配对的 STEP_START: " + e);
                open--;
            }

            //step 单调不减（RESPONSE_START/END 也应落在合法区间内）
            Assertions.assertTrue(e.getStep() >= lastStep,
                    "step 应单调不减，实际从 " + lastStep + " 回落到 " + e.getStep() + ": " + e);
            lastStep = Math.max(lastStep, e.getStep());
        }

        long starts = countOf(ChatEventType.STEP_START);
        long ends = countOf(ChatEventType.STEP_END);

        if (countOf(ChatEventType.RESPONSE_END) == 1) {
            Assertions.assertEquals(starts, ends, "STEP 应成对");
            Assertions.assertEquals(0, open, "不应有未闭合的 STEP");
            Assertions.assertTrue(starts >= 1, "至少应有一步");

            int firstStep = indexOfFirst(ChatEventType.STEP_START);
            int lastEnd = indexOfLast(ChatEventType.STEP_END);
            Assertions.assertTrue(firstStep > 0, "STEP_START 应在 RESPONSE_START 之后");
            Assertions.assertTrue(lastEnd < list.size() - 1, "STEP_END 应在 RESPONSE_END 之前");
        }
    }

    /**
     * 工具调用的边界配平（按 toolCallId）
     *
     * <p>事件模型里「开始 / 参数增量 / 完成」是三个不同语义，分片只能产生一次开始与
     * 一次完成。重复发 START 或在 END 之后再发 ARGS_DELTA，会让拼接参数的订阅方
     * 拿到翻倍的参数串——这类失真在“只断言某事件出现过”的用例里看不出来。</p>
     */
    private void assertToolCallPairing(List<ChatEvent> list) {
        Set<String> started = new LinkedHashSet<>();
        Set<String> ended = new LinkedHashSet<>();

        for (ChatEvent e : list) {
            if (e.getGroup() != ChatEventGroup.TOOL_CALL) {
                continue;
            }

            String id = e.getToolCallId();
            if (id == null) {
                //分片协议下仅首片携带 id，后续片无 id，无法归属则跳过
                continue;
            }

            switch (e.getType()) {
                case TOOL_CALL_START:
                    Assertions.assertFalse(started.contains(id),
                            "同一工具调用不应重复发 TOOL_CALL_START: " + e);
                    Assertions.assertFalse(ended.contains(id),
                            "TOOL_CALL_END 之后不应再发 START: " + e);
                    started.add(id);
                    break;
                case TOOL_CALL_ARGS_DELTA:
                    Assertions.assertFalse(ended.contains(id),
                            "TOOL_CALL_END 之后不应再有参数增量（会让参数翻倍）: " + e);
                    break;
                case TOOL_CALL_END:
                    Assertions.assertFalse(ended.contains(id),
                            "同一工具调用不应重复发 TOOL_CALL_END: " + e);
                    ended.add(id);
                    break;
                default:
                    break;
            }
        }
    }

    private void assertContentBoundary(List<ChatEvent> list) {
        ChatEventGroup open = null;

        for (ChatEvent e : list) {
            ChatEventGroup group = e.getGroup();

            if (group != ChatEventGroup.TEXT && group != ChatEventGroup.THINKING) {
                continue;
            }

            ChatEventPhase phase = e.getPhase();

            if (phase == ChatEventPhase.START) {
                Assertions.assertNull(open, "内容块不应重叠，已打开 " + open + " 又收到: " + e);
                open = group;
            } else if (phase == ChatEventPhase.DELTA) {
                Assertions.assertEquals(group, open, "增量应被同组的 START 包裹: " + e);
            } else if (phase == ChatEventPhase.END) {
                Assertions.assertEquals(group, open, "END 应有配对的 START: " + e);
                open = null;
            } else if (phase == ChatEventPhase.CHUNK) {
                Assertions.fail("CHUNK 应已被归一化器展开，不应到达订阅方: " + e);
            }
        }

        if (countOf(ChatEventType.RESPONSE_END) == 1) {
            Assertions.assertNull(open, "流结束时不应有未闭合的内容块，残留：" + open);
        }
    }
}
