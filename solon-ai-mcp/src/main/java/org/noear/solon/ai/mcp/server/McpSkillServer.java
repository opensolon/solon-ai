/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.mcp.server;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ResourceMapping;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.prompt.PromptImpl;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.lang.Preview;

import java.util.Collection;
import java.util.List;

/**
 * MCP 服务端技能适配器
 * <p>
 * 职责：将本地定义的 {@link Skill} 逻辑通过 MCP 协议导出。
 * 机制：利用注解将技能的生命周期方法（isSupported, getInstruction）映射为 MCP 的 Tool 或 Resource，
 * 供远程 {@link org.noear.solon.ai.mcp.client.McpSkillClient} 发现并调用。
 *
 * @author noear
 * @since 3.9.0
 */
@Preview("3.9.0")
public abstract class McpSkillServer implements Skill {

    /**
     * 导出技能元数据作为 MCP 资源
     */
    @ResourceMapping(uri = "skill://metadataMcp", meta = "{hide:1}")
    public String metadataMcp() {
        return ONode.serialize(this.metadata());
    }

    /**
     * 导出准入检查逻辑为 MCP 工具
     * <p>
     * 注意：此工具标记为 hide，通常由客户端代理调用，不对最终 LLM 暴露
     */
    @ToolMapping(meta = "{hide:1}", description = "禁止 llm 使用")
    public boolean isSupportedMcp(String promptJson) {
        Prompt prompt = ONode.deserialize(promptJson, PromptImpl.class, Feature.Read_AutoType);
        return this.isSupported(prompt);
    }

    /**
     * 导出指令获取逻辑为 MCP 工具
     */
    @ToolMapping(meta = "{hide:1}", description = "禁止 llm 使用")
    public String getInstructionMcp(String promptJson) {
        Prompt prompt = ONode.deserialize(promptJson, PromptImpl.class, Feature.Read_AutoType);
        return this.getInstruction(prompt);
    }

    @ToolMapping(meta = "{hide:1}", description = "禁止 llm 使用")
    public List<String> getToolsMcp(String promptJson) {
        Prompt prompt = ONode.deserialize(promptJson, PromptImpl.class, Feature.Read_AutoType);
        return this.getToolsName(prompt);
    }

    @ToolMapping(meta = "{hide:1}", description = "禁止 llm 使用")
    public void onAttachMcp(String promptJson) {
        Prompt prompt = ONode.deserialize(promptJson, PromptImpl.class, Feature.Read_AutoType);
        this.onAttach(prompt);
    }

    @Override
    public final Collection<FunctionTool> getTools(Prompt prompt) {
        //不充许重载
        return null;
    }

    public List<String> getToolsName(Prompt prompt){
        // null 表示所有工具; 空表示无工具匹配；有表示要过滤名字
        return null;
    }
}
