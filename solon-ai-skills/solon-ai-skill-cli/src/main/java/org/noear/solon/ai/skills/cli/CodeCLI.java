/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.skills.cli;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.ReActRequest;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Handler;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Code CLI 终端 (Pool-Box 模型)
 * <p>基于 ReAct 模式的代码协作终端，提供多池挂载与任务盒隔离体验</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class CodeCLI implements Handler, Runnable {
    private final static Logger LOG = LoggerFactory.getLogger(CodeCLI.class);
    private final static String SESSION_DEFAULT = "cli";

    private final ChatModel chatModel;
    private AgentSessionProvider sessionProvider;
    private String name = "CodeCLI"; // 默认名称
    private String workDir = ".";
    private final Map<String, String> extraPools = new LinkedHashMap<>();
    private Consumer<ReActAgent.Builder> configurator;
    private boolean enableWeb = true;      // 默认启用 Web
    private boolean enableConsole = true;  // 默认启用控制台
    private boolean enableHitl = false;

    public CodeCLI(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 设置 Agent 名称 (同时也作为控制台输出前缀)
     */
    public CodeCLI name(String name) {
        if (name != null && !name.isEmpty()) {
            this.name = name;
        }
        return this;
    }

    public CodeCLI workDir(String workDir) {
        this.workDir = workDir;
        return this;
    }

    public CodeCLI mountPool(String alias, String dir) {
        if (dir != null) {
            this.extraPools.put(alias, dir);
        }
        return this;
    }

    public CodeCLI session(AgentSessionProvider sessionProvider) {
        this.sessionProvider = sessionProvider;
        return this;
    }

    public CodeCLI config(Consumer<ReActAgent.Builder> configurator) {
        this.configurator = configurator;
        return this;
    }

    /**
     * 是否启用 Web 交互
     */
    public CodeCLI enableWeb(boolean enableWeb) {
        this.enableWeb = enableWeb;
        return this;
    }

    /**
     * 是否启用控制台交互
     */
    public CodeCLI enableConsole(boolean enableConsole) {
        this.enableConsole = enableConsole;
        return this;
    }

    /**
     * 是否启用 HITL 交互
     */
    public CodeCLI enableHitl(boolean enableHitl) {
        this.enableHitl = enableHitl;
        return this;
    }

    private ReActAgent agent;

    protected CliSkill getSkill(AgentSession session) {
        String boxId = session.getSessionId();

        return (CliSkill) session.attrs().computeIfAbsent("CliSkill", x -> {
            CliSkill skill = new CliSkill(boxId, workDir + "/" + boxId);
            extraPools.forEach(skill::mountPool);
            return skill;
        });
    }

    protected void prepare() {
        if (agent == null) {
            if (sessionProvider == null) {
                Map<String, AgentSession> store = new ConcurrentHashMap<>();
                sessionProvider = (k) -> store.computeIfAbsent(k, InMemoryAgentSession::new);
            }

            ReActAgent.Builder agentBuilder = ReActAgent.of(chatModel)
                    .role("你的名字叫 " + name + "。")
                    .instruction("你是一个超级智能助手（什么都能干），有记忆能力。要严格遵守挂载技能中的【交互规范】与【操作准则】执行任务。遇到 @pool 路径请阅读其 SKILL.md。");

            if (enableHitl) {
                agentBuilder.defaultInterceptorAdd(new HITLInterceptor()
                        .onTool("bash", new CodeHITLStrategy()));
            }

            if (configurator != null) {
                configurator.accept(agentBuilder);
            }

            agent = agentBuilder.build();
        }
    }

    private ReActRequest buildRequest(String sessonId, Prompt prompt) {
        if (sessonId == null) {
            sessonId = SESSION_DEFAULT;
        }

        AgentSession session = sessionProvider.getSession(sessonId);

        return agent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.skillAdd(getSkill(session));
                });
    }

    public Flux<AgentChunk> stream(String sessionId, Prompt prompt) {
        return buildRequest(sessionId, prompt)
                .stream();
    }

    public AgentResponse call(String sessionId, Prompt prompt) throws Throwable {
        return buildRequest(sessionId, prompt).call();
    }

    @Override
    public void handle(Context ctx) throws Throwable {
        if (!enableWeb) {
            ctx.status(404); // 如果未启用，直接返回 404
            return;
        }

        prepare();

        String input = ctx.param("input");
        String mode = ctx.param("m");
        String sessionId = ctx.headerOrDefault("X-Session-Id", SESSION_DEFAULT);

        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            ctx.status(400);
            ctx.output("Invalid Session ID");
            return;
        }

        if (Assert.isNotEmpty(input)) {
            if ("call".equals(mode)) {
                ctx.contentType(MimeType.TEXT_PLAIN_UTF8_VALUE);
                String result = buildRequest(sessionId, Prompt.of(input))
                        .call()
                        .getContent();

                ctx.output(result);
            } else {
                ctx.contentType(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE);


                Flux<String> stringFlux = buildRequest(sessionId, Prompt.of(input))
                        .stream()
                        .map(chunk -> {
                            if (chunk.hasContent()) {
                                if (chunk instanceof ReasonChunk) {
                                    return ONode.serialize(new Chunk("reason", chunk.getContent()));
                                } else if (chunk instanceof ActionChunk) {
                                    return ONode.serialize(new Chunk("action", chunk.getContent()));
                                } else if (chunk instanceof ReActChunk) {
                                    return ONode.serialize(new Chunk("agent", chunk.getContent()));
                                }
                            }

                            return "";
                        })
                        .filter(Assert::isNotEmpty)
                        .onErrorResume(e -> Flux.just(ONode.serialize(new Chunk("error", e.getMessage()))))
                        .concatWithValues("[DONE]");

                ctx.returnValue(stringFlux);
            }
        }
    }

    @Override
    public void run() {
        if (!enableConsole) {
            LOG.warn("SolonCodeCLI 控制台交互已禁用");
            return;
        }

        prepare();
        Scanner scanner = new Scanner(System.in);
        printWelcome();
        AgentSession session = sessionProvider.getSession("cli");

        while (true) {
            try {
                // 1. 清理输入缓冲区
                while (System.in.available() > 0) {
                    System.in.read();
                }

                System.out.print("\n\uD83D\uDCBB > ");
                System.out.flush();

                if (!scanner.hasNextLine()) break;
                String input = scanner.nextLine();

                if (input == null || input.trim().isEmpty()) continue;
                if (isSystemCommand(input)) break;

                System.out.print(name + ": ");
                System.out.flush();

                // 【优化点 1】调用封装好的任务执行方法
                performAgentTask(session, input, scanner);

            } catch (Throwable e) {
                System.err.println("\n[提示] " + (e.getMessage() == null ? "执行中断" : e.getMessage()));
            }
        }
    }

    final static String GRAY = "\033[90m", YELLOW = "\033[33m", GREEN = "\033[32m",
            RED = "\033[31m", CYAN = "\033[36m", RESET = "\033[0m";

    /**
     * 执行 Agent 任务（优化版：修复状态泄露与异步同步问题）
     */
    private void performAgentTask(AgentSession session, String input, Scanner scanner) throws Exception {

        String currentInput = input;
        // 标记：是否刚提交完审核结果
        boolean isSubmittingDecision = false;

        while (true) {
            CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean isInterrupted = new AtomicBoolean(false);

            // 1. 启动流（注意：currentInput 在续传时为 null）
            reactor.core.Disposable disposable = buildRequest(session.getSessionId(), Prompt.of(currentInput))
                    .stream()
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(chunk -> {
                        if (chunk instanceof ReasonChunk) {
                            // 思考逻辑：灰色
                            ReasonChunk reasonChunk = (ReasonChunk) chunk;
                            if (chunk.hasContent() && !reasonChunk.isToolCalls()) {
                                System.out.print(GRAY + clearThink(chunk.getContent()) + RESET);
                                if (reasonChunk.isFinished()) {
                                    System.out.println();
                                }
                                System.out.flush();
                            }
                        } else if (chunk instanceof ActionChunk) {
                            // 工具调用逻辑：黄色
                            ActionChunk actionChunk = (ActionChunk) chunk;
                            String toolName = actionChunk.getToolName();
                            String content = chunk.getContent();

                            if (Assert.isNotEmpty(toolName)) {
                                // 打印工具调用的 Observation 结果
                                System.out.println("\n" + YELLOW + "🔨 [执行工具: " + toolName + "]" + RESET);
                                System.out.print(GRAY + ">> Observation: " + RESET + YELLOW + content + RESET);
                            } else {
                                // 兜底打印（非工具调用的 Action）
                                System.out.print("\n" + YELLOW + content + RESET);
                            }
                            // 统一在这里换行或 flush
                            System.out.println();
                            System.out.flush();
                        } else if (chunk instanceof ReActChunk) {
                            // 最终回复：增加分界线
                            System.out.println("\n" + GREEN + "----------------------" + RESET);
                            System.out.println(chunk.getContent());
                        }
                    })
                    .doFinally(signal -> latch.countDown())
                    .subscribe();

            // 【关键点 1】如果是续传，给流一点启动时间，避开旧状态残留的毫秒级窗口
            if (isSubmittingDecision) {
                Thread.sleep(60);
                isSubmittingDecision = false;
            }

            // 2. 阻塞监控循环
            while (latch.getCount() > 0) {
                // A. 检查键盘中断 (Enter)
                if (System.in.available() > 0) {
                    disposable.dispose();
                    isInterrupted.set(true);
                    latch.countDown();
                    break;
                }

                // B. 检查是否有新的人工介入请求
                if (HITL.isHitl(session)) {
                    latch.countDown();
                    break;
                }

                Thread.sleep(40); // 采样频率
            }
            latch.await();

            // 处理用户手动中断
            if (isInterrupted.get()) {
                cleanInputBuffer();
                session.addMessage(ChatMessage.ofAssistant(
                        "【执行摘要】：该任务已被用户手动中断。当前上下文已冻结，我将不再主动执行此任务的后续逻辑。已就绪，请下达新指令。"
                ));
                return;
            }

            // 3. 处理人工介入逻辑
            if (HITL.isHitl(session)) {
                HITLTask task = HITL.getPendingTask(session);

                // 💡 改进：先打印风险评估报告 (Reason)
                if (Assert.isNotEmpty(task.getComment())) {
                    System.out.println(RED + "\n[安全风险评估]: " + task.getComment() + RESET);
                }

                // 💡 改进：如果是 bash，直接显示指令内容，用户不需要猜
                if ("bash".equals(task.getToolName())) {
                    System.out.println(YELLOW + "👉 待执行指令: " + task.getArgs().get("command") + RESET);
                }

                System.out.print(GREEN + "❓ 是否授权执行？(y/n): " + RESET);

                String choice = scanner.nextLine().trim().toLowerCase();
                if (choice.equals("y") || choice.equals("yes")) {
                    System.out.println(GREEN + "✅ 已授权，执行中..." + RESET);
                    HITL.approve(session, task.getToolName());
                } else {
                    System.out.println(RED + "❌ 已拒绝。" + RESET);
                    HITL.reject(session, task.getToolName());
                }

                // 准备续传
                currentInput = null;
                isSubmittingDecision = true;
                continue;
            }

            // 既无中断也无拦截，说明 Prompt 任务彻底执行完毕
            break;
        }
    }

    private String clearThink(String chunk) {
        return chunk.replaceAll("(?s)<\\s*/?think\\s*>", "");
    }

    /**
     * 清理输入缓冲区，防止中断触发的回车符污染下一个指令
     */
    private void cleanInputBuffer() throws Exception {
        Thread.sleep(50); // 给系统 IO 一点反应时间
        while (System.in.available() > 0) {
            System.in.read();
        }
    }

    /**
     * 系统指令判定
     */
    private boolean isSystemCommand(String input) {
        String cmd = input.trim().toLowerCase();
        if ("exit".equals(cmd) || "quit".equals(cmd)) {
            System.out.println("再见！");
            System.exit(0); // 强制退出 JVM
            return true;
        }

        if ("clear".equals(cmd)) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
            return false;
        }
        return false;
    }

    protected void printWelcome() {
        // 获取绝对且规范化的路径，去掉多余的 "."
        String absolutePath;
        try {
            absolutePath = new File(workDir).getCanonicalPath();
        } catch (Exception e) {
            absolutePath = new File(workDir).getAbsolutePath();
        }

        System.out.println("==================================================");
        System.out.println("🚀 " + name + " 已就绪");
        System.out.println("--------------------------------------------------");
        System.out.println("📂 工作空间: " + absolutePath);

        if (!extraPools.isEmpty()) {
            System.out.println("📦 挂载技能池:");
            extraPools.forEach((k, v) -> {
                // 对池路径也做一下规范化显示
                String p = new File(v).getAbsolutePath();
                System.out.println("  - " + k + " -> " + p);
            });
        }

        System.out.println("--------------------------------------------------");
        System.out.println("💡 输入 'exit' 退出, 'clear' 清屏");
        System.out.println("🛑 在输出时按 '回车(Enter)' 可中断回复"); // 新增提示
        System.out.println("==================================================");
    }

    public static class Chunk implements Serializable {
        public final String type;
        public final String text;

        public Chunk(String type, String text) {
            this.type = type;
            this.text = text;
        }
    }
}