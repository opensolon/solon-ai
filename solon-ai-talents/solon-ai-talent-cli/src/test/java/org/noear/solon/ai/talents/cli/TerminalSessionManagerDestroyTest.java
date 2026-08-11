package org.noear.solon.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 进程树销毁验证：terminate / 硬超时后，子进程不得孤儿化残留。
 * 背景：Windows 上 taskkill /T 依赖"活着的树根 PID"向下清理，
 * 若先销毁根进程再 taskkill，子进程将成孤儿继续运行（曾导致 node/vite 端口残留）。
 */
public class TerminalSessionManagerDestroyTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase().contains("win");

    @Test
    public void terminate_killsWholeProcessTree_noOrphanChildren() throws Exception {
        // 启动一个会衍生子进程的长命令（Windows: cmd→ping；Unix: sh→sleep）
        String command = WINDOWS ? "ping -n 300 127.0.0.1" : "sleep 300";
        TerminalSessionManager manager = new TerminalSessionManager();

        // 基线：系统存量子进程（排除历史残留，只跟踪本次启动的）
        java.util.Set<Long> baseline = new java.util.HashSet<>(childPids());
        TerminalSessionManager.CommandSnapshot start =
                manager.exec(command, Paths.get(".").toAbsolutePath(), null, 500, 4096, 60_000);

        assertTrue(start.running(), "命令应仍在运行");
        List<Long> ours =
                childPids().stream()
                        .filter(pid -> baseline.contains(pid) == false)
                        .collect(java.util.stream.Collectors.toList());
        assertFalse(ours.isEmpty(), "本次启动的命令应衍生子进程");

        TerminalSessionManager.CommandSnapshot stop =
                manager.terminate(start.sessionId(), "测试终止", 4096);
        assertTrue(stop.terminated(), "应标记为已终止");
        assertFalse(stop.running(), "终止后主进程应已退出");

        // 等待进程树完全退出后，本次启动的子进程不得残留
        TimeUnit.MILLISECONDS.sleep(500);
        java.util.Set<Long> after = childPids().stream().collect(java.util.stream.Collectors.toSet());
        for (Long pid : ours) {
            assertFalse(after.contains(pid), "子进程 " + pid + " 应被清理，不得孤儿化残留");
        }
    }

    /** 收集长命令衍生的子进程 PID：Windows 查 ping.exe；Unix 查 sleep 300。 */
    private static List<Long> childPids() throws Exception {
        List<Long> pids = new ArrayList<>();
        if (WINDOWS) {
            Process tasklist =
                    new ProcessBuilder(
                                    "tasklist", "/FI", "IMAGENAME eq ping.exe", "/FO", "CSV", "/NH")
                            .start();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(tasklist.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // CSV: "ping.exe","1234","Console","1","12,345 K"
                    String csv = line.replace("\"", "");
                    String[] parts = csv.split(",");
                    if (parts.length >= 2 && parts[0].equalsIgnoreCase("ping.exe")) {
                        pids.add(Long.valueOf(Long.parseLong(parts[1].trim())));
                    }
                }
            }
        } else {
            Process pgrep = new ProcessBuilder("pgrep", "-f", "sleep 300").start();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(pgrep.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    pids.add(Long.valueOf(Long.parseLong(line.trim())));
                }
            }
        }
        return pids;
    }
}
