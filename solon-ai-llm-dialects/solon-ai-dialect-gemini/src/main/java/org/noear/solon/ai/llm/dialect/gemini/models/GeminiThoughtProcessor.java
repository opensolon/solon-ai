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
package org.noear.solon.ai.llm.dialect.gemini.models;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.llm.dialect.gemini.GeminiMessageStateSupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gemini 思考内容处理器
 * <p>
 * 负责解析和处理 Gemini API 返回的思考内容（thoughts），
 * 包括流式和非流式两种场景下的思考内容提取。
 *
 * @author cwdhf
 * @since 3.1
 */
public class GeminiThoughtProcessor {
    /** 清理思考内容时，仅处理不超过此长度的整行 "**...**" 包裹（标题型），避免误删正常加粗正文 */
    static final int CLEAN_THOUGHT_HEADING_MAX_LENGTH = 30;

    /**
     * 解析 Gemini 助手消息，处理思考内容和工具调用
     *
     * @param acc     聊天响应
     * @param oContent 消息内容节点
     * @return 解析后的助手消息列表
     */
    public List<AssistantMessage> parse(ChatAccumulator acc, ONode oContent) {
        List<AssistantMessage> messageList = new ArrayList<>();

        if (oContent == null) {
            return messageList;
        }

        ONode oParts = oContent.getOrNull("parts");
        if (oParts == null || oParts.isArray() == false) {
            oParts = oContent.getOrNull("part");
        }

        if (oParts == null || oParts.isArray() == false) {
            return messageList;
        }

        if (oParts != null) {
            // 此处 oParts 必为非空数组（上方已校验）
            boolean hasThoughtPart = false;
            boolean hasNormalPart = false;
            boolean hasMediaPart = false;
            
            List<ToolCall> toolCalls = new ArrayList<>();
            List<ContentBlock> mediaBlocks = new ArrayList<>();
            List<ContentBlock> orderedBlocks = new ArrayList<>();
            StringBuilder thoughtTextAll = new StringBuilder();
            StringBuilder normalTextAll = new StringBuilder();
            // 同一 chunk 内同名函数并行调用去冲突：首个用 name，后续用 name#n 作为流式聚合 index
            Map<String, Integer> nameCount = new LinkedHashMap<>();
            String firstToolCallSignature = null;

            for (ONode oPart : oParts.getArray()) {
                ONode thoughtNode = oPart.getOrNull("thought");
                boolean isThought = thoughtNode != null && thoughtNode.getBoolean();
                
                ONode functionCallNode = oPart.getOrNull("functionCall");
                if (functionCallNode == null) {
                    functionCallNode = oPart.getOrNull("function_call");
                }
                boolean isFunctionCall = functionCallNode != null && functionCallNode.isObject();
                    
                if (isFunctionCall) {
                    String functionName = functionCallNode.get("name").getString();
                    // 续帧兼容：OpenAI 兼容网关（如 bearlab.ai）流式转 Gemini 时会把 functionCall 分帧发送
                    // （帧1 带 name/空 args，帧2 带 args/空 name）。name 为空且已有调用上下文时，视为续帧，
                    // 从 lastToolCallId 恢复函数名，避免生成 name 为空的无效 ToolCall。
                    if (Utils.isEmpty(functionName) && Utils.isNotEmpty(acc.lastToolCallId)) {
                        functionName = acc.lastToolCallId;
                    }
                    // Gemini 3+ 官方规范：functionCall 携带唯一调用 id，functionResponse 回传时必须带上相同 id。
                    // 解析并保留该 id；服务端（含 OpenAI 兼容网关转 Gemini）不返回 id 时为 null ——
                    // 此时完全按 Gemini 2.5 的 name 关联方式回传（不写 id），避免本地伪造 id 导致网关关联失败。
                    String callId = null;
                    ONode idNode = functionCallNode.get("id");
                    if (idNode == null) {
                        idNode = functionCallNode.get("call_id");
                    }
                    if (idNode != null && idNode.isString() && Utils.isNotEmpty(idNode.getString())) {
                        callId = idNode.getString();
                    }
                    ONode argsNode = functionCallNode.get("args");
                    if (argsNode == null || argsNode.isNull()) {
                        argsNode = functionCallNode.get("arguments");
                    }
                    if (argsNode != null && argsNode.isString()) {
                        try {
                            ONode parsedArgs = ONode.ofJson(argsNode.getString());
                            if (parsedArgs.isObject()) argsNode = parsedArgs;
                        } catch (Exception ignored) {
                            // 非 JSON 字符串继续按空参数处理
                        }
                    }
                    // 解析出口净化：仅 object 形态的 args 采纳；字符串（可能内含截断 JSON）等一律归一为空对象，
                    // 避免非法 JSON 进入会话历史后毒化后续请求
                    String argsJson = null;
                    Map<String, Object> argsMap = new LinkedHashMap<>();
                    if (argsNode != null && argsNode.isObject()) {
                        argsMap = argsNode.toBean(Map.class);
                        if (!argsMap.isEmpty() || !acc.isStream()) {
                            argsJson = argsNode.toJson();
                        }
                    }
                    if (argsJson == null && !acc.isStream()) {
                        argsJson = "{}";
                    }
                            
                    String callIndex;
                    if (Utils.isNotEmpty(functionName)) {
                        // 跨 chunk 同名（同一 functionCall 续传）仍用 name 聚合（保持原行为）；
                        // 同 chunk 并行同名（两个 getWeather 同时调用）用 name#n 区分，避免流式 builder 合并
                        int seen = nameCount.merge(functionName, 1, Integer::sum);
                        callIndex = seen == 1 ? functionName : functionName + "#" + (seen - 1);
                    } else {
                        callIndex = acc.lastToolCallId;
                    }
                    if (Utils.isNotEmpty(functionName)) {
                        acc.lastToolCallId = functionName;
                    }
                    ToolCall toolCall = new ToolCall(callIndex,
                            callId,
                            functionName, argsJson, argsMap);
                                
                    // 仅第一个 functionCall part 携带 thoughtSignature（并行调用时后续 part 没有）
                    if (toolCalls.isEmpty()) {
                        ONode thoughtSigNode = oPart.getOrNull("thoughtSignature");
                        if (thoughtSigNode == null) {
                            thoughtSigNode = oPart.getOrNull("thought_signature");
                        }
                        if (thoughtSigNode != null) {
                            String thoughtSignature = thoughtSigNode.getString();
                            if (Utils.isNotEmpty(thoughtSignature)) {
                                firstToolCallSignature = thoughtSignature;
                                acc.thinkingSignature = thoughtSignature;
                            }
                        }
                    }
                    
                    toolCalls.add(toolCall);
                } else if (isThought) {
                    hasThoughtPart = true;
                    String partText = oPart.get("text").getString();
                    if (Utils.isNotEmpty(partText)) thoughtTextAll.append(partText);
                } else if (oPart.hasKey("text")) {
                    hasNormalPart = true;
                    String partText = oPart.get("text").getString();
                    if (Utils.isNotEmpty(partText)) {
                        normalTextAll.append(partText);
                        orderedBlocks.add(TextBlock.of(partText));
                    }
                } else {
                    ContentBlock media = parseMediaPart(oPart);
                    if (media != null) {
                        mediaBlocks.add(media);
                        orderedBlocks.add(media);
                        hasMediaPart = true;
                    }
                }
            }
                    
            if (!toolCalls.isEmpty()) {
                if (acc.in_thinking && acc.isStream()) {
                    messageList.add(new AssistantMessage("", ""));
                }
                acc.in_thinking = false;
                        
                List<ContentBlock> blocksForMsg = orderedBlocks.isEmpty() ? null : new ArrayList<>(orderedBlocks);
                MessageProtocolState signatureState = GeminiMessageStateSupport.createSignatureState(
                        toolCalls.get(0), 0, firstToolCallSignature);
                Map<String, MessageProtocolState> protocolStates = signatureState == null ? null
                        : Collections.singletonMap(
                                GeminiMessageStateSupport.GENERATE_CONTENT_PROTOCOL_ID, signatureState);
                AssistantMessage msg = AssistantMessage.snapshot(
                        normalTextAll.toString(), thoughtTextAll.toString(),
                        toolCalls, blocksForMsg, null, null, protocolStates);
                messageList.add(msg);
                return messageList;
            }
                        
            if (acc.isStream()) {
                if (hasThoughtPart && !hasNormalPart && !hasMediaPart) {
                    if (!acc.in_thinking) {
                        messageList.add(new AssistantMessage("", ""));
                        acc.in_thinking = true;
                    }
                        
                    for (ONode oPart : oParts.getArray()) {
                        ONode thoughtNode = oPart.getOrNull("thought");
                        boolean isThought = thoughtNode != null && thoughtNode.getBoolean();
                            
                        if (isThought) {
                            String text = oPart.get("text").getString();
                            if (Utils.isNotEmpty(text)) {
                                messageList.add(new AssistantMessage("", text));
                            }
                        }
                    }
                } else if (!hasThoughtPart && (hasNormalPart || hasMediaPart)) {
                    if (acc.in_thinking) {
                        messageList.add(new AssistantMessage("", ""));
                        acc.in_thinking = false;
                    }

                    // 有媒体时：合并为单条消息（文本 + media blocks），避免文本先 delta 再整段重复
                    // 无媒体时：保持旧行为，按 part 增量推送文本
                    StringBuilder normalContent = new StringBuilder();
                    for (ONode oPart : oParts.getArray()) {
                        ONode thoughtNode = oPart.getOrNull("thought");
                        boolean isThought = thoughtNode != null && thoughtNode.getBoolean();

                        if (!isThought && oPart.hasKey("text")) {
                            String text = oPart.get("text").getString();
                            if (Utils.isNotEmpty(text)) {
                                normalContent.append(text);
                                if (mediaBlocks.isEmpty()) {
                                    messageList.add(new AssistantMessage(text, ""));
                                }
                            }
                        }
                    }

                    if (!mediaBlocks.isEmpty()) {
                        List<ContentBlock> blocks = new ArrayList<>();
                        if (normalContent.length() > 0) {
                            blocks.add(TextBlock.of(normalContent.toString()));
                        }
                        blocks.addAll(mediaBlocks);
                        messageList.add(new AssistantMessage(normalContent.toString(), "", null, blocks));
                    }
                } else if (hasThoughtPart && (hasNormalPart || hasMediaPart)) {
                    if (!acc.in_thinking) {
                        messageList.add(new AssistantMessage("", ""));
                    }

                    for (ONode oPart : oParts.getArray()) {
                        ONode thoughtNode = oPart.getOrNull("thought");
                        boolean isThought = thoughtNode != null && thoughtNode.getBoolean();

                        if (isThought) {
                            String text = oPart.get("text").getString();
                            if (Utils.isNotEmpty(text)) {
                                messageList.add(new AssistantMessage("", text));
                            }
                        }
                    }

                    messageList.add(new AssistantMessage("", ""));
                    acc.in_thinking = false;

                    // 有媒体时：合并为单条消息；无媒体时按 part 增量推送
                    StringBuilder normalContent = new StringBuilder();
                    for (ONode oPart : oParts.getArray()) {
                        ONode thoughtNode = oPart.getOrNull("thought");
                        boolean isThought = thoughtNode != null && thoughtNode.getBoolean();

                        if (!isThought && oPart.hasKey("text")) {
                            String text = oPart.get("text").getString();
                            if (Utils.isNotEmpty(text)) {
                                normalContent.append(text);
                                if (mediaBlocks.isEmpty()) {
                                    messageList.add(new AssistantMessage(text, ""));
                                }
                            }
                        }
                    }

                    if (!mediaBlocks.isEmpty()) {
                        List<ContentBlock> blocks = new ArrayList<>();
                        if (normalContent.length() > 0) {
                            blocks.add(TextBlock.of(normalContent.toString()));
                        }
                        blocks.addAll(mediaBlocks);
                        messageList.add(new AssistantMessage(normalContent.toString(), "", null, blocks));
                    }
                }
            } else {
                StringBuilder thoughtContent = new StringBuilder();
                StringBuilder normalContent = new StringBuilder();
        
                for (ONode oPart : oParts.getArray()) {
                    ONode thoughtNode = oPart.getOrNull("thought");
                    boolean isThought = thoughtNode != null && thoughtNode.getBoolean();
    
                    if (oPart.hasKey("text")) {
                        String text = oPart.get("text").getString();
                        if (Utils.isNotEmpty(text)) {
                            if (isThought) {
//                                if (thoughtContent.length() > 0) {
//                                    thoughtContent.append("\n");
//                                }
                                thoughtContent.append(text);
                            } else {
//                                if (normalContent.length() > 0) {
//                                    normalContent.append("\n");
//                                }
                                normalContent.append(text);
                            }
                        }
                    }
                }
    
                List<ContentBlock> blocksForMsg = null;
                if (!mediaBlocks.isEmpty()) {
                    blocksForMsg = new ArrayList<>();
                    if (normalContent.length() > 0) {
                        blocksForMsg.add(TextBlock.of(normalContent.toString()));
                    }
                    blocksForMsg.addAll(mediaBlocks);
                }
    
                if (thoughtContent.length() > 0 && normalContent.length() > 0) {
                    String cleanedThought = cleanThoughtContent(thoughtContent.toString());
                    // text/thinking 已是通用语义字段，不再重复生成旧 contentRaw 镜像。
                    messageList.add(new AssistantMessage(normalContent.toString(), cleanedThought,
                            null, blocksForMsg));
                } else if (thoughtContent.length() > 0) {
                    String cleanedThought = cleanThoughtContent(thoughtContent.toString());
                    messageList.add(new AssistantMessage("", cleanedThought,
                            null, blocksForMsg));
                } else if (normalContent.length() > 0 || blocksForMsg != null) {
                    messageList.add(new AssistantMessage(normalContent.toString(), "", null, blocksForMsg));
                }
            }
        }
    
        return messageList;
    }
    
