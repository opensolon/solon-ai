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

import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;

/**
 * Claude Code CLI 终端类
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class ClaudeCodeCLI {
    private final static Logger LOG = LoggerFactory.getLogger(ClaudeCodeCLI.class);

    private final ChatModel chatModel;
    private AgentSession session;
    private String workDir = ".";
    private String sharedSkillsDir;
    private boolean streaming = true;
    private int maxSteps = 20;

    public ClaudeCodeCLI(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 设置工作目录
     */
    public ClaudeCodeCLI workDir(String workDir) {
        this.workDir = workDir;
        return this;
    }

    /**
     * 设置共享技能目录
     */
    public ClaudeCodeCLI sharedSkillsDir(String sharedSkillsDir) {
        this.sharedSkillsDir = sharedSkillsDir;
        return this;
    }

    public ClaudeCodeCLI maxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
        return this;
    }

    /**
     * 是否开启流式响应
     */
    public ClaudeCodeCLI streaming(boolean streaming) {
        this.streaming = streaming;
        return this;
    }

    public void start() {
        // 1. 初始化核心技能组件
        ClaudeCodeAgentSkills skills = new ClaudeCodeAgentSkills(workDir, sharedSkillsDir);

        // 2. 构建 ReAct Agent
        ReActAgent agent = ReActAgent.of(chatModel)
                .role("ClaudeCodeAgent")
                .instruction("严格遵守挂载技能中的【规范协议】执行任务")
                .defaultSkillAdd(skills)
                .maxSteps(maxSteps)
                .build();

        if (session == null) {
            session = new InMemoryAgentSession("cli");
        }

        // 4. 进入 REPL 循环
        Scanner scanner = new Scanner(System.in);
        printWelcome();

        while (true) {
            try {
                System.out.print("\n\uD83D\uDCBB > "); // 使用小图标美化终端
                String input = scanner.nextLine();

                if (input == null || input.trim().isEmpty()) continue;
                if (isSystemCommand(input)) break;

                System.out.print("Claude: ");

                if (streaming) {
                    // 流式输出响应
                    AgentChunk lastChunk = agent.prompt(input)
                            .session(session)
                            .stream()
                            .doOnNext(chunk -> {
                                if (chunk instanceof ReasonChunk) {
                                    //思考输出
                                    System.out.print("\033[90m" + chunk.getContent() + "\033[0m");
                                } else if (chunk instanceof ActionChunk) {
                                    //动作输出
                                    System.out.print("\n\uD83D\uDEE0\ufe0f  " + chunk.getContent());
                                } else if (chunk instanceof ReActChunk) {
                                    //最终结果输出
                                    System.out.print(chunk.getContent());
                                }
                            })
                            .blockLast();
                } else {
                    // 一次性输出
                    String response = agent.prompt(input).call().getContent();
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

    private void printWelcome() {
        System.out.println("--------------------------------------------------");
        System.out.println("Claude Code CLI (Solon AI Edition)");
        System.out.println("工作目录: " + workDir);
        System.out.println("共享目录: " + (sharedSkillsDir != null ? sharedSkillsDir : "未设置"));
        System.out.println("输入 'exit' 退出, 'clear' 清屏");
        System.out.println("--------------------------------------------------");
    }
}