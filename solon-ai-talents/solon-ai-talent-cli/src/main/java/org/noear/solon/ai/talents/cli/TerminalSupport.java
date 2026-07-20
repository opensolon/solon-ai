/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.cli;

import org.noear.solon.Utils;

import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.core.util.Assert;

import java.io.IOException;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TerminalTalent 的内部支撑逻辑。
 *
 * @author noear
 * @since 3.9.1
 */
public class TerminalSupport {

    /**
     * 匹配结果：包含起始位置和实际匹配长度（含缩进和换行符）。
     */
    static class MatchResult {
        final int startIndex;
        final int matchedLength;
        final boolean isLooseMatch; // true 表示通过忽略行首行尾空白匹配（findAtStartLineLoose / collapse）

        MatchResult(int startIndex, int matchedLength, boolean isLooseMatch) {
            this.startIndex = startIndex;
            this.matchedLength = matchedLength;
            this.isLooseMatch = isLooseMatch;
        }
    }
    int maxCharacterLimit = 128 * 1024;

    /**
     * 设置单次读取/搜索的最大物理长度限制（字符数）。
     * 默认 128KB (128 * 1024)。
     */
    public void setMaxCharacterLimit(int maxCharacterLimit) {
        this.maxCharacterLimit = maxCharacterLimit;
    }

    public int getMaxCharacterLimit() {
        return maxCharacterLimit;
    }

    private final MountManager mountManager;
    private final Set<String> ignoreDirs;
    private final ShellMode shellMode;

    TerminalSupport(MountManager mountManager, Set<String> ignoreDirs, ShellMode shellMode) {
        this.mountManager = mountManager;
        this.ignoreDirs = ignoreDirs;
        this.shellMode = shellMode;
    }

    String normalizeNewlines(String context, String text) {
        if (text == null) return null;
        // 如果文件内容包含 \r\n，则将 text 中的 \n 转换为 \r\n
        if (context.contains("\r\n")) {
            return text.replace("\r\n", "\n").replace("\n", "\r\n");
        } else {
            return text.replace("\r\n", "\n");
        }
    }

    String applyEditLogic(String content, String oldStr, String newStr, boolean replaceAll, Integer oldStrStartLine) {
        if (Utils.isEmpty(oldStr)) {
            throw new IllegalArgumentException("old_str 不能为空");
        }

        if (oldStr.equals(newStr)) {
            return content; // 内容相同无需处理
        }

        String finalOld = normalizeNewlines(content, oldStr);
        String finalNew = normalizeNewlines(content, newStr);

        if (replaceAll) {
            if (!content.contains(finalOld)) {
                throw new IllegalArgumentException("找不到待替换的文本块");
            }
            return content.replace(finalOld, finalNew);
        } else {
            MatchResult match = findAtStartLine(content, finalOld, oldStrStartLine);
            if (match != null) {
                String replacement = finalNew;
                if (match.isLooseMatch) {
                    // 当通过忽略缩进的宽松匹配时，提取原始内容的行首缩进并应用到 newStr
                    replacement = applyContentLeadingWhitespace(content, match.startIndex, match.matchedLength, finalNew);
                }
                return content.substring(0, match.startIndex) + replacement + content.substring(match.startIndex + match.matchedLength);
            }

            int firstIndex = content.indexOf(finalOld);
            if (firstIndex == -1) {
                throw new IllegalArgumentException("找不到文本块。这通常是由于前面的修改改变了文件的字符偏移或内容，建议分步执行。");
            }
            if (content.lastIndexOf(finalOld) != firstIndex) {
                throw new IllegalArgumentException("文本块在当前状态下不唯一");
            }
            return content.substring(0, firstIndex) + finalNew + content.substring(firstIndex + finalOld.length());
        }
    }

    MatchResult findAtStartLine(String content, String oldStr, Integer oldStrStartLine) {
        if (oldStrStartLine == null || oldStrStartLine <= 0) {
            return null;
        }

        int lineStartIndex = getLineStartIndex(content, oldStrStartLine);
        if (lineStartIndex < 0) {
            return null;
        }

        // 精确匹配（保留原有行为）
        if (content.startsWith(oldStr, lineStartIndex)) {
            return new MatchResult(lineStartIndex, oldStr.length(), false);
        }

        // 策略2：逐行 trim 匹配（忽略每行首尾空白差异）
        MatchResult looseMatch = findAtStartLineLoose(content, oldStr, lineStartIndex);
        if (looseMatch != null) {
            return looseMatch;
        }

        // 策略3：折叠空白匹配（行内多余空格/Tab混合等，collapseWhitespace 兜底）
        MatchResult collapsedMatch = findAtStartLineCollapseWhitespace(content, oldStr, lineStartIndex);
        if (collapsedMatch != null) {
            return collapsedMatch;
        }

        return null;
    }

