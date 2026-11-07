/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness;

import org.noear.solon.ai.harness.agent.task.GenerateTalent;
import org.noear.solon.ai.harness.agent.task.TaskTalent;
import org.noear.solon.ai.talents.cli.ClockTalent;
import org.noear.solon.ai.talents.cli.SkillTalent;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.code.CodeTalent;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
import org.noear.solon.ai.talents.gateway.OpenApiGatewayTalent;
import org.noear.solon.ai.talents.lsp.LspTalent;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.ai.talents.todo.TodoTalent;
import org.noear.solon.ai.talents.web.CodeSearchTalent;
import org.noear.solon.ai.talents.web.WebfetchTalent;
import org.noear.solon.ai.talents.web.WebsearchTalent;
import org.noear.solon.lang.Preview;

/**
 * 引擎装配的内置能力（Talent）实例视图（只读），经 {@link HarnessEngine#getTalents()} 获取。
 *
 * <p>把十几个 {@code getXxxTalent()} 从引擎门面上收进一个视图：宿主日常只用引擎的会话 / 输入 / 运行控制门面，
 * 只有需要直接操作某个内置能力（如刷新 LSP、读 MCP 网关状态）时才来这里取。</p>
 *
 * <p>记忆能力在未注入 {@code memoryProvider} 时为 null，其余恒不为 null。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class HarnessTalents {
    private final HarnessEngine engine;

    HarnessTalents(HarnessEngine engine) {
        this.engine = engine;
    }

    @SuppressWarnings("deprecation")
    public TerminalTalent getTerminal() {
        return engine.getTerminalTalent();
    }

    @SuppressWarnings("deprecation")
    public SkillTalent getSkill() {
        return engine.getSkillTalent();
    }

    @SuppressWarnings("deprecation")
    public TodoTalent getTodo() {
        return engine.getTodoTalent();
    }

    @SuppressWarnings("deprecation")
    public TaskTalent getTask() {
        return engine.getTaskTalent();
    }

    @SuppressWarnings("deprecation")
    public CodeTalent getCode() {
        return engine.getCodeTalent();
    }

    @SuppressWarnings("deprecation")
    public MemoryTalent getMemory() {
        return engine.getMemoryTalent();
    }

    @SuppressWarnings("deprecation")
    public GenerateTalent getGenerate() {
        return engine.getGenerateTalent();
    }

    @SuppressWarnings("deprecation")
    public CodeSearchTalent getCodeSearch() {
        return engine.getCodeSearchTalent();
    }

    @SuppressWarnings("deprecation")
    public ClockTalent getClock() {
        return engine.getClockTalent();
    }

    @SuppressWarnings("deprecation")
    public WebsearchTalent getWebsearch() {
        return engine.getWebsearchTalent();
    }

    @SuppressWarnings("deprecation")
    public WebfetchTalent getWebfetch() {
        return engine.getWebfetchTalent();
    }

    @SuppressWarnings("deprecation")
    public McpGatewayTalent getMcpGateway() {
        return engine.getMcpGatewayTalent();
    }

    @SuppressWarnings("deprecation")
    public OpenApiGatewayTalent getOpenApiGateway() {
        return engine.getOpenApiGatewayTalent();
    }

    @SuppressWarnings("deprecation")
    public LspTalent getLsp() {
        return engine.getLspTalent();
    }
}
