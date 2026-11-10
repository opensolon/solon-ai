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
package org.noear.solon.ai.agent.i18n;

import org.noear.solon.lang.Preview;

/**
 * 智能体内置文案的 key（与资源文件 {@code solon-ai-agent/i18n/*.yml} 一一对应，统一在此管理）
 *
 * <p>命名规则：常量名 = key 转大写、"." 换成 "_"（如 {@code team.prompt.role} → {@link #TEAM_PROMPT_ROLE}）。
 * 带占位符的 key 在注释中列出变量名，调用 {@link AgentI18n#fmt} 时按名传入。</p>
 *
 * <p>约束（由单元测试保证）：常量与资源 key 集合完全一致；产品代码只通过本类常量取文案；
 * 每个 key 都有用例覆盖。用户覆盖或扩展语言时，也以本类列出的 key 为准。</p>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class AgentI18nKeys {
    private AgentI18nKeys() {
    }

    // ================= react.yml =================

    // ---- main prompt ----
    public static final String REACT_PROMPT_ROLE_DEFAULT = "react.prompt.role.default";
    /** 占位符：{@code {role}} */
    public static final String REACT_PROMPT_INTRO_TEXT = "react.prompt.intro.text";
    /** 占位符：{@code {role}} */
    public static final String REACT_PROMPT_INTRO_NATIVE = "react.prompt.intro.native";
    public static final String REACT_PROMPT_INSTRUCTION_TITLE = "react.prompt.instruction.title";
    public static final String REACT_PROMPT_NATIVE_RULES = "react.prompt.native.rules";
    public static final String REACT_PROMPT_NATIVE_NO_TOOLS = "react.prompt.native.no_tools";
    /** 占位符：{@code {finishMarker}} */
    public static final String REACT_PROMPT_CLASSIC_FORMAT = "react.prompt.classic.format";
    /** 占位符：{@code {finishMarker}} */
    public static final String REACT_PROMPT_CLASSIC_FINAL = "react.prompt.classic.final";
    /** 占位符：{@code {finishMarker}} */
    public static final String REACT_PROMPT_CLASSIC_RULES = "react.prompt.classic.rules";
    /** 占位符：{@code {finishMarker}} */
    public static final String REACT_PROMPT_CLASSIC_EXAMPLE = "react.prompt.classic.example";
    public static final String REACT_PROMPT_CLASSIC_NO_TOOLS = "react.prompt.classic.no_tools";
    public static final String REACT_PROMPT_CLASSIC_TOOLS_TITLE = "react.prompt.classic.tools.title";
    public static final String REACT_PROMPT_CLASSIC_TOOLS_SCHEMA = "react.prompt.classic.tools.schema";

    // ---- Simple main prompt ----
    /** 占位符：{@code {role}} */
    public static final String SIMPLE_PROMPT_ROLE = "simple.prompt.role";
    public static final String SIMPLE_PROMPT_INSTRUCTION_TITLE = "simple.prompt.instruction.title";

    // ---- ReAct runtime: ReasonTask ----
    /** 占位符：{@code {turn}} */
    public static final String REACT_REASON_RETHINK = "react.reason.rethink";
    /** 占位符：{@code {turn}}, {@code {attempt}} */
    public static final String REACT_REASON_FORMAT_FIX = "react.reason.format_fix";
    /** 占位符：{@code {turn}}, {@code {attempt}} */
    public static final String REACT_REASON_REFLECT = "react.reason.reflect";
    public static final String REACT_REASON_EMPTY_EXHAUSTED = "react.reason.empty_exhausted";
    /** 占位符：{@code {code}} */
    public static final String REACT_REASON_HTTP_RATE_LIMIT = "react.reason.http.rate_limit";
    /** 占位符：{@code {code}} */
    public static final String REACT_REASON_HTTP_UPSTREAM_UNAVAILABLE = "react.reason.http.upstream_unavailable";
    /** 占位符：{@code {code}} */
    public static final String REACT_REASON_HTTP_SERVER_ERROR = "react.reason.http.server_error";
    /** 占位符：{@code {code}} */
    public static final String REACT_REASON_HTTP_TIMEOUT = "react.reason.http.timeout";
    /** 占位符：{@code {code}} */
    public static final String REACT_REASON_HTTP_OTHER = "react.reason.http.other";
    public static final String REACT_REASON_ERROR_NO_RETURN = "react.reason.error.no_return";
    public static final String REACT_REASON_ERROR_SSL = "react.reason.error.ssl";
    public static final String REACT_REASON_ERROR_DNS = "react.reason.error.dns";
    public static final String REACT_REASON_ERROR_TIMEOUT = "react.reason.error.timeout";
    public static final String REACT_REASON_ERROR_CONNECTION_REFUSED = "react.reason.error.connection_refused";
    public static final String REACT_REASON_ERROR_CONNECTION = "react.reason.error.connection";
    public static final String REACT_REASON_ERROR_NETWORK = "react.reason.error.network";
    public static final String REACT_REASON_ERROR_OTHER = "react.reason.error.other";

    // ---- ReAct runtime: plan board ----
    public static final String REACT_PLAN_BOARD_TITLE = "react.plan.board.title";
    public static final String REACT_PLAN_BOARD_PROTOCOL = "react.plan.board.protocol";
    /** 占位符：{@code {step}} */
    public static final String REACT_PLAN_BOARD_CURRENT = "react.plan.board.current";
    /** 占位符：{@code {nextStep}} */
    public static final String REACT_PLAN_BOARD_ADVANCE_LAST = "react.plan.board.advance.last";
    /** 占位符：{@code {nextStep}} */
    public static final String REACT_PLAN_BOARD_ADVANCE_NEXT = "react.plan.board.advance.next";
    public static final String REACT_PLAN_BOARD_REVISE = "react.plan.board.revise";
    public static final String REACT_PLAN_BOARD_NO_SKIP = "react.plan.board.no_skip";
    public static final String REACT_PLAN_BOARD_DONE = "react.plan.board.done";

    // ---- ReAct runtime: PlanTalent ----
    public static final String REACT_PLAN_TALENT_DESCRIPTION = "react.plan.talent.description";
    public static final String REACT_PLAN_TALENT_GUIDE = "react.plan.talent.guide";
    public static final String REACT_PLAN_TALENT_STRATEGY = "react.plan.talent.strategy";
    public static final String REACT_PLAN_TOOL_CREATE_DESCRIPTION = "react.plan.tool.create.description";
    public static final String REACT_PLAN_TOOL_CREATE_STEPS = "react.plan.tool.create.steps";
    public static final String REACT_PLAN_TOOL_PROGRESS_DESCRIPTION = "react.plan.tool.progress.description";
    public static final String REACT_PLAN_TOOL_PROGRESS_NEXT_PLAN_INDEX = "react.plan.tool.progress.next_plan_index";
    public static final String REACT_PLAN_TOOL_REVISE_DESCRIPTION = "react.plan.tool.revise.description";
    public static final String REACT_PLAN_TOOL_REVISE_NEW_STEPS = "react.plan.tool.revise.new_steps";
    public static final String REACT_PLAN_TOOL_REVISE_FROM_INDEX = "react.plan.tool.revise.from_index";
    public static final String REACT_PLAN_RESULT_CREATE_IGNORED = "react.plan.result.create.ignored";
    /** 占位符：{@code {count}} */
    public static final String REACT_PLAN_RESULT_CREATE_OK = "react.plan.result.create.ok";
    public static final String REACT_PLAN_RESULT_PROGRESS_NOT_PLANNING = "react.plan.result.progress.not_planning";
    /** 占位符：{@code {step}}, {@code {desc}} */
    public static final String REACT_PLAN_RESULT_PROGRESS_NEXT = "react.plan.result.progress.next";
    public static final String REACT_PLAN_RESULT_PROGRESS_DONE = "react.plan.result.progress.done";
    public static final String REACT_PLAN_RESULT_REVISE_NOT_INIT = "react.plan.result.revise.not_init";
    public static final String REACT_PLAN_RESULT_REVISE_EMPTY = "react.plan.result.revise.empty";
    /** 占位符：{@code {fromIndex}} */
    public static final String REACT_PLAN_RESULT_REVISE_OK = "react.plan.result.revise.ok";

    // ---- ReAct runtime: StopLoopInterceptor ----
    /** 占位符：{@code {window}}, {@code {count}} */
    public static final String REACT_STOPLOOP_PROMPT = "react.stoploop.prompt";

    // ---- ReAct runtime: HITL ----
    /** 占位符：{@code {count}} */
    public static final String REACT_HITL_PENDING_BATCH = "react.hitl.pending_batch";
    public static final String REACT_HITL_SKIPPED = "react.hitl.skipped";
    public static final String REACT_HITL_REJECTED = "react.hitl.rejected";
    public static final String REACT_HITL_SENSITIVE = "react.hitl.sensitive";

    // ---- ReAct runtime: FeedbackTool ----
    public static final String REACT_FEEDBACK_DESCRIPTION = "react.feedback.description";
    public static final String REACT_FEEDBACK_REASON = "react.feedback.reason";

    // ================= team.yml =================

    // ---- supervisor main prompt ----
    public static final String TEAM_PROMPT_ROLE_DEFAULT = "team.prompt.role.default";
    /** 占位符：{@code {role}} */
    public static final String TEAM_PROMPT_ROLE = "team.prompt.role";
    public static final String TEAM_PROMPT_MEMBERS_TITLE = "team.prompt.members.title";
    /** 占位符：{@code {task}} */
    public static final String TEAM_PROMPT_TASK = "team.prompt.task";
    /** 占位符：{@code {finishMarker}} */
    public static final String TEAM_PROMPT_OUTPUT = "team.prompt.output";
    /** 占位符：{@code {finishMarker}} */
    public static final String TEAM_PROMPT_GUIDE = "team.prompt.guide";
    public static final String TEAM_PROMPT_INSTRUCTION_TITLE = "team.prompt.instruction.title";

    // ---- protocol base (TeamProtocolBase) ----
    public static final String TEAM_BASE_AGENT_IDENTITY = "team.base.agent.identity";
    /** 占位符：{@code {window}} */
    public static final String TEAM_BASE_AGENT_HISTORY = "team.base.agent.history";
    public static final String TEAM_BASE_AGENT_FOOTER = "team.base.agent.footer";
    /** 占位符：{@code {protocol}}, {@code {candidates}}, {@code {finishMarker}} */
    public static final String TEAM_BASE_SUPERVISOR_PROTOCOL = "team.base.supervisor.protocol";

    // ---- agent run in team (Agent.run) ; the "[Skipped]" tag is a language-neutral marker ----
    /** 占位符：{@code {interceptor}} */
    public static final String TEAM_RUN_SKIPPED = "team.run.skipped";
    /** 占位符：{@code {agent}} */
    public static final String TEAM_RUN_NO_CONTENT = "team.run.no_content";

    // ---- supervisor task (SupervisorTask) ----
    /** 占位符：{@code {interceptor}} */
    public static final String TEAM_SUPERVISOR_SKIPPED = "team.supervisor.skipped";
    /** 占位符：{@code {window}} */
    public static final String TEAM_SUPERVISOR_PROGRESS = "team.supervisor.progress";
    /** 占位符：{@code {turn}} */
    public static final String TEAM_SUPERVISOR_TURN = "team.supervisor.turn";
    /** 占位符：{@code {finishMarker}} */
    public static final String TEAM_SUPERVISOR_COMMAND = "team.supervisor.command";
    /** 占位符：{@code {agents}} */
    public static final String TEAM_SUPERVISOR_PENDING = "team.supervisor.pending";

    // ---- agent profile (AgentProfile.toFormatString) ----
    public static final String TEAM_PROFILE_INPUT_TEXT_ONLY = "team.profile.input.text_only";
    /** 占位符：{@code {modes}} */
    public static final String TEAM_PROFILE_INPUT_MODES = "team.profile.input.modes";
    public static final String TEAM_PROFILE_OUTPUT_IMAGE = "team.profile.output.image";
    /** 占位符：{@code {capabilities}} */
    public static final String TEAM_PROFILE_CAPABILITIES = "team.profile.capabilities";
    /** 占位符：{@code {constraints}} */
    public static final String TEAM_PROFILE_CONSTRAINTS = "team.profile.constraints";
    /** 占位符：{@code {style}} */
    public static final String TEAM_PROFILE_STYLE = "team.profile.style";

    // ---- A2A protocol ----
    public static final String TEAM_A2A_TOOL_TITLE = "team.a2a.tool.title";
    public static final String TEAM_A2A_TOOL_DESCRIPTION = "team.a2a.tool.description";
    /** 占位符：{@code {experts}} */
    public static final String TEAM_A2A_TOOL_PARAM_TARGET = "team.a2a.tool.param.target";
    public static final String TEAM_A2A_TOOL_PARAM_STATE = "team.a2a.tool.param.state";
    public static final String TEAM_A2A_TOOL_PARAM_MEMO = "team.a2a.tool.param.memo";
    /** 占位符：{@code {target}} */
    public static final String TEAM_A2A_TOOL_RESULT = "team.a2a.tool.result";
    public static final String TEAM_A2A_AGENT_GUIDE = "team.a2a.agent.guide";
    public static final String TEAM_A2A_AGENT_SESSION_TITLE = "team.a2a.agent.session.title";
    /** 占位符：{@code {path}} */
    public static final String TEAM_A2A_AGENT_SESSION_TRAIL = "team.a2a.agent.session.trail";
    /** 占位符：{@code {agent}} */
    public static final String TEAM_A2A_AGENT_SESSION_PRIOR = "team.a2a.agent.session.prior";
    /** 占位符：{@code {output}} */
    public static final String TEAM_A2A_AGENT_SESSION_PENDING = "team.a2a.agent.session.pending";
    /** 占位符：{@code {state}} */
    public static final String TEAM_A2A_AGENT_SESSION_STATE = "team.a2a.agent.session.state";
    public static final String TEAM_A2A_ROUTE_MAX_TRANSFER = "team.a2a.route.max_transfer";
    /** 占位符：{@code {target}} */
    public static final String TEAM_A2A_ROUTE_TRANSFER_FAILED = "team.a2a.route.transfer_failed";
    public static final String TEAM_A2A_DASHBOARD_TITLE = "team.a2a.dashboard.title";
    /** 占位符：{@code {state}} */
    public static final String TEAM_A2A_DASHBOARD_STATE = "team.a2a.dashboard.state";
    /** 占位符：{@code {count}} */
    public static final String TEAM_A2A_DASHBOARD_COUNT = "team.a2a.dashboard.count";
    /** 占位符：{@code {finishMarker}} */
    public static final String TEAM_A2A_SUPERVISOR_RULES = "team.a2a.supervisor.rules";

    // ---- Swarm protocol ----
    public static final String TEAM_SWARM_TOOL_TITLE = "team.swarm.tool.title";
    public static final String TEAM_SWARM_TOOL_DESCRIPTION = "team.swarm.tool.description";
    public static final String TEAM_SWARM_TOOL_PARAM_TASKS = "team.swarm.tool.param.tasks";
    public static final String TEAM_SWARM_TOOL_PARAM_AGENTS = "team.swarm.tool.param.agents";
    public static final String TEAM_SWARM_TOOL_RESULT = "team.swarm.tool.result";
    /** 占位符：{@code {tool}} */
    public static final String TEAM_SWARM_AGENT_GUIDE = "team.swarm.agent.guide";
    public static final String TEAM_SWARM_POOL_SNAPSHOT = "team.swarm.pool.snapshot";
    /** 占位符：{@code {finishMarker}} */
    public static final String TEAM_SWARM_SUPERVISOR_RULES = "team.swarm.supervisor.rules";
    public static final String TEAM_SWARM_DASHBOARD_TITLE = "team.swarm.dashboard.title";
    public static final String TEAM_SWARM_DASHBOARD_GUIDE = "team.swarm.dashboard.guide";
    /** 占位符：{@code {agent}} */
    public static final String TEAM_SWARM_DASHBOARD_RECOMMENDED = "team.swarm.dashboard.recommended";

    // ---- Sequential protocol ----
    public static final String TEAM_SEQUENTIAL_PROGRESS_TITLE = "team.sequential.progress.title";

    // ---- language-neutral recognition: assignment verbs in supervisor decisions (regex alternatives, separated by "|"; no "|" inside an item) ----
    public static final String TEAM_SUPERVISOR_ASSIGN_VERBS = "team.supervisor.assign_verbs";

    // ================= team-protocol.yml =================

    // ---- hierarchical: agent instruction ----
    public static final String PROTOCOL_HIERARCHICAL_AGENT_TITLE = "protocol.hierarchical.agent.title";
    public static final String PROTOCOL_HIERARCHICAL_AGENT_REPORT = "protocol.hierarchical.agent.report";
    /** 占位符：{@code {finishMarker}} */
    public static final String PROTOCOL_HIERARCHICAL_AGENT_FINISH_MARKER = "protocol.hierarchical.agent.finish_marker";

    // ---- hierarchical: agent prompt ----
    public static final String PROTOCOL_HIERARCHICAL_PROMPT_PRE_CONTEXT_TITLE = "protocol.hierarchical.prompt.pre_context.title";
    /** 占位符：{@code {agent}}, {@code {summary}} */
    public static final String PROTOCOL_HIERARCHICAL_PROMPT_PRE_CONTEXT_FROM = "protocol.hierarchical.prompt.pre_context.from";
    public static final String PROTOCOL_HIERARCHICAL_PROMPT_PRE_CONTEXT_HINT = "protocol.hierarchical.prompt.pre_context.hint";
    public static final String PROTOCOL_HIERARCHICAL_PROMPT_DIRECTIVE_TITLE = "protocol.hierarchical.prompt.directive.title";
    public static final String PROTOCOL_HIERARCHICAL_PROMPT_ERROR_TITLE = "protocol.hierarchical.prompt.error.title";
    /** 占位符：{@code {error}} */
    public static final String PROTOCOL_HIERARCHICAL_PROMPT_ERROR_FAILED = "protocol.hierarchical.prompt.error.failed";
    public static final String PROTOCOL_HIERARCHICAL_PROMPT_ERROR_FIX = "protocol.hierarchical.prompt.error.fix";
    public static final String PROTOCOL_HIERARCHICAL_PROMPT_MULTIMODAL = "protocol.hierarchical.prompt.multimodal";

    // ---- hierarchical: supervisor ----
    public static final String PROTOCOL_HIERARCHICAL_ROUTE_FINISH_BLOCKED = "protocol.hierarchical.route.finish_blocked";
    public static final String PROTOCOL_HIERARCHICAL_SUPERVISOR_DASHBOARD_TITLE = "protocol.hierarchical.supervisor.dashboard.title";
    public static final String PROTOCOL_HIERARCHICAL_SUPERVISOR_DASHBOARD_EMPTY = "protocol.hierarchical.supervisor.dashboard.empty";
    /** 占位符：{@code {finishMarker}} */
    public static final String PROTOCOL_HIERARCHICAL_SUPERVISOR_SOP = "protocol.hierarchical.supervisor.sop";
    /** 占位符：{@code {agents}} */
    public static final String PROTOCOL_HIERARCHICAL_SUPERVISOR_OVERWORKED = "protocol.hierarchical.supervisor.overworked";
    public static final String PROTOCOL_HIERARCHICAL_SUPERVISOR_GUIDELINES = "protocol.hierarchical.supervisor.guidelines";

    // ---- blackboard ----
    public static final String PROTOCOL_BLACKBOARD_TOOL_TITLE = "protocol.blackboard.tool.title";
    public static final String PROTOCOL_BLACKBOARD_TOOL_DESCRIPTION = "protocol.blackboard.tool.description";
    public static final String PROTOCOL_BLACKBOARD_TOOL_PARAM_RESULT = "protocol.blackboard.tool.param.result";
    public static final String PROTOCOL_BLACKBOARD_TOOL_PARAM_TODO = "protocol.blackboard.tool.param.todo";
    public static final String PROTOCOL_BLACKBOARD_TOOL_PARAM_COMPLETED_TODO = "protocol.blackboard.tool.param.completed_todo";
    public static final String PROTOCOL_BLACKBOARD_TOOL_PARAM_STATE = "protocol.blackboard.tool.param.state";
    public static final String PROTOCOL_BLACKBOARD_TOOL_UPDATED = "protocol.blackboard.tool.updated";
    /** 占位符：{@code {tool}} */
    public static final String PROTOCOL_BLACKBOARD_AGENT_GUIDELINES = "protocol.blackboard.agent.guidelines";
    public static final String PROTOCOL_BLACKBOARD_PROMPT_SNAPSHOT = "protocol.blackboard.prompt.snapshot";
    public static final String PROTOCOL_BLACKBOARD_SUPERVISOR_DASHBOARD_TITLE = "protocol.blackboard.supervisor.dashboard.title";
    public static final String PROTOCOL_BLACKBOARD_SUPERVISOR_GUIDELINES = "protocol.blackboard.supervisor.guidelines";

    // ---- contract net: tools ----
    public static final String PROTOCOL_CONTRACTNET_BID_TOOL_TITLE = "protocol.contractnet.bid_tool.title";
    public static final String PROTOCOL_CONTRACTNET_BID_TOOL_DESCRIPTION = "protocol.contractnet.bid_tool.description";
    public static final String PROTOCOL_CONTRACTNET_BID_TOOL_PARAM_SCORE = "protocol.contractnet.bid_tool.param.score";
    public static final String PROTOCOL_CONTRACTNET_BID_TOOL_PARAM_PLAN = "protocol.contractnet.bid_tool.param.plan";
    public static final String PROTOCOL_CONTRACTNET_BID_TOOL_PLAN_REQUIRED = "protocol.contractnet.bid_tool.plan_required";
    public static final String PROTOCOL_CONTRACTNET_BID_TOOL_RECEIVED = "protocol.contractnet.bid_tool.received";
    public static final String PROTOCOL_CONTRACTNET_CALL_TOOL_TITLE = "protocol.contractnet.call_tool.title";
    public static final String PROTOCOL_CONTRACTNET_CALL_TOOL_DESCRIPTION = "protocol.contractnet.call_tool.description";
    public static final String PROTOCOL_CONTRACTNET_CALL_TOOL_PARAM_REQUIREMENT = "protocol.contractnet.call_tool.param.requirement";
    public static final String PROTOCOL_CONTRACTNET_CALL_TOOL_EXHAUSTED = "protocol.contractnet.call_tool.exhausted";
    public static final String PROTOCOL_CONTRACTNET_CALL_TOOL_STARTED = "protocol.contractnet.call_tool.started";

    // ---- contract net: agent / routing ----
    /** 占位符：{@code {plan}} */
    public static final String PROTOCOL_CONTRACTNET_PROMPT_AWARD = "protocol.contractnet.prompt.award";
    /** 占位符：{@code {tool}} */
    public static final String PROTOCOL_CONTRACTNET_ROUTE_INVALID_ASSIGNMENT = "protocol.contractnet.route.invalid_assignment";
    public static final String PROTOCOL_CONTRACTNET_ROUTE_CANNOT_AWARD = "protocol.contractnet.route.cannot_award";

    // ---- contract net: supervisor ----
    /** 占位符：{@code {remaining}} */
    public static final String PROTOCOL_CONTRACTNET_SUPERVISOR_NO_BIDS_WARNING = "protocol.contractnet.supervisor.no_bids_warning";
    public static final String PROTOCOL_CONTRACTNET_SUPERVISOR_FINAL_WARNING = "protocol.contractnet.supervisor.final_warning";
    public static final String PROTOCOL_CONTRACTNET_SUPERVISOR_ROUNDS_USED_UP = "protocol.contractnet.supervisor.rounds_used_up";
    public static final String PROTOCOL_CONTRACTNET_SUPERVISOR_DASHBOARD_TITLE = "protocol.contractnet.supervisor.dashboard.title";
    /** 占位符：{@code {remaining}} */
    public static final String PROTOCOL_CONTRACTNET_SUPERVISOR_DASHBOARD_TIPS = "protocol.contractnet.supervisor.dashboard.tips";
    /** 占位符：{@code {tool}}, {@code {remaining}} */
    public static final String PROTOCOL_CONTRACTNET_SUPERVISOR_DASHBOARD_WAITING = "protocol.contractnet.supervisor.dashboard.waiting";
    public static final String PROTOCOL_CONTRACTNET_SUPERVISOR_PROFILES_TITLE = "protocol.contractnet.supervisor.profiles.title";
    /** 占位符：{@code {tool}} */
    public static final String PROTOCOL_CONTRACTNET_SUPERVISOR_GUIDELINES = "protocol.contractnet.supervisor.guidelines";

    // ---- market based ----
    public static final String PROTOCOL_MARKET_SUPERVISOR_MARKETPLACE_TITLE = "protocol.market.supervisor.marketplace.title";
    public static final String PROTOCOL_MARKET_SUPERVISOR_STRATEGY = "protocol.market.supervisor.strategy";
    /** 占位符：{@code {agent}} */
    public static final String PROTOCOL_MARKET_SUPERVISOR_ROI_KING = "protocol.market.supervisor.roi_king";
    /** 占位符：{@code {agent}} */
    public static final String PROTOCOL_MARKET_SUPERVISOR_BUDGET_PICK = "protocol.market.supervisor.budget_pick";
    public static final String PROTOCOL_MARKET_SUPERVISOR_GUIDELINES = "protocol.market.supervisor.guidelines";

    // ---- language-neutral recognition (matched across ALL known languages; items separated by "|", no "|" inside an item) ----
    public static final String PROTOCOL_CONTRACTNET_BIDDING_KEYWORDS = "protocol.contractnet.bidding_keywords";
    public static final String PROTOCOL_BLACKBOARD_COMPLETED_KEYWORDS = "protocol.blackboard.completed_keywords";

    // ================= compress.yml =================

    // ---- LLMCompressionStrategy ----
    public static final String COMPRESS_LLM_EMPTY_MARKER = "compress.llm.empty_marker";
    /** 占位符：{@code {emptyMarker}} */
    public static final String COMPRESS_LLM_SYSTEM = "compress.llm.system";
    public static final String COMPRESS_LLM_USER_PREFIX = "compress.llm.user_prefix";
    public static final String COMPRESS_LLM_USER_SUFFIX = "compress.llm.user_suffix";
    public static final String COMPRESS_LLM_SUMMARY_PREFIX = "compress.llm.summary_prefix";

    // ---- KeyInfoExtractionStrategy ----
    public static final String COMPRESS_KEYINFO_EMPTY_MARKER = "compress.keyinfo.empty_marker";
    /** 占位符：{@code {emptyMarker}} */
    public static final String COMPRESS_KEYINFO_SYSTEM = "compress.keyinfo.system";
    public static final String COMPRESS_KEYINFO_USER_PREFIX = "compress.keyinfo.user_prefix";
    public static final String COMPRESS_KEYINFO_USER_SUFFIX = "compress.keyinfo.user_suffix";
    public static final String COMPRESS_KEYINFO_SUMMARY_PREFIX = "compress.keyinfo.summary_prefix";

    // ---- HierarchicalCompressionStrategy ----
    public static final String COMPRESS_HIERARCHICAL_SYSTEM = "compress.hierarchical.system";
    public static final String COMPRESS_HIERARCHICAL_NO_SUMMARY = "compress.hierarchical.no_summary";
    /** 占位符：{@code {summary}}, {@code {history}} */
    public static final String COMPRESS_HIERARCHICAL_USER_DATA = "compress.hierarchical.user_data";
    public static final String COMPRESS_HIERARCHICAL_SUMMARY_PREFIX = "compress.hierarchical.summary_prefix";

    // ---- VectorStoreCompressionStrategy ----
    public static final String COMPRESS_VECTOR_DESCRIPTION = "compress.vector.description";
    public static final String COMPRESS_VECTOR_ARCHIVED_TAG = "compress.vector.archived_tag";
    /** 占位符：{@code {archivedTag}} */
    public static final String COMPRESS_VECTOR_INSTRUCTION = "compress.vector.instruction";
    /** 占位符：{@code {archivedTag}} */
    public static final String COMPRESS_VECTOR_ARCHIVED_NOTICE = "compress.vector.archived_notice";
    public static final String COMPRESS_VECTOR_TOOL_DESCRIPTION = "compress.vector.tool.description";
    public static final String COMPRESS_VECTOR_TOOL_PARAM_QUERY = "compress.vector.tool.param.query";
    public static final String COMPRESS_VECTOR_TOOL_PARAM_LIMIT = "compress.vector.tool.param.limit";
    public static final String COMPRESS_VECTOR_RECALL_EMPTY_QUERY = "compress.vector.recall.empty_query";
    public static final String COMPRESS_VECTOR_RECALL_INVALID_SESSION = "compress.vector.recall.invalid_session";
    /** 占位符：{@code {query}} */
    public static final String COMPRESS_VECTOR_RECALL_NOT_FOUND = "compress.vector.recall.not_found";
    public static final String COMPRESS_VECTOR_RECALL_FAILED = "compress.vector.recall.failed";

    // ---- CompressionUtil ----
    /** 占位符：{@code {tool}} */
    public static final String COMPRESS_UTIL_ACTION = "compress.util.action";
    /** 占位符：{@code {args}} */
    public static final String COMPRESS_UTIL_ACTION_ARGS = "compress.util.action_args";
    /** 占位符：{@code {content}} */
    public static final String COMPRESS_UTIL_OBSERVATION = "compress.util.observation";

    // ---- ContextCompressionInterceptor ----
    public static final String COMPRESS_INTERCEPTOR_TRUNCATED = "compress.interceptor.truncated";
    public static final String COMPRESS_INTERCEPTOR_TRUNCATED_SHORT = "compress.interceptor.truncated_short";
}
