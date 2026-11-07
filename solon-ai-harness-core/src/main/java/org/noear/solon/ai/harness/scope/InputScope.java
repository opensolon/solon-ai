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
 * 输入级作用域（一次输入能用什么）
 *
 * <p>只能在引擎配置的基础上<b>收窄</b>，不能放大：工具名单与引擎/代理已挂载的工具取交集，
 * 技能与 MCP 名单是<b>强制边界</b>（{@code skillread} / {@code call_tool} 也受约束），
 * 不是视野。任一维度为 {@code null} 表示该维度不限制；空集合表示该维度全部禁止。
 * 技能名单只约束技能工具，不约束文件工具与 bash 对技能挂载点的访问；需要文件级隔离时，
 * 不要把名单外的技能挂进该引擎。</p>
 *
 * <p>生效路径：{@code HarnessInput.scope(...)} 或引擎级 {@link InputScopeResolver}（goal / loop / queue
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
public final class InputScope {
    /** 请求级属性（ReActOptions attrs）中的键 */
    public static final String ATTR_KEY = "harness.inputScope";

    private final String principal;
    private final Set<String> tools;
    private final Set<String> skills;
    private final Set<String> mcps;
    private final Set<String> commands;
    private final ChatModel chatModel;
    private final Map<String, Object> attrs;

    private InputScope(Builder b) {
        this.principal = b.principal;
        this.tools = b.tools == null ? null : Collections.unmodifiableSet(
                b.toolsRuntime ? new LinkedHashSet<>(b.tools) : ToolName.expandRuntime(b.tools));
        this.skills = b.skills == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(b.skills));
        this.mcps = b.mcps == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(b.mcps));
        this.commands = b.commands == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(b.commands));
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

    /**
     * 允许的命令名（不含前缀；null = 不限制）。名单外的命令不执行，返回「不可用」回执。
     *
     * @since 4.2.0
     */
    @Nullable
    public Set<String> getCommands() {
        return commands;
    }

    /** 是否允许执行某条命令（命令名不含前缀） */
    public boolean allowsCommand(@Nullable String commandName) {
        return commands == null || (commandName != null && commands.contains(commandName));
    }

    /**
     * 是否可以延后执行（随积压保存）：只含名单类维度时可序列化；含模型实例或任意属性时不可保存，
     * 忙态下这类输入被拒绝（{@code REJECT_SCOPE_NOT_DEFERRABLE}），不会悄悄丢掉这些维度。
     *
     * @since 4.2.0
     */
    public boolean isDeferrable() {
        return chatModel == null && attrs.isEmpty();
    }

    /**
     * 序列化为可保存的规格（只含名单类维度；null 维度不写）。调用前应先判 {@link #isDeferrable()}。
     *
     * @since 4.2.0
     */
    public Map<String, Object> toSpec() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("principal", principal);
        if (tools != null) m.put("tools", new ArrayList<>(tools));
        if (skills != null) m.put("skills", new ArrayList<>(skills));
        if (mcps != null) m.put("mcps", new ArrayList<>(mcps));
        if (commands != null) m.put("commands", new ArrayList<>(commands));
        return m;
    }

    /**
     * 从规格还原（{@link #toSpec()} 的逆）。工具名已是运行时名，原样还原、不再展开（再展开可能放宽）。
     *
     * @return 规格为空或缺主体时返回 null
     * @since 4.2.0
     */
    @Nullable
    public static InputScope fromSpec(@Nullable Map<String, Object> spec) {
        if (spec == null || !(spec.get("principal") instanceof String)) {
            return null;
        }
        Builder b = builder((String) spec.get("principal"));
        b.tools = listOf(spec.get("tools"));
        b.toolsRuntime = true;
        b.skills = listOf(spec.get("skills"));
        b.mcps = listOf(spec.get("mcps"));
        b.commands = listOf(spec.get("commands"));
        return b.build();
    }

    private static List<String> listOf(Object v) {
        if (!(v instanceof Collection)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object o : (Collection<?>) v) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    /**
     * 交集（只会变窄）：各名单维度 null = 不限，取两者交集；模型与属性取 {@code latest} 的。
     * 主体不同返回 null（不得把 A 的输入按 B 的授权执行）。
     *
     * <p>用于积压续发：入队时显式携带的作用域 ∩ 续发时解析器的最新结果。</p>
     *
     * @since 4.2.0
     */
    @Nullable
    public static InputScope intersect(InputScope saved, InputScope latest) {
        if (saved == null) return latest;
        if (latest == null) return saved;
        if (saved.principal.equals(latest.principal) == false) {
            return null;
        }
        Builder b = builder(saved.principal);
        b.tools = meet(saved.tools, latest.tools);
        b.toolsRuntime = true;
        b.skills = meet(saved.skills, latest.skills);
        b.mcps = meet(saved.mcps, latest.mcps);
        b.commands = meet(saved.commands, latest.commands);
        b.chatModel = latest.chatModel;
        b.attrs.putAll(latest.attrs);
        return b.build();
    }

    private static Set<String> meet(Set<String> x, Set<String> y) {
        if (x == null) return y;
        if (y == null) return x;
        Set<String> r = new LinkedHashSet<>(x);
        r.retainAll(y);
        return r;
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
    public static InputScope of(@Nullable Map<String, Object> attrs) {
        if (attrs == null) {
            return null;
        }

        Object v = attrs.get(ATTR_KEY);
        return v instanceof InputScope ? (InputScope) v : null;
    }

    /**
     * 从运行轨迹读取
     */
    @Nullable
    public static InputScope of(@Nullable ReActTrace trace) {
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
        return "InputScope{principal=" + principal + ", tools=" + tools + ", skills=" + skills + ", mcps=" + mcps
                + ", commands=" + commands + "}";
    }

    public static final class Builder {
        private final String principal;
        private Collection<String> tools;
        private Collection<String> skills;
        private Collection<String> mcps;
        private Collection<String> commands;
        /** tools 已是运行时名（还原 / 交集时），不再展开 */
        private boolean toolsRuntime;
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

        /**
         * 允许的命令名（不含前缀，如 {@code "model"}、{@code "goal"}）。未列出的命令不执行。
         *
         * @since 4.2.0
         */
        public Builder commands(String... names) {
            this.commands = names == null ? null : Arrays.asList(names);
            return this;
        }

        public Builder commands(@Nullable Collection<String> names) {
            this.commands = names;
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

        public InputScope build() {
            return new InputScope(this);
        }
    }
}
