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
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 命令分发器（通用，适用于 CLI、Web 等所有端）
 * <p>
 * 从输入中检测命令前缀、解析参数、查找命令、构建上下文并执行。
 *
 * @author noear
 * @since 2026.4.28
 */
public class CommandDispatcher {

    @FunctionalInterface
    public interface AgentTaskHandler {
        /**
         * Agent 任务回调函数（由各端实现）
         *
         * @param command 触发的命令
         * @param ctx     Web 命令上下文（包含 agentTaskPrompt）
         * @return Flux 流（Web 端）或 null（CLI 端直接在回调内处理）
         */
        Flux<String> handle(Command command, WebCommandContext ctx);
    }

    private final CommandRegistry registry;

    public CommandDispatcher(CommandRegistry registry) {
        this.registry = registry;
    }

    /**
     * 分发命令输入
     *
     * @param input       用户原始输入
     * @param session     Agent 会话
     * @param engine      Agent 运行时
     * @param environment 当前运行环境（"cli"、"web" 等）
     * @param agentHandler Agent 任务回调（Web 端使用）
     * @return 命令结果，如果输入不是命令则返回 null
     */
    public CommandResult dispatch(String input, AgentSession session, HarnessEngine engine,
                                   String environment,
                                   AgentTaskHandler agentHandler) throws Exception {
        if (!input.startsWith("/")) {
            return null;
        }

        // 解析命令名和参数
        String[] parts = input.trim().substring(1).split("\\s+");
        String cmdName = parts[0].toLowerCase();
        List<String> args = parts.length > 1
                ? Arrays.asList(Arrays.copyOfRange(parts, 1, parts.length))
                : Collections.emptyList();

        // 查找命令
        Command command = registry.find(cmdName);
        if (command == null) {
            return null;
        }

        // 检查环境支持
        if (!isSupported(command, environment)) {
            return null;
        }

        // 构建 Web 上下文
        WebCommandContext ctx = new WebCommandContext(session, engine, input, cmdName, args);

        // 执行命令
        boolean handled = command.execute(ctx);

        // 处理结果
        if (ctx.isAgentTaskRequested()) {
            Flux<String> flux = null;
            if (agentHandler != null) {
                flux = agentHandler.handle(command, ctx);
            }
            return new CommandResult(true, ctx.getOutputBuffer(), true, flux);
        } else {
            return new CommandResult(handled, ctx.getOutputBuffer(), false, null);
        }
    }

    /**
     * 检查命令是否支持指定环境
     */
    public static boolean isSupported(Command command, String environment) {
        if (command.cliOnly() && !"cli".equals(environment)) {
            return false;
        }
        return true;
    }
}
