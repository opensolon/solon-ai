package org.noear.solon.ai.codecli.impl;

import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.core.util.Assert;

import java.util.Map;

/**
 * Solon Code CLI 交互干预策略
 * <p>专注于对 bash 等高危指令进行安全审计</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class CodeHITLStrategy implements HITLInterceptor.InterventionStrategy {

    // 高危关键字：涵盖删除、移动、网络下载、系统权限、环境嗅探等
    private static final String DANGER_PATTERN =
            ".*\\b(rm|mv|curl|wget|kill|chmod|chown|apt|yum|dnf|npm|pip|pnpm|sudo|su|lsof|netstat|nmap|env|printenv|export|ssh|scp|ftp|docker|kubectl)\\b.*";

    @Override
    public String evaluate(ReActTrace trace, Map<String, Object> args) {
        String cmd = (String) args.get("command");

        if (Assert.isEmpty(cmd)) {
            return null;
        }

        // 1. 路径越界探测 (防止 Agent 尝试跳出 Box)
        if (cmd.contains("../") || cmd.contains("..\\")) {
            return "检测到路径回溯操作，禁止访问工作区以外的目录。";
        }

        // 2. 拦截敏感关键字
        if (cmd.matches(DANGER_PATTERN)) {
            return "检测到敏感系统指令 [" + cmd + "]，执行可能影响系统环境或导致信息泄露。";
        }

        // 3. 拦截命令拼接与不安全的管道
        // 允许简单的查询管道，如 | grep, | head
        if (cmd.contains(";") || cmd.contains("&") || cmd.contains("|")) {
            if (!cmd.matches(".*\\|\\s*(grep|head|tail|awk|sort|uniq|wc).*")) {
                return "检测到复杂的命令组合、多行指令或潜在危险的管道操作。";
            }
        }

        // 4. 严格控制重定向写入 (防止绕过文件编辑器的权限检查)
        if (cmd.contains(">") || cmd.contains(">>")) {
            return "检测到 shell 重定向写入操作，建议使用 write_to_file 工具以获得更安全的路径校验。";
        }

        // 5. 拦截特殊的反弹 shell 特征
        if (cmd.contains("/dev/tcp") || cmd.contains("/dev/udp")) {
            return "检测到疑似网络重定向/反弹 Shell 操作。";
        }

        return null; // 安全指令，直接放行
    }
}