package org.noear.solon.ai.harness.extension.queue;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.command.builtin.Fakes;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.input.InputOrigin;

import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;


/**
 * /queue 命令编排层测试（直连 SessionQueue，engine 传 null 同既有口径）。
 * 入队结果与容量/长度上限文案由领域类事实锁定。
 */
class QueueCommandTest {
    private final QueueCommand command = new QueueCommand();

    @Test
    void emptyArgsPrintsUsage() {
        Fakes.StubContext ctx = Fakes.StubContext.im("");
        command.execute(null, ctx.in(Fakes.StubContext.imOrigin()), ctx);
        assertTrue(ctx.output().contains("用法：/queue"));
    }

    @Test
    void enqueuesWithImMetadata() {
        Fakes.StubContext ctx = Fakes.StubContext.im("接着跑测试");
        command.execute(null, ctx.in(Fakes.StubContext.imOrigin()), ctx);
        assertEquals(1, QueueStore.pendingSize(ctx.getSession()));
        QueueItem item = QueueStore.snapshot(ctx.getSession()).get(0);
        assertEquals("接着跑测试", item.getText());
        assertEquals("Feishu", item.getOrigin().getChannel());
        assertEquals("user-1", item.getOrigin().getSenderId());
        assertEquals("chat-1", item.getOrigin().getReplyTarget());
        assertEquals("message-1", item.getOrigin().getMessageId());
        assertTrue(ctx.output().contains("已加入队列（第 1 位）"));
    }

    @Test
    void localSourceStaysNoneInsteadOfHardcodedChannel() {
        // 渠道兜底已删：命令层不再硬编码 "CLI"，本地无渠道来源（NONE）是合法值，
        // 展示层用 channelOr 自行降级。origin 携带什么就存什么。
        Fakes.StubContext ctx = Fakes.StubContext.local("接着跑测试");
        command.execute(null, ctx.in(Fakes.StubContext.localOrigin()), ctx);
        QueueItem item = QueueStore.snapshot(ctx.getSession()).get(0);
        assertNull(item.getOrigin().getChannel(), "本地入队不得被伪造渠道标识");
        assertEquals("CLI", item.getOrigin().channelOr("CLI"), "展示层降级由消费方决定");
    }

