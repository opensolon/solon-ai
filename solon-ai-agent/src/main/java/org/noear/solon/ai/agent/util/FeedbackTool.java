/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.agent.util;

import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;

/**
 * 流程挂起工具（主要为 TeamAgent 传递 ctx.stop 信号）
 * 用于在缺失关键信息时，主动挂起当前推理逻辑，等待外部输入后再恢复执行。
 *
 * @author noear
 * @since 3.9.0
 */
public class FeedbackTool {
    public static final String tool_name = "__ask_for_feedback";

    public static final FunctionTool tool = new FunctionToolDesc(tool_name).returnDirect(true)
            .description("当任务缺失必要参数、条件或外部反馈而无法继续时，必须调用此工具反馈，并向用户说明原因。")
            .stringParamAdd("reason", "向用户说明原因，并请求补全缺失信息")
            .doHandle((args) -> {
                return ONode.ofBean(args).set("status", "suspended").toJson();
            });

    public static boolean isSuspend(String text) {
        return text != null && text.contains("\"status\":\"suspended\"");
    }
}
