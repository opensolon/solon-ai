package org.noear.solon.ai.skills.code;

import org.noear.solon.ai.chat.skill.AbsSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 进程执行基类：提供通用的代码写入、进程执行、输出捕获及超时控制
 */
public abstract class AbsProcessSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(AbsProcessSkill.class);
    protected final Path rootPath;
    protected final int MAX_OUTPUT_SIZE = 1024 * 1024; // 1MB 限制

    public AbsProcessSkill(String workDir) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        ensureDir();
    }

    protected String runCode(String code, String cmd, String ext, Map<String, String> envs) {
        Path tempScript = null;
        try {
            tempScript = Files.createTempFile(rootPath, "ai_script_", ext);
            Files.write(tempScript, code.getBytes(StandardCharsets.UTF_8));

            ProcessBuilder pb = new ProcessBuilder(cmd, tempScript.toAbsolutePath().toString());
            pb.directory(rootPath.toFile());
            pb.redirectErrorStream(true);

            if (envs != null) {
                pb.environment().putAll(envs);
            }

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < MAX_OUTPUT_SIZE) {
                        output.append(line).append("\n");
                    } else {
                        output.append("... [输出已截断]");
                        process.destroyForcibly();
                        break;
                    }
                }
            }

            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "执行超时：运行时间超过 30 秒。";
            }

            return output.length() == 0 ? "执行成功" : output.toString();
        } catch (Exception e) {
            LOG.error("{} execution failed", name(), e);
            return "异常失败: " + e.getMessage();
        } finally {
            if (tempScript != null) {
                try { Files.deleteIfExists(tempScript); } catch (IOException ignored) {}
            }
        }
    }

    private void ensureDir() {
        try { if (!Files.exists(rootPath)) Files.createDirectories(rootPath); } catch (IOException ignored) {}
    }
}