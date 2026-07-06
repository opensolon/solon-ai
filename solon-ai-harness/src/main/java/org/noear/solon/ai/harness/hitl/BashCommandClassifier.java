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
package org.noear.solon.ai.harness.hitl;

import org.noear.solon.ai.harness.permission.PermissionDecision;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bash 命令分类器
 *
 * <p>参考 claude-code-java 的 BashPermissions + BashTool 设计，对 bash 命令进行三级分类：
 * <ul>
 * <li><b>DENY</b> — 空命令或不完整命令（尾部有 |, &&, ||, ;）</li>
 * <li><b>ALLOW</b> — 只读/搜索命令（50+ 条白名单，含管道分段检查）</li>
 * <li><b>ASK</b> — 其他所有命令（交由人工确认）</li>
 * </ul>
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

    /** 不完整命令检测正则（尾部有管道、逻辑运算符或分号） */
    private static final Pattern INCOMPLETE_COMMAND_PATTERN =
        Pattern.compile("(\\|\\s*|&&\\s*|\\|\\|\\s*|;\\s*)$");

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
    public PermissionDecision classify(String command) {
        // 1. 空命令拒绝
        if (command == null || command.trim().isEmpty()) {
            return PermissionDecision.DENY;
        }

        // 2. 不完整命令拒绝
        if (isIncompleteCommand(command)) {
            return PermissionDecision.DENY;
        }

        // 3. 只读命令放行
        if (isSearchOrReadCommand(command)) {
            return PermissionDecision.ALLOW;
        }

        // 4. 其他命令交由人工确认
        return PermissionDecision.ASK;
    }

    /**
     * 判断命令是否为只读/搜索命令
     *
     * <p>支持管道命令 — 所有分段都必须是只读命令才放行。</p>
     *
     * @param command 命令字符串
     * @return true 表示只读
     */
    public boolean isSearchOrReadCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        String trimmed = command.trim();

        // 管道命令：所有分段都必须是只读
        if (trimmed.contains("|")) {
            String[] parts = trimmed.split("\\|");
            for (String part : parts) {
                if (!isSingleReadCommand(part.trim())) {
                    return false;
                }
            }
            return true;
        }

        return isSingleReadCommand(trimmed);
    }

    /**
     * 判断单个命令是否为只读
     */
    private boolean isSingleReadCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        String cmd = command.trim();
        String[] words = cmd.split("\\s+");
        String baseCmd = words[0];

        // 安全检查：任何命令带 > 输出重定向，都算写操作
        if (cmd.contains(">")) {
            // 逐字符检测 > 排除 2> (stderr 重定向不算写)
            // 注意：不能用 !cmd.contains("2>") 做外层守卫
            // 否则 "cat f 2>err >out" 会因含有 2> 而跳过整个检查
            if (hasOutputRedirect(cmd)) {
                return false;
            }
        }

        // 单词命令匹配
        if (SEARCH_READ_COMMANDS.contains(baseCmd)) {
            // 安全检查：sed -i 原地修改文件，不算只读
            if ("sed".equals(baseCmd) && hasInPlaceFlag(words)) {
                return false;
            }
            return true;
        }

        // 双词命令匹配（如 "git log"）
        if (words.length >= 2) {
            String twoWord = words[0] + " " + words[1];
            if (SEARCH_READ_COMMANDS.contains(twoWord)) {
                // 安全检查：git branch -d / -D 是删除分支的写操作
                if ("git branch".equals(twoWord) && hasDeleteBranchFlag(words)) {
                    return false;
                }
                return true;
            }
        }

        return false;
    }

    /**
     * 检测 sed 命令是否包含原地修改标志（-i / --in-place）
     */
    private boolean hasInPlaceFlag(String[] words) {
        for (String word : words) {
            if (word.equals("-i") || word.startsWith("-i") ||
                word.equals("--in-place") || word.startsWith("--in-place=")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检测命令是否包含输出重定向（> 但不是 2> stderr 重定向）
     */
    private boolean hasOutputRedirect(String cmd) {
        // 检查 > 但排除 2> (stderr) 和 >> (追加也算写)
        // 简单策略：如果包含 > 且不是以 2 开头的 stderr 重定向
        for (int i = 0; i < cmd.length(); i++) {
            if (cmd.charAt(i) == '>') {
                // 检查前一个字符是否为 '2'（2> 是 stderr 重定向）
                if (i > 0 && cmd.charAt(i - 1) == '2') {
                    continue; // 2> 不算输出写
                }
                // 检查前一个字符是否为 '&'（&> 或 2&> 等也算输出）
                // 普通 > 或 >> 都算写
                return true;
            }
        }
        return false;
    }

    /**
     * 检测 git branch 命令是否包含删除标志（-d / -D / --delete）
     */
    private boolean hasDeleteBranchFlag(String[] words) {
        for (int i = 2; i < words.length; i++) {
            String w = words[i];
            if (w.equals("-d") || w.equals("-D") || w.equals("--delete") ||
                w.startsWith("-d") || w.startsWith("-D") || w.startsWith("--delete")) {
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
}
