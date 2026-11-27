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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * shell 命令行的静态切分结果
 *
 * <p>识别单引号、双引号、{@code $'...'}、反斜杠转义，按管道、{@code ; && || &} 与换行切分为多段，每段为去引号后的词列表
 * （重定向及其目标不计入词列表）。供权限规则按段匹配与只读命令分类共用。</p>
 *
 * <p>遇到无法静态切分的结构（命令替换、进程替换、子 shell、here-doc、{@code <>}、未闭合引号、重定向缺目标）时
 * {@link #isOpaque()} 为 true，此时没有分段。写文件的重定向（{@code /dev/null}、{@code /dev/stdout}、{@code /dev/stderr}
 * 与描述符复制除外）与变量展开（{@code $VAR}、{@code ${...}}）不影响切分，只记标记。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class ShellCommandLine {
    /** 重定向到这些目标不写文件 */
    private static final Set<String> DISCARD_TARGETS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "/dev/null", "/dev/stdout", "/dev/stderr"
    )));

    private final List<List<String>> segments;
    private final boolean opaque;
    private final boolean writeRedirect;
    private final boolean expansion;

    private ShellCommandLine(List<List<String>> segments, boolean opaque, boolean writeRedirect, boolean expansion) {
        this.segments = segments;
        this.opaque = opaque;
        this.writeRedirect = writeRedirect;
        this.expansion = expansion;
    }

    /**
     * 切分命令
     *
     * @param command 命令原文（null 视为空串）
     */
    public static ShellCommandLine parse(String command) {
        Lexer lexer = new Lexer(command == null ? "" : command);
        List<List<String>> segments = lexer.parse();
        if (segments == null) {
            return new ShellCommandLine(Collections.<List<String>>emptyList(), true, lexer.writeRedirect, lexer.expansion);
        }
        return new ShellCommandLine(Collections.unmodifiableList(segments), false, lexer.writeRedirect, lexer.expansion);
    }

    /**
     * 是否无法静态切分（命令替换、子 shell、here-doc 等）；为 true 时没有分段
     */
    public boolean isOpaque() {
        return opaque;
    }

    /**
     * 是否有写文件的重定向（{@code /dev/null} 等丢弃目标与描述符复制不算）
     */
    public boolean hasWriteRedirect() {
        return writeRedirect;
    }

    /**
     * 是否有变量展开（{@code $VAR}、{@code ${...}}、{@code $1} 等；单引号内的不算）
     */
    public boolean hasExpansion() {
        return expansion;
    }

    /**
     * 分段（每段为去引号后的词列表，可能有空段，如 {@code ls;} 的末段）
     */
    public List<List<String>> getSegments() {
        return segments;
    }

    /**
     * 非空分段的文本（段内各词以单个空格拼接），用于 glob 匹配
     */
    public List<String> getSegmentTexts() {
        List<String> texts = new ArrayList<>(segments.size());
        for (List<String> words : segments) {
            if (words.isEmpty() == false) {
                texts.add(String.join(" ", words));
            }
        }
        return texts;
    }

    /**
     * 词法切分器
     */
    private static final class Lexer {
        private static final int REDIRECT_NONE = 0;
        private static final int REDIRECT_READ = 1;
        private static final int REDIRECT_WRITE = 2;
        private static final int REDIRECT_DUP = 3;

        /** {@code $} 后跟这些字符即为变量展开（{@code $(} 与 {@code $'} 另行处理） */
        private static final String EXPANSION_LEADS = "_{@*#?$!-";

        private final String cmd;
        private final List<List<String>> segments = new ArrayList<>();
        private List<String> words = new ArrayList<>();
        private final StringBuilder word = new StringBuilder();
        private boolean inWord;
        private boolean wordQuoted;
        private int redirect = REDIRECT_NONE;

        boolean writeRedirect;
        boolean expansion;

        Lexer(String cmd) {
            this.cmd = cmd;
        }

        /**
         * @return 分段；无法静态切分时返回 null
         */
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
                    // 命令替换、子 shell：无法静态切分
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
                    if (c == '$' && isExpansionLead(next)) {
                        expansion = true;
                    }
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

        private static boolean isExpansionLead(char ch) {
            return Character.isLetterOrDigit(ch) || EXPANSION_LEADS.indexOf(ch) >= 0;
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
                    if (d == '$' && isExpansionLead(e)) {
                        expansion = true;
                    }
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
                    if (!DISCARD_TARGETS.contains(w)) {
                        writeRedirect = true;
                    }
                    return true;
                case REDIRECT_DUP:
                    if (!"-".equals(w) && !isDigits(w)) {
                        writeRedirect = true; // >&file
                    }
                    return true;
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
