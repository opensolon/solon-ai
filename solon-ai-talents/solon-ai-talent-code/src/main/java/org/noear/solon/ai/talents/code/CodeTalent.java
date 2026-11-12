/*
 * Copyright 2017-2026 noear.org and authors
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
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.talents.code.i18n.CodeI18n;
import org.noear.solon.ai.talents.code.i18n.CodeI18nKeys;
import org.noear.solon.ai.talents.code.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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
     * 同构子模块清单的最大展开条目数。
     * <p>
     * 注意：CODE.md 不会被注入提示词（getInstruction 只给出一行摘要 + 文件路径），
     * 因此截断并不省提示词开销，只是防止病态工程写出超大文件；正常多模块工程应完整列出。
     * 触发截断时必须给出可自行补全清单的检索方式，避免模型误判“模块不存在”。
     */
    private static final int MAX_MODULE_LIST_ITEMS = 500;

    private final String workDir;
    private final String codeDir;
    private final String codeMdPath;
    private final List<LanguageProvider> providers;
    private final Map<String, Cached<InitResult>> initCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<Boolean>> supportCache = new ConcurrentHashMap<>();
    /**
     * 按根目录分段的扫描锁（固定 64 段，内存有界）。
     * <p>曾锁整个实例：一个才能实例服务多个会话目录时，一个大仓库的首次全树扫描会挡住
     * 其它所有目录的这一轮。同一根目录仍串行（避免同时重扫并写同一份 CODE.md）。</p>
     *
     * <p>临界区内是全树扫描与 CODE.md 写入，属于持锁 I/O。用 {@link ReentrantLock} 而非监视器：
     * 在支持虚拟线程的 JDK（21–23）上，进入被占用的监视器会钉住载体线程。</p>
     */
    private final ReentrantLock[] rootLocks = newLocks(64);

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
    public String description(Prompt prompt) {
        return CodeI18n.fmt(AiI18n.localeOf(prompt), CodeI18nKeys.CODE_DESCRIPTION, "path", codeMdPath);
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
        initCached(prompt == null ? null : prompt.attrAs(ATTR_CWD), AiI18n.localeOf(prompt));
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String __cwd = prompt == null ? null : prompt.attrAs(ATTR_CWD);
        Locale requestLocale = AiI18n.localeOf(prompt);

        // 正常流程下 onAttach 已完成初始化，此处只是读缓存
        String msg = initCached(__cwd, requestLocale).message(requestLocale);

        return "\n" + CodeI18n.fmt(requestLocale, CodeI18nKeys.CODE_INSTRUCTION, "context", msg, "path", codeMdPath) + "\n";
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
     * 带 TTL 缓存的初始化（供每轮提示词调用；显式 {@link #init(String, Locale)} 仍会强制重扫）
     *
     * @param locale 请求语言（只在新建 CODE.md 时决定文件语言，见 {@link #fileLocaleOf(Path, Locale)}）
     */
    private InitResult initCached(String __cwd, Locale locale) {
        String key;
        String sign;
        try {
            Path rootPath = getRootPath(__cwd);
            key = rootPath.toString();
            sign = rootSignature(rootPath);
        } catch (RuntimeException e) {
            return initResult(__cwd, locale);
        }

        Cached<InitResult> cached = initCache.get(key);
        if (cached != null && cached.isFresh(sign)) {
            return cached.value;
        }

        ReentrantLock lock = lockOf(key);
        lock.lock();
        try {
            // 双检：避免并发场景下多个线程同时全树重扫并写同一个文件
            cached = initCache.get(key);
            if (cached != null && cached.isFresh(sign)) {
                return cached.value;
            }

            return initResult(__cwd, locale);
        } finally {
            lock.unlock();
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


    /**
     * 强制重扫并生成 CODE.md
     *
     * <p>CODE.md 是各会话共用的文件：已存在时沿用它的语言（按标题识别），新建时才用 locale，
     * 避免不同语言的会话来回改写同一个文件。</p>
     *
     * @param __cwd  工作目录（null 为才能的工作目录）
     * @param locale 请求语言：结论的语言，也是新建 CODE.md 的语言（null 为默认语言）
     * @since 4.2
     */
    public String init(String __cwd, Locale locale) {
        return initResult(__cwd, locale).message(locale);
    }

    private InitResult initResult(String __cwd, Locale locale) {
        final Path lockPath;
        try {
            lockPath = getRootPath(__cwd);
        } catch (RuntimeException e) {
            return doInit(__cwd, locale); // 根目录都解析不了：交给 doInit 报告失败
        }
        ReentrantLock lock = lockOf(lockPath.toString());
        lock.lock();
        try {
            return doInit(__cwd, locale);
        } finally {
            lock.unlock();
        }
    }

    /**
     * CODE.md 的语言：已有文件的语言优先（首行与各语言的标题逐字比对），否则用请求语言
     *
     * <p>请求语言排在最前面比对：兜底到同一份文案的语言（如未翻译的语言落回英文）不会被误判成别的语言。
     * 首行对不上任何语言（文件被手工改过）时按请求语言重新生成。</p>
     */
    private static Locale fileLocaleOf(Path targetPath, Locale requestLocale) {
        Locale resolved = AiI18n.resolve(requestLocale);
        if (Files.isRegularFile(targetPath) == false) {
            return resolved;
        }

        String firstLine;
        try (BufferedReader reader = Files.newBufferedReader(targetPath, StandardCharsets.UTF_8)) {
            firstLine = reader.readLine();
        } catch (IOException | RuntimeException e) {
            return resolved;
        }

        if (firstLine == null) {
            return resolved;
        }

        Set<Locale> candidates = new LinkedHashSet<>();
        candidates.add(resolved);
        candidates.addAll(AiI18n.knownLocales());
        for (Locale l : candidates) {
            if (firstLine.equals(CodeI18n.get(l, CodeI18nKeys.CODE_MD_TITLE))) {
                return l;
            }
        }

        return resolved;
    }

    private static ReentrantLock[] newLocks(int n) {
        ReentrantLock[] locks = new ReentrantLock[n];
        for (int i = 0; i < n; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    private ReentrantLock lockOf(String rootKey) {
        return rootLocks[(rootKey.hashCode() & 0x7fffffff) % rootLocks.length];
    }

    private InitResult doInit(String __cwd, Locale requestLocale) {
        Path failPath = null;
        try {
            final Path rootPath = getRootPath(__cwd);
            failPath = rootPath;

            if (!Files.isWritable(rootPath)) {
                return failed(rootPath, CodeI18nKeys.CODE_INIT_REASON_NOT_WRITABLE, null);
            }

            final Path targetPath = rootPath.resolve(codeMdPath);
            // CODE.md 是共用文件：已有文件的语言优先，新建时用请求语言
            final Locale fileLocale = fileLocaleOf(targetPath, requestLocale);

            StringBuilder newContent = new StringBuilder();
            newContent.append(CodeI18n.get(fileLocale, CodeI18nKeys.CODE_MD_TITLE)).append("\n\n");

            List<String> detectedStacks = new ArrayList<>();
            Set<LanguageProvider> rootMatched = new HashSet<>();
            // 保留探测顺序的版本汇总：“位置描述” -> “版本标签”
            Map<String, String> detectedVersions = new LinkedHashMap<>();

            Set<String> rootNames = LanguageProvider.listNames(rootPath);
            for (LanguageProvider provider : providers) {
                if (provider.isMatch(rootPath, rootNames)) {
                    rootMatched.add(provider);
                    detectedStacks.add(provider.id() + " (Root)");
                    provider.appendRootCommands(newContent, fileLocale);

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

            // 同构模块（与根项目同一技术栈）：按类型归组，共用一句说明，不逐行复述套话
            // 以 Provider 为键，便于截断时给出该类型的构建标记检索式
            Map<LanguageProvider, List<String>> homogeneous = new LinkedHashMap<>();

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
                            provider.appendModuleCommands(newContent, relativePath, fileLocale);
                            if (ver != null) {
                                detectedVersions.put(relativePath + " (" + provider.id() + ")", ver);
                            }
                        } else {
                            String item = (ver == null) ? relativePath
                                    : CodeI18n.fmt(fileLocale, CodeI18nKeys.CODE_MD_MODULES_ITEM_VERSION, "path", relativePath, "version", ver);
                            homogeneous.computeIfAbsent(provider, k -> new ArrayList<>()).add(item);
                        }
                        break; // 一个目录识别为一个主语言即可
                    }
                }
            }

            appendHomogeneousModules(newContent, homogeneous, fileLocale);

            appendVersions(newContent, detectedVersions, fileLocale);

            appendGuidelines(newContent, fileLocale);

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

            List<String> pairs = new ArrayList<>();
            for (Map.Entry<String, String> e : detectedVersions.entrySet()) {
                pairs.add(e.getKey() + ": " + e.getValue());
            }

            // 结论只存结构，按调用方语言渲染（同一份缓存服务不同语言的请求）
            InitResult msg = InitResult.success(updated, detectedStacks, pairs);
            // 指纹必须在写入 CODE.md 之后采集：创建 .soloncode/ 本身会改变根目录条目与 mtime，
            // 否则缓存会在下一轮被自己的副作用失效
            // 未识别到技术栈是弱结论（项目可能正在搭建），只短暂缓存
            long ttl = detectedStacks.isEmpty() ? INIT_WEAK_TTL_MS : INIT_CACHE_TTL_MS;
            putCache(initCache, rootPath.toString(), new Cached<>(msg, ttl, rootSignature(rootPath)));
            return msg;
        } catch (Throwable e) {
            LOG.error("Init failed", e);
            return failed(failPath, CodeI18nKeys.CODE_INIT_REASON_ERROR, e.getMessage());
        }
    }

    /**
     * 失败信息也进缓存（短 TTL），避免每轮对话都重复失败的全树扫描
     *
     * @param reasonKey 原因文案 key
     * @param error     异常信息（原因文案带 {error} 占位符时非 null）
     */
    private InitResult failed(Path rootPath, String reasonKey, String error) {
        InitResult msg = InitResult.failure(reasonKey, error);
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
     * 同构子模块清单：按类型归组，组内一行一个模块（完整相对路径）。
     * <p>
     * 不把模块挤成一行逗号列表：路径本身含 `/` 与 `-`，逗号分隔可读性差、精确取值易错；
     * 而换行相比 ", " 每个模块仅多约 3 字符，与去掉逐行套话省下的体积不在一个量级。
     * <p>
     * 仅在病态规模下才截断，且必须附带该类型的检索方式：清单不完整时若不给补全手段，
     * 读者会把“清单里没有”误当成“模块不存在”。
     */
    private void appendHomogeneousModules(StringBuilder buf, Map<LanguageProvider, List<String>> homogeneous, Locale fileLocale) {
        if (homogeneous.isEmpty()) {
            return;
        }

        buf.append(CodeI18n.get(fileLocale, CodeI18nKeys.CODE_MD_MODULES_TITLE)).append("\n");

        for (Map.Entry<LanguageProvider, List<String>> e : homogeneous.entrySet()) {
            LanguageProvider provider = e.getKey();
            List<String> items = new ArrayList<>(e.getValue());
            // 排序使同一父目录下的模块相邻，便于人工核对
            Collections.sort(items);

            buf.append("- ").append(CodeI18n.fmt(fileLocale, CodeI18nKeys.CODE_MD_MODULES_GROUP,
                    "type", provider.typeName(fileLocale), "count", items.size())).append("\n");

            int shown = Math.min(items.size(), MAX_MODULE_LIST_ITEMS);
            for (int i = 0; i < shown; i++) {
                buf.append("  - ").append(items.get(i)).append("\n");
            }
            if (items.size() > shown) {
                buf.append("  - ").append(CodeI18n.fmt(fileLocale, CodeI18nKeys.CODE_MD_MODULES_MORE,
                        "count", items.size() - shown, "globs", markerGlobs(provider, fileLocale))).append("\n");
            }
        }

        buf.append("\n");
    }

    /**
     * 该类型的构建标记检索式，供清单被截断时自行补全（如 `**&#47;pom.xml`）
     */
    private static String markerGlobs(LanguageProvider provider, Locale fileLocale) {
        StringBuilder sb = new StringBuilder();
        for (String marker : provider.markers()) {
            if (sb.length() > 0) {
                sb.append(" ").append(CodeI18n.get(fileLocale, CodeI18nKeys.CODE_MD_MODULES_GLOB_OR)).append(" ");
            }
            sb.append("`**/").append(marker).append("`");
        }
        return sb.length() == 0 ? CodeI18n.get(fileLocale, CodeI18nKeys.CODE_MD_MODULES_GLOB_FALLBACK) : sb.toString();
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

    private void appendVersions(StringBuilder buf, Map<String, String> versions, Locale fileLocale) {
        if (versions.isEmpty()) {
            return;
        }

        buf.append(CodeI18n.get(fileLocale, CodeI18nKeys.CODE_MD_VERSIONS)).append("\n\n");
        for (Map.Entry<String, String> e : versions.entrySet()) {
            buf.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        buf.append("\n");
    }

    private void appendGuidelines(StringBuilder buf, Locale fileLocale) {
        buf.append(CodeI18n.get(fileLocale, CodeI18nKeys.CODE_MD_GUIDELINES)).append("\n\n");
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
     * 初始化结论（只存结构，按语言渲染）
     */
    private static final class InitResult {
        final boolean failed;
        final boolean updated;
        final List<String> stacks;
        final List<String> versions;
        final String reasonKey;
        final String error;

        private InitResult(boolean failed, boolean updated, List<String> stacks, List<String> versions,
                           String reasonKey, String error) {
            this.failed = failed;
            this.updated = updated;
            this.stacks = stacks;
            this.versions = versions;
            this.reasonKey = reasonKey;
            this.error = error;
        }

        static InitResult success(boolean updated, List<String> stacks, List<String> versions) {
            return new InitResult(false, updated, Collections.unmodifiableList(new ArrayList<>(stacks)),
                    Collections.unmodifiableList(new ArrayList<>(versions)), null, null);
        }

        static InitResult failure(String reasonKey, String error) {
            return new InitResult(true, false, Collections.emptyList(), Collections.emptyList(), reasonKey, error);
        }

        String message(Locale locale) {
            if (failed) {
                String reason = CodeI18nKeys.CODE_INIT_REASON_ERROR.equals(reasonKey)
                        ? CodeI18n.fmt(locale, CodeI18nKeys.CODE_INIT_REASON_ERROR, "error", error)
                        : CodeI18n.get(locale, CodeI18nKeys.CODE_INIT_REASON_NOT_WRITABLE);
                return CodeI18n.fmt(locale, CodeI18nKeys.CODE_INIT_FAILED, "reason", reason);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(updated ? CodeI18n.get(locale, CodeI18nKeys.CODE_INIT_UPDATED) : CodeI18n.get(locale, CodeI18nKeys.CODE_INIT_VERIFIED));
            sb.append(" ");
            if (stacks.isEmpty()) {
                sb.append(CodeI18n.get(locale, CodeI18nKeys.CODE_INIT_NO_STACKS));
            } else {
                sb.append(CodeI18n.fmt(locale, CodeI18nKeys.CODE_INIT_STACKS, "stacks", String.join(", ", stacks)));
            }
            if (versions.isEmpty() == false) {
                sb.append(" ").append(CodeI18n.fmt(locale, CodeI18nKeys.CODE_INIT_VERSIONS, "versions", String.join(", ", versions)));
            }
            return sb.toString();
        }
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
