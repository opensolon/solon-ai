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
package org.noear.solon.ai.agent.react.task;

import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

/**
 * ReAct 执行计划进度更新工具
 * <p>通过显式工具调用而非文本解析来推进 PlanIndex，设计更稳健（Hard Design）</p>
 *
 * @author noear
 * @since 3.9.3
 */
@Preview("3.9.3")
public class PlanTool {
    public static final String TOOL_NAME = "update_task_progress";
    public static final String PARAM_NAME = "next_plan_index";

    // 默认工具描述：侧重于“推进”和“同步”
    public static final String TOOL_DESCRIPTION_DEF =
            "更新任务执行计划的当前进度。当你完成当前步骤并准备进入下一步时，必须调用此工具同步状态。";

    // 默认参数描述：强调从 1 开始的索引
    public static final String TOOL_REASON_DESCRIPTION_DEF =
            "下一个即将开始执行的计划步骤索引（从 1 开始计数的数字）。";

    /**
     * 获取进度更新工具实例
     */
    public static FunctionTool getTool(String toolDescription, String paramDescription) {
        if (Assert.isEmpty(toolDescription)) {
            toolDescription = TOOL_DESCRIPTION_DEF;
        }

        if (Assert.isEmpty(paramDescription)) {
            paramDescription = TOOL_REASON_DESCRIPTION_DEF;
        }

        // 使用 FunctionToolDesc 构建，统一处理参数注入
        return new FunctionToolDesc(TOOL_NAME)
                .description(toolDescription)
                .intParamAdd(PARAM_NAME, paramDescription)
                .doHandle((args) -> {
                    // 这里只需要返回结果，具体的 Trace 状态变更在 ActionTask 拦截中完成
                    // 或者在这里通过上下文更新（如果能拿到 Trace 的话）
                    Object index = args.get(PARAM_NAME);
                    return "Plan progress updated to step " + index + ". Please continue.";
                });
    }
}