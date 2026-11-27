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
package org.noear.solon.ai.harness.hitl;

import org.noear.solon.ai.harness.permission.PermissionBehavior;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bash 命令分类器
 *
 * <p>参考 claude-code-java 的 BashPermissions + BashTool 设计，对 bash 命令进行三级分类：
 * <ul>
 * <li><b>DENY</b> — 空命令或不完整命令（尾部有 |, &&, ||, ;）</li>
 * <li><b>ALLOW</b> — 只读/搜索命令（50+ 条白名单，串联的每一段都必须只读）</li>
 * <li><b>ASK</b> — 其他所有命令（交由人工确认）</li>
 * </ul>
 *
 * <p>只读判定按 shell 语法切分（识别单双引号与转义）：管道、{@code ; && || &} 与换行串联的每一段都必须是只读命令；
 * 命令替换、进程替换、子 shell、here-doc、写文件的重定向（{@code /dev/null} 与描述符复制除外）一律不算只读；
 * 白名单命令自带的写文件或执行能力（如 {@code find -delete/-exec}、{@code sed -i}、{@code sed w}、{@code awk system()}、
 * {@code sort -o}、{@code env cmd}、{@code git branch name}）也不算只读。判不准时一律按「非只读」处理。</p>
 *
 * <p>该分类器作为 BashToolStrategy 的内置层，在 P0 硬编码防御之后、PermissionEngine 之前执行。
 * 确保在 DEFAULT 模式下，安全只读命令自动放行，不触发人工确认。</p>
 *
 * @author noear
 * @since 4.0
 */
public class BashCommandClassifier {

