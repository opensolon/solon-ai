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
package org.noear.solon.ai.agent.team.protocol;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.i18n.AgentI18nKeys;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * 黑板协作协议 (Blackboard Protocol)
 *
 * <p>核心特征：全局共享看板。Agent 通过工具主动同步结论和待办事项，
 * Supervisor 依据黑板上的数据补全情况和 `todo` 列表决定下一步路由。</p>
 */
@Preview("3.8.2")
public class BlackboardProtocol extends HierarchicalProtocol {
    private static final Logger LOG = LoggerFactory.getLogger(BlackboardProtocol.class);
    private static final String KEY_BOARD_DATA = "blackboard_state_obj";
    private static final String TOOL_SYNC = "__sync_to_blackboard__";

    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_COMPLETED = "COMPLETED";

    /**
     * 黑板状态机：管理共享数据与待办队列 (TODOs)
     *
     * <p>TODO 是自由文本任务描述，不是 Agent 名。完成语义：</p>
     * <ul>
     *   <li>显式字段 {@code completed_todo} / {@code done_todo} / {@code complete_todo}</li>
     *   <li>result 文本中声明已完成项（包含匹配）</li>
     *   <li>{@code status=COMPLETED} 时按 result 再尝试清理相关 TODO</li>
     * </ul>
     * 禁止用 agentName 删除 todo 文本。
     */
    public static class BoardState {
        private final ONode data = new ONode().asObject();
        public final Set<String> todos = new LinkedHashSet<>();
        private String lastUpdater;

        /**
         * 结构化合并数据（来自 state 字段）
         */
        public void merge(String agentName, Object rawInput) {
            if (rawInput == null) return;
            this.lastUpdater = agentName;

            try {
                ONode node = ONode.ofBean(rawInput);
                if (node.isObject()) {
                    // 先处理完成声明，再增删 todo，避免同包内先后顺序歧义
                    completeTodoNode(node.get("completed_todo"));
                    completeTodoNode(node.get("done_todo"));
                    completeTodoNode(node.get("complete_todo"));

                    node.getObjectUnsafe().forEach((k, v) -> {
                        if ("todo".equalsIgnoreCase(k)) {
                            addTodoNode(v);
                        } else if ("completed_todo".equalsIgnoreCase(k)
                                || "done_todo".equalsIgnoreCase(k)
                                || "complete_todo".equalsIgnoreCase(k)) {
                            // 已在上方处理
                        } else if ("status".equalsIgnoreCase(k)) {
                            String status = v.getString();
                            // FAILED 只标记状态；不再把 agentName 误塞进 todos
                            data.set(k, v);
                            if (STATUS_COMPLETED.equalsIgnoreCase(status)) {
                                // COMPLETED 时尝试用 result 字段清理相关 TODO
                                ONode resultNode = node.get("result");
                                if (resultNode != null && resultNode.isValue()) {
                                    completeMatchingTodos(resultNode.getString());
                                }
                            }
                        } else if ("result".equalsIgnoreCase(k) && v.isValue()) {
                            data.set(k, v);
                            completeMatchingTodos(v.getString());
                        } else {
                            data.set(k, v);
                        }
                    });
                } else if (node.isValue()){
                    String str = node.getString();
                    if(str.startsWith("{")) {
                        merge(agentName, ONode.ofJson(str));
                    } else {
                        data.set("result_" + agentName, str);
                        completeMatchingTodos(str);
                    }
                }
            } catch (Throwable e) {
                LOG.warn("Blackboard Protocol: Merge failed for [{}], payload: {}", agentName, rawInput);
            }
        }

        /**
         * 直接写入结论和待办
         */
        public void addDirect(String agentName, String result, String todo) {
            this.lastUpdater = agentName;
            if (Utils.isNotEmpty(result)) {
                data.set("output_" + agentName, result);
                // result 中声明完成项时同步清理 todo
                completeMatchingTodos(result);
            }
            if (Utils.isNotEmpty(todo)) {
                parseAndAddTodos(todo);
            }
        }

        /**
         * 显式完成一组 TODO（支持逗号/分号/换行分隔）
         */
        public void completeTodos(String rawCompleted) {
            if (Utils.isEmpty(rawCompleted)) return;
            String normalized = rawCompleted.replaceAll("[,;，；\n\r]+", ";");
            Arrays.stream(normalized.split(";"))
                    .map(String::trim)
                    .filter(Utils::isNotEmpty)
                    .forEach(this::completeTodoExactOrContains);
        }

        /**
         * 根据自由文本（result / 完成说明）移除语义匹配的 TODO
         */
        public void completeMatchingTodos(String text) {
            if (Utils.isEmpty(text) || todos.isEmpty()) return;

            String lower = text.toLowerCase(Locale.ROOT);
            // 若文本明确声明完成某 todo 关键词，或直接包含 todo 原文，则清理
            List<String> toRemove = new ArrayList<>();
            for (String todo : todos) {
                if (Utils.isEmpty(todo)) {
                    toRemove.add(todo);
                    continue;
                }
                String t = todo.toLowerCase(Locale.ROOT);
                if (lower.contains(t) || containsCompletedPhrase(lower, t)) {
                    toRemove.add(todo);
                }
            }
            toRemove.forEach(todos::remove);
        }

