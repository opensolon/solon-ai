package org.noear.solon.ai.harness.hitl;

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
public class HitlStrategy implements HITLInterceptor.InterventionStrategy {

    // 1. 系统特权与身份篡改 (绝对禁止)
    private static final String SYSTEM_DANGER =
            ".*\\b(sudo|su|chown|chmod|chgrp|passwd|visudo|alias|unalias)\\b.*";

    // 2. 进程与资源控制
    private static final String PROCESS_DANGER =
            ".*\\b(kill|pkill|xargs|nohup|disown|reboot|shutdown|init|systemctl|service)\\b.*";

    // 3. 环境变更工具 (区分查询与变更)
    private static final String ENV_MODIFIERS = "\\b(apt|yum|dnf|npm|pnpm|yarn|pip|docker|kubectl|git|brew|cargo)\\b";
    // 拦截破坏性动词
    private static final String MODIFY_SUB_CMDS = "\\b(install|i|add|remove|rm|publish|push|commit|checkout|update|upgrade|stop|prune|build|config|set)\\b";

    @Override
    public String evaluate(ReActTrace trace, Map<String, Object> args) {
        String cmd = (String) args.get("command");
        if (Assert.isEmpty(cmd)) return null;

        cmd = cmd.trim();

        // --- A. 注入与子 Shell 防御 (最高优先级) ---
        // 拦截反引号、$(...)、重定向到系统设备
        if (cmd.contains("`") || cmd.contains("$(") || cmd.contains("/dev/")) {
            return "检测到潜在的命令注入或设备重定向风险。";
        }

        // --- B. 系统级安全 (绝对黑名单) ---
        if (cmd.matches(SYSTEM_DANGER) || cmd.matches(PROCESS_DANGER)) {
            return "检测到系统特权或进程控制指令 [" + cmd + "]。";
        }

        // --- C. 路径边界检查 (严格限制) ---
        // 1. 拦截路径回溯
        if (cmd.contains("../") || cmd.contains("..\\")) {
            return "检测到路径回溯操作，禁止访问工作区外目录。";
        }
        // 2. 拦截敏感目录访问 (即使是只读)
        if (cmd.matches(".*\\b(/etc/|/var/|/root/|~/.ssh/|~/.bashrc|~/.zshrc).*")) {
            return "禁止访问系统敏感配置文件。";
        }

        // --- D. 包管理与环境变更 (的分级策略) ---
        if (cmd.matches(".*" + ENV_MODIFIERS + ".*")) {
            // 只要包含修改动词，就必须确认
            if (cmd.matches(".*" + MODIFY_SUB_CMDS + ".*")) {
                return "检测到环境变更或包管理修改操作。";
            }
        }

        // --- E. 网络行为 (零信任原则) ---
        // 任何非 --help / --version 的网络工具调用都需要拦截
        if (cmd.matches(".*\\b(curl|wget|ssh|scp|ftp|nc|telnet|dig|nslookup|ping)\\b.*")) {
            if (!cmd.matches(".*(--help|--version|-V).*")) {
                return "检测到网络外连或远程探测指令。";
            }
        }

        // --- F. 管道与命令组合 (只读安全链) ---
        // 允许单个管道流向安全工具，拦截多重管道或非法组合
        if (cmd.contains(";") || cmd.contains("&")) {
            return "禁止执行多条组合命令以确保审计追踪。";
        }

        if (cmd.contains("|") && !cmd.contains("||")) {
            // 严格对齐只读工具白名单
            if (!cmd.matches(".*\\|\\s*\\b(grep|head|tail|awk|sort|uniq|wc|jq|column|less|sed|xxd)\\b.*")) {
                return "检测到潜在风险的管道链操作。";
            }
        }

        // --- G. 破坏性文件操作 ---
        // rm -r / rm -rf / rm -Rf 等递归删除
        if (cmd.matches(".*\\brm\\s+-(\\w*[rR]\\w*)\\s+.*")) {
            return "检测到递归删除操作 [" + cmd + "]。";
        }
        // Windows: rd /s, rmdir /s
        if (cmd.matches("(?i).*\\b(rd|rmdir)\\s+/s\\b.*")) {
            return "检测到递归删除操作 [" + cmd + "]。";
        }
        // mv 移动操作（可能导致数据丢失）
        if (cmd.matches(".*\\bmv\\s+.*")) {
            return "检测到文件移动操作 [" + cmd + "]。";
        }

        return null; // 基础查询命令 (ls, cat, pwd, echo, find) 全数放行
    }
}