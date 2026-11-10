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
package org.noear.solon.ai.agent.util;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.i18n.AgentI18nKeys;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.core.util.Assert;

import java.util.Collections;
import java.util.Locale;

/**
 * 流程挂起工具（主要为 TeamAgent 传递 ctx.stop 信号）
 * 用于在缺失关键信息时，主动挂起当前推理逻辑，等待外部输入后再恢复执行。
 *
 * @author noear
 * @since 3.9.0
 */
public class FeedbackTool {
    public static final String TOOL_NAME = "__ask_for_feedback";

    /**
     * 默认中文描述（保留以兼容；运行时请使用 {@link #getToolDescription(Locale)}）
     */
    public static final String TOOL_DESCRIPTION_DEF = "当任务缺失必要参数、条件或外部反馈而无法继续时，必须调用此工具反馈，并向用户说明原因。";
    public static final String TOOL_REASON_DESCRIPTION_DEF = "向用户说明原因";


    /**
     * 获取默认工具描述（按语言）
     *
     * @param locale 语言（null 时为默认语言）
     */
    public static String getToolDescription(Locale locale) {
        return AgentI18n.get(locale, AgentI18nKeys.REACT_FEEDBACK_DESCRIPTION);
    }

    /**
     * 获取默认 reason 参数描述（按语言）
     *
     * @param locale 语言（null 时为默认语言）
     */
    public static String getToolReasonDescription(Locale locale) {
        return AgentI18n.get(locale, AgentI18nKeys.REACT_FEEDBACK_REASON);
    }

    /**
     * 获取工具（缺省描述使用默认语言）
     */
    public static FunctionTool getTool(String tool_description, String tool_reason_description) {
        return getTool(tool_description, tool_reason_description, null);
    }

    /**
     * 获取工具
     *
     * @param locale 缺省描述的语言（null 时为默认语言）
     */
    public static FunctionTool getTool(String tool_description, String tool_reason_description, Locale locale) {
        if (Assert.isEmpty(tool_description)) {
            tool_description = getToolDescription(locale);
        }

        if (Assert.isEmpty(tool_reason_description)) {
            tool_reason_description = getToolReasonDescription(locale);
        }

        return new FunctionToolDesc(TOOL_NAME).returnDirect(true)
                .description(tool_description)
                .stringParamAdd("reason", tool_reason_description)
                .doHandle((args) -> {
                    return ONode.ofBean(args).set("status", "suspended").toJson();
                });
    }

    public static boolean isSuspend(String text) {
        return text != null && text.contains("\"status\":\"suspended\"");
    }

    /**
     * 构造可被 {@link #isSuspend(String)} 识别的挂起决策文本。
     * 供 Supervisor 在自行处理 tool_calls 时统一出口。
     */
    public static String asSuspendDecision(String reason) {
        ONode oNode = new ONode().asObject();
        oNode.set("reason", reason == null ? "" : reason);
        oNode.set("status", "suspended");

        return oNode.toJson();
    }
}
