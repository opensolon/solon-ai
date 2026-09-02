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
package org.noear.solon.ai.llm.dialect.gemini.interactions;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallJsonSanitizer;
import org.noear.solon.ai.llm.dialect.gemini.interactions.model.InteractionStepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Gemini Interactions API 响应解析器
 * <p>
 * 负责解析 Interactions API 返回的流式和非流式响应。
 * Interactions API 使用 steps[] 数组替代 Generate Content API 的 candidates[]。
 * 流式模式使用 SSE 事件序列（step.start / step.delta / step.stop）。
 *
 * @since 3.1
 */
public class GeminiInteractionsResponseParser {
    private static final Logger log = LoggerFactory.getLogger(GeminiInteractionsResponseParser.class);

    /**
     * 流式步骤累积器的上下文键
     *
     * <p>解析器被静态单例方言持有，所以跨帧状态必须挂在 ctx 上（每次流订阅一个实例）：
     * step index 是交互内的局部序号（0,1,2…），放实例字段会让所有并发请求共用同一张表——
     * A 请求的 arguments_delta 会拼到 B 请求的工具调用上，A 的 interaction.created
     * 还会清掉 B 正在累积的步骤。</p>
     *
     * @since 4.1
     */
    private static final String ATTR_STEP_ACCUMULATORS = "gemini.interactions.stepAccumulators";

    private final boolean logEnabled;

    public GeminiInteractionsResponseParser() {
        this.logEnabled = log.isDebugEnabled();
    }

    /**
     * 取当前流的步骤累积器表（按 step index 分组）
     *
     * @since 4.1
     */
    private Map<Integer, StepAccumulator> stepAccumulators(ChatStreamContext ctx) {
        return ctx.attrIfAbsent(ATTR_STEP_ACCUMULATORS, k -> new LinkedHashMap<>());
    }

    /**
     * 解析响应 JSON
     *
     * @param ctx  流上下文
     * @param json 响应 JSON 字符串
     * @return 是否有有效的选择
     */
    public boolean parseResponse(ChatStreamContext ctx, String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }

        if (logEnabled) {
            log.debug("Interactions raw response: {}", json);
        }

