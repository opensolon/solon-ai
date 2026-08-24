/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.code;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.talents.code.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Code 规范对齐的才能
 *
 * @author noear
 * @since 3.9.4
 */
public class CodeTalent extends AbsTalent {
    private static final Logger LOG = LoggerFactory.getLogger(CodeTalent.class);

    public final static String NAME_CODE_MD = "CODE.md";
    public final static String ATTR_CWD = "__cwd";

    /**
     * 目录树遍历的深度上限。
     * <p>注 walkFileTree 语义：恰好处于 maxDepth 的目录只会走 visitFile（不走 preVisitDirectory），
     * 故真正参与模块识别的是根目录往下 3 层。
     */
    private static final int MAX_SCAN_DEPTH = 4;

    /**
     * 仅在“根目录一级”参考的泛化线索（深层目录里几乎必然存在，故不参与深层判定）
     */
    private static final Set<String> ROOT_HINTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(".git", ".github", ".gitee", "src", "lib")));

    /**
     * init 结果缓存的有效期（同一根目录内，避免每轮对话都全树重扫）。
     * <p>仅用于「已识别出技术栈」的稳定结论：项目一旦是 Maven 工程，就不会在几分钟内变成别的。
     */
    private static final long INIT_CACHE_TTL_MS = 5 * 60 * 1000L;

    /**
     * 弱结论的缓存有效期：「未检测到技术栈」说明项目可能尚在搭建中（如空目录起步），
     * 随时会冒出 pom.xml / package.json，故只做很短的缓存以便尽快自愈。
     */
    private static final long INIT_WEAK_TTL_MS = 15 * 1000L;

    /**
     * 失败结果的缓存有效期（远短于成功，便于权限或环境修复后尽快自愈）
     */
    private static final long FAIL_CACHE_TTL_MS = 30 * 1000L;

    /**
     * isSupported 肯定结论的缓存有效期（该判定可能触发全树遍历，不宜每轮重算）
     */
    private static final long SUPPORT_CACHE_TTL_MS = 60 * 1000L;

    /**
     * isSupported 否定结论的缓存有效期：空目录/非工程目录随时可能变成工程，故显著短于肯定结论
     */
    private static final long SUPPORT_NEG_TTL_MS = 5 * 1000L;

    /**
     * 单个缓存表的最大条目数（防止长驻进程中 cwd 频繁切换导致无界增长）
     */
    private static final int MAX_CACHE_ENTRIES = 64;

    /**
     * 同构子模块清单的最大展开条目数（超出则截断，避免提示词被大型多模块工程淹没）
     */
    private static final int MAX_MODULE_LIST_ITEMS = 120;

    private final String workDir;
    private final String codeDir;
    private final String codeMdPath;
    private final List<LanguageProvider> providers;
    private final Map<String, Cached<String>> initCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<Boolean>> supportCache = new ConcurrentHashMap<>();

    public CodeTalent(String workDir, String codeDir) {
        this.workDir = workDir;
        this.codeDir = normalizeDir(codeDir);
        this.codeMdPath = this.codeDir + NAME_CODE_MD;

        List<LanguageProvider> tmp = new ArrayList<>();
        tmp.add(new MavenProvider());
        tmp.add(new GradleProvider());
        tmp.add(new NodeProvider());
        tmp.add(new GoProvider());
        tmp.add(new PythonProvider());
        tmp.add(new RustProvider());

        tmp.add(new CangjieProvider());
        tmp.add(new CMakeProvider());
        tmp.add(new FlutterProvider());
        tmp.add(new PhpProvider());
        tmp.add(new DotNetProvider());

        // 扩展点：使用方可通过 META-INF/services 注册自定义语言（内置实现优先）
        tmp.addAll(loadExtraProviders());

        this.providers = Collections.unmodifiableList(tmp);
    }

    /**
     * 加载 SPI 扩展的语言提供者
     */
    private static List<LanguageProvider> loadExtraProviders() {
        List<LanguageProvider> list = new ArrayList<>();
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = LanguageProvider.class.getClassLoader();
            }

            for (LanguageProvider p : ServiceLoader.load(LanguageProvider.class, cl)) {
                list.add(p);
            }
        } catch (Throwable e) {
            LOG.warn("Load LanguageProvider extensions failed", e);
        }
        return list;
    }

    /**
     * 当前生效的语言提供者（含 SPI 扩展）
     *
     * @since 3.10.6
     */
    public List<LanguageProvider> providers() {
        return providers;
    }

    /**
     * CODE.md 相对于项目根目录的路径（如 `.soloncode/CODE.md`）
     */
    public String codeMdPath() {
        return codeMdPath;
    }

    /**
     * @deprecated 3.10.5 改用 {@link #codeMdPath()}
     */
    @Deprecated
    public String HOME_CODE_MD() {
        return codeMdPath();
    }


    @Override
    public String description() {
        return "代码专家。支持项目初始化、技术栈自动识别以及 `" + codeMdPath + "` 规约生成。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        Path rootPath;
        try {
            rootPath = getRootPath(prompt == null ? null : prompt.attrAs(ATTR_CWD));
        } catch (RuntimeException e) {
            // 工作目录未设置：视为不适用，而非向上抛出
            return false;
        }

        String key = rootPath.toString();
        String sign = rootSignature(rootPath);

        Cached<Boolean> cached = supportCache.get(key);
        if (cached != null && cached.isFresh(sign)) {
            return cached.value;
        }

        boolean supported = detectSupported(rootPath);
        // 否定结论只短暂缓存：空目录可能下一秒就被初始化成一个工程
        long ttl = supported ? SUPPORT_CACHE_TTL_MS : SUPPORT_NEG_TTL_MS;
        putCache(supportCache, key, new Cached<>(supported, ttl, sign));
        return supported;
    }

    private boolean detectSupported(Path rootPath) {
        if (rootExists(rootPath, codeMdPath)) {
            return true;
        }

        // 一、根目录一级：泛化线索 + 构建标记
        Set<String> rootNames = LanguageProvider.listNames(rootPath);
        for (String name : rootNames) {
            if (ROOT_HINTS.contains(name)) return true;
        }

        if (anyMatch(rootPath, rootNames)) {
            return true;
        }

        // 二、深层：单趟遍历，仅认构建标记文件
        return deepMatchExists(rootPath);
    }

    /**
     * 挂载钩子：把「扫描 + 写 CODE.md」这类副作用收敛到此处，避免落在拼装提示词的热路径上
     */
    @Override
    public void onAttach(Prompt prompt) {
        initCached(prompt == null ? null : prompt.attrAs(ATTR_CWD));
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String __cwd = prompt == null ? null : prompt.attrAs(ATTR_CWD);

        StringBuilder buf = new StringBuilder();

        // 正常流程下 onAttach 已完成初始化，此处只是读缓存
        String msg = initCached(__cwd);

        buf.append("\n## 核心工程规约 (Core Engineering Protocol)\n");
        buf.append("> 项目当前上下文: ").append(msg).append("\n\n");

        buf.append("为了确保工程质量，要严格执行以下操作：\n")
                .append("1. **动作前导**: 在开始任何任务前，先读 `" + codeMdPath + "` 以获取构建和测试指令。\n")
                .append("2. **验证驱动**: 修改代码后，参考 `" + codeMdPath + "` 中的指令运行测试，严禁未验证提交。\n");

        return buf.toString();
    }

    private static String normalizeDir(String dir) {
        if (dir == null || dir.isEmpty()) {
            return "";
        }

        if (dir.endsWith("/") || dir.endsWith("\\")) {
            return dir;
        }

        return dir + "/";
    }

    private Path getRootPath(String __cwd) {
        String path = (__cwd != null) ? __cwd : workDir;
        if (path == null) throw new IllegalStateException("Working directory is not set.");
        return Paths.get(path).toAbsolutePath().normalize();
    }

    /**
     * 带 TTL 缓存的初始化（供每轮提示词调用；显式 {@link #init(String)} 仍会强制重扫）
     */
    private String initCached(String __cwd) {
        String key;
        String sign;
        try {
            Path rootPath = getRootPath(__cwd);
            key = rootPath.toString();
            sign = rootSignature(rootPath);
        } catch (RuntimeException e) {
            return init(__cwd);
        }

        Cached<String> cached = initCache.get(key);
        if (cached != null && cached.isFresh(sign)) {
            return cached.value;
        }

        synchronized (this) {
            // 双检：避免并发场景下多个线程同时全树重扫并写同一个文件
            cached = initCache.get(key);
            if (cached != null && cached.isFresh(sign)) {
                return cached.value;
            }

            return init(__cwd);
        }
    }

    /**
     * 根目录的廉价指纹（一次 readdir）：条目名集合 + 目录 mtime。
     * <p>用于在 TTL 到期前就感知到根目录结构变化（典型场景：从空目录起步，
     * 随后生成了 pom.xml / package.json 或新的顶层模块目录），成本远低于全树重扫。
     */
    private static String rootSignature(Path rootPath) {
        try {
            List<String> names = new ArrayList<>(LanguageProvider.listNames(rootPath));
            Collections.sort(names);

            long mtime = -1L;
            try {
                mtime = Files.getLastModifiedTime(rootPath).toMillis();
            } catch (IOException ignored) {
                // 目录不存在或不可读：仅依赖条目名集合
            }

            return mtime + "|" + String.join(",", names);
        } catch (Throwable e) {
            // 取不到指纹时返回空串，退化为纯 TTL 语义
            return "";
        }
    }

    public synchronized String init(String __cwd) {
        Path failPath = null;
        try {
            final Path rootPath = getRootPath(__cwd);
            failPath = rootPath;

            if (!Files.isWritable(rootPath)) {
                return failed(rootPath, "目录不可写，本次未生成工程规范文件");
            }

            StringBuilder newContent = new StringBuilder();
            newContent.append("## 构建与测试指令 (Build and Test Commands)\n\n");

            List<String> detectedStacks = new ArrayList<>();
            Set<LanguageProvider> rootMatched = new HashSet<>();
            // 保留探测顺序的版本汇总：“位置描述” -> “版本标签”
            Map<String, String> detectedVersions = new LinkedHashMap<>();

            Set<String> rootNames = LanguageProvider.listNames(rootPath);
            for (LanguageProvider provider : providers) {
                if (provider.isMatch(rootPath, rootNames)) {
                    rootMatched.add(provider);
                    detectedStacks.add(provider.id() + " (Root)");
                    provider.appendRootCommands(newContent);

                    String ver = provider.detectVersion(rootPath);
                    if (ver != null) {
                        detectedVersions.put(provider.id() + " (Root)", ver);
                    }
                }
            }

            // 子模块候选：目录 -> 该目录下的条目名（一次列举，供各 Provider 复用）
            Map<Path, Set<String>> allNodes = new LinkedHashMap<>();
            try {
                walkDirs(rootPath, MAX_SCAN_DEPTH, (dir, entryNames) -> {
                    if (!dir.equals(rootPath)) {
                        allNodes.put(dir, entryNames);
                    }
                    return true;
                });
            } catch (IOException e) {
                LOG.error("Scan sub-modules failed", e);
            }

            // 同构模块（与根项目同一技术栈）：按类型归组，最终折叠成一行，避免逐行复述同一句套话
            Map<String, List<String>> homogeneous = new LinkedHashMap<>();

            // 已作为“叶子模块”处理过的路径，其子目录不再单独列出（聚合器不参与，见 isAggregator）
            Set<String> processedPaths = new HashSet<>();

            for (Map.Entry<Path, Set<String>> node : allNodes.entrySet()) {
                Path dir = node.getKey();
                String relativePath = rootPath.relativize(dir).toString().replace("\\", "/");

                // 如果父目录已经作为叶子模块处理过了，子目录就不再单独列出
                if (isUnderProcessed(processedPaths, relativePath)) continue;

                for (LanguageProvider provider : providers) {
                    if (provider.isMatch(dir, node.getValue())) {
                        // 聚合器（如声明了 <modules> 的父 POM、Cargo workspace）自身虽是模块，
                        // 但真正的代码模块在其子目录下，故不能据此剪掉整棵子树
                        if (!provider.isAggregator(dir, node.getValue())) {
                            processedPaths.add(relativePath);
                        }

                        // 判断异构：如果当前 Provider 没在根目录出现过，则是异构
                        boolean isHeterogeneous = !rootMatched.contains(provider);
                        String ver = provider.detectVersion(dir);

                        if (isHeterogeneous) {
                            detectedStacks.add(relativePath + " (" + provider.id() + ")");
                            provider.appendModuleCommands(newContent, relativePath);
                            if (ver != null) {
                                detectedVersions.put(relativePath + " (" + provider.id() + ")", ver);
                            }
                        } else {
                            String item = (ver == null) ? relativePath : relativePath + "（" + ver + "）";
                            homogeneous.computeIfAbsent(provider.typeName(), k -> new ArrayList<>()).add(item);
                        }
                        break; // 一个目录识别为一个主语言即可
                    }
                }
            }

            appendHomogeneousModules(newContent, homogeneous);

            appendVersions(newContent, detectedVersions);

            appendGuidelines(newContent);

            Path targetPath = rootPath.resolve(codeMdPath);
            String finalContent = newContent.toString();
            boolean updated = true;
            if (Files.exists(targetPath)) {
                updated = !finalContent.equals(new String(Files.readAllBytes(targetPath), StandardCharsets.UTF_8));
            }
            if (updated) {
                Path parent = targetPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(targetPath, finalContent.getBytes(StandardCharsets.UTF_8));
            }

            ensureInGitignore(rootPath, codeDir.isEmpty() ? NAME_CODE_MD : codeDir);

            StringBuilder resultMsg = new StringBuilder();
            resultMsg.append(updated ? "已更新" : "已验证").append("项目工程规范");
            if (!detectedStacks.isEmpty()) {
                resultMsg.append(" (检测到技术栈: ").append(String.join(", ", detectedStacks)).append(")");
            } else {
                resultMsg.append(" (未检测到明确的技术栈)");
            }
            if (!detectedVersions.isEmpty()) {
                List<String> pairs = new ArrayList<>();
                for (Map.Entry<String, String> e : detectedVersions.entrySet()) {
                    pairs.add(e.getKey() + ": " + e.getValue());
                }
                resultMsg.append(" (环境版本: ").append(String.join(", ", pairs)).append(")");
            }

            String msg = resultMsg.toString();
            // 指纹必须在写入 CODE.md 之后采集：创建 .soloncode/ 本身会改变根目录条目与 mtime，
            // 否则缓存会在下一轮被自己的副作用失效
            // 未识别到技术栈是弱结论（项目可能正在搭建），只短暂缓存
            long ttl = detectedStacks.isEmpty() ? INIT_WEAK_TTL_MS : INIT_CACHE_TTL_MS;
            putCache(initCache, rootPath.toString(), new Cached<>(msg, ttl, rootSignature(rootPath)));
            return msg;
        } catch (Throwable e) {
            LOG.error("Init failed", e);
            return failed(failPath, "初始化异常: " + e.getMessage());
        }
    }

    /**
     * 失败信息也进缓存（短 TTL），避免每轮对话都重复失败的全树扫描
     */
    private String failed(Path rootPath, String reason) {
        String msg = "未能生成项目工程规范（" + reason + "），请直接阅读源码与构建配置了解项目结构";
        if (rootPath != null) {
            putCache(initCache, rootPath.toString(), new Cached<>(msg, FAIL_CACHE_TTL_MS, rootSignature(rootPath)));
        }
        return msg;
    }

    /**
     * 该相对路径是否位于某个已处理模块之下
     */
    private static boolean isUnderProcessed(Set<String> processedPaths, String relativePath) {
        for (String p : processedPaths) {
            if (relativePath.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 同构子模块清单：每种类型仅一行，模块名以逗号分隔（同样的信息量，约 1/4 的体积）
     */
    private void appendHomogeneousModules(StringBuilder buf, Map<String, List<String>> homogeneous) {
        if (homogeneous.isEmpty()) {
            return;
        }

        buf.append("### 子模块与子项目 (Sub-modules & Sub-projects)\n")
                .append("> 以下模块受根项目指令统一控制（构建与测试请按上文根项目指令执行）。\n");

        for (Map.Entry<String, List<String>> e : homogeneous.entrySet()) {
            List<String> items = e.getValue();
            buf.append("- ").append(e.getKey()).append(" (").append(items.size()).append(" 个): ");

            if (items.size() > MAX_MODULE_LIST_ITEMS) {
                buf.append(String.join(", ", items.subList(0, MAX_MODULE_LIST_ITEMS)))
                        .append(" … 及其余 ").append(items.size() - MAX_MODULE_LIST_ITEMS).append(" 个");
            } else {
                buf.append(String.join(", ", items));
            }
            buf.append("\n");
        }

        buf.append("\n");
    }

    /**
     * 深层探测：单趟遍历，任一目录命中任一 Provider 的构建标记即返回
     */
    private boolean deepMatchExists(Path rootPath) {
        final boolean[] found = {false};

        try {
            walkDirs(rootPath, MAX_SCAN_DEPTH, (dir, entryNames) -> {
                if (anyMatch(dir, entryNames)) {
                    found[0] = true;
                    return false;
                }
                return true;
            });
        } catch (IOException e) {
            LOG.debug("Deep scan failed", e);
        }

        return found[0];
    }

    private boolean anyMatch(Path dir, Set<String> entryNames) {
        for (LanguageProvider provider : providers) {
            if (provider.isMatch(dir, entryNames)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单趟遍历目录树（跳过忽略项与不可读目录），每个目录仅列举一次条目名后回调
     */
    private void walkDirs(Path rootPath, int maxDepth, DirVisitor visitor) throws IOException {
        Files.walkFileTree(rootPath, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(rootPath)) {
                    // 无权限的目录直接跳过，避免抛异常中断整趟遍历
                    if (isIgnored(dir) || !Files.isReadable(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }

                boolean next = visitor.visit(dir, LanguageProvider.listNames(dir));
                return next ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // 无权限、失效链接等：跳过即可，不中断遍历
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void appendVersions(StringBuilder buf, Map<String, String> versions) {
        if (versions.isEmpty()) {
            return;
        }

        buf.append("## 环境版本 (Environment Versions)\n\n")
                .append("> 以下为项目配置文件中**声明**的版本（非本机安装版本），写代码时请对齐该版本的语法特性。\n\n");
        for (Map.Entry<String, String> e : versions.entrySet()) {
            buf.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        buf.append("\n");
    }

    private void appendGuidelines(StringBuilder buf) {
        buf.append("## 工程规约 (Guidelines)\n\n")
                .append("- **改前必读**: 在进行任何修改前，务必完整阅读相关文件内容。\n")
                .append("- **原子作业**: 每次仅实现一个功能或修复一个 Bug。\n")
                .append("- **验证驱动**: 任务完成前必须运行测试进行验证。\n")
                .append("- **路径规范**: 仅使用相对路径（例如：`src/main/java/App.java`，严禁使用 `./src/...`）。\n")
                .append("- **风格对齐**: 必须遵循代码库中已有的编码风格和设计模式。\n")
                .append("- **版本对齐**: 参考「环境版本」章节声明的版本，不要使用超过该版本的语法特性；若未列出版本，应从配置文件或构建工具复核后再决定。\n")
                .append("- **环境感知**: 利用你对各语言默认本地仓库路径（如 Maven、Node）的知识，协助排查依赖问题或进行源码分析。\n\n");

    }

    /**
     * 若项目已有 .gitignore，则确保其中包含指定条目；不主动创建 .gitignore（未纳入版本管理的项目无需干预）
     */
    private void ensureInGitignore(Path rootPath, String fileName) {
        try {
            Path gitignore = rootPath.resolve(".gitignore");
            if (Files.exists(gitignore)) {
                List<String> lines = Files.readAllLines(gitignore, StandardCharsets.UTF_8);

                if (!gitignoreContains(lines, fileName)) {
                    String separator = (lines.isEmpty() || lines.get(lines.size() - 1).isEmpty()) ? "" : "\n";
                    Files.write(gitignore, (separator + fileName + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * .gitignore 是否已忽略该条目（容忍前后斜杠差异：`x`、`x/`、`/x`、`/x/` 等价）
     */
    private static boolean gitignoreContains(List<String> lines, String fileName) {
        String base = trimSlash(fileName);
        if (base.isEmpty()) {
            return true;
        }

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            // 去掉行内注释后的尾随内容（如 "target/  # build output"）
            int sp = line.indexOf(' ');
            if (sp > 0) {
                line = line.substring(0, sp);
            }

            if (base.equals(trimSlash(line))) {
                return true;
            }
        }
        return false;
    }

    private static String trimSlash(String s) {
        String r = s.trim();
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        while (r.endsWith("/") || r.endsWith("\\")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    private boolean rootExists(Path rootPath, String path) {
        return Files.exists(rootPath.resolve(path));
    }

    private boolean isIgnored(Path path) {
        String pathName = path.getFileName().toString();

        // 1. 基础全局忽略（隐藏目录如 .git, .idea, .soloncode 等）
        if (pathName.startsWith(".")) {
            return true;
        }

        // 2. 委派给各语言实现类（含构建输出目录，如 target/build/bin/node_modules）
        for (LanguageProvider p : providers) {
            if (p.isIgnored(pathName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 写入缓存并做容量控制（先清过期，仍超限则整体清空）
     */
    private static <T> void putCache(Map<String, Cached<T>> cache, String key, Cached<T> value) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.values().removeIf(Cached::isExpired);

            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
        }

        cache.put(key, value);
    }

    /**
     * 目录访问回调
     */
    @FunctionalInterface
    private interface DirVisitor {
        /**
         * @param dir        当前目录
         * @param entryNames 该目录下的直接条目名
         * @return true 继续遍历；false 终止遍历
         */
        boolean visit(Path dir, Set<String> entryNames);
    }

    /**
     * 带过期时间与目录指纹的缓存条目（两者任一不满足即视为失效）
     */
    private static class Cached<T> {
        final T value;
        final long expireAt;
        final String signature;

        Cached(T value, long ttlMs, String signature) {
            this.value = value;
            this.expireAt = System.currentTimeMillis() + ttlMs;
            this.signature = signature;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }

        /**
         * 未过期且根目录结构未变动
         */
        boolean isFresh(String currentSignature) {
            return !isExpired() && Objects.equals(signature, currentSignature);
        }
    }
}
