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
package org.noear.solon.ai.harness.agent;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ActionEndChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.react.task.ThoughtChunk;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RunUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 子代理技能
 *
 * 将子代理能力暴露为可调用的工具（Claude Code Subagent 类似实现）
 *
 * @author bai
 * @since 3.9.5
 */
public class TaskSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(TaskSkill.class);

    public static final String TOOL_TASK = "task";
    public static final String TOOL_MULTITASK = "multitask";

    private final HarnessEngine engine;

    public TaskSkill(HarnessEngine engine) {
        this.engine = engine;
    }

    @Override
    public String description() {
        return "多任务调度专家：将复杂任务拆解并委派给专项子代理（如 explore, plan, bash 等），支持并行处理以提高效率。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 当前可用的子代理\n");
        sb.append("<available_agents>\n");
        for (AgentDefinition agentDefinition : engine.getAgentManager().getAgents()) {
            sb.append(String.format("  - \"%s\": %s\n", agentDefinition.getName(), agentDefinition.getDescription()));
        }
        sb.append("</available_agents>\n\n");

        sb.append("## 任务分配策略：\n");
        sb.append("1. **优先并行**: 当任务可以被拆分为互不干扰的独立单元（如：同时分析 A 文件和 B 文件）时，**必须**使用 `multitask` 以节省时间。\n");
        sb.append("2. **原子性**: 每个子任务应具备明确的边界。\n");
        sb.append("3. **上下文感知**: 必须在 prompt 中提供任务所需的全部背景，子代理无法看到主会话的完整历史。\n");

        return sb.toString();
    }

    @ToolMapping(name = "task", description =
            "委派单一任务给专项子代理。适用于需要深度思考、多步操作或特定领域知识（如文件操作、代码分析）的场景。不支持并行调用（并行请用 multitask）。")
    public String task(@Body SingleTaskOp taskSpec, String __cwd, String __sessionId) {
        if (Assert.isEmpty(__sessionId)) {
            throw new IllegalStateException("__sessionId is required");
        }

        AgentSession __parentSession = engine.getSession(__sessionId);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getContext());

        MultiTaskOp taskOp = new MultiTaskOp();
        taskOp.agent_name = taskSpec.agent_name;
        taskOp.description = taskSpec.description;
        taskOp.prompt = taskSpec.prompt;

        return taskDo(__parentTrace, __cwd, __sessionId, taskOp, false);
    }

    @ToolMapping(name = "multitask", description =
            "并行执行多个互不依赖的子任务。要求任务之间必须没有资源竞争（例如：不同的模块开发、多路搜索）。")
    public String multitask(@Param(name = "tasks", description = "任务列表") List<MultiTaskOp> tasks, String __cwd, String __sessionId) {
        if (Assert.isEmpty(tasks)) {
            return "WARNING: 任务列表为空";
        }

        if (Assert.isEmpty(__sessionId)) {
            throw new IllegalStateException("__sessionId is required");
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("任务接收：{}", ONode.serialize(tasks));
        }

        AgentSession __parentSession = engine.getSession(__sessionId);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getContext());


        List<CompletableFuture<String>> futures = new ArrayList<>();

        for (MultiTaskOp task : tasks) {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                    taskDo(__parentTrace, __cwd, __sessionId, task, true), RunUtil.io());
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    StringBuilder compositeResult = new StringBuilder();
                    compositeResult.append("<multitask_results>\n");
                    for (CompletableFuture<String> f : futures) {
                        compositeResult.append(f.join()).append("\n");
                    }
                    compositeResult.append("</multitask_results>");
                    return compositeResult.toString();
                })
                .exceptionally(ex -> "ERROR: Multitask aggregate failed: " + ex.getMessage())
                .join();

    }

    private String taskDo(ReActTrace __parentTrace, String __cwd, String __sessionId, MultiTaskOp task, boolean isMultitask) {
        AgentDefinition agentDefinition = engine.getAgentManager().getAgent(task.agent_name);
        if (agentDefinition == null) {
            return "ERROR: 未知的子代理类型 '" + task.agent_name + "'。";
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("任务开始[{} - {}]: {}", task.index, task.agent_name, ONode.serialize(task));
        }

        String result = null;
        ReActAgent agent = agentDefinition.builder(engine).build();
        final AgentSession session = InMemoryAgentSession.of(agent.name());

        try {
            if (__parentTrace.getOptions().getStreamSink() == null) {
                // 同步模式
                AgentResponse response = agent.prompt(task.prompt)
                        .session(session)
                        .options(o -> {
                            o.toolContextPut(HarnessEngine.ATTR_CWD, __cwd);
                            o.toolContextPut(ChatSession.ATTR_SESSIONID, __sessionId);
                        })
                        .call();

                __parentTrace.getMetrics().addMetrics(response.getMetrics());
                result = response.getContent();
            } else {
                // 流式模式
                ReActChunk response = (ReActChunk) agent.prompt(task.prompt)
                        .session(session)
                        .options(o -> {
                            o.toolContextPut(HarnessEngine.ATTR_CWD, __cwd);
                            o.toolContextPut(ChatSession.ATTR_SESSIONID, __sessionId);
                        })
                        .stream()
                        .doOnNext(chunk -> {
                            if (chunk instanceof ActionEndChunk) {
                                __parentTrace.getOptions().getStreamSink().next(chunk);
                            } else {
                                if (isMultitask) {
                                    if (chunk instanceof ThoughtChunk) {
                                        chunk.getMeta().put(TOOL_MULTITASK, 1);
                                        __parentTrace.getOptions().getStreamSink().next(chunk);
                                    }
                                } else {
                                    if (chunk instanceof ReasonChunk) {
                                        __parentTrace.getOptions().getStreamSink().next(chunk);
                                    }
                                }
                            }
                        })
                        .blockLast();

                __parentTrace.getMetrics().addMetrics(response.getMetrics());
                result = response.getContent();
            }


            if (LOG.isDebugEnabled()) {
                LOG.debug("任务成功[{} - {}]: {}", task.index, task.agent_name, task.description);
            }

            return formatTaskResp(task, true, result, isMultitask);
        } catch (Throwable e) {
            LOG.error("任务失败[{} - {}]: {}", task.index, task.agent_name, e.getMessage(), e);

            result = String.format("ERROR: 任务执行失败: %s", e.getMessage());

            return formatTaskResp(task, false, result, isMultitask);
        }
    }

    private String formatTaskResp(MultiTaskOp task, boolean successful, String result, boolean isMultitask) {
        StringBuilder buf = new StringBuilder();

        buf.append("<task_result>");
        if (isMultitask) {
            buf.append("<index>").append(task.index).append("</index>");
        }
        buf.append("<description>").append(task.description).append("</description>");
        buf.append("<agent_name>").append(task.agent_name).append("</agent_name>");
        buf.append("<result_status>").append(successful ? "success" : "failure").append("</result_status>");
        buf.append("<result_content><![CDATA[").append(result != null ? result : "").append("]]></result_content>");
        buf.append("</task_result>");

        return buf.toString();
    }

    public static class SingleTaskOp {
        @Param(name = "agent_name", description = "子代理名称")
        public String agent_name;
        @Param(name = "prompt", description = "发给子代理的详细指令。由于子代理是无状态的（上下文隔离），必须在此提供任务所需的所有背景信息、具体要求及预期输出格式。")
        public String prompt;
        @Param(name = "description", description = "任务内容的极简摘要（如：'重构用户认证逻辑'）。该描述将作为标签出现在执行日志和结果摘要中，用于快速识别任务意图。")
        public String description;

        @Override
        public String toString() {
            return "SingleTaskOp{" +
                    "agent_name='" + agent_name + '\'' +
                    ", description='" + description + '\'' +
                    '}';
        }
    }

    /**
     * 任务定义
     */
    public static class MultiTaskOp extends SingleTaskOp {
        @Param(name = "index",
                description = "任务唯一序号，每个任务分配唯一的递增整数（从1开始），以便匹配返回结果",
                defaultValue = "1")
        public int index = 1;

        @Override
        public String toString() {
            return "MultiTaskOp{" +
                    "index='" + index + '\'' +
                    "agent_name='" + agent_name + '\'' +
                    ", description='" + description + '\'' +
                    '}';
        }
    }
}