/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.cli;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 文件编码策略：read / edit / write / grep 识别非 UTF-8 文件时用的候选编码与按路径指定的编码。
 *
 * <p>识别顺序见 {@link TextEncodings#detect}：BOM → 路径规则 → 严格合法的 UTF-8 → 大体是 UTF-8 → 候选编码 → 无法确定。
 * 候选编码只在文件不是合法 UTF-8 时才尝试，且必须解码无错、往返逐字节相同、内容像正常文本才采用；
 * 单字节编码（ISO-8859-1、windows-1252 等）几乎任何字节都能解码，不适合自动猜，请用路径规则指定。</p>
 *
 * <p>不可变；默认值见 {@link #defaults()}。</p>
 *
 * @author noear
 * @since 4.2
 */
public final class FileEncodingPolicy {
    private static final FileEncodingPolicy DEFAULTS = new FileEncodingPolicy(defaultCandidates(), Collections.emptyList());

    private final List<Charset> candidates;
    private final List<Rule> rules;

    private FileEncodingPolicy(List<Charset> candidates, List<Rule> rules) {
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
    }

    /**
     * 默认策略：候选编码为 GBK；系统默认编码是 GBK 以外的多字节编码（如 MS932、MS950、EUC-KR）时追加在后面；没有路径规则。
     */
    public static FileEncodingPolicy defaults() {
        return DEFAULTS;
    }

    /**
     * 按名字构建（便于从配置读取）。
     *
     * @param candidates 候选编码名，按顺序尝试；null 表示用默认候选，空列表表示不自动识别
     * @param rules      路径规则（glob → 编码名），按插入顺序匹配，先命中者生效；可为 null
     * @throws IllegalArgumentException 编码名不受支持或 glob 非法
     */
    public static FileEncodingPolicy of(List<String> candidates, Map<String, String> rules) {
        Builder b = builder();
        if (candidates != null) {
            b.candidates.clear();
            for (String name : candidates) {
                b.candidate(Charset.forName(name.trim()));
            }
        }
        if (rules != null) {
            for (Map.Entry<String, String> e : rules.entrySet()) {
                b.rule(e.getKey(), Charset.forName(e.getValue().trim()));
            }
        }
        return b.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 候选编码（按顺序尝试）。 */
    public List<Charset> getCandidates() {
        return candidates;
    }

    /** 路径规则（按顺序匹配）。 */
    public List<Rule> getRules() {
        return rules;
    }

    /**
     * 路径命中的规则编码；没有命中返回 null。
     *
     * @param path 相对工作区（或挂载根）的路径，分隔符用 '/' 或系统分隔符均可；null 视为不命中
     */
    public Charset ruleFor(String path) {
        if (path == null || rules.isEmpty()) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        for (Rule rule : rules) {
            if (rule.matches(normalized)) {
                return rule.charset;
            }
        }
        return null;
    }


    private static List<Charset> defaultCandidates() {
        List<Charset> list = new ArrayList<>();
        Charset gbk = TextEncodings.charsetOrNull("GBK");
        if (gbk != null) {
            list.add(gbk);
        }
        Charset system = systemDefaultMultiByte();
        if (system != null && !list.contains(system)) {
            list.add(system);
        }
        return list;
    }

    /** 系统默认编码（JDK 17+ 的 native.encoding，JDK 8 的 sun.jnu.encoding）是 GBK 以外的多字节非 Unicode 编码时返回它。 */
    static Charset systemDefaultMultiByte() {
        String name = System.getProperty("native.encoding");
        if (name == null || name.isEmpty()) {
            name = System.getProperty("sun.jnu.encoding");
        }
        if (name == null || name.isEmpty()) {
            return null;
        }
        Charset cs = TextEncodings.charsetOrNull(name);
        if (cs == null || cs.name().startsWith("UTF-") || StandardCharsets.UTF_8.equals(cs) || !cs.canEncode()) {
            return null;
        }
        if (cs.newEncoder().maxBytesPerChar() <= 1.0f || !TextEncodings.isAsciiCompatible(cs)) {
            return null;
        }
        return cs;
    }

    @Override
    public String toString() {
        return "FileEncodingPolicy{candidates=" + candidates + ", rules=" + rules + "}";
    }

    /** 路径规则：glob → 编码。不含 '/' 的 glob 按文件名匹配；以 "**&#47;" 开头的 glob 也匹配根目录下的文件。 */
    public static final class Rule {
        private final String glob;
        private final Charset charset;
        private final PathMatcher matcher;
        private final PathMatcher rootMatcher;
        private final boolean byName;

        Rule(String glob, Charset charset) {
            if (glob == null || glob.trim().isEmpty() || charset == null) {
                throw new IllegalArgumentException("glob and charset are required");
            }
            this.glob = glob.trim().replace('\\', '/');
            this.charset = charset;
            this.byName = this.glob.indexOf('/') < 0;
            this.matcher = FileSystems.getDefault().getPathMatcher("glob:" + this.glob);
            this.rootMatcher = this.glob.startsWith("**/")
                    ? FileSystems.getDefault().getPathMatcher("glob:" + this.glob.substring(3))
                    : null;
        }

        public String getGlob() {
            return glob;
        }

        public Charset getCharset() {
            return charset;
        }

        boolean matches(String path) {
            try {
                if (byName) {
                    int slash = path.lastIndexOf('/');
                    return matcher.matches(Paths.get(slash < 0 ? path : path.substring(slash + 1)));
                }
                return matcher.matches(Paths.get(path)) || (rootMatcher != null && rootMatcher.matches(Paths.get(path)));
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public String toString() {
            return glob + "=" + charset.name();
        }
    }

    public static final class Builder {
        private final List<Charset> candidates = new ArrayList<>(defaultCandidates());
        private final List<Rule> rules = new ArrayList<>();

        private Builder() {
        }

        /** 清空候选编码（之后不自动识别，除非再加候选）。 */
        public Builder noCandidates() {
            candidates.clear();
            return this;
        }

        /** 追加候选编码（已存在则忽略）。 */
        public Builder candidate(Charset charset) {
            if (charset != null && !candidates.contains(charset)) {
                candidates.add(charset);
            }
            return this;
        }

        /** 追加路径规则（如 {@code rule("**&#47;*.properties", StandardCharsets.ISO_8859_1)}）。 */
        public Builder rule(String glob, Charset charset) {
            rules.add(new Rule(glob, charset));
            return this;
        }

        public FileEncodingPolicy build() {
            return new FileEncodingPolicy(candidates, rules);
        }
    }
}