    /** 只读/搜索命令白名单 */
    private static final Set<String> SEARCH_READ_COMMANDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
        // 搜索类
        "grep", "egrep", "fgrep", "rg", "ag", "ack",
        "find", "fd", "locate",
        // 列目录类
        "ls", "dir", "tree", "exa",
        // 查看文件内容类
        "cat", "bat", "less", "more", "head", "tail",
        // 统计与信息类
        "wc", "file", "which", "whereis", "whence", "type",
        "stat", "du", "df",
        // 输出类
        "echo", "printf",
        // 文本处理类
        "diff", "comm", "sort", "uniq", "cut", "tr", "awk", "sed",
        // 格式化工具
        "jq", "yq", "xmllint",
        // Git 只读命令（双词）
        "git log", "git show", "git diff", "git status", "git branch",
        "git tag", "git remote", "git rev-parse", "git ls-files",
        "git blame", "git shortlog",
        // 环境与系统信息
        "pwd", "env", "printenv", "id", "whoami", "hostname", "uname",
        "date", "cal"
    )));

    /** find 中会删除、执行命令或写文件的动作 */
    private static final Set<String> FIND_WRITE_ACTIONS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
        "-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls"
    )));

    /** 重定向到这些目标不写文件 */
    private static final Set<String> DISCARD_TARGETS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
        "/dev/null", "/dev/stdout", "/dev/stderr"
    )));

    /** 不完整命令检测正则（尾部有管道、逻辑运算符或分号；转义的 \; \| 是普通字符，如 find -exec ... \;） */
    private static final Pattern INCOMPLETE_COMMAND_PATTERN =
        Pattern.compile("((?<!\\\\)\\|\\s*|&&\\s*|\\|\\|\\s*|(?<!\\\\);\\s*)$");

    /** Git 写操作命令集合 */
    private static final Set<String> GIT_WRITE_COMMANDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
        "git commit", "git push", "git pull", "git merge", "git rebase",
        "git reset", "git checkout", "git switch", "git stash",
        "git clean", "git rm", "git mv", "git tag -d",
        "git branch -d", "git branch -D"
    )));

    /**
     * 分类 bash 命令
     *
     * @param command bash 命令字符串
     * @return 决策结果：DENY（不完整）、ALLOW（只读）、ASK（其他）
     */
    public PermissionBehavior classify(String command) {
        // 1. 空命令拒绝
        if (command == null || command.trim().isEmpty()) {
            return PermissionBehavior.DENY;
        }

        // 2. 不完整命令拒绝
        if (isIncompleteCommand(command)) {
            return PermissionBehavior.DENY;
        }

        // 3. 只读命令放行
        if (isSearchOrReadCommand(command)) {
            return PermissionBehavior.ALLOW;
        }

        // 4. 其他命令交由人工确认
        return PermissionBehavior.ASK;
    }

    /**
     * 判断命令是否为只读/搜索命令
     *
     * <p>按 shell 语法切分为多段（管道、{@code ; && || &}、换行），每一段都必须是只读命令才放行。</p>
     *
     * @param command 命令字符串
     * @return true 表示只读
     */
    public boolean isSearchOrReadCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        List<List<String>> segments = new CommandLexer(command.trim()).parse();
        if (segments == null || segments.isEmpty()) {
            return false;
        }

        for (List<String> words : segments) {
            if (!isSingleReadCommand(words)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断单段命令（已去引号的词列表）是否为只读
     */
    private static boolean isSingleReadCommand(List<String> words) {
        if (words.isEmpty()) {
            return false;
        }

        String baseCmd = words.get(0);
        if ("git".equals(baseCmd)) {
            return isGitReadCommand(words);
        }

        if (!SEARCH_READ_COMMANDS.contains(baseCmd)) {
            return false;
        }

        List<String> args = words.subList(1, words.size());
        switch (baseCmd) {
            case "find":
                for (String a : args) {
                    if (FIND_WRITE_ACTIONS.contains(a)) {
                        return false;
                    }
                }
                return true;
            case "fd":
                return !hasShortFlag(args, "xX", "") && !hasPrefix(args, "--exec");
            case "rg":
                return !hasPrefix(args, "--pre");
            case "ag":
            case "ack":
            case "bat":
                return !hasPrefix(args, "--pager");
            case "less":
                return !hasPrefix(args, "-o", "-O", "--log-file", "--LOG-FILE");
            case "tree":
                return !hasShortFlag(args, "o", "");
            case "file":
                return !hasShortFlag(args, "C", "eFfmP") && !hasPrefix(args, "--compile");
            case "xmllint":
                return !hasPrefix(args, "--output");
            case "yq":
                return !hasShortFlag(args, "i", "") && !hasPrefix(args, "--inplace");
            case "sort":
                return !hasShortFlag(args, "o", "kStT") && !hasPrefix(args, "--output", "--compress-program");
            case "uniq":
                return isUniqRead(args);
            case "sed":
                return isSedRead(args);
            case "awk":
                return isAwkRead(args);
            case "env":
                // 带命令时 env 会执行它；只认无参（及只打印的 -0）
                for (String a : args) {
                    if (!"-0".equals(a) && !"--null".equals(a)) {
                        return false;
                    }
                }
                return true;
            case "date":
                return isDateRead(args);
            case "hostname":
                // 带名字或 -F 文件时会设置主机名
                for (String a : args) {
                    if (!a.startsWith("-") || a.startsWith("-F") || a.startsWith("--file")
                            || "-b".equals(a) || a.startsWith("--boot")) {
                        return false;
                    }
                }
                return true;
            default:
                return true;
        }
    }

    // ========== git ==========

    private static boolean isGitReadCommand(List<String> words) {
        int k = 1;
        // 只放行不改配置的全局选项；-c/-C/--exec-path 等可借配置执行任意程序，不算只读
        while (k < words.size() && ("--no-pager".equals(words.get(k)) || "-P".equals(words.get(k)))) {
            k++;
        }
        if (k >= words.size()) {
            return false;
        }

        String sub = words.get(k);
        if (!SEARCH_READ_COMMANDS.contains("git " + sub)) {
            return false;
        }

        List<String> args = words.subList(k + 1, words.size());
        switch (sub) {
            case "log":
            case "show":
            case "diff":
            case "shortlog":
                return !hasPrefix(args, "--output", "--ext-diff");
            case "branch":
                return isGitBranchRead(args);
            case "tag":
                return isGitTagRead(args);
            case "remote":
                return isGitRemoteRead(args);
            default:
                return true;
        }
    }

    /** git branch 只认列出模式（不带名字，或带 --list 等列出选项） */
    private static boolean isGitBranchRead(List<String> args) {
        if (hasPrefix(args, "--delete", "--move", "--copy", "--force", "--set-upstream", "--unset-upstream",
                "--edit-description", "--track", "--no-track", "--create-reflog")) {
            return false;
        }
        if (hasShortFlag(args, "dDmMcCfut", "")) {
            return false;
        }

        boolean listMode = hasShortFlag(args, "lv", "")
                || hasPrefix(args, "--list", "--verbose", "--contains", "--no-contains", "--merged", "--no-merged", "--points-at");
        return listMode || !hasPositional(args);
    }

    /** git tag 只认列出与校验模式 */
    private static boolean isGitTagRead(List<String> args) {
        if (hasPrefix(args, "--annotate", "--sign", "--no-sign", "--local-user", "--force", "--delete",
                "--message", "--file", "--edit", "--create-reflog")) {
            return false;
        }
        if (hasShortFlag(args, "asufdmFe", "")) {
            return false;
        }

        boolean listMode = hasShortFlag(args, "lvn", "")
                || hasPrefix(args, "--list", "--verify", "--contains", "--no-contains", "--merged", "--no-merged", "--points-at");
        return listMode || !hasPositional(args);
    }

    /** git remote 只认 [-v]、show、get-url */
    private static boolean isGitRemoteRead(List<String> args) {
        for (String a : args) {
            if ("-v".equals(a) || "--verbose".equals(a)) {
                continue;
            }
            return "show".equals(a) || "get-url".equals(a);
        }
        return true;
    }

    // ========== 文本工具 ==========

    /** uniq 的第二个位置参数是输出文件 */
    private static boolean isUniqRead(List<String> args) {
        int positional = 0;
        boolean endOpts = false;
        for (int k = 0; k < args.size(); k++) {
            String a = args.get(k);
            if (!endOpts && "--".equals(a)) {
                endOpts = true;
            } else if (!endOpts && a.startsWith("-") && a.length() > 1) {
                if ("-f".equals(a) || "-s".equals(a) || "-w".equals(a)
                        || "--skip-fields".equals(a) || "--skip-chars".equals(a) || "--check-chars".equals(a)) {
                    k++; // 跳过选项值
                }
            } else {
                positional++;
            }
        }
        return positional < 2;
    }

    private static boolean isAwkRead(List<String> args) {
        for (String a : args) {
            // 程序文件、原地修改（gawk -i inplace）、加载扩展：内容无法检查
            if (a.startsWith("-f") || a.startsWith("-i") || a.startsWith("-l") || a.startsWith("-E")
                    || a.startsWith("--file") || a.startsWith("--include") || a.startsWith("--load") || a.startsWith("--exec")) {
                return false;
            }
            // 程序里的 system()、输出重定向、管道（含 "cmd" | getline）都能写文件或执行命令
            if (a.contains("system") || a.indexOf('>') >= 0 || a.indexOf('|') >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSedRead(List<String> args) {
        List<String> scripts = new ArrayList<>();
        boolean hasExpression = false;
        String firstPositional = null;
        boolean endOpts = false;

        for (int k = 0; k < args.size(); k++) {
            String a = args.get(k);
            if (!endOpts && "--".equals(a)) {
                endOpts = true;
            } else if (!endOpts && a.startsWith("--")) {
                if (a.startsWith("--in-place") || a.startsWith("--file")) {
                    return false;
                }
                if (a.startsWith("--expression")) {
                    hasExpression = true;
                    int eq = a.indexOf('=');
                    if (eq > 0) {
                        scripts.add(a.substring(eq + 1));
                    } else if (k + 1 < args.size()) {
                        scripts.add(args.get(++k));
                    } else {
                        return false;
                    }
                } else if ("--line-length".equals(a)) {
                    k++;
                }
            } else if (!endOpts && a.startsWith("-") && a.length() > 1) {
                for (int p = 1; p < a.length(); p++) {
                    char ch = a.charAt(p);
                    if (ch == 'i' || ch == 'f') {
                        // 原地修改；脚本文件内容无法检查
                        return false;
                    }
                    if (ch == 'e') {
                        hasExpression = true;
                        if (p + 1 < a.length()) {
                            scripts.add(a.substring(p + 1));
                        } else if (k + 1 < args.size()) {
                            scripts.add(args.get(++k));
                        } else {
                            return false;
                        }
                        break;
                    }
                    if (ch == 'l') {
                        if (p + 1 == a.length()) {
                            k++;
                        }
                        break;
                    }
                }
            } else if (firstPositional == null) {
                firstPositional = a;
            }
        }

        if (!hasExpression) {
            if (firstPositional == null) {
                return false;
            }
            scripts.add(firstPositional);
        }

        for (String script : scripts) {
            if (!isSedScriptRead(script)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查 sed 脚本是否只读：拒绝 w/W/e 命令与 s 的 w/e 标志（正则与替换文本里的字母不算命令）
     */
    private static boolean isSedScriptRead(String s) {
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);

            if (Character.isWhitespace(c) || c == ';' || c == '{' || c == '}' || c == '!'
                    || Character.isDigit(c) || c == '$' || c == ',' || c == '~' || c == '+'
                    || c == 'I' || c == 'M') {
                // 分隔、分组、取反与地址（行号、$、范围、步进、正则地址的 I/M 修饰）
                i++;
            } else if (c == '/') {
                i = skipDelimited(s, i + 1, '/');
            } else if (c == '\\') {
                // \cREGEXc 形式的地址
                i = (i + 1 < n) ? skipDelimited(s, i + 2, s.charAt(i + 1)) : -1;
            } else if (c == '#') {
                i = indexOrEnd(s, '\n', i);
            } else if (c == 's' || c == 'y') {
                if (i + 1 >= n) {
                    return false;
                }
                char d = s.charAt(i + 1);
                int j = skipDelimited(s, i + 2, d);
                if (j >= 0) {
                    j = skipDelimited(s, j, d);
                }
                if (j < 0) {
                    return false;
                }
                if (c == 's') {
                    while (j < n && Character.isLetterOrDigit(s.charAt(j))) {
                        char flag = s.charAt(j);
                        if (flag == 'w' || flag == 'e') {
                            return false;
                        }
                        j++;
                    }
                }
                i = j;
            } else if (c == 'w' || c == 'W' || c == 'e') {
                return false;
            } else if (c == 'a' || c == 'i' || c == 'c' || c == 'r' || c == 'R') {
                // 文本或文件名一直到行尾
                i = indexOrEnd(s, '\n', i);
            } else if (c == ':' || c == 'b' || c == 't' || c == 'T') {
                // 标签到 ; 或行尾
                int semi = indexOrEnd(s, ';', i);
                int nl = indexOrEnd(s, '\n', i);
                i = Math.min(semi, nl);
            } else {
                // 其它单字符命令（p P d D n N g G h H x l = q Q z F 等）
                i++;
            }

            if (i < 0) {
                return false;
            }
        }
        return true;
    }

    /** 从 start 起找未转义的 delim，返回其后一位；找不到返回 -1 */
    private static int skipDelimited(String s, int start, char delim) {
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == delim) {
                return i + 1;
            }
        }
        return -1;
    }

    private static int indexOrEnd(String s, char ch, int from) {
        int idx = s.indexOf(ch, from);
        return idx < 0 ? s.length() : idx;
    }

    private static boolean isDateRead(List<String> args) {
        for (int k = 0; k < args.size(); k++) {
            String a = args.get(k);
            if (a.startsWith("+")) {
                continue; // 输出格式
            }
            if (a.startsWith("--")) {
                if (a.startsWith("--set")) {
                    return false;
                }
                if (("--date".equals(a) || "--file".equals(a) || "--reference".equals(a))) {
                    k++;
                }
                continue;
            }
            if (a.startsWith("-") && a.length() > 1) {
                for (int p = 1; p < a.length(); p++) {
                    char ch = a.charAt(p);
                    if (ch == 's') {
                        return false;
                    }
                    if (ch == 'd' || ch == 'f' || ch == 'r') {
                        if (p + 1 == a.length()) {
                            k++;
                        }
                        break;
                    }
                }
                continue;
            }
            // 非格式的位置参数（如 MMDDhhmm）是设置时间
            return false;
        }
        return true;
    }

    // ========== 选项辅助 ==========

    /** 是否有参数以任一前缀开头（用于长选项及其 = 形式） */
    private static boolean hasPrefix(List<String> args, String... prefixes) {
        for (String a : args) {
            for (String prefix : prefixes) {
                if (a.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 是否有短选项簇（如 -ro）含 flags 中任一字符；遇到 valueFlags 中的选项即停止（其后是选项值）
     */
    private static boolean hasShortFlag(List<String> args, String flags, String valueFlags) {
        for (String a : args) {
            if (a.length() < 2 || a.charAt(0) != '-' || a.charAt(1) == '-') {
                continue;
            }
            for (int p = 1; p < a.length(); p++) {
                char ch = a.charAt(p);
                if (flags.indexOf(ch) >= 0) {
                    return true;
                }
                if (valueFlags.indexOf(ch) >= 0 || ch == '=') {
                    break;
                }
            }
        }
        return false;
    }

    private static boolean hasPositional(List<String> args) {
        for (String a : args) {
            if (!a.startsWith("-")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断命令是否不完整（尾部有管道、逻辑运算符或分号）
     *
     * @param command 命令字符串
     * @return true 表示不完整
     */
    public boolean isIncompleteCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        return INCOMPLETE_COMMAND_PATTERN.matcher(command.trim()).find();
    }

    /**
     * 判断命令是否为 Git 写操作
     *
     * @param command 命令字符串
     * @return true 表示 Git 写操作
     */
    public boolean isGitWriteCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        String trimmed = command.trim();
        String[] words = trimmed.split("\\s+");
        if (words.length >= 2) {
            String twoWord = words[0] + " " + words[1];
            return GIT_WRITE_COMMANDS.contains(twoWord);
        }
        return false;
    }

    /**
     * 命令切分器：识别单引号、双引号、$'...'、反斜杠转义，按管道、{@code ; && || &} 与换行切分为多段，每段为去引号后的词列表。
     *
     * <p>遇到无法静态判定为只读的结构（命令替换、进程替换、子 shell、here-doc、{@code <>}、写文件的重定向、未闭合引号）时，
     * {@link #parse()} 返回 null。重定向目标不计入词列表。</p>
     */
    private static final class CommandLexer {
        private static final int REDIRECT_NONE = 0;
        private static final int REDIRECT_READ = 1;
        private static final int REDIRECT_WRITE = 2;
        private static final int REDIRECT_DUP = 3;

        private final String cmd;
        private final List<List<String>> segments = new ArrayList<>();
        private List<String> words = new ArrayList<>();
        private final StringBuilder word = new StringBuilder();
        private boolean inWord;
        private boolean wordQuoted;
        private int redirect = REDIRECT_NONE;

        CommandLexer(String cmd) {
            this.cmd = cmd;
        }

        List<List<String>> parse() {
            int n = cmd.length();
            int i = 0;
            while (i < n) {
                char c = cmd.charAt(i);
                char next = (i + 1 < n) ? cmd.charAt(i + 1) : '\0';

                if (c == '\'') {
                    int end = cmd.indexOf('\'', i + 1);
                    if (end < 0) {
                        return null;
                    }
                    word.append(cmd, i + 1, end);
                    markQuoted();
                    i = end + 1;
                } else if (c == '$' && next == '\'') {
                    i = readAnsiC(i + 2);
                } else if (c == '"') {
                    i = readDoubleQuoted(i + 1);
                } else if (c == '\\') {
                    if (next != '\n' && next != '\0') {
                        word.append(next);
                        markQuoted();
                    }
                    i += 2;
                } else if (c == '`' || (c == '$' && next == '(') || c == '(' || c == ')') {
                    // 命令替换、子 shell：无法静态判定
                    return null;
                } else if (c == ' ' || c == '\t' || c == '\r') {
                    if (!flush()) {
                        return null;
                    }
                    i++;
                } else if (c == '\n' || c == ';') {
                    if (!endSegment()) {
                        return null;
                    }
                    i++;
                } else if (c == '|') {
                    if (!endSegment()) {
                        return null;
                    }
                    i += (next == '|' || next == '&') ? 2 : 1;
                } else if (c == '&') {
                    if (next == '>') {
                        // &> / &>> 同时重定向 stdout 与 stderr
                        if (!flush()) {
                            return null;
                        }
                        i += 2;
                        if (i < n && cmd.charAt(i) == '>') {
                            i++;
                        }
                        redirect = REDIRECT_WRITE;
                    } else {
                        if (!endSegment()) {
                            return null;
                        }
                        i += (next == '&') ? 2 : 1;
                    }
                } else if (c == '>') {
                    if (!dropFdPrefix()) {
                        return null;
                    }
                    i++;
                    if (i < n && cmd.charAt(i) == '(') {
                        return null; // 进程替换
                    }
                    if (i < n && (cmd.charAt(i) == '>' || cmd.charAt(i) == '|')) {
                        i++; // >> 追加、>| 强制覆盖
                    }
                    if (i < n && cmd.charAt(i) == '&') {
                        i++;
                        redirect = REDIRECT_DUP; // >&N 复制描述符；>&file 仍是写文件
                    } else {
                        redirect = REDIRECT_WRITE;
                    }
                } else if (c == '<') {
                    if (!dropFdPrefix()) {
                        return null;
                    }
                    i++;
                    if (i < n && (cmd.charAt(i) == '(' || cmd.charAt(i) == '>')) {
                        return null; // 进程替换、<> 读写打开
                    }
                    if (i < n && cmd.charAt(i) == '<') {
                        if (i + 1 < n && cmd.charAt(i + 1) == '<') {
                            i += 2; // <<< here-string
                        } else {
                            return null; // here-doc：正文按行跟在后面
                        }
                    } else if (i < n && cmd.charAt(i) == '&') {
                        i++;
                    }
                    redirect = REDIRECT_READ;
                } else {
                    word.append(c);
                    inWord = true;
                    i++;
                }

                if (i < 0) {
                    return null;
                }
            }

            if (!endSegment()) {
                return null;
            }
            return segments;
        }

        private void markQuoted() {
            inWord = true;
            wordQuoted = true;
        }

        /** 读 $'...'，返回闭合引号后一位；未闭合返回 -1 */
        private int readAnsiC(int i) {
            while (i < cmd.length()) {
                char d = cmd.charAt(i);
                if (d == '\\' && i + 1 < cmd.length()) {
                    word.append(cmd.charAt(i + 1));
                    i += 2;
                } else if (d == '\'') {
                    markQuoted();
                    return i + 1;
                } else {
                    word.append(d);
                    i++;
                }
            }
            return -1;
        }

        /** 读 "..."，返回闭合引号后一位；未闭合或含命令替换返回 -1 */
        private int readDoubleQuoted(int i) {
            while (i < cmd.length()) {
                char d = cmd.charAt(i);
                char e = (i + 1 < cmd.length()) ? cmd.charAt(i + 1) : '\0';
                if (d == '\\' && e != '\0') {
                    if ("$`\"\\\n".indexOf(e) >= 0) {
                        if (e != '\n') {
                            word.append(e);
                        }
                    } else {
                        word.append(d).append(e);
                    }
                    i += 2;
                } else if (d == '`' || (d == '$' && e == '(')) {
                    return -1; // 双引号内仍会展开
                } else if (d == '"') {
                    markQuoted();
                    return i + 1;
                } else {
                    word.append(d);
                    i++;
                }
            }
            return -1;
        }

        /** 重定向前紧贴的纯数字是文件描述符（如 2>），不是参数 */
        private boolean dropFdPrefix() {
            if (inWord && !wordQuoted && isDigits(word)) {
                word.setLength(0);
                inWord = false;
                return true;
            }
            return flush();
        }

        /** 结束当前词；若有待处理的重定向，则把该词当作重定向目标检查 */
        private boolean flush() {
            if (!inWord) {
                return true;
            }
            String w = word.toString();
            word.setLength(0);
            inWord = false;
            wordQuoted = false;

            int pending = redirect;
            redirect = REDIRECT_NONE;
            switch (pending) {
                case REDIRECT_WRITE:
                    return DISCARD_TARGETS.contains(w);
                case REDIRECT_DUP:
                    return "-".equals(w) || isDigits(w);
                case REDIRECT_READ:
                    return true;
                default:
                    words.add(w);
                    return true;
            }
        }

        private boolean endSegment() {
            if (!flush() || redirect != REDIRECT_NONE) {
                return false; // 重定向缺目标
            }
            segments.add(words);
            words = new ArrayList<>();
            return true;
        }

        private static boolean isDigits(CharSequence s) {
            if (s.length() == 0) {
                return false;
            }
            for (int k = 0; k < s.length(); k++) {
                if (!Character.isDigit(s.charAt(k))) {
                    return false;
                }
            }
            return true;
        }
    }
}
