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
package org.noear.solon.ai.harness.permission;

import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * 权限规则
 *
 * <p>将工具名映射到一种权限行为，并支持优先级控制。匹配方式二选一：
 * <ul>
 * <li><b>glob 模式</b>：通过 {@link #pattern()} 指定命令/路径的 glob 匹配</li>
 * <li><b>自定义匹配器</b>：通过 {@link #matcher()} 指定 {@link BiPredicate}，实现复杂匹配逻辑</li>
 * </ul>
 * 两者互斥，不能同时设置。</p>
 *
 * <p>可通过 {@link #prompt()} 设置规则的提示信息，供策略类生成友好的拦截提示。</p>
 *
 * @author noear
 * @since 4.0
 */
public class PermissionRule {
    private final String toolName;
    private final PermissionBehavior behavior;
    private final String pattern; // null 表示无模式，匹配全部（与 matcher 互斥）
    private final BiPredicate<String, Map<String, Object>> matcher; // null 表示无自定义匹配器
    private final String prompt; // 拦截时的提示信息
    private final int priority;

    /**
     * 构造函数
     *
     * @param toolName 工具名称（如 "bash", "write", "*"）
     * @param behavior 匹配后的权限行为
     * @param pattern  glob 模式（如 "git *", "cat *"），null 表示无模式
     */
    public PermissionRule(String toolName, PermissionBehavior behavior, String pattern) {
        this(toolName, behavior, pattern, 0);
    }

    /**
     * 构造函数（glob 模式版）
     *
     * @param toolName 工具名称（如 "bash", "write", "*"）
     * @param behavior 匹配后的权限行为
     * @param pattern  glob 模式（如 "git *", "cat *"），null 表示无模式
     * @param priority 优先级，数值越大越优先
     */
    public PermissionRule(String toolName, PermissionBehavior behavior, String pattern, int priority) {
        this.toolName = toolName;
        this.behavior = behavior;
        this.pattern = pattern;
        this.matcher = null;
        this.prompt = null;
        this.priority = priority;
    }

    /**
     * 构造函数（自定义匹配器版）
     *
     * @param toolName 工具名称（如 "bash", "write", "*"）
     * @param behavior 匹配后的权限行为
     * @param matcher  自定义匹配器（null 表示无自定义匹配器，使用默认的 glob 模式匹配）
     * @param prompt   拦截时的提示信息（null 表示使用默认提示）
     * @param priority 优先级，数值越大越优先
     */
    public PermissionRule(String toolName, PermissionBehavior behavior,
                          BiPredicate<String, Map<String, Object>> matcher,
                          String prompt, int priority) {
        this.toolName = toolName;
        this.behavior = behavior;
        this.pattern = null;
        this.matcher = matcher;
        this.prompt = prompt;
        this.priority = priority;
    }

    public String toolName() {
        return toolName;
    }

    public PermissionBehavior behavior() {
        return behavior;
    }

    /**
     * 获取 glob 模式（返回 Optional 视图，调用端无需处理 null）
     */
    public Optional<String> pattern() {
        return pattern != null ? Optional.of(pattern) : Optional.empty();
    }

    /**
     * 获取自定义匹配器
     */
    public Optional<BiPredicate<String, Map<String, Object>>> matcher() {
        return matcher != null ? Optional.of(matcher) : Optional.empty();
    }

    /**
     * 是否为自定义匹配器规则
     */
    public boolean hasMatcher() {
        return matcher != null;
    }

    /**
     * 获取提示信息
     */
    public String prompt() {
        return prompt;
    }

    public int priority() {
        return priority;
    }

    /**
     * 设置优先级，返回新规则实例
     */
    public PermissionRule priority(int priority) {
        return new PermissionRule(toolName, behavior, pattern, priority);
    }

    /**
     * 创建无模式规则
     */
    public static PermissionRule of(String toolName, PermissionBehavior behavior) {
        return new PermissionRule(toolName, behavior, null);
    }

    /**
     * 创建带 glob 模式的规则
     */
    public static PermissionRule withPattern(String toolName, PermissionBehavior behavior, String pattern) {
        return new PermissionRule(toolName, behavior, pattern);
    }

    /**
     * 创建带优先级的规则
     */
    public static PermissionRule of(String toolName, PermissionBehavior behavior, int priority) {
        return new PermissionRule(toolName, behavior, null, priority);
    }

    /**
     * 创建带 glob 模式和优先级的规则
     */
    public static PermissionRule withPattern(String toolName, PermissionBehavior behavior, String pattern, int priority) {
        return new PermissionRule(toolName, behavior, pattern, priority);
    }

    /**
     * 创建带自定义匹配器和提示信息的规则
     *
     * @param toolName 工具名称
     * @param behavior 匹配后的权限行为
     * @param matcher  自定义匹配器（toolName + args → boolean）
     * @param prompt   拦截时的提示信息，null 表示由策略生成动态提示
     * @param priority 优先级，数值越大越优先
     */
    public static PermissionRule of(String toolName, PermissionBehavior behavior,
                                    BiPredicate<String, Map<String, Object>> matcher,
                                    String prompt, int priority) {
        return new PermissionRule(toolName, behavior, matcher, prompt, priority);
    }

    // 便捷工厂：放行
    public static PermissionRule allow(String toolName) {
        return of(toolName, PermissionBehavior.ALLOW);
    }

    /**
     * 便捷工厂：放行（带 glob 模式）
     */
    public static PermissionRule allow(String toolName, String pattern) {
        return withPattern(toolName, PermissionBehavior.ALLOW, pattern);
    }

    /**
     * 便捷工厂：拒绝
     */
    public static PermissionRule deny(String toolName) {
        return of(toolName, PermissionBehavior.DENY);
    }

    /**
     * 便捷工厂：拒绝（带 glob 模式）
     */
    public static PermissionRule deny(String toolName, String pattern) {
        return withPattern(toolName, PermissionBehavior.DENY, pattern);
    }

    /**
     * 便捷工厂：询问
     */
    public static PermissionRule ask(String toolName) {
        return of(toolName, PermissionBehavior.ASK);
    }

    /**
     * 便捷工厂：询问（带 glob 模式）
     */
    public static PermissionRule ask(String toolName, String pattern) {
        return withPattern(toolName, PermissionBehavior.ASK, pattern);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PermissionRule that = (PermissionRule) o;

        if (priority != that.priority) return false;
        if (!toolName.equals(that.toolName)) return false;
        if (behavior != that.behavior) return false;
        if (pattern != null ? !pattern.equals(that.pattern) : that.pattern != null) return false;
        if (matcher != null ? !matcher.equals(that.matcher) : that.matcher != null) return false;
        return prompt != null ? prompt.equals(that.prompt) : that.prompt == null;
    }

    @Override
    public int hashCode() {
        int result = toolName.hashCode();
        result = 31 * result + behavior.hashCode();
        result = 31 * result + (pattern != null ? pattern.hashCode() : 0);
        result = 31 * result + (matcher != null ? matcher.hashCode() : 0);
        result = 31 * result + (prompt != null ? prompt.hashCode() : 0);
        result = 31 * result + priority;
        return result;
    }

    @Override
    public String toString() {
        return "PermissionRule{" +
                "toolName='" + toolName + '\'' +
                ", behavior=" + behavior +
                ", pattern=" + (pattern != null ? "'" + pattern + "'" : "null") +
                ", matcher=" + (matcher != null ? "custom" : "null") +
                ", prompt=" + (prompt != null ? "'" + prompt + "'" : "null") +
                ", priority=" + priority +
                '}';
    }
}
