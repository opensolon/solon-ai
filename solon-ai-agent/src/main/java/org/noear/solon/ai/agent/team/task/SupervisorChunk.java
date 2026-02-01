/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package org.noear.solon.ai.agent.team.task;

import org.noear.solon.ai.agent.AbsAgentChunk;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;

/**
 * 团队指导者（Supervisor）决策片段块
 * <p>用于在团队协作中，实时传递指导者（负责人）的决策思考、任务分配及调度逻辑的流式内容</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class SupervisorChunk extends AbsAgentChunk {
    private final Node node;
    private final TeamTrace trace;
    private final ChatResponse response;

    public SupervisorChunk(Node node, TeamTrace trace, ChatResponse response) {
        super(trace.getAgentName(), trace.getSession(), response.getMessage());

        this.node = node;
        this.trace = trace;
        this.response = response;
    }

    public Node getNode() {
        return node;
    }

    public TeamTrace getTrace() {
        return trace;
    }

    public ChatResponse getResponse() {
        return response;
    }
}
