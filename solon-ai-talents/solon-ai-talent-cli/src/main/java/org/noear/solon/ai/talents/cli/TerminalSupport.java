/*
 * Copyright 2017-2026 noear.org and authors
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

import org.noear.solon.ai.sandbox.config.FilesystemConfig;
import org.noear.solon.ai.mount.Mount;
import org.noear.solon.ai.mount.MountManager;
import org.noear.solon.ai.mount.ResolvedResource;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.talents.cli.i18n.CliI18n;
import org.noear.solon.ai.talents.cli.i18n.CliI18nKeys;
import org.noear.solon.core.util.Assert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    /** 宿主配置目录（规范化后以 / 结尾，如 {@code .solon/}）；受保护子目录与物化缓存目录都由它推导。 */
    private final String harnessHome;
    /** 由 harnessHome 推导的受保护相对路径（commands / agents / 物化缓存）。 */
    private final List<String> harnessDenyPaths;
    /** 可物化虚拟来源的执行缓存；null 表示未启用物化（如测试里只构造 support 时）。 */
    private volatile MountMaterializer materializer;
    /** 与物化器一致的绝对缓存根，用于文件工具层的路径拒绝。 */
    private volatile Path materializeCacheRoot;

    TerminalSupport(MountManager mountManager, Set<String> ignoreDirs, ShellMode shellMode) {
        this(mountManager, ignoreDirs, shellMode, null);
    }

    TerminalSupport(MountManager mountManager, Set<String> ignoreDirs, ShellMode shellMode, String harnessHome) {
        this.mountManager = mountManager;
        this.ignoreDirs = ignoreDirs;
        this.shellMode = shellMode;
        this.harnessHome = normalizeHarnessHome(harnessHome);
        this.harnessDenyPaths = harnessDenyPaths(this.harnessHome);
    }

    String getHarnessHome() {
        return harnessHome;
    }

    /** 宿主状态目录（相对工作区，已带 harnessHome 前缀）：模型的文件工具与 bash 都不得读写。 */
    private volatile List<String> protectedStatePaths = Collections.emptyList();

    /**
     * 设置受保护的宿主状态子路径（相对 harnessHome，如 {@code sessions}、{@code memory}）。
     * 与 commands/agents 不同，这些路径读也拒绝：里面是其它会话/用户的数据。
     */
    void setProtectedStatePaths(Collection<String> subPaths) {
        List<String> list = new ArrayList<>();
        if (subPaths != null) {
            for (String sub : subPaths) {
                if (sub == null) {
                    continue;
                }
                String s = sub.trim().replace("\\", "/");
                while (s.startsWith("/") || s.startsWith("./")) {
                    s = s.substring(s.startsWith("/") ? 1 : 2);
                }
                while (s.endsWith("/")) {
                    s = s.substring(0, s.length() - 1);
                }
                if (s.isEmpty() || s.contains("..")) {
                    throw new IllegalArgumentException("Invalid protected state path: " + sub);
                }
                list.add(harnessHome + s);
            }
        }
        this.protectedStatePaths = Collections.unmodifiableList(list);
    }

    List<String> getProtectedStatePaths() {
        return protectedStatePaths;
    }

    /**
     * 设置物化缓存管理器（由 TerminalTalent 构造后接线）。
     *
     * @param materializer 物化缓存管理器；null 表示禁用物化
     */
    void setMaterializer(MountMaterializer materializer) {
        this.materializer = materializer;
        this.materializeCacheRoot = materializer == null ? null : materializer.getCacheRoot();
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
            throw new CliArgumentException(CliI18nKeys.TERMINAL_APPLY_EMPTY_OLD);
        }

        if (oldStr.equals(newStr)) {
            return content; // 内容相同无需处理
        }

        String finalOld = normalizeNewlines(content, oldStr);
        String finalNew = normalizeNewlines(content, newStr);

        if (replaceAll) {
            if (!content.contains(finalOld)) {
                throw new CliArgumentException(CliI18nKeys.TERMINAL_APPLY_ALL_MISSING);
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
                throw new CliArgumentException(CliI18nKeys.TERMINAL_APPLY_MISSING);
            }
            if (content.lastIndexOf(finalOld) != firstIndex) {
                throw new CliArgumentException(CliI18nKeys.TERMINAL_APPLY_NOT_UNIQUE);
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
        return findLooseMatchDiagnostics(content, oldStr, lineStartIndex, null);
    }

    /**
     * 多策略匹配诊断（诊断文案按指定语言；null 为默认语言）
     */
    String findLooseMatchDiagnostics(String content, String oldStr, int lineStartIndex, Locale locale) {
        // 先将 1-based 行号转换为字符偏移量（和 findAtStartLine 的转换方式保持一致）
        int startOffset = getLineStartIndex(content, lineStartIndex);
        if (startOffset < 0) {
            startOffset = 0; // 行号超出范围时的回退
        }

        // 先尝试 trim 策略检查
        String diag = tryTrimMatchDiagnostics(content, oldStr, startOffset, "trim", locale);
        if (diag == null) {
            return null; // trim 策略匹配成功
        }

        // trim 策略失败，检查是否是 whitespace 折叠可以解决的差异
        String collapseDiag = tryTrimMatchDiagnostics(content, oldStr, startOffset, "collapse", locale);
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
    private String tryTrimMatchDiagnostics(String content, String oldStr, int startOffset, String mode, Locale locale) {
        List<String> contentLines = extractTrimmedLines(content, startOffset);
        if (contentLines.isEmpty()) {
            return CliI18n.get(locale, CliI18nKeys.TERMINAL_DIAG_NO_CONTENT);
        }

        String[] oldStrLinesRaw = oldStr.split("\\r?\\n", -1);
        List<String> oldStrLines = trimTrailingEmptyLines(oldStrLinesRaw);
        if (oldStrLines.isEmpty()) {
            return CliI18n.get(locale, CliI18nKeys.TERMINAL_DIAG_BLANK);
        }

        if (oldStrLines.size() > contentLines.size()) {
            return CliI18n.fmt(locale, CliI18nKeys.TERMINAL_DIAG_LINES,
                    "expected", oldStrLines.size(), "actual", contentLines.size());
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
                    return CliI18n.fmt(locale, CliI18nKeys.TERMINAL_DIAG_MISMATCH_COLLAPSE,
                            "line", fileLineNum, "expected", expected, "actual", actual);
                } else {
                    return CliI18n.fmt(locale, CliI18nKeys.TERMINAL_DIAG_MISMATCH,
                            "line", fileLineNum, "expected", expected, "actual", actual);
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

    /** 类 Unix 的进程终止动词（PowerShell 里 {@code kill} 也是 Stop-Process 的别名，故不分平台都查） */
    private static final Pattern UNIX_KILL_VERB =
            Pattern.compile("\\b(?:kill|pkill|killall)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Windows 下「杀宿主进程」的终止动词。
     *
     * <p>Unix 侧只靠 {@code kill|pkill|killall} 关键字就够了，Windows 完全不同：
     * {@code taskkill /F /PID 1234} 里 PID 前面隔着 {@code /F /PID} 这种含 {@code /} 的开关（不属于
     * {@code [\s\w]}，所以旧正则永不命中），而 {@code Stop-Process -Id 1234} /
     * {@code Stop-Process -Name java} 连 {@code kill} 字样都没有。引导词里已向模型明文承诺
     * 「严禁 taskkill /IM java.exe、Stop-Process -Name java」，这里必须有对应的硬拦截，
     * 否则两个平台的自保护强度不对等。</p>
     *
     * <p>{@code spps} 是 PowerShell 里 {@code Stop-Process} 的别名（{@code kill} 也是，已由 Unix
     * 分支的关键字覆盖）。</p>
     */
    private static final Pattern WINDOWS_KILL_VERB =
            Pattern.compile("\\b(?:taskkill|stop-process|spps)\\b", Pattern.CASE_INSENSITIVE);

    /** 按进程名批量终止 java（{@code /IM java.exe}、{@code -Name java}、{@code -Name javaw}） */
    private static final Pattern WINDOWS_KILL_JAVA_BY_NAME =
            Pattern.compile("\\bjavaw?(?:\\.exe)?\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 删除动词（含 PowerShell 别名 {@code ri}/{@code rm}、CMD 的 {@code rd}/{@code rmdir}/{@code del}）。
     */
    private static final Pattern WINDOWS_DELETE_VERB = Pattern.compile(
            "(?:\\bremove-item\\b|\\bri\\b|\\brm\\b|\\brd\\b|\\brmdir\\b|\\bdel\\b|\\berase\\b)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Windows 高危删除目标：盘符根（{@code C:\}、{@code C:/}）与系统目录。
     *
     * <p>这是 {@code rm -rf /} 在 Windows 上的等价物：{@code Remove-Item -Recurse -Force C:\}、
     * {@code rd /s /q C:\}。同样包含环境变量写法（{@code %SystemRoot%}、{@code $env:windir}），
     * 否则一个变量就能绕过字面量匹配。</p>
     *
     * <p><b>系统目录与用户目录区别对待</b>：{@code C:\Windows}、{@code Program Files} 连子路径一并拦
     * （删其中任何一块都不是正常开发动作）；而用户目录（{@code C:\Users}、{@code %USERPROFILE%}）
     * <b>只拦目录自身</b>。后者如果连子路径一起拦，{@code Remove-Item -Recurse
     * $env:USERPROFILE\.gradle\caches}、{@code del %USERPROFILE%\Downloads\x.zip} 这类日常清理就全部被误伤，
     * 而 Unix 侧对应的 {@code rm -rf ~/.gradle/caches} 是放行的——两个平台的护栏强度必须一致。</p>
     */
    private static final Pattern WINDOWS_CRITICAL_TARGET = Pattern.compile(
            "(?:(?<![\\w:])[a-z]:[\\\\/](?=[\\s\"']|$)"
                    + "|(?<![\\w:])[a-z]:[\\\\/](?:windows|winnt|programdata|program files(?: \\(x86\\))?)\\b"
                    + "|(?<![\\w:])[a-z]:[\\\\/]users[\\\\/]?(?=[\\s\"']|$)"
                    + "|%(?:systemroot|windir|systemdrive|programfiles)%"
                    + "|\\$env:(?:systemroot|windir|systemdrive|programfiles)\\b"
                    + "|%userprofile%[\\\\/]?(?=[\\s\"']|$)"
                    + "|\\$env:userprofile[\\\\/]?(?=[\\s\"']|$))",
            Pattern.CASE_INSENSITIVE);

    /**
     * 类 Unix 的「递归强删根目录 / 系统目录」。
     *
     * <p><b>旧写法的误伤</b>：{@code .*rm\s+.*-[rR].*f\s+/.*} 只要删除目标以 {@code /} 开头就命中，
     * 于是 {@code rm -rf /tmp/build}、{@code rm -rf /var/folders/xx/T/xxx}（macOS 临时目录）这类
     * 完全正当、且已在临时目录白名单内的清理命令全部被拦，Linux/macOS 上的误报率远高于命中率。</p>
     *
     * <p>现改为只认「目标就是根目录或某个系统目录本身」：{@code rm -rf /}、{@code rm -rf /usr} 拦；
     * {@code rm -rf /usr/local/lib/node_modules/x}（Homebrew 区）、{@code rm -rf /tmp/x} 放行。</p>
     *
     * <p><b>递归/强制开关改用两个 lookahead 分开认</b>：只列举 {@code -rf}、{@code -fr}、{@code -r -f}
     * 这些组合永远会漏——{@code rm --recursive --force /}、{@code rm -r --force /} 都是合法的 GNU 写法，
     * 一个长选项就能绕过整条护栏。现在只要同一命令段内同时出现「含 r 的短选项或 --recursive」与
     * 「含 f 的短选项或 --force」即命中，与书写顺序无关。开关判定宽一点不危险：精度由后面的
     * 目标限定（必须是根/系统目录本身）撑着。</p>
     *
     * <p><b>目标前的引号要可选吞掉</b>：{@code rm -rf "/"}、{@code rm -rf '/usr'} 与裸写法危害完全相同，
     * 而只写 {@code \s+/} 会因为中间夹了一个引号导致整条不命中。Windows 侧的
     * {@link #WINDOWS_CRITICAL_TARGET} 已在收尾 lookahead 里认了 {@code "'}，两侧须对称。</p>
     *
     * <p><b>为何把 {@code ~} / {@code $HOME} 也当临界目标</b>：本校验跑在
     * {@link #translateCommandToEnv} 之前，看到的是未展开的原文，所以 {@code rm -rf ~} 既不会命中
     * {@code /home} 字面量，也不会命中任何其它分支——而 Windows 侧的 {@code %USERPROFILE%} /
     * {@code $env:USERPROFILE} 是拦的。同样只拦目录自身：{@code rm -rf ~/.gradle/caches} 放行。</p>
     */
    private static final Pattern UNIX_RM_CRITICAL = Pattern.compile(
            "\\brm\\b"
                    + "(?=[^;|&\\r\\n]*?(?:\\s-[a-z]*r|\\s--recursive\\b))"
                    + "(?=[^;|&\\r\\n]*?(?:\\s-[a-z]*f|\\s--force\\b))"
                    + "[^;|&\\r\\n]*?\\s+[\"']?(?:/\\*?"
                    + "|/(?:etc|usr|bin|sbin|lib|lib64|boot|proc|sys|dev|var|opt|root|home|users"
                    + "|system|library|applications)/?"
                    + "|~[\\\\/]?|\\$\\{?home\\}?[\\\\/]?)"
                    + "(?=[\\s\"';|&]|$)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 统一的命令安全校验
     *
     * <p>设计理念（参考 Anthropic sandbox-runtime）：Java 层仅做最小自保护，
     * 安全隔离的重活交给 OS 内核沙盒（Seatbelt / bwrap）。
     * 当 sandboxSystemRestrict=true 时，由 wrapCommand() 在 OS 内核级强制隔离。</p>
     *
     * <p><b>平台对等</b>：自保护与根目录删除这两条红线必须在 Unix / CMD / PowerShell 上强度一致。
     * 只在引导词里写「严禁」而不做硬拦截，等于给了一个不存在的护栏。
     * Windows 侧的 1b / 2b 以「宿主 OS 是 Windows」为适用判据（见 {@link #isWindowsRiskScope()}），
     * 以免 Windows 上跑 {@link ShellMode#UNIX_SHELL} 时整条护栏隐形失效。</p>
     *
     * @return null 表示校验通过；非 null 为错误消息
     */
    String validateCommandNoKill(String command) {
        return validateCommandNoKill(command, null);
    }

    /**
     * 统一安全校验（错误文案按指定语言；null 为默认语言）
     */
    String validateCommandNoKill(String command, Locale locale) {
        if (Assert.isEmpty(command)) {
            return CliI18n.get(locale, CliI18nKeys.TERMINAL_VALIDATE_EMPTY);
        }

        String pid = Utils.pid();
        String lowerCmd = command.toLowerCase();

        // 1. 自保护：禁止杀死当前 Java 进程（全模式、全配置始终生效）
        if (isUnixHostKill(lowerCmd, pid)) {
            return CliI18n.fmt(locale, CliI18nKeys.TERMINAL_VALIDATE_KILL, "pid", pid);
        }

        // 1b. Windows 侧的等价形态（taskkill / Stop-Process）
        if (isWindowsRiskScope() && isWindowsHostKill(command, pid)) {
            return CliI18n.fmt(locale, CliI18nKeys.TERMINAL_VALIDATE_KILL, "pid", pid);
        }

        // 2. 高危自毁命令：exit / 递归强删根目录或系统目录（全模式、全配置始终生效）
        if (lowerCmd.matches("(?i)(?:^|.*[;|&])\\s*exit\\b.*") ||
                UNIX_RM_CRITICAL.matcher(lowerCmd).find()) {
            return CliI18n.get(locale, CliI18nKeys.TERMINAL_VALIDATE_EXIT);
        }

        // 2b. Windows 侧的盘符根/系统目录删除
        if (isWindowsRiskScope()
                && WINDOWS_DELETE_VERB.matcher(command).find()
                && WINDOWS_CRITICAL_TARGET.matcher(command).find()) {
            return CliI18n.get(locale, CliI18nKeys.TERMINAL_VALIDATE_RM_WINDOWS);
        }

        return null; // null 表示校验通过
    }

    /**
     * Windows 专属红线（自保护 1b、盘符根/系统目录删除 2b）的适用范围。
     *
     * <p><b>判据是宿主 OS，不是 shell 方言</b>：旧实现只看 {@code shellMode == CMD || POWERSHELL}，
     * 于是在 Windows 宿主上一旦方言是 {@link ShellMode#UNIX_SHELL}（Git Bash / MSYS / WSL 风格的会话，
     * 或用户通过 shell override 显式指定），两条红线整体失效——而 {@code taskkill /IM java.exe}、
     * {@code Remove-Item -Recurse -Force C:\} 在那种会话里照样能执行并杀掉宿主 JVM。
     * 护栏的成立条件是「命令可能在 Windows 宿主上生效」，与用哪种方言书写无关。</p>
     *
     * <p><b>为何仍保留方言判定</b>：一是非 Windows 宿主上声明 Windows 方言时（单元测试即如此）
     * 护栏依然要可验证；二是这两条规则在类 Unix 宿主上本就不可能命中真实命令
     * （没有 {@code taskkill}、没有盘符），多判一次零成本、无误伤风险。</p>
     */
    private boolean isWindowsRiskScope() {
        return EnvironmentResolver.isWindows()
                || this.shellMode == ShellMode.CMD
                || this.shellMode == ShellMode.POWERSHELL;
    }

    /**
     * 是否属于类 Unix 下「指向宿主进程」的终止命令。
     *
     * <p><b>旧实现的洞</b>：旧正则为 {@code (?:kill|pkill|killall)\s+[\s\w]*\b<pid>\b}——
     * {@code [\s\w]} 不包含 {@code -}，所以只要动词与 PID 之间夹了任何开关（而“带信号”才是
     * 真实用法）就彻底失效：{@code kill -9 <pid>}、{@code kill -TERM <pid>}、{@code killall -9 java}
     * 全部不命中。而引导词里写的是「严禁执行 kill -9 任何数字」——同样是一个对模型承诺了
     * 却不存在的护栏。此处改为按「同一命令段内出现终止动词 + 目标」判定。</p>
     *
     * <p><b>不能误伤 {@code pkill -P <pid>}</b>：它只终止宿主的<b>子进程</b>，是引导词明确推荐的
     * 清理方式，必须显式放行；否则新护栏会把自己推荐的用法也拦了。</p>
     *
     * <p>跨命令段（{@code ; | &}）不算：{@code kill 111; echo <pid>} 里的 PID 只是一个被打印的
     * 数字，与终止目标无关。</p>
     */
    private static boolean isUnixHostKill(String lowerCmd, String pid) {
        if (UNIX_KILL_VERB.matcher(lowerCmd).find() == false) {
            return false;
        }
        String quotedPid = Pattern.quote(pid);
        if (lowerCmd.matches("(?s).*\\bpkill\\b[^\\r\\n;|&]*\\s-p\\s+" + quotedPid + "\\b.*")) {
            return false; // pkill -P <pid>：只杀子进程，放行
        }
        if (lowerCmd.matches("(?s).*\\b(?:kill|pkill|killall)\\b[^\\r\\n;|&]*\\b" + quotedPid + "\\b.*")) {
            return true;
        }
        // 按进程名批量终止 java（pkill java、killall -9 java、pkill -f javaw）
        return lowerCmd.matches("(?s).*\\b(?:pkill|killall)\\b[^\\r\\n;|&]*\\bjavaw?\\b.*");
    }

    /**
     * 是否属于 Windows 下「指向宿主进程」的终止命令。
     *
     * <p>三种形态：① 终止动词 + 宿主 PID；② 终止动词 + java 进程名（批量杀必然误伤自己）；
     * ③ {@code Get-Process java | Stop-Process} 这种管道形式——终止动词后面没有任何参数，
     * 但“java”仍出现在同一条命令里，故第二条能覆盖。</p>
     */
    private static boolean isWindowsHostKill(String command, String pid) {
        if (WINDOWS_KILL_VERB.matcher(command).find() == false) {
            return false;
        }
        // ① 直接指向宿主 PID（taskkill /PID 1234、Stop-Process -Id 1234）
        if (Pattern.compile("\\b" + Pattern.quote(pid) + "\\b").matcher(command).find()) {
            return true;
        }
        // ②/③ 按进程名批量终止 java
        return WINDOWS_KILL_JAVA_BY_NAME.matcher(command).find();
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
            // Windows 的 java.io.tmpdir 带尾部分隔符（如 C:\...\Temp\），不剥除的话
            // isAllowedAbsolutePath 里的 prefix + "/" 会拼成 "Temp//"，永远匹配不上，
            // 临时目录白名单在 Windows 上等于完全失效
            String normalized = stripTrailingSeparator(tmpdir.replace("\\", "/"));
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
     * 检测给定的绝对路径是否在允许列表内（前缀匹配）。
     *
     * <p>Windows 路径大小写不敏感（{@code C:/Users} 与 {@code c:/users} 是同一个目录），
     * 用大小写敏感的 {@code startsWith} 比前缀会漏判；类 Unix 仍按大小写敏感比较。</p>
     */
    private boolean isAllowedAbsolutePath(String absPath, java.util.List<String> allowedPrefixes) {
        boolean ignoreCase = EnvironmentResolver.isWindows();
        for (String prefix : allowedPrefixes) {
            if (startsWithPath(absPath, prefix, ignoreCase)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithPath(String absPath, String prefix, boolean ignoreCase) {
        if (ignoreCase) {
            absPath = absPath.toLowerCase(Locale.ROOT);
            prefix = prefix.toLowerCase(Locale.ROOT);
        }
        return absPath.equals(prefix)
                || absPath.startsWith(prefix + "/")
                || absPath.startsWith(prefix + File.separator);
    }

    private static String stripTrailingSeparator(String path) {
        int end = path.length();
        while (end > 1 && (path.charAt(end - 1) == '/' || path.charAt(end - 1) == '\\')) {
            end--;
        }
        return end == path.length() ? path : path.substring(0, end);
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
        return resolveSafePath(workPath, pStr, writeMode, sandboxEnabled, sandboxAllowUserHome, null);
    }

    Path resolveSafePath(Path workPath, String pStr, boolean writeMode, boolean sandboxEnabled, boolean sandboxAllowUserHome, FilesystemConfig fsConfig) throws IOException {
        if (Assert.isEmpty(pStr) || ".".equals(pStr)) {
            enforceFilesystemPolicy(workPath, workPath, writeMode, sandboxEnabled, fsConfig);
            return workPath;
        }

        if (pStr.startsWith("./")) {
            pStr = pStr.substring(2);
        }

        // 1. 如果是逻辑路径（@开头），走 mountManager 逻辑
        if (pStr.startsWith("@")) {
            ResolvedResource resource = mountManager.resolveResource(pStr);
            Optional<Path> localPath = resource.getLocalPath();
            if (!localPath.isPresent()) {
                throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_NOT_LOCAL, "path", pStr);
            }
            Path target = localPath.get();
            Mount mount = resource.getMount();
            if (mount == null && !"@workspace".equals(resource.getMountAlias())) {
                throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_UNKNOWN_MOUNT, "path", pStr);
            }
            if (writeMode && (mount != null && !mount.isWritable()
                    || !resource.getSource().capabilities().isWritable())) {
                throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_READONLY_MOUNT, "path", pStr);
            }
            Optional<Path> localRoot = resource.getSource().getLocalRoot();
            if (!localRoot.isPresent()) {
                throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_NO_LOCAL_ROOT, "path", pStr);
            }
            Path mountRoot = localRoot.get();

            // 符号链接防护：解析真实路径。
            // 挂载根目录可以是尚未创建的用户目录，此时不能直接调用 toRealPath()。
            if (sandboxEnabled) {
                checkLocalMountPathSafety(mountRoot, target);
            }

            enforceFilesystemPolicy(mountRoot, target, writeMode, sandboxEnabled, fsConfig);
            return target;
        }

        // 2. 处理物理路径
        String pStr2 = preprocessUserHome(pStr);
        Path p = Paths.get(pStr2);
        Path target;

        if (p.isAbsolute()) {
            // 【沙盒模式】拦截绝对路径
            if (sandboxEnabled) {
                // 用户目录仅允许通过 ~ 访问；物化缓存是系统内部路径，先放行到统一
                // mandatory deny 检查，避免因缓存位于用户目录而无法完成安全拒绝。
                if (!(sandboxAllowUserHome && pStr.startsWith("~"))
                        && !isMaterializeCachePath(p.normalize())) {
                    throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_ABSOLUTE);
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
                        throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_OUTSIDE);
                    }
                } catch (NoSuchFileException e) {
                    // 目标路径及其已存在祖先均不存在，回退到字符串检查。
                    if (!target.startsWith(workPath)) {
                        throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_OUTSIDE);
                    }
                }
            }
        }

        enforceFilesystemPolicy(workPath, target, writeMode, sandboxEnabled, fsConfig);
        return target;
    }

    /**
     * 文件系统策略校验：物化缓存属于文件工具自身的保护边界，
     * 不因 OS 沙盒开关关闭而失效；其它 mandatory deny 和 allow/deny 配置只在沙盒模式下生效。
     * relPath 基于符号链接 resolve 后的真实路径计算，确保 symlink 指向受保护文件时也能拦截。
     */
    private void enforceFilesystemPolicy(Path policyRoot, Path target, boolean writeMode, boolean sandboxEnabled, FilesystemConfig fs) {
        if (isMaterializeCachePath(target)) {
            throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_CACHE);
        }
        String rel = toPolicyRelative(policyRoot, target);
        if (isMandatoryDeny(rel)) {
            throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_PROTECTED, "path", rel);
        }
        if (fs == null) {
            return;
        }
        if (matchesAny(rel, fs.getDenyRead()) && !matchesAny(rel, fs.getAllowRead())) {
            throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_READ, "path", rel);
        }
        if (writeMode && fs.getAllowWrite() != null
                && !matchesAny(rel, fs.getAllowWrite())) {
            throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_WRITE_WHITELIST, "path", rel);
        }
    }

    /**
     * 计算 target 相对 policyRoot 的路径（统一用 / 分隔）。
     * 优先解析符号链接后的真实路径；目标不存在时回退到已存在祖先 + 逻辑相对；
     * 仍失败时回退到 normalize 后的逻辑相对。
     */
    private String toPolicyRelative(Path policyRoot, Path target) {
        try {
            Path realRoot = policyRoot.toRealPath();
            Path realTarget;
            try {
                realTarget = target.toRealPath();
            } catch (IOException e) {
                Path ancestor = resolveExistingAncestor(target);
                Path relative = ancestor.relativize(target.normalize());
                realTarget = ancestor.toRealPath().resolve(relative).normalize();
            }
            return realRoot.relativize(realTarget).toString().replace("\\", "/");
        } catch (IOException | IllegalArgumentException e) {
            try {
                return policyRoot.relativize(target.normalize()).toString().replace("\\", "/");
            } catch (IllegalArgumentException e2) {
                return target.normalize().toString().replace("\\", "/");
            }
        }
    }

    /**
     * 前缀匹配：rel 等于某个 pattern 或位于其子路径下（pattern 为 "." 时匹配根自身及任意路径）。
     */
    private boolean matchesAny(String rel, List<String> patterns) {
        if (patterns == null) {
            return false;
        }
        String n = rel.replace("\\", "/");
        if (n.startsWith("./")) {
            n = n.substring(2);
        }
        for (String p : patterns) {
            if (p == null) {
                continue;
            }
            String pp = p.replace("\\", "/");
            if (pp.startsWith("./")) {
                pp = pp.substring(2);
            }
            if (".".equals(pp)) {
                return true;
            }
            if (n.equals(pp) || n.startsWith(pp + "/")) {
                return true;
            }
        }
        return false;
    }



    /**
     * 在物理越界判定基础上叠加 mandatoryDeny / denyRead 策略过滤，
     * 用于 glob/ls/grep 遍历时跳过受保护或拒绝读取的子树。
     */
    boolean isReadDenied(Path policyRoot, Path target, boolean sandboxEnabled, FilesystemConfig fs) {
        if (isSandboxBoundaryDenied(policyRoot, target, sandboxEnabled)) {
            return true;
        }
        if (isMaterializeCachePath(target)) {
            return true;
        }
        if (!sandboxEnabled) {
            return false;
        }
        return isPolicyReadDenied(toPolicyRelative(policyRoot, target), fs);
    }

    /** 按策略相对路径判定：mandatory deny，以及 denyRead 中未被 allowRead 放行的路径。 */
    private boolean isPolicyReadDenied(String rel, FilesystemConfig fs) {
        if (isMandatoryDeny(rel)) {
            return true;
        }
        if (fs == null) {
            return false;
        }
        return matchesAny(rel, fs.getDenyRead()) && !matchesAny(rel, fs.getAllowRead());
    }

    /**
     * 为一次目录遍历创建读拒绝判定器（见 {@link WalkReadGuard}）。
     *
     * @param policyRoot     策略根（同 {@link #isReadDenied} 的 policyRoot）
     * @param start          遍历起点
     * @param sandboxEnabled 是否沙盒模式
     * @param fs             文件系统策略配置（可为 null）
     */
    WalkReadGuard newWalkReadGuard(Path policyRoot, Path start, boolean sandboxEnabled, FilesystemConfig fs) {
        return new WalkReadGuard(policyRoot, start, sandboxEnabled, fs);
    }

    /**
     * 一次目录遍历内的读拒绝判定器：结果与逐条调用 {@link #isReadDenied} 相同，但省掉重复的文件系统访问。
     *
     * <p>{@link #isReadDenied} 每次都要解析策略根、缓存根和目标的真实路径（约 6 次 toRealPath、3 次 exists），
     * 遍历大目录时这是主要开销。这里做两件事：</p>
     * <ul>
     *   <li>策略根、缓存根的真实路径在创建时只算一次；</li>
     *   <li>遍历不跟随链接，普通文件/目录的真实路径就是“所在目录的真实路径 + 自身名字”，不必再访问文件系统；
     *       符号链接、重解析点（{@code isOther()}，如 Windows junction）、起点目录，以及无法确定所在目录真实路径的条目，
     *       仍走 {@link #isReadDenied} 原实现。</li>
     * </ul>
     *
     * <p>用法约定：只用于不跟随链接的 {@link Files#walkFileTree}；条目属性用遍历回调给出的 attrs；
     * 目录在 preVisitDirectory 判定通过、决定进入后调用 {@link #enter}，在 postVisitDirectory 调用 {@link #exit}。
     * 非线程安全，每次遍历新建一个。</p>
     *
     * <p>与原实现的唯一差别是时间点：策略根、缓存根的状态在创建时取一次，遍历期间它们被创建或改成链接不会再感知。
     * 缓存根当时不存在的情况下，额外按“已存在祖先的真实路径 + 余下路径”比较，遍历期间才创建出来的缓存目录同样会被拒绝。</p>
     */
    final class WalkReadGuard {
        private final Path policyRoot;
        private final boolean sandboxEnabled;
        private final FilesystemConfig fs;
        /** 能否用“所在目录真实路径 + 名字”推算真实路径；条件不满足时全部走原实现 */
        private final boolean fastEnabled;
        /** 策略根真实路径（仅沙盒模式使用） */
        private final Path realPolicyRoot;
        /** 规范化后的缓存根；null 表示未启用物化缓存 */
        private final Path cacheRoot;
        /** 缓存根存在时的真实路径 */
        private final Path cacheRealRoot;
        /** 缓存根不存在时，按已存在祖先推算出的真实路径（可能为 null） */
        private final Path cacheProjectedRoot;
        /** 缓存根真实路径解析失败：与原实现一样回退为规范化路径比较 */
        private final boolean cacheLogical;
        private final Deque<Path[]> frames = new ArrayDeque<>();
        /** 用推算真实路径完成的判定次数（供测试确认快速路径确实生效） */
        private int derivedCount;

        private WalkReadGuard(Path policyRoot, Path start, boolean sandboxEnabled, FilesystemConfig fs) {
            this.policyRoot = policyRoot;
            this.sandboxEnabled = sandboxEnabled;
            this.fs = fs;

            // 遍历条目 = 起点 + 若干名字。起点须是规范化的绝对路径，原实现里 target、
            // target.toAbsolutePath().normalize() 两种写法的真实路径才是同一个。
            boolean fast = start != null && start.isAbsolute() && start.equals(start.normalize());

            Path realRoot = null;
            if (fast && sandboxEnabled) {
                try {
                    realRoot = policyRoot.toRealPath();
                } catch (IOException | RuntimeException e) {
                    // 原实现在这里有多级回退口径，交给它处理
                    fast = false;
                }
            }
            this.realPolicyRoot = realRoot;

            Path cr = materializeCacheRoot;
            Path normalizedCache = null;
            Path cacheReal = null;
            Path cacheProjected = null;
            boolean logical = false;
            if (cr != null) {
                normalizedCache = cr.toAbsolutePath().normalize();
                try {
                    if (Files.exists(normalizedCache)) {
                        cacheReal = normalizedCache.toRealPath();
                    } else {
                        cacheProjected = projectRealPath(normalizedCache);
                    }
                } catch (IOException | RuntimeException e) {
                    logical = true;
                }
            }
            this.cacheRoot = normalizedCache;
            this.cacheRealRoot = cacheReal;
            this.cacheProjectedRoot = cacheProjected;
            this.cacheLogical = logical;
            this.fastEnabled = fast;
        }

        /** 不存在的路径：已存在祖先的真实路径 + 余下部分；推算不了返回 null。 */
        private Path projectRealPath(Path normalized) {
            try {
                Path ancestor = resolveExistingAncestorIncludingSelf(normalized);
                return ancestor.toRealPath().resolve(ancestor.relativize(normalized)).normalize();
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        /**
         * 判定遍历条目是否拒绝读取，结果同 {@link #isReadDenied}。
         *
         * @param entry 遍历条目
         * @param attrs 遍历回调给出的条目属性（不跟随链接）
         */
        boolean isDenied(Path entry, BasicFileAttributes attrs) {
            Path real = derivedRealPath(entry, attrs);
            if (real == null) {
                return isReadDenied(policyRoot, entry, sandboxEnabled, fs);
            }
            derivedCount++;
            // 以下判定顺序、口径与 isReadDenied 一致，只是真实路径换成推算值
            if (sandboxEnabled && !real.startsWith(realPolicyRoot)) {
                return true;
            }
            if (isCachePath(entry, real)) {
                return true;
            }
            if (!sandboxEnabled) {
                return false;
            }
            return isPolicyReadDenied(realPolicyRoot.relativize(real).toString().replace("\\", "/"), fs);
        }

        int getDerivedCount() {
            return derivedCount;
        }

        /** 进入目录（preVisitDirectory 判定通过后调用）。 */
        void enter(Path dir, BasicFileAttributes attrs) {
            Path real = null;
            if (fastEnabled) {
                real = derivedRealPath(dir, attrs);
                if (real == null) {
                    try {
                        real = dir.toRealPath();
                    } catch (IOException | RuntimeException e) {
                        // 推算不了：其子项改走原实现
                        real = null;
                    }
                }
            }
            frames.push(new Path[]{dir, real});
        }

        /** 离开目录（postVisitDirectory 时调用）。 */
        void exit(Path dir) {
            Path[] top = frames.peek();
            if (top != null && top[0].equals(dir)) {
                frames.pop();
            }
        }

        /** 普通文件/目录：所在目录真实路径 + 名字；其它情况返回 null（走原实现）。 */
        private Path derivedRealPath(Path entry, BasicFileAttributes attrs) {
            if (!fastEnabled || attrs == null || attrs.isSymbolicLink() || attrs.isOther()) {
                return null;
            }
            Path[] top = frames.peek();
            if (top == null || top[1] == null) {
                return null;
            }
            Path name = entry.getFileName();
            if (name == null || !top[0].equals(entry.getParent())) {
                return null;
            }
            return top[1].resolve(name.toString());
        }

        /** 同 isMaterializeCachePath 的口径，目标真实路径用推算值。 */
        private boolean isCachePath(Path entry, Path real) {
            if (cacheRoot == null) {
                return false;
            }
            if (cacheLogical) {
                // entry 是规范化绝对路径（构造时已保证），即原实现里的 normalizedTarget
                return entry.equals(cacheRoot) || entry.startsWith(cacheRoot);
            }
            Path root = cacheRealRoot != null ? cacheRealRoot : cacheRoot;
            if (real.equals(root) || real.startsWith(root)) {
                return true;
            }
            return cacheProjectedRoot != null && real.startsWith(cacheProjectedRoot);
        }
    }

    /**
     * glob 字面目录前缀：模式开头若干段是普通目录名（如 {@code src/main/**}&#47;{@code *.java} 的 src、main）时，
     * 返回这些段在 target 下对应的目录（逐级，第一个是 target 的子目录）；不能剪枝时返回空列表。
     *
     * <p>只有确定剪枝前后匹配结果一致才剪：</p>
     * <ul>
     *   <li>模式是相对的（不以 / 开头、不含盘符冒号），否则走绝对路径匹配；</li>
     *   <li>段不为空、不是 . 或 ..，不含通配字符 {@code * ? [ ] { } \}；</li>
     *   <li>目录里按该段（同一套 glob 匹配规则，含平台大小写口径）只匹配到一个条目，且名字与段完全相同；</li>
     *   <li>该条目是真实目录（不跟随链接读取，非符号链接、非重解析点）——原遍历不进入链接目录。</li>
     * </ul>
     * 任一条件不满足即停在上一段；读目录出错同样停下（由全量遍历按原口径处理）。
     */
    static List<Path> globLiteralPrefixDirs(Path target, String pattern) {
        if (target == null || pattern == null || pattern.isEmpty()
                || pattern.startsWith("/") || pattern.indexOf(':') >= 0) {
            return Collections.emptyList();
        }
        String[] segments = pattern.split("/", -1);
        List<Path> dirs = new ArrayList<>();
        Path current = target;
        // 最后一段是文件名部分，不参与剪枝
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            if (!isLiteralGlobSegment(segment)) {
                break;
            }
            Path next = findSoleLiteralDir(current, segment);
            if (next == null) {
                break;
            }
            dirs.add(next);
            current = next;
        }
        return dirs;
    }

    private static boolean isLiteralGlobSegment(String segment) {
        if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            if ("*?[]{}\\".indexOf(segment.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static Path findSoleLiteralDir(Path dir, String segment) {
        PathMatcher segmentMatcher;
        try {
            segmentMatcher = dir.getFileSystem().getPathMatcher("glob:" + segment);
        } catch (RuntimeException e) {
            return null;
        }
        Path found = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                Path name = entry.getFileName();
                if (name != null && segmentMatcher.matches(name)) {
                    // 多个条目能匹配（如大小写不敏感匹配下的 a 与 A），或名字写法不同：不剪
                    if (found != null || !segment.equals(name.toString())) {
                        return null;
                    }
                    found = entry;
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (found == null) {
            return null;
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(found, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) {
                return null;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return found;
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





    /**
     * 未显式配置时的宿主配置目录。直接 new TerminalTalent（不经 harness 引擎）的使用方保持原有保护范围；
     * 经 harness 引擎构建时由引擎传入其 harnessHome。
     */
    static final String DEFAULT_HARNESS_HOME = ".soloncode/";

    /** 物化缓存目录相对 harnessHome 的子路径。 */
    static final String MATERIALIZE_CACHE_SUBDIR = "cache/mount";

    /**
     * 规范化宿主配置目录：空白回落到 {@link #DEFAULT_HARNESS_HOME}，统一 / 分隔、去掉前导 ./、补齐结尾 /。
     */
    static String normalizeHarnessHome(String harnessHome) {
        if (harnessHome == null || harnessHome.trim().isEmpty()) {
            return DEFAULT_HARNESS_HOME;
        }
        String hh = harnessHome.trim().replace("\\", "/");
        while (hh.startsWith("./")) {
            hh = hh.substring(2);
        }
        if (hh.isEmpty() || "/".equals(hh) || ".".equals(hh)) {
            return DEFAULT_HARNESS_HOME;
        }
        return hh.endsWith("/") ? hh : hh + "/";
    }

    /**
     * harnessHome 的首段目录名（如 {@code .solon/} → {@code .solon}），用于搜索忽略；无法取得时返回 null。
     */
    static String harnessHomeTopDir(String harnessHome) {
        String hh = normalizeHarnessHome(harnessHome);
        if (hh.startsWith("/")) {
            return null;
        }
        int idx = hh.indexOf('/');
        String top = idx < 0 ? hh : hh.substring(0, idx);
        return top.isEmpty() || ".".equals(top) || "..".equals(top) ? null : top;
    }

    /** 由 harnessHome 推导受保护的相对路径：命令定义、子代理定义、物化缓存。 */
    static List<String> harnessDenyPaths(String harnessHome) {
        String hh = normalizeHarnessHome(harnessHome);
        return Collections.unmodifiableList(Arrays.asList(
                hh + "commands", hh + "agents", hh + MATERIALIZE_CACHE_SUBDIR));
    }

    /**
     * 解析默认用户级缓存根（{@code ~/<harnessHome>cache/mount}）；user.home 不可用时回退到工作区，
     * 确保脚本执行能力不静默失效。
     */
    static Path defaultMaterializeCacheRoot(String workDir, String harnessHome) {
        String cacheDir = normalizeHarnessHome(harnessHome) + MATERIALIZE_CACHE_SUBDIR;
        String userHome = System.getProperty("user.home");
        if (Assert.isNotEmpty(userHome)) {
            return Paths.get(userHome).resolve(cacheDir).toAbsolutePath().normalize();
        }
        if (Assert.isNotEmpty(workDir)) {
            return Paths.get(workDir).resolve(cacheDir).toAbsolutePath().normalize();
        }
        return null;
    }

    /**
     * 判断文件工具目标是否落在当前物化缓存内。使用规范化绝对路径，避免用户级缓存
     * 因工作区不同而无法通过旧的相对 mandatory deny 规则拦截。
     */
    private boolean isMaterializeCachePath(Path target) {
        Path cacheRoot = materializeCacheRoot;
        if (cacheRoot == null || target == null) {
            return false;
        }
        Path normalizedRoot = cacheRoot.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        try {
            if (Files.exists(normalizedRoot)) {
                normalizedRoot = normalizedRoot.toRealPath();
            }
            Path realTarget;
            if (Files.exists(normalizedTarget)) {
                realTarget = normalizedTarget.toRealPath();
            } else {
                Path ancestor = resolveExistingAncestorIncludingSelf(normalizedTarget);
                Path relative = ancestor.relativize(normalizedTarget);
                realTarget = ancestor.toRealPath().resolve(relative).normalize();
            }
            return realTarget.equals(normalizedRoot) || realTarget.startsWith(normalizedRoot);
        } catch (IOException | RuntimeException ignored) {
            return normalizedTarget.equals(normalizedRoot) || normalizedTarget.startsWith(normalizedRoot);
        }
    }

    // mandatory deny：与宿主无关的固定部分（dangerous files/dirs）。
    // 宿主配置目录下的 commands / agents / 物化缓存按 harnessHome 推导（见 harnessDenyPaths），不在此写死。
    // 匹配口径：等于、以其为前缀、或在路径任意层级出现，见 matchesDenyEntry。
    private static final List<String> MANDATORY_DENY_FILES = Collections.unmodifiableList(Arrays.asList(
            ".gitconfig", ".gitmodules", ".bashrc", ".bash_profile", ".bash_logout",
            ".zshrc", ".zprofile", ".profile", ".ripgreprc", ".mcp.json"
    ));

    private static final List<String> MANDATORY_DENY_DIRS = Collections.unmodifiableList(Arrays.asList(
            ".vscode", ".idea", ".git/hooks"
    ));

    /**
     * 按缺省宿主配置目录（{@link #DEFAULT_HARNESS_HOME}）判定。实例内部按自身 harnessHome 判定，
     * 需要指定目录时用 {@link #isMandatoryDenyRelativePath(String, String)}。
     */
    public static boolean isMandatoryDenyRelativePath(String relativePath) {
        return isMandatoryDenyRelativePath(relativePath, DEFAULT_HARNESS_HOME);
    }

    /**
     * 判定相对路径是否受强制保护（固定敏感文件/目录 + {@code <harnessHome>commands|agents|cache/mount}）。
     *
     * @param harnessHome 宿主配置目录；空白时按 {@link #DEFAULT_HARNESS_HOME}
     */
    public static boolean isMandatoryDenyRelativePath(String relativePath, String harnessHome) {
        return isMandatoryDenyRelativePath(relativePath, harnessDenyPaths(harnessHome));
    }

    private boolean isMandatoryDeny(String relativePath) {
        if (isMandatoryDenyRelativePath(relativePath, harnessDenyPaths)) {
            return true;
        }
        List<String> state = protectedStatePaths;
        if (relativePath == null || state.isEmpty()) {
            return false;
        }
        String normalized = relativePath.replace("\\", "/");
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        for (String deny : state) {
            if (matchesDenyEntry(normalized, deny)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMandatoryDenyRelativePath(String relativePath, List<String> harnessDeny) {
        if (relativePath == null) {
            return false;
        }
        String normalized = relativePath.replace("\\", "/");
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        for (String deny : MANDATORY_DENY_FILES) {
            if (matchesDenyEntry(normalized, deny)) {
                return true;
            }
        }
        for (String deny : MANDATORY_DENY_DIRS) {
            if (matchesDenyEntry(normalized, deny)) {
                return true;
            }
        }
        for (String deny : harnessDeny) {
            if (matchesDenyEntry(normalized, deny)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesDenyEntry(String normalized, String deny) {
        return normalized.equals(deny)
                || normalized.startsWith(deny + "/")
                || normalized.endsWith("/" + deny)
                || normalized.contains("/" + deny + "/");
    }


    String formatDisplayPath(Path workPath, String inputPath, Path targetDir, Path file, boolean sandboxEnabled) {
        if (inputPath != null && inputPath.startsWith("@")) {
            String alias = inputPath.split("[/\\\\]")[0];
            // 以挂载根为 relativize 基准，避免 inputPath 带子目录时中间层级丢失
            Mount mount = mountManager.getMount(alias);
            Path base = (mount != null)
                    ? mount.getSource().getLocalRoot().orElse(targetDir) : targetDir;
            return alias + "/" + base.relativize(file).toString().replace("\\", "/");
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

    /**
     * shell 可达的本地根路径：需<b>同时</b>满足来源声明 {@code shellAccessible}（策略层，
     * 由 {@link MountSource#capabilities()} 决定）且存在真实本地根路径（物理层，
     * {@link MountSource#getLocalPath(String) getLocalPath("")}）。
     *
     * <p>二者缺一即返回空：纯虚拟来源（Classpath / 未来的 JDBC）没有本地路径；
     * 有本地路径但未开放 shell 的来源也不应被 bash 触达。调用方据此决定是否
     * 纳入 OS 沙盒白名单、是否支持 {@code cd @alias}——其余文件工具不受此限，任何来源皆可用。</p>
     */
    static Optional<Path> shellLocalRoot(MountSource source) {
        if (source != null && source.capabilities().isShellAccessible()) {
            return source.getLocalRoot();
        }
        return Optional.empty();
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
        for (Mount mount : mountManager.getMounts()) {
            Path localRoot = shellLocalRoot(mount.getSource()).orElse(null);
            if (mount.isEnabled()
                    && localRoot == null
                    && mount.getSource().capabilities().isMaterializable()
                    && result.contains(mount.getAlias())) {
                // 虚拟来源（classpath / 未来 jdbc）的物化执行：命令引用了别名时按需落地到本地缓存，
                // 之后与 shellAccessible 来源走完全相同的 env 注入 + 占位符替换路径。
                // 已注册的可物化挂载一旦物化失败必须直接失败，不能把原始 @alias 命令交给 shell，
                // 否则可能误执行工作区内同名路径，也会把真正的 IO/缓存错误伪装成路径不存在。
                if (materializer == null) {
                    throw new MountMaterializer.MaterializationException(
                            CliI18nKeys.TERMINAL_MATERIALIZE_NO_CACHE, mount.getAlias(), null);
                }
                localRoot = materializer.ensureMaterialized(mount).orElseThrow(() ->
                        new MountMaterializer.MaterializationException(
                                CliI18nKeys.TERMINAL_MATERIALIZE_NO_DIR, mount.getAlias(), null));
            }
            if (mount.isEnabled() && localRoot != null) {
                String alias = mount.getAlias(); // 例如 @pool1
                String envKey = toMountEnvKey(alias); // POOL1

                // 仅注入命令中实际使用的环境变量（减少污染）
                if (result.contains(alias)) {
                    String realPath = localRoot.toString();
                    envs.put(envKey, realPath);
                    String placeholder = getEnvPlaceholder(envKey);

                    // 精确替换：仅替换作为路径前缀出现的 @alias（后跟 / 或 \ 或空白或在行尾）
                    String aliasRegex = Pattern.quote(alias) + "(?=[/\\\\\\s]|$)";

                    // 挂载物理路径含空格/制表符时，直接展开会按空白拆成多个参数（尤其 CMD 的 %VAR% 在解析后
                    // 才展开，无法自行加引号）：给占位符补一层双引号。已处于引号内的（前一字符是引号）跳过，
                    // 否则会往引号内插入字面双引号。
                    // 已知边界：只看前一个字符，别名若处于一段更长的引号串中间（如 cat "dir @pool/f.txt"）
                    // 仍会产生嵌套引号——要完整覆盖需跟踪整行引号状态，当前按主流用法（裸用或紧跟引号）近似处理。
                    if (realPath.indexOf(' ') >= 0 || realPath.indexOf('\t') >= 0) {
                        if (this.shellMode == ShellMode.POWERSHELL) {
                            // PowerShell 不做 bash/cmd 那种「引号段与裸字符相邻即拼接」的处理：
                            // `"$env:POOL"/a.py` 会被解析成两个独立参数（实测 Get-Content 报
                            // 「找不到接受实际参数 /a.py 的位置形式参数」）。必须把占位符连同后续路径片段
                            // 整体包进同一对引号：`"$env:POOL/a.py"`。
                            // 后缀在遇到空白、引号或 shell 元字符（; | & < > ( )）处停止，避免把
                            // `cd @pool/bin; ls` 的分号一起吞进引号里。
                            String tokenRegex = "(?<![\"'])" + aliasRegex + "([/\\\\][^\\s\"';|&<>()]*)?";
                            result = result.replaceAll(tokenRegex,
                                    Matcher.quoteReplacement("\"" + placeholder) + "$1" + "\"");
                        } else {
                            result = result.replaceAll("(?<![\"'])" + aliasRegex,
                                    Matcher.quoteReplacement('"' + placeholder + '"'));
                        }
                    }
                    // 剩余的（已在引号内）按裸占位符替换
                    result = result.replaceAll(aliasRegex, Matcher.quoteReplacement(placeholder));
                }
            }
        }

        // ~ 路径处理（统一所有 shell 模式）
        if (containsUserHomePath(result)) {
            if (sandboxEnabled && !sandboxAllowUserHome) {
                throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_HOME);
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

    /**
     * UTF-8 BOM 解码后的字符（{@code EF BB BF} → {@code U+FEFF}）。
     *
     * <p>Java 的 UTF-8 解码器不会自动剥除 BOM，它会作为首行的第一个字符出现。
     * Windows PowerShell 5.1 的 {@code Set-Content -Encoding UTF8} / {@code >} 写出的文件必带 BOM，
     * 若不剥除：{@code read} 的首行会多一个不可见字符，{@code edit} 对首行的匹配会无声失败，
     * {@code grep} 对首行的 {@code ^} 锚定也会失效。</p>
     */
    static final char UTF8_BOM = '\uFEFF';

    /**
     * 文本是否以 BOM 开头。
     */
    static boolean hasUtf8Bom(String content) {
        return content != null && content.length() > 0 && content.charAt(0) == UTF8_BOM;
    }

    /**
     * 剥除首部 BOM（幂等：无 BOM 时原串返回）。
     */
    static String stripUtf8Bom(String content) {
        return hasUtf8Bom(content) ? content.substring(1) : content;
    }

    // ---- edit 的保字节编解码 ----
    //
    // 文件里有按字符集解不了的字节（GBK 等其他编码的文件、混入的零星坏字节）时，edit 不能按替换模式解码后整体写回：
    // 那样每个坏字节都会变成 U+FFFD 的编码（EF BF BD），用户没要求改的内容被无声改坏且无法恢复。
    // 这里改为：能解码的部分照常解码；解不了的字节逐个映射为孤立低代理 U+DC00+b（同 Python 的 surrogateescape），
    // 编辑只发生在文本上，写回时再把它们还原成原字节，未编辑的部分逐字节不变。
    // 合法解码不会产生孤立低代理（UTF-8 解码器把编码过的代理视为非法；代理对的低半部分前面总有高代理），所以不会混淆。

    /** 原字节映射区的起点：字节 b 映射为 {@code (char) (RAW_BYTE_BASE + b)}。 */
    private static final char RAW_BYTE_BASE = '\uDC00';

    /** 位置 i 是否为原字节映射字符（U+DC00..U+DCFF，且前面不是高代理）。 */
    private static boolean isRawByteAt(CharSequence text, int i) {
        char c = text.charAt(i);
        return c >= RAW_BYTE_BASE && c <= RAW_BYTE_BASE + 0xFF
                && (i == 0 || !Character.isHighSurrogate(text.charAt(i - 1)));
    }

    /**
     * 保字节解码：能按字符集解码的部分照常解码，解不了的字节逐个映射为 U+DC00+b。
     * 内容全部合法时，结果与 {@code new String(bytes, charset)} 相同。
     */
    static String decodeKeepingRawBytes(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        CharBuffer out = CharBuffer.allocate(Math.max(16, Math.min(bytes.length + 16, 8192)));
        StringBuilder sb = new StringBuilder(bytes.length);
        while (true) {
            CoderResult cr = decoder.decode(in, out, true);
            if (cr.isOverflow()) {
                drain(out, sb);
                continue;
            }
            if (cr.isError()) {
                drain(out, sb);
                for (int i = 0; i < cr.length(); i++) {
                    sb.append((char) (RAW_BYTE_BASE + (in.get() & 0xFF)));
                }
                continue;
            }
            break; // 下溢且已到输入末尾：解码完成
        }
        while (decoder.flush(out).isOverflow()) {
            drain(out, sb);
        }
        drain(out, sb);
        return sb.toString();
    }

    private static void drain(CharBuffer out, StringBuilder sb) {
        out.flip();
        sb.append(out);
        out.clear();
    }

    /** 文本中是否含原字节映射字符（即原文件有解不了的字节）。 */
    static boolean hasRawBytes(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            if (isRawByteAt(text, i)) {
                return true;
            }
        }
        return false;
    }

    /** 文本中原字节映射字符的个数（即原文件里解不了的字节数）。 */
    static int countRawBytes(CharSequence text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isRawByteAt(text, i)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 保字节编码：原字节映射字符还原为原字节，其余按字符集严格编码。
     *
     * @throws CharacterCodingException 有字符无法用该字符集表示
     */
    static byte[] encodeKeepingRawBytes(String text, Charset charset) throws CharacterCodingException {
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        // 按字符数组包装：编码器对数组支撑的缓冲走批量快路径（包装 String 时只能逐字符读取，大文件慢数倍）
        char[] chars = text.toCharArray();
        int firstRaw = 0;
        while (firstRaw < chars.length && !isRawByteAt(text, firstRaw)) {
            firstRaw++;
        }
        if (firstRaw == chars.length) {
            // 没有原字节映射字符（绝大多数文件）：整体编码一次
            ByteBuffer bb = encoder.encode(CharBuffer.wrap(chars));
            return Arrays.copyOfRange(bb.array(), bb.arrayOffset() + bb.position(), bb.arrayOffset() + bb.limit());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() + 16);
        int runStart = 0;
        for (int i = firstRaw; i <= text.length(); i++) {
            boolean raw = i < text.length() && isRawByteAt(text, i);
            if (i == text.length() || raw) {
                if (i > runStart) {
                    ByteBuffer bb = encoder.encode(CharBuffer.wrap(chars, runStart, i - runStart));
                    out.write(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining());
                }
                if (raw) {
                    out.write(text.charAt(i) - RAW_BYTE_BASE);
                }
                runStart = i + 1;
            }
        }
        return out.toByteArray();
    }

    /**
     * 含原字节映射字符的文本能否原样写回（编码结果与原字节逐字节相同）。
     * UTF-8 总是可以；个别字符集（如带 BOM 的 UTF-16、有状态的编码）可能不行，这时不能编辑。
     */
    static boolean writesBackUnchanged(String text, byte[] original, Charset charset) {
        try {
            return Arrays.equals(encodeKeepingRawBytes(text, charset), original);
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** 展示用：孤立代理（含原字节映射字符）一律换成 U+FFFD，避免把它们输出给模型。 */
    static String displayRawBytes(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean lone = Character.isHighSurrogate(c)
                    ? i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))
                    : Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(text.charAt(i - 1)));
            if (lone && sb == null) {
                sb = new StringBuilder(text.length());
                sb.append(text, 0, i);
            }
            if (sb != null) {
                sb.append(lone ? '\uFFFD' : c);
            }
        }
        return sb == null ? text : sb.toString();
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

    /**
     * 校验本地挂载路径没有通过符号链接越出挂载根目录。
     *
     * <p>挂载根不存在时保留逻辑路径校验，并使用最近的已存在祖先进行真实路径校验。
     * 这样 ls 可以把不存在的挂载目录作为普通的“不存在”结果处理，同时不放松已存在
     * 挂载目录的符号链接防护。</p>
     */
    private void checkLocalMountPathSafety(Path mountRoot, Path target) throws IOException {
        Path normalizedRoot = mountRoot.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_MOUNT_OUTSIDE);
        }

        Path realMountPath;
        try {
            realMountPath = normalizedRoot.toRealPath();
        } catch (NoSuchFileException e) {
            // 根目录尚不存在：先确认目标的已存在祖先仍位于根目录的已存在祖先内。
            Path existingRoot = resolveExistingAncestorIncludingSelf(normalizedRoot);
            Path existingTarget = resolveExistingAncestorIncludingSelf(normalizedTarget);
            if (!existingTarget.toRealPath().startsWith(existingRoot.toRealPath())) {
                throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_SYMLINK);
            }
            return;
        }

        Path realTarget;
        try {
            realTarget = normalizedTarget.toRealPath();
        } catch (NoSuchFileException e) {
            realTarget = resolveExistingAncestor(normalizedTarget).toRealPath();
        }
        if (!realTarget.startsWith(realMountPath)) {
            throw CliSecurityException.of(CliI18nKeys.TERMINAL_DENIED_SYMLINK);
        }
    }

    private Path resolveExistingAncestorIncludingSelf(Path target) throws IOException {
        Path current = target;
        while (current != null) {
            if (Files.exists(current)) {
                return current;
            }
            current = current.getParent();
        }
        throw new NoSuchFileException(String.valueOf(target));
    }

    private Path resolveExistingAncestor(Path target) throws IOException {
        return resolveExistingAncestorIncludingSelf(target.getParent());
    }

    Path getSandboxPolicyRoot(Path workPath, String inputPath) {
        if (inputPath != null && inputPath.startsWith("@")) {
            String alias = inputPath.split("[/\\\\]")[0];
            Mount mount = mountManager.getMount(alias);
            if (mount != null) {
                Path localRoot = mount.getSource().getLocalRoot().orElse(null);
                if (localRoot != null) {
                    return localRoot;
                }
            }
        }
        return workPath;
    }

    void generateTreeInternal(Path workPath, Path current, int depth, int maxDepth, String indent, StringBuilder sb, boolean showHidden, boolean sandboxEnabled, FilesystemConfig fs) throws IOException {
        generateTreeInternal(workPath, current, depth, maxDepth, indent, sb, showHidden, sandboxEnabled, fs, null);
    }

    void generateTreeInternal(Path workPath, Path current, int depth, int maxDepth, String indent, StringBuilder sb, boolean showHidden, boolean sandboxEnabled, FilesystemConfig fs, Locale locale) throws IOException {
        // 一次树遍历共用一个判定器：策略根/缓存根的真实路径只算一次（口径见 WalkReadGuard）
        WalkReadGuard guard = newWalkReadGuard(workPath, current, sandboxEnabled, fs);
        generateTreeInternal(guard, workPath, current, depth, maxDepth, indent, sb, showHidden, locale);
    }

    private void generateTreeInternal(WalkReadGuard guard, Path workPath, Path current, int depth, int maxDepth, String indent, StringBuilder sb, boolean showHidden, Locale locale) throws IOException {
        if (depth >= maxDepth) return;
        guard.enter(current, readAttributesNoFollow(current));
        try (Stream<Path> stream = Files.list(current)) {
            List<LsEntry> children = new ArrayList<>();
            Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                if (isIgnored(workPath, p)) continue;
                // 每个条目只读一次属性：读拒绝判定与“是否目录”共用（后者与原 Files.isDirectory 同口径）
                BasicFileAttributes attrs = readAttributesNoFollow(p);
                if (guard.isDenied(p, attrs)) continue;
                if (!showHidden && p.getFileName().toString().startsWith(".")) continue;
                children.add(new LsEntry(p, isDirectoryFollow(p, attrs)));
            }
            // 目录在前、再按名字：与原比较器一致
            Collections.sort(children, (a, b) -> {
                if (a.directory != b.directory) return a.directory ? -1 : 1;
                return a.path.getFileName().compareTo(b.path.getFileName());
            });

            for (int i = 0; i < children.size(); i++) {
                LsEntry child = children.get(i);
                boolean isLast = (i == children.size() - 1);
                sb.append(indent).append(isLast ? "└── " : "├── ").append(child.path.getFileName()).append("\n");
                if (child.directory)
                    generateTreeInternal(guard, workPath, child.path, depth + 1, maxDepth, indent + (isLast ? "    " : "│   "), sb, showHidden, locale);
            }
        } catch (AccessDeniedException e) {
            sb.append(indent).append("└── ").append(CliI18n.get(locale, CliI18nKeys.TERMINAL_LS_DENIED)).append("\n");
        } finally {
            guard.exit(current);
        }
    }

    /** ls 的一条子项：路径 + 是否目录（跟随链接，同 Files.isDirectory 口径）。 */
    private static final class LsEntry {
        final Path path;
        final boolean directory;

        LsEntry(Path path, boolean directory) {
            this.path = path;
            this.directory = directory;
        }
    }

    /**
     * 条目的 NOFOLLOW 属性，供 {@link WalkReadGuard} 判定使用；读取失败返回 null（判定器退回原实现）。
     */
    private static BasicFileAttributes readAttributesNoFollow(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 与 {@link Files#isDirectory} 同口径（跟随链接），已有 NOFOLLOW 属性时省掉一次系统调用。
     * 符号链接、重解析点（{@code isOther()}）以及属性读取失败的条目仍走 {@link Files#isDirectory}。
     */
    private static boolean isDirectoryFollow(Path path, BasicFileAttributes noFollowAttrs) {
        if (noFollowAttrs == null || noFollowAttrs.isSymbolicLink() || noFollowAttrs.isOther()) {
            return Files.isDirectory(path);
        }
        return noFollowAttrs.isDirectory();
    }

    String flatListLogic(Path workPath, Path policyRoot, Path target, String inputPath, boolean showHidden, boolean sandboxEnabled, FilesystemConfig fs) throws IOException {
        return flatListLogic(workPath, policyRoot, target, inputPath, showHidden, sandboxEnabled, fs, null);
    }

    String flatListLogic(Path workPath, Path policyRoot, Path target, String inputPath, boolean showHidden, boolean sandboxEnabled, FilesystemConfig fs, Locale locale) throws IOException {
        WalkReadGuard guard = newWalkReadGuard(policyRoot, target, sandboxEnabled, fs);
        guard.enter(target, readAttributesNoFollow(target));
        try (Stream<Path> stream = Files.list(target)) {
            List<String> lines = new ArrayList<>();
            Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                if (isIgnored(workPath, p)) continue;
                // 每个条目只读一次属性：读拒绝判定与“是否目录”共用（后者与原 Files.isDirectory 同口径）
                BasicFileAttributes attrs = readAttributesNoFollow(p);
                if (guard.isDenied(p, attrs)) continue;
                if (!showHidden && p.getFileName().toString().startsWith(".")) continue;
                boolean isDir = isDirectoryFollow(p, attrs);
                String displayPath = formatDisplayPath(workPath, inputPath, target, p, sandboxEnabled);
                lines.add((isDir ? "[DIR] " : "[FILE] ") + displayPath + (isDir ? "/" : ""));
            }
            // 与原 sorted() 一致：按整行字符串排序（[DIR] 前缀天然排在 [FILE] 之前）
            Collections.sort(lines);
            return lines.isEmpty() ? CliI18n.get(locale, CliI18nKeys.TERMINAL_LS_EMPTY) : String.join("\n", lines);
        } finally {
            guard.exit(target);
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
        // 1. 基于已知二进制后缀的快速过滤
        if (isNotTextFileByName(file)) {
            return true;
        }

        // 2. 字节级内容兜底：识别无后缀（或后缀伪装）的二进制文件，
        //    如编译产物、core dump、无扩展名的 ELF 等。读取文件首部采样探测，
        //    避免后缀白名单漏判后将二进制按文本强行解码输出乱码。
        return isLikelyBinaryFile(file);
    }

    /**
     * 按文件名后缀判定是否为已知二进制文件（{@link #isNotTextFile} 的第一步，不访问文件系统）。
     */
    boolean isNotTextFileByName(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();

        return fileName.endsWith(".class") || fileName.endsWith(".jar")  ||
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
                fileName.endsWith(".wasm");
    }

    /** 二进制探测的首部采样字节数。 */
    static final int BINARY_SNIFF_BYTES = 4096;

    /**
     * 读取文件首部采样，按字节判定是否疑似二进制。
     *
     * <p>判定规则与命令输出探测保持一致：NUL 直接判定；统计非空白控制字符占比，
     * 超过阈值判定为二进制。对常见空白、ANSI 转义及 UTF-8 多字节高位字节（&gt;= 0x80）
     * 做豁免，避免误伤中文等多字节文本。探测失败（IO 异常）时保守按文本处理。</p>
     */
    boolean isLikelyBinaryFile(Path file) {
        byte[] buffer = new byte[BINARY_SNIFF_BYTES];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.read(buffer);
        } catch (Throwable e) {
            return false; // 读不到就保守按文本，交由后续流程处理
        }

        return isLikelyBinary(buffer, read);
    }

    /**
     * 按首部采样字节判定是否疑似二进制（{@link #isLikelyBinaryFile} 的判定部分）。
     *
     * @param read 采样的有效字节数（取文件开头的 {@link #BINARY_SNIFF_BYTES} 字节以内）；不大于 0 按文本处理
     */
    static boolean isLikelyBinary(byte[] buffer, int read) {
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

    /** grep 扫描单个文件的大小上限（超过即跳过；与按属性判断的阈值一致）。 */
    static final int GREP_MAX_FILE_BYTES = 10 * 1024 * 1024;

    /**
     * 一次读入文件全部内容，最多 {@code limit} 字节；实际内容超过上限时返回 null（调用方另行处理）。
     *
     * <p>{@code sizeHint} 是遍历时拿到的文件大小，普通文件按它一次分配、不再复制；
     * 符号链接（属性是链接本身的）或读取期间文件变化时按需扩容。</p>
     */
    static byte[] readUpTo(Path file, long sizeHint, int limit) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[(int) Math.max(0L, Math.min(sizeHint, (long) limit))];
            int n = 0;
            while (true) {
                if (n == buf.length) {
                    // 缓冲已满：再读一个字节确认是否到达末尾
                    int b = in.read();
                    if (b < 0) {
                        return buf;
                    }
                    if (n >= limit) {
                        return null;
                    }
                    buf = Arrays.copyOf(buf, (int) Math.min((long) limit, Math.max(8192L, (long) buf.length * 2)));
                    buf[n++] = (byte) b;
                    continue;
                }
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) {
                    return Arrays.copyOf(buf, n);
                }
                n += r;
            }
        }
    }

    /**
     * 第 {@code lines} 行之后（即跳过前 {@code lines} 行）的字节位置；行尾与 {@link java.io.BufferedReader#readLine()} 同口径
     * （{@code \n}、{@code \r}、{@code \r\n}）。不足这么多行时返回末尾。只用于换行符就是单个 0x0A / 0x0D 字节的编码。
     */
    static int lineStartOffset(byte[] bytes, long lines) {
        int i = 0;
        long n = 0;
        while (n < lines && i < bytes.length) {
            byte c = bytes[i++];
            if (c == '\n') {
                n++;
            } else if (c == '\r') {
                if (i < bytes.length && bytes[i] == '\n') {
                    i++;
                }
                n++;
            }
        }
        return n < lines ? bytes.length : i;
    }

    /** 读取文件开头最多 {@code limit} 字节（超大文件的编码抽样）。 */
    static byte[] readHead(Path file, int limit) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[limit];
            int n = 0;
            int r;
            while (n < limit && (r = in.read(buf, n, limit - n)) > 0) {
                n += r;
            }
            return n == limit ? buf : Arrays.copyOf(buf, n);
        }
    }

    /** 正则元字符：模式里不含这些字符时，按正则查找与按字面查找等价。 */
    private static final String REGEX_META_CHARS = "\\^$.|?*+()[]{}";

    /**
     * grep 字面量预筛用的字节序列；返回 null 表示不预筛。
     *
     * <p>只在两种情况下预筛：模式不是合法正则（原实现回退为 {@code contains}），或模式是合法正则但不含任何元字符
     * （按正则查找等价于按字面查找）；并且文件按 UTF-8 解码。此时某一行能命中，说明解码出的文本包含该模式，
     * 文件字节里也就一定包含它的 UTF-8 编码；反过来字节里没有，就一行也命中不了，可以整文件跳过。
     * 这只是必要条件：字节里有，仍按原方式逐行匹配。</p>
     *
     * <p>grep 按替换模式解码（坏字节变成 U+FFFD 后继续），每处坏字节至少产生一个 U+FFFD，
     * 所以不含 U+FFFD 的命中片段只由合法字节序列解码而来，上面的推理成立；模式含 U+FFFD 时它可能来自坏字节，不预筛。</p>
     */
    static byte[] grepLiteralBytes(String pattern, boolean compiledAsRegex, Charset charset) {
        if (pattern == null || pattern.isEmpty() || !StandardCharsets.UTF_8.equals(charset)
                || pattern.indexOf('\uFFFD') >= 0) {
            return null;
        }
        if (compiledAsRegex) {
            for (int i = 0; i < pattern.length(); i++) {
                if (REGEX_META_CHARS.indexOf(pattern.charAt(i)) >= 0) {
                    return null;
                }
            }
        }
        // 含孤立代理字符的模式无法如实编码（会被替换为 '?'），不预筛
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(pattern)) {
            return null;
        }
        return pattern.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 字面量模式在另一种编码下的字节（已由 {@link #grepLiteralBytes} 判定可以预筛）；无法如实编码时返回 null（不预筛）。
     *
     * <p>只用于按严格解码、往返逐字节相同的编码识别出的文件：解码文本含有该模式，字节里就一定有它的编码；
     * 路径规则指定的编码若有解不了的字节，它们显示为 U+FFFD，含 U+FFFD 的模式本就不预筛。</p>
     */
    static byte[] literalBytesIn(String pattern, Charset charset) {
        try {
            ByteBuffer bb = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(pattern));
            byte[] out = new byte[bb.remaining()];
            bb.get(out);
            return out.length == 0 ? null : out;
        } catch (CharacterCodingException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 字节序列查找：{@code data} 中是否包含 {@code needle}。
     */
    static boolean containsBytes(byte[] data, byte[] needle) {
        int n = needle.length;
        if (n == 0) {
            return true;
        }
        byte first = needle[0];
        int max = data.length - n;
        for (int i = 0; i <= max; i++) {
            if (data[i] != first) {
                while (++i <= max && data[i] != first) {
                    // 跳到下一个首字节
                }
            }
            if (i <= max) {
                int j = i + 1;
                int end = i + n;
                for (int k = 1; j < end && data[j] == needle[k]; j++, k++) {
                    // 逐字节比较剩余部分
                }
                if (j == end) {
                    return true;
                }
            }
        }
        return false;
    }
}