    /**
     * 宽松匹配：将 oldStr 按行拆分，与 content 从指定起始位置开始的行逐行比较，
     * 忽略每行行首和行尾的空白字符差异（空格 vs Tab、空格数量不同等）。
     * 同时容忍 oldStr 末尾多余的空白行（例如从 diff 格式转换带来的多余换行）。
     */
    private MatchResult findAtStartLineLoose(String content, String oldStr, int lineStartIndex) {
        // 提取 content 中从 lineStartIndex 开始的各行的实际内容（trim 后）
        List<String> contentLines = extractTrimmedLines(content, lineStartIndex);
        if (contentLines.isEmpty()) {
            return null;
        }

        // 拆分 oldStr 为行列表，并去掉末尾多余的空白行
        String[] oldStrLinesRaw = oldStr.split("\\r?\\n", -1);
        List<String> oldStrLines = trimTrailingEmptyLines(oldStrLinesRaw);
        if (oldStrLines.isEmpty()) {
            return null;
        }

        if (oldStrLines.size() > contentLines.size()) {
            return null; // oldStr 行数多于 content 可用行数，不可能匹配
        }

        // 逐行比较（忽略每行首尾空白）
        for (int i = 0; i < oldStrLines.size(); i++) {
            String actualLine = contentLines.get(i);
            String expectedLine = trimLine(oldStrLines.get(i));
            if (!actualLine.equals(expectedLine)) {
                return null;
            }
        }

        int computedLength = computeMatchLength(content, lineStartIndex, oldStrLines.size());
        return computedLength > 0 ? new MatchResult(lineStartIndex, computedLength, true) : null;
    }

    /**
     * 折叠空白匹配：在 trim 行首尾空白的基础上，将行内连续空白折叠为单个空格后比较。
     * 作为 findAtStartLineLoose 的兜底策略，处理行内多余空格或 Tab 混合的情况。
     */
    private MatchResult findAtStartLineCollapseWhitespace(String content, String oldStr, int lineStartIndex) {
        List<String> contentLines = extractTrimmedLines(content, lineStartIndex);
        if (contentLines.isEmpty()) {
            return null;
        }

        String[] oldStrLinesRaw = oldStr.split("\\r?\\n", -1);
        List<String> oldStrLines = trimTrailingEmptyLines(oldStrLinesRaw);
        if (oldStrLines.isEmpty() || oldStrLines.size() > contentLines.size()) {
            return null;
        }

        for (int i = 0; i < oldStrLines.size(); i++) {
            String actualLine = collapseWhitespace(contentLines.get(i));
            String expectedLine = collapseWhitespace(trimLine(oldStrLines.get(i)));
            if (!actualLine.equals(expectedLine)) {
                return null;
            }
        }

        int computedLength = computeMatchLength(content, lineStartIndex, oldStrLines.size());
        return computedLength > 0 ? new MatchResult(lineStartIndex, computedLength, true) : null;
    }

    /**
     * 去掉字符串列表末尾的空白行。
     */
    private List<String> trimTrailingEmptyLines(String[] lines) {
        int end = lines.length;
        while (end > 0 && lines[end - 1].trim().isEmpty()) {
            end--;
        }
        List<String> result = new ArrayList<>(end);
        for (int i = 0; i < end; i++) {
            result.add(lines[i]);
        }
        return result;
    }

    /**
     * 多策略匹配诊断版：按策略链（trim → collapseWhitespace）依次检查，
     * 返回具体是哪一行不匹配及其差异描述。匹配成功时返回 null。
     *
     * @param lineStartIndex 1-based 行号（来自 edit.oldStrStartLine），注意不是字符偏移量
     */
    String findLooseMatchDiagnostics(String content, String oldStr, int lineStartIndex) {
        // 先将 1-based 行号转换为字符偏移量（和 findAtStartLine 的转换方式保持一致）
        int startOffset = getLineStartIndex(content, lineStartIndex);
        if (startOffset < 0) {
            startOffset = 0; // 行号超出范围时的回退
        }

        // 先尝试 trim 策略检查
        String diag = tryTrimMatchDiagnostics(content, oldStr, startOffset, "trim");
        if (diag == null) {
            return null; // trim 策略匹配成功
        }

        // trim 策略失败，检查是否是 whitespace 折叠可以解决的差异
        String collapseDiag = tryTrimMatchDiagnostics(content, oldStr, startOffset, "collapse");
        if (collapseDiag == null) {
            return null; // collapse 策略匹配成功
        }

        // 两个策略都失败，报告 trim 策略的诊断信息（最贴近用户预期）
        return diag;
    }

