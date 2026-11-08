package features.ai.harness.all;

import org.noear.solon.ai.harness.input.BusyPolicy;

import org.noear.solon.ai.harness.input.HarnessInput;

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallStartEvent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessEnginePool;
import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.goal.GoalPatch;
import org.noear.solon.ai.harness.extension.goal.GoalSnapshot;
import org.noear.solon.ai.harness.extension.goal.GoalSpec;
import org.noear.solon.ai.harness.extension.loop.LoopExtension;
import org.noear.solon.ai.harness.extension.loop.LoopTask;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.queue.QueueItem;
import org.noear.solon.ai.harness.extension.steer.SteerExtension;
import org.noear.solon.ai.harness.extension.steer.SteerMessage;
import org.noear.solon.ai.harness.extension.steer.SteerReceipt;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.ai.harness.host.HostInput;
import org.noear.solon.ai.harness.host.HostRun;
import org.noear.solon.ai.harness.host.HostSignal;
import org.noear.solon.ai.harness.host.HostSink;
import org.noear.solon.ai.harness.host.RunHandle;
import org.noear.solon.ai.harness.host.RunOutcome;
import org.noear.solon.ai.harness.host.RunPrep;
import org.noear.solon.ai.harness.host.RunSink;
import org.noear.solon.ai.harness.host.SessionAdmin;
import org.noear.solon.ai.harness.host.SubmitResult;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.RejectReason;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Post;
import org.noear.solon.annotation.Produces;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.MimeType;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * README.md 的示例代码：README 里的每段示例都摘自这里（改 README 时同步此处），
 * 随构建编译，{@code ReadmeSampleTest} 运行其中的主干流程。
 */
final class ReadmeSamples {
    private ReadmeSamples() {
    }

    /** 示例里的「登录态」：真实项目从你的认证体系取 */
    static String userOf(Context ctx) {
        return ctx.header("X-User-Id");
    }

    /** 会话主体：用租户限定用户，防止跨租户串号 */
    static String principalOf(String tenant, String userId) {
        return tenant + "/" + userId;
    }

    // ==================== §2 装配 ====================

    /** §2.2 最小装配：单租户、不写 sink */
    static HarnessHost minimalHost(HarnessEngine engine) {
        return HarnessHost.builder()
                .engines(tenant -> engine)          // 单租户：返回同一个引擎
                .build();
    }

    /** §2.3 完整装配：多租户引擎池 + 全部配置项 */
    static HarnessHost fullHost(HarnessEnginePool pool, HostSink sink) {
        return HarnessHost.builder()
                .engines(pool::get)                                     // 必填：租户 → 引擎
                .sink(sink)                                             // 全局输出通道
                .wrap((tenant, task) -> () -> {                         // 框架代跑的动作带上租户上下文
                    MDC.put("tenant", tenant);
                    try {
                        task.run();
                    } finally {
                        MDC.remove("tenant");
                    }
                })
                .input((tenant, in) -> in.attr("tenant", tenant))       // 每条输入（含续发、后台轮次）都补上
                .programRuns(sid -> sid.startsWith("web-"))             // 只接管本入口会话的 goal / loop
                .build();
    }

    // ==================== §4.1 一问一答（同步 HTTP） ====================

    @Controller
    public static class AskController {
        @Inject
        HarnessHost host;

        @Post
        @Mapping("/ask/{tenant}/{sid}")
        public Result<String> ask(String tenant, String sid, String text, Context ctx) throws InterruptedException {
            SubmitResult r = host.submit(tenant, sid, HarnessInput.of(text)
                    .origin(InputOrigin.of("http", userOf(ctx), null, null))
                    .principal(principalOf(tenant, userOf(ctx)))        // sessionId 来自客户端：必须校验主体
                    .whenBusy(BusyPolicy.REJECT));           // 请求结束就没人接了：忙时不排队

            switch (r.getKind()) {
                case STARTED:
                    return awaitAnswer(r.getRun());
                case REPLY:                                             // 斜杠命令的回执
                    return Result.succeed(r.getReplyText());
                default:                                                // REJECTED / IGNORED / ...
                    ctx.status(r.httpStatus());
                    return Result.failure(r.httpStatus(),
                            r.getRejectReason() != null ? r.getRejectReason().code() : r.getKind().name());
            }
        }