    @Test
    void tooLongTextRejected() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 4097; i++) {
            longText.append('x');
        }
        Fakes.StubContext ctx = Fakes.StubContext.im(longText.toString());
        command.execute(null, ctx.in(Fakes.StubContext.imOrigin()), ctx);
        assertEquals(0, QueueStore.pendingSize(ctx.getSession()));
        assertTrue(ctx.output().contains("排队内容过长（上限 4096 字符）"));
    }

    @Test
    void enqueueFailurePrintsLimit() {
        Fakes.StubContext ctx = Fakes.StubContext.im("接着跑测试");
        AgentSession session = ctx.getSession();
        for (int i = 0; i < QueueStore.MAX_QUEUE_SIZE; i++) {
            QueueStore.enqueue(session, "fill-" + i, null);
        }
        command.execute(null, ctx.in(Fakes.StubContext.imOrigin()), ctx);
        assertEquals(0, ctx.tasks.size());
        assertEquals(QueueStore.MAX_QUEUE_SIZE, QueueStore.pendingSize(session));
        assertTrue(ctx.output().contains("排队失败（队列已满，上限 20 条）"));
    }

    @Test
    void commandEnqueueSnapshotsTurnLikeExtensionOffer() {
        // 命令入队与扩展入队（QueueExtension#offer）同一口径：本轮属性随条目保存，续发时还原
        AgentSession session = Fakes.newSession();
        InputScope scope = InputScope.builder("alice").commands("queue").build();
        InputOrigin origin = InputOrigin.of("Feishu", "u1", "c1", "m1");
        HarnessInput turn = HarnessInput.of(session, "/queue 接着跑")
                .origin(origin).scope(scope).systemPrompt("sp").locale(Locale.ENGLISH)
                .selectedModel("m1").selectedAgent("coder").selectedSkill("s1").selectedMcp("mcp1")
                .terminal(true).attr("effort", "high");
        Fakes.StubContext out = new Fakes.StubContext(session, Arrays.asList("接着跑"));

        command.execute(null, CommandInput.of(turn, ParsedCommand.parse("/queue 接着跑"), null), out);

        QueueItem item = QueueStore.snapshot(session).get(0);
        assertEquals("接着跑", item.getText());
        assertSame(origin, item.getOrigin());
        assertEquals("m1", item.getModel());
        assertEquals("coder", item.getSelectedAgent());
        assertEquals("high", item.getAttrs().get("effort"));
        assertEquals(QueueItem.snapshotOf(turn.derive("接着跑")), item.getInputSnapshot());

        // 续发还原：显式作用域、提示词、语言、视野都回来
        HarnessInput resumed = item.toInput(session);
        assertTrue(resumed.isScopeExplicit());
        assertEquals(scope.toSpec(), resumed.getScope().toSpec());
        assertEquals("sp", resumed.getSystemPrompt());
        assertEquals(Locale.ENGLISH, resumed.getLocale());
        assertEquals(Arrays.asList("s1"), resumed.getSelectedSkills());
        assertEquals(Arrays.asList("mcp1"), resumed.getSelectedMcps());
    }

    @Test
    void nonDeferrableScopeIsRejected() {
        AgentSession session = Fakes.newSession();
        HarnessInput turn = HarnessInput.of(session, "/queue x")
                .scope(InputScope.builder("alice").attr("quota", 1).build());
        Fakes.StubContext out = new Fakes.StubContext(session, Arrays.asList("x"));

        command.execute(null, CommandInput.of(turn, ParsedCommand.parse("/queue x"), null), out);

        assertEquals(0, QueueStore.pendingSize(session), "不可保存的作用域不能被悄悄丢掉后按更宽权限续发");
        assertTrue(out.output().contains("不能排队"), out.output());
    }

    private static Fakes.StubContext args(AgentSession session, String... args) {
        return new Fakes.StubContext(session, Arrays.asList(args));
    }

    @Test
    void listCancelAndClearSubcommands() {
        AgentSession session = Fakes.StubContext.local("x").getSession();
        QueueStore.enqueue(session, "a", null);
        QueueStore.enqueue(session, "b", null);
        QueueStore.enqueue(session, "c", null);

        Fakes.StubContext list = args(session, "list");
        command.execute(null, list.in(InputOrigin.NONE), list);
        assertTrue(list.output().contains("队列（共 3 条）"), list.output());

        run(args(session, "cancel", "1"));
        assertEquals(2, QueueStore.pendingSize(session), "按序号取消");
        String idOfC = QueueStore.view(session).get(1).getId();
        run(args(session, "cancel", idOfC));
        assertEquals(1, QueueStore.pendingSize(session), "按 id 取消");

        Fakes.StubContext clear = args(session, "clear");
        command.execute(null, clear.in(InputOrigin.NONE), clear);
        assertEquals(0, QueueStore.pendingSize(session));
        assertTrue(clear.output().contains("已清空 1 条"), clear.output());
    }

    @Test
    void clearKeepsInFlightItem() {
        AgentSession session = Fakes.StubContext.local("x").getSession();
        QueueStore.enqueue(session, "running", null);
        QueueStore.enqueue(session, "pending", null);
        QueueStore.poll(session);

        run(args(session, "clear"));
        assertEquals(0, QueueStore.pendingSize(session));
        assertTrue(QueueStore.hasItems(session), "正在执行的那条不受 clear 影响");
    }

    @Test
    void naturalLanguageAndEscapedWordsAreEnqueued() {
        AgentSession session = Fakes.StubContext.local("x").getSession();
        run(args(session, "list", "all", "files"));
        run(args(session, "--", "clear"));
        assertEquals(2, QueueStore.pendingSize(session));
        assertEquals("list all files", QueueStore.snapshot(session).get(0).getText());
        assertEquals("clear", QueueStore.snapshot(session).get(1).getText());
    }

    private void run(Fakes.StubContext ctx) {
        command.execute(null, ctx.in(InputOrigin.NONE), ctx);
    }
}
