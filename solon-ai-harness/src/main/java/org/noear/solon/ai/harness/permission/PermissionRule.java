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

import java.util.Optional;

/**
 * 权限规则
 *
 * <p>将工具名（含可选 glob 模式）映射到一种权限行为，并支持优先级控制。</p>
 *
 * @author noear
 * @since 4.0
 */
public class PermissionRule {
    private final String toolName;
    private final PermissionBehavior behavior;
    private final String pattern; // null 表示无模式，匹配全部
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
     * 构造函数
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
        return pattern != null ? pattern.equals(that.pattern) : that.pattern == null;
    }

    @Override
    public int hashCode() {
        int result = toolName.hashCode();
        result = 31 * result + behavior.hashCode();
        result = 31 * result + (pattern != null ? pattern.hashCode() : 0);
        result = 31 * result + priority;
        return result;
    }

    @Override
    public String toString() {
        return "PermissionRule{" +
                "toolName='" + toolName + '\'' +
                ", behavior=" + behavior +
                ", pattern=" + (pattern != null ? "'" + pattern + "'" : "null") +
                ", priority=" + priority +
                '}';
    }
}
