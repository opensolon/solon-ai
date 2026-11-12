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
package org.noear.solon.ai.harness.input;

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
 * input 包架构护栏。
 *
 * <p>锁定四条不可回退的决策：
 * <ol>
 *   <li>input 包不得反向依赖宿主（codecli）的任何类型 —— harness 是被多端复用的库。</li>
 *   <li>input 包不得反向依赖 {@code HarnessEngine} —— 能力靠注入而非反向依赖。
 *       （曾经禁止过 {@code Flux}：担心统一消费口会退化成满屏回调。后来改为允许
 *       {@link InputOutcome#stream()} 作为「可选出口」：三端不调用它也不受影响，
 *       而第三方写 Coding Agent 时少一层翻译。但流里不得含引擎能力。）</li>
 *   <li>input 包不替宿主做调度与轮次决策 —— 不得出现 {@code Schedulers}，
 *       {@code stream()} 不得对 HITL 自动续流（续流时机三端各不相同）。</li>
 *   <li>input 包不碰文件系统 —— 附件落盘与路径校验属于宿主（各端规则不同），
 *       引擎只做装配。见 {@link InputAttachments}。</li>
 * </ol>
 *
 * <p>采用纯文本扫描，不引入 ArchUnit 等额外依赖。</p>
 */
class InputBoundaryTest {

    private static final Path INPUT_DIR = Paths.get("src/main/java/org/noear/solon/ai/harness/input");

    @Test
    void inputPackageMustNotImportHostPackages() throws IOException {
        List<Path> files = javaFiles(INPUT_DIR);
        assertFalse(files.isEmpty(), "input 包源码必须存在（否则本护栏形同虚设）");

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (content.contains("import org.noear.solon.codecli.")) {
                violations.add(file.getFileName().toString() + " 引用了宿主包 codecli");
            }
        }
        assertTrue(violations.isEmpty(),
                "input 是 harness 的通用能力，不得反向依赖宿主：" + violations);
    }

    @Test
    void inputOutcomeMustNotDependOnEngineOrScheduler() throws IOException {
        Path outcome = INPUT_DIR.resolve("InputOutcome.java");
        assertTrue(Files.exists(outcome), "InputOutcome 源码必须存在");

        String code = stripComments(new String(Files.readAllBytes(outcome), StandardCharsets.UTF_8));

        //能力靠注入：stream() 不得借引擎之力（否则 HITL 自动续流这类决策会偷偷进来）
        assertFalse(code.contains("import org.noear.solon.ai.harness.HarnessEngine"),
                "InputOutcome 不得反向依赖引擎");

        //调度与轮次决策属于宿主：Web 必须先注册 Disposable 再订阅，桌面续流要重挂拦截器
        assertFalse(code.contains("Schedulers"),
                "InputOutcome 不得替宿主切换调度器");
    }

    @Test
    void inputOutcomeStreamMustNotResumeHitl() throws IOException {
        Path outcome = INPUT_DIR.resolve("InputOutcome.java");
        assertTrue(Files.exists(outcome), "InputOutcome 源码必须存在");

        //HITL 续流时机三端不同（Web 整批审批、桌面包拦截器、CLI 多轮循环），引擎替任何一端做选择都是错的
        String code = stripComments(new String(Files.readAllBytes(outcome), StandardCharsets.UTF_8));
        assertFalse(code.contains("prompt("),
                "InputOutcome#stream 不得自动续流（续流须由宿主用 engine.handle(...resume(true)...) 显式发起）");
    }

    @Test
    void inputPackageMustNotTouchFileSystem() throws IOException {
        //落盘下沉到宿主：input 包（尤其 InputAttachments）不得读卷、写盘、推断 mime。
        //各端校验规则不同（桌面端有符号链接与总量上限，Web 端只需路径穿越校验），
        //引擎若重新接管落盘，就会把这些差异重新拉回库内。
        List<Path> files = javaFiles(INPUT_DIR);
        assertFalse(files.isEmpty(), "input 包源码必须存在（否则本护栏形同虚设）");

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String code = stripComments(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            String name = file.getFileName().toString();
            for (String token : new String[]{"java.nio.file", "Files.", "Paths.", "java.io.File"}) {
                if (code.contains(token)) {
                    violations.add(name + " 触碰文件系统：" + token);
                }
            }
            if (code.contains("InputAttachment ")) {
                //InputAttachment 已删除，回潮即违约
                violations.add(name + " 又重新引入了 InputAttachment");
            }
            if (code.contains("MediaItem") || code.contains("FileReference")) {
                //块自带 filePath、引用就是 String，包装类型已取消，回潮即违约
                violations.add(name + " 又重新引入了 MediaItem / FileReference 包装");
            }
        }
        assertTrue(violations.isEmpty(),
                "input 包只做装配，落盘与路径校验由宿主负责：" + violations);
    }

    /**
     * 去掉块注释与行注释，避免注释中提及的 stream() 影响断言
     */
    private static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)^\\s*//.*$", "");
    }

    @Test
    void commandOutputFactoryMustStayHostImplemented() throws IOException {
        //输出构造留给宿主：CLI 需要实时写终端
        //4.2 起命令输出工厂归 command 包（与 CommandOutput 同包）
        Path factory = INPUT_DIR.resolveSibling("command").resolve("CommandOutputFactory.java");
        assertTrue(Files.exists(factory), "CommandOutputFactory 源码必须存在");

        String content = new String(Files.readAllBytes(factory), StandardCharsets.UTF_8);
        assertTrue(content.contains("interface CommandOutputFactory"),
                "输出工厂必须是接口（由宿主实现）");
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
