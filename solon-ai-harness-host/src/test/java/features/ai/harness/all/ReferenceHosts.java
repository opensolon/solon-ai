package features.ai.harness.all;

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.ai.harness.host.HostRun;
import org.noear.solon.ai.harness.host.HostSignal;
import org.noear.solon.ai.harness.host.HostSink;
import org.noear.solon.ai.harness.host.HostInput;
import org.noear.solon.ai.harness.host.RunOutcome;
import org.noear.solon.ai.harness.host.RunPrep;
import org.noear.solon.ai.harness.host.RunSink;
import org.noear.solon.ai.harness.host.RunUsage;
import org.noear.solon.ai.harness.host.SubmitResult;
import org.noear.solon.ai.harness.host.SubmitSpec;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome.RejectReason;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 三个形态各异的参考宿主（防锁定：HarnessHost 的每个扩展点都必须被 A 或 B 用到，不能只为 C 这种
 * 「多会话推送 + 每轮状态」的形态存在，见 {@code HostGenericityGuardTest}）。
 *
 * <p>三者都只用公开 API，写法就是给业务程序员看的样例：</p>
 * <ul>
 *   <li>A {@link SseChatSample}：无状态 HTTP，一个请求一轮，不写 sink（第一档）；</li>
 *   <li>B {@link ImBotSample}：只发文本的 IM 机器人，有排队、插话、审批、提醒（第二档）；</li>
 *   <li>C {@link PushWebSample}：多会话 WebSocket 推送，每轮一个有状态的 RunSink（第三档）。</li>
 * </ul>
 */
final class ReferenceHosts {
    private ReferenceHosts() {
    }

    /** 模拟的线程上下文（MDC / 日志作用域 / 租户上下文） */
    static final ThreadLocal<String> TENANT_CTX = new ThreadLocal<>();

    static Runnable withTenant(String tenant, Runnable r) {
        return () -> {
            TENANT_CTX.set(tenant);
            try {
                r.run();
            } finally {
                TENANT_CTX.remove();
            }
        };
    }

    // ==================== A：无状态 HTTP / SSE ====================

    /** A：一个请求一轮，控制器直接返回信号流；按轮计费；忙时直接拒绝（请求结束了，排队的那一轮没人接） */
    static final class SseChatSample {
        final HarnessHost host;
        final List<RunUsage> billing = new CopyOnWriteArrayList<>();
        final List<String> preparedTenants = new CopyOnWriteArrayList<>();

        SseChatSample(Function<String, HarnessEngine> engines) {
            this.host = HarnessHost.builder()
                    .engines(engines)
                    .wrap(ReferenceHosts::withTenant)                       // 日志里带上租户
                    .input((tenant, in) -> in.attr("tenant", tenant))       // 每条输入带上租户属性
                    .build();
        }

        /** {@code @Get @Produces(text/event-stream) /chat/{tenant}/{sid}?q=&maxTurns=} */
        Flux<HostSignal> chat(String tenant, String sid, String userId, String q, int maxTurns) {
            return host.stream(tenant, sid, SubmitSpec.of(q)
                    .origin(InputOrigin.of("http", userId, null, null))
                    .principal(tenant + "/" + userId)
                    .rejectWhenBusy(true)
                    .customize(in -> in.attr("client", "sse"))
                    .observe(new RunSink() {
                        @Override
                        public void prepare(RunPrep prep) {                 // 按请求参数调整本轮
                            preparedTenants.add(String.valueOf(TENANT_CTX.get()));
                            prep.request().options(o -> o.maxTurns(maxTurns));
                        }

                        @Override
                        public void finished(RunOutcome outcome) {          // 按轮计费
                            billing.add(outcome.getUsage());
                        }
                    }));
        }
    }

    // ==================== B：文本 IM 机器人 ====================

    /** B：只发文本；忙时先插话再排队；审批卡转成文字按钮；多节点转发；loop 提醒只接管 IM 会话 */
    static final class ImBotSample implements HostSink {
        final HarnessHost host;
        /** 发往 IM 的消息（chatId → 文本） */
        final List<String> outbox = new CopyOnWriteArrayList<>();
        final Map<String, String> replyRoute = new ConcurrentHashMap<>();
        final List<String> typing = new CopyOnWriteArrayList<>();

        ImBotSample(Function<String, HarnessEngine> engines) {
            this.host = HarnessHost.builder()
                    .engines(engines)
                    .sink(this)
                    .wrap(ReferenceHosts::withTenant)
                    .programRuns(sid -> sid.startsWith("im-"))             // 同一引擎上还有别的宿主：只接管 IM 会话
                    .build();
        }

        /** IM 回调入口 */
        SubmitResult onMessage(String tenant, String chatId, String userId, String text) {
            return host.submit(tenant, "im-" + chatId, SubmitSpec.of(text)
                    .origin(InputOrigin.of("im", userId, chatId, null))
                    .principal(tenant + "/" + userId)
                    .steerWhenBusy(true)
                    .customize(in -> in.attr("im.chat", chatId)));
        }

