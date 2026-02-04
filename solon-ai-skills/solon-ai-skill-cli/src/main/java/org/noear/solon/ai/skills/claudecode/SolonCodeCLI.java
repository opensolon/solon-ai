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
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Solon Code CLI 终端 (Pool-Box 模型)
 * <p>基于 ReAct 模式的代码协作终端，提供多池挂载与任务盒隔离体验</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class SolonCodeCLI {
    private final static Logger LOG = LoggerFactory.getLogger(SolonCodeCLI.class);

    private final ChatModel chatModel;
    private AgentSession session;
    private String name = "SolonCodeAgent"; // 默认名称
    private String workDir = ".";
    private final Map<String, String> extraPools = new LinkedHashMap<>();
    private boolean streaming = true;
    private int maxSteps = 20;

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

    public SolonCodeCLI maxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
        return this;
    }

    public SolonCodeCLI streaming(boolean streaming) {
        this.streaming = streaming;
        return this;
    }

    public void start() {
        if (session == null) {
            session = new InMemoryAgentSession("cli-" + System.currentTimeMillis());
        }

        CliSkill skills = new CliSkill(session.getSessionId(), workDir);
        extraPools.forEach(skills::mountPool);

        ReActAgent agent = ReActAgent.of(chatModel)
                .role("你的名字叫 " + name + "。")
                .instruction("你是一个超级智能助手（什么都能干）。要严格遵守挂载技能中的【交互规范】与【操作准则】执行任务。遇到 @pool 路径请阅读其 SKILL.md。")
                .defaultSkillAdd(skills)
                .maxSteps(maxSteps)
                .build();

        Scanner scanner = new Scanner(System.in);
        printWelcome();

        while (true) {
            try {
                System.out.print("\n\uD83D\uDCBB > ");
                String input = scanner.nextLine();

                if (input == null || input.trim().isEmpty()) continue;
                if (isSystemCommand(input)) break;

                System.out.print(name + ": ");

                if (streaming) {
                    final String[] frames = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
                    final int[] frameIdx = {0};
                    final AtomicBoolean hasSpinner = new AtomicBoolean(false);

                    agent.prompt(input)
                            .session(session)
                            .stream()
                            .doOnNext(chunk -> {
                                // 逻辑：只要是 Chunk 进来，我们都维持转子的旋转
                                // 如果是 Reason 内容，我们打印它；如果是 Action，我们只转圈
                                if (hasSpinner.get()) {
                                    System.out.print("\b\b");
                                }

                                if (chunk instanceof ReasonChunk) {
                                    String content = chunk.getContent();
                                    if (content != null) {
                                        System.out.print(content);
                                    }
                                }

                                System.out.print(" " + frames[frameIdx[0]++ % frames.length]);
                                System.out.flush();
                                hasSpinner.set(true);
                            })
                            .blockLast();

                    if (hasSpinner.get()) {
                        System.out.print("\b\b  \b\b");
                    }
                    System.out.println();
                } else {
                    String response = agent.prompt(input).session(session).call().getContent();
                    System.out.println(response);
                }

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
        System.out.println("🚀 " + name + " 终端已就绪");
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