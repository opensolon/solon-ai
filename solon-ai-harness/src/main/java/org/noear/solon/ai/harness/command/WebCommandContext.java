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
package org.noear.solon.ai.harness.command;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Web 端命令上下文实现
 * <p>
 * println() 收集到缓冲区，runAgentTask() 记录 prompt 供外部回调处理。
 *
 * @author noear
 * @since 2026.4.28
 */
public class WebCommandContext implements CommandContext {

    private final AgentSession session;
    private final HarnessEngine agentRuntime;
    private final String rawInput;
    private final String commandName;
    private final List<String> args;

    private final List<String> outputBuffer = new ArrayList<>();
    private boolean agentTaskRequested = false;
    private String agentTaskPrompt;

    public WebCommandContext(AgentSession session, HarnessEngine agentRuntime,String rawInput, String commandName, List<String> args) {
        this.session = session;
        this.agentRuntime = agentRuntime;
        this.rawInput = rawInput;
        this.commandName = commandName;
        this.args = args != null ? args : Collections.emptyList();
    }

    @Override
    public AgentSession getSession() {
        return session;
    }

    @Override
    public HarnessEngine getEngine() {
        return agentRuntime;
    }

    @Override
    public String getRawInput() {
        return rawInput;
    }

    @Override
    public String getCommandName() {
        return commandName;
    }

    @Override
    public List<String> getArgs() {
        return args;
    }

    @Override
    public void println(String text) {
        outputBuffer.add(text);
    }

    @Override
    public boolean supportsAnsi() {
        return false;
    }

    @Override
    public void runAgentTask(String prompt) {
        this.agentTaskRequested = true;
        this.agentTaskPrompt = prompt;
    }

    @Override
    public String getAgentTaskPrompt() {
        return agentTaskPrompt;
    }

    /**
     * 是否请求了 Agent 任务
     */
    public boolean isAgentTaskRequested() {
        return agentTaskRequested;
    }

    /**
     * 获取 println 收集的输出缓冲区
     */
    public List<String> getOutputBuffer() {
        return outputBuffer;
    }
}
