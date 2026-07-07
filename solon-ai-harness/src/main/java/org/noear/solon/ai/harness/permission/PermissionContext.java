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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 权限上下文（不可变）
 *
 * <p>包含活跃规则列表、工作目录和附加目录。所有变更方法返回新实例（不可变更新模式）。</p>
 *
 * @author noear
 * @since 4.0
 */
public class PermissionContext {
    private final Path workingDirectory;
    private final List<PermissionRule> rules;
    private final List<Path> additionalDirs;

    /**
     * 构造函数
     *
     * @param workingDirectory 工作目录
     * @param rules 权限规则列表
     * @param additionalDirs 附加目录列表
     */
    public PermissionContext(Path workingDirectory, List<PermissionRule> rules, List<Path> additionalDirs) {
        this.workingDirectory = workingDirectory;
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
        this.additionalDirs = Collections.unmodifiableList(new ArrayList<>(additionalDirs));
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public List<PermissionRule> rules() {
        return rules;
    }

    public List<Path> additionalDirs() {
        return additionalDirs;
    }

    /**
     * 创建默认上下文（工作目录为当前目录）
     */
    public static PermissionContext create() {
        return new PermissionContext(Paths.get("."),
            Collections.<PermissionRule>emptyList(), Collections.<Path>emptyList());
    }

    /**
     * 创建最小上下文（只需工作目录）
     */
    public static PermissionContext of(Path workingDirectory) {
        return new PermissionContext(workingDirectory,
            Collections.<PermissionRule>emptyList(), Collections.<Path>emptyList());
    }

    /**
     * 转换为 Builder
     */
    public Builder toBuilder() {
        return new Builder()
            .workingDirectory(workingDirectory)
            .rules(rules)
            .additionalDirs(additionalDirs);
    }

    /**
     * 设置工作目录
     */
    public PermissionContext withWorkingDirectory(Path newWorkingDirectory) {
        return new PermissionContext(newWorkingDirectory, rules, additionalDirs);
    }

    /**
     * 添加规则
     */
    public PermissionContext addRule(PermissionRule rule) {
        List<PermissionRule> merged = new ArrayList<>(this.rules);
        merged.add(rule);
        return new PermissionContext(workingDirectory, merged, additionalDirs);
    }

    /**
     * 批量添加规则
     */
    public PermissionContext addRules(List<PermissionRule> newRules) {
        List<PermissionRule> merged = new ArrayList<>(this.rules);
        merged.addAll(newRules);
        return new PermissionContext(workingDirectory, merged, additionalDirs);
    }

    /**
     * 替换全部规则
     */
    public PermissionContext replaceRules(List<PermissionRule> newRules) {
        return new PermissionContext(workingDirectory, newRules, additionalDirs);
    }

    /**
     * 按条件移除规则
     */
    public PermissionContext removeRules(Predicate<PermissionRule> filter) {
        List<PermissionRule> remaining = this.rules.stream()
            .filter(filter.negate())
            .collect(Collectors.toList());
        return new PermissionContext(workingDirectory, remaining, additionalDirs);
    }

    /**
     * 添加附加目录
     */
    public PermissionContext addDirectories(List<Path> dirs) {
        List<Path> merged = new ArrayList<>(this.additionalDirs);
        merged.addAll(dirs);
        return new PermissionContext(workingDirectory, rules, merged);
    }

    /**
     * 移除指定目录
     */
    public PermissionContext removeDirectories(List<Path> dirs) {
        List<Path> remaining = this.additionalDirs.stream()
            .filter(d -> !dirs.contains(d))
            .collect(Collectors.toList());
        return new PermissionContext(workingDirectory, rules, remaining);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PermissionContext that = (PermissionContext) o;

        if (!workingDirectory.equals(that.workingDirectory)) return false;
        if (!rules.equals(that.rules)) return false;
        return additionalDirs.equals(that.additionalDirs);
    }

    @Override
    public int hashCode() {
        int result = workingDirectory.hashCode();
        result = 31 * result + rules.hashCode();
        result = 31 * result + additionalDirs.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "PermissionContext{" +
                "workingDirectory=" + workingDirectory +
                ", rules=" + rules +
                ", additionalDirs=" + additionalDirs +
                '}';
    }

    // ========== Builder ==========

    public static class Builder {
        private Path workingDirectory = Paths.get(".");
        private List<PermissionRule> rules = new ArrayList<>();
        private List<Path> additionalDirs = new ArrayList<>();

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder rules(List<PermissionRule> rules) {
            this.rules = new ArrayList<>(rules);
            return this;
        }

        public Builder addRule(PermissionRule rule) {
            this.rules.add(rule);
            return this;
        }

        public Builder additionalDirs(List<Path> additionalDirs) {
            this.additionalDirs = new ArrayList<>(additionalDirs);
            return this;
        }

        public Builder addDir(Path dir) {
            this.additionalDirs.add(dir);
            return this;
        }

        public PermissionContext build() {
            return new PermissionContext(workingDirectory, rules, additionalDirs);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
