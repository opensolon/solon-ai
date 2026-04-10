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
        return "子代理管理专家：委派任务给专门的子代理（比如：explore、plan、bash 等）";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 当前可用的子代理注册表\n");
        sb.append("<available_agents>\n");
        for (AgentDefinition agentDefinition : engine.getAgentManager().getAgents()) {
            sb.append(String.format("  - \"%s\": %s\n", agentDefinition.getName(), agentDefinition.getDescription()));
        }
        sb.append("</available_agents>\n\n");

        sb.append("**规则提示**：\n");
        sb.append("1. **上下文隔离**: 子代理不共享主会话历史，请在 prompt 中提供必要的背景信息。\n");
        sb.append("2. **并行限制**: 使用 multitask 时，确保任务间不存在同一文件的写冲突。");

        return sb.toString();
    }

    @ToolMapping(name = "task", description =
            "分派任务给专项子代理。所有实际开发工作必须使用此工具委派给子代理完成。")
    public String task(@Body TaskOp taskSpec, String __cwd, String __sessionId) {
        if(Assert.isEmpty(__sessionId)){
            throw new IllegalStateException("__sessionId is required");
        }

        AgentSession __parentSession = engine.getSession(__sessionId);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getContext());

        return taskDo(__parentTrace, __cwd, taskSpec, false);
    }

    @ToolMapping(name = "multitask", description =
            "并行执行多个独立子任务。仅用于互不干扰的任务（如不同模块的修改或多路搜索）。单次调用中的每个任务必须使用不同的 task_id，用于在结果中区分各任务的响应")
    public String multitask(@Param(name = "tasks", description = "任务列表") List<TaskOp> tasks, String __cwd, String __sessionId) {
        if (Assert.isEmpty(tasks)) {
            return "WARNING: 任务列表为空";
        }

        if(Assert.isEmpty(__sessionId)){
            throw new IllegalStateException("__sessionId is required");
        }

        AgentSession __parentSession = engine.getSession(__sessionId);
        ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getContext());

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (TaskOp task : tasks) {
            // 使用 RunUtil.io() 是正确的，因为这主要是 I/O 密集型（等待 AI 响应）
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                    taskDo(__parentTrace, __cwd, task, true), RunUtil.io());
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .handle((v, ex) -> {
                    StringBuilder compositeResult = new StringBuilder();
                    compositeResult.append("<multitask_results>\n");

                    for (int i = 0; i < futures.size(); i++) {
                        try {
                            // 获取子代理返回的 XML 片段
                            String subTaskXml = futures.get(i).get();
                            compositeResult.append(subTaskXml).append("\n");
                        } catch (Exception e) {
                            TaskOp task = tasks.get(i);

                            LOG.error("任务[{} - {}]执行失败: {}", task.getTaskId(), task.getAgentName(), e.getMessage(), e);

                            String result = String.format("ERROR: 任务执行失败: %s", e.getMessage());

                            String subTaskXml = formatTaskResp(task, false, result);
                            compositeResult.append(subTaskXml).append("\n");
                        }
                    }
                    compositeResult.append("</multitask_results>");

                    return compositeResult.toString();
                }).join();
    }

    private String taskDo(ReActTrace __parentTrace, String __cwd, TaskOp task, boolean isMultitask) {
        AgentDefinition agentDefinition = engine.getAgentManager().getAgent(task.getAgentName());
        if (agentDefinition == null) {
            return "ERROR: 未知的子代理类型 '" + task.getAgentName() + "'。";
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("task-description: {}\ntask-prompt: {}", task.getDescription(), task.getPrompt());
        }

        String result = null;
        ReActAgent agent = agentDefinition.builder(engine).build();
        final AgentSession session = InMemoryAgentSession.of(agent.name());

        try {
            if (__parentTrace.getOptions().getStreamSink() == null) {
                // 同步模式
                AgentResponse response = agent.prompt(task.getPrompt())
                        .session(session)
                        .options(o -> {
                            o.toolContextPut("__cwd", __cwd);
                        })
                        .call();

                __parentTrace.getMetrics().addMetrics(response.getMetrics());
                result = response.getContent();
            } else {
                // 流式模式
                ReActChunk response = (ReActChunk) agent.prompt(task.getPrompt())
                        .session(session)
                        .options(o -> {
                            o.toolContextPut("__cwd", __cwd);
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

            return formatTaskResp(task, true, result);
        } catch (Throwable e) {
            LOG.error("任务[{} - {}]执行失败: {}", task.getTaskId(), task.getAgentName(), e.getMessage(), e);

            result = String.format("ERROR: 任务执行失败: %s", e.getMessage());

            return formatTaskResp(task, false, result);
        }
    }

    private String formatTaskResp(TaskOp task, boolean successful, String result) {
        StringBuilder buf = new StringBuilder();

        buf.append("<task_response>");
        buf.append("<task_id>").append(task.getTaskId()).append("</task_id>");
        buf.append("<agent_name>").append(task.getAgentName()).append("</agent_name>");
        buf.append("<status>").append(successful ? "success" : "failure").append("</status>");
        buf.append("<content><![CDATA[").append(result != null ? result : "").append("]]></content>");

        buf.append("</task_response>");

        return buf.toString();
    }


    /**
     * 任务定义
     */
    public static class TaskOp {
        @Param(name = "task_id", description = "任务ID（仅支持字母和数字）")
        private String task_id;
        @Param(name = "agent_name", description = "子代理名称")
        private String agent_name;
        @Param(name = "prompt", description = "派给子代理的任务描述。子代理看不见当前历史，每次都是重新开始，必须要非常详细的描述任务，并传递用户的原始意图。")
        private String prompt;
        @Param(name = "description", required = false, description = "简短的任务描述")
        private String description;

        public String getTaskId() {
            return task_id;
        }

        public String getAgentName() {
            return agent_name;
        }

        public String getPrompt() {
            return prompt;
        }

        public String getDescription() {
            return description;
        }

        @Override
        public String toString() {
            return "TaskOp{" +
                    "task_id='" + task_id + '\'' +
                    "agent_name='" + agent_name + '\'' +
                    ", desc='" + description + '\'' +
                    '}';
        }
    }
}