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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 权限评估引擎
 *
 * <p>评估工具调用请求是否应被放行、拒绝或需要人工介入。
 * 评估优先级：DENY &gt; ALLOW &gt; ASK &gt; 模式默认。</p>
 *
 * <p>内部维护写工具白名单。</p>
 *
 * @author noear
 * @since 4.0
 */
public class PermissionEngine {

    /** 写工具列表（用于 PLAN 模式自动拒绝） */
    private static final List<String> WRITE_TOOLS = Collections.unmodifiableList(
        Arrays.asList("bash", "write", "edit", "rm", "mv", "cp", "mkdir")
    );

    /** 常见参数字段（用于模式匹配文本提取） */
    private static final List<String> ARG_FIELDS = Collections.unmodifiableList(
        Arrays.asList("command", "file_path", "path", "content", "url", "link")
    );

    /**
     * 评估工具调用
     *
 * <p>评估策略：
 * <ol>
 * <li><b>DENY 最高优先级</b>：遍历所有规则，任何匹配的 DENY 规则立即拒绝。</li>
 * <li><b>ALLOW/ASK 按规则顺序</b>：首个匹配的非 DENY 规则决定结果。</li>
 * <li><b>模式降级</b>：无规则匹配时，按 PermissionMode 返回默认决策。</li>
 * </ol>
     *
     * @param toolName 工具名称
     * @param args     工具参数
     * @param context  权限上下文（含模式 + 规则）
     * @return 决策结果
     */
    public PermissionDecision evaluate(String toolName, Map<String, Object> args,
                                        PermissionContext context) {
        List<PermissionRule> rules = context.rules();

        // 1. DENY 最高优先级：任何匹配的 DENY 规则立即拒绝
        for (PermissionRule rule : rules) {
            if (rule.behavior() == PermissionBehavior.DENY &&
                matchesToolName(rule.toolName(), toolName) && matchesPattern(rule, args)) {
                return PermissionDecision.DENY;
            }
        }

        // 2. 按规则顺序扫描，首个 ALLOW/ASK 匹配返回
        for (PermissionRule rule : rules) {
            if (rule.behavior() == PermissionBehavior.DENY) continue;    // DENY 已处理

            if (matchesToolName(rule.toolName(), toolName) && matchesPattern(rule, args)) {
                return rule.behavior() == PermissionBehavior.ALLOW
                    ? PermissionDecision.ALLOW
                    : PermissionDecision.ASK;
            }
        }

        // 3. 无规则匹配时，按模式降级
        return evaluateByMode(toolName, context.mode());
    }

    /**
     * 检查规则工具名是否匹配。支持 "*" 通配。
     */
    private boolean matchesToolName(String ruleToolName, String toolName) {
        if ("*".equals(ruleToolName)) {
            return true;
        }
        return ruleToolName.equalsIgnoreCase(toolName);
    }

    /**
     * 检查规则的 glob 模式是否匹配参数
     */
    private boolean matchesPattern(PermissionRule rule, Map<String, Object> args) {
        Optional<String> patternOpt = rule.pattern();
        if (!patternOpt.isPresent()) {
            return true; // 无模式则匹配全部
        }
        String pattern = patternOpt.get();
        String inputText = extractInputText(args);
        if (inputText == null) {
            return false;
        }
        String regex = globToRegex(pattern);
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(inputText).matches();
    }

    /**
     * 从工具参数中提取文本用于模式匹配
     */
    private String extractInputText(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        // 尝试常见参数字段
        for (String field : ARG_FIELDS) {
            Object val = args.get(field);
            if (val instanceof String) {
                return (String) val;
            }
        }
        return args.toString();
    }

    /**
     * 将简单 glob 模式转换为正则表达式
     */
    static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append(".");
                    break;
                case '.':
                    regex.append("\\.");
                    break;
                case '\\':
                    regex.append("\\\\");
                    break;
                case '(':
                    regex.append("\\(");
                    break;
                case ')':
                    regex.append("\\)");
                    break;
                case '[':
                    regex.append("\\[");
                    break;
                case ']':
                    regex.append("\\]");
                    break;
                case '{':
                    regex.append("\\{");
                    break;
                case '}':
                    regex.append("\\}");
                    break;
                case '^':
                    regex.append("\\^");
                    break;
                case '$':
                    regex.append("\\$");
                    break;
                case '|':
                    regex.append("\\|");
                    break;
                case '+':
                    regex.append("\\+");
                    break;
                default:
                    regex.append(c);
                    break;
            }
        }
        return regex.toString();
    }

    /**
     * 按权限模式降级决策
     */
    private PermissionDecision evaluateByMode(String toolName, PermissionMode mode) {
        switch (mode) {
            case UNLIMITED:
                return PermissionDecision.ALLOW;
            case READ_ONLY:
                return isWriteTool(toolName) ? PermissionDecision.DENY : PermissionDecision.ALLOW;
            default:
                return PermissionDecision.ASK; // 无规则时默认询问
        }
    }

    /**
     * 判断工具是否为写操作
     */
    private boolean isWriteTool(String toolName) {
        String toolLower = toolName.toLowerCase();
        for (String w : WRITE_TOOLS) {
            if (w.equals(toolLower)) {
                return true;
            }
        }
        return false;
    }
}
