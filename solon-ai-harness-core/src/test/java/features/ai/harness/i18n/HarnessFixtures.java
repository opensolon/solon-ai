package features.ai.harness.i18n;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.BufferedCommandOutput;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.util.TraceUtil;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * i18n 用例的测试替身（反射只用于构造私有结构，不复制产品逻辑）
 */
final class HarnessFixtures {
    private HarnessFixtures() {
    }

    static HarnessEngine engine(Path workspace) {
        return builder(workspace).build();
    }

    /**
     * 会话按 id 缓存（getSessionOrNull 对未建过的会话返回 null）
     */
    static HarnessEngine.Builder builder(Path workspace) {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        //假模型（不可达地址）：只装配请求，不实际调用
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        return HarnessEngine.of(workspace.toString(), null)
                .modelAdd(model)
                .defaultModel("fake")
                .sessionProvider((id, create) -> create
                        ? sessions.computeIfAbsent(id, InMemoryAgentSession::of)
                        : sessions.get(id));
    }

    /**
     * 带 options 的轨迹（locale 为 null 表示未显式设置）
     */
    static ReActTrace trace(Locale locale, boolean cancelledSink) throws Exception {
        ReActOptions options = new ReActOptions((ChatModel) null);
        if (locale != null) {
            Method m = ReActOptions.class.getDeclaredMethod("setLocale", Locale.class);
            m.setAccessible(true);
            m.invoke(options, locale);
        }

        if (cancelledSink) {
            AtomicReference<FluxSink<?>> ref = new AtomicReference<>();
            Disposable d = Flux.create(ref::set).subscribe();
            d.dispose();
            Method m = ReActOptions.class.getDeclaredMethod("setStreamSink", FluxSink.class);
            m.setAccessible(true);
            m.invoke(options, ref.get());
        }

        ReActTrace trace = new ReActTrace();
        set(trace, "options", options);
        return trace;
    }

    /**
     * 让会话的“当前轨迹”指向 trace（TaskTalent 从这里取父轨迹）
     */
    static void bindTrace(AgentSession session, ReActTrace trace) {
        session.getContext().put(Agent.KEY_CURRENT_UNIT_TRACE_KEY, "__i18n_trace");
        session.getContext().put("__i18n_trace", trace);
        //主代理轨迹（中断补写取消消息时从这里取语言）
        session.getContext().put(TraceUtil.TRACE_KEY_MAIN, trace);
    }

    /** 命令输入 + 缓冲输出（不经分发器直接执行命令时用） */
    static final class Cmd {
        final CommandInput in;
        final BufferedCommandOutput out = new BufferedCommandOutput();

        Cmd(CommandInput in) {
            this.in = in;
        }

        String text() {
            return out.getText();
        }
    }

    static Cmd ctx(Locale locale, AgentSession session, String raw, String name, String... args) {
        HarnessInput input = HarnessInput.of(session, raw).locale(locale);
        return new Cmd(CommandInput.of(input, ParsedCommand.of(name, Arrays.asList(args), raw), locale));
    }

    static void set(Object target, String field, Object value) throws Exception {
        Field f = findField(target.getClass(), field);
        f.setAccessible(true);
        f.set(target, value);
    }

    static Object get(Object target, String field) throws Exception {
        Field f = findField(target.getClass(), field);
        f.setAccessible(true);
        return f.get(target);
    }

    static Object getStatic(Class<?> type, String field) throws Exception {
        Field f = type.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(null);
    }

    static Field findField(Class<?> type, String field) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(field);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(field);
    }

    static Object invoke(Object target, String method, Class<?>[] types, Object... args) throws Throwable {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        Method m = type.getDeclaredMethod(method, types);
        m.setAccessible(true);
        try {
            return m.invoke(target instanceof Class ? null : target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