    /**
     * 解析 Gemini part 中的媒体（inline_data / file_data，兼容 camelCase）。
     *
     * @since 3.9
     */
    private ContentBlock parseMediaPart(ONode oPart) {
        if (oPart == null || !oPart.isObject()) {
            return null;
        }
    
        ONode inline = oPart.getOrNull("inline_data");
        if (inline == null) {
            inline = oPart.getOrNull("inlineData");
        }
        if (inline != null && inline.isObject()) {
            String mime = inline.get("mime_type").getString();
            if (Utils.isEmpty(mime)) {
                mime = inline.get("mimeType").getString();
            }
            String data = inline.get("data").getString();
            return createMediaByMime(mime, null, data);
        }
    
        ONode fileData = oPart.getOrNull("file_data");
        if (fileData == null) {
            fileData = oPart.getOrNull("fileData");
        }
        if (fileData != null && fileData.isObject()) {
            String mime = fileData.get("mime_type").getString();
            if (Utils.isEmpty(mime)) {
                mime = fileData.get("mimeType").getString();
            }
            String uri = fileData.get("file_uri").getString();
            if (Utils.isEmpty(uri)) {
                uri = fileData.get("fileUri").getString();
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
            } else if (lower.startsWith("image/")) {
                mediaType = "image";
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

    private static final String ATTR_FUNCTION_CALL_STATES = "gemini.models.functionCallStates";
    private static final String ATTR_EMITTED_MEDIA_POSITIONS = "gemini.models.emittedMediaPositions";

    /**
     * 将 Gemini Models 流式分片直接投影为事件，不写入内容项。
     *
     * <p>functionCall.args 是累计快照。调用头和最终参数先按候选与调用位置稳定归并，
     * 到候选完成时再发出唯一 START 与一个真实 ARGS_DELTA，避免把多个完整 JSON 快照串接。</p>
     */
    public void emitStream(ChatStreamContext ctx, ONode oContent) {
        emitStream(ctx, oContent, 0, false);
    }

    /**
     * 按候选位置处理流式内容。
     *
     * @param candidateIndex 候选位置
     * @param completed      当前候选是否已经完成
     */
    public void emitStream(ChatStreamContext ctx, ONode oContent, int candidateIndex, boolean completed) {
        ChatAccumulator acc = ctx.getAccumulator();
        List<AssistantMessage> messages = parse(acc, oContent);
        int toolOrdinal = 0;

        for (AssistantMessage message : messages) {
            if (Utils.isNotEmpty(message.getThinkingRaw())) {
                ctx.emit(ctx.event(ChatEventType.THINKING_DELTA)
                        .text(message.getThinkingRaw()).build());
            }
            if (Utils.isNotEmpty(message.getTextRaw())) {
                ctx.emit(ctx.event(ChatEventType.TEXT_DELTA)
                        .text(message.getTextRaw()).build());
            }
            if (message.hasMedia()) {
                for (ContentBlock block : message.getBlocks()) {
                    if (block instanceof TextBlock) {
                        continue;
                    }

                    int mediaIndex = nextMediaIndex(ctx);
                    String itemId = "candidate:" + candidateIndex + ":media:" + mediaIndex;
                    ctx.emit(ctx.event(ChatEventType.MEDIA_DONE)
                            .itemId(itemId)
                            .index(mediaIndex)
                            .block(block).build());
                }
            }
            if (Utils.isNotEmpty(message.getToolCalls())) {
                int messageToolIndex = 0;
                for (ToolCall call : message.getToolCalls()) {
                    String signature = GeminiMessageStateSupport.resolveSignature(message,
                            GeminiMessageStateSupport.GENERATE_CONTENT_PROTOCOL_ID, call, messageToolIndex++);
                    String stableKey = resolveFunctionCallKey(ctx, candidateIndex, toolOrdinal++, call);
                    FunctionCallStreamState state = functionCallStates(ctx)
                            .computeIfAbsent(stableKey, key -> new FunctionCallStreamState(stableKey, candidateIndex));
                    state.merge(call);

                    if (Utils.isNotEmpty(signature)) {
                        state.signature = signature;
                    }
                    if (Utils.isNotEmpty(signature) && !state.signatureEmitted) {
                        state.signatureEmitted = true;
                        ctx.emit(ctx.event(ChatEventType.THINKING_SIGNATURE)
                                .itemId(stableKey)
                                .text(signature).build());
                    }
                }
            }
        }

        if (completed) {
            flushFunctionCalls(ctx, candidateIndex);
        }
    }

    /** 将非流式解析结果中的媒体也经 MEDIA_DONE 统一归并。 */
    void emitMediaEvents(ChatStreamContext ctx, List<AssistantMessage> messages, int candidateIndex) {
        int mediaOrdinal = 0;
        Set<String> emittedPositions = ctx.attrIfAbsent(ATTR_EMITTED_MEDIA_POSITIONS,
                key -> new LinkedHashSet<String>());

        for (AssistantMessage message : messages) {
            if (!message.hasMedia()) {
                continue;
            }
            for (ContentBlock block : message.getBlocks()) {
                if (block instanceof TextBlock) {
                    continue;
                }

                String positionKey = "candidate:" + candidateIndex + ":media:" + mediaOrdinal;
                if (emittedPositions.add(positionKey)) {
                    ctx.emit(ctx.event(ChatEventType.MEDIA_DONE)
                            .itemId(positionKey)
                            .index(mediaOrdinal)
                            .block(block).build());
                }
                mediaOrdinal++;
            }
        }
    }

    /** 在供应商只用 [DONE] 收尾时提交所有尚未发出的 functionCall 快照。 */
    public void completeStream(ChatStreamContext ctx) {
        Map<String, FunctionCallStreamState> states = functionCallStates(ctx);
        List<Integer> candidateIndexes = new ArrayList<>();
        for (FunctionCallStreamState state : states.values()) {
            if (!candidateIndexes.contains(state.candidateIndex)) {
                candidateIndexes.add(state.candidateIndex);
            }
        }
        for (Integer candidateIndex : candidateIndexes) {
            flushFunctionCalls(ctx, candidateIndex);
        }
    }

    private int nextMediaIndex(ChatStreamContext ctx) {
        Integer value = ctx.attrAs("gemini.models.mediaSequence");
        int next = value == null ? 0 : value;
        ctx.attrPut("gemini.models.mediaSequence", next + 1);
        return next;
    }

    private String resolveFunctionCallKey(ChatStreamContext ctx, int candidateIndex, int ordinal, ToolCall call) {
        Map<String, FunctionCallStreamState> states = functionCallStates(ctx);
        String base = "candidate:" + candidateIndex + ":function:" + ordinal;
        FunctionCallStreamState existing = states.get(base);
        if (existing == null || existing.matches(call)) {
            return base;
        }

        String identity = Utils.isNotEmpty(call.getId()) ? call.getId() : call.getName();
        String candidate = base + ":" + (identity == null ? "next" : identity);
        int suffix = 1;
        while (states.containsKey(candidate) && !states.get(candidate).matches(call)) {
            candidate = base + ":" + (identity == null ? "next" : identity) + ":" + suffix++;
        }
        return candidate;
    }

    private Map<String, FunctionCallStreamState> functionCallStates(ChatStreamContext ctx) {
        return ctx.attrIfAbsent(ATTR_FUNCTION_CALL_STATES, key -> new LinkedHashMap<String, FunctionCallStreamState>());
    }

    private void flushFunctionCalls(ChatStreamContext ctx, int candidateIndex) {
        Map<String, FunctionCallStreamState> states = functionCallStates(ctx);
        java.util.Iterator<Map.Entry<String, FunctionCallStreamState>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            FunctionCallStreamState state = iterator.next().getValue();
            if (state.candidateIndex != candidateIndex) {
                continue;
            }

            ToolCall call = new ToolCall(state.stableKey, state.callId, state.functionName, null, null);
            ctx.emit(ctx.event(ChatEventType.TOOL_CALL_START)
                    .itemId(state.stableKey)
                    .toolCall(call)
                    .toolCallId(state.callId)
                    .build());

            String finalArgs = Utils.isNotEmpty(state.argumentsSnapshot) ? state.argumentsSnapshot : "{}";
            ToolCall argsCall = new ToolCall(state.stableKey, state.callId, state.functionName, finalArgs, null);
            ctx.emit(ctx.event(ChatEventType.TOOL_CALL_ARGS_DELTA)
                    .itemId(state.stableKey)
                    .toolCall(argsCall)
                    .toolCallId(state.callId)
                    .text(finalArgs)
                    .build());
            if (Utils.isNotEmpty(state.signature)) {
                ctx.getAccumulator().putTerminalProtocolState(
                        GeminiMessageStateSupport.GENERATE_CONTENT_PROTOCOL_ID,
                        GeminiMessageStateSupport.createSignatureState(call, 0, state.signature));
            }
            iterator.remove();
        }
    }

    private static final class FunctionCallStreamState {
        final String stableKey;
        final int candidateIndex;
        String functionName;
        String callId;
        String argumentsSnapshot;
        String signature;
        boolean signatureEmitted;

        FunctionCallStreamState(String stableKey, int candidateIndex) {
            this.stableKey = stableKey;
            this.candidateIndex = candidateIndex;
        }

        boolean matches(ToolCall call) {
            if (Utils.isNotEmpty(callId) && Utils.isNotEmpty(call.getId())) {
                return callId.equals(call.getId());
            }
            if (Utils.isNotEmpty(functionName) && Utils.isNotEmpty(call.getName())) {
                return functionName.equals(call.getName());
            }
            return true;
        }

        void merge(ToolCall call) {
            if (Utils.isNotEmpty(call.getName())) {
                functionName = call.getName();
            }
            if (Utils.isNotEmpty(call.getId())) {
                callId = call.getId();
            }
            if (Utils.isNotEmpty(call.getArgumentsStr())) {
                argumentsSnapshot = call.getArgumentsStr();
            } else if (argumentsSnapshot == null) {
                argumentsSnapshot = "{}";
            }
        }
    }

    /**
     * 清理思考内容中的冗余 Markdown 格式。
     * <p>
     * Gemini API 返回的思考内容可能包含以下不需要的格式：
     * <ul>
     *   <li>以 "**" 开头和结尾的标题行（如 "**思考过程**"）</li>
     *   <li>纯 "**" 行</li>
     * </ul>
     * <p>
     * 此方法逐行处理内容，识别并跳过这些格式的行，
     * 保留其他所有内容以维持原始思考过程的完整性。
     *
     * @param content 原始思考内容
     * @return 清理后的思考内容，移除了不需要的 markdown 格式
     */
    public String cleanThoughtContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        String[] lines = content.split("\n");
        StringBuilder cleaned = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            // 仅清理短小的标题型包裹行（如 "**思考过程**"）与空包裹符：
            // 思考正文里合法的整行加粗短语不受影响
            if (trimmed.length() <= CLEAN_THOUGHT_HEADING_MAX_LENGTH
                    && trimmed.startsWith("**") && trimmed.endsWith("**")) {
                continue;
            }

            if (cleaned.length() > 0) {
                cleaned.append("\n");
            }
            cleaned.append(line);
        }

        return cleaned.toString();
    }
}
