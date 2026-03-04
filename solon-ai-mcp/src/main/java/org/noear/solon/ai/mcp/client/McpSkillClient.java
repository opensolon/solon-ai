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
package org.noear.solon.ai.mcp.client;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.skill.SkillMetadata;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.lang.Preview;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * MCP 客户端技能代理
 * <p>
 * 职责：作为 MCP 客户端，将远程 MCP 服务的能力（工具、资源、指令）封装为本地 {@link Skill} 接口。
 * 特点：支持跨进程的能力调用，并通过 {@link Prompt} 上下文实现远程准入检查与动态指令获取。
 *
 * @author noear
 * @since 3.9.0
 */
@Preview("3.9.0")
public class McpSkillClient implements Skill {
    /**
     * MCP 客户端提供者，负责底层的通信协议（如 Stdio, SSE）
     */
    protected final McpClientProvider clientProvider;
    /**
     * 缓存的技能元信息
     */
    protected SkillMetadata metadata;

    public McpSkillClient(McpClientProvider clientProvider) {
        this.clientProvider = clientProvider;

        // 从远程加载静态元信息（通过预定义的 Resource URI）
        String metadataJson = clientProvider.readResource("skill://metadataMcp")
                .getContent();

        metadata = ONode.deserialize(metadataJson, SkillMetadata.class);
    }

    @Override
    public String name() {
        return metadata.getName();
    }

    @Override
    public String description() {
        return metadata.getDescription();
    }

    @Override
    public SkillMetadata metadata() {
        return metadata;
    }

    /**
     * 跨进程准入检查：请求远程服务端判断当前 Prompt 环境是否允许激活该技能
     */
    @Override
    public boolean isSupported(Prompt prompt) {
        String promptJson = ONode.serialize(prompt, Feature.Write_ClassName);

        String result = clientProvider.callTool("isSupportedMcp",
                        Utils.asMap("promptJson", promptJson))
                .getContent();

        return "true".equals(result);
    }

    @Override
    public void onAttach(Prompt prompt) {
        String promptJson = ONode.serialize(prompt, Feature.Write_ClassName);

        clientProvider.callTool("onAttachMcp",
                        Utils.asMap("promptJson", promptJson));
    }

    /**
     * 动态指令获取：从远程服务端获取针对当前上下文优化后的 System Message 指令
     */
    @Override
    public String getInstruction(Prompt prompt) {
        String promptJson = ONode.serialize(prompt, Feature.Write_ClassName);

        return clientProvider.callTool("getInstructionMcp",
                        Utils.asMap("promptJson", promptJson))
                .getContent();
    }

    /**
     * 获取远程导出的工具流
     * <p>
     * 过滤策略：自动剔除标记为 "hide" 的管理类工具（即元数据同步工具），仅保留业务工具
     */
    protected Stream<FunctionTool> getToolsStream() {
        return clientProvider.getTools().stream()
                .filter(tool -> {
                    return tool.meta() == null || tool.meta().containsKey("hide") == false;
                });
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        String promptJson = ONode.serialize(prompt, Feature.Write_ClassName);

        String toolsNameJson = clientProvider.callTool("getToolsMcp",
                        Utils.asMap("promptJson", promptJson))
                .getContent();

        List<String> toolsName = ONode.deserialize(toolsNameJson, List.class);

        if(toolsName == null){
            return getToolsStream().collect(Collectors.toList());
        } else if(toolsName.isEmpty()) {
            return Collections.EMPTY_LIST;
        } else {
            return getToolsStream().filter(tool -> toolsName.contains(tool.name()))
                    .collect(Collectors.toList());
        }
    }
}