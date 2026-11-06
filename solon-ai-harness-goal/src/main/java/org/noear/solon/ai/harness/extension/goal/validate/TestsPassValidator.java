package org.noear.solon.ai.harness.extension.goal.validate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 测试通过验证器 — 自动检测项目类型并运行测试套件。
 *
 * <p>支持的项目类型：
 * <ul>
 *   <li>Maven (pom.xml → {@code mvn test})</li>
 *   <li>Gradle (build.gradle / build.gradle.kts → {@code gradle test})</li>
 *   <li>Node (package.json → {@code npm test})</li>
 *   <li>Python (setup.py / pyproject.toml → {@code python -m pytest})</li>
 * </ul>
 *
 * <p>仅在目标条件包含 test/pass/check/ensure/verify 等关键字时自动触发。
 * 在调用方传入的执行目录（本轮会话工作目录）下检测与运行；目录为空时按未通过处理。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public class TestsPassValidator implements GoalValidator {
    private static final Logger log = LoggerFactory.getLogger(TestsPassValidator.class);

    @Override
    public ValidationResult validate(String condition, String workDir) {
        if (workDir == null || workDir.isEmpty()) {
            return ValidationResult.failed("工作目录为空，无法运行测试");
        }

        Path workspacePath = Paths.get(workDir);

        try {
            // 检测 Maven 项目
            if (Files.exists(workspacePath.resolve("pom.xml"))) {
                return runCommand("mvn test", workspacePath);
            }
            // 检测 Gradle 项目
            if (Files.exists(workspacePath.resolve("build.gradle")) ||
                    Files.exists(workspacePath.resolve("build.gradle.kts"))) {
                return runCommand("gradle test", workspacePath);
            }
            // 检测 Node 项目
            if (Files.exists(workspacePath.resolve("package.json"))) {
                return runCommand("npm test", workspacePath);
            }
            // 检测 Python 项目
            if (Files.exists(workspacePath.resolve("setup.py")) ||
                    Files.exists(workspacePath.resolve("pyproject.toml"))) {
                return runCommand("python3 -m pytest", workspacePath);
            }

            return ValidationResult.failed("未检测到已知的项目类型 (pom.xml / package.json / build.gradle / setup.py)");
        } catch (Exception e) {
            log.warn("TestsPassValidator 运行测试失败: {}", e.getMessage());
            return ValidationResult.failed("测试执行异常: " + e.getMessage());
        }
    }

    private ValidationResult runCommand(String command, Path workDir) {
        try {
            log.info("TestsPassValidator 运行: {} (工作目录: {})", command, workDir);

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
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = process.getInputStream().read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("TestsPassValidator: 测试通过 (exit code=0)");
                return ValidationResult.passed();
            } else {
                String tail = output.length() > 500
                        ? output.substring(output.length() - 500)
                        : output;
                log.warn("TestsPassValidator: 测试失败 (exit code={})", exitCode);
                return ValidationResult.failed("测试未通过 (exit=" + exitCode + "): " + tail.trim());
            }
        } catch (Exception e) {
            log.warn("TestsPassValidator 命令执行异常: {}", e.getMessage());
            return ValidationResult.failed("执行 '" + command + "' 失败: " + e.getMessage());
        }
    }
}
