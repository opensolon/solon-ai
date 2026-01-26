package org.noear.solon.ai.agent.react.task;

import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;

/**
 *
 * @author noear 2026/1/26 created
 *
 */
public class AskTool {
    public static final String tool_name = "ask_for_information";

    public static final FunctionTool tool = new FunctionToolDesc(tool_name)
            .description("当缺失执行任务所需的必要条件、参数或资料时，必须调用此工具向用户索要，严禁自行编造。")
            .stringParamAdd("reason", "礼貌地询问用户提供该信息的文案（如：为了完成转账，请提供您的银行卡号）")
            .doHandle((args) -> {
                return "{\"status\":\"suspended\"}";
            });
}