        static Result<String> awaitAnswer(RunHandle run) throws InterruptedException {
            RunOutcome o;
            try {
                o = run.await(Duration.ofMinutes(5));
            } catch (TimeoutException e) {
                run.cancel();                                           // 只停这一轮
                return Result.failure(504, "timeout");
            }

            if (o.getHitlCards().isEmpty() == false) {                  // 挂起等审批
                return Result.failure(409, "approval required: " + o.getHitlCards().get(0).getCallId());
            }
            switch (o.getEnd()) {
                case COMPLETED:
                    return o.isAbnormal()
                            ? Result.failure(500, "task not finished (e.g. max turns exceeded)")
                            : Result.succeed(o.getResultText());
                case CANCELLED:
                    return Result.failure(409, "cancelled: " + o.getCancelCause());
                default:
                    return Result.failure(500, String.valueOf(o.getError()));
            }
        }
    }

    // ==================== §4.2 SSE 流式 ====================

    @Controller
    public static class SseController {
        @Inject
        HarnessHost host;

        @Get
        @Produces(MimeType.TEXT_EVENT_STREAM_VALUE)
        @Mapping("/sse/{tenant}/{sid}")
        public Flux<Map<String, Object>> sse(String tenant, String sid, String text, Context ctx) {
            return host.stream(tenant, sid, HarnessInput.of(text)
                            .origin(InputOrigin.of("sse", userOf(ctx), null, null))
                            .principal(principalOf(tenant, userOf(ctx)))
                            .whenBusy(BusyPolicy.REJECT))
                    .handle((signal, out) -> {                          // 客户端断开 = 停掉本轮（框架代办）
                        Map<String, Object> frame = toFrame(signal);
                        if (frame != null) {
                            out.next(frame);
                        }
                    });
        }

        /** 信号 → 前端帧（协议由你定，这里是最小的一份） */
        static Map<String, Object> toFrame(HostSignal s) {
            Map<String, Object> f = new LinkedHashMap<>();
            switch (s.getType()) {
                case EVENT: {
                    AgentEvent e = s.getEvent();
                    if (e instanceof ReasonDeltaEvent) {
                        ReasonDeltaEvent d = (ReasonDeltaEvent) e;
                        f.put("type", d.isThinking() ? "thinking" : "text");
                        f.put("text", d.getText());
                    } else if (e instanceof ToolCallStartEvent) {
                        f.put("type", "tool_start");
                        f.put("name", ((ToolCallStartEvent) e).getToolName());
                    } else if (e instanceof ToolCallEndEvent) {
                        f.put("type", "tool_end");
                        f.put("error", ((ToolCallEndEvent) e).isError());
                    } else {
                        return null;                                    // 其余事件不发给前端
                    }
                    return f;
                }
                case REPLY:
                    f.put("type", "reply");
                    f.put("text", s.getText());
                    return f;
                case HITL:
                    f.put("type", "hitl");
                    f.put("callIds", callIdsOf(s.getCards()));
                    return f;
                case REJECTED:
                    f.put("type", "error");
                    f.put("code", s.getRejectReason().code());
                    return f;
                case FINISHED: {
                    RunOutcome o = s.getOutcome();
                    f.put("type", "done");
                    f.put("end", o.getEnd().name());
                    f.put("tokens", o.getUsage().getTotalTokens());
                    return f;
                }
                default:                                                // DEFERRED / STEERED / IGNORED
                    f.put("type", s.getType().name().toLowerCase());
                    return f;
            }
        }
    }

