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
package org.noear.solon.ai.skills.claudecode;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatModel;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Solon Code CLI 终端 (Pool-Box 模型)
 * <p>基于 ReAct 模式的代码协作终端，提供多池挂载与任务盒隔离体验</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class SolonCodeCLI implements Handler, Runnable {
    private final static Logger LOG = LoggerFactory.getLogger(SolonCodeCLI.class);

    private final ChatModel chatModel;
    private AgentSession session;
    private String name = "SolonCodeAgent"; // 默认名称
    private String workDir = ".";
    private final Map<String, String> extraPools = new LinkedHashMap<>();
    private Consumer<ReActAgent.Builder> configurator;
    private boolean enableWeb = true;      // 默认启用 Web
    private boolean enableConsole = true;  // 默认启用控制台
    private boolean enableHitl = false;

    public SolonCodeCLI(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 设置 Agent 名称 (同时也作为控制台输出前缀)
     */
    public SolonCodeCLI name(String name) {
        if (name != null && !name.isEmpty()) {
            this.name = name;
        }
        return this;
    }

    public SolonCodeCLI workDir(String workDir) {
        this.workDir = workDir;
        return this;
    }

    public SolonCodeCLI mountPool(String alias, String dir) {
        if (dir != null) {
            this.extraPools.put(alias, dir);
        }
        return this;
    }

    public SolonCodeCLI config(Consumer<ReActAgent.Builder> configurator) {
        this.configurator = configurator;
        return this;
    }

    /**
     * 是否启用 Web 交互
     */
    public SolonCodeCLI enableWeb(boolean enableWeb) {
        this.enableWeb = enableWeb;
        return this;
    }

    /**
     * 是否启用控制台交互
     */
    public SolonCodeCLI enableConsole(boolean enableConsole) {
        this.enableConsole = enableConsole;
        return this;
    }

    /**
     * 是否启用 HITL 交互
     */
    public SolonCodeCLI enableHitl(boolean enableHitl) {
        this.enableHitl = enableHitl;
        return this;
    }

    private ReActAgent agent;

    protected void prepare() {
        if (agent == null) {
            if (session == null) {
                session = new InMemoryAgentSession("cli-" + System.currentTimeMillis());
            }

            CliSkill skills = new CliSkill(session.getSessionId(), workDir);
            extraPools.forEach(skills::mountPool);

            ReActAgent.Builder agentBuilder = ReActAgent.of(chatModel)
                    .role("你的名字叫 " + name + "。")
                    .instruction("你是一个超级智能助手（什么都能干）。要严格遵守挂载技能中的【交互规范】与【操作准则】执行任务。遇到 @pool 路径请阅读其 SKILL.md。")
                    .defaultSkillAdd(skills);

            if (enableHitl) {
                agentBuilder.defaultInterceptorAdd(new HITLInterceptor()
                        .onSensitiveTool("write", "edit", "run_command"));
            }

            if (configurator != null) {
                configurator.accept(agentBuilder);
            }

            agent = agentBuilder.build();
        }
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

        if (Assert.isNotEmpty(input)) {
            System.out.println(input);

            if ("call".equals(mode)) {
                String result = agent.prompt(input).call().getContent();
                System.out.print(result);
                System.out.println();
                System.out.print("\n\uD83D\uDCBB > ");
                ctx.output(result);
            } else {
                ctx.contentType(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE);

                Flux<String> stringFlux = agent.prompt(input)
                        .session(session)
                        .stream()
                        .filter(chunk -> chunk instanceof ReasonChunk)
                        .map(chunk -> {
                            System.out.print(chunk.getContent());
                            return chunk.getContent();
                        })
                        .filter(content -> Assert.isNotEmpty(content))
                        .concatWithValues("[DONE]")
                        .doOnComplete(() -> {
                            System.out.println();
                            System.out.print("\n\uD83D\uDCBB > ");
                        });

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

        while (true) {
            try {
                // 1. 清理输入缓冲区
                while (System.in.available() > 0) { System.in.read(); }

                System.out.print("\n\uD83D\uDCBB > ");
                System.out.flush();

                if (!scanner.hasNextLine()) break;
                String input = scanner.nextLine();

                if (input == null || input.trim().isEmpty()) continue;
                if (isSystemCommand(input)) break;

                System.out.print(name + ": ");
                System.out.flush();

                // 【优化点 1】调用封装好的任务执行方法
                performAgentTask(input, scanner);

            } catch (Throwable e) {
                System.err.println("\n[提示] " + (e.getMessage() == null ? "执行中断" : e.getMessage()));
            }
        }
    }

    /**
     * 执行 Agent 任务（优化版：修复状态泄露与异步同步问题）
     */
    private void performAgentTask(String input, Scanner scanner) throws Exception {
        final String YELLOW = "\033[33m", GREEN = "\033[32m", RED = "\033[31m", RESET = "\033[0m";

        String currentInput = input;
        // 标记：是否刚提交完审核结果
        boolean isSubmittingDecision = false;

        while (true) {
            CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean isInterrupted = new AtomicBoolean(false);

            // 1. 启动流（注意：currentInput 在续传时为 null）
            reactor.core.Disposable disposable = agent.prompt(currentInput)
                    .session(session)
                    .stream()
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(chunk -> {
                        // 渲染逻辑：不依赖 latch 状态，确保最后一段话能打印完
                        if (chunk instanceof ReasonChunk) {
                            ReasonChunk reason = (ReasonChunk) chunk;
                            if (reason.hasContent()) {
                                System.out.print(reason.getContent());
                                System.out.flush();
                            }
                        } else if (chunk instanceof ActionChunk) {
                            System.out.println("\n" + YELLOW + chunk.getContent() + RESET);
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
                return;
            }

            // 3. 处理人工介入逻辑
            if (HITL.isHitl(session)) {
                HITLTask task = HITL.getPendingTask(session);
                System.out.print(GREEN + "\n❓ 是否允许操作 [" + task.getToolName() + "] ？(y/n): " + RESET);

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
        if ("exit".equals(cmd) || "quit".equals(cmd)) return true;
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
}