    /**
     * 单一策略诊断：按指定模式检查每一行的差异。
     *
     * @param mode "trim" 使用 trimLine 比较，"collapse" 使用 collapseWhitespace 比较
     */
    private String tryTrimMatchDiagnostics(String content, String oldStr, int startOffset, String mode) {
        List<String> contentLines = extractTrimmedLines(content, startOffset);
        if (contentLines.isEmpty()) {
            return "文件在指定行号处无内容";
        }

        String[] oldStrLinesRaw = oldStr.split("\\r?\\n", -1);
        List<String> oldStrLines = trimTrailingEmptyLines(oldStrLinesRaw);
        if (oldStrLines.isEmpty()) {
            return "old_str 全为空行";
        }

        if (oldStrLines.size() > contentLines.size()) {
            return String.format("行数不匹配：old_str 有 %d 行，但文件中从起始行开始只剩 %d 行",
                    oldStrLines.size(), contentLines.size());
        }

        boolean useCollapse = "collapse".equals(mode);
        for (int i = 0; i < oldStrLines.size(); i++) {
            String actualRaw = contentLines.get(i);
            String expectedRaw = trimLine(oldStrLines.get(i));
            String actual = useCollapse ? collapseWhitespace(actualRaw) : actualRaw;
            String expected = useCollapse ? collapseWhitespace(expectedRaw) : expectedRaw;
            if (!actual.equals(expected)) {
                int fileLineNum = lineStartIndexToLineNumber(content, startOffset) + i;
                if (useCollapse) {
                    return String.format("第 %d 行内容不匹配（折叠空白后）：期待「%s」，文件中是「%s」",
                            fileLineNum, expected, actual);
                } else {
                    return String.format("第 %d 行内容不匹配：期待「%s」，文件中是「%s」",
                            fileLineNum, expected, actual);
                }
            }
        }

        return null; // 匹配成功
    }

