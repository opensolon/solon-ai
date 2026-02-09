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

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.*;
import org.noear.solon.ai.agent.util.FeedbackTool;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ReAct 规划任务节点
 * <p>在正式推理开始前，引导模型将复杂目标拆解为结构化的执行计划（Plans）。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class PlanTask implements NamedTaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(PlanTask.class);
    private static final Pattern PLAN_LINE_PREFIX_PATTERN = Pattern.compile("^[\\d\\.\\-\\s*]+");

    private final ReActAgentConfig config;

    public PlanTask(ReActAgentConfig config) { this.config = config; }

    @Override
    public String name() { return ReActAgent.ID_PLAN; }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String traceKey = context.getAs(ReActAgent.KEY_CURRENT_UNIT_TRACE_KEY);
        ReActTrace trace = context.getAs(traceKey);

        if(trace.getOptions().isPlanningMode() == false){
            if (LOG.isTraceEnabled()) {
                LOG.trace("ReActAgent [{}] Plan is disabled, skipping...", config.getName());
            }
            return;
        }

        // 1. 根据配置的语言环境确定“目标”标签
        boolean isZh = Locale.CHINA.getLanguage().equals(trace.getConfig().getLocale().getLanguage());
        String targetLabel = isZh ? "目标：" : "Target: ";

        // 2. 构建规划请求
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofSystem(trace.getOptions().getPlanningInstruction(trace)));
        // 动态拼接引导词
        messages.add(ChatMessage.ofUser(targetLabel + trace.getOriginalPrompt().getUserContent()));

        // 3. 调用模型生成计划
        ChatRequestDesc req = config.getChatModel()
                .prompt(messages)
                .options(o->{
                    if (trace.getConfig().getStyle() == ReActStyle.NATIVE_TOOL) {
                        if (trace.getOptions().isFeedbackMode()) {
                            o.toolAdd(FeedbackTool.getTool(
                                    trace.getOptions().getFeedbackDescription(trace),
                                    trace.getOptions().getFeedbackReasonDescription(trace)));
                        }
                    }
                });

        ChatResponse response;

        if(trace.getOptions().getStreamSink() != null){
            response = req.stream()
                    .doOnNext(resp->{
                        trace.getOptions().getStreamSink().next(
                                new PlanChunk(node, trace, resp));
                    })
                    .blockLast();
        } else {
            response = req.call();
        }


        AssistantMessage responseMessage = response.getMessage();
        if(responseMessage == null){
            responseMessage = response.getAggregationMessage();
        }

        if (response.getUsage() != null) {
            trace.getMetrics().addUsage(response.getUsage());
        }

        // 触发计划审计事件（传递原始消息对象）
        for (RankEntity<ReActInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onPlan(trace, responseMessage);
        }

        if(trace.isPending()){
            return;
        }

        if (responseMessage.hasContent()) {
            if (FeedbackTool.TOOL_NAME.equals(responseMessage.getMetadataAs("__tool"))) {
                String source = responseMessage.getMetadataAs("source");
                if (Assert.isNotEmpty(source)) {
                    trace.setRoute(Agent.ID_END);
                    trace.setFinalAnswer(source);
                    trace.getContext().interrupt();
                    return;
                }
            }
        }

        String planContent = responseMessage.getResultContent();

        if (Assert.isEmpty(planContent)) {
            LOG.warn("ReActAgent [{}] Plan generated empty content, using default goal.", config.getName());
            return;
        }

        // 4. 清洗计划内容（移除数字序号和 Markdown 符号）
        String[] lines = planContent.split("\n");
        List<String> cleanedPlans = new ArrayList<>(lines.length);

        for (String line : lines) {
            String cleaned = PLAN_LINE_PREFIX_PATTERN.matcher(line).replaceAll("").trim();
            if (!cleaned.isEmpty()) {
                cleanedPlans.add(cleaned);
            }
        }

        trace.setPlans(cleanedPlans);

        if (LOG.isDebugEnabled()) {
            LOG.debug("ReActAgent [{}] Plan generated: \n{}", config.getName(), planContent);
        }
    }
}