        private void completeTodoExactOrContains(String item) {
            if (Utils.isEmpty(item)) return;
            // 精确命中
            if (todos.remove(item)) {
                return;
            }
            String lower = item.toLowerCase(Locale.ROOT);
            List<String> toRemove = new ArrayList<>();
            for (String todo : todos) {
                if (Utils.isEmpty(todo)) {
                    toRemove.add(todo);
                    continue;
                }
                String t = todo.toLowerCase(Locale.ROOT);
                if (t.equals(lower) || t.contains(lower) || lower.contains(t)) {
                    toRemove.add(todo);
                }
            }
            toRemove.forEach(todos::remove);
        }

        private static boolean containsCompletedPhrase(String lowerText, String lowerTodo) {
            // 兼容 “完成了完善接口文档” / "completed: xxx" / "done - xxx" / "Finished xxx"
            // 调用方传入的是已 toLowerCase(Locale.ROOT) 的文本，关键词匹配即忽略大小写
            return lowerText.contains(lowerTodo) && hasCompletedKeyword(lowerText);
        }

        /**
         * 文本是否包含完成语义关键词（中文 / 英文忽略大小写）
         */
        public static boolean hasCompletedKeyword(String text) {
            if (Utils.isEmpty(text)) {
                return false;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            //完成关键词来自资源文件（合并所有已知语言；用户新增语言文件后自动生效）
            for (String kw : AgentI18n.itemsOfAllLocales(AgentI18nKeys.PROTOCOL_BLACKBOARD_COMPLETED_KEYWORDS)) {
                if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }

        private void addTodoNode(ONode v) {
            if (v == null || v.isNull()) return;
            if (v.isArray()) {
                v.getArrayUnsafe().forEach(i -> parseAndAddTodos(i.getString()));
            } else if (v.isValue()) {
                parseAndAddTodos(v.getString());
            }
        }

        private void completeTodoNode(ONode v) {
            if (v == null || v.isNull()) return;
            if (v.isArray()) {
                v.getArrayUnsafe().forEach(i -> completeTodos(i.getString()));
            } else if (v.isValue()) {
                completeTodos(v.getString());
            }
        }

        private void parseAndAddTodos(String rawTodo) {
            if (Utils.isEmpty(rawTodo)) return;
            // 正则清洗：统一分隔符 -> 去除序号/Markdown符号 -> 去空去重
            String normalized = rawTodo.replaceAll("[,;，；\n\r]+", ";");
            Arrays.stream(normalized.split(";"))
                    .map(String::trim)
                    .filter(Utils::isNotEmpty)
                    // 去掉常见序号前缀：1. / 1) / - / *
                    .map(t -> t.replaceFirst("^(?:\\d+[.)、]\\s*|[-*•]\\s+)", "").trim())
                    .filter(Utils::isNotEmpty)
                    .forEach(todos::add);
        }

        @Override
        public String toString() {
            ONode root = ONode.ofJson(data.toJson());
            if (!todos.isEmpty()) {
                ONode todoNode = root.getOrNew("todo").asArray();
                todos.forEach(todoNode::add);
            }
            root.getOrNew("_meta").set("last_updater", lastUpdater);
            return root.toJson();
        }
    }

    public BlackboardProtocol(TeamAgentConfig config) {
        super(config);
    }

    @Override
    public String name() {
        return "BLACKBOARD";
    }

    /**
     * 为 Agent 注入黑板同步工具，实现主动数据回传
     */
    @Override
    public void injectAgentTools(FlowContext context, Agent agent, Consumer<FunctionTool> receiver) {
        String traceKey = context.getAs(Agent.KEY_CURRENT_TEAM_TRACE_KEY);
        TeamTrace trace = (traceKey != null) ? context.getAs(traceKey) : null;
        if (trace == null) return;

        FunctionToolDesc tool = new FunctionToolDesc(TOOL_SYNC).returnDirect(true);
        tool.metaPut(Agent.META_AGENT, agent.name());

        Locale locale = trace.getOptions().getLocale();

        // 参数全部可选：避免 schema required 强迫模型编造空 todo/state，导致错误覆盖黑板
        tool.title(AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_BLACKBOARD_TOOL_TITLE))
                .description(AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_BLACKBOARD_TOOL_DESCRIPTION))
                .paramAdd("result", String.class, false, AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_BLACKBOARD_TOOL_PARAM_RESULT))
                .paramAdd("todo", String.class, false, AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_BLACKBOARD_TOOL_PARAM_TODO))
                .paramAdd("completed_todo", String.class, false, AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_BLACKBOARD_TOOL_PARAM_COMPLETED_TODO))
                .paramAdd("state", String.class, false, AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_BLACKBOARD_TOOL_PARAM_STATE));

        tool.doHandle(args -> {
            BoardState state = (BoardState) trace.getProtocolContext().computeIfAbsent(KEY_BOARD_DATA, k -> new BoardState());
            String res = (String) args.get("result");
            String todo = (String) args.get("todo");
            String completedTodo = (String) args.get("completed_todo");
            Object rawState = args.get("state");

            // 先清理已完成项，再写入新 todo，避免同一次同步中自相矛盾
            if (Utils.isNotEmpty(completedTodo)) {
                state.completeTodos(completedTodo);
            }
            if (Utils.isNotEmpty(res) || Utils.isNotEmpty(todo)) {
                state.addDirect(agent.name(), res, todo);
            }
            if (rawState != null) {
                state.merge(agent.name(), rawState);
            }

            return AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_BLACKBOARD_TOOL_UPDATED);
        });

        receiver.accept(tool);
    }

    @Override
    public void injectAgentInstruction(FlowContext context, Agent agent, Locale locale, StringBuilder sb) {
        // 保留 Hierarchical 身份/历史 + 汇报规范，再追加黑板专属要求
        super.injectAgentInstruction(context, agent, locale, sb);

        sb.append("\n").append(AgentI18n.fmt(locale, AgentI18nKeys.PROTOCOL_BLACKBOARD_AGENT_GUIDELINES, "tool", TOOL_SYNC)).append("\n");
    }

    /**
     * 先走 Hierarchical 增强（父类链路已 Prompt.copy()），再追加黑板快照。
     * state==null 时也必须 super，避免丢掉父类 Pre-Context / 主管指令。
     */
    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        // 父类已返回独立副本，可安全追加
        Prompt finalPrompt = super.prepareAgentPrompt(trace, agent, originalPrompt, locale);

        BoardState state = (BoardState) trace.getProtocolContext().get(KEY_BOARD_DATA);
        if (state == null) {
            return finalPrompt;
        }

        String info = AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_BLACKBOARD_PROMPT_SNAPSHOT);

        // 注入到消息列表顶部，作为 Agent 的上下文感知
        String blackboardContext = info + "\n```json\n" + ONode.serialize(state) + "\n```";
        return finalPrompt.addMessage(ChatMessage.ofUser(blackboardContext));
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        BoardState state = (BoardState) trace.getProtocolContext().get(KEY_BOARD_DATA);

        if (decision != null && decision.contains(config.getFinishMarker()) && state != null) {
            // 优先级 1：存在明确待办 —— 不得把 todo 自由文本当 Agent 名路由
            if (!state.todos.isEmpty()) {
                LOG.info("Blackboard: Intervention! Pending todos exist, defer to supervisor re-assignment.");
                return null;
            }
            // 优先级 2：状态为 FAILED 且未修复
            if (STATUS_FAILED.equalsIgnoreCase(state.data.get("status").getString())) {
                LOG.warn("Blackboard: Intervention! Status is FAILED, blocking finish.");
                // lastUpdater 可能是合法 Agent 名；非法时仍交 super/主管兜底
                if (Utils.isNotEmpty(state.lastUpdater) && config.getAgentMap().containsKey(state.lastUpdater)) {
                    return state.lastUpdater;
                }
                return null;
            }
        }

        return super.resolveSupervisorRoute(context, trace, decision);
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        BoardState state = (BoardState) trace.getProtocolContext().get(KEY_BOARD_DATA);
        sb.append("\n").append(AgentI18n.get(trace.getOptions().getLocale(), AgentI18nKeys.PROTOCOL_BLACKBOARD_SUPERVISOR_DASHBOARD_TITLE)).append("\n");
        sb.append(state != null ? "```json\n" + ONode.serialize(state) + "\n```\n" : "- Empty\n");
        super.prepareSupervisorInstruction(context, trace, sb);
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        sb.append("\n").append(AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_BLACKBOARD_SUPERVISOR_GUIDELINES));
    }

    /**
     * 终结审计：如果黑板中尚存待办事项且未达轮次上限，拦截 [FINISH] 路由
     */
    @Override
    public boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        if (decision.contains(config.getFinishMarker())) {
            BoardState state = (BoardState) trace.getProtocolContext().get(KEY_BOARD_DATA);
            if (state != null) {
                boolean hasPending = !state.todos.isEmpty() || STATUS_FAILED.equalsIgnoreCase(state.data.get("status").getString());
                if (hasPending && trace.getTurnCount() < trace.getOptions().getMaxTurns()) {
                    LOG.warn("Blackboard: Physical Block! Status is incomplete. Turn: {}", trace.getTurnCount());
                    return false;
                }
            }
        }

        return super.shouldSupervisorRoute(context, trace, decision);
    }
}