    static String callIdsOf(List<HitlCard> cards) {
        StringBuilder buf = new StringBuilder();
        for (HitlCard c : cards) {
            if (buf.length() > 0) {
                buf.append(',');
            }
            buf.append(c.getCallId());
        }
        return buf.toString();
    }

    // ==================== §4.3 IM 机器人（推送型，第二档） ====================

    /** IM 平台的发送接口（示例） */
    interface ImClient {
        void send(String chatId, String text);
    }

    /** 一个群 / 私聊 = 一个会话；只发文本；忙时先插话；审批用文字指令 */
    static final class ImBot implements HostSink {
        final HarnessHost host;
        private final ImClient im;

        ImBot(Function<String, HarnessEngine> engines, ImClient im) {
            this.im = im;
            this.host = HarnessHost.builder()
                    .engines(engines)
                    .sink(this)
                    .programRuns(sid -> sid.startsWith("im-"))          // 同一引擎上还有别的入口：只接管 IM 会话
                    .build();
        }

        private static String sessionOf(String chatId) {
            return "im-" + chatId;
        }

        private static String chatOf(String sessionId) {
            return sessionId.substring(3);
        }

        /** IM 平台的消息回调 */
        public SubmitResult onMessage(String tenant, String chatId, String userId, String msgId, String text) {
            String sid = sessionOf(chatId);
            String principal = principalOf(tenant, chatId);              // IM 以「聊天」为主体
            host.openSession(tenant, sid, principal);                    // 幂等：已有且主体相同时直接返回

            InputOrigin origin = InputOrigin.of("im", userId, chatId, msgId);
            if (text.startsWith("approve ") || text.startsWith("reject ")) {
                String[] parts = text.split(" ", 2);
                return host.decide(tenant, sid, parts[1].trim(), HITLAction.of(parts[0]), null, origin);
            }
            return host.submit(tenant, sid, HarnessInput.of(text)
                    .origin(origin)
                    .principal(principal)
                    .whenBusy(BusyPolicy.STEER));            // 边跑边补充；插不进再排队
        }

        @Override
        public void reply(HostInput input, String text) {               // 命令回执
            im.send(chatOf(input.getSessionId()), text);
        }

        @Override
        public void deferred(HostInput input, int position) {
            im.send(chatOf(input.getSessionId()), "Queued (#" + position + "), will run after the current task.");
        }

        @Override
        public void steered(HostInput input) {
            im.send(chatOf(input.getSessionId()), "Got it, added to the current task.");
        }

        @Override
        public void rejected(HostInput input, RejectReason reason, String detail) {
            im.send(chatOf(input.getSessionId()), reason == RejectReason.HITL_PENDING
                    ? "Please approve or reject the pending action first."
                    : "Not accepted: " + reason.code());
        }

        @Override
        public void hitl(HostInput input, List<HitlCard> cards) {
            for (HitlCard c : cards) {
                im.send(chatOf(input.getSessionId()), "Run " + c.getToolName() + "? Reply 'approve "
                        + c.getCallId() + "' or 'reject " + c.getCallId() + "'.");
            }
        }

        @Override
        public void hitlExpired(HostInput input) {
            im.send(chatOf(input.getSessionId()), "This approval has expired.");
        }

        @Override
        public void runFinished(HostRun run, RunOutcome o) {            // IM 只发最终文本（含 goal / loop 轮次）
            String chat = chatOf(run.getSessionId());
            String prefix = run.isProgram() ? "[" + run.getProgramChannel() + "] " : "";
            if (o.isCompleted()) {
                if (o.isAbnormal()) {
                    im.send(chat, prefix + "The task could not be finished.");
                } else if (o.getResultText() != null) {
                    im.send(chat, prefix + o.getResultText());
                }
            } else if (o.isFailed()) {
                im.send(chat, prefix + "Something went wrong.");
            } else if (o.getCancelCause() == RunOutcome.CancelCause.INTERRUPT_ALL) {
                im.send(chat, "Stopped.");                              // 只有用户停止才回执
            }
        }
    }

