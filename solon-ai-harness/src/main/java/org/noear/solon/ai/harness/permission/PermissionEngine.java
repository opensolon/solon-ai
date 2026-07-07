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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 权限评估引擎
 *
 * <p>评估工具调用请求是否应被放行、拒绝或需要人工介入。
 * 评估优先级：规则 priority 越大越优先；同 priority 下 DENY &gt; ALLOW &gt; ASK。</p>
 *
 * @author noear
 * @since 4.0
 */
public class PermissionEngine {

    /** 常见参数字段（用于模式匹配文本提取） */
    private static final List<String> ARG_FIELDS = Collections.unmodifiableList(
        Arrays.asList("command", "file_path", "path", "content", "url", "link")
    );

    /**
     * 评估工具调用
     *
     * <p>评估策略：
     * <ol>
     * <li><b>规则优先级</b>：priority 越大越优先。</li>
     * <li><b>同级安全兜底</b>：同 priority 下 DENY &gt; ALLOW &gt; ASK。</li>
     * <li><b>默认决策</b>：无规则匹配时返回 {@code defaultDecision}。</li>
     * </ol>
     *
     * @param toolName       工具名称
     * @param args           工具参数
     * @param context        权限上下文（含规则）
     * @param defaultDecision 无规则匹配时的默认决策
     * @return 决策结果
     */
    public PermissionDecision evaluate(String toolName, Map<String, Object> args,
                                        PermissionContext context,
                                        PermissionDecision defaultDecision) {
        List<PermissionRule> rules = new ArrayList<>(context.rules());
        Collections.sort(rules, RULE_COMPARATOR);

        for (PermissionRule rule : rules) {
            if (matchesToolName(rule.toolName(), toolName) && matchesPattern(rule, args)) {
                return toDecision(rule.behavior());
            }
        }

        return defaultDecision;
    }

    /**
     * 评估工具调用（无规则匹配时默认返回 ASK）
     *
     * @see #evaluate(String, Map, PermissionContext, PermissionDecision)
     */
    public PermissionDecision evaluate(String toolName, Map<String, Object> args,
                                        PermissionContext context) {
        return evaluate(toolName, args, context, PermissionDecision.ASK);
    }

    private static final Comparator<PermissionRule> RULE_COMPARATOR = new Comparator<PermissionRule>() {
        @Override
        public int compare(PermissionRule o1, PermissionRule o2) {
            int priorityCompare = Integer.compare(o2.priority(), o1.priority());
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return Integer.compare(behaviorWeight(o2.behavior()), behaviorWeight(o1.behavior()));
        }
    };

    private static int behaviorWeight(PermissionBehavior behavior) {
        switch (behavior) {
            case DENY:
                return 3;
            case ALLOW:
                return 2;
            case ASK:
                return 1;
            default:
                return 0;
        }
    }

    private PermissionDecision toDecision(PermissionBehavior behavior) {
        return behavior == PermissionBehavior.ALLOW
                ? PermissionDecision.ALLOW
                : behavior == PermissionBehavior.DENY ? PermissionDecision.DENY : PermissionDecision.ASK;
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


}
