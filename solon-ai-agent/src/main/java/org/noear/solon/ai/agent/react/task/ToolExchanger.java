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
package org.noear.solon.ai.agent.react.task;

import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.core.util.Assert;

import java.util.Map;

/**
 * Action 工具执行交换器
 * <p>承载工具参数与完整 {@link ToolResult}（文本 / media / isError / metas），
 * 避免 returnDirect 与 observation 路径把富结果压成纯字符串。</p>
 *
 * @author noear
 * @since 3.11.0
 */
public class ToolExchanger {
    private final String callId;
    private final String toolName;
    private final Map<String, Object> args;
    private boolean returnDirect;

    /** 完整工具结果；拦截器改写文本时通过 {@link #setResult(String)} 更新 */
    private ToolResult toolResult;

    public ToolExchanger(String callId, String toolName, Map<String, Object> args) {
        this.callId = callId;
        this.toolName = toolName;
        this.args = args;
    }

    public String getCallId() {
        return callId;
    }

    public String getToolName() {
        return toolName;
    }

    public Map<String, Object> getArgs() {
        return args;
    }

    /**
     * 是否可直接返回给调用者（对齐 FunctionTool.returnDirect，仅真实成功后标记）
     */
    public boolean isReturnDirect() {
        return returnDirect;
    }

    public void setReturnDirect(boolean returnDirect) {
        this.returnDirect = returnDirect;
    }

    /**
     * 文本投影（兼容 HITL / 拦截器既有 API）
     */
    public String getResult() {
        return toolResult == null ? null : toolResult.getContent();
    }

    /**
     * 完整工具结果（含 blocks / isError / metas）
     */
    public ToolResult getToolResult() {
        return toolResult;
    }

    /**
     * 写入完整工具结果（执行成功 / 失败 / HITL 预填后统一入口）
     */
    public void setToolResult(ToolResult toolResult) {
        this.toolResult = toolResult;
    }

    /**
     * 按文本写入或改写结果。
     * <p>HITL reject/skip 预填、批准 Note 等场景使用。若已有富结果，仅替换文本投影，
     * 保留非文本 media blocks 与 isError / metas，避免 Note 注入把图冲掉。</p>
     */
    public void setResult(String result) {
        if (toolResult == null) {
            this.toolResult = ToolResult.success(result);
            return;
        }

        // 保留 media / error / metas，仅更新文本
        ToolResult rebuilt = new ToolResult();
        rebuilt.setError(toolResult.isError());
        if (Assert.isNotEmpty(toolResult.metas())) {
            rebuilt.metas().putAll(toolResult.metas());
        }

        if (result != null) {
            rebuilt.addText(result);
        }
        for (ContentBlock block : toolResult.getBlocks()) {
            if (!(block instanceof TextBlock)) {
                rebuilt.addBlock(block);
            }
        }
        this.toolResult = rebuilt;
    }
}
