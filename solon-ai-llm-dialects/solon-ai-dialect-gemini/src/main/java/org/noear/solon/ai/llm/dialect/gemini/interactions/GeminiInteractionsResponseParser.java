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
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.source.SearchResult;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallJsonSanitizer;
import org.noear.solon.ai.llm.dialect.gemini.GeminiMessageStateSupport;
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
    private static final String ATTR_SIGNATURE_BOUND = "gemini.interactions.signatureBound";

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

        // 顶层 id 是非流式响应的供应商响应标识；事件上下文会自动预填到后续事件。
        ctx.setProviderResponseId(responseIdOf(oResp));

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
        String firstToolCallSignature = null;

        ONode oSteps = oResp.getOrNull("steps");
        if (oSteps != null && oSteps.isArray()) {
            for (ONode oStep : oSteps.getArray()) {
                InteractionStepType stepType = InteractionStepType.fromApiValue(oStep.get("type").getString());
                if (stepType == null) {
                    ctx.emit(ctx.event(ChatEventType.RAW).rawType("step")
                            .subType(oStep.get("type").getString()).raw(oStep).build());
                    continue;
                }

                switch (stepType) {
                    case THOUGHT:
                        String signature = oStep.get("signature").getString();
                        if (Utils.isNotEmpty(signature)) {
                            firstToolCallSignature = signature;
                            acc.thinkingSignature = signature;
                            ctx.emit(ctx.event(ChatEventType.THINKING_SIGNATURE)
                                    .rawType("thought").text(signature).raw(oStep).build());
                        }
                        String thoughtText = extractThoughtSummary(oStep);
                        if (Utils.isNotEmpty(thoughtText)) {
                            messages.add(new AssistantMessage("", thoughtText));
                        }
                        break;

                    case MODEL_OUTPUT:
                        AssistantMessage modelMsg = extractModelOutputMessage(oStep);
                        if (modelMsg != null) {
                            emitMediaEvents(ctx, modelMsg, messages.size());
                            messages.add(modelMsg);
                        }
                        break;

                    case FUNCTION_CALL:
                        ToolCall toolCall = parseFunctionCallStep(oStep);
                        if (toolCall != null) {
                            // 兼容早期网关把签名挂在 function_call 上的响应。
                            if (toolCalls.isEmpty() && oStep.hasKey("thought_signature")) {
                                String sig = oStep.get("thought_signature").getString();
                                if (Utils.isNotEmpty(sig)) {
                                    firstToolCallSignature = sig;
                                    acc.thinkingSignature = sig;
                                    // 与流式 thought_signature delta 对称：签名也必须进入事件通道。
                                    ctx.emit(ctx.event(ChatEventType.THINKING_SIGNATURE)
                                            .rawType("function_call")
                                            .text(sig)
                                            .raw(oStep)
                                            .build());
                                }
                            }
                            toolCalls.add(toolCall);
                        }
                        break;

                    case GOOGLE_SEARCH_CALL:
                    case CODE_EXECUTION_CALL:
                    case URL_CONTEXT_CALL:
                    case MCP_SERVER_TOOL_CALL:
                    case FILE_SEARCH_CALL:
                    case GOOGLE_MAPS_CALL:
                        emitServerToolSnapshot(ctx, ChatEventType.SERVER_TOOL_START, stepType, oStep);
                        break;
                    case GOOGLE_SEARCH_RESULT:
                        emitServerToolSnapshot(ctx, ChatEventType.SERVER_TOOL_RESULT, stepType, oStep);
                        emitGoogleSearchResults(ctx, oStep, null);
                        break;
                    case CODE_EXECUTION_RESULT:
                    case URL_CONTEXT_RESULT:
                    case MCP_SERVER_TOOL_RESULT:
                    case FILE_SEARCH_RESULT:
                    case GOOGLE_MAPS_RESULT:
                        emitServerToolSnapshot(ctx, ChatEventType.SERVER_TOOL_RESULT, stepType, oStep);
                        break;

                    default:
                        break;
                }
            }
        }


        // 合并为唯一终态 AssistantMessage；非流式内容项不再承担分片队列职责。
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        List<ContentBlock> blocks = new ArrayList<>();
        for (AssistantMessage message : messages) {
            if (Utils.isNotEmpty(message.getTextRaw())) text.append(message.getTextRaw());
            if (Utils.isNotEmpty(message.getThinkingRaw())) thinking.append(message.getThinkingRaw());
            if (Utils.isNotEmpty(message.getBlocks())) blocks.addAll(message.getBlocks());
        }

        boolean hasContent = text.length() > 0 || thinking.length() > 0
                || !toolCalls.isEmpty() || !blocks.isEmpty();
        if (hasContent || Utils.isNotEmpty(finishReason)) {
            AssistantMessage terminal = new AssistantMessage(text.toString(), thinking.toString(),
                    toolCalls.isEmpty() ? null : toolCalls,
                    blocks.isEmpty() ? null : blocks);
            acc.setTerminalMessage(terminal);
            MessageProtocolState signatureState = GeminiMessageStateSupport.createSignatureState(
                    toolCalls.isEmpty() ? null : toolCalls.get(0), 0, firstToolCallSignature);
            if (signatureState != null) {
                acc.putTerminalProtocolState(
                        GeminiMessageStateSupport.INTERACTIONS_PROTOCOL_ID, signatureState);
            }
            acc.in_thinking = false;
            hasContent = true;
        }

        // finishReason
        if (isTerminalStatus(status)) {
            acc.setFinished(true);
            acc.lastFinishReason = finishReason;
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
            ctx.emit(ctx.event(ChatEventType.ERROR).rawType("error")
                    .error(acc.getError()).raw(oData).build());
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
                emitStepEvent(ctx, eventType, oData);
                hasContent = handleStepStop(ctx, oData);
                break;

            case "interaction.status_update":
                handleInteractionStatus(ctx, oData);
                break;

            case "interaction.completed":
                // 供应商响应标识可能在 completed 帧再次出现；非空时更新并自动预填事件。
                ctx.setProviderResponseId(responseIdOf(oData));
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

    private static String responseIdOf(ONode oData) {
        String id = oData.get("id").getString();
        if (Utils.isNotEmpty(id)) return id;
        ONode interaction = oData.getOrNull("interaction");
        return interaction == null ? null : interaction.get("id").getString();
    }

    /**
     * 取交互 id
     */
    private static String interactionIdOf(ONode oData) {
        return responseIdOf(oData);
    }

    /**
     * 发射步骤事件
     *
     * <p>Google 搜索等服务端工具步骤在旧实现下只能落成文本或消失，此处给出显式事件；
     * 内容型步骤（text / thought / function_call）直接发射语义事件并由累积器归并。</p>
     *
     * <p>阶段按原始 event_type 三态映射，而不是「是否 start」的二态：二态会把 step.stop
     * 也当成 delta 发出，服务端工具就永远等不到配对的结束事件，订阅方状态机只能一直停在
     * 「进行中」。</p>
     *
     * @since 4.1
     */
    private void emitStepEvent(ChatStreamContext ctx, String eventType, ONode oData) {
        int index = oData.get("index").getInt();
        StepAccumulator stepAcc = stepAccumulators(ctx).get(index);
        if (stepAcc == null || !isServerToolStep(stepAcc.stepType)) return;
        ChatEventType phase = serverToolPhaseOf(eventType);
        if (phase == null) return;
        ctx.emit(ctx.event(phase)
                .rawType(eventType)
                .subType(stepAcc.stepType.getApiValue())
                .itemId(stepAcc.callId)
                .index(index)
                .raw(oData)
                .build());
    }

    private boolean isServerToolStep(InteractionStepType type) {
        return type != null && type != InteractionStepType.USER_INPUT
                && type != InteractionStepType.MODEL_OUTPUT
                && type != InteractionStepType.THOUGHT
                && type != InteractionStepType.FUNCTION_CALL
                && type != InteractionStepType.FUNCTION_RESULT;
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
        ctx.attrPut(ATTR_SIGNATURE_BOUND, false);
        
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
        if (stepAcc.stepType == null) {
            ctx.emit(ctx.event(ChatEventType.RAW).rawType("step.start")
                    .subType(stepTypeValue).index(index).raw(oData).build());
        }
        stepAcc.callId = step.get("id").getString();
        if (Utils.isEmpty(stepAcc.callId)) stepAcc.callId = step.get("call_id").getString();
        stepAccumulators(ctx).put(index, stepAcc);

        if (InteractionStepType.THOUGHT == stepAcc.stepType) {
            acc.in_thinking = true;
            String thought = extractThoughtSummary(step);
            if (Utils.isNotEmpty(thought)) {
                ctx.emit(ctx.event(ChatEventType.THINKING_DELTA)
                        .text(thought).index(index).raw(oData).build());
                return true;
            }
            return false;
        }

        if (InteractionStepType.MODEL_OUTPUT == stepAcc.stepType) {
            AssistantMessage initial = extractModelOutputMessage(step);
            if (initial != null) {
                if (Utils.isNotEmpty(initial.getTextRaw())) {
                    ctx.emit(ctx.event(ChatEventType.TEXT_DELTA)
                            .text(initial.getTextRaw()).index(index).raw(oData).build());
                }
                emitMediaEvents(ctx, initial, index);
                return true;
            }
        }

        if (InteractionStepType.FUNCTION_CALL == stepAcc.stepType) {
            mergeFunctionIdentity(stepAcc, step);
            ONode arguments = step.getOrNull("arguments");
            if (arguments != null) {
                String initialArgs = arguments.isObject() ? arguments.toJson() : arguments.getString();
                if (Utils.isNotEmpty(initialArgs)) {
                    stepAcc.argumentsBuilder.append(initialArgs);
                }
            }
            emitFunctionStartIfReady(ctx, index, stepAcc, false);
            emitPendingFunctionArguments(ctx, index, stepAcc, oData);
            return stepAcc.startEmitted;
        }

        if (InteractionStepType.GOOGLE_SEARCH_RESULT == stepAcc.stepType) {
            emitGoogleSearchResults(ctx, step, stepAcc.emittedSearchResultKeys);
        }

        return false;
    }

    /**
     * 处理 step.delta 事件
     * <p>根据 delta 类型处理文本、思考摘要、签名和工具参数增量。</p>
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
                acc.thinkingSignature = signature;
            }
            return false;
        }

        ONode step = oData.getOrNull("step");
        if (stepAcc == null) return false;
        if (InteractionStepType.GOOGLE_SEARCH_RESULT == stepAcc.stepType) {
            emitGoogleSearchResults(ctx, step, stepAcc.emittedSearchResultKeys);
            emitGoogleSearchResults(ctx, delta, stepAcc.emittedSearchResultKeys);
        }
        if (InteractionStepType.FUNCTION_CALL == stepAcc.stepType && step != null) {
            mergeFunctionIdentity(stepAcc, step);
            emitFunctionStartIfReady(ctx, index, stepAcc, false);
        }

        if ("text".equals(deltaType)) {
            String text = delta.get("text").getString();
            if (Utils.isNotEmpty(text)) {
                // model_output 的 text delta 直接发出，并由 ChatStreamContext 统一归并
                if (InteractionStepType.MODEL_OUTPUT == stepAcc.stepType) {
                    if (acc.in_thinking) {
                        acc.in_thinking = false;
                    }
                    ctx.emit(ctx.event(ChatEventType.TEXT_DELTA)
                            .text(text)
                            .index(index)
                            .raw(oData)
                            .build());
                    return true;
                }
            }
        } else if ("thought_summary".equals(deltaType)) {
            // 当前协议为单个 content；兼容早期 summary 数组。
            ONode summary = delta.getOrNull("content");
            if (summary == null) summary = delta.getOrNull("summary");
            if (summary != null) {
                String summaryText = extractContentArrayText(summary);
                if (Utils.isNotEmpty(summaryText)) {
                    // thought 的 summary 增量直接进入思考事件通道
                    if (!acc.in_thinking) {
                        acc.in_thinking = true;
                    }
                    ctx.emit(ctx.event(ChatEventType.THINKING_DELTA)
                            .text(summaryText)
                            .index(index)
                            .raw(oData)
                            .build());
                    return true;
                }
            }
        } else if ("image".equals(deltaType) || "audio".equals(deltaType) || "video".equals(deltaType)) {
            ContentBlock block = parseInteractionContentItem(delta);
            if (block != null) {
                ctx.emit(ctx.event(ChatEventType.MEDIA_DONE)
                        .itemId("step:" + index).index(index).block(block).raw(oData).build());
                return true;
            }
        } else if ("document".equals(deltaType)) {
            ctx.emit(ctx.event(ChatEventType.RAW).rawType("step.delta")
                    .subType("document").index(index).raw(oData).build());
        } else if ("arguments_delta".equals(deltaType)) {
            if (InteractionStepType.FUNCTION_CALL != stepAcc.stepType) return false;
            String argsDelta = delta.get("arguments").getString();
            if (Utils.isNotEmpty(argsDelta)) {
                stepAcc.argumentsBuilder.append(argsDelta);
                emitFunctionStartIfReady(ctx, index, stepAcc, false);
                emitPendingFunctionArguments(ctx, index, stepAcc, oData);
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
    private void mergeFunctionIdentity(StepAccumulator stepAcc, ONode step) {
        if (step == null) return;
        String name = step.get("name").getString();
        String id = step.get("id").getString();
        if (Utils.isEmpty(id)) id = step.get("call_id").getString();
        if (Utils.isNotEmpty(name)) stepAcc.functionName = name;
        if (Utils.isNotEmpty(id)) stepAcc.callId = id;
    }

    private void emitFunctionStartIfReady(ChatStreamContext ctx, int index, StepAccumulator stepAcc,
                                          boolean allowFallbackId) {
        if (stepAcc.startEmitted || Utils.isEmpty(stepAcc.functionName)) return;
        if (Utils.isEmpty(stepAcc.callId)) {
            if (!allowFallbackId) return;
            stepAcc.callId = stepAcc.functionName + "_" + index;
        }
        ToolCall startCall = new ToolCall("idx:" + index, stepAcc.callId, stepAcc.functionName, null, null);
        ctx.emit(ctx.event(ChatEventType.TOOL_CALL_START).toolCall(startCall)
                .toolCallId(stepAcc.callId).index(index).build());
        stepAcc.startEmitted = true;
    }

    private void emitPendingFunctionArguments(ChatStreamContext ctx, int index, StepAccumulator stepAcc, ONode raw) {
        if (!stepAcc.startEmitted || stepAcc.emittedArgumentsLength >= stepAcc.argumentsBuilder.length()) return;
        String delta = stepAcc.argumentsBuilder.substring(stepAcc.emittedArgumentsLength);
        stepAcc.emittedArgumentsLength = stepAcc.argumentsBuilder.length();
        ctx.emit(ctx.event(ChatEventType.TOOL_CALL_ARGS_DELTA)
                .toolCallId(stepAcc.callId).text(delta).index(index).raw(raw).build());
    }

    /**
     * 处理 step.stop 事件
     * <p>
     * 这里只释放步骤私有状态。工具参数已经通过 START/ARGS_DELTA 进入 core 聚合器，
     * {@code TOOL_CALL_END} 必须由 core 在最终 ToolCall 组装完成后唯一发出。
     * </p>
     */
    private boolean handleStepStop(ChatStreamContext ctx, ONode oData) {
        ChatAccumulator acc = ctx.getAccumulator();

        int index = oData.get("index").getInt();
        StepAccumulator stepAcc = stepAccumulators(ctx).remove(index);
        if (stepAcc == null) return false;

        if (InteractionStepType.GOOGLE_SEARCH_RESULT == stepAcc.stepType) {
            emitGoogleSearchResults(ctx, oData.getOrNull("step"), stepAcc.emittedSearchResultKeys);
        }

        if (InteractionStepType.FUNCTION_CALL == stepAcc.stepType) {
            mergeFunctionIdentity(stepAcc, oData.getOrNull("step"));
            emitFunctionStartIfReady(ctx, index, stepAcc, true);
            emitPendingFunctionArguments(ctx, index, stepAcc, oData);
            if (Utils.isNotEmpty(acc.thinkingSignature)
                    && !Boolean.TRUE.equals(ctx.attrAs(ATTR_SIGNATURE_BOUND))) {
                ToolCall call = new ToolCall("idx:" + index, stepAcc.callId,
                        stepAcc.functionName, null, null);
                acc.putTerminalProtocolState(GeminiMessageStateSupport.INTERACTIONS_PROTOCOL_ID,
                        GeminiMessageStateSupport.createSignatureState(call, 0, acc.thinkingSignature));
                ctx.attrPut(ATTR_SIGNATURE_BOUND, true);
            }
        }

        // thinking -> function_call 的边界由事件归一化器根据工具事件自动闭合。
        if (acc.in_thinking) {
            acc.in_thinking = false;
        }

        return false;
    }

    /**
     * 处理 interaction.completed 事件
     */
    private void handleInteractionCompleted(ChatAccumulator acc, ONode oData) {
        ONode interaction = oData.getOrNull("interaction");
        if (interaction == null) interaction = oData;
        applyInteractionStatus(acc, interaction);
        ONode usage = interaction.getOrNull("usage");
        if (usage != null) parseUsage(acc, usage);
    }

    private void handleInteractionStatus(ChatStreamContext ctx, ONode data) {
        ONode interaction = data.getOrNull("interaction");
        if (interaction == null) interaction = data;
        ctx.setProviderResponseId(responseIdOf(data));
        applyInteractionStatus(ctx.getAccumulator(), interaction);
        ctx.emit(ctx.event(ChatEventType.STATUS)
                .rawType("interaction.status_update")
                .itemId(responseIdOf(data)).raw(data).build());
    }

    private void applyInteractionStatus(ChatAccumulator acc, ONode interaction) {
        String status = interaction.get("status").getString();
        if (!isTerminalStatus(status)) return;
        acc.setFinished(true);
        acc.lastFinishReason = mapStatusToFinishReason(status);
        if ("failed".equals(status)) {
            ONode error = interaction.getOrNull("error");
            String message = error == null ? null : error.get("message").getString();
            acc.setError(new ChatException(Utils.isEmpty(message) ? "Gemini interaction failed" : message));
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
            return new AssistantMessage(text.toString());
        }
    
        List<ContentBlock> blocksForMsg = new ArrayList<>();
        if (text.length() > 0) {
            blocksForMsg.add(TextBlock.of(text.toString()));
        }
        blocksForMsg.addAll(media);
        return new AssistantMessage(text.toString(), "", null, blocksForMsg);
    }
    
    /**
     * 将消息中的媒体逐块投影为 MEDIA_DONE；事件本身负责写入累积器。
     */
    private void emitMediaEvents(ChatStreamContext ctx, AssistantMessage message, int messageIndex) {
        if (message == null || !message.hasMedia()) {
            return;
        }

        int blockIndex = -1;
        for (ContentBlock block : message.getBlocks()) {
            blockIndex++;
            if (block == null || block instanceof TextBlock) {
                continue;
            }
            ctx.emit(ctx.event(ChatEventType.MEDIA_DONE)
                    .itemId("message:" + messageIndex + ":block:" + blockIndex)
                    .index(blockIndex)
                    .block(block)
                    .build());
        }
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
    
        if ("image".equals(type) || "audio".equals(type) || "video".equals(type)) {
            String mime = item.get("mime_type").getString();
            if (Utils.isEmpty(mime)) mime = item.get("mimeType").getString();
            String data = item.get("data").getString();
            String uri = item.get("uri").getString();
            if (Utils.isEmpty(mime)) {
                mime = "audio".equals(type) ? "audio/mpeg" : "video".equals(type) ? "video/mp4" : "image/jpeg";
            }
            return createMediaByMime(mime, uri, data);
        }

        if ("inline_data".equals(type) || item.hasKey("inline_data") || item.hasKey("inlineData")) {
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
            case "failed": return "error";
            case "cancelled": return "cancelled";
            case "incomplete": return "incomplete";
            case "budget_exceeded": return "length";
            default: return null;
        }
    }

    private boolean isTerminalStatus(String status) {
        return "completed".equals(status) || "requires_action".equals(status)
                || "failed".equals(status) || "cancelled".equals(status)
                || "incomplete".equals(status) || "budget_exceeded".equals(status);
    }

    private void emitServerToolSnapshot(ChatStreamContext ctx, ChatEventType phase,
                                        InteractionStepType type, ONode step) {
        ctx.emit(ctx.event(phase).rawType("step").subType(type.getApiValue())
                .itemId(step.get("id").getString()).raw(step).build());
    }

    /**
     * 只投影 google_search_result 中明确存在的逐项网页结果。
     *
     * <p>Interactions 当前常见的 result 仅包含 search_suggestions，这属于服务端工具结果载荷，
     * 不是网页结果列表。只有 result/results/search_results（或其对象包裹层）实际提供数组，且数组项
     * 能提取 url/uri 时，才发出类型化 SEARCH_RESULT；空的生命周期 step 仍只走 SERVER_TOOL_*。</p>
     */
    private void emitGoogleSearchResults(ChatStreamContext ctx, ONode step, Set<String> emittedKeys) {
        ONode items = findGoogleSearchResultItems(step);
        if (items == null) {
            return;
        }

        int itemIndex = -1;
        for (ONode item : items.getArray()) {
            itemIndex++;
            SearchResult result = parseGoogleSearchResult(item, itemIndex);
            if (result == null) {
                continue;
            }

            String identity = Utils.isNotEmpty(result.getId()) ? "id:" + result.getId() : "url:" + result.getUrl();
            String key = "index:" + itemIndex + ':' + identity;
            if (emittedKeys != null && emittedKeys.add(key) == false) {
                continue;
            }

            ctx.emit(ctx.event(ChatEventType.SEARCH_RESULT)
                    .rawType("step")
                    .subType(InteractionStepType.GOOGLE_SEARCH_RESULT.getApiValue())
                    .itemId(result.getId())
                    .index(result.getIndex() == null ? itemIndex : result.getIndex())
                    .text(result.getUrl())
                    .searchResult(result)
                    .raw(item)
                    .build());
        }
    }

    private ONode findGoogleSearchResultItems(ONode step) {
        if (step == null || step.isObject() == false) {
            return null;
        }

        String[] fields = {"result", "results", "search_results"};
        for (String field : fields) {
            ONode value = step.getOrNull(field);
            if (value == null) {
                continue;
            }
            if (value.isArray()) {
                return value;
            }
            if (value.isObject()) {
                ONode nested = firstArray(value, "results", "search_results", "items");
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private ONode firstArray(ONode node, String... fields) {
        for (String field : fields) {
            ONode value = node.getOrNull(field);
            if (value != null && value.isArray()) {
                return value;
            }
        }
        return null;
    }

    private SearchResult parseGoogleSearchResult(ONode item, int fallbackIndex) {
        if (item == null || item.isObject() == false) {
            return null;
        }

        ONode source = item;
        ONode web = item.getOrNull("web");
        if (web != null && web.isObject()) {
            source = web;
        }

        String url = firstNonEmpty(source, "url", "uri");
        if (Utils.isEmpty(url)) {
            return null;
        }

        Integer index = fallbackIndex;
        if (item.hasKey("index")) {
            index = item.get("index").getInt();
        } else if (source != item && source.hasKey("index")) {
            index = source.get("index").getInt();
        }

        String id = firstNonEmpty(item, "id");
        if (Utils.isEmpty(id) && source != item) {
            id = firstNonEmpty(source, "id");
        }

        return new SearchResult()
                .index(index)
                .id(id)
                .title(firstNonEmpty(source, "title"))
                .url(url)
                .snippet(firstNonEmpty(source, "snippet", "summary", "description"));
    }

    private String firstNonEmpty(ONode node, String... fields) {
        for (String field : fields) {
            String value = node.get(field).getString();
            if (Utils.isNotEmpty(value)) {
                return value;
            }
        }
        return null;
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
        long toolUseTokens = oUsage.get("total_tool_use_tokens").getLong();
        long promptTokens = oUsage.get("total_input_tokens").getLong() + toolUseTokens;
        long thinkingTokens = oUsage.get("total_thought_tokens").getLong();
        long completionTokens = oUsage.get("total_output_tokens").getLong();
        long totalTokens = oUsage.get("total_tokens").getLong();
        long cachedTokens = oUsage.get("total_cached_tokens").getLong();
        long webSearchRequests = 0L;
        ONode groundingCounts = oUsage.getOrNull("grounding_tool_count");
        if (groundingCounts != null && groundingCounts.isArray()) {
            for (ONode count : groundingCounts.getArray()) {
                String type = count.get("type").getString();
                if (Utils.isNotEmpty(type) && type.toLowerCase().contains("search")) {
                    webSearchRequests += count.get("count").getLong();
                }
            }
        }

        acc.setUsage(AiUsage.builder()
                .promptTokens(promptTokens)
                .thinkTokens(thinkingTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .cacheReadInputTokens(cachedTokens)
                .webSearchRequests(webSearchRequests)
                .source(oUsage)
                .build());
    }

    /**
     * 步骤累积器
     * <p>
     * 用于在流式模式下累积每个 step 的增量数据。
     */
    private static class StepAccumulator {
        // 未建模的步骤类型为 null：后绥按类型分叉时自然不命中，与旧字符串比较等价
        final InteractionStepType stepType;
        String functionName;
        String callId;
        final StringBuilder argumentsBuilder = new StringBuilder();
        int emittedArgumentsLength;
        final Set<String> emittedSearchResultKeys = new HashSet<>();

        boolean startEmitted;

        StepAccumulator(InteractionStepType stepType) {
            this.stepType = stepType;
        }
    }
}
