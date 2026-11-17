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
package org.noear.solon.ai.talents.gateway;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.snack4.codec.TypeRef;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.tool.AbsTool;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.talents.gateway.i18n.McpGatewayI18n;
import org.noear.solon.ai.talents.gateway.i18n.McpGatewayI18nKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP 网关的中转调用工具 {@code call_tool}（包内）。
 *
 * <p>只转发模型给的 tool_args（args 里其余的键是工具上下文：会话 id、工作目录、边界与视野、语言等，
 * 第三方 MCP 服务不认识也不应看到；严格校验参数的服务还会因多出的键直接报参数非法）。
 * 调用本身的失败（未找到、重名、参数不是 JSON 对象、执行异常）一律返回报错结果（isError）；
 * 工具报错或服务端报参数非法时附上完整参数定义；只在请求确定没送达时重试。
 *
 * @author noear
 * @since 4.2.0
 */
final class McpCallTool extends AbsTool {
    private static final Logger LOG = LoggerFactory.getLogger(McpCallTool.class);

    static final String NAME = "call_tool";
    static final String ARG_SERVER_NAME = "server_name";
    static final String ARG_TOOL_NAME = "tool_name";
    static final String ARG_TOOL_ARGS = "tool_args";

    private final McpGatewayTalent gateway;

    McpCallTool(McpGatewayTalent gateway) {
        this.gateway = gateway;

        // server_name 可选：为空且边界内工具名唯一时自动补上（兼容不带服务名的旧调用），重名时由返回值提示指定
        addParam(ARG_SERVER_NAME, String.class, false, "");
        addParam(ARG_TOOL_NAME, String.class, "");
        addParam(ARG_TOOL_ARGS, TypeRef.mapOf(String.class, Object.class).getType(), "");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "代理执行特定的MCP工具";
    }

    @Override
    public ToolResult handle(Map<String, Object> args) {
        String serverName = GatewaySupport.textOf(args.get(ARG_SERVER_NAME));
        String toolName = GatewaySupport.textOf(args.get(ARG_TOOL_NAME));
        Locale locale = AiI18n.toLocale(args.get(AiI18n.ATTR_LOCALE));

        // 调用本身的失败一律返回报错结果（与框架对未找到工具、工具异常的处理一致），客户端据此标红
        if (Utils.isBlank(toolName)) {
            return ToolResult.error(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_NAME_BLANK));
        }

        // 视野不是权限：call_tool 是点名调用，不按视野收窄；
        // 但强制边界要守，越界按「不存在」处理（不泄露其它租户的服务是否存在）
        McpServerRegistry.Target target = gateway.registry().resolveTarget(serverName, toolName, McpGatewayTalent.getAllowed(args));
        if (target == null) {
            return ToolResult.error(McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_CALL_MISSING, "name", toolName));
        }

        if (target.tool == null) {
            return ToolResult.error(McpGatewayTalent.ambiguousText(locale, toolName, target.candidates));
        }

        final FunctionTool tool = target.tool;

        // 只转发模型给的参数（副本，JSON 字符串会解析一次）
        final Map<String, Object> toolArgs = GatewaySupport.argsOf(args.get(ARG_TOOL_ARGS));
        if (toolArgs == null) {
            return ToolResult.error(McpGatewayI18n.get(locale, McpGatewayI18nKeys.GATEWAY_MCP_CALL_ARGS_INVALID)
                    + "\n\n" + schemaHint(locale, target.server, tool));
        }

        try {
            ToolResult result = GatewaySupport.callWithRetry(gateway.maxRetries(), gateway.retryDelayMs(),
                    () -> tool.call(toolArgs));
            if (result != null && result.isError()) {
                // 工具报错（多半是参数不对）：附上完整参数定义，模型对照一次就能改对，不必再查详情
                return withSchemaHint(result, schemaHint(locale, target.server, tool));
            }
            return result;
        } catch (Throwable e) {
            LOG.error("McpTool gateway execution failed: {}/{}", target.server, toolName, e);
            String text = McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_CALL_ERROR,
                    "error", GatewaySupport.errorOf(e));
            if (isInvalidParams(e)) {
                // 只在服务端明确报「参数非法」时附定义；超时、断连等与参数无关，不加干扰信息
                text = text + "\n\n" + schemaHint(locale, target.server, tool);
            }
            return ToolResult.error(text);
        }
    }

    /** 异常链里是否有 JSON-RPC「参数非法」（-32602） */
    private static boolean isInvalidParams(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth < 10; t = t.getCause(), depth++) {
            if (t instanceof McpError) {
                McpSchema.JSONRPCResponse.JSONRPCError error = ((McpError) t).getJsonRpcError();
                return error != null && error.code() == McpSchema.ErrorCodes.INVALID_PARAMS;
            }
        }
        return false;
    }

    /** 参数定义提示：说明 + 完整 inputSchema */
    private static String schemaHint(Locale locale, String server, FunctionTool tool) {
        return McpGatewayI18n.fmt(locale, McpGatewayI18nKeys.GATEWAY_MCP_CALL_SCHEMA_HINT, "server", server, "name", tool.name())
                + "\n```json\n" + tool.inputSchema() + "\n```";
    }

    /**
     * 在报错结果后附上提示。重建结果而不是 addText：部分适配只取第一个文本块（getContent），
     * 追加的块会被丢掉。文本块合并成一段，媒体块、报错标记与 metas 原样保留。
     */
    private static ToolResult withSchemaHint(ToolResult result, String hint) {
        StringBuilder text = new StringBuilder();
        List<ContentBlock> others = new ArrayList<>();
        for (ContentBlock block : result.getBlocks()) {
            if (block instanceof TextBlock) {
                if (text.length() > 0) {
                    text.append("\n");
                }
                text.append(block.getContent());
            } else {
                others.add(block);
            }
        }

        if (text.length() > 0) {
            text.append("\n\n");
        }
        text.append(hint);

        ToolResult out = new ToolResult(text.toString());
        out.addBlocks(others);
        out.setError(true);
        out.metas().putAll(result.metas());
        return out;
    }
}
