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
package org.noear.solon.ai.harness.scope;

import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.talents.cli.SkillTalent;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.agent.ToolName;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.*;

/**
 * 请求级作用域（一轮请求能用什么）
 *
 * <p>只能在引擎配置的基础上<b>收窄</b>，不能放大：工具名单与引擎/代理已挂载的工具取交集，
 * 技能与 MCP 名单是<b>强制边界</b>（{@code skillread} / {@code call_tool} 也受约束），
 * 不是视野。任一维度为 {@code null} 表示该维度不限制；空集合表示该维度全部禁止。
 * 技能名单只约束技能工具，不约束文件工具与 bash 对技能挂载点的访问；需要文件级隔离时，
 * 不要把名单外的技能挂进该引擎。</p>
 *
 * <p>生效路径：{@code HarnessInput.scope(...)} 或引擎级 {@link TurnScopeResolver}（goal / loop / queue
 * 的程序输入同样经过 {@code handle}，因此自动继承）→ 请求级属性 {@link #ATTR_KEY}（不进 toolContext：
 * toolContext 会并入工具参数、经 MCP 发给第三方）→
 * 每个代理常驻的 {@link ScopeGuardInterceptor} 在工具执行前否决越界调用；委派子代理继承同一作用域。</p>
 *
 * <p>作用域是安全边界的一部分；对模型的「隐藏」只是体验优化，强制以拦截为准。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class TurnScope {
    /** 请求级属性（ReActOptions attrs）中的键 */
    public static final String ATTR_KEY = "harness.turnScope";

    private final String principal;
    private final Set<String> tools;
    private final Set<String> skills;
    private final Set<String> mcps;
    private final ChatModel chatModel;
    private final Map<String, Object> attrs;

    private TurnScope(Builder b) {
        this.principal = b.principal;
        this.tools = b.tools == null ? null : Collections.unmodifiableSet(ToolName.expandRuntime(b.tools));
        this.skills = b.skills == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(b.skills));
        this.mcps = b.mcps == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(b.mcps));
        this.chatModel = b.chatModel;
        this.attrs = Collections.unmodifiableMap(new LinkedHashMap<>(b.attrs));
    }

    public static Builder builder(String principal) {
        return new Builder(principal);
    }

    /** 主体标识（租户 / 用户，用于审计、配额、日志） */
    public String getPrincipal() {
        return principal;
    }

    /** 允许的运行时工具名（已展开分组；null = 不限制） */
    @Nullable
    public Set<String> getTools() {
        return tools;
    }

    /** 允许的技能（约束技能工具；null = 不限制） */
    @Nullable
    public Set<String> getSkills() {
        return skills;
    }

    /** 允许的 MCP 服务名（强制边界；null = 不限制） */
    @Nullable
    public Set<String> getMcps() {
        return mcps;
    }

    /** 本轮使用的模型（如租户自带 Key；null = 沿用引擎解析结果）。委派子代理同样使用它 */
    @Nullable
    public ChatModel getChatModel() {
        return chatModel;
    }

    public Map<String, Object> getAttrs() {
        return attrs;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T getAttr(String key) {
        return (T) attrs.get(key);
    }

    /**
     * 是否允许按名称调用某个运行时工具（只看名单，不含 MCP 动态工具的归属判断）
     */
    public boolean allowsToolName(String runtimeName) {
        return tools == null || tools.contains(runtimeName) || ToolName.isAlwaysAllowed(runtimeName);
    }

    /** 是否允许某个 MCP 服务 */
    public boolean allowsMcp(@Nullable String serverName) {
        return mcps == null || (serverName != null && mcps.contains(serverName));
    }

    /**
     * 从请求级属性表读取
     */
    @Nullable
    public static TurnScope of(@Nullable Map<String, Object> attrs) {
        if (attrs == null) {
            return null;
        }

        Object v = attrs.get(ATTR_KEY);
        return v instanceof TurnScope ? (TurnScope) v : null;
    }

    /**
     * 从运行轨迹读取
     */
    @Nullable
    public static TurnScope of(@Nullable ReActTrace trace) {
        if (trace == null || trace.getOptions() == null) {
            return null;
        }

        return of(trace.getOptions().getAttrs());
    }

    /**
     * 写进一次请求的选项：作用域对象放请求级属性（不进 toolContext——toolContext 会并入工具参数、
     * 经 MCP 发给第三方）；技能 / MCP 边界以名单形式进 toolContext，由对应 Talent 在执行侧强制；
     * 模型覆盖须在会话模型之后调用（{@code options()} 是 andThen 叠加，后写覆盖）。
     */
    public void applyTo(ReActOptionsAmend o) {
        o.attrPut(ATTR_KEY, this);

        if (skills != null) {
            o.toolContextPut(SkillTalent.CTX_SKILLS_ALLOWED, new ArrayList<>(skills));
        }

        if (mcps != null) {
            o.toolContextPut(McpGatewayTalent.CTX_MCPS_ALLOWED, new ArrayList<>(mcps));
        }

        if (chatModel != null) {
            o.chatModel(chatModel);
        }
    }

    @Override
    public String toString() {
        return "TurnScope{principal=" + principal + ", tools=" + tools + ", skills=" + skills + ", mcps=" + mcps + "}";
    }

    public static final class Builder {
        private final String principal;
        private Collection<String> tools;
        private Collection<String> skills;
        private Collection<String> mcps;
        private ChatModel chatModel;
        private final Map<String, Object> attrs = new LinkedHashMap<>();

        private Builder(String principal) {
            this.principal = Objects.requireNonNull(principal, "principal");
        }

        /**
         * 允许的内置工具：可写逻辑名（{@code bash}、{@code skill}、{@code mcp}，分组 {@code pi} / {@code *} / {@code **}）
         * 或运行时工具名。未列出的内置工具一律拒绝（时钟工具除外）。
         *
         * <p>名单只约束内置工具（{@link ToolName} 映射内的）与 MCP 工具；扩展自带的工具（如 {@code goal_update}）
         * 跟随扩展是否挂载 / 启用——否则按逻辑名写名单会把 goal 的完成工具拒掉，goal 只能空转到预算耗尽。
         * 不希望某类用户使用扩展时，不要给对应引擎挂该扩展。</p>
         */
        public Builder tools(String... names) {
            this.tools = names == null ? null : Arrays.asList(names);
            return this;
        }

        public Builder tools(@Nullable Collection<String> names) {
            this.tools = names;
            return this;
        }

        public Builder skills(String... names) {
            this.skills = names == null ? null : Arrays.asList(names);
            return this;
        }

        public Builder skills(@Nullable Collection<String> names) {
            this.skills = names;
            return this;
        }

        public Builder mcps(String... names) {
            this.mcps = names == null ? null : Arrays.asList(names);
            return this;
        }

        public Builder mcps(@Nullable Collection<String> names) {
            this.mcps = names;
            return this;
        }

        public Builder chatModel(@Nullable ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder attr(String key, Object value) {
            this.attrs.put(key, value);
            return this;
        }

        public TurnScope build() {
            return new TurnScope(this);
        }
    }
}
