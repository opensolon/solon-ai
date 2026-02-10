package org.noear.solon.ai.codecli;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.PlanChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.skills.cli.CodeCLI;
import org.noear.solon.core.util.Assert;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;

public class AcpConnector {
    private final CodeCLI codeCLI; // CodeCLI 内部的 Agent

    public AcpConnector(CodeCLI codeCLI) {
        this.codeCLI = codeCLI;
    }

    public AcpAsyncAgent createAgent(AcpAgentTransport transport) {
        return AcpAgent.async(transport)
                .requestTimeout(Duration.ofSeconds(60))
                // 1. 初始化：告知 IDE 代理的能力
                .initializeHandler(request -> {
                    return Mono.just(new AcpSchema.InitializeResponse(
                            1,
                            new AcpSchema.AgentCapabilities(true,
                                    new AcpSchema.McpCapabilities(true, true),
                                    new AcpSchema.PromptCapabilities(true, true, true)),
                            Arrays.asList()
                    ));
                })
                // 2. 提示词处理：核心对接点
                .promptHandler((request, acpContext) -> {
                    Prompt userInput = toPrompt(request);
                    String sessionId = request.sessionId();

                    // 将 ACP 的 Prompt 转发给 Solon ReActAgent
                    return codeCLI.stream(request.sessionId(), userInput)
                            .doOnNext(chunk -> {

                                // --- 规划阶段 ---
                                if (chunk instanceof PlanChunk) {
                                    acpContext.sendUpdate(sessionId, new AcpSchema.AgentThoughtChunk(
                                            "agent_thought_chunk",
                                            new AcpSchema.TextContent("📋 [规划]: " + chunk.getContent())));
                                }
                                // --- 思考阶段 ---
                                else if (chunk instanceof ReasonChunk) {
                                    ReasonChunk reasonChunk = (ReasonChunk) chunk;
                                    // 过滤掉包含工具调用的原始思考片段（让 UI 更整洁）
                                    if (chunk.hasContent() && !reasonChunk.isToolCalls()) {
                                        acpContext.sendUpdate(sessionId, new AcpSchema.AgentThoughtChunk(
                                                "agent_thought_chunk",
                                                new AcpSchema.TextContent(chunk.getContent())));
                                    }
                                }
                                // --- 工具执行阶段 (Action/Observation) ---
                                else if (chunk instanceof ActionChunk) {
                                    ActionChunk actionChunk = (ActionChunk) chunk;
                                    String toolName = actionChunk.getToolName();
                                    String content = chunk.getContent();

                                    String output;
                                    if (Assert.isNotEmpty(toolName)) {
                                        // 参考 CodeCLI: ⚙️ [toolName] Observation:
                                        output = String.format("\n⚙️ [%s] Observation:\n%s", toolName, content);
                                    } else {
                                        output = "\n⚙️ " + content;
                                    }

                                    acpContext.sendUpdate(sessionId, new AcpSchema.AgentMessageChunk(
                                            "agent_message_chunk",
                                            new AcpSchema.TextContent(output)));
                                }
                                // --- 最终回复阶段 ---
                                else if (chunk instanceof ReActChunk) {
                                    // 参考 CodeCLI 的分割线风格
                                    String finalContent = "\n----------------------\n" + chunk.getContent();
                                    acpContext.sendUpdate(sessionId, new AcpSchema.AgentMessageChunk(
                                            "agent_message_chunk",
                                            new AcpSchema.TextContent(finalContent)));
                                }
                            })
                            .onErrorResume(e -> {
                                // 向 IDE 发送错误消息块，避免界面假死
                                acpContext.sendUpdate(request.sessionId(), new AcpSchema.AgentMessageChunk(
                                        "agent_message_chunk",
                                        new AcpSchema.TextContent("\n❌ 错误: " + e.getMessage())));
                                return Mono.empty();
                            })
                            .then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
                })
                .build();
    }

    public Prompt toPrompt(AcpSchema.PromptRequest promptRequest) {
        Prompt prompt = Prompt.of();

        Contents contents = new Contents();

        for (AcpSchema.ContentBlock cp : promptRequest.prompt()) {
            if (cp instanceof AcpSchema.TextContent) {
                AcpSchema.TextContent text = (AcpSchema.TextContent) cp;

                contents.addBlock(TextBlock.of(false, text.text()));
            } else if (cp instanceof AcpSchema.ImageContent) {
                AcpSchema.ImageContent image = (AcpSchema.ImageContent) cp;

                if (Assert.isEmpty(image.uri())) {
                    contents.addBlock(ImageBlock.ofBase64(image.data(), image.mimeType()));
                } else {
                    contents.addBlock(ImageBlock.ofUrl(image.uri(), image.mimeType()));
                }
            }
        }

        return prompt.addMessage(ChatMessage.ofUser(contents));
    }
}