    /**
     * 将字符偏移位置转换为行号
     */
    private int lineStartIndexToLineNumber(String content, int charOffset) {
        int line = 1;
        for (int i = 0; i < charOffset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * 从 content 的 lineStartIndex 开始，提取所有行的内容（trim 后，不含换行符）。
     */
    private List<String> extractTrimmedLines(String content, int startOffset) {
        List<String> lines = new ArrayList<>();
        int pos = startOffset;
        while (pos < content.length()) {
            int lineEnd = findLineEnd(content, pos);
            String lineContent = content.substring(pos, lineEnd);
            lines.add(trimLine(lineContent));
            if (lineEnd >= content.length()) {
                break; // 文件末尾
            }
            char c = content.charAt(lineEnd);
            if (c == '\n') {
                pos = lineEnd + 1;
            } else if (c == '\r') {
                pos = lineEnd + 1;
                if (pos < content.length() && content.charAt(pos) == '\n') {
                    pos++;
                }
            }
        }
        return lines;
    }

    /**
     * 查找从 pos 开始的行的结束位置（换行符之前的位置）。
     */
    private int findLineEnd(String content, int pos) {
        for (int i = pos; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return content.length();
    }

    /**
     * 计算从 lineStartIndex 开始的 n 行的总字符数（包含换行符）。
     */
    private int computeMatchLength(String content, int lineStartIndex, int nLines) {
        int pos = lineStartIndex;
        int length = 0;
        for (int i = 0; i < nLines; i++) {
            int lineEnd = findLineEnd(content, pos);
            length = lineEnd - lineStartIndex;
            boolean isLastLine = (i == nLines - 1);
            // 包含换行符（仅非最后一行包含行尾换行符）
            if (lineEnd < content.length() && content.charAt(lineEnd) == '\n') {
                if (!isLastLine) {
                    length++;
                }
                pos = lineEnd + 1;
            } else if (lineEnd < content.length() && content.charAt(lineEnd) == '\r') {
                if (!isLastLine) {
                    length++;
                }
                pos = lineEnd + 1;
                if (lineEnd + 1 < content.length() && content.charAt(lineEnd + 1) == '\n') {
                    if (!isLastLine) {
                        length++;
                    }
                    pos = lineEnd + 2;
                }
            } else {
                pos = lineEnd;
            }
        }
        return length;
    }

    /**
     * 去除字符串行首和行尾的空白字符（空格、Tab）。
     */
    private String trimLine(String s) {
        int start = 0;
        while (start < s.length() && (s.charAt(start) == ' ' || s.charAt(start) == '\t')) {
            start++;
        }
        int end = s.length();
        while (end > start && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
            end--;
        }
        return s.substring(start, end);
    }

    /**
     * 将字符串内部的所有连续空白字符折叠为单个空格，并去除首尾空白。
     * 用于处理行内多余空格或 Tab 混合等 whitespace 差异。
     */
    private String collapseWhitespace(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    /**
     * 将原始匹配内容各行的行首缩进提取出来，逐行应用到 newStr 上。
     *
     * <p>当通过忽略缩进的宽松匹配（loose match / collapse match）时，此方法确保
     * newStr 中的每一行都继承原始内容对应行的行首缩进，避免替换后缩进丢失。</p>
     *
     * @param content      原始文件内容
     * @param startIndex   匹配区域的起始字符偏移
     * @param matchedLength 匹配区域的字符长度
     * @param newStr       用户提供的替换内容（newline 已归一化）
     * @return 调整后的替换文本，每行继承了原始内容的行首缩进
     */
    private String applyContentLeadingWhitespace(String content, int startIndex, int matchedLength, String newStr) {
        // Step 1: 提取原始匹配内容中每行的行首空白
        List<String> leadingWhitespaces = new ArrayList<>();
        int pos = startIndex;
        int end = startIndex + matchedLength;
        while (pos < end && pos < content.length()) {
            int lineStart = pos;
            int lineEnd = findLineEnd(content, pos);
            if (lineEnd > end) {
                lineEnd = end;
            }
            String line = content.substring(lineStart, lineEnd);
            // 提取行首空白（空格和 Tab）
            int wsLen = 0;
            while (wsLen < line.length() && (line.charAt(wsLen) == ' ' || line.charAt(wsLen) == '\t')) {
                wsLen++;
            }
            leadingWhitespaces.add(line.substring(0, wsLen));

            // 前进到下一行
            if (lineEnd >= end || lineEnd >= content.length()) break;
            char c = content.charAt(lineEnd);
            if (c == '\n') {
                pos = lineEnd + 1;
            } else if (c == '\r') {
                pos = lineEnd + 1;
                if (pos < content.length() && content.charAt(pos) == '\n') {
                    pos++;
                }
            } else {
                pos = lineEnd;
            }
        }

        if (leadingWhitespaces.isEmpty()) {
            return newStr; // 没有原始内容可提取
        }

        // Step 2: 确定行分隔符
        String lineSep = content.contains("\r\n") ? "\r\n" : "\n";

        // Step 3: 逐行处理 newStr
        String[] newLines = newStr.split("\\r?\\n", -1);
        boolean hasTrailingNewline = newLines.length > 1 && newLines[newLines.length - 1].isEmpty();
        int linesToProcess = hasTrailingNewline ? newLines.length - 1 : newLines.length;

        StringBuilder sb = new StringBuilder(newStr.length());
        for (int i = 0; i < linesToProcess; i++) {
            if (i > 0) {
                sb.append(lineSep);
            }
            String line = newLines[i];
            // 如果 newStr 该行已有行首空白（用户有意指定缩进），则原样保留
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                sb.append(line);
            } else {
                // 用户没写缩进 → 继承原文件对应行的缩进
                // 如果 newStr 行数多于原始行，则重复最后一行缩进
                int wsIdx = Math.min(i, leadingWhitespaces.size() - 1);
                sb.append(leadingWhitespaces.get(wsIdx)).append(line);
            }
        }
        if (hasTrailingNewline) {
            sb.append(lineSep);
        }

        return sb.toString();
    }

    int getLineStartIndex(String content, int lineNumber) {
        if (lineNumber <= 0) {
            return -1;
        }

        if (lineNumber == 1) {
            return 0;
        }

        int currentLine = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                currentLine++;
                if (currentLine == lineNumber) {
                    return i + 1;
                }
            }
        }

        return -1;
    }

    Path resolveCommandWorkPath(Path workPath, String workdir, boolean sandboxEnabled, boolean sandboxAllowUserHome) throws IOException {
        if (Assert.isEmpty(workdir) || ".".equals(workdir)) {
            return workPath;
        }
        return resolveSafePath(workPath, workdir, false, sandboxEnabled, sandboxAllowUserHome);
    }

    /**
     * 统一的命令安全校验
     *
     * <p>设计理念（参考 Anthropic sandbox-runtime）：Java 层仅做最小自保护，
     * 安全隔离的重活交给 OS 内核沙盒（Seatbelt / bwrap）。
     * 当 sandboxSystemRestrict=true 时，由 wrapCommand() 在 OS 内核级强制隔离。</p>
     *
     * @return null 表示校验通过；非 null 为错误消息
     */
    String validateCommandNoKill(String command) {
        if (Assert.isEmpty(command)) {
            return "错误：command 不能为空。";
        }

        String pid = Utils.pid();
        String lowerCmd = command.toLowerCase();

        // 1. 自保护：禁止杀死当前 Java 进程（全模式、全配置始终生效）
        String killPattern = "(?i).*(?:kill|pkill|killall)\\s+[\\s\\w]*\\b" + pid + "\\b.*";
        if (lowerCmd.matches(killPattern) ||
                lowerCmd.contains("pkill java") ||
                lowerCmd.contains("killall java")) {
            return "错误：检测到危险命令。严禁试图停止宿主进程 (PID: " + pid + ")。";
        }

        // 2. 高危自毁命令：exit / rm -rf / （全模式、全配置始终生效）
        if (lowerCmd.matches("(?i)(?:^|.*[;|&])\\s*exit\\b.*") ||
                lowerCmd.matches("(?i).*rm\\s+.*-[rR].*f\\s+/.*")) {
            return "错误：检测到高危指令。出于安全策略，禁止执行 exit、系统重启或根目录删除的操作。";
        }

        return null; // null 表示校验通过
    }

    /**
     * 构建允许在沙盒 bash 命令中使用的绝对路径前缀列表。
     * 这些路径对应 OS 级沙盒也已放行的写/读区域，确保 Java 层校验与 OS 级沙盒一致。
     */
    public Collection<String> buildAllowedAbsolutePrefixes(boolean sandboxAllowUserHome) {
        Set<String> prefixes = new HashSet<>();

        // 系统临时目录（surefire 等构建工具依赖）
        prefixes.add("/tmp");
        prefixes.add("/private/tmp");
        String tmpdir = System.getProperty("java.io.tmpdir");
        if (Assert.isNotEmpty(tmpdir)) {
            String normalized = tmpdir.replace("\\", "/");
            if (!normalized.equals("/tmp")) {
                prefixes.add(normalized);
                if (normalized.startsWith("/var/")) {
                    prefixes.add("/private" + normalized);
                }
            }
        }

        // 用户主目录（Maven/Gradle/npm 缓存等构建工具）
        if (sandboxAllowUserHome) {
            String home = System.getProperty("user.home");
            if (Assert.isNotEmpty(home)) {
                prefixes.add(home);
            }
        }

        // 设备节点
        prefixes.add("/dev/null");
        prefixes.add("/dev/tty");
        prefixes.add("/dev/stdout");
        prefixes.add("/dev/stderr");
        prefixes.add("/dev/zero");
        prefixes.add("/dev/random");
        prefixes.add("/dev/urandom");
        prefixes.add("/dev/dtracehelper");

        return prefixes;
    }

    /**
     * 检测给定的绝对路径是否在允许列表内（前缀匹配）
     */
    private boolean isAllowedAbsolutePath(String absPath, java.util.List<String> allowedPrefixes) {
        for (String prefix : allowedPrefixes) {
            if (absPath.equals(prefix) || absPath.startsWith(prefix + "/") || absPath.startsWith(prefix + File.separator)) {
                return true;
            }
        }
        return false;
    }

    private String preprocessUserHome(String pStr) {
        if (pStr == null) return null;

        // 支持 ~/ 或 ~ 转换为用户主目录
        if (pStr.equals("~") || pStr.startsWith("~/") || pStr.startsWith("~\\")) {
            String userHome = System.getProperty("user.home");
            if (pStr.length() == 1) {
                return userHome;
            } else {
                return Paths.get(userHome, pStr.substring(2)).toString();
            }
        }
        return pStr;
    }

    Path resolveSafePath(Path workPath, String pStr, boolean writeMode, boolean sandboxEnabled, boolean sandboxAllowUserHome) throws IOException {
        if (Assert.isEmpty(pStr) || ".".equals(pStr)) {
            return workPath;
        }

        if (pStr.startsWith("./")) {
            pStr = pStr.substring(2);
        }

        // 1. 如果是逻辑路径（@开头），走 mountManager 逻辑
        if (pStr.startsWith("@")) {
            Path target = mountManager.resolve(workPath, pStr);
            String alias = pStr.split("[/\\\\]")[0];
            MountDir mount = mountManager.getMount(alias);

            if (mount == null || !mount.isEnabled()) {
                throw new SecurityException("权限拒绝：未知的挂载点 " + pStr);
            }

            if (writeMode && !mount.isWriteable()) {
                throw new SecurityException(
                        "权限拒绝：路径 " + pStr + " 属于只读挂载点，禁止写入。请将结果写入工作区的相对路径。");
            }

            // 符号链接防护：解析真实路径
            if (sandboxEnabled) {
                Path realMountPath = mount.getRealPath().toRealPath();
                Path realTarget;
                try {
                    realTarget = target.toRealPath();
                } catch (NoSuchFileException e) {
                    realTarget = resolveExistingAncestor(target).toRealPath();
                }
                if (!realTarget.startsWith(realMountPath)) {
                    throw new SecurityException("权限拒绝：符号链接越界（沙盒模式已开启）。");
                }
            }

            return target;
        }

        // 2. 处理物理路径
        String pStr2 = preprocessUserHome(pStr);
        Path p = Paths.get(pStr2);
        Path target;

        if (p.isAbsolute()) {
            // 【沙盒模式】拦截绝对路径
            if (sandboxEnabled) {
                // sandboxAllowUserHome=true 且原始输入以 /Users/noear 开头 → 放行
                if (!(sandboxAllowUserHome && pStr.startsWith("~"))) {
                    throw new SecurityException("权限拒绝：沙盒模式下禁止使用绝对路径。");
                }
            }
            target = p.normalize();
        } else {
            // 相对路径
            target = workPath.resolve(pStr2).normalize();
        }

        // 3. 越界检查（沙盒模式）
        if (sandboxEnabled) {
            boolean isUserHomeAccess = sandboxAllowUserHome && pStr.startsWith("~");
            if (!isUserHomeAccess) {
                // 符号链接防护：先解析真实路径再判断
                try {
                    Path realTarget;
                    try {
                        realTarget = target.toRealPath();
                    } catch (NoSuchFileException e) {
                        realTarget = resolveExistingAncestor(target).toRealPath();
                    }
                    Path realWorkPath = workPath.toRealPath();
                    if (!realTarget.startsWith(realWorkPath)) {
                        throw new SecurityException("权限拒绝：路径越界（沙盒模式已开启）。");
                    }
                } catch (NoSuchFileException e) {
                    // 目标路径及其已存在祖先均不存在，回退到字符串检查。
                    if (!target.startsWith(workPath)) {
                        throw new SecurityException("权限拒绝：路径越界（沙盒模式已开启）。");
                    }
                }
            }
        }

        return target;
    }



    boolean isSandboxBoundaryDenied(Path rootPath, Path target, boolean sandboxEnabled) {
        if (!sandboxEnabled) {
            return false;
        }
        try {
            Path realRoot = rootPath.toRealPath();
            Path realTarget;
            try {
                if (Files.exists(target)) {
                    realTarget = target.toRealPath();
                } else {
                    Path ancestor = resolveExistingAncestor(target);
                    Path relative = ancestor.relativize(target.normalize());
                    realTarget = ancestor.toRealPath().resolve(relative).normalize();
                }
            } catch (IOException | IllegalArgumentException e) {
                realTarget = target.normalize();
            }
            return !realTarget.startsWith(realRoot);
        } catch (IOException e) {
            return false;
        }
    }





    // mandatory deny files (same as dangerous files + cli-specific paths)
    private static final List<String> MANDATORY_DENY_FILES = Collections.unmodifiableList(Arrays.asList(
            ".gitconfig", ".gitmodules", ".bashrc", ".bash_profile", ".bash_logout",
            ".zshrc", ".zprofile", ".profile", ".ripgreprc", ".mcp.json",
            ".soloncode/commands", ".soloncode/agents"
    ));

    private static final List<String> MANDATORY_DENY_DIRS = Collections.unmodifiableList(Arrays.asList(
            ".vscode", ".idea", ".soloncode/commands", ".soloncode/agents", ".git/hooks"
    ));

    public static boolean isMandatoryDenyRelativePath(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        String normalized = relativePath.replace("\\", "/");
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        for (String denyFile : MANDATORY_DENY_FILES) {
            if (normalized.equals(denyFile)
                    || normalized.startsWith(denyFile + "/")
                    || normalized.endsWith("/" + denyFile)
                    || normalized.contains("/" + denyFile + "/")) {
                return true;
            }
        }
        for (String denyDir : MANDATORY_DENY_DIRS) {
            if (normalized.equals(denyDir) || normalized.startsWith(denyDir + "/")
                    || normalized.endsWith("/" + denyDir)
                    || normalized.contains("/" + denyDir + "/")) {
                return true;
            }
        }
        return false;
    }


    String formatDisplayPath(Path workPath, String inputPath, Path targetDir, Path file, boolean sandboxEnabled) {
        if (inputPath != null && inputPath.startsWith("@")) {
            String prefix = inputPath.split("[/\\\\]")[0];
            return prefix + "/" + targetDir.relativize(file).toString().replace("\\", "/");
        }


        // 开放模式下，如果文件不在 workPath 内部，返回绝对路径字符串
        if (!sandboxEnabled && !file.startsWith(workPath)) {
            return file.toAbsolutePath().toString().replace("\\", "/");
        }

        try {
            return workPath.relativize(file).toString().replace("\\", "/");
        } catch (IllegalArgumentException e) {
            return file.toAbsolutePath().toString().replace("\\", "/");
        }
    }

    String toMountEnvKey(String alias) {
        String raw = alias.startsWith("@") ? alias.substring(1) : alias;
        String envKey = raw.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        if (envKey.isEmpty() || Character.isDigit(envKey.charAt(0))) {
            envKey = "MOUNT_" + envKey;
        }
        return envKey;
    }

    String translateCommandToEnv(String command, Map<String, String> envs, boolean sandboxEnabled, boolean sandboxAllowUserHome) {
        String result = command;
        for (MountDir mount : mountManager.getMounts()) {
            if (mount.isEnabled()) {
                String alias = mount.getAlias(); // 例如 @pool1
                String envKey = toMountEnvKey(alias); // POOL1

                // 仅注入命令中实际使用的环境变量（减少污染）
                if (result.contains(alias)) {
                    envs.put(envKey, mount.getRealPath().toString());
                    String placeholder = getEnvPlaceholder(envKey);

                    // 精确替换：仅替换作为路径前缀出现的 @alias（后跟 / 或 \\ 或在行尾）
                    result = result.replaceAll(
                            java.util.regex.Pattern.quote(alias) + "(?=[/\\\\\\s]|$)",
                            java.util.regex.Matcher.quoteReplacement(placeholder)
                    );
                }
            }
        }

        // ~ 路径处理（统一所有 shell 模式）
        if (containsUserHomePath(result)) {
            if (sandboxEnabled && !sandboxAllowUserHome) {
                throw new SecurityException("权限拒绝：沙盒模式下禁止使用 ~ 路径（sandboxAllowUserHome 已关闭）。");
            }
            result = expandUserHomePaths(result);
        }

        return result;
    }

    boolean containsUserHomePath(String command) {
        return command != null && command.matches("(?s).*(^|[\\s=:\\(\\[\\{;|&<>])~(?=$|[/\\\\\\s'\"`)\\]\\};|&<>]).*");
    }

    private String expandUserHomePaths(String command) {
        String userHome = System.getProperty("user.home").replace("\\", "/");
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(^|[\\s=:\\(\\[\\{;|&<>])~(?=$|[/\\\\\\s'\"`)\\]\\};|&<>])");
        java.util.regex.Matcher matcher = pattern.matcher(command);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(matcher.group(1) + userHome));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    String getEnvPlaceholder(String envKey) {
        if (this.shellMode == ShellMode.CMD) {
            return "%" + envKey + "%";
        }
        if (this.shellMode == ShellMode.POWERSHELL) {
            return "$env:" + envKey;
        }
        return "$" + envKey;
    }

    private Path resolveExistingAncestor(Path target) throws IOException {
        Path current = target.getParent();
        while (current != null) {
            if (Files.exists(current)) {
                return current;
            }
            current = current.getParent();
        }
        throw new NoSuchFileException(String.valueOf(target));
    }

    Path getSandboxPolicyRoot(Path workPath, String inputPath) {
        if (inputPath != null && inputPath.startsWith("@")) {
            String alias = inputPath.split("[/\\\\]")[0];
            MountDir mount = mountManager.getMount(alias);
            if (mount != null && mount.getRealPath() != null) {
                return mount.getRealPath();
            }
        }
        return workPath;
    }

    void generateTreeInternal(Path workPath, Path current, int depth, int maxDepth, String indent, StringBuilder sb, boolean showHidden, boolean sandboxEnabled) throws IOException {
        if (depth >= maxDepth) return;
        try (Stream<Path> stream = Files.list(current)) {
            List<Path> children = stream
                    .filter(p -> !isIgnored(workPath, p))
                    .filter(p -> !isSandboxBoundaryDenied(workPath, p, sandboxEnabled))
                    .filter(p -> showHidden || !p.getFileName().toString().startsWith("."))
                    .sorted((a, b) -> {
                        boolean aDir = Files.isDirectory(a);
                        boolean bDir = Files.isDirectory(b);
                        if (aDir != bDir) return aDir ? -1 : 1;
                        return a.getFileName().compareTo(b.getFileName());
                    }).collect(Collectors.toList());

            for (int i = 0; i < children.size(); i++) {
                Path child = children.get(i);
                boolean isLast = (i == children.size() - 1);
                boolean isDir = Files.isDirectory(child);
                if (isSandboxBoundaryDenied(workPath, child, sandboxEnabled)) {
                    continue;
                }
                sb.append(indent).append(isLast ? "└── " : "├── ").append(child.getFileName()).append("\n");
                if (isDir)
                    generateTreeInternal(workPath, child, depth + 1, maxDepth, indent + (isLast ? "    " : "│   "), sb, showHidden, sandboxEnabled);
            }
        } catch (AccessDeniedException e) {
            sb.append(indent).append("└── [拒绝访问]\n");
        }
    }

    String flatListLogic(Path workPath, Path policyRoot, Path target, String inputPath, boolean showHidden, boolean sandboxEnabled) throws IOException {
        try (Stream<Path> stream = Files.list(target)) {
            List<String> lines = stream
                    .filter(p -> !isIgnored(workPath, p))
                    .filter(p -> !isSandboxBoundaryDenied(policyRoot, p, sandboxEnabled))
                    .filter(p -> showHidden || !p.getFileName().toString().startsWith("."))
                    .map(p -> {
                        boolean isDir = Files.isDirectory(p);
                        String displayPath = formatDisplayPath(workPath, inputPath, target, p, sandboxEnabled);
                        return (isDir ? "[DIR] " : "[FILE] ") + displayPath + (isDir ? "/" : "");
                    }).sorted().collect(Collectors.toList());
            return lines.isEmpty() ? "(目录为空)" : String.join("\n", lines);
        }
    }


    boolean isIgnored(Path workPath, Path path) {
        String name = path.getFileName().toString();
        if (ignoreDirs.contains(name)) return true;
        try {
            // 只有在 workPath 内部时才进行递归片段检查
            if (path.startsWith(workPath)) {
                Path relative = workPath.relativize(path);
                for (Path segment : relative) {
                    if (ignoreDirs.contains(segment.toString())) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    boolean isNotTextFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();

        // 1. 基于已知二进制后缀的快速过滤
        if (fileName.endsWith(".class") || fileName.endsWith(".jar")  ||
                fileName.endsWith(".war")   || fileName.endsWith(".exe")   ||
                fileName.endsWith(".dll")   || fileName.endsWith(".so")    ||
                fileName.endsWith(".a")     || fileName.endsWith(".lib")   ||
                fileName.endsWith(".o")     || fileName.endsWith(".obj")   ||
                fileName.endsWith(".pyc")   || fileName.endsWith(".pyo")   ||
                fileName.endsWith(".png")   || fileName.endsWith(".jpg")   ||
                fileName.endsWith(".jpeg")  || fileName.endsWith(".gif")   ||
                fileName.endsWith(".webp")  || fileName.endsWith(".ico")   ||
                fileName.endsWith(".zip")   || fileName.endsWith(".gz")    ||
                fileName.endsWith(".tar")   || fileName.endsWith(".bz2")   ||
                fileName.endsWith(".7z")    || fileName.endsWith(".rar")   ||
                fileName.endsWith(".pdf")   || fileName.endsWith(".doc")   ||
                fileName.endsWith(".docx")  || fileName.endsWith(".xls")   ||
                fileName.endsWith(".xlsx")  || fileName.endsWith(".ppt")   ||
                fileName.endsWith(".pptx")  || fileName.endsWith(".odt")   ||
                fileName.endsWith(".ods")   || fileName.endsWith(".odp")   ||
                fileName.endsWith(".bin")   || fileName.endsWith(".dat")   ||
                fileName.endsWith(".wasm")  || fileName.endsWith(".class")) {
            return true;
        }

        // 2. 字节级内容兜底：识别无后缀（或后缀伪装）的二进制文件，
        //    如编译产物、core dump、无扩展名的 ELF 等。读取文件首部采样探测，
        //    避免后缀白名单漏判后将二进制按文本强行解码输出乱码。
        return isLikelyBinaryFile(file);
    }

    /**
     * 读取文件首部采样，按字节判定是否疑似二进制。
     *
     * <p>判定规则与命令输出探测保持一致：NUL 直接判定；统计非空白控制字符占比，
     * 超过阈值判定为二进制。对常见空白、ANSI 转义及 UTF-8 多字节高位字节（&gt;= 0x80）
     * 做豁免，避免误伤中文等多字节文本。探测失败（IO 异常）时保守按文本处理。</p>
     */
    private boolean isLikelyBinaryFile(Path file) {
        byte[] buffer = new byte[4096];
        int read;
        try (java.io.InputStream in = Files.newInputStream(file)) {
            read = in.read(buffer);
        } catch (Throwable e) {
            return false; // 读不到就保守按文本，交由后续流程处理
        }

        if (read <= 0) {
            return false;
        }

        int suspicious = 0;
        for (int i = 0; i < read; i++) {
            int b = buffer[i] & 0xFF;
            if (b == 0x00) {
                return true; // NUL 是二进制强特征
            }
            if (b == '\n' || b == '\r' || b == '\t' || b == 0x1B || b >= 0x80) {
                continue;
            }
            if (b < 0x20) {
                suspicious++;
            }
        }

        return suspicious * 100 > read * 30; // 控制字符占比 > 30%
    }
}
