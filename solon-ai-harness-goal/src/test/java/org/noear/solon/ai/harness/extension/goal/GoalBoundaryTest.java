/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * goal 域架构护栏（随 goal 下沉 harness 一同迁移）。
 *
 * <p>锁定以下不可回退的决策：
 * <ol>
 *   <li>goal 不得反向依赖宿主（CLI）的任何类型 —— 下沉后这道约束比原先更强，
 *       因为 harness 是库，被多端复用。</li>
 *   <li>{@code GoalEngine} 只依赖 {@link org.noear.solon.ai.agent.AgentSession} 抽象，
 *       不绑定文件/内存等具体存储实现（存储介质是会话实现的细节）。</li>
 *   <li>预算阈值必须是实例级，不得存在全局静态配置（历史上多工作区互相覆盖）。</li>
 *   <li>验证器注册表必须是实例级，不得持有共享静态表（历史上跨工作区污染）。</li>
 *   <li>goal 不得引用 loop 的任何调度类型（执行桥由组合根注入）。</li>
 * </ol>
 *
 * <p>采用纯文本扫描，不引入 ArchUnit 等额外依赖。</p>
 */
class GoalBoundaryTest {

    private static final Path GOAL_DIR = Paths.get("src/main/java/org/noear/solon/ai/harness/session/goal");
    private static final Path COMMAND = Paths.get("src/main/java/org/noear/solon/ai/harness/session/goal/GoalCommand.java");

    @Test
    void goalPackageMustNotImportHostPackages() throws IOException {
        List<Path> files = javaFiles(GOAL_DIR);
        assertFalse(files.isEmpty(), "goal 包源码必须存在（否则本护栏形同虚设）");

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (content.contains("import org.noear.solon.codecli.")) {
                violations.add(file.getFileName().toString() + " 引用了宿主包 codecli");
            }
        }
        assertTrue(violations.isEmpty(),
                "goal 是 harness 的通用能力，不得反向依赖宿主：" + violations);
    }

    @Test
    void goalEngineMustNotCoupleToConcreteSessionStorage() throws IOException {
        Path engine = GOAL_DIR.resolve("GoalEngine.java");
        assertTrue(Files.exists(engine), "GoalEngine 源码必须存在");

        String content = new String(Files.readAllBytes(engine), StandardCharsets.UTF_8);
        assertTrue(!content.contains("FileAgentSession"),
                "GoalEngine 不得依赖 FileAgentSession（应只依赖 AgentSession 接口）");
        assertTrue(!content.contains("InMemoryAgentSession"),
                "GoalEngine 不得依赖 InMemoryAgentSession（应只依赖 AgentSession 接口）");
        assertTrue(!content.contains("Files.write"),
                "GoalEngine 不得自行写盘（落盘由会话实现负责）");
    }

    @Test
    void goalStateMustNotUseGlobalStaticConfiguration() throws IOException {
        Path gs = GOAL_DIR.resolve("GoalState.java");
        assertTrue(Files.exists(gs), "GoalState 源码必须存在");

        String content = new String(Files.readAllBytes(gs), StandardCharsets.UTF_8);
        assertTrue(!content.contains("static volatile int budgetWarningPercent"),
                "预算阈值必须是实例级，不得为全局静态（多工作区会互相覆盖）");
        assertTrue(!content.contains("public static void configure"),
                "不得再提供全局静态 configure 入口");
    }

    @Test
    void goalPackageMustNotHoldSharedStaticState() throws IOException {
        List<Path> files = javaFiles(GOAL_DIR);
        assertFalse(files.isEmpty(), "goal 包源码必须存在");

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    continue;
                }
                if (trimmed.contains("static") && trimmed.contains("List<") && !trimmed.contains("final long")) {
                    violations.add(file.getFileName() + ": " + trimmed);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "goal 不得持有全局静态注册表（每工作区须实例化）：" + violations);
    }

    @Test
    void goalPackageMustNotReferenceLoopScheduler() throws IOException {
        List<Path> files = javaFiles(GOAL_DIR);
        assertFalse(files.isEmpty(), "goal 包源码必须存在");

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (content.contains("LoopScheduler")) {
                violations.add(file.getFileName().toString() + " 引用了 LoopScheduler");
            }
        }
        assertTrue(violations.isEmpty(),
                "goal 不得持有 loop 的调度器（执行桥应来自组合根注入）：" + violations);
    }

    @Test
    void goalCommandMustNotDelegateToLoopCommand() throws IOException {
        assertTrue(Files.exists(COMMAND), "GoalCommand 源码必须存在");

        String content = new String(Files.readAllBytes(COMMAND), StandardCharsets.UTF_8);
        assertTrue(!content.contains("LoopCommand"),
                "/goal 必须是独立命令，不得委托 LoopCommand");
        assertTrue(!content.contains("codecli.loop."),
                "/goal 不得依赖 loop 包");
    }

    private static List<Path> javaFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(result::add);
        }
        return result;
    }
}
