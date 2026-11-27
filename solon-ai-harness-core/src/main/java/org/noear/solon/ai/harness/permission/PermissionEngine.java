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
package org.noear.solon.ai.harness.permission;

import org.noear.solon.ai.talents.cli.TerminalTalent;

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
 * <p>glob 模式：{@code *} 匹配任意字符（含换行），{@code ?} 匹配单个字符，不区分大小写；
 * 结尾的 {@code " *"} 也匹配空（{@code git log *} 匹配 {@code git log}，与 opencode 一致）。</p>
 *
 * <p>shell 命令（{@code bash} / {@code bash_start} 的 {@code command}）在配置了 glob 规则时按段判定
 * （见 {@link ShellCommandLine}）：每段各自取第一条命中的规则，任一段 DENY 即 DENY，任一段 ASK 即 ASK，
 * 全部 ALLOW 才 ALLOW——{@code git *} 放行不了 {@code git log; rm -rf x}，{@code rm *} 拒绝也躲不过 {@code ls && rm x}。
 * 自定义匹配器（如记住的审批）始终按整条命令判断。glob 放行只担保它写明的命令本身：
 * 命令含写文件的重定向或变量展开、或无法静态切分（命令替换、子 shell 等）时，靠 glob 得出的 ALLOW 降为 ASK。
 * 未配置 glob 规则时与整条匹配完全一致。</p>
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
    public PermissionBehavior evaluate(String toolName, Map<String, Object> args,
                                        PermissionContext context,
                                        PermissionBehavior defaultDecision) {
        List<PermissionRule> rules = new ArrayList<>(context.rules());
        Collections.sort(rules, RULE_COMPARATOR);

        String command = shellCommandOf(toolName, args);
        if (command != null && hasGlobRule(rules, toolName)) {
            return evaluateShell(rules, toolName, args, command, defaultDecision);
        }

        PermissionRule hit = firstMatch(rules, toolName, args, extractInputText(args));
        return behaviorOf(hit, defaultDecision);
    }

    /**
     * shell 命令按段判定（见类注释）
     */
    private PermissionBehavior evaluateShell(List<PermissionRule> rules, String toolName, Map<String, Object> args,
                                             String command, PermissionBehavior defaultDecision) {
        ShellCommandLine line = ShellCommandLine.parse(command);

        if (line.isOpaque()) {
            // 无法切分：整条匹配；拒绝照常，glob 不能担保放行
            PermissionRule hit = firstMatch(rules, toolName, args, command);
            PermissionBehavior behavior = behaviorOf(hit, defaultDecision);
            if (behavior == PermissionBehavior.ALLOW && (hit == null || hit.hasMatcher() == false)) {
                return PermissionBehavior.ASK;
            }
            return behavior;
        }

        List<String> texts = line.getSegmentTexts();
        if (texts.isEmpty()) {
            return behaviorOf(firstMatch(rules, toolName, args, command), defaultDecision);
        }

        boolean anyAsk = false;
        boolean globAllow = false;
        for (String text : texts) {
            PermissionRule hit = firstMatch(rules, toolName, args, text);
            PermissionBehavior behavior = behaviorOf(hit, defaultDecision);
            if (behavior == PermissionBehavior.DENY) {
                return PermissionBehavior.DENY;
            }
            if (behavior == PermissionBehavior.ASK) {
                anyAsk = true;
            } else if (behavior == PermissionBehavior.ALLOW && isGlob(hit)) {
                globAllow = true;
            }
        }

        if (anyAsk) {
            return PermissionBehavior.ASK;
        }
        if (globAllow && (line.hasWriteRedirect() || line.hasExpansion())) {
            return PermissionBehavior.ASK;
        }
        return PermissionBehavior.ALLOW;
    }

    /**
     * 按排序后的规则取第一条命中的（text 为 glob 匹配的文本，自定义匹配器看完整参数）
     */
    private PermissionRule firstMatch(List<PermissionRule> rules, String toolName, Map<String, Object> args, String text) {
        for (PermissionRule rule : rules) {
            if (matchesToolName(rule.toolName(), toolName) && matchesPattern(rule, toolName, args, text)) {
                return rule;
            }
        }
        return null;
    }

    private static PermissionBehavior behaviorOf(PermissionRule hit, PermissionBehavior defaultDecision) {
        if (hit == null) {
            return defaultDecision;
        }
        return hit.behavior() != null ? hit.behavior() : PermissionBehavior.ASK;
    }

    private static boolean isGlob(PermissionRule rule) {
        return rule != null && rule.hasMatcher() == false && rule.pattern().isPresent();
    }

    private boolean hasGlobRule(List<PermissionRule> rules, String toolName) {
        for (PermissionRule rule : rules) {
            if (isGlob(rule) && matchesToolName(rule.toolName(), toolName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * shell 工具的命令原文；非 shell 工具或没有命令时返回 null
     */
    private static String shellCommandOf(String toolName, Map<String, Object> args) {
        if (args == null || (TerminalTalent.TOOL_BASH.equalsIgnoreCase(toolName) == false
                && TerminalTalent.TOOL_BASH_START.equalsIgnoreCase(toolName) == false)) {
            return null;
        }
        Object command = args.get(TerminalTalent.PARAM_COMMAND);
        if (command instanceof String && ((String) command).trim().isEmpty() == false) {
            return (String) command;
        }
        return null;
    }

    /**
     * 评估工具调用（无规则匹配时默认返回 ASK）
     *
     * @see #evaluate(String, Map, PermissionContext, PermissionBehavior)
     */
    public PermissionBehavior evaluate(String toolName, Map<String, Object> args,
                                        PermissionContext context) {
        return evaluate(toolName, args, context, PermissionBehavior.ASK);
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
     * 检查规则是否匹配参数
     *
     * <p>匹配顺序：先检查自定义匹配器（{@link PermissionRule#hasMatcher()}），
     * 有则优先使用；否则走 glob 模式匹配。</p>
     */
    private boolean matchesPattern(PermissionRule rule, String toolName, Map<String, Object> args, String inputText) {
        // 1. 自定义匹配器优先
        if (rule.hasMatcher()) {
            return rule.matcher().get().test(toolName, args);
        }

        // 2. glob 模式匹配
        Optional<String> patternOpt = rule.pattern();
        if (!patternOpt.isPresent()) {
            return true; // 无模式则匹配全部
        }
        if (inputText == null) {
            return false;
        }
        String regex = globToRegex(patternOpt.get());
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(inputText).matches();
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
     * 将简单 glob 模式转换为正则表达式（结尾的 {@code " *"} 转为可选的 {@code ( .*)?}）
     */
    static String globToRegex(String glob) {
        if (glob.length() > 2 && glob.endsWith(" *")) {
            return globToRegex(glob.substring(0, glob.length() - 2)) + "( .*)?";
        }

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