        if (ctx.getAccumulator().isStream()) {
            return parseStreamResponse(ctx, json);
        } else {
            return parseNonStreamResponse(ctx, json);
        }
    }

    // ==================== 非流式解析 ====================

    /**
     * 解析非流式响应
     * <p>
     * Interactions API 非流式响应格式：
     * <pre>{@code
     * {
     *   "id": "v1_...",
     *   "model": "gemini-3.5-flash",
     *   "status": "completed",
     *   "steps": [
     *     {"type": "thought", "summary": [...], "signature": "..."},
     *     {"type": "model_output", "content": [{"type":"text","text":"Answer"}]},
     *     {"type": "function_call", "name":"...", "arguments":{...}, "id":"..."}
     *   ],
     *   "usage": {"total_input_tokens":7, "total_output_tokens":20, "total_tokens":49}
     * }
     * }</pre>
     *
     * <p>非流式同样要给出扩展语义事件：错误、思考签名、Google 搜索等服务端工具步骤——
     * 这些语义并非流式独有。</p>
     *
     * @since 4.1
     */
    public boolean parseNonStreamResponse(ChatStreamContext ctx, String json) {
        ChatAccumulator acc = ctx.getAccumulator();

        ONode oResp;
        try {
            oResp = ONode.ofJson(json);
        } catch (Exception e) {
            log.warn("Failed to parse Interactions response JSON", e);
            return false;
        }

        if (!oResp.isObject()) {
            return false;
        }

        // 错误处理
        if (oResp.hasKey("error")) {
            ONode oError = oResp.get("error");
            String errorMsg = oError.get("message").getString();
            if (Utils.isEmpty(errorMsg)) {
                errorMsg = oError.toJson();
            }
            acc.setError(new ChatException(errorMsg));
            ctx.emit(ctx.event(ChatEventType.ERROR)
                    .rawType("error")
                    .error(acc.getError())
                    .raw(oResp)
                    .build());
            return true;
        }

        // model
        if (oResp.hasKey("model")) {
            acc.setModel(oResp.get("model").getString());
        }

        // status → finishReason
        String status = oResp.get("status").getString();
        String finishReason = mapStatusToFinishReason(status);

        // steps[]: 解析各个 step
        List<AssistantMessage> messages = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        String thinkingSignature = null;

        ONode oSteps = oResp.getOrNull("steps");
        if (oSteps != null && oSteps.isArray()) {
            for (ONode oStep : oSteps.getArray()) {
                //未建模的步骤类型解析为 null：与旧实现落到 default 分支等价，静默跳过
                InteractionStepType stepType = InteractionStepType.fromApiValue(oStep.get("type").getString());
                if (stepType == null) continue;

                switch (stepType) {
                    case THOUGHT:
                        String thoughtText = extractThoughtSummary(oStep);
                        if (Utils.isNotEmpty(thoughtText)) {
                            String signature = oStep.get("signature").getString();
                            if (Utils.isNotEmpty(signature)) {
                                thinkingSignature = signature;
                                // 与流式 thought_signature delta 对称：给出专用事件通道
                                ctx.emit(ctx.event(ChatEventType.THINKING_SIGNATURE)
                                        .rawType("thought")
                                        .text(signature)
                                        .raw(oStep)
                                        .build());
                            }
                            messages.add(new AssistantMessage("",thoughtText, true));
                        }
                        break;

                    case MODEL_OUTPUT:
                        AssistantMessage modelMsg = extractModelOutputMessage(oStep);
                        if (modelMsg != null) {
                            if (modelMsg.hasMedia()) {
                                acc.addMediaBlocks(modelMsg.getBlocks());
                            }
                            messages.add(modelMsg);
                        }
                        break;

                    case FUNCTION_CALL:
                        ToolCall toolCall = parseFunctionCallStep(oStep);
                        if (toolCall != null) {
                            // 第一个 function_call 可能携带 thought_signature
                            if (toolCalls.isEmpty() && oStep.hasKey("thought_signature")) {
                                String sig = oStep.get("thought_signature").getString();
                                if (Utils.isNotEmpty(sig)) {
                                    toolCall.setThoughtSignature(sig);
                                    thinkingSignature = sig;
                                }
                            }
                            toolCalls.add(toolCall);
                        }
                        break;

                    case GOOGLE_SEARCH_CALL:
                    case GOOGLE_SEARCH_RESULT:
                        // Google 搜索等服务端工具步骤：旧实现在非流式下整步丢弃，与流式的
                        // SERVER_TOOL_* 对称地补上
                        ctx.emit(ctx.event(ChatEventType.SERVER_TOOL_RESULT)
                                .rawType("step")
                                .subType(stepType.getApiValue())
                                .itemId(oStep.get("id").getString())
                                .raw(oStep)
                                .build());
                        break;

                    default:
                        break;
                }
            }
        }

        // 保存 thinkingSignature
        if (Utils.isNotEmpty(thinkingSignature)) {
            acc.thinkingSignature = thinkingSignature;
        }

        // 发出消息
        boolean hasContent = false;

        // 先发出 thought 消息
        for (AssistantMessage thoughtMsg : messages) {
            acc.addContentItem(thoughtMsg);
            hasContent = true;
        }

        // 如果有 tool calls，发出一个空文本的 tool_calls 消息
        if (!toolCalls.isEmpty()) {
            // 结束 thinking 状态（如果有）
            if (acc.in_thinking) {
                acc.addContentItem(new AssistantMessage("","", true));
                acc.in_thinking = false;
                hasContent = true;
            }
            AssistantMessage toolCallMsg = new AssistantMessage("", "", false, null, null, toolCalls, null);
            acc.addContentItem(toolCallMsg);
            hasContent = true;
        }

        // finishReason
        if (Utils.isNotEmpty(finishReason)) {
            acc.setFinished(true);
            acc.lastFinishReason = finishReason;
        }

        // 兜底：如果没有内容项但 response 存在（空响应补一个）
        if (!hasContent && Utils.isNotEmpty(finishReason)) {
            acc.addContentItem(new AssistantMessage(""));
            hasContent = true;
        }

        // usage
        ONode oUsage = oResp.getOrNull("usage");
        if (oUsage != null && acc.isFinished()) {
            parseUsage(acc, oUsage);
        }

        return hasContent;
    }

    // ==================== 流式解析 ====================

    /**
     * 解析流式响应（SSE 事件数据）
     * <p>
     * 每个 SSE 事件的 data 行包含一个 JSON 对象，event_type 字段标识事件类型。
     * 支持的 event_type:
     * <ul>
     *   <li>interaction.created — 交互创建，包含 interaction id</li>
     *   <li>step.start — 步骤开始，包含 step index 和 type</li>
     *   <li>step.delta — 步骤增量，包含 delta 内容</li>
     *   <li>step.stop — 步骤结束</li>
     *   <li>interaction.completed — 交互完成，包含 usage</li>
     * </ul>
     *
     * <p>内容主干仍以内容项表达，由核心统一转事件与边界；本方法额外发射
     * 生命周期（interaction.created / completed）、步骤边界与 Google 搜索等服务端工具事件。</p>
     *
     * @since 4.1
     */
    public boolean parseStreamResponse(ChatStreamContext ctx, String json) {
        ChatAccumulator acc = ctx.getAccumulator();

        ONode oData;
        try {
            oData = ONode.ofJson(json);
        } catch (Exception e) {
            log.warn("Failed to parse Interactions SSE data", e);
            return false;
        }

        if (!oData.isObject()) {
            return false;
        }

        // 错误处理
        if (oData.hasKey("error")) {
            ONode oError = oData.get("error");
            String errorMsg = oError.get("message").getString();
            if (Utils.isEmpty(errorMsg)) {
                errorMsg = oError.toJson();
            }
            acc.setError(new ChatException(errorMsg));
            return true;
        }

        String eventType = oData.get("event_type").getString();
        if (eventType == null) {
            return false;
        }

        boolean hasContent = false;

        switch (eventType) {
            case "interaction.created":
                handleInteractionCreated(ctx, oData);
                // 供应商响应标识（交互 id）：记录一次，本步后续事件自动预填
                ctx.setProviderResponseId(interactionIdOf(oData));
                // 旧实现下该帧只用于设置 model，订阅方无从感知交互已建立
                ctx.emit(ctx.event(ChatEventType.STATUS)
                        .rawType(eventType)
                        .itemId(interactionIdOf(oData))
                        .raw(oData)
                        .build());
                break;

            case "step.start":
                hasContent = handleStepStart(ctx, oData);
                emitStepEvent(ctx, eventType, oData);
                break;

            case "step.delta":
                hasContent = handleStepDelta(ctx, oData);
                emitStepEvent(ctx, eventType, oData);
                emitStepDeltaEvent(ctx, oData);
                break;

            case "step.stop":
                hasContent = handleStepStop(ctx, oData);
                emitStepEvent(ctx, eventType, oData);
                break;

            case "interaction.completed":
                handleInteractionCompleted(acc, oData);
                ctx.emit(ctx.event(ChatEventType.STATUS)
                        .rawType(eventType)
                        .itemId(interactionIdOf(oData))
                        .usage(acc.getUsage())
                        .raw(oData)
                        .build());
                break;

            default:
                // 未建模事件：旧实现静默丢弃，现在以 RAW 透出
                ctx.emit(ctx.event(ChatEventType.RAW)
                        .rawType(eventType)
                        .raw(oData)
                        .build());
                break;
        }

        return hasContent;
    }

    /**
     * 取交互 id
     *
     * @since 4.1
     */
    private static String interactionIdOf(ONode oData) {
        ONode interaction = oData.getOrNull("interaction");
        return interaction == null ? null : interaction.get("id").getString();
    }

    /**
     * 发射步骤事件
     *
     * <p>Google 搜索等服务端工具步骤在旧实现下只能落成文本或消失，此处给出显式事件；
     * 内容型步骤（text / thought / function_call）仍由核心从内容项转换，不重复发射。</p>
     *
     * <p>阶段按原始 event_type 三态映射，而不是「是否 start」的二态：二态会把 step.stop
     * 也当成 delta 发出，服务端工具就永远等不到配对的结束事件，订阅方状态机只能一直停在
     * 「进行中」。</p>
     *
     * @since 4.1
     */
    private void emitStepEvent(ChatStreamContext ctx, String eventType, ONode oData) {
        ONode step = oData.getOrNull("step");
        if (step == null) {
            return;
        }

        InteractionStepType stepType = InteractionStepType.fromApiValue(step.get("type").getString());
        if (stepType != InteractionStepType.GOOGLE_SEARCH_CALL
                && stepType != InteractionStepType.GOOGLE_SEARCH_RESULT) {
            return;
        }

        ChatEventType phase = serverToolPhaseOf(eventType);
        if (phase == null) {
            return;
        }

        ctx.emit(ctx.event(phase)
                .rawType(eventType)
                .subType(stepType.getApiValue())
                .itemId(step.get("id").getString())
                .index(oData.get("index").getInt())
                .raw(oData)
                .build());
    }

    /**
     * 服务端工具步骤的事件阶段映射（开始 / 参数增量 / 结束）
     *
     * @since 4.1
     */
    private static ChatEventType serverToolPhaseOf(String eventType) {
        switch (eventType) {
            case "step.start":
                return ChatEventType.SERVER_TOOL_START;
            case "step.delta":
                return ChatEventType.SERVER_TOOL_ARGS_DELTA;
            case "step.stop":
                return ChatEventType.SERVER_TOOL_RESULT;
            default:
                return null;
        }
    }

    /**
     * 处理 interaction.created 事件
     */
    private void handleInteractionCreated(ChatStreamContext ctx, ONode oData) {
        ChatAccumulator acc = ctx.getAccumulator();

        // 新交互开始，清掉上一交互的残留步骤状态；作用域限于当前流，不会波及并发请求
        stepAccumulators(ctx).clear();
        
        ONode interaction = oData.getOrNull("interaction");
        if (interaction != null) {
            if (interaction.hasKey("model")) {
                acc.setModel(interaction.get("model").getString());
            }
        }
    }

    /**
     * 处理 step.start 事件
     * <p>
     * 创建一个新的 StepAccumulator，准备接收 delta 数据。
     * 如果是 thought 类型，开始 thinking 标记。
     */
    private boolean handleStepStart(ChatStreamContext ctx, ONode oData) {
        ChatAccumulator acc = ctx.getAccumulator();

        int index = oData.get("index").getInt();
        ONode step = oData.getOrNull("step");
        if (step == null) return false;

        String stepTypeValue = step.get("type").getString();
        if (stepTypeValue == null) return false;

        // 未建模的类型解析为 null，仍照旧行为登记累积器（只是后绥不会命中任何类型分支）
        StepAccumulator stepAcc = new StepAccumulator(InteractionStepType.fromApiValue(stepTypeValue));
        stepAccumulators(ctx).put(index, stepAcc);

        // 如果是 thought 类型且尚未进入 thinking 状态，发出开始标记
        if (InteractionStepType.THOUGHT == stepAcc.stepType && !acc.in_thinking) {
            acc.in_thinking = true;
            acc.addContentItem(new AssistantMessage("", "", true));
            return true;
        }

        // 如果是 function_call 类型，保存函数名和 id
        // Interactions API 在 function_call step 中使用 "id" 字段（非 "call_id"）
        if (InteractionStepType.FUNCTION_CALL == stepAcc.stepType) {
            if (step.hasKey("name")) {
                stepAcc.functionName = step.get("name").getString();
            }
            if (step.hasKey("id")) {
                stepAcc.callId = step.get("id").getString();
            }
        }

        return false;
    }

    /**
     * 处理 step.delta 事件
     * <p>
     * 根据 delta 类型处理不同的内容：
     * <ul>
     *   <li>text — 文本增量（model_output content 的一部分）</li>
     *   <li>thought_signature — 思考签名</li>
     *   <li>thought_summary — 思考摘要文本</li>
     *   <li>arguments_delta — 工具调用参数增量</li>
     * </ul>
     */
    private boolean handleStepDelta(ChatStreamContext ctx, ONode oData) {
        ChatAccumulator acc = ctx.getAccumulator();

        int index = oData.get("index").getInt();
        ONode delta = oData.getOrNull("delta");
        if (delta == null) return false;

        StepAccumulator stepAcc = stepAccumulators(ctx).get(index);
        String deltaType = delta.get("type").getString();

        // 思考签名是累积器级状态（要跨轮回传），不应因为本流未登记该步骤而整个丢弃
        if ("thought_signature".equals(deltaType)) {
            String signature = delta.get("signature").getString();
            if (Utils.isNotEmpty(signature)) {
                if (stepAcc != null) {
                    stepAcc.signature = signature;
                }
                acc.thinkingSignature = signature;
            }
            return false;
        }

        if (stepAcc == null) return false;

        if ("text".equals(deltaType)) {
            String text = delta.get("text").getString();
            if (Utils.isNotEmpty(text)) {
                // model_output 的 text delta 直接发出
                if (InteractionStepType.MODEL_OUTPUT == stepAcc.stepType) {
                    if (acc.in_thinking) {
                        acc.addContentItem(new AssistantMessage("", "", true));
                        acc.in_thinking = false;
                    }
                    acc.addContentItem(new AssistantMessage(text, "", false));
                    return true;
                }
            }
        } else if ("thought_summary".equals(deltaType)) {
            // 提取摘要文本
            ONode summary = delta.getOrNull("summary");
            if (summary != null && summary.isArray()) {
                String summaryText = extractContentArrayText(summary);
                if (Utils.isNotEmpty(summaryText)) {
                    // thought 的 summary 增量作为 thinking 内容发出
                    if (!acc.in_thinking) {
                        acc.addContentItem(new AssistantMessage("", "", true));
                        acc.in_thinking = true;
                    }
                    acc.addContentItem(new AssistantMessage("",summaryText, true));
                    return true;
                }
            }
        } else if ("arguments_delta".equals(deltaType)) {
            String argsDelta = delta.get("arguments").getString();
            if (Utils.isNotEmpty(argsDelta)) {
                stepAcc.argumentsBuilder.append(argsDelta);
            }
        }

        return false;
    }

    private void emitStepDeltaEvent(ChatStreamContext ctx, ONode data) {
        ONode delta = data.getOrNull("delta");
        if (delta == null) {
            return;
        }

        String deltaType = delta.get("type").getString();
        if ("thought_signature".equals(deltaType)) {
            String signature = delta.get("signature").getString();
            if (Utils.isNotEmpty(signature)) {
                ctx.emit(ctx.event(org.noear.solon.ai.chat.event.ChatEventType.THINKING_SIGNATURE)
                        .rawType("step.delta")
                        .text(signature)
                        .raw(data)
                        .build());
            }
        }
    }
    /**
     * 处理 step.stop 事件
     * <p>
     * 完成步骤累积，发出最终消息（如有必要）。
     * function_call 步骤在此处完成并发出 ToolCall。
     */
    private boolean handleStepStop(ChatStreamContext ctx, ONode oData) {
        ChatAccumulator acc = ctx.getAccumulator();

        int index = oData.get("index").getInt();
        StepAccumulator stepAcc = stepAccumulators(ctx).remove(index);
        if (stepAcc == null) return false;

        // function_call 步骤完成
        if (InteractionStepType.FUNCTION_CALL == stepAcc.stepType) {
            // 结束 thinking 状态
            if (acc.in_thinking) {
                acc.addContentItem(new AssistantMessage("", "", true));
                acc.in_thinking = false;
            }

            // 从 step.start 中保存的 call info 构建 ToolCall
            // call info 应在之前的 step.start 或关联数据中
            ToolCall toolCall = buildToolCallFromAccumulator(stepAcc);
            if (toolCall != null) {
                acc.addContentItem(new AssistantMessage("", "", false, null, null,
                                Collections.singletonList(toolCall), null));
                return true;
            }
        }

        return false;
    }

    /**
     * 处理 interaction.completed 事件
     */
    private void handleInteractionCompleted(ChatAccumulator acc, ONode oData) {
        ONode interaction = oData.getOrNull("interaction");
        if (interaction != null) {
            String status = interaction.get("status").getString();
            if ("completed".equals(status)) {
                acc.setFinished(true);
                acc.lastFinishReason = "stop";
            } else if ("requires_action".equals(status)) {
                acc.setFinished(true);
                acc.lastFinishReason = "tool_calls";
            } else if ("failed".equals(status)) {
                ONode oError = interaction.getOrNull("error");
                if (oError != null) {
                    String errorMsg = oError.get("message").getString();
                    if (Utils.isNotEmpty(errorMsg)) {
                        acc.setError(new ChatException(errorMsg));
                    }
                }
                acc.setFinished(true);
                acc.lastFinishReason = "error";
            }

            // usage
            ONode oUsage = interaction.getOrNull("usage");
            if (oUsage != null) {
                parseUsage(acc, oUsage);
            }
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 从 function_call step 中解析 ToolCall
     */
    private ToolCall parseFunctionCallStep(ONode oStep) {
        String name = oStep.get("name").getString();
        // Interactions API 在 function_call step 中使用 "id" 字段（非 "call_id"）
        String callId = oStep.get("id").getString();
        if (name == null) return null;

        if (Utils.isEmpty(callId)) {
            callId = name + "_" + System.currentTimeMillis();
        }

        ONode argsNode = oStep.getOrNull("arguments");
        // 解析出口净化：仅 object 采纳；字符串可能内含截断 JSON，统一归一为合法 JSON object
        String argsStr;
        Map<String, Object> argsMap = null;
        if (argsNode != null && argsNode.isObject()) {
            argsStr = argsNode.toJson();
            argsMap = argsNode.toBean(Map.class);
        } else {
            argsStr = ToolCallJsonSanitizer.sanitizeArguments(
                    argsNode == null ? null : argsNode.getString(), name);
            if (!"{}".equals(argsStr)) {
                try {
                    argsMap = ONode.ofJson(argsStr).toBean(Map.class);
                } catch (Exception ignored) {
                    argsMap = null;
                }
            }
        }

        return new ToolCall(callId, callId, name, argsStr, argsMap);
    }

    /**
     * 从 StepAccumulator 构建 ToolCall
     */
    private ToolCall buildToolCallFromAccumulator(StepAccumulator acc) {
        if (Utils.isEmpty(acc.functionName)) return null;

        String callId = acc.callId;
        if (Utils.isEmpty(callId)) {
            callId = acc.functionName + "_" + System.currentTimeMillis();
        }

        // 流式解析出口净化：截断损坏的 arguments 禁止入历史（会毒化会话）
        String argsStr = ToolCallJsonSanitizer.sanitizeArguments(
                acc.argumentsBuilder.length() > 0 ? acc.argumentsBuilder.toString() : null,
                acc.functionName);

        Map<String, Object> argsMap = null;
        try {
            ONode argsNode = ONode.ofJson(argsStr);
            if (argsNode.isObject()) {
                argsMap = argsNode.toBean(Map.class);
            }
        } catch (Exception e) {
            // ignore parse error
        }

        ToolCall toolCall = new ToolCall(callId, callId, acc.functionName, argsStr, argsMap);
        if (Utils.isNotEmpty(acc.signature)) {
            toolCall.setThoughtSignature(acc.signature);
        }

        return toolCall;
    }

    /**
     * 提取 thought step 的摘要文本
     */
    private String extractThoughtSummary(ONode oStep) {
        ONode summary = oStep.getOrNull("summary");
        if (summary == null) {
            return null;
        }
        return extractContentArrayText(summary);
    }

    /**
     * 提取 model_output 为 AssistantMessage（含多模态 blocks）。
     *
     * @since 3.9
     */
    private AssistantMessage extractModelOutputMessage(ONode oStep) {
        ONode content = oStep.getOrNull("content");
        if (content == null) {
            return null;
        }
                    
        List<ContentBlock> blocks = extractContentBlocks(content);
        if (blocks.isEmpty()) {
            return null;
        }

        StringBuilder text = new StringBuilder();
        List<ContentBlock> media = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock) {
//                if (text.length() > 0) {
//                    text.append("\n");
//                }
                text.append(block.getContent());
            } else {
                media.add(block);
            }
        }
        
        if (media.isEmpty()) {
            return new AssistantMessage(text.toString(), "", false);
        }
    
        List<ContentBlock> blocksForMsg = new ArrayList<>();
        if (text.length() > 0) {
            blocksForMsg.add(TextBlock.of(text.toString()));
        }
        blocksForMsg.addAll(media);
        return new AssistantMessage(text.toString(), "",false, null, null, null, null, blocksForMsg);
    }
    
    /**
     * 从 Content[] 数组中提取文本
     * <p>
     * Content 数组结构：[{"type": "text", "text": "..."}, ...]
     */
    private String extractContentArrayText(ONode contentArr) {
        List<ContentBlock> blocks = extractContentBlocks(contentArr);
        if (blocks.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock) {
                String text = block.getContent();
                if (Utils.isNotEmpty(text)) {
//                    if (sb.length() > 0) {
//                        sb.append("\n");
//                    }
                    sb.append(text);
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
    
    /**
     * 从 Content[] 提取完整 blocks（text + media）。
     *
     * @since 3.9
     */
    private List<ContentBlock> extractContentBlocks(ONode contentArr) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (contentArr == null) {
            return blocks;
        }
    
        if (contentArr.isObject()) {
            ContentBlock block = parseInteractionContentItem(contentArr);
            if (block != null) {
                blocks.add(block);
            }
            return blocks;
        }
    
        if (!contentArr.isArray()) {
            return blocks;
        }
    
        for (ONode item : contentArr.getArray()) {
            ContentBlock block = parseInteractionContentItem(item);
            if (block != null) {
                blocks.add(block);
            }
        }
        return blocks;
    }
    
    private ContentBlock parseInteractionContentItem(ONode item) {
        if (item == null || !item.isObject()) {
            return null;
        }
    
        String type = item.get("type").getString();
        if ("text".equals(type) || item.hasKey("text")) {
            String text = item.get("text").getString();
            return Utils.isEmpty(text) ? null : TextBlock.of(text);
        }
    
        if ("inline_data".equals(type) || item.hasKey("data") || item.hasKey("inline_data") || item.hasKey("inlineData")) {
            String mime = item.get("mime_type").getString();
            if (Utils.isEmpty(mime)) {
                mime = item.get("mimeType").getString();
            }
            String data = item.get("data").getString();
            if (Utils.isEmpty(data) && item.hasKey("inline_data")) {
                ONode inline = item.get("inline_data");
                if (inline.isObject()) {
                    data = inline.get("data").getString();
                    if (Utils.isEmpty(mime)) {
                        mime = inline.get("mime_type").getString();
                    }
                }
            }
            return createMediaByMime(mime, null, data);
        }
    
        if ("file_data".equals(type) || item.hasKey("file_uri") || item.hasKey("fileUri") || item.hasKey("file_data") || item.hasKey("fileData")) {
            String mime = item.get("mime_type").getString();
            if (Utils.isEmpty(mime)) {
                mime = item.get("mimeType").getString();
            }
            String uri = item.get("file_uri").getString();
            if (Utils.isEmpty(uri)) {
                uri = item.get("fileUri").getString();
            }
            if (Utils.isEmpty(uri) && item.hasKey("file_data")) {
                ONode fileData = item.get("file_data");
                if (fileData.isObject()) {
                    uri = fileData.get("file_uri").getString();
                    if (Utils.isEmpty(uri)) {
                        uri = fileData.get("fileUri").getString();
                    }
                    if (Utils.isEmpty(mime)) {
                        mime = fileData.get("mime_type").getString();
                    }
                }
            }
            return createMediaByMime(mime, uri, null);
        }
    
        return null;
    }
    
    private ContentBlock createMediaByMime(String mime, String url, String data) {
        boolean hasData = Utils.isNotEmpty(data);
        boolean hasUrl = Utils.isNotEmpty(url);
        if (!hasData && !hasUrl) {
            return null;
        }
    
        String mediaType = "image";
        if (Utils.isNotEmpty(mime)) {
            String lower = mime.toLowerCase();
            if (lower.startsWith("audio/")) {
                mediaType = "audio";
            } else if (lower.startsWith("video/")) {
                mediaType = "video";
            }
        }
    
        if ("audio".equals(mediaType)) {
            if (hasData) {
                return Utils.isEmpty(mime) ? AudioBlock.ofBase64(data) : AudioBlock.ofBase64(data, mime);
            }
            return Utils.isEmpty(mime) ? AudioBlock.ofUrl(url) : AudioBlock.ofUrl(url, mime);
        }
        if ("video".equals(mediaType)) {
            if (hasData) {
                return Utils.isEmpty(mime) ? VideoBlock.ofBase64(data) : VideoBlock.ofBase64(data, mime);
            }
            return Utils.isEmpty(mime) ? VideoBlock.ofUrl(url) : VideoBlock.ofUrl(url, mime);
        }
    
        if (hasData) {
            return Utils.isEmpty(mime) ? ImageBlock.ofBase64(data) : ImageBlock.ofBase64(data, mime);
        }
        return Utils.isEmpty(mime) ? ImageBlock.ofUrl(url) : ImageBlock.ofUrl(url, mime);
    }

    /**
     * 将 Interactions API 的 status 映射为 finishReason
     */
    private String mapStatusToFinishReason(String status) {
        if (status == null) return null;
        switch (status) {
            case "completed":
                return "stop";
            case "requires_action":
                return "tool_calls";
            case "failed":
                return "error";
            default:
                return status;
        }
    }

    /**
     * 解析 usage 信息
     * <p>
     * Interactions API 的 usage 格式：
     * <pre>{@code
     * {
     *   "total_input_tokens": 7,
     *   "total_output_tokens": 20,
     *   "total_thought_tokens": 22,
     *   "total_tokens": 49
     * }
     * }</pre>
     */
    private void parseUsage(ChatAccumulator acc, ONode oUsage) {
        long promptTokens = oUsage.get("total_input_tokens").getLong();
        long completionTokens = oUsage.get("total_output_tokens").getLong();
        long totalTokens = oUsage.get("total_tokens").getLong();

        acc.setUsage(new AiUsage(promptTokens, 0L, completionTokens, totalTokens, oUsage));
    }

    /**
     * 步骤累积器
     * <p>
     * 用于在流式模式下累积每个 step 的增量数据。
     */
    private static class StepAccumulator {
        // 未建模的步骤类型为 null：后绥按类型分叉时自然不命中，与旧字符串比较等价
        final InteractionStepType stepType;
        final StringBuilder argumentsBuilder = new StringBuilder();
        String functionName;
        String callId;
        String signature;

        StepAccumulator(InteractionStepType stepType) {
            this.stepType = stepType;
        }
    }
}