        private void send(InputOrigin origin, String text) {
            outbox.add(origin.getReplyTarget() + ": " + text);
        }

        @Override
        public void admitted(HostInput input) {
            if (input.isBesideRunning() == false) {
                // 提交时写入的宿主属性随回合带回（队列续发时为排队时保存的）
                String chat = input.getAttr("im.chat");
                replyRoute.put(input.getSessionId(), chat != null ? chat : String.valueOf(input.getOrigin().getReplyTarget()));
            }
        }

        @Override
        public void reply(HostInput input, String text) {
            send(input.getOrigin(), text);
        }

        @Override
        public void deferred(HostInput input, int position) {
            send(input.getOrigin(), "queued #" + position);
        }

        @Override
        public void steered(HostInput input) {
            send(input.getOrigin(), "noted, applying to the current task");
        }

        @Override
        public void rejected(HostInput input, RejectReason reason, String detail) {
            send(input.getOrigin(), "rejected: " + reason.code());
        }

        @Override
        public void notOwner(HostInput input, String owner) {
            send(input.getOrigin(), "forwarded to " + owner);
        }

        @Override
        public void hitl(HostInput input, List<HitlCard> cards) {
            for (HitlCard card : cards) {
                send(input.getOrigin(), "approve? " + card.getCallId());
            }
        }

        @Override
        public void hitlExpired(HostInput input) {
            send(input.getOrigin(), "this approval has expired");
        }

        @Override
        public RunSink open(HostRun run) {
            typing.add(run.getSessionId());                                 // 打开「正在输入」提示
            return HostSink.super.open(run);
        }

        @Override
        public void runFinished(HostRun run, RunOutcome outcome) {          // IM 只发最终文本
            if (outcome.isCompleted() && outcome.getResultText() != null) {
                send(run.getInput().getOrigin(), outcome.getResultText());
            } else if (outcome.isFailed()) {
                send(run.getInput().getOrigin(), "failed");
            }
        }
    }

    // ==================== C：多会话 WebSocket 推送 ====================

    /** C：每轮一个有状态的 RunSink（分段渲染 + 按轮序号），并接管全部后台轮次 */
    static final class PushWebSample implements HostSink {
        final HarnessHost host;
        /** 推给前端的帧（sessionId → 帧序列） */
        final Map<String, List<String>> frames = new ConcurrentHashMap<>();

        PushWebSample(Function<String, HarnessEngine> engines) {
            this.host = HarnessHost.builder()
                    .engines(engines)
                    .sink(this)
                    .wrap(ReferenceHosts::withTenant)
                    .input((tenant, in) -> in.attr("ui", "web"))
                    .programRuns(true)
                    .build();
        }

        SubmitResult send(String tenant, String sid, String userId, String text) {
            return host.submit(tenant, sid, SubmitSpec.of(text)
                    .origin(InputOrigin.of("web", userId, sid, null))
                    .principal(tenant + "/" + userId)
                    .steerWhenBusy(true));
        }

        private void push(String sid, String frame) {
            frames.computeIfAbsent(sid, k -> new CopyOnWriteArrayList<>()).add(frame);
        }

        @Override
        public void admitted(HostInput input) {
            if (input.isCommand() && input.hasReply() == false) {
                push(input.getSessionId(), "toast:done");                     // 命令无输出的兜底提示
            }
        }

        @Override
        public void reply(HostInput input, String text) {
            push(input.getSessionId(), "reply:" + text);
        }

        @Override
        public void deferred(HostInput input, int position) {
            push(input.getSessionId(), "queued:" + input.getText());
        }

        @Override
        public void steered(HostInput input) {
            push(input.getSessionId(), "steered:" + input.getText());
        }

        @Override
        public void rejected(HostInput input, RejectReason reason, String detail) {
            push(input.getSessionId(), "error:" + reason.code());
        }

        @Override
        public void notOwner(HostInput input, String owner) {
            push(input.getSessionId(), "redirect:" + owner);
        }

        @Override
        public void hitl(HostInput input, List<HitlCard> cards) {
            push(input.getSessionId(), "hitl:" + cards.size());
        }

        @Override
        public void hitlExpired(HostInput input) {
            push(input.getSessionId(), "hitl-expired");
        }

        @Override
        public RunSink open(HostRun run) {
            final String sid = run.getSessionId();
            push(sid, "reset");                                              // 流门复位：新一轮开始
            return new RunSink() {
                private int seq;                                             // 按轮状态

                @Override
                public void prepare(RunPrep prep) {
                    prep.request().options(o -> o.maxTurns(30));
                }

                @Override
                public void reply(String text) {
                    push(sid, "reply:" + text);
                }

                @Override
                public void event(AgentEvent event) {
                    push(sid, "event#" + (++seq));
                }

                @Override
                public void finished(RunOutcome outcome) {
                    push(sid, "done:" + outcome.getEnd());
                }
            };
        }
    }
}
