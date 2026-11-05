package org.noear.solon.ai.harness.extension.goal.validate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 构建通过验证器 — 自动检测项目类型并运行编译检查。
 *
 * <p>支持的项目类型：
 * <ul>
 *   <li>Maven (pom.xml → {@code mvn compile -q -o})</li>
 *   <li>Gradle (build.gradle / build.gradle.kts → {@code gradle compileJava -q})</li>
 * </ul>
 *
 * <p>无构建工具或构建工具不可用时不阻塞完成（返回 passed）。</p>
 *
 * <p>匹配含 build/compile/implement/refactor/fix/create/add/write 等关键字的目标。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public class BuildPassValidator implements GoalValidator {
    private static final Logger log = LoggerFactory.getLogger(BuildPassValidator.class);

    private final String workspace;

    public BuildPassValidator(String workspace) {
        this.workspace = workspace;
    }

    @Override
    public ValidationResult validate(String condition) {
        if (workspace == null || workspace.isEmpty()) {
            return ValidationResult.passed(); // 无工作区信息时不阻塞
        }

        Path workspacePath = Paths.get(workspace);

        try {
            // 检测 Maven 项目
            if (Files.exists(workspacePath.resolve("pom.xml"))) {
                return runCommand("mvn compile -q -o", workspacePath);
            }
            // 检测 Gradle 项目
            if (Files.exists(workspacePath.resolve("build.gradle")) ||
                    Files.exists(workspacePath.resolve("build.gradle.kts"))) {
                return runCommand("gradle compileJava -q", workspacePath);
            }

            // 无已知构建工具时跳过，不阻塞完成
            log.info("BuildPassValidator: no known build tool detected, skipping");
            return ValidationResult.passed();
        } catch (Exception e) {
            log.warn("BuildPassValidator 执行异常: {}", e.getMessage());
            return ValidationResult.passed(); // 异常时不阻塞完成
        }
    }

    private ValidationResult runCommand(String command, Path workDir) {
        try {
            log.info("BuildPassValidator 运行: {} (工作目录: {})", command, workDir);

            ProcessBuilder pb = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb.command("cmd.exe", "/c", command);
            } else {
                pb.command("sh", "-c", command);
            }
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            // Java 8 兼容：手动读取 InputStream
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = process.getInputStream().read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("BuildPassValidator: 编译通过 (exit code=0)");
                return ValidationResult.passed();
            } else {
                String tail = output.length() > 500
                        ? output.substring(output.length() - 500)
                        : output;
                log.warn("BuildPassValidator: 编译失败 (exit code={})", exitCode);
                return ValidationResult.failed("编译失败 (exit=" + exitCode + "): " + tail.trim());
            }
        } catch (Exception e) {
            log.warn("BuildPassValidator 命令执行异常: {}", e.getMessage());
            return ValidationResult.passed(); // 异常时不阻塞完成
        }
    }
}
