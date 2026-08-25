/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LSP 诊断渲染器
 *
 * <p>诊断一旦自动注入到工具输出，就直接占用模型上下文，因此渲染层的职责是「降噪」：
 * <ul>
 *   <li>只保留 ERROR：WARN/INFO/HINT 对当前修改多半无关，且量级极大</li>
 *   <li>同一句错误只出一行，重复出现的位置折叠进剩余计数：见 {@link #selectForOutput}</li>
 *   <li>每文件封顶 {@link #MAX_PER_FILE} 条，超出只提示剩余条数</li>
 *   <li>不带 file:// 前缀，行列按 1-based 输出，与 read/edit 的行号语义一致</li>
 * </ul>
 *
 * @author noear
 * @since 4.1
 */
public final class LspDiagnosticReporter {
    /**
     * 单文件最多输出的诊断条数
     */
    public static final int MAX_PER_FILE = 20;

    /**
     * 自动注入时的祈使句：明确限定"这个文件"，避免模型跑去修无关报错
     */
    public static final String PROMPT_PREFIX = "LSP errors detected in this file, please fix:";

    private LspDiagnosticReporter() {
    }

    /**
     * 过滤出 ERROR 级诊断
     */
    public static List<Diagnostic> filterErrors(List<Diagnostic> items) {
        List<Diagnostic> errors = new ArrayList<>();
        if (items == null) {
            return errors;
        }
        for (Diagnostic d : items) {
            if (d != null && DiagnosticSeverity.Error.equals(d.getSeverity())) {
                errors.add(d);
            }
        }
        return errors;
    }

    /**
     * 渲染单条诊断，格式：{@code ERROR [12:5] message}
     */
    public static String renderItem(Diagnostic d) {
        int line = 1;
        int character = 1;
        if (d.getRange() != null && d.getRange().getStart() != null) {
            Position start = d.getRange().getStart();
            line = start.getLine() + 1;
            character = start.getCharacter() + 1;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("ERROR [").append(line).append(':').append(character).append("] ")
                .append(d.getMessage() == null ? "" : d.getMessage().trim());
        if (d.getSource() != null && !d.getSource().isEmpty()) {
            sb.append(" (").append(d.getSource()).append(')');
        }
        return sb.toString();
    }

    /**
     * 渲染 {@code <diagnostics file="...">} 块；无 ERROR 时返回 null
     *
     * @param displayPath 展示用路径（相对工作区）
     * @param items       原始诊断列表（含各严重度）
     */
    public static String renderBlock(String displayPath, List<Diagnostic> items) {
        List<Diagnostic> errors = filterErrors(items);
        if (errors.isEmpty()) {
            return null;
        }

        List<Diagnostic> shown = selectForOutput(errors, MAX_PER_FILE);

        StringBuilder sb = new StringBuilder();
        sb.append("<diagnostics file=\"").append(displayPath).append("\">\n");

        for (int i = 0; i < shown.size(); i++) {
            sb.append(renderItem(shown.get(i))).append('\n');
        }
        if (errors.size() > shown.size()) {
            sb.append("... and ").append(errors.size() - shown.size()).append(" more\n");
        }

        sb.append("</diagnostics>");
        return sb.toString();
    }

    /**
     * 在 {@code limit} 条预算内挑选最有信息量的诊断：先去重，再截断
     *
     * <p>同一处笔误常在一个文件里复现几十次（如某个字段类型声明错了，所有赋值点都会报同一
     * 句话）。逐条列出这些完全重复的消息没有信息增量，却会挤占预算把其它类型的错误压进
     * 「... and N more」里彻底看不见，同时白白消耗模型上下文。对修错来说「有几类问题」比
     * 「某一类的全部位置」重要：类型决定修法，改完后剩余位置会在下一轮编辑时重新报出来。
     *
     * <p>因此按 (message, source) 去重，每类只保留首次出现的那条（行号最小者），天然按行号
     * 递增排列；被折叠掉的重复项计入 {@code renderBlock} 尾部的剩余计数，故错误总数不失真。
     * 单行格式不变，解析方无需适配。
     *
     * @param errors 已过滤出的 ERROR 级诊断
     * @param limit  最多输出条数
     */
    public static List<Diagnostic> selectForOutput(List<Diagnostic> errors, int limit) {
        List<Diagnostic> out = new ArrayList<>();
        if (errors == null) {
            return out;
        }

        Set<String> seen = new HashSet<>();
        for (Diagnostic d : errors) {
            if (out.size() >= limit) {
                break;
            }
            //add 返回 false 即已出现过同样的消息，直接丢弃
            if (seen.add(groupKey(d))) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * 错误类型标识：同一句 message + 同一来源视为同类
     */
    private static String groupKey(Diagnostic d) {
        String message = d.getMessage() == null ? "" : d.getMessage().trim();
        String source = d.getSource() == null ? "" : d.getSource();
        return source + '\u0000' + message;
    }

    /**
     * 渲染带祈使句的完整注入片段；无 ERROR 时返回 null
     */
    public static String renderForToolOutput(String displayPath, List<Diagnostic> items) {
        String block = renderBlock(displayPath, items);
        if (block == null) {
            return null;
        }
        return PROMPT_PREFIX + "\n" + block;
    }
}
