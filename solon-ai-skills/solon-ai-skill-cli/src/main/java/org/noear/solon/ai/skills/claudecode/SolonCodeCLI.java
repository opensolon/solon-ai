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

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;
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

        final String GRAY = "\033[90m";
        final String YELLOW = "\033[33m";
        final String RESET = "\033[0m";

        while (true) {
            try {
                // 彻底清理缓冲区
                while (System.in.available() > 0) { System.in.read(); }

                System.out.print("\n\uD83D\uDCBB > ");
                System.out.flush();

                if (!scanner.hasNextLine()) break;
                String input = scanner.nextLine();

                if (input == null || input.trim().isEmpty()) continue;
                if (isSystemCommand(input)) break;

                System.out.print(name + ": ");

                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                final AtomicBoolean lastIsAction = new AtomicBoolean(false);
                final AtomicBoolean inGrayMode = new AtomicBoolean(false);

                // 1. 启动流，并切到弹性线程池跑
                reactor.core.Disposable disposable = agent.prompt(input)
                        .session(session)
                        .stream()
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()) // 关键：解放主线程
                        .doOnNext(chunk -> {
                            // 逻辑保持不变
                            if (chunk instanceof ReasonChunk) {
                                ReasonChunk reason = (ReasonChunk) chunk;
                                if (!reason.hasContent()) return;
                                String content = reason.getContent();
                                boolean isToolCalling = Assert.isNotEmpty(reason.getResponse().getMessage().getToolCalls());
                                if (isToolCalling) {
                                    if (!inGrayMode.get()) {
                                        if (lastIsAction.get()) System.out.println();
                                        System.out.print(GRAY);
                                        inGrayMode.set(true);
                                        lastIsAction.set(false);
                                    }
                                } else {
                                    if (inGrayMode.get()) {
                                        System.out.print(RESET);
                                        inGrayMode.set(false);
                                    }
                                    if (lastIsAction.get()) {
                                        System.out.println();
                                        lastIsAction.set(false);
                                    }
                                }
                                System.out.print(content);
                                System.out.flush();
                            } else if (chunk instanceof ActionChunk) {
                                if (inGrayMode.get()) {
                                    System.out.print(RESET);
                                    inGrayMode.set(false);
                                }
                                System.out.println();
                                System.out.println(YELLOW + chunk.getContent() + RESET);
                                lastIsAction.set(true);
                            }
                        })
                        .doFinally(signalType -> {
                            System.out.print(RESET);
                            latch.countDown();
                        })
                        .subscribe();

                // 2. 主线程现在可以自由地检测键盘了
                while (latch.getCount() > 0) {
                    if (System.in.available() > 0) {
                        disposable.dispose(); // 瞬间掐断
                        latch.countDown();
                        break;
                    }
                    // 微秒级轮询，不吃 CPU 但保证响应
                    Thread.sleep(20);
                }

                // 3. 再次确保锁被释放，并清理换行
                latch.await();
                System.out.println();

            } catch (Throwable e) {
                System.err.println("\n[错误] " + e.getMessage());
                LOG.error("CLI 执行异常", e);
            }
        }
    }

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
        System.out.println("==================================================");
    }
}