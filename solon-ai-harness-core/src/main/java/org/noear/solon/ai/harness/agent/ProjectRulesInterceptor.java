/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness.agent;

import org.noear.solon.ai.agent.react.AbsReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.ProjectRulesTalent.Part;
import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 项目规范拦截器（与 {@link ProjectRulesTalent} 成对挂到代理上）：
 *
 * <ul>
 *   <li><b>变更消息</b>（opencode 每一步推理前的 InstructionState.prepare）：推理前重新读取规范，与模型当前看到的版本
 *   （工作记忆里最近一条变更消息告知的版本，没有时为才能记在本次提示词上的系统提示词版本）比较，不同就追加一条变更消息；</li>
 *   <li><b>子目录按需加载</b>（opencode 的 read 工具）：read / ls 成功后，把目标附近新发现的规范以消息注入；
 *   工作记忆里已注入过的不再重复（压缩后不可见了会重新注入）。</li>
 * </ul>
 *
 * <p>注入的是用户消息，只进工作记忆（与插话同一方式），尾部有未闭合的工具调用时不注入。</p>
 *
 * @author noear
 * @since 4.2.0
 */
final class ProjectRulesInterceptor extends AbsReActInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(ProjectRulesInterceptor.class);

    private final ProjectRulesTalent talent;

    ProjectRulesInterceptor(ProjectRulesTalent talent) {
        this.talent = talent;
    }

    @Override
    public boolean isEnabled() {
        return talent.isEnabled();
    }

    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPromptBuf) {
        if (trace.getWorkingMemory() == null || hasOpenToolCalls(trace)) {
            return;
        }

        try {
            List<Part> visible = visibleOf(trace);
            if (visible == null) {
                return; // 才能本次没有注入（未激活），无从比较
            }

            List<Part> observed = talent.load(cwdOf(trace));
            if (observed.equals(visible)) {
                return;
            }

            String text = ProjectRulesTalent.renderUpdate(visible, observed, HarnessI18n.localeOf(trace));
            if (text != null) {
                ChatMessage message = ChatMessage.ofUser(text);
                message.addMetadata("source", ProjectRulesTalent.MESSAGE_SOURCE);
                message.addMetadata(ProjectRulesTalent.META_PARTS, Part.toMaps(observed));
                trace.getWorkingMemory().addMessage(message);
            }
        } catch (Throwable e) {
            LOG.warn("Project rules update check failed: {}", e.getMessage());
        }
    }

    @Override
    public void onActionEnd(ReActTrace trace, Collection<ToolExchanger> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty() || trace.getWorkingMemory() == null || hasOpenToolCalls(trace)) {
            return;
        }

        try {
            String cwd = cwdOf(trace);
            List<Part> found = new ArrayList<>();
            for (ToolExchanger exchanger : toolCalls) {
                boolean isLs = ToolName.TOOL_LS.getName().equals(exchanger.getToolName());
                if (isLs == false && ToolName.TOOL_READ.getName().equals(exchanger.getToolName()) == false) {
                    continue;
                }
                ToolResult result = exchanger.getToolResult();
                if (result == null || result.isError()) {
                    continue;
                }
                Object path = exchanger.getArgs() == null ? null : exchanger.getArgs().get(isLs ? "path" : "file_path");
                if (path instanceof String) {
                    found.addAll(talent.discover(cwd, (String) path, isLs));
                }
            }
            if (found.isEmpty()) {
                return;
            }

            // 去重：工作记忆里已注入过的、本次重复发现的
            Set<String> known = injectedPaths(trace.getWorkingMemory().getMessages());
            List<Part> fresh = new ArrayList<>();
            List<String> paths = new ArrayList<>();
            for (Part part : found) {
                if (known.add(part.getPath())) {
                    fresh.add(part);
                    paths.add(part.getPath());
                }
            }
            if (fresh.isEmpty()) {
                return;
            }

            ChatMessage message = ChatMessage.ofUser(ProjectRulesTalent.render(fresh, HarnessI18n.localeOf(trace)));
            message.addMetadata("source", ProjectRulesTalent.MESSAGE_SOURCE);
            message.addMetadata(ProjectRulesTalent.META_PATHS, paths);
            trace.getWorkingMemory().addMessage(message);
        } catch (Throwable e) {
            LOG.warn("Project rules discovery failed: {}", e.getMessage()); // 发现失败不影响工具结果
        }
    }

    /**
     * 模型当前看到的规范：工作记忆里最近一条变更消息告知的版本，没有时为本次系统提示词里的版本（才能未注入时为 null）
     */
    private static @Nullable List<Part> visibleOf(ReActTrace trace) {
        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m instanceof UserMessage && m.hasMetadata(ProjectRulesTalent.META_PARTS)) {
                List<Part> parts = Part.fromMaps(m.getMetadata().get(ProjectRulesTalent.META_PARTS));
                if (parts != null) {
                    return parts;
                }
            }
        }

        Prompt prompt = trace.getOriginalPrompt();
        return prompt == null ? null : Part.fromMaps(prompt.attrs().get(ProjectRulesTalent.ATTR_RUN_PARTS));
    }

    private static @Nullable String cwdOf(ReActTrace trace) {
        Object cwd = trace.getOptions().getToolContext().get(HarnessEngine.ATTR_CWD);
        return cwd instanceof String && Assert.isNotEmpty((String) cwd) ? (String) cwd : null;
    }

    /** 工作记忆里已注入过的子目录规范路径 */
    static Set<String> injectedPaths(List<ChatMessage> messages) {
        Set<String> paths = new HashSet<>();
        for (ChatMessage m : messages) {
            if (m instanceof UserMessage && m.hasMetadata(ProjectRulesTalent.META_PATHS)) {
                Object list = m.getMetadata().get(ProjectRulesTalent.META_PATHS);
                if (list instanceof Collection) {
                    for (Object p : (Collection<?>) list) {
                        if (p != null) {
                            paths.add(String.valueOf(p));
                        }
                    }
                }
            }
        }
        return paths;
    }

    private static boolean hasOpenToolCalls(ReActTrace trace) {
        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        if (messages.isEmpty()) {
            return false;
        }
        ChatMessage last = messages.get(messages.size() - 1);
        return last instanceof AssistantMessage && Assert.isNotEmpty(((AssistantMessage) last).getToolCalls());
    }
}