    // ==================== §4.4 Web 多会话推送（每轮有状态，第三档） ====================

    /** WebSocket 推送接口（示例） */
    interface Push {
        void send(String sessionId, Map<String, Object> frame);
    }

    static Map<String, Object> frame(String type, Object... kv) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            f.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return f;
    }

    static final class WebPushSink implements HostSink {
        private final Push push;

        WebPushSink(Push push) {
            this.push = push;
        }

        // ---- 会话级 ----

        @Override
        public void admitted(HostInput input) {
            if (input.isCommand() && input.hasReply() == false) {
                push.send(input.getSessionId(), frame("toast", "text", "/" + input.getCommandName() + " done"));
            }
        }

        @Override
        public void reply(HostInput input, String text) {
            push.send(input.getSessionId(), frame("reply", "text", text));
        }

        @Override
        public void deferred(HostInput input, int position) {
            push.send(input.getSessionId(), frame("queued", "text", input.getText(), "position", position));
        }

        @Override
        public void steered(HostInput input) {
            push.send(input.getSessionId(), frame("steered", "text", input.getText()));
        }

        @Override
        public void rejected(HostInput input, RejectReason reason, String detail) {
            push.send(input.getSessionId(), frame("error", "code", reason.code()));
        }

        @Override
        public void hitl(HostInput input, List<HitlCard> cards) {
            push.send(input.getSessionId(), frame("hitl", "callIds", callIdsOf(cards)));
        }

        @Override
        public void hitlExpired(HostInput input) {
            push.send(input.getSessionId(), frame("hitl_expired"));
        }

        // ---- 轮次级：每轮一个 RunSink ----

        @Override
        public RunSink open(HostRun run) {
            return new WebRun(run);                                     // 只建对象，不输出
        }

        final class WebRun implements RunSink {
            private final HostRun run;
            private final String sid;
            private boolean begun;                                      // 按轮状态：普通字段即可（回调串行）
            private int seq;

            WebRun(HostRun run) {
                this.run = run;
                this.sid = run.getSessionId();
            }

            /** 第一次真正输出时才复位前端流（本轮可能在调度窗口里就被取消、根本不开流） */
            private void begin() {
                if (begun == false) {
                    begun = true;
                    if (run.getInput().isResume() == false) {           // 审批续跑：接着原来的流，不复位
                        push.send(sid, frame("reset", "runId", run.getId(),
                                "source", run.isProgram() ? run.getProgramChannel() : "user"));
                    }
                }
            }

            @Override
            public void reply(String text) {                            // 命令触发的任务的回执
                begin();
                push.send(sid, frame("reply", "text", text));
            }

            @Override
            public void prepare(RunPrep prep) {                         // 开流前：按轮调整请求
                begin();
                prep.request().options(o -> o.maxTurns(30));
            }

            @Override
            public void event(AgentEvent e) {
                if (e instanceof ReasonDeltaEvent) {
                    push.send(sid, frame("delta", "seq", ++seq, "text", ((ReasonDeltaEvent) e).getText()));
                } else if (e instanceof ToolCallStartEvent) {
                    push.send(sid, frame("tool", "seq", ++seq, "name", ((ToolCallStartEvent) e).getToolName()));
                }
            }

            @Override
            public void finished(RunOutcome o) {                        // 恰好一次
                if (o.isFailed()) {
                    push.send(sid, frame("error", "text", String.valueOf(o.getError())));
                } else if (o.getCancelCause() == RunOutcome.CancelCause.INTERRUPT_ALL && o.isStreamOpened()) {
                    push.send(sid, frame("stopped"));
                }
                push.send(sid, frame("done", "end", o.getEnd().name(),
                        "tokens", o.getUsage().getTotalTokens(), "ms", o.getUsage().getElapsedMs()));
            }
        }
    }

    // ==================== §4.5 审批 / §4.6 停止 ====================

    @Controller
    public static class ChatController {
        @Inject
        HarnessHost host;

        /** 推送型：内容已经经 sink 发出，这里只回状态 */
        @Post
        @Mapping("/chat/{tenant}/{sid}")
        public String send(String tenant, String sid, String text, String model, Context ctx) {
            SubmitResult r = host.submit(tenant, sid, HarnessInput.of(text)
                    .origin(InputOrigin.of("web", userOf(ctx), sid, null))
                    .principal(principalOf(tenant, userOf(ctx)))
                    .whenBusy(BusyPolicy.STEER)
                    .selectedModel(model).attr("effort", ctx.param("effort")));
            ctx.status(r.httpStatus());
            return r.getKind().name();
        }

        /** 审批：协议值 approve / reject / skip，脏值抛 IllegalArgumentException */
        @Post
        @Mapping("/chat/{tenant}/{sid}/hitl")
        public String decide(String tenant, String sid, String callId, String action, String note, Context ctx) {
            SubmitResult r = host.decide(tenant, sid, callId, HITLAction.of(action), note,
                    InputOrigin.of("web", userOf(ctx), sid, null));
            ctx.status(r.httpStatus());                                 // STARTED = 已续跑；HITL = 还有未决卡或卡已失效
            return r.getKind().name();
        }

        /** 用户点「停止」 */
        @Post
        @Mapping("/chat/{tenant}/{sid}/stop")
        public Result<Boolean> stop(String tenant, String sid, Context ctx) {
            HarnessResult<Boolean> r = host.interrupt(tenant, sid);
            if (r.isOk()) {
                return Result.succeed(r.getValue());                    // false = 本来就没有在跑的轮次
            }
            ctx.status(r.httpStatus());
            return Result.failure(r.httpStatus(), r.getReasonOrCode());
        }
    }

    // ==================== §5 管理态 ====================

    /** 你的鉴权（示例）：框架不做鉴权 */
    interface Authz {
        void require(String action, String tenant, String userId, String sessionId);
    }

    @Controller
    @Mapping("/admin/{tenant}/{sid}")
    public static class AdminController {
        @Inject
        HarnessHost host;
        @Inject
        Authz authz;

        private SessionAdmin admin(String action, String tenant, String sid, Context ctx) {
            authz.require(action, tenant, userOf(ctx), sid);            // 先鉴权，再进管理态
            return host.admin(tenant);
        }

        /** HarnessResult → 应答：粗码自带状态码；NOT_OWNER 带上持有者给网关转发 */
        static <T> Result<T> reply(HarnessResult<T> r, Context ctx) {
            if (r.isOk()) {
                return Result.succeed(r.getValue());
            }
            ctx.status(r.httpStatus());
            if (r.getCode() == HarnessResult.Code.NOT_OWNER && r.getOwner() != null) {
                ctx.headerSet("X-Session-Owner", r.getOwner());
            }
            return Result.failure(r.httpStatus(), r.getReasonOrCode());
        }

        // ---- queue ----

        @Get
        @Mapping("queue")
        public Result<List<QueueItem>> queueList(String tenant, String sid, Context ctx) {
            return reply(admin("queue.read", tenant, sid, ctx)
                    .call(sid, QueueExtension.class, (queue, s) -> HarnessResult.ok(queue.list(s))), ctx);
        }

        @Post
        @Mapping("queue/add")
        public Result<Integer> queueAdd(String tenant, String sid, String text, Context ctx) {
            // 成功后框架自动续发：会话空闲就立即执行
            return reply(admin("queue.write", tenant, sid, ctx)
                    .call(sid, QueueExtension.class, (queue, s) -> queue.enqueue(s, text, InputOrigin.channel("console"))), ctx);
        }

        @Post
        @Mapping("queue/top")
        public Result<Void> queueTop(String tenant, String sid, String itemId, Context ctx) {
            return reply(admin("queue.write", tenant, sid, ctx)
                    .call(sid, QueueExtension.class, (queue, s) -> queue.promote(s, itemId)), ctx);
        }

        @Post
        @Mapping("queue/cancel")
        public Result<Void> queueCancel(String tenant, String sid, String itemId, Context ctx) {
            return reply(admin("queue.write", tenant, sid, ctx)
                    .call(sid, QueueExtension.class, (queue, s) -> queue.cancelItem(s, itemId)), ctx);
        }

        @Post
        @Mapping("queue/clear")
        public Result<Integer> queueClear(String tenant, String sid, Context ctx) {
            return reply(admin("queue.write", tenant, sid, ctx)
                    .call(sid, QueueExtension.class, (queue, s) -> queue.clearPending(s)), ctx);
        }

        // ---- steer ----

        @Get
        @Mapping("steer")
        public Result<List<SteerMessage>> steerPending(String tenant, String sid, Context ctx) {
            return reply(admin("steer.read", tenant, sid, ctx)
                    .call(sid, SteerExtension.class, (steer, s) -> HarnessResult.ok(steer.pending(s))), ctx);
        }

        @Post
        @Mapping("steer/clear")
        public Result<SteerReceipt> steerClear(String tenant, String sid, Context ctx) {
            return reply(admin("steer.write", tenant, sid, ctx)
                    .call(sid, SteerExtension.class, (steer, s) -> steer.cancelPending(s, null)), ctx);
        }

        // ---- goal ----

        @Get
        @Mapping("goal")
        public Result<GoalSnapshot> goalGet(String tenant, String sid, Context ctx) {
            return reply(admin("goal.read", tenant, sid, ctx)
                    .call(sid, GoalExtension.class, (goal, s) -> HarnessResult.ok(goal.get(sid))), ctx);
        }

        @Post
        @Mapping("goal/create")
        public Result<GoalSnapshot> goalCreate(String tenant, String sid, String objective, int maxIterations, Context ctx) {
            return reply(admin("goal.write", tenant, sid, ctx)
                    .call(sid, GoalExtension.class, (goal, s) ->
                            goal.create(sid, GoalSpec.of(objective).maxIterations(maxIterations).runNow(true))), ctx);
        }

        @Post
        @Mapping("goal/update")
        public Result<GoalSnapshot> goalUpdate(String tenant, String sid, String objective, Context ctx) {
            SessionAdmin admin = admin("goal.write", tenant, sid, ctx);
            // 停在跑轮次走骨架（只停当前轮、保留排队）；不要用 INTERRUPT_ALL，那会清空用户的排队
            return reply(admin.call(sid, GoalExtension.class, (goal, s) -> goal.update(sid,
                    GoalPatch.create().objective(objective),
                    () -> admin.interrupt(sid, InterruptMode.CANCEL_STREAM))), ctx);
        }

        @Post
        @Mapping("goal/pause")
        public Result<Void> goalPause(String tenant, String sid, Context ctx) {
            return reply(admin("goal.write", tenant, sid, ctx)
                    .call(sid, GoalExtension.class, (goal, s) -> goal.pause(sid)), ctx);
        }

        @Post
        @Mapping("goal/resume")
        public Result<Void> goalResume(String tenant, String sid, Context ctx) {
            return reply(admin("goal.write", tenant, sid, ctx)
                    .call(sid, GoalExtension.class, (goal, s) -> goal.resume(sid)), ctx);
        }

        @Post
        @Mapping("goal/remove")
        public Result<Void> goalRemove(String tenant, String sid, Context ctx) {
            return reply(admin("goal.write", tenant, sid, ctx)
                    .call(sid, GoalExtension.class, (goal, s) -> goal.remove(sid)), ctx);
        }

        // ---- loop ----

        @Get
        @Mapping("loop")
        public Result<List<LoopTask>> loopList(String tenant, String sid, Context ctx) {
            return reply(admin("loop.read", tenant, sid, ctx)
                    .call(sid, LoopExtension.class, (loop, s) -> HarnessResult.ok(loop.list(sid))), ctx);
        }

        @Post
        @Mapping("loop/pause")
        public Result<Void> loopPause(String tenant, String sid, String taskId, Context ctx) {
            return reply(admin("loop.write", tenant, sid, ctx)
                    .call(sid, LoopExtension.class, (loop, s) -> loop.pause(sid, taskId)), ctx);
        }

        @Post
        @Mapping("loop/trigger")
        public Result<Void> loopTrigger(String tenant, String sid, String taskId, Context ctx) {
            return reply(admin("loop.write", tenant, sid, ctx)
                    .call(sid, LoopExtension.class, (loop, s) -> loop.trigger(sid, taskId)), ctx);
        }

        @Post
        @Mapping("loop/remove")
        public Result<Void> loopRemove(String tenant, String sid, String taskId, Context ctx) {
            return reply(admin("loop.write", tenant, sid, ctx)
                    .call(sid, LoopExtension.class, (loop, s) -> loop.remove(sid, taskId)), ctx);
        }

        // ---- 运行控制 ----

        @Post
        @Mapping("stop")
        public Result<Boolean> stop(String tenant, String sid, String mode, Context ctx) {
            // mode：INTERRUPT_ALL（同用户停止）/ CANCEL_STREAM（只停当前轮、保留排队）
            return reply(admin("run.stop", tenant, sid, ctx).interrupt(sid, InterruptMode.valueOf(mode)), ctx);
        }
    }

    // ==================== §6 后台态 ====================

    /** 后台轮次（goal / loop）与用户轮次走同一个 sink：按需区分 */
    static final class ProgramAwareSink implements HostSink {
        final List<String> notices;

        ProgramAwareSink(List<String> notices) {
            this.notices = notices;
        }

        @Override
        public void runFinished(HostRun run, RunOutcome o) {
            if (run.isProgram() && o.isCompleted() && o.getResultText() != null) {
                // 例如：给会话主人发通知（站内信 / 邮件 / IM）
                notices.add(run.getSessionId() + " [" + run.getProgramChannel() + "] " + o.getResultText());
            }
        }
    }

    // ==================== §7 运维态 ====================

    /** 会话的存储（示例） */
    interface SessionStore {
        void delete(String tenant, String sessionId);
    }

    /** 启动 / 停止 */
    static final class HostBootstrap {
        final HarnessEnginePool pool;
        final HarnessHost host;

        HostBootstrap(Function<String, HarnessEngine> buildEngine, HostSink sink) {
            this.pool = HarnessEnginePool.builder(buildEngine)
                    .idleTtl(Duration.ofMinutes(30))                    // 空闲 30 分钟回收
                    .maxEngines(500)
                    .build();
            this.host = HarnessHost.builder()
                    .engines(pool::get)
                    .sink(sink)
                    .build();
        }

        /** 重启后：提前恢复有后台工作的租户（goal 调度、积压续发）；不调也会在首次用到时恢复 */
        int start(Collection<String> tenantsWithBackgroundWork) {
            return host.warmup(tenantsWithBackgroundWork);
        }

        void stop() {
            pool.close();
        }
    }

    @Controller
    public static class SessionController {
        @Inject
        HarnessHost host;
        @Inject
        SessionStore store;

        /** 新建对话：建会话与写入主体一次完成 */
        @Post
        @Mapping("/sessions/{tenant}/{sid}/open")
        public Result<String> open(String tenant, String sid, Context ctx) {
            host.openSession(tenant, sid, principalOf(tenant, userOf(ctx)));
            return Result.succeed(sid);
        }

        /** 删除对话：判忙 → 通知扩展（撤掉 goal / loop）→ 删存储 */
        @Post
        @Mapping("/sessions/{tenant}/{sid}/delete")
        public Result<Void> delete(String tenant, String sid, Context ctx) {
            if (host.closeSession(tenant, sid, () -> store.delete(tenant, sid)) == false) {
                ctx.status(409);
                return Result.failure(409, "session is busy");
            }
            return Result.succeed();
        }
    }
}
