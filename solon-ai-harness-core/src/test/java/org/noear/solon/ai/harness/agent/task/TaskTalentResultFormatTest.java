/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.agent.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子任务结果外壳契约测试
 *
 * <p>三项都属于「错了不报错」：子代理输出不可控，一旦破坏 {@code <task_result>} 结构，
 * 主代理读到的内容会静默截断或错位；不设上限则会直接灌爆父会话上下文。</p>
 *
 * @author bai
 * @since 4.2
 */
public class TaskTalentResultFormatTest {

    /** formatTaskResp 不触达 engine，null 引擎即可 */
    private static String format(boolean successful, String result, boolean isMultitask) {
        TaskTalent talent = new TaskTalent(null);

        TaskTalent.MultiTaskOp op = new TaskTalent.MultiTaskOp();
        op.agent_name = "general";
        op.description = "d";
        op.index = 2;

        return talent.formatTaskResp(op, successful, result, isMultitask);
    }

    private static int count(String text, String token) {
        int count = 0;
        int from = 0;

        while (true) {
            int at = text.indexOf(token, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + token.length();
        }
    }

    /**
     * 极简 CDATA 还原：按 {@code <![CDATA[ ... ]]>} 段拼接，模拟严格 XML 解析器。
     *
     * <p>比「数终止符个数」可靠：转义产物 {@code ]]]]><![CDATA[>} 本身就含一个
     * {@code ]]>}，个数不是判据；能完整还原出原文才是。</p>
     */
    private static String parseCdata(String out) {
        int from = out.indexOf("<result_content><![CDATA[") + "<result_content><![CDATA[".length();
        StringBuilder sb = new StringBuilder();

        while (true) {
            int end = out.indexOf("]]>", from);
            sb.append(out, from, end);
            from = end + 3;

            if (out.startsWith("<![CDATA[", from)) {
                from += "<![CDATA[".length();
                continue;
            }

            return sb.toString();
        }
    }

    /**
     * 核心回归：子代理输出含 {@code ]]>}（XML 示例、diff、代码片段都可能）时
     * 不得提前闭合 CDATA，否则外壳的后续标签会被当成内容。
     */
    @Test
    public void cdataTerminatorInResultDoesNotCloseEnvelope() {
        String raw = "示例: <a><![CDATA[x]]></a>";
        String out = format(true, raw, true);

        assertTrue(out.endsWith("]]></result_content></task_result>"),
                "外壳必须以一个正常终止符收尾: " + out);
        assertEquals(raw, parseCdata(out),
                "按严格 XML 解析后必须完整还原出原文（证明 ]]> 未被截断）: " + out);
        assertEquals(1, count(out.replace("]]]]><![CDATA[>", ""), "]]>"),
                "内容里的 ]]> 应已全部拆开，只剩结尾那一个: " + out);
    }

    /** description / agent_name 也来自模型输出，含 < & 会破坏外层标签结构 */
    @Test
    public void structuredFieldsAreEscaped() {
        TaskTalent talent = new TaskTalent(null);

        TaskTalent.MultiTaskOp op = new TaskTalent.MultiTaskOp();
        op.agent_name = "a<b";
        op.description = "x & y";
        op.index = 1;

        String out = talent.formatTaskResp(op, true, "ok", true);

        assertTrue(out.contains("<agent_name>a&lt;b</agent_name>"), "agent_name 必须转义: " + out);
        assertTrue(out.contains("<description>x &amp; y</description>"), "description 必须转义: " + out);
    }

    /** 不设上限时单个超长子代理输出就能把父会话上下文灌爆并触发压缩 */
    @Test
    public void longResultIsTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30000; i++) {
            sb.append('x');
        }

        String out = format(true, sb.toString(), false);

        assertTrue(out.contains("内容过长已截断"), "超长结果应给出截断说明: " + out.length());
        assertTrue(out.length() < 30000, "输出必须短于原始长度: " + out.length());
        assertTrue(out.contains("原始长度 30000 字符"), "应报告原始长度便于追查");
    }

    /** 未截断且无危险序列时，内容必须原样保留（不要顺手做 HTML 转义） */
    @Test
    public void normalResultIsKeptVerbatim() {
        String out = format(false, "a < b && c > d", true);

        assertTrue(out.contains("<![CDATA[a < b && c > d]]></result_content>"),
                "CDATA 内的 < & 无需转义，不能改写内容: " + out);
        assertTrue(out.contains("<result_status>failure</result_status>"), "失败状态应写入 failure");
    }

    // ---------- 超长结果落盘 ----------

    private static TaskTalent.MultiTaskOp op() {
        TaskTalent.MultiTaskOp op = new TaskTalent.MultiTaskOp();
        op.agent_name = "general";
        op.description = "d";
        op.index = 1;
        return op;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /** 落盘成功：头部保留 + 路径指引；磁盘内容与原文完全一致（供宿主文件工具分页读回） */
    @Test
    public void oversizedResultSpillsToFilesDir(@TempDir Path workspace) throws Exception {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), "home")
                .sessionProvider(InMemoryAgentSession::of)
                .build();
        TaskTalent talent = engine.getTaskTalent();

        String raw = repeat('x', 30000);
        String taskId = "spill-test-1";

        String out = talent.formatTaskResp(op(), taskId, true, raw, false);

        assertTrue(out.contains("内容过长已截断"), "应保留截断说明: " + out.length());
        assertTrue(out.contains("完整结果已落盘到 .files/task/" + taskId + "/result.txt"),
                "应给出 workspace 相对落盘路径（供宿主文件工具直接读）: " + out);
        assertTrue(out.contains("不要重新委派该任务"), "应告诫不要为看完整结果重新委派: " + out);
        assertTrue(out.length() < 30000, "外壳仍必须短于原始长度: " + out.length());

        // 路径必须真实可读：宿主 read 工具按 offset/limit 分页
        Path file = workspace.resolve(".files").resolve("task").resolve(taskId).resolve("result.txt");
        assertTrue(Files.isRegularFile(file), "落盘文件应存在: " + file);
        assertEquals(30000,
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8).length(),
                "磁盘内容必须完整保留原文");
    }

    /** 短结果不落盘：不能为普通任务制造文件系统副作用 */
    @Test
    public void shortResultDoesNotSpill(@TempDir Path workspace) {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), "home")
                .sessionProvider(InMemoryAgentSession::of)
                .build();
        TaskTalent talent = engine.getTaskTalent();

        talent.formatTaskResp(op(), "spill-test-2", true, "ok", false);

        assertFalse(Files.exists(workspace.resolve(".files")),
                "短结果不应创建 .files 目录");
    }

    /** taskId 净化：含路径分隔符/上跳的 id 不得逃出 .files/task 目录（路径穿越防护） */
    @Test
    public void taskIdIsSanitizedAgainstPathTraversal(@TempDir Path workspace) {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), "home")
                .sessionProvider(InMemoryAgentSession::of)
                .build();
        TaskTalent talent = engine.getTaskTalent();

        String evil = "../../escape";
        talent.formatTaskResp(op(), evil, true, repeat('y', 30000), false);

        // 落盘位置在 .files/task 内（分隔符被替换为 _），workspace 之外不得出现文件
        Path sanitized = workspace.resolve(".files").resolve("task").resolve(".._.._escape");
        assertTrue(Files.exists(sanitized), "净化后的落盘位置: " + sanitized);
        assertFalse(Files.exists(workspace.resolve("escape")), "不得逃出 .files/task 目录");
        assertFalse(Files.exists(workspace.getParent().resolve("escape")), "不得上跳");
    }
}
